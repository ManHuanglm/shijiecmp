package com.shiping.app.data.spider

import android.util.Log
import com.shiping.desktop.player.PlaybackUrlResolver
import com.shiping.shared.spider.SpiderProxyServer
import java.io.File
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response
import fi.iki.elonen.NanoHTTPD.Response.Status

/**
 * Android 端本地代理扩展路由：/hdr 直链播放 header 注入端点（对齐桌面 DesktopProxyRoutes）。
 * media3 数据源默认请求属性只覆盖 UA，Cookie 等其余 header 走本地代理流式转发。
 * 参数：url=目标直链（URL 编码）；h=header 文本「Key: Value」换行分隔（URL 编码）。
 * 透传播放器的 Range 请求头，支持拖动进度。
 */
object AndroidProxyRoutes {

    private const val TAG = "SpiderProxyServer"

    /** 处理 /hdr 请求；非 /hdr 路径返回 null 交回 shared 服务器 404 */
    fun handle(session: NanoHTTPD.IHTTPSession): Response? {
        val uri = session.uri.orEmpty()
        return when {
            uri.startsWith("/hdr") -> handleHdr(session)
            uri.startsWith("/file/") -> handleFilePath(session)
            uri.startsWith("/file") -> handleFile(session)
            else -> null
        }
    }

    private fun handleHdr(session: NanoHTTPD.IHTTPSession): Response {
        val target = session.parms["url"].orEmpty()
        if (!target.contains("://")) return textResponse(Status.BAD_REQUEST, "缺少 url 参数")
        val headerMap = parseHdrHeaders(session.parms["h"].orEmpty()).toMap()
        val builder = okhttp3.Request.Builder().url(target)
        headerMap.forEach { (key, value) -> builder.header(key, value) }
        // NanoHTTPD 的 headers 键为小写
        session.headers["range"]?.let { builder.header("Range", it) }
        val upstream = try {
            SpiderHttp.client.newCall(builder.build()).execute()
        } catch (t: Throwable) {
            Log.e(TAG, "hdr 上游请求失败: ${t.message} url=$target")
            return textResponse(Status.INTERNAL_ERROR, "上游请求失败: ${t.message}")
        }
        if (upstream.code >= 300) {
            Log.w(TAG, "hdr 上游异常响应 code=${upstream.code} range=${session.headers["range"]} url=$target")
        }
        val body = upstream.body ?: run {
            upstream.close()
            return textResponse(Status.INTERNAL_ERROR, "上游无响应体")
        }
        val status = Status.lookup(upstream.code) ?: Status.OK
        val mime = upstream.header("Content-Type") ?: "application/octet-stream"
        // MPD 清单（B 站 DASH 等）：BaseURL 直链被播放内核直连会因缺 header 被 CDN 403，
        // 缓冲后重写 BaseURL 为本地 /hdr 包裹（分段 Range 请求由本路由透传 header）
        if (isMpdManifest(target, mime)) {
            val rewritten = rewriteMpdBaseUrls(body.string(), headerMap)
            return NanoHTTPD.newFixedLengthResponse(status, mime, rewritten)
        }
        // 播放内核要求 206 响应携带 Content-Range：上游定长响应用定长转发并
        // 透传 Range 相关头，否则 206+chunked 缺 Content-Range 会被播放内核拒播
        val response = upstream.header("Content-Length")?.toLongOrNull()?.let { length ->
            NanoHTTPD.newFixedLengthResponse(status, mime, body.byteStream(), length)
        } ?: NanoHTTPD.newChunkedResponse(status, mime, body.byteStream())
        upstream.header("Content-Range")?.let { response.addHeader("Content-Range", it) }
        upstream.header("Accept-Ranges")?.let { response.addHeader("Accept-Ranges", it) }
        return response
    }

    /** MPD 清单识别：url 后缀/type 参数或响应 MIME（dash/mpd），对齐桌面 DesktopProxyRoutes */
    private fun isMpdManifest(target: String, mime: String): Boolean {
        val lower = target.lowercase()
        val mimeType = mime.lowercase()
        return lower.substringBefore('?').endsWith(".mpd") || lower.contains("type=mpd") ||
            mimeType.contains("dash") || mimeType.contains("mpd")
    }

    /**
     * 重写 MPD 内 BaseURL 直链为本地 /hdr 包裹地址（对齐桌面 DesktopProxyRoutes）：
     * URL 先还原 XML 转义再编码进参数，重新放回时分隔符 & 转义为 &amp;（严格 XML 解析）。
     */
    private fun rewriteMpdBaseUrls(mpd: String, headerMap: Map<String, String>): String {
        val port = SpiderProxyServer.listeningPort
        if (port <= 0) return mpd
        val count = intArrayOf(0)
        val rewritten = Regex("<BaseURL>(https?://[^<]+)</BaseURL>").replace(mpd) { match ->
            val decoded = match.groupValues[1].replace("&amp;", "&")
            count[0]++
            val wrapped = PlaybackUrlResolver.wrapHdrProxy(decoded, headerMap, port).replace("&", "&amp;")
            "<BaseURL>$wrapped</BaseURL>"
        }
        if (count[0] > 0) Log.i(TAG, "MPD BaseURL 重写 ${count[0]} 处")
        return rewritten
    }

    /** 解析 h 参数（「Key: Value」换行分隔）为 header 键值对 */
    private fun parseHdrHeaders(raw: String): List<Pair<String, String>> = raw.split("\n").mapNotNull { line ->
        val idx = line.indexOf(':')
        if (idx <= 0) null else line.substring(0, idx).trim() to line.substring(idx + 1).trim()
    }.filter { it.first.isNotBlank() && it.second.isNotBlank() }

    private fun textResponse(status: Status, message: String): Response =
        NanoHTTPD.newFixedLengthResponse(status, NanoHTTPD.MIME_PLAINTEXT, message.ifBlank { status.description })
    /**
     * /file 本地包文件路由：p 参数承载本地绝对路径（ext/脚本内 clan:// 引用翻译而来）。
     * 供爬虫 jar/quickjs 引擎经 okhttp 抓取本地包文件（本地信任边界：代理仅监听 127.0.0.1）。
     */
    private fun handleFile(session: NanoHTTPD.IHTTPSession): Response {
        val file = File(session.parms["p"].orEmpty())
        if (!file.isFile) return textResponse(Status.NOT_FOUND, "本地文件不存在: ${file.path}")
        return NanoHTTPD.newFixedLengthResponse(Status.OK, fileMime(file), file.inputStream(), file.length())
    }
    /** 路径式文件路由：/file/<URL 编码绝对路径>（quickjs 相对 import 解析友好） */
    private fun handleFilePath(session: NanoHTTPD.IHTTPSession): Response {
        val path = java.net.URLDecoder.decode(session.uri.orEmpty().substring("/file".length), "UTF-8")
        val file = File(path)
        if (!file.isFile) return textResponse(Status.NOT_FOUND, "本地文件不存在: $path")
        return NanoHTTPD.newFixedLengthResponse(Status.OK, fileMime(file), file.inputStream(), file.length())
    }


    /** 按扩展名映射响应 MIME（jar 抓取以 json/脚本为主，其余 octet-stream） */
    private fun fileMime(file: File): String = when (file.extension.lowercase()) {
        "json" -> "application/json"
        "js" -> "application/javascript"
        "txt", "m3u" -> "text/plain"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        else -> "application/octet-stream"
    }

}
