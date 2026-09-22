package com.shiping.desktop.ui.common

import androidx.compose.runtime.Composable
import com.shiping.desktop.ui.AppViewModel

/** 日志条目视图（跨端统一：平台日志实现映射，日志页筛选/着色/复制用） */
data class LogEntryView(
    val level: Char,
    val text: String,
)

/** 选择本地 txt 文件并返回绝对路径（取消返回 null；桌面 JFileChooser，Android 待接 SAF） */
expect suspend fun pickImportTextFile(title: String): String?

/** 复制文本到系统剪贴板 */
expect fun copyTextToClipboard(text: String)

/** 日志缓冲快照（结构化条目，日志页按级别筛选展示） */
expect fun snapshotLogEntries(): List<LogEntryView>

/** 清空日志缓冲 */
expect fun clearLogs()

/** 自定义存储路径页（桌面专属：目录选择 + 覆盖持久化重启生效；Android 显示占位说明） */
@Composable
expect fun StoragePathPageContent(app: AppViewModel)
