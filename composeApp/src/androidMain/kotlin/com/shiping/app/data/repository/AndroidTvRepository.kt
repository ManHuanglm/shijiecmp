package com.shiping.app.data.repository

import com.shiping.app.data.local.TvSourceDao
import com.shiping.app.data.model.TvSourceEntity as RoomTvSource
import com.shiping.desktop.data.model.TvChannel
import com.shiping.desktop.data.model.TvSourceEntity
import com.shiping.shared.repository.TvRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

/** 直播源仓库 Android 实现（Room + HTTP 拉取解析，实现 shared 共享接口） */
class AndroidTvRepository(
    private val dao: TvSourceDao,
    private val prefs: com.shiping.shared.prefs.KeyValueStore,
    scope: CoroutineScope,
) : TvRepository {

    private fun toModel(room: RoomTvSource) = TvSourceEntity(
        id = room.id, name = room.name, url = room.url, note = room.note, createdAt = room.createdAt,
    )

    override val sources: StateFlow<List<TvSourceEntity>> =
        dao.getAll().map { list -> list.map { toModel(it) } }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    override suspend fun refresh() = Unit

    override suspend fun update(source: TvSourceEntity) = withContext(Dispatchers.IO) {
        dao.update(RoomTvSource(id = source.id, name = source.name, url = source.url, note = source.note, createdAt = source.createdAt))
    }

    /** 批量添加直播源（每行一个，仅url / 名称,url / 名称,url,备注），返回导入条数 */
    override suspend fun batchInsert(text: String): Int = withContext(Dispatchers.IO) {
        val parsed = text.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { line ->
                val parts = line.split(',', '，').map { it.trim() }
                val url = parts.getOrNull(1) ?: parts[0]
                if (!url.startsWith("http") && !url.contains(".")) return@mapNotNull null
                val name = (if (parts.size >= 2) parts[0] else "").ifBlank {
                    url.removePrefix("https://").removePrefix("http://").substringBefore('/')
                }
                RoomTvSource(name = name, url = url, note = parts.drop(2).joinToString(","))
            }
        parsed.forEach { insertModel(it) }
        parsed.size
    }

    override suspend fun importFile(path: String): Int = withContext(Dispatchers.IO) {
        val text = runCatching { java.io.File(path).readText(Charsets.UTF_8) }.getOrNull() ?: return@withContext 0
        batchInsert(text)
    }

    override suspend fun insert(source: TvSourceEntity): Long = withContext(Dispatchers.IO) {
        insertModel(RoomTvSource(name = source.name, url = source.url, note = source.note, createdAt = source.createdAt))
    }

    private suspend fun insertModel(room: RoomTvSource): Long {
        val id = dao.insert(room)
        return id
    }

    override suspend fun deleteById(id: Long) = withContext(Dispatchers.IO) { dao.deleteById(id) }

    override suspend fun count(): Int = withContext(Dispatchers.IO) { dao.count() }

    /** 首次启动插入默认直播源 */
    override suspend fun initDefaultSource() {
        if (count() == 0) {
            insert(
                TvSourceEntity(
                    name = "默认直播源",
                    url = "https://cdn.jsdmirror.com/gh/Guovin/iptv-api@gd/output/result.m3u",
                    note = "系统默认",
                ),
            )
        }
    }

    override fun currentTvId(): Long? =
        prefs.getLong(KEY_CURRENT_TV_ID).takeIf { it > 0 }

    /** 拉取并解析播放列表，返回按分组归类的频道（支持 M3U 与 TVBox TXT） */
    override suspend fun loadChannels(source: TvSourceEntity): List<TvChannel> = withContext(Dispatchers.IO) {
        val request = okhttp3.Request.Builder().url(source.url).get().build()
        com.shiping.app.data.spider.SpiderHttp.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) throw IllegalStateException("播放列表为空")
            parseLiveList(body)
        }
    }

    /** 按内容特征分发解析：M3U 或 TVBox TXT 格式 */
    private fun parseLiveList(content: String): List<TvChannel> =
        if (content.contains("#EXTINF", ignoreCase = true) || content.contains("#EXTM3U", ignoreCase = true)) {
            parseM3u(content)
        } else {
            parseTxt(content)
        }

    /** 解析 TVBox TXT 格式（分组行「组名,#genre#」+ 频道行「名称,地址1#地址2」） */
    private fun parseTxt(content: String): List<TvChannel> {
        data class Pending(val name: String, val group: String, val urls: MutableList<String> = mutableListOf())

        val ordered = ArrayList<Pending>()
        val byKey = LinkedHashMap<String, Pending>()
        var pendingGroup = "其他"
        content.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEach
            if (line.endsWith("#genre#", ignoreCase = true)) {
                pendingGroup = line.removeSuffix("#genre#").trim().removeSuffix(",").trim().ifBlank { "其他" }
                return@forEach
            }
            if (line.startsWith("#")) return@forEach
            val parts = line.split(",", "，")
            if (parts.size < 2) return@forEach
            val name = parts[0].trim()
            if (name.isBlank()) return@forEach
            val urls = parts.drop(1).flatMap { it.split("#") }.map { it.trim() }.filter { it.contains("://") }
            if (urls.isEmpty()) return@forEach
            val existing = byKey[name]
            if (existing != null) {
                urls.forEach { url -> if (url !in existing.urls) existing.urls.add(url) }
            } else {
                val pending = Pending(name, pendingGroup, urls.toMutableList())
                byKey[name] = pending
                ordered.add(pending)
            }
        }
        if (ordered.isEmpty()) throw IllegalStateException("未解析到任何频道")
        return ordered.map { TvChannel(name = it.name, urls = it.urls, group = it.group, logo = "") }
    }

    /** 解析 M3U 文本：同名频道合并为一条、追加线路 */
    private fun parseM3u(content: String): List<TvChannel> {
        data class Pending(val name: String, val group: String, val logo: String, val urls: MutableList<String> = mutableListOf())

        val ordered = ArrayList<Pending>()
        val byKey = LinkedHashMap<String, Pending>()
        var pendingName: String? = null
        var pendingGroup: String? = null
        var pendingLogo: String? = null
        content.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEach
            when {
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    pendingName = extractExtInfName(line)
                    pendingGroup = extractAttr(line, "group-title")?.takeIf { it.isNotBlank() }
                    pendingLogo = extractAttr(line, "tvg-logo")?.takeIf { it.isNotBlank() }
                }
                line.startsWith("#") -> Unit
                else -> {
                    val name = (pendingName ?: line.substringAfterLast('/').substringBefore('?'))
                        .ifBlank { "未命名频道" }
                    val existing = byKey[name]
                    if (existing != null) {
                        if (line !in existing.urls) existing.urls.add(line)
                    } else {
                        val pending = Pending(
                            name,
                            pendingGroup?.takeIf { it.isNotBlank() } ?: "其他",
                            pendingLogo.orEmpty(),
                            mutableListOf(line),
                        )
                        byKey[name] = pending
                        ordered.add(pending)
                    }
                    pendingName = null
                    pendingGroup = null
                    pendingLogo = null
                }
            }
        }
        if (ordered.isEmpty()) throw IllegalStateException("未解析到任何频道")
        return ordered.map { TvChannel(name = it.name, urls = it.urls, group = it.group, logo = it.logo) }
    }

    /** 提取 #EXTINF 行中逗号后的频道名称 */
    private fun extractExtInfName(line: String): String? {
        val idx = line.lastIndexOf(',')
        return if (idx >= 0 && idx < line.length - 1) line.substring(idx + 1).trim() else null
    }

    /** 从 #EXTINF 属性段提取 key="value" 形式的属性值 */
    private fun extractAttr(line: String, key: String): String? {
        val regex = Regex("""$key\s*=\s*"([^"]*)"""", RegexOption.IGNORE_CASE)
        return regex.find(line)?.groupValues?.getOrNull(1)
    }

    companion object {
        /** 当前选中的直播源 ID（键名与桌面 DesktopPrefs 对齐） */
        const val KEY_CURRENT_TV_ID = "currentTvId"
    }
}
