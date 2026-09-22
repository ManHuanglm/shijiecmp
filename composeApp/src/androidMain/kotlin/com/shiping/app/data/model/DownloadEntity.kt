package com.shiping.app.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 下载/缓存任务实体。
 * 与 ExoPlayer DownloadManager 中的 contentId（即视频 URL）一一对应。
 */
@Entity(tableName = "downloads")
data class DownloadEntity(
    /** 内容 ID，使用视频 URL 作为唯一标识 */
    @PrimaryKey val contentId: String,
    /** 视频标题 */
    val title: String,
    /** 视频封面 URL */
    val vodPic: String = "",
    /** 播放地址（可能是解析后的直链或原始地址） */
    val url: String,
    /** 清晰度标签，如 "720P"、"1080P" */
    val quality: String = "",
    /** 下载状态：0=队列中 1=下载中 2=已完成 3=失败 4=已暂停 */
    val status: Int = 0,
    /** 下载进度百分比 0-100 */
    val progress: Int = 0,
    /** 已下载字节数 */
    val bytesDownloaded: Long = 0L,
    /** 总字节数 */
    val totalBytes: Long = 0L,
    /** 下载速度（字节/秒） */
    val speedBytesPerSecond: Long = 0L,
    /** 失败原因 */
    val errorMessage: String? = null,
    /** 创建时间戳 */
    val createdAt: Long = System.currentTimeMillis(),
    /** 完成时间戳 */
    val completedAt: Long = 0L,
    /** 导出到相册后的本地地址（MediaStore content Uri 或文件路径），空表示未导出 */
    val localPath: String = "",
) {
    companion object {
        const val STATUS_QUEUED = 0
        const val STATUS_DOWNLOADING = 1
        const val STATUS_COMPLETED = 2
        const val STATUS_FAILED = 3
        const val STATUS_PAUSED = 4
    }
}
