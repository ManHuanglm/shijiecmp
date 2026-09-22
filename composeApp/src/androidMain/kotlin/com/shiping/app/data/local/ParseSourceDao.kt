package com.shiping.app.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.shiping.app.data.model.ParseSourceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ParseSourceDao {

    @Query("SELECT * FROM parse_sources ORDER BY sortOrder ASC, createdAt DESC")
    fun getAll(): Flow<List<ParseSourceEntity>>

    @Query("SELECT * FROM parse_sources ORDER BY sortOrder ASC, createdAt DESC")
    suspend fun getAllList(): List<ParseSourceEntity>

    @Query("SELECT * FROM parse_sources WHERE enabled = 1 ORDER BY sortOrder ASC, createdAt DESC")
    fun getEnabled(): Flow<List<ParseSourceEntity>>

    @Query("SELECT * FROM parse_sources WHERE id = :id")
    suspend fun getById(id: Long): ParseSourceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(source: ParseSourceEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(sources: List<ParseSourceEntity>)

    @Update
    suspend fun update(source: ParseSourceEntity)

    @Delete
    suspend fun delete(source: ParseSourceEntity)

    @Query("DELETE FROM parse_sources WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("DELETE FROM parse_sources")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM parse_sources")
    suspend fun count(): Int
}
