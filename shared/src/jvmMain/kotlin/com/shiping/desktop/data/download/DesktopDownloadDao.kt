package com.shiping.desktop.data.download

import com.shiping.desktop.data.db.DesktopDb

/**
 * 桌面下载任务 DAO（SQLite，方法同步阻塞，由引擎在 IO 协程中调用；
 * DesktopDb 单连接内部已串行化，无需额外加锁）。
 */
class DesktopDownloadDao(private val db: DesktopDb) {

    companion object {
        /** 查询列序（fromResultSet 按名取列，此处仅保证新增列齐全） */
        private val COLUMNS = "contentId, title, vodPic, url, quality, status, progress, " +
            "bytesDownloaded, totalBytes, speedBytesPerSecond, errorMessage, headersJson, " +
            "createdAt, completedAt, localPath, completedSegments"
    }

    /** 全部任务（创建时间倒序，与 Android 下载列表一致） */
    fun getAll(): List<DesktopDownloadRecord> = db.query(
        "SELECT $COLUMNS FROM downloads ORDER BY createdAt DESC",
    ) { DesktopDownloadRecord.fromResultSet(it) }

    /** 按 ID 查单条 */
    fun getById(contentId: String): DesktopDownloadRecord? = db.query(
        "SELECT $COLUMNS FROM downloads WHERE contentId = ? LIMIT 1",
        listOf(contentId),
    ) { DesktopDownloadRecord.fromResultSet(it) }.firstOrNull()

    /** 插入或覆盖（同 ID 重下场景） */
    fun insert(record: DesktopDownloadRecord) {
        db.exec(
            "INSERT OR REPLACE INTO downloads ($COLUMNS) VALUES " +
                "(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            listOf(
                record.contentId,
                record.title,
                record.vodPic,
                record.url,
                record.quality,
                record.status,
                record.progress,
                record.bytesDownloaded,
                record.totalBytes,
                record.speedBytesPerSecond,
                record.errorMessage,
                record.headersJson,
                record.createdAt,
                record.completedAt,
                record.localPath,
                record.completedSegments,
            ),
        )
    }

    /** 更新状态 */
    fun updateStatus(contentId: String, status: Int) {
        db.exec("UPDATE downloads SET status = ? WHERE contentId = ?", listOf(status, contentId))
    }

    /** 进度落库（状态/百分比/字节/总数/速度） */
    fun updateProgress(
        contentId: String,
        status: Int,
        progress: Int,
        bytesDownloaded: Long,
        totalBytes: Long,
        speed: Long,
    ) {
        db.exec(
            "UPDATE downloads SET status = ?, progress = ?, bytesDownloaded = ?, " +
                "totalBytes = ?, speedBytesPerSecond = ? WHERE contentId = ?",
            listOf(status, progress, bytesDownloaded, totalBytes, speed, contentId),
        )
    }

    /** 置失败（含原因） */
    fun updateFailed(contentId: String, status: Int, error: String?) {
        db.exec(
            "UPDATE downloads SET status = ?, errorMessage = ? WHERE contentId = ?",
            listOf(status, error, contentId),
        )
    }

    /** 置完成（产物路径 + 字节数 + 时间，进度归 100，分片续传计数清零） */
    fun updateCompleted(
        contentId: String,
        status: Int,
        localPath: String,
        bytes: Long,
        completedAt: Long,
    ) {
        db.exec(
            "UPDATE downloads SET status = ?, localPath = ?, bytesDownloaded = ?, " +
                "totalBytes = ?, progress = 100, completedAt = ?, completedSegments = 0 WHERE contentId = ?",
            listOf(status, localPath, bytes, bytes, completedAt, contentId),
        )
    }

    /** 分片级进度落库（HLS 断点续传：已完成分片数 + 累计字节；暂停/失败后重试从该分片继续） */
    fun updateSegmentProgress(contentId: String, completedSegments: Int, bytesDownloaded: Long) {
        db.exec(
            "UPDATE downloads SET completedSegments = ?, bytesDownloaded = ? WHERE contentId = ?",
            listOf(completedSegments, bytesDownloaded, contentId),
        )
    }

    /** 记录产物目标路径（首次下载时分配并落库，续传/重试稳定复用防重名漂移） */
    fun updateLocalPath(contentId: String, path: String) {
        db.exec("UPDATE downloads SET localPath = ? WHERE contentId = ?", listOf(path, contentId))
    }

    /** 删除任务记录 */
    fun delete(contentId: String) {
        db.exec("DELETE FROM downloads WHERE contentId = ?", listOf(contentId))
    }

    /** 指定状态批量置失败（进程中断清理） */
    fun resetStatuses(statuses: List<Int>, failedStatus: Int, error: String?) {
        if (statuses.isEmpty()) return
        val placeholders = statuses.joinToString(",") { "?" }
        db.exec(
            "UPDATE downloads SET status = ?, errorMessage = ? WHERE status IN ($placeholders)",
            listOf(failedStatus, error) + statuses,
        )
    }
}
