package com.shiping.desktop.data.spider

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.shiping.desktop.data.model.Category
import com.shiping.desktop.data.model.Vod
import com.shiping.desktop.util.Constants

/**
 * 爬虫 JSON 结果 -> 应用模型映射：
 * - vod 列表字段按 TVBox 规格映射，vodId 经 SpiderIdRegistry 转为稳定 Int
 * - homeContent 的 class 数组映射为应用分类（Int id 顺序分配，另存 id 到真实 tid 的映射）
 * - detailContent 的 vod_play_from/vod_play_url 填充进 Vod 原始字段，复用 parsePlaySources 拆线
 */
object SpiderMapper {

    /** 应用分类 Int id 起始值（0 保留给"首页推荐"） */
    private const val CATEGORY_ID_START = 1

    /** playerContent 的 parse 取值：1 表示 url 为网页需要解析 */
    internal const val PARSE_NEEDED = 1

    /** 分页信息缺省页数 */
    private const val DEFAULT_PAGE_COUNT = 1

    /** 爬虫返回的本地代理地址占位（catvod Proxy 未注入端口时的默认值） */
    private const val LOCAL_PROXY_PLACEHOLDER = "http://127.0.0.1:-1"

    /** 本地代理地址前缀（真实端口运行时注入） */
    private const val LOCAL_PROXY_PREFIX = "http://127.0.0.1:"

    /** B 站 playUrl 清晰度协议格式：cid+aid+qn 列表（冒号分隔）+画质映射（可选，冒号分组） */
    private val BILI_PLAYURL_PATTERN = Regex("^\\d+\\+\\d+\\+\\d+(?::\\d+)*(?:\\+.+)?$")

    /** B 站协议固定段数：cid、aid、qn 列表，其后均为画质映射段 */
    private const val BILI_CORE_SEGMENT_COUNT = 3

    /** vod 列表 JSON -> Vod 列表 */
    fun parseVodList(json: String, sourceUrl: String): List<Vod> {
        val array = jsonObject(json)?.getAsJsonArray("list") ?: return emptyList()
        return array.mapNotNull { element ->
            element.takeIf { it.isJsonObject }?.asJsonObject
                ?.let { mapVod(it, sourceUrl) }
        }
    }

    /**
     * homeContent 的 class 数组 -> 应用分类与 Int id 到真实 tid 的映射。
     * 真实 tid 可能为任意字符串，映射由仓库按源地址缓存。
     */
    fun parseCategories(json: String): Pair<List<Category>, Map<Int, String>> {
        val array = jsonObject(json)?.getAsJsonArray("class") ?: return emptyList<Category>() to emptyMap()
        val categories = mutableListOf<Category>()
        val tidMap = mutableMapOf<Int, String>()
        var index = CATEGORY_ID_START
        for (element in array) {
            val o = element.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            val realTid = optString(o, "type_id") ?: continue
            categories += Category(typeId = index, typeName = optString(o, "type_name") ?: "未命名")
            tidMap[index] = realTid
            index++
        }
        return categories to tidMap
    }

    /** detailContent JSON -> Vod（含播放线路原始字段，供 parsePlaySources 拆分） */
    fun parseDetail(json: String, sourceUrl: String): Vod? {
        val o = jsonObject(json)?.getAsJsonArray("list")
            ?.firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        return mapVod(o, sourceUrl)
    }

    /** categoryContent JSON 的分页信息：(pagecount, total) */
    fun parsePaging(json: String): Pair<Int, Int> {
        val o = jsonObject(json) ?: return DEFAULT_PAGE_COUNT to 0
        val pagecount = o.get("pagecount")?.takeIf { it.isJsonPrimitive }?.asInt ?: DEFAULT_PAGE_COUNT
        val total = o.get("total")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
        return pagecount to total
    }

