package com.shiping.app.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 电视直播源（M3U / M3U8 播放列表地址）
 */
@Entity(tableName = "tv_sources")
data class TvSourceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 名称 */
    val name: String,
    /** M3U 播放列表地址 */
    val url: String,
    /** 备注/说明 */
    val note: String = "",
    /** 是否启用 */
    val enabled: Boolean = true,
    /** 排序 */
    val sortOrder: Int = 0,
    /** 创建时间戳 */
    val createdAt: Long = System.currentTimeMillis(),
)
