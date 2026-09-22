package com.shiping.desktop.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
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
import com.shiping.desktop.di.AppContainer
import com.shiping.desktop.ui.AppViewModel
import com.shiping.desktop.util.AppLog
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

/** 自定义存储路径页（桌面）：目录选择覆盖持久化，重启生效 */
@Composable
actual fun StoragePathPageContent(app: AppViewModel) {
    val defaultDir = File(System.getProperty("user.home"), ".shiping-desktop")
    var currentPath by remember { mutableStateOf(AppContainer.get().dataDir.absolutePath) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("当前数据目录", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            currentPath,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                runCatching {
                    val chooser = JFileChooser(currentPath).apply {
                        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                        dialogTitle = "选择数据目录"
                    }
                    if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                        val path = chooser.selectedFile.absolutePath
                        AppContainer.writeDataDirOverride(defaultDir, path)
                        currentPath = path
                        app.showMessage("已保存自定义数据目录，重启应用后生效")
                    }
                }.onFailure { app.showMessage("选择目录失败：${it.message}") }
            }) { Text("更改目录…") }
            OutlinedButton(onClick = {
                AppContainer.writeDataDirOverride(defaultDir, "")
                currentPath = defaultDir.absolutePath
                app.showMessage("已恢复默认数据目录，重启应用后生效")
            }) { Text("恢复默认") }
        }
    }
}
