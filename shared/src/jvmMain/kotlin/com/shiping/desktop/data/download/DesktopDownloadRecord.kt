package com.shiping.desktop.data.download

import com.shiping.shared.download.DownloadStatus
import java.sql.ResultSet

/**
 * 桌面下载任务记录（SQLite downloads 表行，字段对齐 Android Room DownloadEntity）。
 */
data class DesktopDownloadRecord(
    /** 内容 ID（vodId|sourceUrl|episodeName），双端统一由 episodeDownloadContentId 构造 */
    val contentId: String,
    /** 展示标题（片名·集名） */
    val title: String,
    /** 视频封面 URL */
    val vodPic: String = "",
    /** 播放地址（已解析直链） */
    val url: String,
    /** 清晰度标签 */
    val quality: String = "",
    /** 状态：见 [DownloadStatus] */
    val status: Int = DownloadStatus.QUEUED,
    /** 进度百分比 0-100 */
    val progress: Int = 0,
    /** 已下载字节数 */
    val bytesDownloaded: Long = 0L,
    /** 总字节数（HLS 分片拼接无字节语义时为 0） */
    val totalBytes: Long = 0L,
    /** 下载速度（字节/秒） */
    val speedBytesPerSecond: Long = 0L,
    /** 失败原因 */
    val errorMessage: String? = null,
    /** 播放请求头 JSON（UA/Referer 防盗链），下载时注入 */
    val headersJson: String = "",
    /** 创建时间戳 */
    val createdAt: Long = 0L,
    /** 完成时间戳 */
    val completedAt: Long = 0L,
    /** 本地产物绝对路径，空表示未完成 */
    val localPath: String = "",
    /** 已完成分片数（HLS 分片级断点续传：重试从该分片继续追加） */
    val completedSegments: Int = 0,
) {
    companion object {

        /** 结果集行 → 记录（列序与 DesktopDownloadDao 查询列一致） */
        fun fromResultSet(rs: ResultSet): DesktopDownloadRecord = DesktopDownloadRecord(
            contentId = rs.getString("contentId"),
            title = rs.getString("title"),
            vodPic = rs.getString("vodPic"),
            url = rs.getString("url"),
            quality = rs.getString("quality"),
            status = rs.getInt("status"),
            progress = rs.getInt("progress"),
            bytesDownloaded = rs.getLong("bytesDownloaded"),
            totalBytes = rs.getLong("totalBytes"),
            speedBytesPerSecond = rs.getLong("speedBytesPerSecond"),
            errorMessage = rs.getString("errorMessage"),
            headersJson = rs.getString("headersJson"),
            createdAt = rs.getLong("createdAt"),
            completedAt = rs.getLong("completedAt"),
            localPath = rs.getString("localPath"),
            completedSegments = rs.getInt("completedSegments"),
        )
    }
}
