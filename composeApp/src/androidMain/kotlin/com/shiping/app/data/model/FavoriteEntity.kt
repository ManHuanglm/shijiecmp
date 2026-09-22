package com.shiping.app.data.model

import androidx.room.Entity

/**
 * 收藏实体。
 * 以 (vodId, sourceUrl) 为复合主键：不同 API 源的影片 vodId 可能相同，
 * 必须按来源隔离，与播放历史保持一致的键语义。
 */
@Entity(tableName = "favorites", primaryKeys = ["vodId", "sourceUrl"])
data class FavoriteEntity(
    /** 视频 ID */
    val vodId: Int,
    /** 来源 API 源地址（空串表示未指定来源，使用当前默认源） */
    val sourceUrl: String = "",
    /** 视频标题 */
    val title: String,
    /** 封面图 URL */
    val vodPic: String = "",
    /** 收藏时间戳 */
    val addedAt: Long = System.currentTimeMillis(),
)
