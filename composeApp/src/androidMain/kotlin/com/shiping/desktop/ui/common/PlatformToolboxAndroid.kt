package com.shiping.desktop.ui.common

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.shiping.app.ShipingApp
import com.shiping.app.data.download.DownloadEngine
import com.shiping.app.data.model.DownloadEntity
import com.shiping.app.ui.scan.ScanActivity
import com.shiping.app.util.AppLog
import com.shiping.desktop.ui.AppViewModel
import org.json.JSONObject

/** Android 文件选择：待接 SAF（M5），当前返回 null 占位 */
actual suspend fun pickImportTextFile(title: String): String? = null

/** Android 剪贴板：系统 ClipboardManager */
actual fun copyTextToClipboard(text: String) {
    runCatching {
        val cm = ShipingApp.context.getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("text", text))
    }
}

/** Android 日志快照：AppLog 内存缓冲映射为跨端条目 */
actual fun snapshotLogEntries(): List<LogEntryView> =
    AppLog.snapshot().map { entry ->
        LogEntryView(
            level = entry.level.label.first(),
            text = buildString {
                append(entry.level.label)
                append('/')
                append(entry.tag)
                append(": ")
                append(entry.message)
                entry.throwableText?.let { append('\n').append(it) }
            },
        )
    }

/** 清空 Android 日志缓冲 */
actual fun clearLogs() = AppLog.clear()

/** 自定义存储路径页（Android）：桌面专属功能占位说明 */
@Composable
actual fun StoragePathPageContent(app: AppViewModel) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            "自定义存储路径为桌面版专属功能",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Android 支持剧集下载（经 DownloadEngine 导出系统相册） */
actual val isEpisodeDownloadSupported: Boolean = true

/** 提交剧集下载：构造下载实体入队（请求头序列化 JSON 落库） */
actual suspend fun submitEpisodeDownload(request: EpisodeDownloadRequest): String? {
    val entity = DownloadEntity(
        contentId = episodeDownloadContentId(
            request.vodId,
            request.sourceUrl,
            request.episodeName.ifBlank { "第1集" },
        ),
        title = request.episodeName.ifBlank { request.title }
            .let { name -> "${request.title}·$name" },
        vodPic = request.vodPic,
        url = request.playUrl,
        headersJson = runCatching { JSONObject(request.headers).toString() }.getOrDefault(""),
    )
    return runCatching { DownloadEngine.enqueue(entity) }
        .getOrElse { "加入下载队列失败：${it.message ?: "未知错误"}" }
}

/** 发起扫码导入：启动 ScanActivity（识别成功后页内自动导入并提示） */
actual fun launchConfigScanner(): Boolean = runCatching {
    ShipingApp.context.startActivity(
        Intent(ShipingApp.context, ScanActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
    true
}.getOrDefault(false)
