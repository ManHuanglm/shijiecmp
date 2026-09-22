package com.shiping.desktop.data.repository

import com.shiping.app.data.repository.TvBoxConfigParser
import com.shiping.desktop.data.model.ApiSourceEntity
import com.shiping.desktop.data.model.ParseSourceEntity
import com.shiping.desktop.data.model.TvSourceEntity
import com.shiping.shared.repository.ApiSourceRepository
import com.shiping.shared.repository.ParseSourceRepository
import com.shiping.shared.repository.TvRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
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
     * @param label 写入各源备注的配置名（默认用地址）
     */
    suspend fun import(url: String, label: String = ""): Summary {
        return try {
            val data = fetch(url)
            val parsed = TvBoxConfigParser.parse(data)
            when {
                !parsed.ok -> Summary(error = parsed.unsupportedReason)
                parsed.warehouses.isNotEmpty() -> Summary(warehouses = parsed.warehouses)
                else -> importParsed(parsed, label.ifBlank { url })
            }
        } catch (e: Exception) {
            Summary(error = "配置拉取失败：${e.message ?: "网络错误"}")
        }
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
                        ext = site.ext,
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

        // 直播源
        for (live in parsed.lives) {
            val url = live.url.trim()
            tvRepository.insert(
                TvSourceEntity(name = live.name, url = url, note = "TVBox·$label"),
            )
            liveImported++
        }

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
