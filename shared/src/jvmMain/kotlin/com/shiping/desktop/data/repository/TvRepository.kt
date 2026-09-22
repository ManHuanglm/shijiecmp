package com.shiping.desktop.data.repository

import com.shiping.desktop.data.db.DesktopDb
import com.shiping.desktop.data.model.ParseSourceEntity
import com.shiping.desktop.data.model.TvSourceEntity
import com.shiping.desktop.data.model.TvChannel
import com.shiping.desktop.data.prefs.DesktopPrefs
import com.shiping.desktop.data.spider.SpiderHttp
import com.shiping.desktop.util.AppLog
import com.shiping.desktop.util.Constants
import com.shiping.shared.repository.ParseSourceRepository as ParseSourceRepositoryContract
import com.shiping.shared.repository.TvRepository as TvRepositoryContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * 解析源仓库：增删改查 + 当前默认解析地址。
 */
class ParseSourceRepository(private val db: DesktopDb, private val prefs: DesktopPrefs) : ParseSourceRepositoryContract {

    private val _sources = MutableStateFlow<List<ParseSourceEntity>>(emptyList())
    override val sources: StateFlow<List<ParseSourceEntity>> = _sources.asStateFlow()

    override suspend fun refresh() = withContext(Dispatchers.IO) {
        _sources.value = db.query("SELECT * FROM parse_sources ORDER BY sortOrder, id") { rs ->
            ParseSourceEntity(
                id = rs.getLong("id"),
                name = rs.getString("name") ?: "",
                url = rs.getString("url") ?: "",
                note = rs.getString("note") ?: "",
                enabled = rs.getInt("enabled") == 1,
                sortOrder = rs.getInt("sortOrder"),
                createdAt = rs.getLong("createdAt"),
            )
        }
    }

    override suspend fun insert(source: ParseSourceEntity): Long = withContext(Dispatchers.IO) {
        val id = db.insert(
            "INSERT INTO parse_sources(name, url, note, enabled, sortOrder, createdAt) VALUES (?,?,?,?,?,?)",
            listOf(source.name, source.url, source.note, source.enabled, source.sortOrder, source.createdAt),
        )
        refresh()
        id
    }

    override suspend fun deleteByIds(ids: List<Long>) = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext
        val placeholders = ids.joinToString(",") { "?" }
        db.exec("DELETE FROM parse_sources WHERE id IN ($placeholders)", ids)
        refresh()
    }

    /** 当前生效的解析前缀：优先用户默认，其次第一个启用源，无则 app 默认值 */
    override fun currentParserUrl(): String? {
        val stored = prefs.getString(DesktopPrefs.KEY_DEFAULT_PARSER_URL)
        if (stored.isNotBlank()) return stored
        return _sources.value.firstOrNull { it.enabled }?.url ?: Constants.DEFAULT_PARSER_URL
    }

    override fun setDefaultParserUrl(url: String) = prefs.put(DesktopPrefs.KEY_DEFAULT_PARSER_URL, url)
}

/**
 * 直播源仓库：直播源增删改查 + M3U/TXT 播放列表拉取解析（对齐 app TvRepository）。
 */
class TvRepository(private val db: DesktopDb, private val prefs: DesktopPrefs) : TvRepositoryContract {

    private val _sources = MutableStateFlow<List<TvSourceEntity>>(emptyList())
    override val sources: StateFlow<List<TvSourceEntity>> = _sources.asStateFlow()

    override suspend fun refresh() = withContext(Dispatchers.IO) {
        _sources.value = db.query("SELECT * FROM tv_sources ORDER BY id") { rs ->
            TvSourceEntity(
                id = rs.getLong("id"),
                name = rs.getString("name") ?: "",
                url = rs.getString("url") ?: "",
                note = rs.getString("note") ?: "",
                createdAt = rs.getLong("createdAt"),
            )
        }
    }

    /** 更新直播源（名称/地址/备注） */
    override suspend fun update(source: TvSourceEntity) = withContext(Dispatchers.IO) {
        db.exec(
            "UPDATE tv_sources SET name=?, url=?, note=? WHERE id=?",
            listOf(source.name, source.url, source.note, source.id),
        )
        refresh()
    }

    /** 批量添加直播源：每行一个源，支持 仅url / 名称,url / 名称,url,备注（备注可含中英文逗号），返回导入条数 */
    override suspend fun batchInsert(text: String): Int = withContext(Dispatchers.IO) {
        val parsed = text.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { line ->
                val parts = line.split(',', '，').map { it.trim() }
                // 单段为仅地址；两段为 名称,url；三段及以上为 名称,url,备注（多余段并入备注）
                val url = parts.getOrNull(1) ?: parts[0]
                if (!url.startsWith("http") && !url.contains(".")) return@mapNotNull null
                val name = (if (parts.size >= 2) parts[0] else "").ifBlank {
                    url.removePrefix("https://").removePrefix("http://").substringBefore('/')
                }
                TvSourceEntity(name = name, url = url, note = parts.drop(2).joinToString(","))
            }
        parsed.forEach { insert(it) }
        refresh()
        parsed.size
    }

