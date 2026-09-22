package com.shiping.app.data.repository

import com.shiping.app.data.local.ApiSourceDao
import com.shiping.app.data.model.ApiSourceEntity as RoomApiSource
import com.shiping.desktop.data.model.ApiSourceEntity
import com.shiping.shared.repository.ApiSourceRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

/**
 * API 源仓库 Android 实现（Room + DataStore，实现 shared 共享接口）：
 * Room Flow 自动通知，refresh 为空操作；输出 shared 桌面模型类型。
 * locked/playRecord/searchable/userAgent 为桌面端字段，Android 无对应存储列，
 * 映射时取缺省值，批量开关为空实现（Android 版本功能子集）。
 */
class AndroidApiSourceRepository(
    private val dao: ApiSourceDao,
    private val prefs: com.shiping.shared.prefs.KeyValueStore,
    scope: CoroutineScope,
) : ApiSourceRepository {

    private fun RoomApiSource.toModel() = ApiSourceEntity(
        id = id, name = name, url = url, note = note, enabled = enabled,
        sortOrder = sortOrder, siteType = siteType, jarUrl = jarUrl,
        spiderKey = spiderKey, ext = ext, locked = false, playRecord = true,
        searchable = true, userAgent = "", createdAt = createdAt,
    )

    private fun modelToRoom(m: ApiSourceEntity, room: RoomApiSource? = null) = (room ?: RoomApiSource(name = m.name, url = m.url)).copy(
        id = m.id, name = m.name, url = m.url, note = m.note, enabled = m.enabled,
        sortOrder = m.sortOrder, siteType = m.siteType, jarUrl = m.jarUrl,
        spiderKey = m.spiderKey, ext = m.ext, createdAt = m.createdAt,
    )

    override val sources: StateFlow<List<ApiSourceEntity>> =
        dao.getAll().map { list -> list.map { it.toModel() } }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Room Flow 自动通知，无需手动刷新 */
    override suspend fun refresh() = Unit

    override suspend fun getById(id: Long): ApiSourceEntity? = dao.getById(id)?.toModel()

    override suspend fun insert(source: ApiSourceEntity): Long =
        withContext(Dispatchers.IO) { dao.insert(modelToRoom(source)) }

    override suspend fun update(source: ApiSourceEntity) = withContext(Dispatchers.IO) {
        dao.update(modelToRoom(source, dao.getById(source.id)))
    }

    override suspend fun setLocked(ids: List<Long>, value: Boolean) = Unit

    override suspend fun setPlayRecord(ids: List<Long>, value: Boolean) = Unit

    override suspend fun setSearchable(ids: List<Long>, value: Boolean) = Unit

    override suspend fun moveUp(id: Long) = withContext(Dispatchers.IO) {
        val list = sources.value
        val index = list.indexOfFirst { it.id == id }
        if (index <= 0) return@withContext
        val reordered = list.toMutableList().apply { add(index - 1, removeAt(index)) }
        reordered.forEachIndexed { position, source ->
            if (source.sortOrder != position) {
                dao.update(modelToRoom(source.copy(sortOrder = position), dao.getById(source.id)))
            }
        }
    }

    /** 检测源是否可用（仅 CMS 接口源），返回可用数量 */
    override suspend fun checkUsable(ids: List<Long>): Int = withContext(Dispatchers.IO) {
        sources.value
            .filter { it.id in ids && it.siteType != 3 && it.url.startsWith("http") }
            .count { source ->
                runCatching {
                    val conn = java.net.URL(source.url).openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 5000
                    conn.readTimeout = 5000
                    conn.requestMethod = "GET"
                    val code = conn.responseCode
                    conn.disconnect()
                    code in 200..399
                }.getOrDefault(false)
            }
    }

    /** 批量导入 CMS 源（每行一个，支持「名称,地址」「名称$地址」「仅地址」），返回导入条数 */
    override suspend fun batchInsertCms(
        text: String,
        putFirst: Boolean,
        locked: Boolean,
        playRecord: Boolean,
    ): Int = withContext(Dispatchers.IO) {
        val cmsList = sources.value.filter { it.siteType != 3 }
        val parsed = text.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { line ->
                val splitAt = line.indexOfFirst { it == ',' || it == '，' || it == '$' }
                val name = if (splitAt > 0) line.take(splitAt).trim() else ""
                val url = if (splitAt > 0) line.substring(splitAt + 1).trim() else line
                if (!url.startsWith("http") && !url.contains(".")) return@mapNotNull null
                val autoName = name.ifBlank { url.removePrefix("https://").removePrefix("http://").substringBefore('/') }
                RoomApiSource(name = autoName, url = url, siteType = 0, sortOrder = 0)
            }
        if (parsed.isEmpty()) return@withContext 0
        var order = if (putFirst) {
            (cmsList.minOfOrNull { it.sortOrder } ?: 0) - parsed.size
        } else {
            (cmsList.maxOfOrNull { it.sortOrder } ?: 0) + 1
        }
        parsed.forEach { source ->
            dao.insert(source.copy(sortOrder = order))
            order++
        }
        parsed.size
    }

    override suspend fun deleteByIds(ids: List<Long>) = withContext(Dispatchers.IO) { dao.deleteByIds(ids) }

    override suspend fun deleteBySiteType(siteType: Int) = withContext(Dispatchers.IO) { dao.deleteBySiteType(siteType) }

    override suspend fun count(): Int = withContext(Dispatchers.IO) { dao.count() }

    /** 首次启动时插入默认 API 源（如果为空） */
    override suspend fun initDefaultSource() {
        if (count() == 0) {
            insert(
                ApiSourceEntity(
                    name = com.shiping.app.util.Constants.DEFAULT_API_NAME,
                    url = com.shiping.app.util.Constants.DEFAULT_API_URL,
                    note = "默认源",
                    enabled = true,
                ),
            )
        }
    }

    override val currentApiId: Long?
        get() = prefs.getLong(KEY_CURRENT_API_ID).takeIf { it > 0 }

    override fun setCurrentApiId(id: Long) = prefs.put(KEY_CURRENT_API_ID, id)

    override fun getCurrentApiSource(): ApiSourceEntity? {
        val all = sources.value
        currentApiId?.let { id -> all.firstOrNull { it.id == id }?.let { return it } }
        return all.firstOrNull { it.enabled } ?: all.firstOrNull()
    }

    override fun currentApiUrlOnce(): String =
        getCurrentApiSource()?.url ?: com.shiping.app.util.Constants.DEFAULT_API_URL

    override fun currentApiName(): String =
        getCurrentApiSource()?.name ?: com.shiping.app.util.Constants.DEFAULT_API_NAME

    companion object {
        /** 当前选中的 API 源 ID（键名与桌面 DesktopPrefs 对齐） */
        const val KEY_CURRENT_API_ID = "currentApiId"
    }
}
