package com.shiping.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.shiping.app.data.model.PlayHistoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PlayHistoryDao {

    /** 观察全部播放记录，按最后观看时间倒序 */
    @Query("SELECT * FROM play_history ORDER BY watchedAt DESC")
    fun observeAll(): Flow<List<PlayHistoryEntity>>

    /** 插入或更新（同 vodId + sourceUrl 覆盖，不同源互不影响） */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: PlayHistoryEntity)

    /** 删除单条 */
    @Query("DELETE FROM play_history WHERE vodId = :vodId AND sourceUrl = :sourceUrl")
    suspend fun delete(vodId: Int, sourceUrl: String)

    /** 查询单条续播进度（无记录返回 null） */
    @Query("SELECT * FROM play_history WHERE vodId = :vodId AND sourceUrl = :sourceUrl LIMIT 1")
    suspend fun getByVod(vodId: Int, sourceUrl: String): PlayHistoryEntity?

    /** 清空全部 */
    @Query("DELETE FROM play_history")
    suspend fun clearAll()
}
