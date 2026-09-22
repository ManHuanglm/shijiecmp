package com.shiping.app

import com.google.gson.JsonParser
import com.shiping.app.data.repository.TvBoxConfigParser
import com.shiping.desktop.data.spider.DesktopSpiderRuntime
import com.shiping.desktop.data.spider.SpiderHttp
import com.shiping.desktop.di.AppContainer
import kotlinx.coroutines.runBlocking
import okhttp3.Request

/**
 * M1 临时探针入口（验证 shared 爬虫链路）：拉取饭太硬订阅配置 → 解析站点 →
 * 直调 DesktopSpiderRuntime homeContent/detailContent/playerContent（jar 下载 →
 * dex2jar 转换 → 反射调用 → 代理端口注入）。M2 起由正式桌面入口替代。
 */
fun main() {
    AppContainer.get()
    val configUrl = "http://www.饭太硬.net/tv"
    val body = SpiderHttp.client.newCall(Request.Builder().url(configUrl).build()).execute().use {
        it.body?.bytes()
    } ?: error("订阅配置下载失败")
    val result = TvBoxConfigParser.parse(body)
    val spiderSites = result.sites.filter { it.type == 3 && it.jar.isNotBlank() && it.url.startsWith("csp_") }
    println("[probe] config ok=${result.ok} sites=${result.sites.size} spiderSites=${spiderSites.size}")
    // 站点优先级：跳过已知问题源（豆豆=豆瓣风 detail SIOOBE、AppSx=上游故障、YGP=越界），
    // 依次尝试直至完整走通 home→category→detail→player
    val preferred = listOf("csp_LibvioGuard", "csp_NmyswvGuard", "csp_AllliveGuard", "csp_T4Guard")
    val site = preferred.firstNotNullOfOrNull { key -> spiderSites.firstOrNull { it.url == key } }
        ?: spiderSites.firstOrNull { it.url !in listOf("csp_DouDouGuard", "csp_AppSx", "csp_YGP") }
        ?: error("无可用爬虫站点")
    println("[probe] site=${site.name} key=${site.url} jar=${site.jar.take(60)}")

    // 应用级链路验证：站点入库 → VodRepository（spiderHome/spiderDetail/resolvePlayUrl，
    // 含 SpiderIdRegistry 映射与 SpiderMapper 解析），与正式 App 行为一致
    runBlocking {
        val container = AppContainer.get()
        val sourceId = container.apiSourceRepository.insert(
            com.shiping.desktop.data.model.ApiSourceEntity(
                name = "probe_${site.name}",
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
            println("[probe] home classes=${home.classList.size} list=${home.list.size} first=${home.list.firstOrNull()?.vodName}")
            val vodId: Int = home.list.firstOrNull()?.vodId ?: run {
                val typeId = home.classList.firstOrNull()?.typeId ?: error("无分类")
                val category = container.vodRepository.getHome(typeId = typeId, url = sourceUrl)
                println("[probe] category list=${category.list.size}")
                category.list.firstOrNull()?.vodId ?: error("分类无条目")
            }
            val detail = container.vodRepository.getDetail(vodId = vodId, url = sourceUrl)
                ?: error("详情获取失败")
            val playSource = detail.parsePlaySources().firstOrNull() ?: error("无播放源")
            val episode = playSource.episodes.firstOrNull() ?: error("无剧集")
            println("[probe] detail name=${detail.vodName} from=${playSource.name} ep0=${episode.name}->${episode.url.take(60)}")
            val playInfo = container.vodRepository.resolvePlayUrl(sourceUrl, episode.url)
            println("[probe] play url=${playInfo?.url?.take(100)} headers=${playInfo?.headers?.keys} parse=${playInfo?.parse}")
        } finally {
            // 探针与原桌面 App 共用数据目录，用后即清避免污染源列表
            container.apiSourceRepository.deleteByIds(listOf(sourceId))
        }
    }
    println("[probe] proxyPort=${com.shiping.shared.spider.SpiderProxyServer.listeningPort}")
}

/** TVBox JSON 中数字/字符串混合字段统一按字符串取（宽松语义，对齐 payload 习惯） */
private fun com.google.gson.JsonElement?.asStringOrNull(): String? = when {
    this == null || isJsonNull -> null
    isJsonPrimitive -> asJsonPrimitive.asString
    else -> null
}
