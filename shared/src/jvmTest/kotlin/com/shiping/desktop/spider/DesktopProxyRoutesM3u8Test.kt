package com.shiping.desktop.spider

import com.shiping.desktop.player.PlaybackUrlResolver
import com.shiping.shared.spider.SpiderProxyBridge
import com.shiping.shared.spider.SpiderProxyServer
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response.Status
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 桌面 /m3u8 播放级去广告路由端到端验证：
 * 本地模拟源服务器提供带广告块的播放列表，经真实本地代理（SpiderProxyServer + 扩展路由）
 * 请求过滤地址，断言切片过滤、地址改写与装饰语义（开关/非 m3u8/本地代理地址）。
 */
class DesktopProxyRoutesM3u8Test {

    /** 伪图分片 TS 包数与包长 */
    private val TS_PACKETS = 3
    private val TS_PACKET_LEN = 188

    /** 主内容切片目录（正片） */
    private val mainDir = "20260925/u3mkkQki/1000kb/hls"

    /** 广告切片目录（与正片不同目录，命中目录指纹规则） */
    private val adDir = "20260917/ads/9000kb/hls"

    /** 媒体级播放列表：正片-广告-正片分块结构（相对切片地址） */
    private val mediaPlaylist = """
        #EXTM3U
        #EXT-X-VERSION:3
        #EXT-X-TARGETDURATION:10
        #EXTINF:6.0,
        $mainDir/seg01.ts
        #EXTINF:6.0,
        $mainDir/seg02.ts
        #EXT-X-DISCONTINUITY
        #EXTINF:5.0,
        $adDir/ad01.ts
        #EXTINF:5.0,
        $adDir/ad02.ts
        #EXT-X-DISCONTINUITY
        #EXTINF:6.0,
        $mainDir/seg03.ts
    """.trimIndent()

    /** 主播放列表：变体地址为相对路径 */
    private val masterPlaylist = """
        #EXTM3U
        #EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1280x720
        media.m3u8
    """.trimIndent()

    /** 伪图媒体列表：切片为 PNG 壳包 TS 的伪装分片（相对地址） */
    private val fakeMediaPlaylist = """
        #EXTM3U
        #EXT-X-TARGETDURATION:10
        #EXTINF:10.0,
        /fake/seg.bin
    """.trimIndent()