    /** 从本地 txt 文件导入直播源，返回导入条数（读取失败返回 0） */
    override suspend fun importFile(path: String): Int = withContext(Dispatchers.IO) {
        val text = runCatching { java.io.File(path).readText(Charsets.UTF_8) }.getOrNull()
            ?: return@withContext 0
        batchInsert(text)
    }

    override suspend fun insert(source: TvSourceEntity): Long = withContext(Dispatchers.IO) {
        val id = db.insert(
            "INSERT INTO tv_sources(name, url, note, createdAt) VALUES (?,?,?,?)",
            listOf(source.name, source.url, source.note, source.createdAt),
        )
        refresh()
        id
    }

    override suspend fun deleteById(id: Long) = withContext(Dispatchers.IO) {
        db.exec("DELETE FROM tv_sources WHERE id=?", listOf(id))
        refresh()
    }

    override suspend fun count(): Int = withContext(Dispatchers.IO) {
        db.query("SELECT COUNT(*) AS c FROM tv_sources") { it.getInt(1) }.firstOrNull() ?: 0
    }

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

    /** 当前选中的直播源 ID（持久化，未设置返回 null） */
    override fun currentTvId(): Long? =
        prefs.getLong(DesktopPrefs.KEY_CURRENT_TV_ID).takeIf { it > 0 }

    /**
     * 拉取并解析播放列表，返回按分组归类的频道。
     * 支持 M3U（#EXTINF）与 TVBox TXT（#genre# 分组行）两种格式。
     */
    override suspend fun loadChannels(source: TvSourceEntity): List<TvChannel> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(source.url).get().build()
        SpiderHttp.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code}")
            }
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

    /**
     * 解析 TVBox TXT 格式播放列表：
     * 央视频道,#genre#     ← 分组行
     * CCTV1,http://a#http://b   ← 频道行（多线路以 # 分隔）
     */
    private fun parseTxt(content: String): List<TvChannel> {
        data class PendingChannel(
            val name: String,
            val group: String,
            val urls: MutableList<String> = mutableListOf(),
        )

        val ordered = ArrayList<PendingChannel>()
        val byKey = LinkedHashMap<String, PendingChannel>()
        var pendingGroup = "其他"

        content.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEach
            // 分组行："组名,#genre#" 或 "组名#genre#"
            if (line.endsWith("#genre#", ignoreCase = true)) {
                pendingGroup = line.removeSuffix("#genre#").trim().removeSuffix(",").trim()
                    .ifBlank { "其他" }
                return@forEach
            }
            if (line.startsWith("#")) return@forEach
            val parts = line.split(",", "，")
            if (parts.size < 2) return@forEach
            val name = parts[0].trim()
            if (name.isBlank()) return@forEach
            val urls = parts.drop(1)
                .flatMap { it.split("#") }
                .map { it.trim() }
                .filter { it.contains("://") }
            if (urls.isEmpty()) return@forEach
            val existing = byKey[name]
            if (existing != null) {
                urls.forEach { url -> if (url !in existing.urls) existing.urls.add(url) }
            } else {
                val pending = PendingChannel(name = name, group = pendingGroup, urls = urls.toMutableList())
                byKey[name] = pending
                ordered.add(pending)
            }
        }
        if (ordered.isEmpty()) throw IllegalStateException("未解析到任何频道")
        return ordered.map { TvChannel(name = it.name, urls = it.urls, group = it.group, logo = "") }
    }

    /**
     * 解析 M3U 文本：同名频道合并为一条、追加线路。
     */
    private fun parseM3u(content: String): List<TvChannel> {
        data class PendingChannel(
            val name: String,
            val group: String,
            val logo: String,
            val urls: MutableList<String> = mutableListOf(),
        )

        val ordered = ArrayList<PendingChannel>()
        val byKey = LinkedHashMap<String, PendingChannel>()
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
                    // URL 行：与最近一条 EXTINF 配对；同名频道合并为一条、追加线路
                    val name = (pendingName ?: line.substringAfterLast('/').substringBefore('?'))
                        .ifBlank { "未命名频道" }
                    val existing = byKey[name]
                    if (existing != null) {
                        if (line !in existing.urls) existing.urls.add(line)
                    } else {
                        val pending = PendingChannel(
                            name = name,
                            group = pendingGroup?.takeIf { it.isNotBlank() } ?: "其他",
                            logo = pendingLogo.orEmpty(),
                            urls = mutableListOf(line),
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
}
