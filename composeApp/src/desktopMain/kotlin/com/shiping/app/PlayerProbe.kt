package com.shiping.app

import com.shiping.app.data.repository.TvBoxConfigParser
import com.shiping.desktop.data.model.ApiSourceEntity
import com.shiping.desktop.data.spider.SpiderHttp
import com.shiping.desktop.di.AppContainer
import com.shiping.desktop.player.PlaybackUrlResolver
import com.shiping.desktop.player.PlayerControllerFactory
import com.shiping.desktop.player.PlayerKernel
import com.shiping.shared.spider.SpiderProxyServer
import kotlinx.coroutines.runBlocking
import okhttp3.Request

/**
 * M2 临时探针入口（验证 shared 播放链路）：复用 M1 爬虫链路解析出真实直链后，
 * 用 mpv 内核（PlayerControllerFactory）起播并打印播放状态，验证
 * JNA 内核加载（app-resources mpv/）→ play → timeMs/durationMs 推进 → releaseAsync。
 * M3 起由正式桌面入口替代。
 */
fun main() {
    val container = AppContainer.get()
    val configUrl = "http://www.饭太硬.net/tv"
    val body = SpiderHttp.client.newCall(Request.Builder().url(configUrl).build()).execute().use {
        it.body?.bytes()
    } ?: error("订阅配置下载失败")
    val result = TvBoxConfigParser.parse(body)
    val site = result.sites.firstOrNull { it.url == "csp_LibvioGuard" }
        ?: result.sites.firstOrNull { it.type == 3 && it.jar.isNotBlank() }
        ?: error("无爬虫站点")
    println("[probe] site=${site.name} key=${site.url}")

    runBlocking {
        val sourceId = container.apiSourceRepository.insert(
            ApiSourceEntity(
                name = "probe_play_${site.name}",
                url = site.siteKey.ifBlank { site.url },
                siteType = 3,
                jarUrl = site.jar,
                spiderKey = site.url,
                ext = site.ext,
            ),
        )
        try {
            val sourceUrl = container.apiSourceRepository.getById(sourceId)?.url ?: error("站点入库失败")
            val home = container.vodRepository.getHome(url = sourceUrl)
            val vodId: Int = home.list.firstOrNull()?.vodId ?: run {
                val typeId = home.classList.firstOrNull()?.typeId ?: error("无分类")
                container.vodRepository.getHome(typeId = typeId, url = sourceUrl)
                    .list.firstOrNull()?.vodId ?: error("分类无条目")
            }
            val detail = container.vodRepository.getDetail(vodId = vodId, url = sourceUrl) ?: error("详情失败")
            val episode = detail.parsePlaySources().firstOrNull()?.episodes?.firstOrNull() ?: error("无剧集")
            val playInfo = container.vodRepository.resolvePlayUrl(sourceUrl, episode.url)
                ?: error("播放地址解析失败")
            println("[probe] resolved url=${playInfo.url.take(90)} headers=${playInfo.headers.keys}")

            // mpv 内核起播：内核初始化在 Canvas addNotify 之后（需可显示的 AWT 窗口捕获 HWND）。
            // 顺序关键：先显示空窗口（peer 稳定），再挂渲染面（addNotify 立即捕获有效 HWND），
            // 避免「先挂面后显示」造成的 peer 重建使 wid 指向失效句柄
            val kernel = when (System.getProperty("probe.kernel") ?: "vlc") {
                "vlc" -> PlayerKernel.VLC
                "mdk" -> PlayerKernel.MDK
                else -> PlayerKernel.MPV
            }
            println("[probe] kernel=$kernel")
            val controller = PlayerControllerFactory.create(kernel, container.prefs)
            val surface = controller.renderSurface as? java.awt.Component ?: error("渲染面缺失")
            val frame = javax.swing.JFrame("probe").apply {
                defaultCloseOperation = javax.swing.WindowConstants.DISPOSE_ON_CLOSE
                setSize(640, 360)
                layout = java.awt.BorderLayout()
            }
            java.awt.EventQueue.invokeAndWait { frame.isVisible = true }
            java.awt.EventQueue.invokeAndWait {
                frame.add(surface, java.awt.BorderLayout.CENTER)
                frame.revalidate()
                surface.requestFocus()
            }
            // 等内核就绪（addNotify → 后台初始化）
            var waited = 0
            while (!controller.isReady && waited < 10_000) {
                Thread.sleep(250)
                waited += 250
            }
            println("[probe] kernel ready=$waited ms")
            val finalUrl = PlaybackUrlResolver.resolve(playInfo.url, playInfo.headers, SpiderProxyServer.listeningPort)
            controller.play(finalUrl, playInfo.headers, 0)
            repeat(3) {
                Thread.sleep(5000)
                println(
                    "[probe] ready=${controller.isReady} t=${controller.timeMs.value}ms dur=${controller.durationMs.value}ms " +
                        "playing=${controller.isPlaying.value} buffering=${controller.isBuffering.value} " +
                        "${controller.videoWidth.value}x${controller.videoHeight.value} " +
                        "err=${controller.errorText.value} initErr=${controller.initError.value}",
                )
            }
            // 站点直链可能已失效：timeMs 未推进时切换公开测试流（Mux HLS）区分内核问题与源失效
            if (controller.timeMs.value <= 0L) {
                println("[probe] 站点直链未推进，切测试流复验内核")
                controller.stop()
                controller.play("https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8", emptyMap(), 0)
                repeat(3) {
                    Thread.sleep(5000)
                    println(
                        "[probe] test t=${controller.timeMs.value}ms dur=${controller.durationMs.value}ms " +
                            "playing=${controller.isPlaying.value} ${controller.videoWidth.value}x${controller.videoHeight.value} " +
                            "err=${controller.errorText.value}",
                    )
                }
            }
            val tMs = controller.timeMs.value
            val shot = controller.snapshot()
            println("[probe] snapshot=$shot")
            controller.releaseAsync()
            java.awt.EventQueue.invokeAndWait { frame.dispose() }
            if (tMs <= 0L) {
                error("播放未推进（mpv 与测试流均未推进）")
            }
        } finally {
            // 探针与原桌面 App 共用数据目录，用后即清避免污染源列表
            container.apiSourceRepository.deleteByIds(listOf(sourceId))
        }
    }
    println("[probe] done proxyPort=${SpiderProxyServer.listeningPort}")
}
