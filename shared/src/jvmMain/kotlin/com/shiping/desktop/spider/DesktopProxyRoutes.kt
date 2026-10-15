package com.shiping.desktop.spider

import com.shiping.desktop.data.spider.SpiderHttp
import com.shiping.desktop.player.PlaybackUrlResolver
import com.shiping.desktop.util.AppLog
import com.shiping.shared.player.HlsAdFilter
import com.shiping.shared.spider.SpiderProxyServer
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response
import fi.iki.elonen.NanoHTTPD.Response.Status
import java.io.ByteArrayInputStream
import java.io.File
import java.io.SequenceInputStream
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * 桌面端本地代理扩展路由：
 * - /hdr 直链播放 header 注入端点。参数：url=目标直链（URL 编码）；
 *   h=header 文本「Key: Value」换行分隔（URL 编码）。透传播放器的 Range 请求头。
 * - /m3u8 播放级端点（对齐 Android media3 数据源过滤，共用共享 HlsAdFilter）：
 *   拉取目标播放列表 → 按开关过滤广告切片 → 改写内部地址（master 变体递归包 /m3u8，
 *   切片/KEY/MAP 绝对化，含 Cookie 类 header 时包 /hdr）；首个切片探测命中伪图伪装
 *   （PNG 壳包 TS，厂长/立播系，桌面 ffmpeg 系内核格式探测会误判为图片黑屏）时切片改包 /seg；
 *   上游拉取失败 302 回退原始地址，路由故障不阻断播放。
 * - /seg 伪图分片剥离端点：探测分片头部非 0 偏移的 188 步进 TS 同步字节，剥离壳字节后转发。
 */
object DesktopProxyRoutes {

    /** m3u8 响应 MIME */
    private const val M3U8_MIME = "application/vnd.apple.mpegurl"

    /** 本地代理地址前缀（识别已包装地址防重复装饰） */
    private const val LOCAL_PREFIX = "http://127.0.0.1:"

    /** TS 包同步字节与包长（188 步进连续命中判定伪图分片） */
    private const val TS_SYNC_BYTE = 0x47
    private const val TS_PACKET_SIZE = 188

    /** 分片伪图探测缓冲大小（伪图壳头部远小于此值） */
    private const val SEGMENT_PROBE_BYTES = 4096

    /** 剥离后分片 MIME（中性 octet-stream 交由内核内容探测） */
    private const val SEGMENT_MIME = "application/octet-stream"

    /** 处理扩展路由；未命中返回 null 交回 shared 服务器 404 */
    fun handle(session: NanoHTTPD.IHTTPSession): Response? {
        val uri = session.uri.orEmpty()
        return when {
            uri.startsWith("/hdr") -> handleHdr(session)
            uri.startsWith("/m3u8") -> handleM3u8(session)
            uri.startsWith("/seg") -> handleSeg(session)
            uri.startsWith("/file/") -> handleFilePath(session)
            uri.startsWith("/file") -> handleFile(session)
            else -> null
        }
    }

    /**
     * 页内播放地址装饰（桌面 m3u8 本地路由入口）：
     * m3u8 直链 / /hdr 包装地址统一改写为本地 /m3u8 路由——路由端按开关决定是否过滤广告，
     * 并探测伪图分片（PNG 壳包 TS，Android ExoPlayer 可重同步播放而桌面内核黑屏）按需剥离；
     * 其余原样返回。
     * - 本地代理未启动：不装饰
     * - 本地爬虫代理地址（/proxy）：m3u8 重写由爬虫生态处理，不叠加装饰
     * - 已是 /m3u8 路由地址：防重复包装
     */
    fun decoratePlayUrl(url: String, headers: Map<String, String>, filterEnabled: Boolean): String {
        val port = SpiderProxyServer.listeningPort
        if (port <= 0) return url
        if (!url.substringBefore("#").lowercase().contains(".m3u8")) return url
        // 本地代理自处理的地址不叠加装饰：/proxy 由爬虫生态处理，/m3u8 已包装（防重复包装）
        if (url.startsWith(LOCAL_PREFIX) && (url.contains("/proxy") || url.contains("/m3u8?url="))) return url
        val target = if (url.startsWith(LOCAL_PREFIX) && url.contains("/hdr?url=")) {
            // /hdr 包装地址：解出内层直链重包路由地址（header 由路由地址 h 参数承载）
            runCatching {
                URLDecoder.decode(url.substringAfter("url=").substringBefore("&"), "UTF-8")
            }.getOrNull() ?: return url
        } else {
            url
        }
        AppLog.i("HlsAdFilter", "m3u8 本地路由包装（广告过滤=${if (filterEnabled) "开" else "关"}）: $target")
        return wrapFilter(target, headers, filterEnabled, port)
    }

