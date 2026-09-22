package com.shiping.desktop.data.model

import com.google.gson.annotations.SerializedName
import com.shiping.desktop.util.Constants

/**
 * 桌面版数据模型（对齐 app 模块同名实体，去除 Room 注解，SQLite JDBC 直写）。
 */

/** API 源实体（CMS 接口 / TVBox 爬虫源） */
data class ApiSourceEntity(
    val id: Long = 0,
    val name: String,
    /** 接口地址（爬虫源为站点 key，如 幼儿启蒙） */
    val url: String,
    val note: String = "",
    val enabled: Boolean = true,
    val sortOrder: Int = 0,
    /** 站点类型：0=CMS 接口，3=TVBox 爬虫源 */
    val siteType: Int = 0,
    /** 爬虫 jar 地址（含分号分隔备用地址/md5 校验段） */
    val jarUrl: String = "",
    /** 爬虫类 key（如 csp_XXX） */
    val spiderKey: String = "",
    /** 爬虫站点 ext 配置（JSON 字符串） */
    val ext: String = "",
    /** 锁定源（防止误删/订阅覆盖） */
    val locked: Boolean = false,
    /** 该源是否记录播放历史 */
    val playRecord: Boolean = true,
    /** 该源是否参与聚合搜索 */
    val searchable: Boolean = true,
    /** 该源请求使用的 User-Agent */
    val userAgent: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)

/** 解析源实体（前缀拼接式） */
data class ParseSourceEntity(
    val id: Long = 0,
    val name: String,
    val url: String,
    val note: String = "",
    val enabled: Boolean = true,
    val sortOrder: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
)

/** 直播源实体 */
data class TvSourceEntity(
    val id: Long = 0,
    val name: String,
    val url: String,
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)

/** 电视频道（由 M3U/TXT 播放列表解析得到，同名频道合并为一条、保留全部线路） */
data class TvChannel(
    /** 频道名称，如 "CCTV-1 综合" */
    val name: String,
    /** 播放地址列表（同名频道多线路，第一个为默认线路） */
    val urls: List<String>,
    /** 分组名称，如 "央视"、"卫视" */
    val group: String,
    /** 台标地址，可为空 */
    val logo: String = "",
) {
    /** 当前默认播放地址 */
    val url: String get() = urls.first()

    /** 收藏键（名称+首线路地址，跨直播源稳定去重） */
    val favoriteKey: String get() = "$name|$url"
}

/** 播放历史（复合主键 vodId+sourceUrl） */
data class PlayHistoryEntity(
    val vodId: Int,
    val sourceUrl: String = "",
    val title: String,
    val vodPic: String = "",
    val lastEpisodeName: String = "",
    val lastPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val watchedAt: Long = System.currentTimeMillis(),
)

/** 收藏（复合主键 vodId+sourceUrl） */
data class FavoriteEntity(
    val vodId: Int,
    val sourceUrl: String = "",
    val title: String,
    val vodPic: String = "",
    val addedAt: Long = System.currentTimeMillis(),
)

/** 分类 */
data class Category(
    @SerializedName("type_id") val typeId: Int = 0,
    @SerializedName("type_name") val typeName: String = "",
)

/** MacCMS 通用响应包装 */
data class ApiResponse<T>(
    @SerializedName("code") val code: Int = 0,
    @SerializedName("msg") val msg: String = "",
    @SerializedName("page") val page: Int = 1,
    @SerializedName("pagecount") val pageCount: Int = 0,
    @SerializedName("limit") val limit: Int = 20,
    @SerializedName("total") val total: Int = 0,
    @SerializedName("list") val list: List<T> = emptyList(),
    @SerializedName("class") val classList: List<Category> = emptyList(),
)

/**
 * 视频数据模型，同时兼容列表接口与详情接口返回字段。
 * 爬虫源的 vodId 为 SpiderIdRegistry 稳定哈希出的 Int。
 */
data class Vod(
    @SerializedName("vod_id") val vodId: Int = 0,
    @SerializedName("type_id") val typeId: Int = 0,
    @SerializedName("vod_name") val vodName: String = "",
    @SerializedName("vod_tag") val vodTag: String = "",
    @SerializedName("vod_class") val vodClass: String = "",
    @SerializedName("vod_pic") val vodPic: String = "",
    @SerializedName("vod_pic_thumb") val vodPicThumb: String = "",
    @SerializedName("vod_pic_slide") val vodPicSlide: String = "",
    @SerializedName("vod_actor") val vodActor: String = "",
    @SerializedName("vod_director") val vodDirector: String = "",
    @SerializedName("vod_remarks") val vodRemarks: String = "",
    @SerializedName("vod_area") val vodArea: String = "",
    @SerializedName("vod_lang") val vodLang: String = "",
    @SerializedName("vod_year") val vodYear: String = "",
    @SerializedName("vod_score") val vodScore: String = "",
    @SerializedName("vod_content") val vodContent: String = "",
    @SerializedName("vod_play_from") val vodPlayFrom: String = "",
    @SerializedName("vod_play_url") val vodPlayUrl: String = "",
    @SerializedName("type_name") val typeName: String = "",
) {
    /**
     * 解析播放源列表。
     * 格式: vod_play_from = "source1$$$source2"
     *       vod_play_url  = "ep1$url1#ep2$url2$$$ep1$url1"
     */
    fun parsePlaySources(): List<PlaySource> {
        val fromList = vodPlayFrom.split(Constants.SEPARATOR_SOURCE).filter { it.isNotBlank() }
        val urlList = vodPlayUrl.split(Constants.SEPARATOR_SOURCE)
        return fromList.mapIndexed { index, sourceName ->
            val episodeStr = urlList.getOrNull(index).orEmpty()
            val episodes = episodeStr.split(Constants.SEPARATOR_EPISODE)
                .filter { it.contains(Constants.SEPARATOR_NAME_URL) }
                .mapNotNull { ep ->
                    val parts = ep.split(Constants.SEPARATOR_NAME_URL, limit = 2)
                    if (parts.size == 2) PlayEpisode(parts[0].trim(), parts[1].trim()) else null
                }
            PlaySource(sourceName.trim(), episodes)
        }.filter { it.episodes.isNotEmpty() }
    }

    /** 优先取非空封面图 */
    val safePic: String
        get() = when {
            vodPic.isNotBlank() -> vodPic
            vodPicThumb.isNotBlank() -> vodPicThumb
            vodPicSlide.isNotBlank() -> vodPicSlide
            else -> ""
        }

    /** 用详情数据补全列表项缺失的封面等字段 */
    fun mergeWithDetail(detail: Vod): Vod = copy(
        vodPic = detail.vodPic,
        vodPicThumb = detail.vodPicThumb,
        vodPicSlide = detail.vodPicSlide,
        vodScore = detail.vodScore,
        vodRemarks = detail.vodRemarks.ifBlank { vodRemarks },
    )
}

/** 播放源（线路） */
data class PlaySource(
    val name: String,
    val episodes: List<PlayEpisode>,
)

/** 单集 */
data class PlayEpisode(
    val name: String,
    val url: String,
)

/** 聚合搜索结果分组（按 API 源） */
data class SearchGroup(
    val sourceId: Long,
    val sourceName: String,
    val sourceUrl: String = "",
    val results: List<Vod> = emptyList(),
    val error: String? = null,
    val page: Int = 1,
    val hasMore: Boolean = true,
)
