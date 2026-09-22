package com.shiping.app.data.repository

import com.google.gson.JsonElement
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * TVBox 配置仓解析器。
 *
 * 支持的配置形态：
 * - 纯文本 JSON（容忍 BOM、前导杂字符、// 行注释与块注释）
 * - 图片壳：JPEG/PNG 图片尾部追加 Base64 编码的 JSON（饭太硬等）
 * - 多仓：{"urls" | "warehouse" | "storeHouse": [...]}
 * - 单仓：{"sites": [...], "parses": [...], "lives": [...]}
 *
 * 提取范围（应用能力内可直连的部分）：
 * - sites：type 0（MacCMS V10 JSON）与 type 1（XML，/at/xml 结尾的自动转 JSON 地址）；
 *   type 3 且 api 以 csp_ 开头的爬虫源在有 jar（站点级或全局 spider）时提取，由爬虫运行时执行，
 *   其余带 jar 或其余 type 的站点不提取仅计数
 * - parses：前缀拼接式解析接口（url 以 = 结尾），转应用的解析源
 * - lives：type 0 的播放列表地址（M3U），转应用的直播源
 *
 * 加密配置：支持 `**` Base64 壳（"abcdefgh**" 标记后为 Base64 的 JSON）与
 * $# AES-CBC 加密（整体 hex，切片算法与 FongMi Decoder.cbc 一致：
 * "$#key#$" + 密文 + 13 字符 iv，key/iv 不足 16 位右侧补 '0'）；解密失败时明确提示。
 */
object TvBoxConfigParser {

    /** TVBox 站点类型：3=爬虫源（协议常量） */
    const val SITE_TYPE_SPIDER = 3

    /** 爬虫站点 key 前缀 */
    private const val CSP_KEY_PREFIX = "csp_"

    /** 单仓中可提取的站点（type 3 为爬虫源，url 存爬虫类键如 csp_XXX） */
    data class TvBoxSite(
        val name: String,
        val url: String,
        val isXml: Boolean,
        /** TVBox 站点类型：0/1=CMS 接口，3=爬虫源 */
        val type: Int = 0,
        /** 爬虫 jar 地址（已取分号前 URL），仅 type==3 使用 */
        val jar: String = "",
        /** 站点 ext 配置（对象/数组序列化为 JSON 字符串），仅 type==3 使用 */
        val ext: String = "",
        /** 站点 key 字段（TVBox 站点唯一标识，同 api 的多源靠它区分），仅 type==3 使用，缺省回退 url */
        val siteKey: String = "",
    )

    /** 前缀拼接式解析接口 */
    data class TvBoxParse(val name: String, val url: String)

    /** 直播播放列表地址 */
    data class TvBoxLive(val name: String, val url: String)

    data class Result(
        val ok: Boolean = false,
        /** 非空表示这是多仓配置，元素为 名称 to 子配置地址 */
        val warehouses: List<Pair<String, String>> = emptyList(),
        val sites: List<TvBoxSite> = emptyList(),
        val parses: List<TvBoxParse> = emptyList(),
        val lives: List<TvBoxLive> = emptyList(),
        /** 需要 spider 运行时而被跳过的站点数 */
        val spiderCount: Int = 0,
        /** 全局 spider jar 地址（分号前 URL），供 type 3 站点兜底 */
        val spiderJar: String = "",
        /** ok=false 时的原因说明 */
        val unsupportedReason: String? = null,
    )

    fun parse(data: ByteArray): Result {
        if (data.isEmpty()) return Result(unsupportedReason = "配置内容为空")

        var text = decodeText(data)
        if (isJpeg(data)) text = imagePayloadText(data, JPEG_EOI) ?: text
        if (isPng(data)) text = imagePayloadText(data, PNG_IEND) ?: text

        val jsonText = decodeShell(text)
            ?: return Result(unsupportedReason = "加密配置解密失败（\$# AES 与 ** 壳均未能解出 JSON）")

        val element = parseJsonBody(jsonText)
            ?: return Result(unsupportedReason = "配置中未找到 JSON 内容")

        return when {
            element.isJsonObject -> parseObject(element.asJsonObject)
            element.isJsonArray -> parseSitesArray(element.asJsonArray)
            else -> Result(unsupportedReason = "配置格式无法识别")
        }
    }