    /** 包装为本地 /m3u8 路由地址（header 文本全量透传，f 参数承载广告过滤开关） */
    private fun wrapFilter(url: String, headers: Map<String, String>, filterEnabled: Boolean, port: Int): String {
        val headerText = headers.entries.joinToString("\n") { "${it.key}:${it.value}" }
        return "$LOCAL_PREFIX$port/m3u8?url=${urlEncode(url)}&h=${urlEncode(headerText)}" +
            "&f=${if (filterEnabled) "1" else "0"}"
    }

    /**
     * /m3u8 路由：拉取目标播放列表（h 参数 header 注入，f 参数过滤开关）→ 非播放列表内容原样回传；
     * master 列表不过滤仅改写变体地址为递归 /m3u8；媒体列表按开关经共享 HlsAdFilter 过滤后，
     * 切片/KEY/MAP 地址绝对化（含 Cookie 类 header 时包 /hdr，UA/Referer 由内核全局属性覆盖）；
     * 首个切片探测命中伪图伪装时切片行改包 /seg（KEY/MAP 非伪图载体，维持原逻辑）。
     */
    private fun handleM3u8(session: NanoHTTPD.IHTTPSession): Response {
        val target = session.parms["url"].orEmpty()
        if (!target.contains("://")) return textResponse(Status.BAD_REQUEST, "缺少 url 参数")
        val headers = parseHdrHeaders(session.parms["h"].orEmpty())
        val headerMap = headers.toMap()
        val filterEnabled = session.parms["f"] != "0"
        // 上游可能 302 跳转（gslb 调度类清单）：分片相对路径必须基于重定向后的最终 URL 解析
        val (finalUrl, body) = fetchPlaylist(target, headers)
            ?: return redirectFallback(target)
        if (!body.contains("#EXTM3U")) return m3u8Response(body.toByteArray(Charsets.UTF_8))

        val port = SpiderProxyServer.listeningPort
        val isMaster = body.contains("#EXT-X-STREAM-INF")
        val (filtered, removed) = when {
            isMaster || !filterEnabled -> body to 0
            else -> HlsAdFilter.filter(body).let { it.text to it.removedSegments }
        }
        // 伪图分片探测：首个切片头部命中非 0 偏移 TS 同步（PNG 等伪装壳）→ 全部切片行改包 /seg
        val disguised = !isMaster && firstSegmentUri(body)
            ?.let { probeDisguisedSegment(resolveUri(finalUrl, it), headerMap) } == true
        AppLog.i("HlsAdFilter", "播放列表 $finalUrl → 移除 $removed，伪图分片=$disguised")

        val rewritten = if (isMaster) {
            // 进入本路由的目标必非本地代理地址，变体地址一律绝对化后递归包装
            rewriteLines(filtered) { _, uri ->
                wrapFilter(resolveUri(finalUrl, uri), headerMap, filterEnabled, port)
            }
        } else {
            rewriteLines(filtered) { line, uri ->
                val absolute = resolveUri(finalUrl, uri)
                when {
                    !line.startsWith("#") && disguised -> wrapSegProxy(absolute, headerMap, port)
                    PlaybackUrlResolver.needsHdrProxy(headerMap) -> {
                        PlaybackUrlResolver.wrapHdrProxy(absolute, headerMap, port)
                    }
                    else -> absolute
                }
            }
        }
        return m3u8Response(rewritten.toByteArray(Charsets.UTF_8))
    }

