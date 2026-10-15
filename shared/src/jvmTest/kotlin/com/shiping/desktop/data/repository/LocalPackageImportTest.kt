package com.shiping.desktop.data.repository

import com.shiping.desktop.data.db.DesktopDb
import com.shiping.desktop.data.model.ApiSourceEntity
import com.shiping.desktop.data.prefs.DesktopPrefs
import com.shiping.desktop.data.spider.SpiderHttp
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** 本地包（clan://localhost/）导入链路集成验证：临时包目录 + 临时 SQLite，
 * 验证相对引用（jar/ext/脚本路径）改写为 clan:// 绝对路径并按爬虫源入库 */
class LocalPackageImportTest {

    @Test
    fun 本地包导入改写相对引用为clan绝对路径(): Unit = runBlocking {
        val pkgDir = Files.createTempDirectory("localpkg").toFile()
        val libDir = File(pkgDir, "lib").apply { mkdirs() }
        File(libDir, "tokenm.json").writeText("""{"token":"x"}""")
        File(pkgDir, "spider.jar").writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04))
        val config = File(pkgDir, "jsm.json")
        config.writeText(
            """
            {"spider":"./spider.jar;md5;abc",
             "sites":[
               {"key":"A","name":"甲","type":3,"api":"csp_Demo","ext":"./lib/tokenm.json${'$'}${'$'}${'$'}./lib/miss.json"},
               {"key":"B","name":"乙","type":3,"api":"csp_Demo2","ext":{"token":"./lib/tokenm.json","url":"http://x/y"}},
               {"key":"C","name":"丙","type":3,"api":"./lib/drpy.min.js"}
             ]}
            """.trimIndent(),
        )

        val dataDir = Files.createTempDirectory("shiping-test").toFile()
        val db = DesktopDb(File(dataDir, "test.db"))
        val apiRepo = ApiSourceRepository(db, DesktopPrefs(dataDir))
        val importer = TvBoxConfigImporter(
            apiRepo,
            ParseSourceRepository(db, DesktopPrefs(dataDir)),
            TvRepository(db, DesktopPrefs(dataDir)),
            SpiderHttp.client,
        )
        val summary = importer.import(config.absolutePath)
        assertNull(summary.error, "导入不应失败: ${summary.error}")
        assertEquals(3, summary.spiderImported)

        apiRepo.refresh()
        val spiders = apiRepo.sources.value.filter { it.siteType == 3 }
        assertEquals(3, spiders.size)
        val byName = spiders.associateBy { it.name }

        val a = byName.getValue("甲")
        assertEquals("A", a.url)
        assertEquals("csp_Demo", a.spiderKey)
        assertTrue(a.jarUrl.startsWith(ClanPaths.PREFIX) && a.jarUrl.endsWith("spider.jar;md5;abc"), "jar: ${a.jarUrl}")
        assertEquals(
            File(pkgDir, "spider.jar").canonicalFile,
            ClanPaths.toFile(a.jarUrl.substringBefore(";"))?.canonicalFile,
        )
        assertEquals(2, a.ext.split("\$\$\$").size, "ext 应含两段 clan 引用: ${a.ext}")
        assertEquals(
            File(libDir, "tokenm.json").canonicalFile,
            ClanPaths.toFile(a.ext.substringBefore("\$\$\$"))?.canonicalFile,
        )

        val b = byName.getValue("乙")
        assertTrue(b.ext.contains(ClanPaths.PREFIX), "JSON ext 应改写相对值: ${b.ext}")
        assertTrue(b.ext.contains("http://x/y"), "JSON ext 非相对值应保留: ${b.ext}")

        val c = byName.getValue("丙")
        assertEquals(
            File(libDir, "drpy.min.js").canonicalFile,
            ClanPaths.toFile(c.spiderKey)?.canonicalFile,
        )
        assertEquals("", c.jarUrl)

        pkgDir.deleteRecursively()
        dataDir.deleteRecursively()
    }
}
