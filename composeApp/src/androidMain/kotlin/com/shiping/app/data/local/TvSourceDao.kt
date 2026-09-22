package com.shiping.app.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.shiping.app.data.model.TvSourceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TvSourceDao {

    @Query("SELECT * FROM tv_sources ORDER BY sortOrder ASC, createdAt DESC")
    fun getAll(): Flow<List<TvSourceEntity>>

    @Query("SELECT * FROM tv_sources ORDER BY sortOrder ASC, createdAt DESC")
    suspend fun getAllList(): List<TvSourceEntity>

    @Query("SELECT * FROM tv_sources WHERE enabled = 1 ORDER BY sortOrder ASC, createdAt DESC")
    fun getEnabled(): Flow<List<TvSourceEntity>>

    @Query("SELECT * FROM tv_sources WHERE id = :id")
    suspend fun getById(id: Long): TvSourceEntity?

    /** 查询入参地址中已存在于库内的部分（去重导入用） */
    @Query("SELECT url FROM tv_sources WHERE url IN (:urls)")
    suspend fun findExistingUrls(urls: List<String>): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(source: TvSourceEntity): Long

    @Update
    suspend fun update(source: TvSourceEntity)

    @Delete
    suspend fun delete(source: TvSourceEntity)

    @Query("DELETE FROM tv_sources WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM tv_sources")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM tv_sources")
    suspend fun count(): Int
}