    // region 文本与图片壳预处理

    private fun isJpeg(data: ByteArray) =
        data.size > 4 && data[0] == 0xFF.toByte() && data[1] == 0xD8.toByte()

    private fun isPng(data: ByteArray) =
        data.size > 8 && data[0] == 0x89.toByte() && data[1] == 0x50.toByte()

    /**
     * 取图片结束符之后的 Base64 载荷并解码为文本（配置藏在图片字节后面）：
     * 带 "**" 标记（8 位标记 + "**"，如饭太硬）取 ** 之后整体去空白解码——
     * Base64 常为多行文本，不可逐段匹配取最长单行（只解出单行碎片导致解析失败）；
     * 无标记的图片壳为纯 Base64 追加，取最长连续 Base64 段解码。
     */
    private fun imagePayloadText(data: ByteArray, terminator: ByteArray): String? {
        val end = lastIndexOf(data, terminator)
        if (end < 0) return null
        val payload = String(
            data.copyOfRange(end + terminator.size, data.size),
            Charsets.ISO_8859_1,
        )
        val base64 = if (payload.contains("**")) {
            payload.substringAfter("**").replace(WHITESPACE, "")
        } else {
            Regex("[A-Za-z0-9+/=]{16,}").findAll(payload)
                .map { it.value }
                .maxByOrNull { it.length }
                ?.replace(WHITESPACE, "")
                ?: return null
        }
        if (base64.length < 16) return null
        val decoded = runCatching {
            Base64.getMimeDecoder().decode(base64)
        }.getOrNull() ?: return null
        return String(decoded, Charsets.UTF_8)
    }

    private fun lastIndexOf(data: ByteArray, pattern: ByteArray): Int {
        for (i in data.size - pattern.size downTo 0) {
            var matched = true
            for (j in pattern.indices) {
                if (data[i + j] != pattern[j]) { matched = false; break }
            }
            if (matched) return i
        }
        return -1
    }

    /**
     * 文本解码：仅去 BOM 与首尾空白。hex 加密配置原样保留 hex 文本，
     * 由 decodeShell/cbcDecrypt 在 hex 字符串上切片处理（严禁解码为
     * 二进制字符串——密文字节中与空白同值的字节会被误删导致解密失败）。
     */
    private fun decodeText(data: ByteArray): String =
        String(data, Charsets.UTF_8).trim().removePrefix("\uFEFF")

    /**
     * 配置解壳（与 FongMi Decoder.verify 流程一致）：形似 JSON 原样返回；
     * 含 "**" 时提取其后 Base64 载荷解码；结果以 "2423"/"$#" 开头再做 AES-CBC 解密。
     * 解密执行但失败返回 null，其余情况返回处理后文本。
     */
    private fun decodeShell(text: String): String? {
        val data = text.trim()
        if (data.isEmpty() || data.startsWith("{") || data.startsWith("[")) return data
        var out = data
        if (out.contains("**")) {
            val payload = extractBase64Payload(out)
            if (!payload.isNullOrEmpty()) {
                val decoded = runCatching {
                    String(Base64.getMimeDecoder().decode(payload), Charsets.UTF_8)
                }.getOrNull()
                if (!decoded.isNullOrBlank()) out = decoded
            }
        }
        val lowered = out.lowercase()
        if (lowered.startsWith("2423") || lowered.startsWith("\$#")) {
            return runCatching { cbcDecrypt(out) }.getOrNull()
        }
        return out
    }

    /** 取 8 位字母数字 + "**" 标记之后的 Base64 载荷 */
    private fun extractBase64Payload(data: String): String? {
        val match = BASE64_SHELL.find(data) ?: return null
        return data.substring(data.indexOf(match.value) + 10)
    }