    /**
     * /seg 伪图分片剥离路由：拉取目标分片（h 参数 header 注入）→ 头部最多探测 4KB，
     * 非 0 偏移处命中 188 步进 TS 同步字节（PNG 等伪装壳）则剥离壳字节后转发，否则原样透传。
     * 定长响应按剥离量校正 Content-Length（上游透明解压时退回 chunked），保证内核进度正确。
     */
    private fun handleSeg(session: NanoHTTPD.IHTTPSession): Response {
        val target = session.parms["url"].orEmpty()
        if (!target.contains("://")) return textResponse(Status.BAD_REQUEST, "缺少 url 参数")
        val headerMap = parseHdrHeaders(session.parms["h"].orEmpty()).toMap()
        val builder = okhttp3.Request.Builder().url(target)
        headerMap.forEach { (key, value) -> builder.header(key, value) }
        val upstream = try {
            SpiderHttp.client.newCall(builder.build()).execute()
        } catch (t: Throwable) {
            AppLog.e("SpiderProxyServer", "seg 上游请求失败: ${t.message} url=$target")
            return textResponse(Status.INTERNAL_ERROR, "上游请求失败: ${t.message}")
        }
        if (!upstream.isSuccessful) {
            val code = upstream.code
            upstream.close()
            return textResponse(Status.lookup(code) ?: Status.INTERNAL_ERROR, "上游异常响应 code=$code")
        }
        val body = upstream.body ?: run {
            upstream.close()
            return textResponse(Status.INTERNAL_ERROR, "上游无响应体")
        }
        val head = ByteArray(SEGMENT_PROBE_BYTES)
        var filled = 0
        val input = body.byteStream()
        while (filled < head.size) {
            val read = input.read(head, filled, head.size - filled)
            if (read < 0) break
            filled += read
        }
        val offset = disguisedTsOffset(head, filled)
        if (offset > 0) AppLog.i("SpiderProxyServer", "伪图分片剥离 ${offset}B: $target")
        // 已探测头部按剥离偏移回放 + 上游剩余字节合并为单一转发流（单线程无缓冲管道）
        val stream = SequenceInputStream(ByteArrayInputStream(head, offset, filled - offset), input)
        val mime = if (offset > 0) "video/mp2t" else SEGMENT_MIME
        val length = upstream.header("Content-Length")?.toLongOrNull()
            ?.takeIf { upstream.header("Content-Encoding") == null }
        return length?.let { contentLength ->
            NanoHTTPD.newFixedLengthResponse(Status.OK, mime, stream, contentLength - offset)
        } ?: NanoHTTPD.newChunkedResponse(Status.OK, mime, stream)
    }

    /** 播放列表首个切片地址（伪图探测样本）：首个非注释非空行 */
    private fun firstSegmentUri(playlist: String): String? =
        playlist.lines().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") }

    /** 探测目标分片是否伪图伪装 TS（Range 拉头部判定）；探测失败按非伪图处理保持现状 */
    private fun probeDisguisedSegment(url: String, headerMap: Map<String, String>): Boolean = runCatching {
        val builder = okhttp3.Request.Builder().url(url).header("Range", "bytes=0-${SEGMENT_PROBE_BYTES - 1}")
        headerMap.forEach { (key, value) -> builder.header(key, value) }
        SpiderHttp.client.newCall(builder.build()).execute().use { upstream ->
            if (!upstream.isSuccessful) return false
            val input = upstream.body?.byteStream() ?: return false
            val head = ByteArray(SEGMENT_PROBE_BYTES)
            var filled = 0
            while (filled < head.size) {
                val read = input.read(head, filled, head.size - filled)
                if (read < 0) break
                filled += read
            }
            disguisedTsOffset(head, filled) > 0
        }
    }.getOrDefault(false)

