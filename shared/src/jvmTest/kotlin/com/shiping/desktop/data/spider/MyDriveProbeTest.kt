package com.shiping.desktop.data.spider

import android.app.Application
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File
import java.net.URLClassLoader

/**
 * 动态诊断探针 v5（非功能测试）：端到端验证网盘 Cookie 推送链路——
 * 内直调 ProxyOrigin.proxy(do=X&type=input) 后重拉 homeContent，
 * 观察网盘登录状态行是否翻转（推送 → files/<drive>_ut → 状态读取）。
 * 仅本地手工运行：gradlew :shared:jvmTest --tests "*MyDriveProbe*"
 */
class MyDriveProbeTest {

    @Test
    fun probeProxyInput() = runBlocking {
        System.setProperty("shiping.debug.http", "true")
        val dataDir = File("C:\\Users\\Administrator\\Pictures\\视界Desktop")
        val prefsDir = File(dataDir, "probe_prefs").apply { mkdirs() }
        android.content.MemSharedPreferences.storeDir = prefsDir
        val context = object : Application() {
            override val baseDir: File get() = dataDir
        }
        DesktopSpiderRuntime.init(context, dataDir)

        val jarUrl = "https://file.icve.com.cn/file_doc/933/630/E959D9455533A04AF01A15C4EB1B78EA.jpg;md5;e959d9455533a04af01a15c4eb1b78ea"
        val key = "csp_MyDriveGuard"
        val ext = "{\"Cloud-drive\":\"./tvfan/Cloud-drive.txt\"}"

        // 基线：推送前「云盘配置」页的网盘状态行
        val home1 = DesktopSpiderRuntime.categoryContent(jarUrl, key, "peizhi", "1", ext = ext, siteKey = key)
        println("=== home1（推送前）===")
        println(home1.take(6000))

        // 内直调推送：ProxyOrigin.proxy({do=UC, type=input, 推送=测试值})
        val loader = payloadLoader()
        val origin = loader.loadClass("com.github.catvod.spider.ProxyOrigin")
        val proxy = origin.getDeclaredMethod("proxy", Map::class.java).apply { isAccessible = true }
        val params = mapOf("do" to "UC", "type" to "input", "推送" to "__TEST_UC__")
        val pushed = runCatching { proxy.invoke(null, params) }
        println("=== push($params) ===")
        println("结果: ${pushed.fold({ it }, { "异常 ${it.cause?.javaClass?.simpleName}: ${it.cause?.message?.take(300)}" })}")

        // 推送后：登录状态行是否翻转
        val home2 = DesktopSpiderRuntime.categoryContent(jarUrl, key, "peizhi", "1", ext = ext, siteKey = key)
        println("=== home2（推送后）===")
        println(home2.take(6000))
        println("=== 状态差分 ===")
        println("home1 == home2 ? ${home1 == home2}")
        Unit
    }

    /** 从 DesktopSpiderRuntime 私有缓存取 payload URLClassLoader */
    private fun payloadLoader(): URLClassLoader {
        val field = DesktopSpiderRuntime::class.java.getDeclaredField("classLoaders")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val map = field.get(null) as Map<String, URLClassLoader>
        val path = map.keys.first { it.contains("e959d9455533a04af01a15c4eb1b78ea_payload") }
        return map.getValue(path)
    }
}
