package com.shiping.desktop.data.repository

import com.shiping.desktop.data.db.DesktopDb
import com.shiping.desktop.data.model.FavoriteEntity
import com.shiping.desktop.data.model.PlayHistoryEntity
import com.shiping.shared.repository.FavoriteRepository as FavoriteRepositoryContract
import com.shiping.shared.repository.PlayHistoryRepository as PlayHistoryRepositoryContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * 播放历史仓库（复合主键 vodId+sourceUrl，跨源隔离，对齐 app 硬性约束）。
 */
class PlayHistoryRepository(private val db: DesktopDb) : PlayHistoryRepositoryContract {

    private val _history = MutableStateFlow<List<PlayHistoryEntity>>(emptyList())
    override val history: StateFlow<List<PlayHistoryEntity>> = _history.asStateFlow()

    override suspend fun refresh() = withContext(Dispatchers.IO) {
        _history.value = db.query("SELECT * FROM play_history ORDER BY watchedAt DESC LIMIT 200") { rs ->
            PlayHistoryEntity(
                vodId = rs.getInt("vodId"),
                sourceUrl = rs.getString("sourceUrl") ?: "",
                title = rs.getString("title") ?: "",
                vodPic = rs.getString("vodPic") ?: "",
                lastEpisodeName = rs.getString("lastEpisodeName") ?: "",
                lastPositionMs = rs.getLong("lastPositionMs"),
                durationMs = rs.getLong("durationMs"),
                watchedAt = rs.getLong("watchedAt"),
            )
        }
    }

    /** 保存/更新进度（开播即写一条，之后定期刷新进度） */
    override suspend fun save(entity: PlayHistoryEntity) = withContext(Dispatchers.IO) {
        db.exec(
            """
            INSERT INTO play_history(vodId, sourceUrl, title, vodPic, lastEpisodeName, lastPositionMs, durationMs, watchedAt)
            VALUES (?,?,?,?,?,?,?,?)
            ON CONFLICT(vodId, sourceUrl) DO UPDATE SET
                title=excluded.title, vodPic=excluded.vodPic, lastEpisodeName=excluded.lastEpisodeName,
                lastPositionMs=excluded.lastPositionMs, durationMs=excluded.durationMs, watchedAt=excluded.watchedAt
            """.trimIndent(),
            listOf(
                entity.vodId, entity.sourceUrl, entity.title, entity.vodPic,
                entity.lastEpisodeName, entity.lastPositionMs, entity.durationMs, entity.watchedAt,
            ),
        )
        refresh()
    }

    /** 查询某片的续播进度（无记录返回 null） */
    override suspend fun getByVod(vodId: Int, sourceUrl: String): PlayHistoryEntity? = withContext(Dispatchers.IO) {
        db.query(
            "SELECT * FROM play_history WHERE vodId=? AND sourceUrl=?",
            listOf(vodId, sourceUrl),
        ) { rs ->
            PlayHistoryEntity(
                vodId = rs.getInt("vodId"),
                sourceUrl = rs.getString("sourceUrl") ?: "",
                title = rs.getString("title") ?: "",
                vodPic = rs.getString("vodPic") ?: "",
                lastEpisodeName = rs.getString("lastEpisodeName") ?: "",
                lastPositionMs = rs.getLong("lastPositionMs"),
                durationMs = rs.getLong("durationMs"),
                watchedAt = rs.getLong("watchedAt"),
            )
        }.firstOrNull()
    }

    override suspend fun delete(vodId: Int, sourceUrl: String) = withContext(Dispatchers.IO) {
        db.exec("DELETE FROM play_history WHERE vodId=? AND sourceUrl=?", listOf(vodId, sourceUrl))
        refresh()
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        db.exec("DELETE FROM play_history")
        refresh()
    }
}

/**
 * 收藏仓库（复合主键 vodId+sourceUrl，与播放历史键语义一致）。
 */
class FavoriteRepository(private val db: DesktopDb) : FavoriteRepositoryContract {

    private val _favorites = MutableStateFlow<List<FavoriteEntity>>(emptyList())
    override val favorites: StateFlow<List<FavoriteEntity>> = _favorites.asStateFlow()

    override suspend fun refresh() = withContext(Dispatchers.IO) {
        _favorites.value = db.query("SELECT * FROM favorites ORDER BY addedAt DESC") { rs ->
            FavoriteEntity(
                vodId = rs.getInt("vodId"),
                sourceUrl = rs.getString("sourceUrl") ?: "",
                title = rs.getString("title") ?: "",
                vodPic = rs.getString("vodPic") ?: "",
                addedAt = rs.getLong("addedAt"),
            )
        }
    }

    override suspend fun add(entity: FavoriteEntity) = withContext(Dispatchers.IO) {
        db.exec(
            """
            INSERT INTO favorites(vodId, sourceUrl, title, vodPic, addedAt) VALUES (?,?,?,?,?)
            ON CONFLICT(vodId, sourceUrl) DO UPDATE SET
                title=excluded.title, vodPic=excluded.vodPic, addedAt=excluded.addedAt
            """.trimIndent(),
            listOf(entity.vodId, entity.sourceUrl, entity.title, entity.vodPic, entity.addedAt),
        )
        refresh()
    }

    override suspend fun remove(vodId: Int, sourceUrl: String) = withContext(Dispatchers.IO) {
        db.exec("DELETE FROM favorites WHERE vodId=? AND sourceUrl=?", listOf(vodId, sourceUrl))
        refresh()
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        db.exec("DELETE FROM favorites")
        refresh()
    }

    override suspend fun isFavorite(vodId: Int, sourceUrl: String): Boolean = withContext(Dispatchers.IO) {
        db.query(
            "SELECT COUNT(*) FROM favorites WHERE vodId=? AND sourceUrl=?",
            listOf(vodId, sourceUrl),
        ) { it.getInt(1) }.firstOrNull() ?: 0 > 0
    }
}