    /**
     * 伪图 TS 壳头部偏移：p>0 处出现 188 步进连续 3 个同步字节即命中伪装壳（p=0 为正常 TS
     * 返回 0，fMP4 等格式无该模式），无命中返回 0（字节流原样转发）。
     */
    private fun disguisedTsOffset(head: ByteArray, length: Int): Int {
        if (length <= 0 || head[0].toInt() == TS_SYNC_BYTE) return 0
        // 扫描上限保证末次访问索引 offset + 2*188 不越界
        val last = length - TS_PACKET_SIZE * 2 - 1
        for (offset in 1..last) {
            if (head[offset].toInt() == TS_SYNC_BYTE &&
                head[offset + TS_PACKET_SIZE].toInt() == TS_SYNC_BYTE &&
                head[offset + TS_PACKET_SIZE * 2].toInt() == TS_SYNC_BYTE
            ) {
                return offset
            }
        }
        return 0
    }

    /** 包装为本地 /seg 伪图剥离地址（header 文本全量透传，供路由端拉取分片） */
    private fun wrapSegProxy(url: String, headers: Map<String, String>, port: Int): String {
        val headerText = headers.entries.joinToString("\n") { "${it.key}:${it.value}" }
        return "$LOCAL_PREFIX$port/seg?url=${urlEncode(url)}&h=${urlEncode(headerText)}"
    }

    /** 拉取失败 302 回退原始地址：播放内核跟随重定向直连，本地路由故障不阻断播放 */
    private fun redirectFallback(target: String): Response =
        NanoHTTPD.newFixedLengthResponse(Status.REDIRECT, NanoHTTPD.MIME_PLAINTEXT, "").apply {
            addHeader("Location", target)
        }

    /** 拉取目标播放列表全文（header 注入；播放列表需完整正文，不透传 Range）；返回 (重定向后最终 URL, 正文)，失败返回 null。
     *  gslb 类调度源每次 302 到不同节点：解析结果短 TTL 缓存，TTL 内后续清单请求直接走最终 URL 省一跳 */
    private fun fetchPlaylist(target: String, headers: List<Pair<String, String>>): Pair<String, String>? = try {
        // 解析缓存命中：直接请求已知最终地址（跳过 302 调度跳）
        val requestUrl = redirectCache[target]
            ?.takeIf { System.currentTimeMillis() - it.second < REDIRECT_CACHE_TTL_MS }
            ?.first ?: target
        val builder = okhttp3.Request.Builder().url(requestUrl)
        headers.forEach { (key, value) -> builder.header(key, value) }
        SpiderHttp.client.newCall(builder.build()).execute().use { upstream ->
            if (!upstream.isSuccessful) {
                // 走缓存的最终地址失败（节点下线等）：失效缓存，本次回退由调用方重试原始地址
                redirectCache.remove(target)
                AppLog.w("HlsAdFilter", "播放列表拉取失败 code=${upstream.code} url=$requestUrl")
                null
            } else {
                // okhttp 的 request.url 为跟随 302 后的最终请求地址（相对分片解析基准）
                val finalUrl = upstream.request.url.toString()
                if (finalUrl != requestUrl) redirectCache[target] = finalUrl to System.currentTimeMillis()
                finalUrl to (upstream.body?.string() ?: "")
            }
        }
    } catch (t: Throwable) {
        redirectCache.remove(target)
        AppLog.e("HlsAdFilter", "播放列表拉取异常: ${t.message} url=$target")
        null
    }

    /** 302 跳转解析缓存（原始 URL → [最终 URL, 解析时间]） */
    private val redirectCache = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Long>>()

    /** 302 解析缓存有效期（毫秒）：调度节点变更超时后自动失效回退原始地址 */
    private const val REDIRECT_CACHE_TTL_MS = 5 * 60_000L

