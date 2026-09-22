package com.shiping.app.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.shiping.app.data.model.ApiSourceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ApiSourceDao {

    @Query("SELECT * FROM api_sources ORDER BY sortOrder ASC, createdAt DESC")
    fun getAll(): Flow<List<ApiSourceEntity>>

    @Query("SELECT * FROM api_sources ORDER BY sortOrder ASC, createdAt DESC")
    suspend fun getAllList(): List<ApiSourceEntity>

    @Query("SELECT * FROM api_sources WHERE enabled = 1 ORDER BY sortOrder ASC, createdAt DESC")
    fun getEnabled(): Flow<List<ApiSourceEntity>>

    @Query("SELECT * FROM api_sources WHERE id = :id")
    suspend fun getById(id: Long): ApiSourceEntity?

    /** 查询入参地址中已存在于库内的 URL（CMS 源批量导入去重用） */
    @Query("SELECT url FROM api_sources WHERE url IN (:urls)")
    suspend fun findExistingUrls(urls: List<String>): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(source: ApiSourceEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(sources: List<ApiSourceEntity>)

    @Update
    suspend fun update(source: ApiSourceEntity)

    @Delete
    suspend fun delete(source: ApiSourceEntity)

    @Query("DELETE FROM api_sources WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    /** 按站点类型删除（0/1=CMS 接口源，3=爬虫源），爬虫源导入整体替换用 */
    @Query("DELETE FROM api_sources WHERE siteType = :siteType")
    suspend fun deleteBySiteType(siteType: Int)

    @Query("DELETE FROM api_sources")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM api_sources")
    suspend fun count(): Int
}
