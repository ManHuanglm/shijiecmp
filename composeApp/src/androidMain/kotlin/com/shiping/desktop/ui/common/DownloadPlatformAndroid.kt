package com.shiping.desktop.ui.common

import android.content.Intent
import com.shiping.app.DirectPlaybackActivity
import com.shiping.app.ShipingApp
import com.shiping.app.data.local.AppDatabase
import com.shiping.app.data.model.DownloadEntity
import com.shiping.shared.download.DownloadStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Android 下载任务流：Room downloads 表实时流映射为跨端 UI 模型 */
actual fun observeDownloadTasks(): Flow<List<DownloadTaskUi>> =
    AppDatabase.getInstance(ShipingApp.context).downloadDao()
        .observeAll().map { list -> list.map { it.toUi() } }

/** 暂停 Android 下载任务 */
actual fun pauseDownloadTask(contentId: String) =
    com.shiping.app.data.download.DownloadEngine.pause(contentId)

/** 恢复/重试 Android 下载任务 */
actual fun resumeDownloadTask(contentId: String) =
    com.shiping.app.data.download.DownloadEngine.resume(contentId)

/** 删除 Android 下载任务（含相册产物） */
actual fun deleteDownloadTask(contentId: String) =
    com.shiping.app.data.download.DownloadEngine.delete(contentId)

/** Android 打开本地播放（DirectPlaybackActivity 复用，media3 原生支持 content://） */
actual fun openDownloadedFile(contentId: String) {
    val dao = AppDatabase.getInstance(ShipingApp.context).downloadDao()
    CoroutineScope(Dispatchers.Main.immediate).launch {
        val path = withContext(Dispatchers.IO) {
            dao.getById(contentId)?.localPath?.takeIf { it.isNotBlank() }
        } ?: return@launch
        runCatching {
            ShipingApp.context.startActivity(
                Intent(ShipingApp.context, DirectPlaybackActivity::class.java).apply {
                    putExtra(DirectPlaybackActivity.EXTRA_URL, path)
                    putExtra(DirectPlaybackActivity.EXTRA_TITLE, "本地播放")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
        }
    }
}

/** Android 剧集活跃下载判定（排队/下载中/完成算活跃；暂停/失败可重新入队） */
actual suspend fun isEpisodeDownloadActive(
    vodId: Int,
    sourceUrl: String,
    episodeName: String,
): Boolean {
    val record = AppDatabase.getInstance(ShipingApp.context).downloadDao()
        .getById(episodeDownloadContentId(vodId, sourceUrl, episodeName))
    return when (record?.status) {
        DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING, DownloadStatus.COMPLETED -> true
        else -> false
    }
}

/** Android 完成态提示 */
actual val downloadCompletedHint: String = "已保存至应用下载目录，可「转MP4」置入相册"

/** Android 支持置换：已下载产物转封装 MP4 置入相册 */
actual val supportsConvertToMp4: Boolean = true

/**
 * 置换：本地产物（HLS 拼接 TS / 直链文件）经 Transformer 转封装为 MP4
 * （H.264/AAC 无损不重编码，本地文件无网络环节）后登记进相册，
 * 任务 localPath 更新为相册地址，源文件删除释放空间。
 */
actual suspend fun convertDownloadToMp4(contentId: String) {
    val dao = AppDatabase.getInstance(ShipingApp.context).downloadDao()
    val task = dao.getById(contentId) ?: throw IllegalStateException("任务不存在")
    val source = java.io.File(task.localPath)
    if (task.localPath.startsWith("content://") || !source.exists()) {
        throw IllegalStateException("产物已置入相册或文件不存在")
    }
    val galleryUri = withContext(Dispatchers.IO) {
        val target = java.io.File(ShipingApp.context.cacheDir, "cvt_${System.nanoTime()}.mp4")
        try {
            transformToMp4File(source, target)
            val baseName = com.shiping.app.data.download.GalleryExporter
                .sanitizeFileName(task.title.ifBlank { "video_${System.currentTimeMillis()}" })
            com.shiping.app.data.download.GalleryExporter.saveToGallery(
                ShipingApp.context, target, baseName, "mp4",
            )
        } finally {
            runCatching { target.delete() }
        }
    }
    dao.updateLocalPath(contentId, galleryUri)
    runCatching { source.delete() }
}

/**
 * 本地媒体文件转封装为 MP4（Transformer + 内置 MP4 封装器，纯转封装不重编码）。
 * start 需在带 Looper 的线程调用；本地文件转换快，无进度回调需求。
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private suspend fun transformToMp4File(source: java.io.File, target: java.io.File) =
    withContext(Dispatchers.Main.immediate) {
        val transformer = androidx.media3.transformer.Transformer.Builder(ShipingApp.context)
            .setMuxerFactory(androidx.media3.transformer.InAppMuxer.Factory.Builder().build())
            .build()
        val editedItem = androidx.media3.transformer.EditedMediaItem.Builder(
            androidx.media3.common.MediaItem.fromUri(android.net.Uri.fromFile(source)),
        ).build()
        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            transformer.addListener(object : androidx.media3.transformer.Transformer.Listener {
                override fun onCompleted(
                    composition: androidx.media3.transformer.Composition,
                    exportResult: androidx.media3.transformer.ExportResult,
                ) {
                    if (cont.isActive) cont.resume(Unit)
                }

                override fun onError(
                    composition: androidx.media3.transformer.Composition,
                    exportResult: androidx.media3.transformer.ExportResult,
                    exportException: androidx.media3.transformer.ExportException,
                ) {
                    if (cont.isActive) cont.resumeWithException(exportException)
                }
            })
            cont.invokeOnCancellation { runCatching { transformer.cancel() } }
            runCatching { transformer.start(editedItem, target.absolutePath) }
                .onFailure { if (cont.isActive) cont.resumeWithException(it) }
        }
    }

/** 「仅 WiFi 下载」对 Android 生效 */
actual val downloadWifiOnlyApplicable: Boolean = true

/** 下载设置变化：唤醒队列重新评估（如关闭「仅 WiFi」后继续排队任务） */
actual fun notifyDownloadSettingsChanged() =
    com.shiping.app.data.download.DownloadEngine.onSettingsChanged()

/** Room 实体 → 跨端 UI 模型 */
private fun DownloadEntity.toUi(): DownloadTaskUi = DownloadTaskUi(
    contentId = contentId,
    title = title,
    status = status,
    progress = progress,
    bytesDownloaded = bytesDownloaded,
    totalBytes = totalBytes,
    speedBytesPerSecond = speedBytesPerSecond,
    errorMessage = errorMessage,
    localPath = localPath,
)
