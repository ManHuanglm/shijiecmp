package com.shiping.desktop.ui.common

import com.shiping.desktop.data.download.DesktopDownloadRecord
import com.shiping.desktop.di.AppContainer
import com.shiping.desktop.util.AppLog
import com.shiping.shared.download.DownloadStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.File

/** 桌面下载任务流：引擎 StateFlow 映射为跨端 UI 模型 */
actual fun observeDownloadTasks(): Flow<List<DownloadTaskUi>> =
    AppContainer.get().downloadEngine.tasks.map { list -> list.map { it.toUi() } }

/** 暂停桌面下载任务 */
actual fun pauseDownloadTask(contentId: String) =
    AppContainer.get().downloadEngine.pause(contentId)

/** 恢复/重试桌面下载任务 */
actual fun resumeDownloadTask(contentId: String) =
    AppContainer.get().downloadEngine.resume(contentId)

/** 删除桌面下载任务（含本地产物） */
actual fun deleteDownloadTask(contentId: String) =
    AppContainer.get().downloadEngine.delete(contentId)

/** 桌面打开下载产物所在文件夹（Windows 资源管理器定位选中，其余打开父目录） */
actual fun openDownloadedFile(contentId: String) {
    val path = AppContainer.get().downloadEngine.getById(contentId)?.localPath
        ?.takeIf { it.isNotBlank() } ?: return
    runCatching {
        val file = File(path)
        if (System.getProperty("os.name").lowercase().contains("win")) {
            ProcessBuilder("explorer.exe", "/select,", file.absolutePath).start()
        } else {
            java.awt.Desktop.getDesktop().open(file.parentFile)
        }
    }.onFailure { AppLog.w("Downloads", "打开所在文件夹失败: ${it.message}") }
}

/** 桌面剧集活跃下载判定（排队/下载中/完成算活跃；暂停/失败可重新入队） */
actual suspend fun isEpisodeDownloadActive(
    vodId: Int,
    sourceUrl: String,
    episodeName: String,
): Boolean {
    val record = AppContainer.get().downloadEngine
        .getById(episodeDownloadContentId(vodId, sourceUrl, episodeName))
    return when (record?.status) {
        DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING, DownloadStatus.COMPLETED -> true
        else -> false
    }
}

/** 桌面完成态提示 */
actual val downloadCompletedHint: String = "已保存至 downloads 文件夹"

/** 桌面产物即本地文件（mpv 直接播放），无「转 MP4 置入相册」需求 */
actual val supportsConvertToMp4: Boolean = false

/** 桌面不支持置换（UI 不显示按钮，不会被调用） */
actual suspend fun convertDownloadToMp4(contentId: String) = Unit

/** 「仅 WiFi 下载」对桌面不生效（无移动网络约束） */
actual val downloadWifiOnlyApplicable: Boolean = false

/** 下载设置变化：补足/收敛 worker 使新并发任务数即时生效 */
actual fun notifyDownloadSettingsChanged() = AppContainer.get().downloadEngine.kick()

/** 引擎记录 → 跨端 UI 模型 */
private fun DesktopDownloadRecord.toUi(): DownloadTaskUi = DownloadTaskUi(
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
