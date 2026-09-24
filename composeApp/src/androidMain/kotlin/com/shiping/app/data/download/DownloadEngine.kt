package com.shiping.app.data.download

import android.os.SystemClock
import com.shiping.app.ShipingApp
import com.shiping.app.data.local.AppDatabase
import com.shiping.app.data.model.DownloadEntity
import com.shiping.app.util.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 下载引擎：Room downloads 表驱动的顺序队列。
 * - 队列任务逐个经 [GalleryExporter] 导出相册（Transformer 转封装不支持断点，暂停即取消本次导出，续传从头重来）
 * - 进程重启时把残留「下载中」任务置失败；重启后自动继续队列中的任务
 * - 删除任务同步删除相册中已导出的 MP4
 */
object DownloadEngine {

    private const val TAG = "DownloadEngine"

    /** 进度落库节流：进度百分比步进 ≥1 才写（一次任务至多 100 次） */
    private const val PROGRESS_MIN_STEP = 1

    /** 速度采样间隔（毫秒） */
    private const val SPEED_TICK_MS = 800L

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val dao get() = AppDatabase.getInstance(ShipingApp.context).downloadDao()

    /** 当前队列执行任务（单 worker） */
    private var worker: Job? = null

    /** 正在导出的任务 ID（暂停/删除判断取消对象用） */
    @Volatile
    private var currentContentId: String? = null

    /** 启动初始化：清理上次中断任务 + 自动恢复队列 */
    fun init() {
        ioScope.launch {
            runCatching {
                dao.resetStatuses(
                    listOf(DownloadEntity.STATUS_DOWNLOADING),
                    DownloadEntity.STATUS_FAILED,
                    "下载被中断（应用退出）",
                )
            }.onFailure { AppLog.w(TAG, "中断任务清理失败: ${it.message}") }
            kick()
        }
    }

    /**
     * 入队下载任务（同任务已完成/排队中则拒绝，暂停/失败则重置重下）。
     * @return 用户提示文案
     */
    suspend fun enqueue(entity: DownloadEntity): String = withContext(Dispatchers.IO) {
        val existing = dao.getById(entity.contentId)
        when {
            existing != null && existing.status == DownloadEntity.STATUS_COMPLETED -> "该集已在下载列表中"
            existing != null && existing.status in intArrayOf(
                DownloadEntity.STATUS_DOWNLOADING,
                DownloadEntity.STATUS_QUEUED,
            ) -> "该集正在下载中"
            else -> {
                dao.insert(entity)
                kick()
                "已加入下载队列"
            }
        }
    }

    /** 暂停任务：进行中则取消本次导出（Transformer 无断点，续传从头重来），排队中仅改状态 */
    fun pause(contentId: String) {
        ioScope.launch {
            if (currentContentId == contentId) worker?.cancel()
            runCatching { dao.updateStatus(contentId, DownloadEntity.STATUS_PAUSED) }
        }
    }

    /** 恢复/重试任务：重新排队（从头下载）并唤醒队列 */
    fun resume(contentId: String) {
        ioScope.launch {
            runCatching { dao.updateStatus(contentId, DownloadEntity.STATUS_QUEUED) }
            kick()
        }
    }

    /** 删除任务：取消进行中导出 + 删除相册 MP4 + 删库记录 */
    fun delete(contentId: String) {
        ioScope.launch {
            if (currentContentId == contentId) worker?.cancel()
            runCatching {
                dao.getById(contentId)?.localPath?.takeIf { it.isNotBlank() }?.let { localPath ->
                    GalleryExporter.deleteFromGallery(ShipingApp.context, localPath)
                }
            }.onFailure { AppLog.w(TAG, "相册文件删除失败: ${it.message}") }
            runCatching { dao.delete(contentId) }
        }
    }

    // region 队列执行

    /** 唤醒队列（幂等：已有 worker 运行则忽略） */
    private fun kick() {
        if (worker?.isActive == true) return
        synchronized(this) {
            if (worker?.isActive == true) return
            worker = ioScope.launch { runQueue() }
        }
    }

    private suspend fun runQueue() {
        while (true) {
            val task = dao.getAll().firstOrNull { it.status == DownloadEntity.STATUS_QUEUED } ?: break
            dao.updateStatus(task.contentId, DownloadEntity.STATUS_DOWNLOADING)
            currentContentId = task.contentId
            try {
                performDownload(task)
            } catch (e: CancellationException) {
                // 暂停/删除取消：置为暂停态（NonCancellable 保证取消上下文中落库成功）
                withContext(NonCancellable) {
                    runCatching { dao.updateStatus(task.contentId, DownloadEntity.STATUS_PAUSED) }
                }
                throw e
            } catch (t: Throwable) {
                AppLog.e(TAG, "下载失败 ${task.title}: ${t.message}", t)
                runCatching {
                    dao.updateFailed(task.contentId, DownloadEntity.STATUS_FAILED, t.message ?: "未知错误")
                }
            } finally {
                if (currentContentId == task.contentId) currentContentId = null
            }
        }
    }

    /** 导出单集到相册（带节流进度落库与速度估算） */
    private suspend fun performDownload(task: DownloadEntity) {
        val writer = ProgressWriter(task.contentId)
        val result = GalleryExporter.exportToGallery(
            ShipingApp.context,
            task.url,
            task.title,
            parseHeaders(task.headersJson),
            onProgress = writer::onPercent,
            onBytes = writer::onBytes,
        )
        dao.updateCompleted(
            task.contentId,
            DownloadEntity.STATUS_COMPLETED,
            result.localPath,
            result.sizeBytes,
            System.currentTimeMillis(),
        )
    }

    /**
     * 进度聚合写入器：百分比步进节流落库；字节快照仅用于速度估算
     * （HLS 转封装无字节语义，speed/total 为 0 时 UI 隐藏对应展示）。
     */
    private class ProgressWriter(private val contentId: String) {

        private var percent = 0
        private var bytes = 0L
        private var total = 0L
        private var speed = 0L
        private var lastTickMs = 0L
        private var lastTickBytes = 0L
        private var lastWritePercent = -1

        fun onPercent(value: Int) {
            percent = value
            if (value - lastWritePercent >= PROGRESS_MIN_STEP) {
                lastWritePercent = value
                write()
            }
        }

        fun onBytes(bytesDone: Long, totalBytes: Long) {
            bytes = bytesDone
            total = totalBytes
            val now = SystemClock.elapsedRealtime()
            if (lastTickMs > 0L && now - lastTickMs >= SPEED_TICK_MS) {
                speed = (bytesDone - lastTickBytes) * 1000 / (now - lastTickMs)
            }
            lastTickMs = now
            lastTickBytes = bytesDone
        }

        private fun write() {
            ioScope.launch {
                runCatching {
                    dao.updateProgress(
                        contentId,
                        DownloadEntity.STATUS_DOWNLOADING,
                        percent,
                        bytes,
                        total,
                        speed,
                    )
                }
            }
        }
    }

    // endregion

    /** 请求头 JSON 反序列化（入队时由 JSONObject 序列化） */
    private fun parseHeaders(json: String): Map<String, String> = runCatching {
        if (json.isBlank()) {
            emptyMap()
        } else {
            val obj = JSONObject(json)
            obj.keys().asSequence().associateWith { obj.optString(it) }
        }
    }.getOrDefault(emptyMap())
}
