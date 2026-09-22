package com.shiping.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.shiping.app.data.model.DownloadEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadDao {

    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    suspend fun getAll(): List<DownloadEntity>

    @Query("SELECT * FROM downloads WHERE contentId = :contentId LIMIT 1")
    suspend fun getById(contentId: String): DownloadEntity?

    @Query("SELECT * FROM downloads WHERE status = :status")
    fun observeByStatus(status: Int): Flow<List<DownloadEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(download: DownloadEntity)

    @Update
    suspend fun update(download: DownloadEntity)

    @Query("UPDATE downloads SET status = :status WHERE contentId = :contentId")
    suspend fun updateStatus(contentId: String, status: Int)

    @Query(
        "UPDATE downloads SET status = :status, progress = :progress, " +
            "bytesDownloaded = :bytesDownloaded, totalBytes = :totalBytes, " +
            "speedBytesPerSecond = :speed WHERE contentId = :contentId",
    )
    suspend fun updateProgress(
        contentId: String,
        status: Int,
        progress: Int,
        bytesDownloaded: Long,
        totalBytes: Long,
        speed: Long,
    )

    @Query("UPDATE downloads SET status = :status, errorMessage = :error WHERE contentId = :contentId")
    suspend fun updateFailed(contentId: String, status: Int, error: String?)

    @Query("UPDATE downloads SET status = :status, localPath = :localPath, bytesDownloaded = :bytes, totalBytes = :bytes, progress = 100, completedAt = :completedAt WHERE contentId = :contentId")
    suspend fun updateCompleted(
        contentId: String,
        status: Int,
        localPath: String,
        bytes: Long,
        completedAt: Long,
    )

    @Query("DELETE FROM downloads WHERE contentId = :contentId")
    suspend fun delete(contentId: String)

    @Query("DELETE FROM downloads WHERE contentId IN (:ids)")
    suspend fun deleteAll(ids: List<String>)

    @Query("DELETE FROM downloads")
    suspend fun clearAll()

    @Query("SELECT COALESCE(SUM(bytesDownloaded), 0) FROM downloads")
    suspend fun getTotalDownloadedBytes(): Long

    /** 将指定状态的任务统一置为失败（进程被杀后清理中断任务） */
    @Query("UPDATE downloads SET status = :failedStatus, errorMessage = :error WHERE status IN (:statuses)")
    suspend fun resetStatuses(statuses: List<Int>, failedStatus: Int, error: String?)

    @Query("SELECT COUNT(*) FROM downloads")
    suspend fun getCount(): Int
}
