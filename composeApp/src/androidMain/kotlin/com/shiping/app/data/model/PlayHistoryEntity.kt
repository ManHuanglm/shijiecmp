package com.shiping.app.data.model

import androidx.room.Entity

/**
 * 播放记录实体。
 * 以 (vodId, sourceUrl) 为复合主键：不同 API 源的影片 vodId 可能相同，
 * 必须按来源隔离，避免换源后互相覆盖导致历史内容不符或丢失。
 */
@Entity(tableName = "play_history", primaryKeys = ["vodId", "sourceUrl"])
data class PlayHistoryEntity(
    /** 视频 ID */
    val vodId: Int,
    /** 来源 API 源地址（空串表示未知来源的旧记录） */
    val sourceUrl: String = "",
    /** 视频标题 */
    val title: String,
    /** 封面图 URL */
    val vodPic: String = "",
    /** 上次观看的集数名称 */
    val lastEpisodeName: String = "",
    /** 上次播放进度（毫秒） */
    val lastPositionMs: Long = 0L,
    /** 视频总时长（毫秒） */
    val durationMs: Long = 0L,
    /** 最后观看时间戳 */
    val watchedAt: Long = System.currentTimeMillis(),
)
