package com.shiping.desktop.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.shiping.desktop.data.download.DesktopDownloadRecord
import com.shiping.desktop.data.prefs.DesktopPrefs
import com.shiping.desktop.di.AppContainer
import com.shiping.desktop.ui.AppViewModel
import com.shiping.desktop.util.AppLog
import com.google.gson.Gson
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

/** 桌面文件选择：EDT 阻塞弹出（与原设置页行为一致），取消返回 null */
actual suspend fun pickImportTextFile(title: String): String? {
    val chooser = JFileChooser().apply {
        dialogTitle = title
        fileFilter = FileNameExtensionFilter("文本文件 (txt)", "txt")
    }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
        chooser.selectedFile.absolutePath
    } else {
        null
    }
}

/** 桌面剪贴板：AWT 系统剪贴板 */
actual fun copyTextToClipboard(text: String) {
    runCatching {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    }
}

/** 桌面日志快照：AppLog 环形缓冲映射为跨端条目 */
actual fun snapshotLogEntries(): List<LogEntryView> =
    AppLog.snapshotEntries().map { LogEntryView(it.level, it.text) }

/** 清空桌面日志缓冲 */
actual fun clearLogs() = AppLog.clear()

/** 桌面支持剧集下载（经 DesktopDownloadEngine 落盘到数据目录 downloads） */
actual val isEpisodeDownloadSupported: Boolean = true

/** 提交剧集下载：构造下载记录入队（请求头序列化 JSON 落库） */
actual suspend fun submitEpisodeDownload(request: EpisodeDownloadRequest): String? {
    val engine = AppContainer.get().downloadEngine
    val name = request.episodeName.ifBlank { "第1集" }
    val record = DesktopDownloadRecord(
        contentId = episodeDownloadContentId(request.vodId, request.sourceUrl, name),
        title = "${request.title}·$name",
        vodPic = request.vodPic,
        url = request.playUrl,
        headersJson = runCatching { Gson().toJson(request.headers) }.getOrDefault(""),
        createdAt = System.currentTimeMillis(),
    )
    return runCatching { engine.enqueue(record) }
        .getOrElse { "加入下载队列失败：${it.message ?: "未知错误"}" }
}

/** 桌面不支持扫码导入（无摄像头） */
actual fun launchConfigScanner(): Boolean = false

/** 自定义存储路径页（桌面）：主数据目录 + 下载目录双条目，点击选择目录 */
@Composable
actual fun StoragePathPageContent(app: AppViewModel) {
    val container = AppContainer.get()
    val defaultDir = File(System.getProperty("user.home"), ".shiping-desktop")
    var mainPath by remember { mutableStateOf(container.dataDir.absolutePath) }
    var downloadPath by remember {
        mutableStateOf(container.prefs.getString(DesktopPrefs.KEY_DOWNLOAD_DIR, "")
            .takeIf { it.isNotBlank() }
            ?: File(container.dataDir, "downloads").absolutePath)
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        StoragePathItem(title = "主数据存储路径", path = mainPath) {
            pickDirectory("选择主数据存储目录", mainPath)?.let { path ->
                AppContainer.writeDataDirOverride(defaultDir, path)
                mainPath = path
                app.showMessage("已保存主数据存储路径，重启应用后生效")
            }
        }
        StoragePathItem(title = "下载存储路径", path = downloadPath) {
            pickDirectory("选择下载存储目录", downloadPath)?.let { path ->
                container.prefs.put(DesktopPrefs.KEY_DOWNLOAD_DIR, path)
                downloadPath = path
                app.showMessage("下载存储路径已更新，新下载任务即时生效")
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedButton(onClick = {
            AppContainer.writeDataDirOverride(defaultDir, "")
            container.prefs.put(DesktopPrefs.KEY_DOWNLOAD_DIR, null)
            mainPath = defaultDir.absolutePath
            downloadPath = File(defaultDir, "downloads").absolutePath
            app.showMessage("已恢复默认路径，主数据目录重启应用后生效")
        }) { Text("恢复默认") }
    }
}

/** 存储路径条目：标题 + 当前路径两行，整行可点击更改 */
@Composable
private fun StoragePathItem(title: String, path: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 14.dp),
    ) {
        Text(text = title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = path,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 桌面目录选择（EDT 阻塞弹出），取消返回 null */
private fun pickDirectory(title: String, current: String): String? {
    val chooser = JFileChooser(current).apply {
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        dialogTitle = title
    }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
        chooser.selectedFile.absolutePath
    } else {
        null
    }
}
