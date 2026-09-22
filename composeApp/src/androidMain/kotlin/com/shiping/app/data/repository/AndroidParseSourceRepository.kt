package com.shiping.app.data.repository

import com.shiping.app.data.local.ParseSourceDao
import com.shiping.app.data.model.ParseSourceEntity as RoomParseSource
import com.shiping.desktop.data.model.ParseSourceEntity
import com.shiping.shared.repository.ParseSourceRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

/** 解析源仓库 Android 实现（Room，实现 shared 共享接口） */
class AndroidParseSourceRepository(
    private val dao: ParseSourceDao,
    private val prefs: com.shiping.shared.prefs.KeyValueStore,
    scope: CoroutineScope,
) : ParseSourceRepository {

    private fun toModel(room: RoomParseSource) = ParseSourceEntity(
        id = room.id, name = room.name, url = room.url, note = room.note,
        enabled = room.enabled, sortOrder = room.sortOrder, createdAt = room.createdAt,
    )

    override val sources: StateFlow<List<ParseSourceEntity>> =
        dao.getAll().map { list -> list.map { toModel(it) } }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    override suspend fun refresh() = Unit

    override suspend fun insert(source: ParseSourceEntity): Long = withContext(Dispatchers.IO) {
        dao.insert(RoomParseSource(name = source.name, url = source.url, note = source.note, enabled = source.enabled, sortOrder = source.sortOrder, createdAt = source.createdAt))
    }

    override suspend fun deleteByIds(ids: List<Long>) = withContext(Dispatchers.IO) { dao.deleteByIds(ids) }

    /** 当前生效的解析前缀：优先用户默认，其次第一个启用源，无则默认值 */
    override fun currentParserUrl(): String? {
        val stored = prefs.getString("defaultParserUrl")
        if (stored.isNotBlank()) return stored
        return sources.value.firstOrNull { it.enabled }?.url
            ?: com.shiping.app.util.Constants.DEFAULT_PARSER_URL
    }

    override fun setDefaultParserUrl(url: String) = prefs.put("defaultParserUrl", url)
}