    /** 伪图分片字节：126 字节 PNG 壳头 + 3 个 TS 包（188 步进 0x47 同步） */
    private val disguisedSegment: ByteArray = run {
        val pngHead = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        ) + ByteArray(118) { 0x12 }
        val ts = ByteArray(TS_PACKETS * TS_PACKET_LEN) { index ->
            if (index % TS_PACKET_LEN == 0) 0x47 else 0x33
        }
        pngHead + ts
    }

    /** 模拟源服务器（随机空闲端口），按路径回放预置播放列表 */
    private val origin = object : NanoHTTPD(0) {
        override fun serve(session: IHTTPSession): Response {
            if (session.uri == "/fake/seg.bin") {
                return newFixedLengthResponse(
                    Status.OK,
                    "image/png",
                    java.io.ByteArrayInputStream(disguisedSegment),
                    disguisedSegment.size.toLong(),
                )
            }
            val body = when (session.uri) {
                "/media.m3u8" -> mediaPlaylist
                "/vod/index.m3u8" -> mediaPlaylist
                "/master.m3u8" -> masterPlaylist
                "/fake/index.m3u8" -> fakeMediaPlaylist
                else -> return newFixedLengthResponse(Status.NOT_FOUND, MIME_PLAINTEXT, "not found")
            }
            return newFixedLengthResponse(Status.OK, "application/vnd.apple.mpegurl", body)
        }
    }

    init {
        origin.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        SpiderProxyServer.install(
            SpiderProxyBridge(
                onPortChanged = { },
                dispatchProxy = { null },
                extraRouteHandler = { session -> DesktopProxyRoutes.handle(session) },
                logger = { _, _, _ -> },
            ),
        )
        SpiderProxyServer.start()
    }

    @AfterTest
    fun tearDown() {
        origin.stop()
    }

    @Test
    fun 媒体列表经路由过滤后移除广告切片且地址绝对化() {
        val originUrl = "${originRoot()}/vod/index.m3u8"
        val filtered = fetchViaFilterRoute(originUrl)
        // 广告目录切片全部移除
        assertFalse(filtered.contains("/$adDir/"), "广告切片应被移除")
        // 正片切片保留且绝对化（无 Cookie 类 header 时不包 /hdr）
        val segment = Regex("http://127\\.0\\.0\\.1:${origin.listeningPort}/vod/$mainDir/seg01\\.ts")
        assertTrue(segment.containsMatchIn(filtered), "正片切片应绝对化保留")
        assertFalse(filtered.contains("/hdr?url="), "无 Cookie header 时切片不应包装 /hdr")
        // 广告块的 DISCONTINUITY 标签保留（过滤仅移除切片与块内 KEY/MAP）
        assertTrue(filtered.contains("#EXT-X-DISCONTINUITY"))
    }

    @Test
    fun 含Cookie时切片包装hdr代理转发() {
        val originUrl = "${originRoot()}/vod/index.m3u8"
        val headers = mapOf("User-Agent" to "UA-TEST", "Cookie" to "sid=1")
        val decorated = DesktopProxyRoutes.decoratePlayUrl(originUrl, headers, filterEnabled = true)
        assertTrue(decorated.contains("/m3u8?url="), "m3u8 应包装过滤地址")
        val filtered = httpGet(decorated)
        assertTrue(filtered.contains("/hdr?url="), "含 Cookie 时切片应包装 /hdr 携带 header")
        assertFalse(filtered.contains("/$adDir/"), "广告切片应被移除")
    }

    @Test
    fun 主列表变体地址递归包装且二次请求得过滤结果() {
        val masterWrapped = DesktopProxyRoutes.decoratePlayUrl("${originRoot()}/master.m3u8", emptyMap(), true)
        val master = httpGet(masterWrapped)
        // 变体地址被改写为本地 /m3u8 过滤地址（含 url 参数）
        val variantLine = master.lineSequence().firstOrNull { it.startsWith("http") } ?: error("主列表无变体地址")
        assertTrue(variantLine.contains("/m3u8?url="), "变体地址应递归包装 /m3u8")
        // 二次请求过滤地址得到过滤后的媒体列表
        val media = httpGet(variantLine)
        assertFalse(media.contains("/$adDir/"), "递归请求应返回过滤后媒体列表")
        assertTrue(media.contains("seg03.ts"), "正片切片应保留")
    }

    @Test
    fun 装饰语义按开关与地址类型分流() {
        val direct = "${originRoot()}/vod/index.m3u8"
        // 开关关闭：仍包装（伪图分片修复对全量生效），f=0 承载过滤开关
        val filterOff = DesktopProxyRoutes.decoratePlayUrl(direct, emptyMap(), filterEnabled = false)
        assertTrue(filterOff.contains("/m3u8?url="), "过滤关闭时 m3u8 也应包装本地路由（伪图修复）")
        assertTrue(filterOff.contains("f=0"), "过滤开关应经 f 参数透传")
        // 非 m3u8：原样
        val mp4 = "https://cdn.example.com/video.mp4"
        assertEquals(mp4, DesktopProxyRoutes.decoratePlayUrl(mp4, emptyMap(), filterEnabled = true))
        // 已是爬虫代理地址（/proxy）：不叠加装饰
        val proxyUrl = "http://127.0.0.1:${SpiderProxyServer.listeningPort}/proxy?do=m3u8&url=x"
        assertEquals(proxyUrl, DesktopProxyRoutes.decoratePlayUrl(proxyUrl, emptyMap(), filterEnabled = true))
        // /hdr 包装的 m3u8：解出内层直链重包 /m3u8
        val inner = "https://cdn.example.com/live/index.m3u8"
        val hdrWrapped = PlaybackUrlResolver.wrapHdrProxy(inner, mapOf("Cookie" to "a=b"), SpiderProxyServer.listeningPort)
        val decorated = DesktopProxyRoutes.decoratePlayUrl(hdrWrapped, emptyMap(), filterEnabled = true)
        assertTrue(decorated.contains("/m3u8?url="), "/hdr 包装地址应改写为 /m3u8")
        assertTrue(decorated.contains(java.net.URLEncoder.encode(inner, "UTF-8")), "内层直链应保留为过滤目标")
        // 已是过滤地址：防重复包装
        assertEquals(decorated, DesktopProxyRoutes.decoratePlayUrl(decorated, emptyMap(), filterEnabled = true))
    }

    @Test
    fun 伪图分片包装seg路由且剥离壳字节() {
        val originUrl = "${originRoot()}/fake/index.m3u8"
        // 过滤关闭也包装（伪图修复与广告过滤解耦）
        val decorated = DesktopProxyRoutes.decoratePlayUrl(originUrl, emptyMap(), filterEnabled = false)
        assertTrue(decorated.contains("/m3u8?url="), "伪图修复应在过滤关闭时生效")
        val rewritten = httpGet(decorated)
        val segLine = rewritten.lineSequence().firstOrNull { it.startsWith("http") } ?: error("无切片行")
        assertTrue(segLine.contains("/seg?url="), "伪图切片应包装 /seg 剥离地址")
        // 经 /seg 剥离后字节流以 TS 同步字节开头且长度恰为 TS 包总和
        val bytes = java.net.URL(segLine).openStream().use { it.readBytes() }
        assertEquals(TS_PACKETS * TS_PACKET_LEN, bytes.size, "剥离后长度应为 TS 包总和")
        assertEquals(0x47, bytes[0].toInt() and 0xFF, "剥离后应以 TS 同步字节开头")
        assertEquals(0x47, bytes[TS_PACKET_LEN].toInt() and 0xFF, "第二包同步字节应保持 188 步进")
    }

    /** 经 /m3u8 过滤路由请求播放列表（走装饰入口，header 为空） */
    private fun fetchViaFilterRoute(originUrl: String): String =
        httpGet(DesktopProxyRoutes.decoratePlayUrl(originUrl, emptyMap(), filterEnabled = true))

    /** 模拟源服务器根地址（随机端口） */
    private fun originRoot(): String = "http://127.0.0.1:${origin.listeningPort}"

    private fun httpGet(url: String): String =
        java.net.URL(url).readText()
}
