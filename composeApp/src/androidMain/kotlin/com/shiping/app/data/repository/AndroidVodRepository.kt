package com.shiping.app.data.repository

import android.util.Log
import com.shiping.app.data.remote.ApiService
import com.shiping.desktop.data.model.ApiResponse
import com.shiping.desktop.data.model.ApiSourceEntity
import com.shiping.desktop.data.model.Category
import com.shiping.desktop.data.model.Vod
import com.shiping.desktop.data.repository.SearchGroupData
import com.shiping.desktop.data.spider.SpiderIdRegistry
import com.shiping.desktop.data.spider.SpiderMapper
import com.shiping.desktop.data.spider.SpiderPlayInfo
import com.shiping.desktop.util.Constants
import com.shiping.shared.repository.ApiSourceRepository
import com.shiping.shared.repository.ParseSourceRepository
import com.shiping.shared.repository.VodRepository
import com.shiping.shared.spider.SpiderEngine
import com.shiping.shared.prefs.KeyValueStore
import com.shiping.shared.prefs.PrefsKeys
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/** 单源搜索超时（对齐 FongMi TIMEOUT_SEARCH）：卡死源超时回报，不再拖死整轮聚合搜索 */
private const val SEARCH_TIMEOUT_MILLIS = 30_000L

/**
 * 视频数据仓库 Android 实现（实现 shared 共享接口）：
 * CMS 接口源走 ApiService（Retrofit），TVBox 爬虫源（type 3）走 SpiderEngine
 * （SpiderRuntime，DexClassLoader）。逻辑与桌面 VodRepository 对齐。
 */
