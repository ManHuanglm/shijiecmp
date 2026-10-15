package com.shiping.desktop.ui.common

import kotlinx.coroutines.flow.Flow

/**
 * 下载任务 UI 模型（双端共享下载管理页展示；平台引擎实体在 actual 内映射，不出各自源集）。
 */
data class DownloadTaskUi(
    /** 任务 ID（vodId|sourceUrl|episodeName） */
    val contentId: String,
    /** 展示标题（片名·集名） */
    val title: String,
    /** 状态：见 DownloadStatus */
    val status: Int,
    /** 进度百分比 0-100 */
    val progress: Int,
    /** 已下载字节数 */
    val bytesDownloaded: Long,
    /** 总字节数（0 表示未知，UI 隐藏总数展示） */
    val totalBytes: Long,
    /** 下载速度（字节/秒，0 隐藏） */
    val speedBytesPerSecond: Long,
    /** 失败原因 */
    val errorMessage: String?,
    /** 本地产物路径（空表示未完成） */
    val localPath: String,
)

/** 下载任务 ID 统一构造（双端 actual 入队/去重共用，格式勿改） */
fun episodeDownloadContentId(vodId: Int, sourceUrl: String, episodeName: String): String =
    "$vodId|$sourceUrl|$episodeName"

/** 不支持下载的播放地址：DASH 清单 / 本地 /hdr 包装流（DASH）；
 *  本地 /m3u8 过滤路由与 /proxy?do=m3u8 类清单代理按 HLS 正常下载，双端下载入口前置拦截 */
fun isUnsupportedDownloadUrl(url: String): Boolean {
    if (url.substringBefore('?').substringBefore('#').lowercase().endsWith(".mpd")) return true
    if (!url.startsWith("http://127.0.0.1:")) return false
    if (url.contains("/m3u8") || url.contains("do=m3u8")) return false
    return url.contains("/proxy") || url.contains("/hdr")
}

/** 观察全部下载任务（Android=Room Flow；桌面=引擎 StateFlow，collectAsState 双端通吃） */
expect fun observeDownloadTasks(): Flow<List<DownloadTaskUi>>

/** 暂停任务（进行中取消本次下载，无断点续传从头重来） */
expect fun pauseDownloadTask(contentId: String)

/** 恢复/重试任务（重新排队） */
expect fun resumeDownloadTask(contentId: String)

/** 删除任务（取消进行中 + 删除本地产物 + 删记录） */
expect fun deleteDownloadTask(contentId: String)

/** 打开完成任务的本地产物：Android 启动本地播放；桌面在资源管理器中定位文件 */
expect fun openDownloadedFile(contentId: String)

/** 「仅 WiFi 下载」开关是否对该平台生效（Android=true，桌面恒允许） */
expect val downloadWifiOnlyApplicable: Boolean

/** 下载设置变化后通知引擎（桌面补足/收敛 worker；Android 任务级现读无需处理） */
expect fun notifyDownloadSettingsChanged()

/** 剧集是否已有活跃下载任务（排队/下载中/完成视为活跃；暂停/失败可重新入队） */
expect suspend fun isEpisodeDownloadActive(vodId: Int, sourceUrl: String, episodeName: String): Boolean

/** 完成态存储位置提示（Android「已保存至应用下载目录」/桌面「已保存至 downloads 文件夹」） */
expect val downloadCompletedHint: String

/** 是否支持将已下载产物转封装 MP4 置入相册（Android=true；桌面产物即本地文件无此需求） */
expect val supportsConvertToMp4: Boolean

/**
 * 置换：将已下载产物（如 HLS 拼接的 TS）转封装为 MP4 并置入系统相册
 * （无损不重编码；成功后任务 localPath 更新为相册地址，源文件删除）。
 * 失败抛异常由 UI 提示；不支持的平台不会显示按钮也不会被调用。
 */
expect suspend fun convertDownloadToMp4(contentId: String)
