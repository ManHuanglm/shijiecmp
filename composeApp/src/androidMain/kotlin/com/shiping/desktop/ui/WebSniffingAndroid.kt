package com.shiping.desktop.ui

import android.net.Uri
import com.shiping.app.ShipingApp
import com.shiping.app.data.player.WebSniffer
import com.shiping.app.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** Android 支持网页嗅探：WebView 承载 parse=1 解析页，监听媒体请求命中直链 */
actual val supportsWebSniffing: Boolean = true

private val sniffHttpClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
}

/**
 * Android 嗅探：优先无头模拟已知解析站模板（HHJX，巴士动漫等共用——纯 HTTP 不起 WebView，
 * 与桌面实现一致），失败再回退 WebView 嗅探（解析页 → 原始网页兜底）。
 */
actual suspend fun sniffParsePlayUrl(
    parseUrl: String,
    fallbackUrl: String?,
    headers: Map<String, String>,
): SniffedMedia? {
    // 无头协议模拟优先：HHJX 模板命中即返回真实直链（纯 HTTP 不起 WebView）
    val headless = withContext(Dispatchers.IO) {
        val candidates = listOfNotNull(
            innerTargetOf(parseUrl),
            parseUrl,
            fallbackUrl?.takeIf { it.contains("://") },
        ).distinct()
        for (candidate in candidates) {
            runCatching { sniffHhjxTemplate(candidate, headers) }.getOrElse {
                AppLog.w("WebSniffer", "HHJX 无头嗅探失败: ${it.message} url=$candidate")
                null
            }?.let { return@withContext it }
        }
        null
    }
    if (headless != null) return headless
    // WebView 嗅探回退（协议模拟未命中：非 HHJX 模板或解析接口变更）
    return suspendCancellableCoroutine { continuation ->
        val sniffer = WebSniffer()
        continuation.invokeOnCancellation { sniffer.cancel() }
        val onSniffed: (String?) -> Unit = { url ->
            if (continuation.isActive) continuation.resume(url?.let { SniffedMedia(it, headers) })
        }
        sniffer.sniff(ShipingApp.context, parseUrl) { sniffedUrl ->
            // 解析页嗅探失败时回退直接嗅探原始网页（解析接口失效时源页常自带播放器）
            if (sniffedUrl != null || fallbackUrl.isNullOrBlank()) {
                onSniffed(sniffedUrl)
            } else {
                sniffer.sniff(ShipingApp.context, fallbackUrl, onResult = onSniffed)
            }
        }
    }
}

/** 从解析前缀复合地址提取内层目标（query 中 url 参数为 http 开头地址） */
private fun innerTargetOf(parseUrl: String): String? {
    val query = parseUrl.substringAfter('?', "").substringBefore('#')
    return query.split('&').firstNotNullOfOrNull { pair ->
        val kv = pair.split('=', limit = 2)
        if (kv.size == 2 && kv[0].equals("url", ignoreCase = true)) {
            runCatching { Uri.decode(kv[1]) }.getOrNull()?.takeIf { it.contains("://") }
        } else {
            null
        }
    }
}

/**
 * HHJX 模板（巴士动漫等解析站共用）无头嗅探：
 * 1. GET 解析页提取 __HHJX_BOOTSTRAP__（url/t/key/act，与页面 JS 的 /api/parse 协议一致）
 * 2. POST {origin}/api/parse 携带原参数，响应 code=200 的 url 即真实直链
 * 非该模板页面返回 null（调用方回退 WebView 嗅探）。
 */
private fun sniffHhjxTemplate(parseUrl: String, headers: Map<String, String>): SniffedMedia? {
    val requestHeaders = mapOf(
        "User-Agent" to (headerValue(headers, "User-Agent") ?: MOBILE_UA),
        "Referer" to originOf(parseUrl),
    )
    val page = getBody(parseUrl, requestHeaders) ?: return null
    val bootstrap = BOOTSTRAP_REGEX.find(page)?.groupValues?.get(1) ?: return null
    val json = runCatching { JSONObject(bootstrap) }.getOrNull() ?: return null
    val payloadUrl = json.optString("url").takeIf { it.isNotEmpty() } ?: return null
    val body = JSONObject().apply {
        put("url", payloadUrl)
        put("t", json.optLong("t", 0L))
        put("key", json.optString("key"))
        put("client_fallback", false)
        // 模板约定：act=99 时需随请求回传（备用线路解析标记）
        if (json.optInt("act", 0) == 99) put("act", 99)
    }.toString()
    val text = postJson(originOf(parseUrl) + "/api/parse", body, requestHeaders) ?: return null
    val response = runCatching { JSONObject(text) }.getOrNull() ?: return null
    if (response.optInt("code") != 200) {
        AppLog.w("WebSniffer", "解析接口拒绝: code=${response.optInt("code")} url=$parseUrl")
        return null
    }
    val media = response.optString("url")
    if (media.isBlank()) return null
    return SniffedMedia(media, requestHeaders)
}

/** GET 文本页面，失败返回 null */
private fun getBody(url: String, headers: Map<String, String>): String? = runCatching {
    val builder = Request.Builder().url(url).get()
    headers.forEach { (key, value) -> builder.header(key, value) }
    sniffHttpClient.newCall(builder.build()).execute().use { response ->
        if (!response.isSuccessful) return@runCatching null
        response.body?.string()
    }
}.getOrNull()

/** POST JSON 并返回响应文本，失败返回 null */
private fun postJson(url: String, body: String, headers: Map<String, String>): String? = runCatching {
    val builder = Request.Builder().url(url)
        .post(body.toRequestBody("application/json".toMediaType()))
    headers.forEach { (key, value) -> builder.header(key, value) }
    sniffHttpClient.newCall(builder.build()).execute().use { response ->
        response.body?.string()
    }
}.getOrNull()

private fun originOf(url: String): String = with(Uri.parse(url)) {
    "${scheme ?: "https"}://${host ?: ""}" + if (port > 0) ":$port" else ""
}

private fun headerValue(headers: Map<String, String>, name: String): String? =
    headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

/** 解析页默认移动端 UA（与 WebView 嗅探一致） */
private const val MOBILE_UA =
    "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

/** HHJX 模板启动参数标记（解析页内 __HHJX_BOOTSTRAP__ = {...}） */
private val BOOTSTRAP_REGEX = Regex("""window\.__HHJX_BOOTSTRAP__\s*=\s*(\{.*?\});""")
