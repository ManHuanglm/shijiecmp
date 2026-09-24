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

/** 剧集下载请求（已解析直链与请求头，平台下载器消费） */
data class EpisodeDownloadRequest(
    val vodId: Int,
    val sourceUrl: String,
    val title: String,
    val vodPic: String,
    val episodeName: String,
    val playUrl: String,
    val headers: Map<String, String>,
)

/** 平台是否支持剧集下载（Android 导出系统相册；桌面不支持） */
expect val isEpisodeDownloadSupported: Boolean

/** 提交剧集下载，返回提示文案（不支持的平台返回 null） */
expect suspend fun submitEpisodeDownload(request: EpisodeDownloadRequest): String?

/** 发起扫码导入 TVBox 配置（Android 打开扫码页自动导入；桌面不支持返回 false） */
expect fun launchConfigScanner(): Boolean
