package com.shiping.app.data.repository

import com.shiping.app.data.local.FavoriteDao
import com.shiping.app.data.model.FavoriteEntity as RoomFavorite
import com.shiping.desktop.data.model.FavoriteEntity
import com.shiping.shared.repository.FavoriteRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

/** 收藏仓库 Android 实现（Room，实现 shared 共享接口） */
class AndroidFavoriteRepository(
    private val dao: FavoriteDao,
    scope: CoroutineScope,
) : FavoriteRepository {

    private fun toModel(room: RoomFavorite) = FavoriteEntity(
        vodId = room.vodId, sourceUrl = room.sourceUrl, title = room.title,
        vodPic = room.vodPic, addedAt = room.addedAt,
    )

    override val favorites: StateFlow<List<FavoriteEntity>> =
        dao.observeAll().map { list -> list.map { toModel(it) } }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    override suspend fun refresh() = Unit

    override suspend fun add(entity: FavoriteEntity) = withContext(Dispatchers.IO) {
        dao.upsert(RoomFavorite(vodId = entity.vodId, sourceUrl = entity.sourceUrl, title = entity.title, vodPic = entity.vodPic, addedAt = entity.addedAt))
    }

    override suspend fun remove(vodId: Int, sourceUrl: String) = withContext(Dispatchers.IO) { dao.delete(vodId, sourceUrl) }

    override suspend fun clear() = withContext(Dispatchers.IO) { dao.clearAll() }

    override suspend fun isFavorite(vodId: Int, sourceUrl: String): Boolean =
        withContext(Dispatchers.IO) { dao.exists(vodId, sourceUrl) }
}
