package com.shiping.app.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 视频解析源
 */
@Entity(tableName = "parse_sources")
data class ParseSourceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 名称 */
    val name: String,
    /** 解析地址前缀，播放URL会拼接在后面 */
    val url: String,
    /** 备注 */
    val note: String = "",
    /** 是否启用 */
    val enabled: Boolean = true,
    /** 排序 */
    val sortOrder: Int = 0,
    /** 创建时间 */
    val createdAt: Long = System.currentTimeMillis()
)