    /**
     * playerContent JSON -> SpiderPlayInfo：
     * - parse=1 且 url 非直链时，拼第一个启用解析源前缀（parserUrl 为空则原样返回）
     * - parse=0 且 playUrl 非空时，playUrl 作为前缀拼接（TVBox 常见约定）
     * - playUrl 为 B 站清晰度协议文本时按协议解析，url 保持直链不拼前缀
     */
    fun parsePlayInfo(json: String, parserUrl: String?): SpiderPlayInfo {
        val o = jsonObject(json) ?: return SpiderPlayInfo(url = "")
        val parse = o.get("parse")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
        val playUrlPrefix = optString(o, "playUrl").orEmpty()
        // B 站爬虫两种形态归一：
        // 1) url 为「画质名,代理地址,画质名,代理地址,...」成对数组（payload 现行形态），
        //    直接生成清晰度选项并取首地址播放
        // 2) playUrl 为清晰度协议文本（url 为单码率直链）
        val arrayProtocol = (o.get("url") as? JsonArray)?.let { parseBiliQualityArray(it) }
        val biliProtocol = arrayProtocol ?: parseBiliQualityProtocol(playUrlPrefix)
        var url = arrayProtocol?.directUrl ?: optString(o, "url").orEmpty()
        // 部分爬虫 jar 自带 catvod Proxy 副本，拼出的本地代理地址端口为默认值 -1，
        // 统一改写为宿主本地代理真实端口（SpiderProxyPort 由各端桥回调写入），否则播放器报 invalid port
        url = url.replace(LOCAL_PROXY_PLACEHOLDER, LOCAL_PROXY_PREFIX + SpiderProxyPort.current)
        when {
            biliProtocol != null -> Unit
            parse == PARSE_NEEDED && !isDirectStream(url) && !parserUrl.isNullOrBlank() -> url = parserUrl + url
            // url 为空时不做前缀拼接（playUrl 描述符不是可播放地址，拼上去只会喂坏播放器）
            parse == 0 && playUrlPrefix.isNotBlank() && url.isNotBlank() -> url = playUrlPrefix + url
        }
        val headers = parseHeader(o.get("header"))
        return SpiderPlayInfo(
            url = url,
            // B 站直链有防盗链校验，header 缺 UA/Referer 时补齐
            headers = if (biliProtocol != null) ensureBiliPlayHeaders(headers) else headers,
            parse = parse,
            qualityOptions = biliProtocol?.options ?: emptyList(),
            biliCid = biliProtocol?.cid ?: 0L,
            biliAid = biliProtocol?.aid ?: 0L,
        )
    }

    /** B 站协议解析结果：cid/aid 与按 qn 顺序排列的清晰度选项；directUrl 为数组形态的首地址 */
    private data class BiliQualityProtocol(
        val cid: Long,
        val aid: Long,
        val options: List<BiliQualityOption>,
        val directUrl: String? = null,
    )

    /**
     * 识别并解析 B 站 playUrl 清晰度协议，形如：
     * 「cid+aid+qn1:qn2+名称1+标签1:名称2+标签2」（例：116901208397383+39854541041+32:16+清晰+480P:流畅+360P），
     * 画质映射组顺序与 qn 列表一一对应；映射缺失或数量不符时用内置 qn->名称表兜底。
     * 非 B 站协议或关键字段解析失败返回 null，调用方保持原有前缀拼接逻辑。
     */
    private fun parseBiliQualityProtocol(playUrl: String): BiliQualityProtocol? {
        if (playUrl.isBlank() || playUrl.startsWith("http")) return null
        if (!BILI_PLAYURL_PATTERN.matches(playUrl)) return null
        val segments = playUrl.split("+")
        val cid = segments.getOrNull(0)?.toLongOrNull() ?: return null
        val aid = segments.getOrNull(1)?.toLongOrNull() ?: return null
        val qnList = segments.getOrNull(BILI_CORE_SEGMENT_COUNT - 1)
            ?.split(":")?.mapNotNull { it.toIntOrNull() }
            .orEmpty()
        if (qnList.isEmpty()) return null
        val mappingSegments = segments.drop(BILI_CORE_SEGMENT_COUNT)
        return BiliQualityProtocol(cid, aid, buildBiliQualityOptions(qnList, mappingSegments))
    }

    /**
     * 解析 B 站 playerContent 的多清晰度数组形态：url = [画质名, 代理地址, 画质名, 代理地址, ...]。
     * aid/cid/qn 从代理地址的查询参数提取，清晰度选项顺序与数组一致。
     * 数组结构不符或首地址缺 aid/cid 时返回 null，调用方走 playUrl 协议文本兼容路径。
     */
    private fun parseBiliQualityArray(array: JsonArray): BiliQualityProtocol? {
        if (array.size() < 2 || array.size() % 2 != 0) return null
        val pairs = ArrayList<Pair<String, String>>(array.size() / 2)
        var index = 0
        while (index + 1 < array.size()) {
            val label = array.get(index)?.takeIf { it.isJsonPrimitive }?.asString
            val link = array.get(index + 1)?.takeIf { it.isJsonPrimitive }?.asString
            if (label.isNullOrBlank() || link.isNullOrBlank()) return null
            pairs += label to link
            index += 2
        }
        val first = pairs.first().second
        val aid = queryParam(first, "aid")?.toLongOrNull() ?: return null
        val cid = queryParam(first, "cid")?.toLongOrNull() ?: return null
        val options = pairs.map { (label, link) ->
            BiliQualityOption(queryParam(link, "qn")?.toIntOrNull() ?: 0, label)
        }
        return BiliQualityProtocol(cid, aid, options, first)
    }

