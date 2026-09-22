package com.shiping.app.data.repository

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** TVBox 配置解析：明文 JSON（对象/数组/多仓）、站点分类、jar 兜底、解析/直播提取 */
class TvBoxConfigParserTest {

    private val parser = TvBoxConfigParser

    private fun parse(json: String) = parser.parse(json.toByteArray(Charsets.UTF_8))

    @Test
    fun 明文对象配置_提取CMS与爬虫站点() {
        val json = """
            {
              "spider": "https://cdn.example.com/fty.jar;md5;0123456789abcdef0123456789abcdef",
              "sites": [
                {"key":"home","name":"首页推荐","type":3,"api":"csp_DouDou","ext":{"flag":"站点级"}},
                {"key":"cms1","name":"CMS一","type":0,"api":"https://api.example.com/api.php/provide/vod"},
                {"key":"xml1","name":"XML源","type":1,"api":"https://xml.example.com/api.php/provide/vod/at/xml"}
              ]
            }
        """.trimIndent()
        val result = parse(json)

        assertTrue(result.ok)
        assertEquals(0, result.spiderCount)
        assertEquals(3, result.sites.size)
        // 全局 spider 兜底：站点无 jar 时使用全局地址（保留分号完整串）
        assertEquals("https://cdn.example.com/fty.jar;md5;0123456789abcdef0123456789abcdef", result.spiderJar)
        val spider = result.sites.first { it.type == TvBoxConfigParser.SITE_TYPE_SPIDER }
        assertEquals("csp_DouDou", spider.url)
        assertEquals("首页推荐", spider.name)
        assertEquals("home", spider.siteKey)
        assertTrue(spider.ext.contains("站点级"))
        // CMS 源
        val cms = result.sites.first { it.type == 0 && !it.isXml }
        assertEquals("https://api.example.com/api.php/provide/vod", cms.url)
        // XML 接口地址以 /at/xml 结尾时剥掉后缀归一为 JSON 接口
        val xml = result.sites.first { !it.isXml && it.name == "XML源" }
        assertEquals("https://xml.example.com/api.php/provide/vod", xml.url)
    }

    @Test
    fun 站点级jar优先于全局() {
        val json = """
            {
              "spider": "https://global.example.com/global.jar",
              "sites": [
                {"key":"s1","name":"带站jar","type":3,"api":"csp_A","jar":"https://site.example.com/site.jar;md5;ff"}
              ]
            }
        """.trimIndent()
        val result = parse(json)
        val site = result.sites.single()
        assertTrue(site.jar.startsWith("https://site.example.com/site.jar"))
        assertFalse(site.jar.contains("global"))
    }

    @Test
    fun 爬虫站点缺jar计入spiderCount不导入() {
        val json = """
            {"sites": [
              {"key":"s1","name":"无jar","type":3,"api":"csp_NoJar"},
              {"key":"s2","name":"CMS二","type":0,"api":"https://api.example.com/vod"}
            ]}
        """.trimIndent()
        val result = parse(json)
        assertTrue(result.ok)
        assertEquals(1, result.spiderCount)
        assertEquals(1, result.sites.size)
    }

    @Test
    fun 数组式配置直接解析() {
        val json = """
            [
              {"key":"c1","name":"CMS三","type":0,"api":"https://api.example.com/vod"},
              {"key":"c2","name":"CMS四","type":1,"api":"https://xml.example.com/vod"}
            ]
        """.trimIndent()
        val result = parse(json)
        assertTrue(result.ok)
        assertEquals(2, result.sites.size)
        assertTrue(result.sites.first { it.name == "CMS四" }.isXml)
    }

    @Test
    fun 多仓配置识别仓库名与地址() {
        val json = """
            {"urls": [
              {"name":"仓一","url":"https://a.example.com/1.json"},
              {"name":"仓二","url":"https://b.example.com/2.json"}
            ]}
        """.trimIndent()
        val result = parse(json)
        assertTrue(result.ok)
        assertEquals(2, result.warehouses.size)
        assertEquals("仓一" to "https://a.example.com/1.json", result.warehouses.first())
        assertTrue(result.sites.isEmpty())
    }

    @Test
    fun 解析源仅取前缀拼接式() {
        val json = """
            {"parses": [
              {"name":"JSON解析","type":0,"url":"https://jx.example.com/?url="},
              {"name":"网页壳","type":3,"url":"https://web.example.com/"},
              {"name":"非拼接","type":0,"url":"https://jx2.example.com/play"}
            ]}
        """.trimIndent()
        val result = parse(json)
        assertTrue(result.ok)
        assertEquals(1, result.parses.size)
        assertEquals("JSON解析" to "https://jx.example.com/?url=", result.parses.first().let { it.name to it.url })
    }

    @Test
    fun 空与非JSON内容返回不支持原因() {
        assertFalse(parse("").ok)
        assertFalse(parse("   ").ok)
        val bad = parse("这是一段纯文本配置")
        assertFalse(bad.ok)
        assertTrue(bad.unsupportedReason != null)
    }
}
