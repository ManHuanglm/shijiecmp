package com.shiping.desktop.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 播放地址解析与展示格式化（/hdr 代理包装、直链判断、时长/倍速格式） */
class PlaybackUrlResolverTest {

    @Test
    fun 无header或仅UAReferer不需要代理() {
        assertFalse(PlaybackUrlResolver.needsHdrProxy(emptyMap()))
        assertFalse(PlaybackUrlResolver.needsHdrProxy(mapOf("User-Agent" to "UA", "Referer" to "https://r")))
        assertFalse(PlaybackUrlResolver.needsHdrProxy(mapOf("user-agent" to "UA")))
    }

    @Test
    fun 其余header需要hdr代理() {
        assertTrue(PlaybackUrlResolver.needsHdrProxy(mapOf("Cookie" to "sid=1")))
        assertTrue(
            PlaybackUrlResolver.needsHdrProxy(
                mapOf("User-Agent" to "UA", "Cookie" to "sid=1", "Authorization" to "Bearer t"),
            ),
        )
    }

    @Test
    fun hdr包装_参数编码与空格转义() {
        val wrapped = PlaybackUrlResolver.wrapHdrProxy(
            "https://v.example.com/a b.mp4",
            mapOf("Referer" to "https://r.example.com/"),
            9978,
        )
        // URLEncoder 的空格为 +，实现统一替换为 %20（NanoHTTPD 不解 + 为空格）；
        // header 文本拼接格式为「Key:Value」（冒号后无空格）
        assertEquals("http://127.0.0.1:9978/hdr?url=https%3A%2F%2Fv.example.com%2Fa%20b.mp4&h=Referer%3Ahttps%3A%2F%2Fr.example.com%2F", wrapped)
    }

    @Test
    fun resolve按需包装() {
        val direct = PlaybackUrlResolver.resolve("https://v.example.com/a.mp4", emptyMap(), 9978)
        assertEquals("https://v.example.com/a.mp4", direct)
        val viaProxy = PlaybackUrlResolver.resolve("https://v.example.com/a.mp4", mapOf("Cookie" to "k=v"), 9978)
        assertTrue(viaProxy.startsWith("http://127.0.0.1:9978/hdr?url="))
    }

    @Test
    fun 时长格式化() {
        assertEquals("00:00", formatTime(0))
        assertEquals("00:59", formatTime(59_000))
        assertEquals("01:01", formatTime(61_000))
        assertEquals("1:01:01", formatTime(3_661_000))
        assertEquals("00:00", formatTime(-5_000))
    }

    @Test
    fun 倍速格式化保留一位小数() {
        assertEquals("1.0x", formatSpeed(1.0f))
        assertEquals("0.5x", formatSpeed(0.5f))
        // %.1f 四舍五入进位
        assertEquals("1.3x", formatSpeed(1.25f))
    }

    @Test
    fun 直链判断() {
        assertTrue(isDirectStreamUrl("https://v.example.com/a.m3u8"))
        assertTrue(isDirectStreamUrl("https://v.example.com/a.mp4?token=1"))
        assertTrue(isDirectStreamUrl("https://v.example.com/a.M3U8"))
        assertTrue(isDirectStreamUrl("https://v.example.com/a.flv"))
        assertTrue(isDirectStreamUrl("https://v.example.com/a.ts"))
        assertFalse(isDirectStreamUrl("https://v.example.com/page.html"))
        assertFalse(isDirectStreamUrl("https://v.example.com/"))
    }
}
