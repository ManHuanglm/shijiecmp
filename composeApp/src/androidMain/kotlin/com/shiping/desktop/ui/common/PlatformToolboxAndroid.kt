package com.shiping.desktop.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import android.content.ClipData
import android.content.ClipboardManager
import com.shiping.app.ShipingApp
import com.shiping.app.util.AppLog
import com.shiping.desktop.ui.AppViewModel

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