    /**
     * AES-CBC 解密（与 FongMi Decoder.cbc 一致）：整体为 hex 文本，全部切片在
     * hex 字符串上完成。key/iv 取自 hex 解码后的 UTF-8 文本（小写，不足 16 位补 '0'）；
     * 密文段为 "2324"（"#$" 的 hex）之后、去掉末尾 26 个 hex 字符（13 字节 iv）的
     * hex 段。真实配置的密文二进制中存在与空白字符同值的字节，若先解码成字符串
     * 再 strip 空白会删掉密文字节导致解密失败，因此全程不得解码后再切片。
     */
    private fun cbcDecrypt(hex: String): String {
        val compact = hex.replace(WHITESPACE, "")
        check(HEX_ONLY.matches(compact) && compact.length % 2 == 0) { "信封不是合法 hex 文本" }
        val decoded = String(hexToBytes(compact), Charsets.UTF_8).lowercase()
        val keyStart = decoded.indexOf("\$#") + 2
        val keyEnd = decoded.indexOf("#\$")
        check(keyStart >= 2 && keyEnd > keyStart) { "缺少 \$#/#\$ 标记" }
        check(decoded.length > 13) { "信封长度不足" }
        val cipherStart = compact.indexOf("2324") + 4
        val cipherEnd = compact.length - 26
        check(cipherEnd > cipherStart && (cipherEnd - cipherStart) % 32 == 0) { "密文长度非法" }
        val decrypted = decryptAesCbc(
            hexToBytes(compact.substring(cipherStart, cipherEnd)),
            padEnd(decoded.substring(keyStart, keyEnd)).toByteArray(Charsets.UTF_8),
            padEnd(decoded.substring(decoded.length - 13)).toByteArray(Charsets.UTF_8),
        )
        return String(decrypted, Charsets.UTF_8).trim()
    }

    /** 不足 16 位右侧补 '0' */
    private fun padEnd(value: String): String =
        if (value.length >= 16) value else value + "0000000000000000".substring(value.length)

    /** AES/CBC/PKCS5Padding 解密（与 catvod Crypto.decryptAesCbc 同实现，jvmCommon 不依赖桥） */
    private fun decryptAesCbc(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(data)
    }

    /**
     * 杂质文本（配置常带免责声明/注释前缀）中提取 JSON 并解析：
     * 先以对象为锚（最后一个 '{' 起向前逐候选，到最末 '}'），失败再以数组为锚——
     * 杂质里的方括号（如「[所有内容仅供学习使用]」）不会误判为数组起点；
     * 锚定后移除字符串外的行注释与块注释（字符串内的 "//" 不受影响），
     * 逐候选尝试解析，成功即返回。
     */
    private fun parseJsonBody(raw: String): JsonElement? {
        val trimmed = raw.trim().removePrefix("\uFEFF")
        // 明文整体 JSON（首尾锚齐全）优先整体解析：数组式配置 [{...},...] 若走下方
        // 对象锚扫描，会被数组内最后一个对象短路成单站点对象而丢失其余条目
        if (trimmed.length >= 2) {
            val head = trimmed.first()
            val tail = trimmed.last()
            if ((head == '{' && tail == '}') || (head == '[' && tail == ']')) {
                val whole = runCatching {
                    JsonParser.parseString(stripComments(trimmed))
                }.getOrNull()
                if (whole != null) return whole
            }
        }
        for (anchor in sequenceOf('{' to '}', '[' to ']')) {
            val end = trimmed.lastIndexOf(anchor.second)
            if (end < 0) continue
            // 从最后一个开锚向前逐候选（杂质可能也含开锚字符，真实 JSON 通常整体靠后）
            var start = trimmed.lastIndexOf(anchor.first, end)
            while (start >= 0) {
                val element = runCatching {
                    JsonParser.parseString(stripComments(trimmed.substring(start, end + 1)))
                }.getOrNull()
                if (element != null) return element
                start = if (start == 0) -1 else trimmed.lastIndexOf(anchor.first, start - 1)
            }
        }
        return null
    }

