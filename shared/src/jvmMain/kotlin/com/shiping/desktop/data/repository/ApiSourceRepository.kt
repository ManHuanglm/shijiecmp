package com.shiping.desktop.data.repository

import com.shiping.desktop.data.db.DesktopDb
import com.shiping.desktop.data.model.ApiSourceEntity
import com.shiping.desktop.util.Constants
import com.shiping.shared.repository.ApiSourceRepository as ApiSourceRepositoryContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * API 源管理仓库（桌面版，SQLite 直写 + StateFlow 通知）。
 * 增删改查、爬虫源整体替换、当前源选择。
 */
class ApiSourceRepository(
    private val db: DesktopDb,
    private val prefs: com.shiping.desktop.data.prefs.DesktopPrefs,
) : ApiSourceRepositoryContract {

    private val _sources = MutableStateFlow<List<ApiSourceEntity>>(emptyList())

    /** 全部源（响应式） */
    override val sources: StateFlow<List<ApiSourceEntity>> = _sources.asStateFlow()

    /** 启动加载 */
    override suspend fun refresh() = withContext(Dispatchers.IO) {
        _sources.value = db.query(
            "SELECT * FROM api_sources ORDER BY sortOrder, id",
        ) { rs -> mapSource(rs) }
    }

    override suspend fun getById(id: Long): ApiSourceEntity? =
        _sources.value.firstOrNull { it.id == id }

    override suspend fun insert(source: ApiSourceEntity): Long = withContext(Dispatchers.IO) {
        val id = db.insert(
            "INSERT INTO api_sources(name, url, note, enabled, sortOrder, siteType, jarUrl, spiderKey, ext, locked, playRecord, searchable, userAgent, createdAt) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            listOf(
                source.name, source.url, source.note, source.enabled, source.sortOrder,
                source.siteType, source.jarUrl, source.spiderKey, source.ext,
                source.locked, source.playRecord, source.searchable, source.userAgent, source.createdAt,
            ),
        )
        refresh()
        id
    }

    override suspend fun update(source: ApiSourceEntity) = withContext(Dispatchers.IO) {
        db.exec(
            "UPDATE api_sources SET name=?, url=?, note=?, enabled=?, sortOrder=?, siteType=?, jarUrl=?, spiderKey=?, ext=?, " +
                "locked=?, playRecord=?, searchable=?, userAgent=? WHERE id=?",
            listOf(
                source.name, source.url, source.note, source.enabled, source.sortOrder,
                source.siteType, source.jarUrl, source.spiderKey, source.ext,
                source.locked, source.playRecord, source.searchable, source.userAgent, source.id,
            ),
        )
        refresh()
    }

    /** 批量设置源锁定状态 */
    override suspend fun setLocked(ids: List<Long>, value: Boolean) = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext
        val placeholders = ids.joinToString(",") { "?" }
        db.exec(
            "UPDATE api_sources SET locked=? WHERE id IN ($placeholders)",
            listOf(value) + ids,
        )
        refresh()
    }

    /** 批量设置源播放记录开关 */
    override suspend fun setPlayRecord(ids: List<Long>, value: Boolean) = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext
        val placeholders = ids.joinToString(",") { "?" }
        db.exec(
            "UPDATE api_sources SET playRecord=? WHERE id IN ($placeholders)",
            listOf(value) + ids,
        )
        refresh()
    }

    /** 批量设置源是否参与搜索 */
    override suspend fun setSearchable(ids: List<Long>, value: Boolean) = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext
        val placeholders = ids.joinToString(",") { "?" }
        db.exec(
            "UPDATE api_sources SET searchable=? WHERE id IN ($placeholders)",
            listOf(value) + ids,
        )
        refresh()
    }

    /** 上移源一位（整体重排 sortOrder，保持相对顺序） */
    override suspend fun moveUp(id: Long) = withContext(Dispatchers.IO) {
        val list = _sources.value
        val index = list.indexOfFirst { it.id == id }
        if (index <= 0) return@withContext
        val reordered = list.toMutableList().apply {
            add(index - 1, removeAt(index))
        }
        reordered.forEachIndexed { position, source ->
            if (source.sortOrder != position) {
                db.exec("UPDATE api_sources SET sortOrder=? WHERE id=?", listOf(position, source.id))
            }
        }
        refresh()
    }

    /**
     * 检测源是否可用（轻量 GET，2xx-3xx 视为可用），返回可用数量。
     * 仅支持 CMS 接口源；爬虫源地址为站点 key，不在检测范围。
     */
    override suspend fun checkUsable(ids: List<Long>): Int = withContext(Dispatchers.IO) {
        _sources.value
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

    /**
     * 批量导入 CMS 源：每行一个源，支持「名称,地址」「名称$地址」「仅地址」三种格式。
     * 返回成功导入条数。
     */
    override suspend fun batchInsertCms(
        text: String,
        putFirst: Boolean,
        locked: Boolean,
        playRecord: Boolean,
    ): Int = withContext(Dispatchers.IO) {
        val cmsList = _sources.value.filter { it.siteType != 3 }
        val parsed = text.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { line ->
                val splitAt = line.indexOfFirst { it == ',' || it == '，' || it == '$' }
                val name = if (splitAt > 0) line.take(splitAt).trim() else ""
                val url = if (splitAt > 0) line.substring(splitAt + 1).trim() else line
                if (!url.startsWith("http") && !url.contains(".")) return@mapNotNull null
                val autoName = name.ifBlank { url.removePrefix("https://").removePrefix("http://").substringBefore('/') }
                ApiSourceEntity(
                    name = autoName,
                    url = url,
                    siteType = 0,
                    sortOrder = 0,
                    locked = locked,
                    playRecord = playRecord,
                )
            }
        if (parsed.isEmpty()) return@withContext 0
        var order = if (putFirst) {
            (cmsList.minOfOrNull { it.sortOrder } ?: 0) - parsed.size
        } else {
            (cmsList.maxOfOrNull { it.sortOrder } ?: 0) + 1
        }
        parsed.forEach { source ->
            insert(source.copy(sortOrder = order))
            order++
        }
        refresh()
        parsed.size
    }

    override suspend fun deleteByIds(ids: List<Long>) = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext
        val placeholders = ids.joinToString(",") { "?" }
        db.exec("DELETE FROM api_sources WHERE id IN ($placeholders)", ids)
        refresh()
    }

    /** 按站点类型删除（0/1=CMS 接口源，3=爬虫源），爬虫源导入整体替换用 */
    override suspend fun deleteBySiteType(siteType: Int) = withContext(Dispatchers.IO) {
        db.exec("DELETE FROM api_sources WHERE siteType=?", listOf(siteType))
        refresh()
    }

    override suspend fun count(): Int = withContext(Dispatchers.IO) {
        db.query("SELECT COUNT(*) AS c FROM api_sources") { it.getInt(1) }.firstOrNull() ?: 0
    }

    /** 首次启动时插入默认 API 源（如果数据库为空） */
    override suspend fun initDefaultSource() {
        if (count() == 0) {
            insert(
                ApiSourceEntity(
                    name = Constants.DEFAULT_API_NAME,
                    url = Constants.DEFAULT_API_URL,
                    note = "默认源",
                    enabled = true,
                ),
            )
        }
    }

    // region 当前源选择（选中 ID 持久化，重启恢复）

    /** 当前选中的 API 源 ID（null 表示未指定，取第一个启用源） */
    override val currentApiId: Long?
        get() = prefs.getLong(com.shiping.desktop.data.prefs.DesktopPrefs.KEY_CURRENT_API_ID).takeIf { it > 0 }

    override fun setCurrentApiId(id: Long) =
        prefs.put(com.shiping.desktop.data.prefs.DesktopPrefs.KEY_CURRENT_API_ID, id)

    /** 当前生效的 API 源实体 */
    override fun getCurrentApiSource(): ApiSourceEntity? {
        val all = _sources.value
        currentApiId?.let { id -> all.firstOrNull { it.id == id }?.let { return it } }
        return all.firstOrNull { it.enabled } ?: all.firstOrNull()
    }

    /** 当前 API 地址 */
    override fun currentApiUrlOnce(): String =
        getCurrentApiSource()?.url ?: Constants.DEFAULT_API_URL

    /** 当前 API 源名称 */
    override fun currentApiName(): String =
        getCurrentApiSource()?.name ?: Constants.DEFAULT_API_NAME

    // endregion
}

/** ResultSet -> ApiSourceEntity */
internal fun mapSource(rs: java.sql.ResultSet): ApiSourceEntity = ApiSourceEntity(
    id = rs.getLong("id"),
    name = rs.getString("name") ?: "",
    url = rs.getString("url") ?: "",
    note = rs.getString("note") ?: "",
    enabled = rs.getInt("enabled") == 1,
    sortOrder = rs.getInt("sortOrder"),
    siteType = rs.getInt("siteType"),
    jarUrl = rs.getString("jarUrl") ?: "",
    spiderKey = rs.getString("spiderKey") ?: "",
    ext = rs.getString("ext") ?: "",
    locked = rs.getInt("locked") == 1,
    playRecord = rs.getInt("playRecord") == 1,
    searchable = rs.getInt("searchable") == 1,
    userAgent = rs.getString("userAgent") ?: "",
    createdAt = rs.getLong("createdAt"),
)
