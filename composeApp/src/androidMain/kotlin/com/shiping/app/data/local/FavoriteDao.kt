package com.shiping.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.shiping.app.data.model.FavoriteEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FavoriteDao {

    /** 观察全部收藏，按收藏时间倒序 */
    @Query("SELECT * FROM favorites ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<FavoriteEntity>>

    /** 观察是否已收藏 */
    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE vodId = :vodId AND sourceUrl = :sourceUrl)")
    fun observeIsFavorite(vodId: Int, sourceUrl: String): Flow<Boolean>

    /** 是否已收藏（一次性查询） */
    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE vodId = :vodId AND sourceUrl = :sourceUrl)")
    suspend fun exists(vodId: Int, sourceUrl: String): Boolean

    /** 插入或更新 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: FavoriteEntity)

    /** 删除单条 */
    @Query("DELETE FROM favorites WHERE vodId = :vodId AND sourceUrl = :sourceUrl")
    suspend fun delete(vodId: Int, sourceUrl: String)

    /** 清空全部 */
    @Query("DELETE FROM favorites")
    suspend fun clearAll()
}