    /** 移除字符串外的行注释与块注释（字符串内的 "//" 等不受影响） */
    private fun stripComments(body: String): String {
        val sb = StringBuilder(body.length)
        var inString = false
        var escaped = false
        var i = 0
        while (i < body.length) {
            val c = body[i]
            if (inString) {
                sb.append(c)
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                i++
                continue
            }
            when {
                c == '"' -> { inString = true; sb.append(c); i++ }
                c == '/' && i + 1 < body.length && body[i + 1] == '*' -> {
                    i += 2
                    while (i + 1 < body.length && !(body[i] == '*' && body[i + 1] == '/')) i++
                    i = minOf(i + 2, body.length)
                }
                c == '/' && i + 1 < body.length && body[i + 1] == '/' -> {
                    while (i < body.length && body[i] != '\n') i++
                }
                else -> { sb.append(c); i++ }
            }
        }
        return sb.toString().trim()
    }

    // endregion

    // region 结构解析

    private fun parseObject(obj: JsonObject): Result {
        // 多仓：urls / warehouse / storeHouse 数组
        val warehouses = mutableListOf<Pair<String, String>>()
        for (key in listOf("urls", "warehouse", "storeHouse", "storehouses")) {
            val arr = obj.get(key)?.takeIf { it.isJsonArray }?.asJsonArray ?: continue
            for (item in arr) {
                val o = item.takeIf { it.isJsonObject }?.asJsonObject ?: continue
                val name = optString(o, "name", "title", "sourceName").orEmpty()
                val url = optString(o, "url", "sourceUrl")
                if (!url.isNullOrBlank() && url.startsWith("http")) {
                    warehouses += name.ifBlank { url } to url.trim()
                }
            }
        }
        if (warehouses.isNotEmpty()) return Result(ok = true, warehouses = warehouses)

        // sites 缺失时容忍：只要 parses/lives 有内容仍可导入
        val sites = obj.get("sites")?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()
        val globalSpiderJar = extractJarUrl(optString(obj, "spider", "spiderEx", "jar")).orEmpty()
        val sitesResult = parseSitesArray(sites, globalSpiderJar).let { r ->
            r.copy(
                parses = extractParses(obj),
                lives = extractLives(obj),
            )
        }
        if (sitesResult.sites.isEmpty() && sitesResult.parses.isEmpty() &&
            sitesResult.lives.isEmpty() && sitesResult.spiderCount == 0
        ) {
            return Result(unsupportedReason = "配置中未找到 sites/parses/lives 内容")
        }
        return sitesResult
    }

    private fun parseSitesArray(arr: JsonArray, globalSpiderJar: String = ""): Result {
        val sites = mutableListOf<TvBoxSite>()
        var spider = 0
        for (item in arr) {
            val o = item.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            val api = optString(o, "api")?.trim().orEmpty()
            val jar = extractJarUrl(optString(o, "jar"))
            val type = optInt(o, "type")
            // 爬虫源（type 3 且 csp_ key）：有 jar（站点级优先，否则全局）即可导入运行
            if (type == SITE_TYPE_SPIDER && api.startsWith(CSP_KEY_PREFIX)) {
                val jarUrl = jar ?: globalSpiderJar
                if (jarUrl.isNullOrBlank()) {
                    spider++
                    continue
                }
                val name = optString(o, "name") ?: optString(o, "key") ?: "未命名"
                // key 字段为站点唯一标识（如 幼儿启蒙/小灯塔），同 api(csp_XXX) 的多源靠它区分
                val siteKey = optString(o, "key")?.trim().orEmpty().ifBlank { api }
                sites += TvBoxSite(
                    name, api, isXml = false, type = SITE_TYPE_SPIDER,
                    jar = jarUrl, ext = optExt(o), siteKey = siteKey,
                )
                continue
            }
            // 其余需要爬虫运行时（带 jar、type 3 非 csp 等）或非直连的站点：不提取仅计数
            if (!api.startsWith("http") || jar != null || (type != 0 && type != 1)) {
                spider++
                continue
            }
            val name = optString(o, "name") ?: optString(o, "key") ?: "未命名"
            val xmlEndpoint = XML_SUFFIX.find(api) != null
            // XML 接口地址以 /at/xml 结尾时剥掉后缀即为 JSON 接口（与 type 无关，V1/V10 均适用）
            when {
                xmlEndpoint -> sites += TvBoxSite(name, api.replace(XML_SUFFIX, ""), isXml = false)
                type == 1 -> sites += TvBoxSite(name, api, isXml = true)
                else -> sites += TvBoxSite(name, api, isXml = false)
            }
        }
        return Result(ok = true, sites = sites, spiderCount = spider, spiderJar = globalSpiderJar)
    }

