package com.shiping.desktop.data.repository

import com.shiping.app.data.repository.TvBoxConfigParser
import com.shiping.desktop.data.model.ApiSourceEntity
import com.shiping.desktop.data.model.ParseSourceEntity
import com.shiping.desktop.data.model.TvSourceEntity
import com.shiping.shared.repository.ApiSourceRepository
import com.shiping.shared.repository.ParseSourceRepository
import com.shiping.shared.repository.TvRepository
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/**
 * TVBox 配置导入编排（对齐 app TvBoxConfigImporter）：
 * 拉取配置 → 解析 → 按类别写入 API 源（含爬虫源）/ 解析源 / 直播源。
 * 爬虫源为整体替换；CMS/解析/直播源为追加式、不去重。
 * jvmCommon 跨端共用（桌面/Android 各自注入 OkHttpClient 与仓库实现）。
 */
class TvBoxConfigImporter(
    private val apiSourceRepository: ApiSourceRepository,
    private val parseSourceRepository: ParseSourceRepository,
    private val tvRepository: TvRepository,
    /** 配置拉取客户端（桌面=SpiderHttp.client，Android=SpiderHttp.client），UA 由 fetch 注入 */
    private val client: OkHttpClient,
) {

    data class Summary(
        /** 非空表示是多仓配置，需用户选择子配置后再导入 */
        val warehouses: List<Pair<String, String>> = emptyList(),
        val apiImported: Int = 0,
        val apiXmlDisabled: Int = 0,
        /** 导入的爬虫源（type 3）数量 */
        val spiderImported: Int = 0,
        val parseImported: Int = 0,
        val liveImported: Int = 0,
        val spiderSkipped: Int = 0,
        val error: String? = null,
    ) {
        fun messageText(): String {
            if (error != null) return error
            if (warehouses.isNotEmpty()) return "发现 ${warehouses.size} 个子配置，请选择导入"
            val parts = mutableListOf<String>()
            if (apiImported > 0) parts += "CMS 源 $apiImported 个"
            if (apiXmlDisabled > 0) parts += "XML 接口 $apiXmlDisabled 个（已禁用）"
            if (spiderImported > 0) parts += "爬虫源 $spiderImported 个"
            if (parseImported > 0) parts += "解析源 $parseImported 个"
            if (liveImported > 0) parts += "直播源 $liveImported 条"
            if (spiderSkipped > 0) parts += "spider 源 $spiderSkipped 个（无法运行已跳过）"
            return if (parts.isEmpty()) {
                "配置中未发现可导入的源"
            } else {
                "导入成功：" + parts.joinToString("、")
            }
        }
    }

    /**
     * 拉取并导入一个配置地址（单仓直接导入；多仓返回仓库列表待用户选择）。
     * HTML 落地页（游魂等发布页，内容为网页而非配置）自动提取页面内的候选配置地址
     * 逐个跟进导入（限深防止跳转环），第一个成功者生效。
     * @param label 写入各源备注的配置名（默认用地址）
     */
    suspend fun import(url: String, label: String = ""): Summary {
        return try {
            importRecursive(url, label, depth = 0)
        } catch (e: Exception) {
            Summary(error = "配置拉取失败：${e.message ?: "网络错误"}")
        }
    }

    /** 落地页最大跟进层数（防页面互相跳转成环） */
    /**
     * 剪贴板批量导入：直接解析已获取的配置文本（粘贴的多仓 JSON 内容/合成的多仓串），
     * 不走网络拉取；多仓返回仓库列表供 UI 批量添加。
     */
    suspend fun importRaw(content: String, label: String = ""): Summary = try {
        val parsed = TvBoxConfigParser.parse(content.toByteArray())
        when {
            !parsed.ok -> Summary(error = parsed.unsupportedReason)
            parsed.warehouses.isNotEmpty() -> Summary(warehouses = parsed.warehouses)
            else -> importParsed(parsed, label.ifBlank { "剪贴板" })
        }
    } catch (e: Exception) {
        Summary(error = "配置解析失败：${e.message ?: "内容无法识别"}")
    }

    private val LANDING_MAX_DEPTH = 2

    private suspend fun importRecursive(url: String, label: String, depth: Int): Summary {
        val data = fetch(url)
        val importedLabel = label.ifBlank { url }
        if (depth < LANDING_MAX_DEPTH) {
            val candidates = TvBoxConfigParser.landingCandidates(data, url)
            if (candidates.isNotEmpty()) {
                var lastError = "落地页中未找到可导入的配置"
                for (candidate in candidates) {
                    val nested = importRecursive(candidate, importedLabel, depth + 1)
                    if (nested.error == null) return nested
                    lastError = nested.error
                }
                return Summary(error = "落地页候选配置均不可用（$lastError）")
            }
        }
        val parsed = TvBoxConfigParser.parse(data)
        // 本地包导入：以 json 所在目录为基准，包内 ./ 相对引用改写为 clan:// 绝对路径（一次性解析）
        val resolved = ClanPaths.toFile(url)?.takeIf { it.isFile }?.parentFile
            ?.let { rewriteLocalRefs(parsed, it) } ?: parsed
        return when {
            !resolved.ok -> Summary(error = resolved.unsupportedReason)
            resolved.warehouses.isNotEmpty() -> Summary(warehouses = resolved.warehouses)
            else -> importParsed(resolved, importedLabel)
        }
    }

    /**
     * 本地包相对引用改写（导入时一次性解析，运行时全走 clan:// 绝对路径）：
     * - 站点级/全局 spider jar 的 ./ 段（;md5/备用段原样保留）
     * - js/py 爬虫源 api 的相对脚本路径
     * - ext 两种形态：$$$ 分隔字符串段（如 tokenm.json$$$douban.json）、
     *   JSON 对象/数组内字符串值（如 TGDouban 的 token/json/douban 字段）
     */
    private fun rewriteLocalRefs(parsed: TvBoxConfigParser.Result, baseDir: File): TvBoxConfigParser.Result =
        parsed.copy(
            sites = parsed.sites.map { site ->
                site.copy(
                    url = localizeLocalForm(site.url, baseDir),
                    jar = site.jar.split(";").joinToString(";") { segment -> localizeLocalForm(segment, baseDir) },
                    ext = rewriteExtRefs(site.ext, baseDir),
                )
            },
        )

    /**
     * 单个本地引用归一：先归一 TVBox 生态本地形态（9978/file 自有服务器、file:// 相对、clan 相对），
     * 再把包内 ./ 相对引用按 json 基准目录解析为 clan:// 绝对路径。
     */
    private fun localizeLocalForm(raw: String, baseDir: File): String {
        val normalized = ClanPaths.normalizeTvBoxLocal(raw)
        return if (ClanPaths.isRelativeRef(normalized)) ClanPaths.resolveClan(baseDir, normalized) else normalized
    }

    /** ext 相对引用改写：JSON 形态递归重建，字符串形态按 $$$ 分段逐段解析 */
    private fun rewriteExtRefs(ext: String, baseDir: File): String {
        if (ext.isBlank()) return ext
        if (ext.startsWith("{") || ext.startsWith("[")) {
            return runCatching {
                rewriteJsonRefs(JsonParser.parseString(ext), baseDir).toString()
            }.getOrDefault(ext)
        }
        return ext.split("\$\$\$").joinToString("\$\$\$") { segment -> localizeLocalForm(segment, baseDir) }
    }

    /** 递归重建 JSON：字符串值命中包内相对引用时改写为 clan:// 绝对路径，其余原样保留 */
    private fun rewriteJsonRefs(element: JsonElement, baseDir: File): JsonElement = when {
        element.isJsonObject -> JsonObject().apply {
            element.asJsonObject.entrySet().forEach { (key, value) -> add(key, rewriteJsonRefs(value, baseDir)) }
        }
        element.isJsonArray -> JsonArray().apply {
            element.asJsonArray.forEach { add(rewriteJsonRefs(it, baseDir)) }
        }
        element.isJsonPrimitive && element.asJsonPrimitive.isString &&
            (ClanPaths.isRelativeRef(element.asString) || element.asString.contains("9978/file/")) ->
            JsonPrimitive(localizeLocalForm(element.asString, baseDir))
        else -> element
    }

    private suspend fun importParsed(parsed: TvBoxConfigParser.Result, label: String): Summary {
        var apiImported = 0
        var apiXmlDisabled = 0
        var spiderImported = 0
        var parseImported = 0
        var liveImported = 0

        // 配置内全部源原样导入，不做去重（重复导入由用户自行管理）；
        // 爬虫源为整体替换：本次配置含爬虫源时先清空旧爬虫源，避免新旧同名/同键源并存串数据
        if (parsed.sites.any { it.type == TvBoxConfigParser.SITE_TYPE_SPIDER }) {
            apiSourceRepository.deleteBySiteType(TvBoxConfigParser.SITE_TYPE_SPIDER)
        }
        for (site in parsed.sites) {
            val url = site.url.trim()
            val isSpider = site.type == TvBoxConfigParser.SITE_TYPE_SPIDER
            if (isSpider) {
                // 爬虫源：url 存站点唯一标识（配置 key），spiderKey 存爬虫类键（csp_XXX）
                apiSourceRepository.insert(
                    ApiSourceEntity(
                        name = site.name,
                        url = site.siteKey.ifBlank { url },
                        note = "TVBox·$label·爬虫",
                        enabled = true,
                        siteType = TvBoxConfigParser.SITE_TYPE_SPIDER,
                        jarUrl = site.jar,
                        spiderKey = site.url,
                        ext = normalizeBiliExt(site.url, site.ext),
                        searchable = site.searchable,
                    ),
                )
                apiImported++
                spiderImported++
                continue
            }
            apiSourceRepository.insert(
                ApiSourceEntity(
                    name = site.name,
                    url = url,
                    note = "TVBox·$label" + if (site.isXml) "·XML" else "",
                    enabled = !site.isXml,
                    searchable = site.searchable,
                ),
            )
            if (site.isXml) apiXmlDisabled++ else apiImported++
        }

        // 解析源
        for (parse in parsed.parses) {
            val url = parse.url.trim()
            parseSourceRepository.insert(
                ParseSourceEntity(name = parse.name, url = url, note = "TVBox·$label"),
            )
            parseImported++
        }

        // 直播源：批量去重插入（批内与库中 URL 去重，重复导入配置不产生重复记录）
        liveImported = tvRepository.insertUnique(
            parsed.lives.map { TvSourceEntity(name = it.name, url = it.url.trim(), note = "TVBox·$label") },
        )

        return Summary(
            apiImported = apiImported,
            apiXmlDisabled = apiXmlDisabled,
            spiderImported = spiderImported,
            parseImported = parseImported,
            liveImported = liveImported,
            spiderSkipped = parsed.spiderCount,
        )
    }

    private suspend fun fetch(url: String): ByteArray = withContext(Dispatchers.IO) {
        // 本地包配置：clan://localhost/、file:// 或盘符绝对路径直接读盘
        ClanPaths.toFile(url)?.let { local ->
            if (!local.isFile) throw IOException("本地配置文件不存在: ${local.absolutePath}")
            return@withContext local.readBytes()
        }
        // 对齐 FongMi catvod OkHttp：配置拉取用 okhttp UA——部分配置站（如小虎斑）
        // 按 UA 区分内容：浏览器 UA 返回 HTML 页面，okhttp UA 才返回加密配置文本
        val request = Request.Builder()
            .url(normalizeUrl(url))
            .header("User-Agent", "okhttp/" + okhttp3.OkHttp.VERSION)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            response.body?.bytes() ?: ByteArray(0)
        }
    }

    /** Bili 系站点 api 键 */
    private val BILI_API = "csp_Bili"

    /**
     * Bili 系站点 ext 规范化：新版 Bili 爬虫要求 ext 带 `type`（分类名 `#` 分隔），
     * 源方老格式 json（仅 class/filter，如南风 Bili.json）缺失该字段时，
     * 从 json URL 拉取分类定义取 type_name 拼接补齐；拉取/解析失败返回原 ext 不阻断导入。
     */
    private suspend fun normalizeBiliExt(api: String, ext: String): String {
        if (api != BILI_API) return ext
        val obj = runCatching { JsonParser.parseString(ext).asJsonObject }.getOrNull() ?: return ext
        if (obj.has("type")) return ext
        val jsonUrl = obj.get("json")?.takeIf { it.isJsonPrimitive }?.asString ?: return ext
        if (!jsonUrl.startsWith("http")) return ext
        val typeNames = runCatching {
            JsonParser.parseString(fetch(jsonUrl).decodeToString()).asJsonObject
                .getAsJsonArray("class")
                .mapNotNull { item ->
                    item.takeIf { it.isJsonObject }?.asJsonObject
                        ?.get("type_name")?.takeIf { it.isJsonPrimitive }?.asString
                }
                .filter { it.isNotBlank() }
        }.getOrDefault(emptyList())
        if (typeNames.isEmpty()) return ext
        obj.addProperty("type", typeNames.joinToString("#"))
        return obj.toString()
    }

    /** 中文域名转 punycode（如 饭太硬.net），否则 OkHttp 会拒绝请求 */
    private fun normalizeUrl(raw: String): String = runCatching {
        val trimmed = raw.trim()
        val schemeEnd = trimmed.indexOf("://")
        if (schemeEnd <= 0) return@runCatching trimmed
        val rest = trimmed.substring(schemeEnd + 3)
        val pathStart = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
        val host = if (pathStart >= 0) rest.substring(0, pathStart) else rest
        val after = if (pathStart >= 0) rest.substring(pathStart) else ""
        val asciiHost = if (host.any { it.code > 127 }) {
            java.net.IDN.toASCII(host, java.net.IDN.ALLOW_UNASSIGNED)
        } else {
            host
        }
        trimmed.substring(0, schemeEnd + 3) + asciiHost + after
    }.getOrDefault(raw)
}