    /** 逐行改写播放列表：非注释行为切片地址，注释行处理 URI="..." 属性（KEY/MAP/MEDIA 等） */
    private fun rewriteLines(playlist: String, rewrite: (line: String, uri: String) -> String): String =
        playlist.lines().joinToString("\n") { line ->
            val trimmed = line.trim()
            when {
                trimmed.isEmpty() -> line
                trimmed.startsWith("#") -> rewriteUriAttribute(line, rewrite)
                else -> rewrite(line, trimmed)
            }
        }

    /** 改写标签行内首个 URI="..." 属性值；无该属性原样返回 */
    private fun rewriteUriAttribute(line: String, rewrite: (line: String, uri: String) -> String): String {
        val marker = "URI=\""
        val start = line.indexOf(marker)
        if (start < 0) return line
        val valueStart = start + marker.length
        val end = line.indexOf('"', valueStart)
        if (end < 0) return line
        return line.substring(0, valueStart) + rewrite(line, line.substring(valueStart, end)) + line.substring(end)
    }

    /** 相对地址按播放列表原始地址绝对化；解析失败原样返回 */
    private fun resolveUri(base: String, ref: String): String =
        runCatching { URI(base).resolve(ref).toString() }.getOrDefault(ref)

    /** m3u8 定长响应（字节数准确，避免字符串重编码长度漂移） */
    private fun m3u8Response(bytes: ByteArray): Response =
        NanoHTTPD.newFixedLengthResponse(Status.OK, M3U8_MIME, ByteArrayInputStream(bytes), bytes.size.toLong())

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
            AppLog.e("SpiderProxyServer", "hdr 上游请求失败: ${t.message} url=$target")
            return textResponse(Status.INTERNAL_ERROR, "上游请求失败: ${t.message}")
        }
        if (upstream.code >= 300) {
            AppLog.w("SpiderProxyServer", "hdr 上游异常响应 code=${upstream.code} range=${session.headers["range"]} url=$target")
        }
        val body = upstream.body ?: run {
            upstream.close()
            return textResponse(Status.INTERNAL_ERROR, "上游无响应体")
        }
        val status = Status.lookup(upstream.code) ?: Status.OK
        val mime = upstream.header("Content-Type") ?: "application/octet-stream"
        // MPD 清单（B 站 DASH 等）：BaseURL 直链被播放内核直连会因缺 header 被 CDN 403，
        // 缓冲后重写 BaseURL 为本地 /hdr 包裹（分段 Range 请求由本路由透传）
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

    /** MPD 清单识别：url 后缀/type 参数或响应 MIME（dash/mpd） */
    private fun isMpdManifest(target: String, mime: String): Boolean {
        val lower = target.lowercase()
        val mimeType = mime.lowercase()
        return lower.substringBefore('?').endsWith(".mpd") || lower.contains("type=mpd") ||
            mimeType.contains("dash") || mimeType.contains("mpd")
    }

    /**
     * 重写 MPD 内 BaseURL 直链为本地 /hdr 包裹地址：
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
        if (count[0] > 0) AppLog.i("SpiderProxyServer", "MPD BaseURL 重写 ${count[0]} 处")
        return rewritten
    }

    /** 解析 h 参数（「Key: Value」换行分隔）为 header 键值对 */
    private fun parseHdrHeaders(raw: String): List<Pair<String, String>> = raw.split("\n").mapNotNull { line ->
        val idx = line.indexOf(':')
        if (idx <= 0) null else line.substring(0, idx).trim() to line.substring(idx + 1).trim()
    }.filter { it.first.isNotBlank() && it.second.isNotBlank() }

    /** URL 编码：空格统一转义为 %20（NanoHTTPD 不解 + 为空格） */
    private fun urlEncode(raw: String): String =
        URLEncoder.encode(raw, "UTF-8").replace("+", "%20")

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

    private fun textResponse(status: Status, message: String): Response =
        NanoHTTPD.newFixedLengthResponse(status, NanoHTTPD.MIME_PLAINTEXT, message.ifBlank { status.description })
}