    /** 提取 jar 地址：保留分号分隔完整串（备用地址/md5 校验段由运行时解析），仅要求首段为 http */
    private fun extractJarUrl(raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        val first = value.substringBefore(";").trim()
        return value.takeIf { first.startsWith("http") }
    }

    private fun extractParses(obj: JsonObject): List<TvBoxParse> {
        val arr = obj.get("parses")?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
        val result = mutableListOf<TvBoxParse>()
        for (item in arr) {
            val o = item.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            val url = optString(o, "url")?.trim().orEmpty()
            val type = optInt(o, "type")
            // 仅取前缀拼接式（url 以 = 结尾），type 0/1；网页壳解析对直连播放无意义
            if (!url.startsWith("http") || !url.endsWith("=")) continue
            if (type != 0 && type != 1) continue
            val name = optString(o, "name") ?: "未命名"
            result += TvBoxParse(name, url)
        }
        return result
    }

    private fun extractLives(obj: JsonObject): List<TvBoxLive> {
        val arr = obj.get("lives")?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
        val result = mutableListOf<TvBoxLive>()
        for (item in arr) {
            val o = item.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            val url = optString(o, "url")?.trim().orEmpty()
            val type = optInt(o, "type")
            // type 0 为远程播放列表（m3u/txt），其余为内嵌频道组，不处理
            if (type != 0 || !url.startsWith("http")) continue
            val name = optString(o, "name") ?: "未命名"
            result += TvBoxLive(name, url)
        }
        return result
    }

    // endregion

    // region 容错取值

    private fun optString(o: JsonObject, vararg keys: String): String? {
        for (key in keys) {
            val value = o.get(key)?.takeIf { it.isJsonPrimitive } ?: continue
            val s = runCatching { value.asString }.getOrNull() ?: continue
            if (s.isNotBlank()) return s.trim()
        }
        return null
    }

    private fun optInt(o: JsonObject, key: String): Int =
        optString(o, key)?.toDoubleOrNull()?.toInt() ?: -1

    /** ext 字段容错提取：字符串原样返回；对象/数组序列化为紧凑 JSON 字符串（与 FongMi 传参语义一致） */
    private fun optExt(o: JsonObject): String {
        val value = o.get("ext") ?: return ""
        return when {
            value.isJsonPrimitive -> runCatching { value.asString }.getOrNull()?.trim() ?: ""
            value.isJsonObject || value.isJsonArray -> value.toString()
            else -> ""
        }
    }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i ->
            ((Character.digit(hex[i * 2], 16) shl 4) + Character.digit(hex[i * 2 + 1], 16)).toByte()
        }

    // endregion

    private val HEX_ONLY = Regex("^[0-9a-fA-F]+$")
    private val XML_SUFFIX = Regex("/at/xml\\b", RegexOption.IGNORE_CASE)
    private val BASE64_SHELL = Regex("[A-Za-z0-9]{8}\\*\\*")
    private val WHITESPACE = Regex("\\s+")
    private val JPEG_EOI = byteArrayOf(0xFF.toByte(), 0xD9.toByte())
    private val PNG_IEND = byteArrayOf(
        0x49, 0x45, 0x4E, 0x44, 0xAE.toByte(), 0x42, 0x60, 0x82.toByte(),
    )
}
