package com.shiping.desktop.ui

import com.google.gson.JsonParser
import com.shiping.desktop.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URI
import java.util.concurrent.TimeUnit

/** 桌面无 WebView：parse=1 场景走无头嗅探（识别已知解析站模板，模拟其解析接口） */
actual val supportsWebSniffing: Boolean = true

/** 页面默认 UA（解析站校验常规浏览器特征） */
private const val DEFAULT_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
    "(KHTML, like Gecko) Chrome/109.0.0.0 Safari/537.36"

/** HHJX 模板启动参数标记（解析页内 __HHJX_BOOTSTRAP__ = {...}） */
private val BOOTSTRAP_REGEX = Regex("""window\.__HHJX_BOOTSTRAP__\s*=\s*(\{.*?\});""")

private val sniffClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
}

actual suspend fun sniffParsePlayUrl(
    parseUrl: String,
    fallbackUrl: String?,
    headers: Map<String, String>,
): SniffedMedia? = withContext(Dispatchers.IO) {
    // 候选依次嗅探：解析前缀复合地址的内层目标优先（jx 前缀 ?url=内层），原地址兜底
    val candidates = listOfNotNull(
        innerTargetOf(parseUrl),
        parseUrl,
        fallbackUrl?.takeIf { it.contains("://") },
    ).distinct()
    for (candidate in candidates) {
        runCatching { sniffHhjxTemplate(candidate, headers) }.getOrElse {
            AppLog.w("WebSniffer", "桌面嗅探失败: ${it.message} url=$candidate")
            null
        }?.let { return@withContext it }
    }
    null
}

/** 从解析前缀复合地址提取内层目标（query 中 url 参数为 http 开头地址） */
private fun innerTargetOf(parseUrl: String): String? {
    val query = parseUrl.substringAfter('?', "").substringBefore('#')
    return query.split('&').firstNotNullOfOrNull { pair ->
        val kv = pair.split('=', limit = 2)
        if (kv.size == 2 && kv[0].equals("url", ignoreCase = true)) {
            runCatching { java.net.URLDecoder.decode(kv[1], "UTF-8") }
                .getOrNull()?.takeIf { it.contains("://") }
        } else {
            null
        }
    }
}

/**
 * HHJX 模板（巴士动漫等解析站共用）无头嗅探：
 * 1. GET 解析页提取 __HHJX_BOOTSTRAP__（url/t/key/act，与页面 JS 的 /api/parse 协议一致）
 * 2. POST {origin}/api/parse 携带原参数，响应 code=200 的 url 即真实直链
 * 非该模板页面返回 null（调用方提示更换线路）。
 */
private fun sniffHhjxTemplate(parseUrl: String, headers: Map<String, String>): SniffedMedia? {
    val requestHeaders = mapOf(
        "User-Agent" to (headerValue(headers, "User-Agent") ?: DEFAULT_UA),
        "Referer" to originOf(parseUrl),
    )
    val page = getBody(parseUrl, requestHeaders) ?: return null
    val bootstrap = BOOTSTRAP_REGEX.find(page)?.groupValues?.get(1) ?: return null
    val json = JsonParser.parseString(bootstrap).asJsonObject
    val payloadUrl = json.get("url")?.asString ?: return null
    val body = buildString {
        append("{\"url\":\"").append(escapeJson(payloadUrl)).append('"')
        append(",\"t\":").append(json.get("t")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L)
        append(",\"key\":\"").append(escapeJson(json.get("key")?.asString.orEmpty())).append('"')
        append(",\"client_fallback\":false")
        // 模板约定：act=99 时需随请求回传（备用线路解析标记）
        if (json.get("act")?.takeIf { it.isJsonPrimitive }?.asInt == 99) append(",\"act\":99")
        append('}')
    }
    val text = postJson(originOf(parseUrl) + "/api/parse", body, requestHeaders) ?: return null
    val response = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull() ?: return null
    if (response.get("code")?.asInt != 200) {
        AppLog.w("WebSniffer", "解析接口拒绝: code=${response.get("code")} url=$parseUrl")
        return null
    }
    val media = response.get("url")?.asString.orEmpty()
    if (media.isBlank()) return null
    return SniffedMedia(media, requestHeaders)
}

/** GET 文本页面，失败返回 null */
private fun getBody(url: String, headers: Map<String, String>): String? = runCatching {
    val builder = Request.Builder().url(url).get()
    headers.forEach { (key, value) -> builder.header(key, value) }
    sniffClient.newCall(builder.build()).execute().use { response ->
        if (!response.isSuccessful) return@runCatching null
        response.body?.string()
    }
}.getOrNull()

/** POST JSON 并返回响应文本，失败返回 null */
private fun postJson(url: String, body: String, headers: Map<String, String>): String? = runCatching {
    val builder = Request.Builder().url(url)
        .post(body.toRequestBody("application/json".toMediaType()))
    headers.forEach { (key, value) -> builder.header(key, value) }
    sniffClient.newCall(builder.build()).execute().use { response ->
        response.body?.string()
    }
}.getOrNull()

private fun originOf(url: String): String = with(URI(url)) {
    "${scheme ?: "https"}://${host}" + if (port > 0) ":$port" else ""
}

private fun headerValue(headers: Map<String, String>, name: String): String? =
    headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

/** JSON 字符串值转义（手拼请求体用） */
private fun escapeJson(value: String): String = buildString {
    value.forEach { c ->
        when (c) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
        }
    }
}
