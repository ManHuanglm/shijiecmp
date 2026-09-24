package com.shiping.app.data.spider

import android.util.Log
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
        if (!session.uri.orEmpty().startsWith("/hdr")) return null
        return handleHdr(session)
    }

    private fun handleHdr(session: NanoHTTPD.IHTTPSession): Response {
        val target = session.parms["url"].orEmpty()
        if (!target.contains("://")) return textResponse(Status.BAD_REQUEST, "缺少 url 参数")
        val builder = okhttp3.Request.Builder().url(target)
        parseHdrHeaders(session.parms["h"].orEmpty()).forEach { (key, value) -> builder.header(key, value) }
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
        // 播放内核要求 206 响应携带 Content-Range：上游定长响应用定长转发并
        // 透传 Range 相关头，否则 206+chunked 缺 Content-Range 会被播放内核拒播
        val response = upstream.header("Content-Length")?.toLongOrNull()?.let { length ->
            NanoHTTPD.newFixedLengthResponse(status, mime, body.byteStream(), length)
        } ?: NanoHTTPD.newChunkedResponse(status, mime, body.byteStream())
        upstream.header("Content-Range")?.let { response.addHeader("Content-Range", it) }
        upstream.header("Accept-Ranges")?.let { response.addHeader("Accept-Ranges", it) }
        return response
    }

    /** 解析 h 参数（「Key: Value」换行分隔）为 header 键值对 */
    private fun parseHdrHeaders(raw: String): List<Pair<String, String>> = raw.split("\n").mapNotNull { line ->
        val idx = line.indexOf(':')
        if (idx <= 0) null else line.substring(0, idx).trim() to line.substring(idx + 1).trim()
    }.filter { it.first.isNotBlank() && it.second.isNotBlank() }

    private fun textResponse(status: Status, message: String): Response =
        NanoHTTPD.newFixedLengthResponse(status, NanoHTTPD.MIME_PLAINTEXT, message.ifBlank { status.description })
}