    /** 提取 URL 查询参数值（缺失返回 null） */
    private fun queryParam(link: String, key: String): String? =
        Regex("[?&]$key=([^&#]+)").find(link)?.groupValues?.get(1)

    /**
     * 由画质映射段生成清晰度选项（映射组顺序与 qn 顺序一一对应）：
     * - 组尾为 qn 数字（如「高清+80」）时按规格取该数字为 qn，其余为名称
     * - 组尾为分辨率标签（如「清晰+480P」）时按位置与 qn 配对，整组文本作展示标签
     */
    private fun buildBiliQualityOptions(
        qnList: List<Int>,
        mappingSegments: List<String>,
    ): List<BiliQualityOption> {
        val groups = mappingSegments.joinToString("+")
            .split(":")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        // 映射缺失或数量与 qn 不符：内置 qn->名称表兜底
        if (groups.size != qnList.size) {
            return qnList.map { qn -> BiliQualityOption(qn, Constants.BILI_QN_LABELS[qn] ?: "清晰度$qn") }
        }
        return groups.mapIndexed { index, group ->
            val qn = qnList[index]
            val parts = group.split("+").map { it.trim() }.filter { it.isNotEmpty() }
            val tailQn = parts.lastOrNull()?.toIntOrNull()
            if (tailQn != null && tailQn in qnList) {
                BiliQualityOption(tailQn, parts.dropLast(1).joinToString(" ").ifBlank { parts.last() })
            } else {
                BiliQualityOption(qn, parts.joinToString(" ").ifBlank { Constants.BILI_QN_LABELS[qn] ?: "清晰度$qn" })
            }
        }
    }

    /** B 站直链需要浏览器 UA 与 B 站 Referer 防盗链，爬虫 header 未携带时补齐 */
    private fun ensureBiliPlayHeaders(headers: Map<String, String>): Map<String, String> {
        val hasUa = headers.keys.any { it.equals("User-Agent", ignoreCase = true) }
        val hasReferer = headers.keys.any { it.equals("Referer", ignoreCase = true) }
        if (hasUa && hasReferer) return headers
        val result = headers.toMutableMap()
        if (!hasUa) result["User-Agent"] = Constants.API_USER_AGENT
        if (!hasReferer) result["Referer"] = Constants.BILI_REFERER
        return result
    }

    private fun mapVod(o: JsonObject, sourceUrl: String): Vod? {
        val realId = optString(o, "vod_id") ?: return null
        return Vod(
            vodId = SpiderIdRegistry.toIntKey(sourceUrl, realId),
            vodName = optString(o, "vod_name").orEmpty(),
            vodPic = optString(o, "vod_pic").orEmpty(),
            vodRemarks = optString(o, "vod_remarks").orEmpty(),
            vodContent = optString(o, "vod_content").orEmpty(),
            vodActor = optString(o, "vod_actor").orEmpty(),
            vodDirector = optString(o, "vod_director").orEmpty(),
            vodClass = optString(o, "vod_class").orEmpty(),
            vodYear = optString(o, "vod_year").orEmpty(),
            vodArea = optString(o, "vod_area").orEmpty(),
            vodLang = optString(o, "vod_lang").orEmpty(),
            vodTag = optString(o, "vod_tag").orEmpty(),
            typeName = optString(o, "type_name").orEmpty(),
            vodPlayFrom = optString(o, "vod_play_from").orEmpty(),
            vodPlayUrl = optString(o, "vod_play_url").orEmpty(),
        )
    }

    /** header 字段支持对象与字符串（内嵌 JSON，部分爬虫双重编码）两种形态，键值均取字符串 */
    private fun parseHeader(element: JsonElement?): Map<String, String> {
        val o = when {
            element == null || element.isJsonNull -> return emptyMap()
            element.isJsonObject -> element.asJsonObject
            element.isJsonPrimitive -> runCatching {
                JsonParser.parseString(element.asString).takeIf { it.isJsonObject }?.asJsonObject
            }.getOrNull() ?: return emptyMap()
            else -> return emptyMap()
        }
        val result = mutableMapOf<String, String>()
        for ((key, value) in o.entrySet()) {
            val v = value.takeIf { it.isJsonPrimitive }?.asString ?: continue
            result[key] = v
        }
        return result
    }

    private fun isDirectStream(url: String): Boolean {
        val path = url.substringBefore("?").lowercase()
        return Constants.DIRECT_STREAM_SUFFIXES.any { path.endsWith(it) }
    }

    private fun jsonObject(json: String): JsonObject? = runCatching {
        JsonParser.parseString(json).takeIf { it.isJsonObject }?.asJsonObject
    }.getOrNull()

    private fun optString(o: JsonObject, key: String): String? {
        val value = o.get(key)?.takeIf { it.isJsonPrimitive } ?: return null
        return runCatching { value.asString }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
    }
}
