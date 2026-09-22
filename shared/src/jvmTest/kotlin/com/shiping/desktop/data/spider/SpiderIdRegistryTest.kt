package com.shiping.desktop.data.spider

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/** 爬虫 vod_id 稳定映射：CRC32 + 源地址混入，防止跨源撞号 */
class SpiderIdRegistryTest {

    @Test
    fun 同源同id映射稳定() {
        val first = SpiderIdRegistry.toIntKey("https://a.example.com", "714893619")
        val second = SpiderIdRegistry.toIntKey("https://a.example.com", "714893619")
        assertEquals(first, second)
    }

    @Test
    fun 登记后可反查真实id() {
        val intKey = SpiderIdRegistry.toIntKey("https://a.example.com", "raw-episode-42")
        assertEquals("raw-episode-42", SpiderIdRegistry.toRealId("https://a.example.com", intKey))
    }

    @Test
    fun 不同真实id得到不同int键() {
        val a = SpiderIdRegistry.toIntKey("https://a.example.com", "100")
        val b = SpiderIdRegistry.toIntKey("https://a.example.com", "200")
        assertNotEquals(a, b)
    }

    @Test
    fun 跨源隔离_同真实id互不串号() {
        val keyA = SpiderIdRegistry.toIntKey("https://a.example.com", "shared-id")
        val keyB = SpiderIdRegistry.toIntKey("https://b.example.com", "shared-id")
        // CRC32 混入源地址：不同源的同 id 映射键不同（32 位哈希，取固定样例断言）
        assertNotEquals(keyA, keyB)
        assertEquals("shared-id", SpiderIdRegistry.toRealId("https://a.example.com", keyA))
        assertEquals("shared-id", SpiderIdRegistry.toRealId("https://b.example.com", keyB))
        assertNull(SpiderIdRegistry.toRealId("https://a.example.com", keyB))
    }

    @Test
    fun 源地址首尾空白不影响映射() {
        val key = SpiderIdRegistry.toIntKey("https://a.example.com", "x1")
        val keyPadded = SpiderIdRegistry.toIntKey("  https://a.example.com  ", "x1")
        assertEquals(key, keyPadded)
    }
}
