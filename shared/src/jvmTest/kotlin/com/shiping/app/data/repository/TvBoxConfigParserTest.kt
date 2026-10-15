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
    fun 多仓配置兼容中文键名() {
        val json = """
            {"storeHouse": [
              {"仓名":"匿名单仓","地址":"https://a.example.com/123.txt"},
              {"sourceName":"无名","sourceUrl":"https://b.example.com/lmw.json"}
            ]}
        """.trimIndent()
        val result = parse(json)
        assertTrue(result.ok)
        assertEquals(2, result.warehouses.size)
        assertEquals("匿名单仓" to "https://a.example.com/123.txt", result.warehouses.first())
    }

    @Test
    fun HTML落地页提取候选配置地址() {
        val html = """
            <!DOCTYPE html>
            <html lang="zh-CN"><head><title>TvBox接口源</title>
            <link rel="stylesheet" href="//cdn.staticfile.net/font-awesome/6.5.1/css/all.min.css">
            <script src="./todo-sdk.min.js"></script></head>
            <body><a class="main-btn" href="https://app.example.com/tv">一键配置</a>
            <a href="https://www.example.com/about">关于</a></body></html>
        """.trimIndent()
        val candidates = TvBoxConfigParser.landingCandidates(html.toByteArray(), "https://www.example.com/tv")
        assertTrue(candidates.isNotEmpty())
        assertEquals("https://app.example.com/tv", candidates.first())
        // 相对地址按 baseUrl 补全、静态资源被排除
        assertTrue(candidates.none { it.contains("staticfile") || it.contains("todo-sdk") })
    }

    @Test
    fun 非HTML内容不做落地页提取() {
        val json = """{"urls":[]}"""
        assertTrue(TvBoxConfigParser.landingCandidates(json.toByteArray(), "https://a.example.com/x.json").isEmpty())
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
    fun 站点searchable字段决定可搜状态() {
        val json = """
            {"sites": [
              {"key":"a","name":"缺省源","type":3,"api":"csp_A","jar":"https://j.example.com/a.jar"},
              {"key":"b","name":"显式可搜","type":3,"api":"csp_B","jar":"https://j.example.com/b.jar","searchable":1},
              {"key":"c","name":"不可搜","type":3,"api":"csp_C","jar":"https://j.example.com/c.jar","searchable":0},
              {"key":"d","name":"CMS禁搜","type":0,"api":"https://api.example.com/vod","searchable":0}
            ]}
        """.trimIndent()
        val result = parse(json)
        assertTrue(result.ok)
        val byName = result.sites.associateBy { it.name }
        assertTrue(byName.getValue("缺省源").searchable, "缺省视为可搜（对齐 FongMi null→1）")
        assertTrue(byName.getValue("显式可搜").searchable)
        assertFalse(byName.getValue("不可搜").searchable, "searchable=0 不参与聚合搜索")
        assertFalse(byName.getValue("CMS禁搜").searchable)
    }

    @Test
    fun 缺引号键名修复且字符串内斜杠不受损() {
        // 肥猫系配置手写笔误：ext 键缺开头引号；且 ext.site 字符串内含 //（曾被状态机错位误删）
        val json = """
            {
              "spider": "https://j.example.com/x.jar;md5;ff",
              "sites": [
                {"key":"a","name":"行动","type":3,"api":"csp_AppDrama",
ext": {"site": " https://cdn.example.com/oss/az5.txt ","pkg":"com.x"}}
              ],
              "lives": [
                {"name":"聚合","type":3,"url":"https://live.example.com/g.m3u"}
              ]
            }
        """.trimIndent()
        val result = parse(json)
        assertTrue(result.ok)
        assertEquals(1, result.sites.size)
        val site = result.sites.single()
        assertTrue(site.ext.contains("https://cdn.example.com/oss/az5.txt"), "字符串内 // 应原样保留")
        assertEquals("com.x", Regex("\"pkg\":\"([^\"]+)\"").find(site.ext)?.groupValues?.get(1))
        // 缺引号键修复后 ext 应为合法 JSON 对象串
        assertTrue(site.ext.contains("\"host\"") || site.ext.contains("\"site\""))
        // lives type 3（聚合直播）应收录
        assertEquals(1, result.lives.size)
        assertEquals("https://live.example.com/g.m3u", result.lives.single().url)
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