class AndroidVodRepository(
    private val apiService: ApiService,
    private val apiSourceRepository: ApiSourceRepository,
    private val parseSourceRepository: ParseSourceRepository,
    private val prefs: KeyValueStore,
    private val spiderEngine: SpiderEngine,
) : VodRepository {

    /** 站点实体缓存：url -> entity（siteType/jarUrl/spiderKey 为导入后固定字段） */
    private var siteCache: Pair<String, ApiSourceEntity?>? = null

    /** 爬虫分类缓存：按源地址缓存 homeContent 的 class 与 Int id 到真实 tid 的映射 */
    private var spiderCategoryCache: SpiderCategoryCache? = null

    /** 过滤切片广告（设置开关）：剔除名称/备注含「切片」的条目 */
    private fun filterSliceAds(list: List<Vod>): List<Vod> {
        if (!prefs.getBoolean(PrefsKeys.KEY_FILTER_SLICE_AD, false)) return list
        return list.filter { !it.vodName.contains("切片") && !it.vodRemarks.contains("切片") }
    }

    /** 获取首页视频列表（附带分类）；部分 CMS 源列表不返回封面，需批量详情补全 */
    override suspend fun getHome(page: Int, typeId: Int, url: String?): ApiResponse<Vod> {
        val baseUrl = url?.takeIf { it.isNotBlank() } ?: apiSourceRepository.currentApiUrlOnce()
        val site = getSite(baseUrl)
        if (site != null && site.isSpider()) return spiderHome(site, page, typeId)
        val response = apiService.getVodList(baseUrl = baseUrl, page = page, typeId = typeId)
        val filtered = filterSliceAds(response.list)
        val detailMap = runCatching { getDetails(filtered.map { it.vodId }, url = baseUrl) }
            .getOrDefault(emptyMap())
        return response.copy(list = filtered.map { vod -> detailMap[vod.vodId]?.let { vod.mergeWithDetail(it) } ?: vod })
    }

    /** 搜索 */
    override suspend fun search(keyword: String, page: Int, url: String?): ApiResponse<Vod> {
        val baseUrl = url ?: apiSourceRepository.currentApiUrlOnce()
        val site = getSite(baseUrl)
        if (site != null && site.isSpider()) return spiderSearch(site, keyword, page)
        return apiService.getVodList(baseUrl = baseUrl, page = page, keyword = keyword)
            .let { it.copy(list = filterSliceAds(it.list)) }
    }

    /** 获取视频详情 */
    override suspend fun getDetail(vodId: Int, url: String?): Vod? {
        val baseUrl = url?.takeIf { it.isNotBlank() } ?: apiSourceRepository.currentApiUrlOnce()
        val site = getSite(baseUrl)
        if (site != null && site.isSpider()) return spiderDetail(site, vodId)
        val response = apiService.getVodDetail(baseUrl = baseUrl, ids = vodId.toString())
        return response.list.firstOrNull()
    }

    /** 批量获取视频详情（补全列表封面），返回 vodId -> Vod 映射 */
    override suspend fun getDetails(ids: List<Int>, url: String?): Map<Int, Vod> {
        if (ids.isEmpty()) return emptyMap()
        val baseUrl = url ?: apiSourceRepository.currentApiUrlOnce()
        val site = getSite(baseUrl)
        if (site != null && site.isSpider()) return emptyMap()
        return ids.chunked(Constants.DETAIL_BATCH_SIZE).flatMap { batch ->
            runCatching {
                apiService.getVodDetail(baseUrl = baseUrl, ids = batch.joinToString(",")).list
            }.getOrElse {
                Log.w("VodRepository", "批量详情失败: ${it.message}")
                emptyList()
            }
        }.associateBy { it.vodId }
    }

    /** 在单个 API 源上搜索（含详情补全，30 秒硬超时），失败时返回带 error 的分组，不抛异常 */
    override suspend fun searchOneSource(
        source: ApiSourceEntity,
        keyword: String,
        page: Int,
    ): SearchGroupData = runCatching {
        withTimeout(SEARCH_TIMEOUT_MILLIS) {
            val response = search(keyword, page = page, url = source.url)
            val list = if (source.isSpider()) {
                // 爬虫源：结果自带封面与备注，无需详情补全
                response.list
            } else {
                val detailMap = runCatching {
                    getDetails(response.list.map { it.vodId }, url = source.url)
                }.getOrDefault(emptyMap())
                response.list.map { vod ->
                    detailMap[vod.vodId]?.let { vod.mergeWithDetail(it) } ?: vod
                }
            }
            SearchGroupData(
                sourceId = source.id,
                sourceName = source.name,
                sourceUrl = source.url,
                results = list,
                page = page,
                hasMore = page < response.pageCount,
                done = true,
            )
        }
    }.getOrElse { e ->
        SearchGroupData(
            sourceId = source.id,
            sourceName = source.name,
            sourceUrl = source.url,
            error = if (e is TimeoutCancellationException) "搜索超时（源无响应）" else e.message ?: "搜索失败",
            done = true,
        )
    }

    /** 解析播放地址：爬虫源（type 3）调 playerContent 得到直链与请求头；CMS 源返回 null */
    override suspend fun resolvePlayUrl(sourceUrl: String, rawUrl: String): SpiderPlayInfo? {
        if (rawUrl.isBlank()) return null
        val site = getSite(sourceUrl) ?: return null
        if (!site.isSpider()) return null
        return runCatching {
            val json = spiderEngine.playerContent(
                site.jarUrl, site.spiderKey, flag = "", id = rawUrl, ext = site.ext, siteKey = site.url,
            )
            Log.i("VodRepository", "playerContent 返回: ${json.take(400)}")
            SpiderMapper.parsePlayInfo(json, parseSourceRepository.currentParserUrl())
                .takeIf { it.url.isNotBlank() }
        }.getOrElse {
            Log.e("VodRepository", "resolvePlayUrl 失败: ${it.message}")
            null
        }
    }

    // region 爬虫源分支

    /**
     * 爬虫源首页/分类：第 1 页且未选分类用 homeContent 推荐（空时回退 homeVideoContent），
     * 其余按真实 tid 拉分类。异常向外抛出，由 ViewModel 呈现具体原因。
     */
    private suspend fun spiderHome(site: ApiSourceEntity, page: Int, typeId: Int): ApiResponse<Vod> {
        val (categories, tidMap) = spiderCategories(site)
        val realTid = if (typeId > 0) {
            tidMap[typeId] ?: tidMap.values.firstOrNull()
        } else if (page > 1) {
            tidMap.values.firstOrNull()
        } else {
            null
        }
        if (realTid == null) {
            val json = spiderEngine.homeContent(site.jarUrl, site.spiderKey, ext = site.ext, siteKey = site.url)
            val list = SpiderMapper.parseVodList(json, site.url).ifEmpty {
                SpiderMapper.parseVodList(
                    spiderEngine.homeVideoContent(site.jarUrl, site.spiderKey, ext = site.ext, siteKey = site.url),
                    site.url,
                )
            }
            return ApiResponse(page = 1, pageCount = 1, list = filterSliceAds(list), classList = categories)
        }
        val json = spiderEngine.categoryContent(
            site.jarUrl, site.spiderKey, realTid, page.toString(), ext = site.ext, siteKey = site.url,
        )
        val (pageCount, total) = SpiderMapper.parsePaging(json)
        return ApiResponse(
            page = page,
            pageCount = pageCount,
            total = total,
            list = filterSliceAds(SpiderMapper.parseVodList(json, site.url)),
            classList = categories,
        )
    }

    /** 爬虫源分类（homeContent 的 class），按源地址缓存（空结果不缓存以便重试） */
    private suspend fun spiderCategories(site: ApiSourceEntity): Pair<List<Category>, Map<Int, String>> {
        spiderCategoryCache?.takeIf { it.sourceUrl == site.url }?.let { return it.categories to it.tidMap }
        val data = SpiderMapper.parseCategories(
            spiderEngine.homeContent(site.jarUrl, site.spiderKey, ext = site.ext, siteKey = site.url),
        )
        if (data.first.isNotEmpty()) {
            spiderCategoryCache = SpiderCategoryCache(site.url, data.first, data.second)
        }
        return data
    }

    /** 爬虫源搜索：结果为空视为最后一页，非空允许再翻一页探测 */
    private suspend fun spiderSearch(site: ApiSourceEntity, keyword: String, page: Int): ApiResponse<Vod> {
        val json = spiderEngine.searchContent(
            site.jarUrl, site.spiderKey, keyword, page, ext = site.ext, siteKey = site.url,
        )
        val list = filterSliceAds(SpiderMapper.parseVodList(json, site.url))
        return ApiResponse(page = page, pageCount = if (list.isEmpty()) page else page + 1, list = list)
    }

    /** 爬虫源详情：还原真实 id -> detailContent -> 映射（含播放线路） */
    private suspend fun spiderDetail(site: ApiSourceEntity, vodId: Int): Vod? {
        val realId = SpiderIdRegistry.toRealId(site.url, vodId) ?: vodId.toString()
        val json = spiderEngine.detailContent(site.jarUrl, site.spiderKey, realId, ext = site.ext, siteKey = site.url)
        val vod = SpiderMapper.parseDetail(json, site.url)
        if (vod == null) {
            Log.w("VodRepository", "爬虫详情为空 key=${site.spiderKey} vodId=$vodId realId=$realId jsonLen=${json.length} head=${json.take(180)}")
            // 爬虫业务提示（如体育源「比赛尚未开始」）优先于笼统失败文案
            SpiderMapper.parseDetailMessage(json)?.let { throw IllegalStateException(it) }
        }
        return vod
    }

    // endregion

    /** 按 url 查站点实体（单条缓存：这些字段导入后不可变） */
    private suspend fun getSite(url: String): ApiSourceEntity? {
        val target = url.trim()
        siteCache?.let { (cachedUrl, site) -> if (cachedUrl == target) return site }
        val site = apiSourceRepository.sources.value.firstOrNull { it.url.trim() == target }
        siteCache = target to site
        return site
    }

    /** 是否为 TVBox 爬虫源（type 3） */
    private fun ApiSourceEntity.isSpider(): Boolean = siteType == Constants.SITE_TYPE_SPIDER

    /** 爬虫分类缓存条目 */
    private class SpiderCategoryCache(
        val sourceUrl: String,
        val categories: List<Category>,
        val tidMap: Map<Int, String>,
    )
}
