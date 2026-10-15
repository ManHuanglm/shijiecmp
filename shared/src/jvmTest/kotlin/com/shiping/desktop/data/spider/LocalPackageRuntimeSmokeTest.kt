package com.shiping.desktop.data.spider

import android.app.Application
import com.shiping.desktop.data.db.DesktopDb
import com.shiping.desktop.data.prefs.DesktopPrefs
import com.shiping.desktop.data.repository.ClanPaths
import com.shiping.desktop.data.repository.TvBoxConfigImporter
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** TVBoxOSC 本地包端到端冒烟（依赖项目内 TVBoxOSC 目录，缺失即跳过）：
 * clan://localhost/<相对路径> 订阅导入 → spider jar 转换加载 → 站点实例化与 homeContent */
class LocalPackageRuntimeSmokeTest {

    private val oscRoot: File? by lazy {
        sequenceOf(
            File("..").absoluteFile.normalize(),
            File(".").absoluteFile.normalize(),
            File(System.getProperty("user.dir")).normalize(),
        ).mapNotNull { base -> File(base, "TVBoxOSC").takeIf { it.isDirectory } }.firstOrNull()
    }

    @Test
    fun tvboxosc订阅导入与站点加载(): Unit = runBlocking {
        val root = oscRoot ?: return@runBlocking
        // 包根目录候选：项目根（含 TVBoxOSC/）
        ClanPaths.setRelativeRoots(root.parentFile)
        val dataDir = Files.createTempDirectory("shiping-smoke").toFile()
        val stub = object : Application() {
            override val baseDir: File get() = dataDir
        }
        DesktopSpiderRuntime.init(stub, dataDir)

        // 1) 主配置导入（clan 相对形态）
        val db = DesktopDb(File(dataDir, "test.db"))
        val prefs = DesktopPrefs(dataDir)
        val apiRepo = com.shiping.desktop.data.repository.ApiSourceRepository(db, prefs)
        val importer = TvBoxConfigImporter(
            apiRepo,
            com.shiping.desktop.data.repository.ParseSourceRepository(db, prefs),
            com.shiping.desktop.data.repository.TvRepository(db, prefs),
            SpiderHttp.client,
        )
        val summary = importer.import("clan://localhost/TVBoxOSC/tvbox/api.json")
        assertNull(summary.error, "导入失败: ${summary.error}")
        assertTrue(summary.spiderImported > 0, "应导入爬虫源: ${summary.messageText()}")
        apiRepo.refresh()
        val douban = apiRepo.sources.value.firstOrNull { it.spiderKey == "csp_Douban" }
        assertTrue(douban != null, "应包含豆瓣源")
        assertTrue(douban!!.jarUrl.startsWith(ClanPaths.PREFIX), "jar 应为 clan 形态: ${douban.jarUrl}")

        // 2) 多仓订阅（本地仓.txt）返回子配置列表
        val multi = importer.import("clan://localhost/TVBoxOSC/本地仓/本地仓.txt")
        assertNull(multi.error, "多仓导入失败: ${multi.error}")
        assertTrue(multi.warehouses.isNotEmpty(), "应返回子配置列表")

        // 3) Init 引导门禁：csp_Douban 依赖 Init.context()（引导失败必报 context() is null）
        val doubanJson = runCatching {
            DesktopSpiderRuntime.homeContent(douban.jarUrl, "csp_Douban", ext = "", siteKey = "豆瓣")
        }.getOrElse { e ->
            assertTrue(
                e.message?.contains("context() is null") != true,
                "Init 引导失败: ${e.message}",
            )
            ""
        }
        if (doubanJson.isNotBlank()) println("SMOKE csp_Douban: " + doubanJson.take(200))

        // 4) 本地视频源（无外网依赖）实例化并返回结构
        val json = DesktopSpiderRuntime.homeContent(
            douban.jarUrl,
            "csp_LocalFile",
            ext = "",
            siteKey = "本地",
        )
        println("SMOKE csp_LocalFile: " + json.take(200))
        assertTrue(json.isNotBlank(), "homeContent 不应为空")
        dataDir.deleteRecursively()
    }
}
