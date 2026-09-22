package com.shiping.app.data.repository

import com.shiping.app.data.local.PlayHistoryDao
import com.shiping.app.data.model.PlayHistoryEntity as RoomPlayHistory
import com.shiping.desktop.data.model.PlayHistoryEntity
import com.shiping.shared.repository.PlayHistoryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

/** 播放历史仓库 Android 实现（Room，实现 shared 共享接口） */
class AndroidPlayHistoryRepository(
    private val dao: PlayHistoryDao,
    scope: CoroutineScope,
) : PlayHistoryRepository {

    private fun toModel(room: RoomPlayHistory) = PlayHistoryEntity(
        vodId = room.vodId, sourceUrl = room.sourceUrl, title = room.title,
        vodPic = room.vodPic, lastEpisodeName = room.lastEpisodeName,
        lastPositionMs = room.lastPositionMs, durationMs = room.durationMs,
        watchedAt = room.watchedAt,
    )

    override val history: StateFlow<List<PlayHistoryEntity>> =
        dao.observeAll().map { list -> list.map { toModel(it) } }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    override suspend fun refresh() = Unit

    /** 保存/更新进度（开播即写一条，之后定期刷新进度） */
    override suspend fun save(entity: PlayHistoryEntity) = withContext(Dispatchers.IO) {
        dao.upsert(
            RoomPlayHistory(
                vodId = entity.vodId, sourceUrl = entity.sourceUrl, title = entity.title,
                vodPic = entity.vodPic, lastEpisodeName = entity.lastEpisodeName,
                lastPositionMs = entity.lastPositionMs, durationMs = entity.durationMs,
                watchedAt = entity.watchedAt,
            ),
        )
    }

    /** 查询某片的续播进度（无记录返回 null） */
    override suspend fun getByVod(vodId: Int, sourceUrl: String): PlayHistoryEntity? =
        withContext(Dispatchers.IO) { dao.getByVod(vodId, sourceUrl)?.let { toModel(it) } }

    override suspend fun delete(vodId: Int, sourceUrl: String) = withContext(Dispatchers.IO) { dao.delete(vodId, sourceUrl) }

    override suspend fun clear() = withContext(Dispatchers.IO) { dao.clearAll() }
}
