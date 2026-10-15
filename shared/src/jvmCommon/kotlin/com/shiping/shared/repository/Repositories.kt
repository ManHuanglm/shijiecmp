package com.shiping.shared.repository

import com.shiping.desktop.data.model.ApiResponse
import com.shiping.desktop.data.model.ApiSourceEntity
import com.shiping.desktop.data.model.FavoriteEntity
import com.shiping.desktop.data.model.ParseSourceEntity
import com.shiping.desktop.data.model.PlayHistoryEntity
import com.shiping.desktop.data.model.TvChannel
import com.shiping.desktop.data.model.TvSourceEntity
import com.shiping.desktop.data.model.Vod
import com.shiping.desktop.data.repository.SearchGroupData
import com.shiping.desktop.data.spider.SpiderPlayInfo
import kotlinx.coroutines.flow.StateFlow

/**
 * 仓库统一接口（jvmCommon，供 Android/Desktop 两端实现、CMP UI 消费）：
 * Android 端（M4）由 Room + DataStore 实现并做 实体↔模型 映射；
 * 桌面端由 SQLite JDBC（DesktopDb）实现。
 * 注：因模型层依赖 gson（@SerializedName，payload 生态语义），接口暂居 jvmCommon
 * 而非 commonMain；Kotlin 2.x 升级（Room KMP）时再评估上移。
 */

/** API 源管理：增删改查、爬虫源整体替换、当前源选择 */
interface ApiSourceRepository {

    /** 全部源（响应式） */
    val sources: StateFlow<List<ApiSourceEntity>>

    suspend fun refresh()

    suspend fun getById(id: Long): ApiSourceEntity?

    suspend fun insert(source: ApiSourceEntity): Long

    suspend fun update(source: ApiSourceEntity)

    /** 批量设置源锁定状态 */
    suspend fun setLocked(ids: List<Long>, value: Boolean)

    /** 批量设置源播放记录开关 */
    suspend fun setPlayRecord(ids: List<Long>, value: Boolean)

    /** 批量设置源是否参与搜索 */
    suspend fun setSearchable(ids: List<Long>, value: Boolean)

    /** 上移源一位（整体重排 sortOrder，保持相对顺序） */
    suspend fun moveUp(id: Long)

    /** 检测源是否可用（仅 CMS 接口源），返回可用数量 */
    suspend fun checkUsable(ids: List<Long>): Int

    /** 批量导入 CMS 源（每行一个，支持「名称,地址」「名称$地址」「仅地址」），返回导入条数 */
    suspend fun batchInsertCms(text: String, putFirst: Boolean, locked: Boolean, playRecord: Boolean): Int

    suspend fun deleteByIds(ids: List<Long>)

    /** 按站点类型删除（0/1=CMS 接口源，3=爬虫源），爬虫源导入整体替换用 */
    suspend fun deleteBySiteType(siteType: Int)

    suspend fun count(): Int

    /** 首次启动时插入默认 API 源（如果为空） */
    suspend fun initDefaultSource()

    /** 当前选中的 API 源 ID（null 表示未指定，取第一个启用源） */
    val currentApiId: Long?

    fun setCurrentApiId(id: Long)

    /** 当前生效的 API 源实体 */
    fun getCurrentApiSource(): ApiSourceEntity?

    /** 当前 API 地址 */
    fun currentApiUrlOnce(): String

    /** 当前 API 源名称 */
    fun currentApiName(): String
}

/** 解析源仓库：增删改查 + 当前默认解析地址 */
interface ParseSourceRepository {

    val sources: StateFlow<List<ParseSourceEntity>>

    suspend fun refresh()

    suspend fun insert(source: ParseSourceEntity): Long

    suspend fun deleteByIds(ids: List<Long>)

    /** 当前生效的解析前缀：优先用户默认，其次第一个启用源，无则 app 默认值 */
    fun currentParserUrl(): String?

    fun setDefaultParserUrl(url: String)
}

/** 直播源仓库：直播源增删改查 + M3U/TXT 播放列表拉取解析 */
interface TvRepository {

    val sources: StateFlow<List<TvSourceEntity>>

    suspend fun refresh()

    /** 更新直播源（名称/地址/备注） */
    suspend fun update(source: TvSourceEntity)

    /** 批量添加直播源（每行一个，仅url / 名称,url / 名称,url,备注），按 URL 去重，返回实际新增条数 */
    suspend fun batchInsert(text: String): Int

    /** 从本地 txt 文件导入直播源，返回导入条数（读取失败返回 0） */
    suspend fun importFile(path: String): Int

    /**
     * 批量去重插入直播源：批内重复与库中已有 URL 均跳过（先到者优先），返回实际新增条数。
     * TVBox 配置导入直播源走此入口，重复导入配置不产生重复记录。
     */
    suspend fun insertUnique(sources: List<TvSourceEntity>): Int

    suspend fun insert(source: TvSourceEntity): Long

    suspend fun deleteById(id: Long)

    suspend fun count(): Int

    /** 首次启动插入默认直播源 */
    suspend fun initDefaultSource()

    /** 当前选中的直播源 ID（持久化，未设置返回 null） */
    fun currentTvId(): Long?

    /** 拉取并解析播放列表，返回按分组归类的频道（支持 M3U 与 TVBox TXT） */
    suspend fun loadChannels(source: TvSourceEntity): List<TvChannel>
}

/** 播放历史仓库（复合主键 vodId+sourceUrl，跨源隔离） */
interface PlayHistoryRepository {

    val history: StateFlow<List<PlayHistoryEntity>>

    suspend fun refresh()

    /** 保存/更新进度（开播即写一条，之后定期刷新进度） */
    suspend fun save(entity: PlayHistoryEntity)

    /** 查询某片的续播进度（无记录返回 null） */
    suspend fun getByVod(vodId: Int, sourceUrl: String): PlayHistoryEntity?

    suspend fun delete(vodId: Int, sourceUrl: String)

    suspend fun clear()
}

/** 收藏仓库（复合主键 vodId+sourceUrl，与播放历史键语义一致） */
interface FavoriteRepository {

    val favorites: StateFlow<List<FavoriteEntity>>

    suspend fun refresh()

    suspend fun add(entity: FavoriteEntity)

    suspend fun remove(vodId: Int, sourceUrl: String)

    suspend fun clear()

    suspend fun isFavorite(vodId: Int, sourceUrl: String): Boolean
}

/** 视频数据仓库：CMS 接口源直连 + TVBox 爬虫源（type 3）走 SpiderEngine */
interface VodRepository {

    /** 获取首页视频列表（附带分类）；部分 CMS 源列表不返回封面，需批量详情补全 */
    suspend fun getHome(page: Int = 1, typeId: Int = 0, url: String? = null): ApiResponse<Vod>

    /** 搜索 */
    suspend fun search(keyword: String, page: Int = 1, url: String? = null): ApiResponse<Vod>

    /** 获取视频详情 */
    suspend fun getDetail(vodId: Int, url: String? = null): Vod?

    /** 批量获取视频详情（补全列表封面），返回 vodId -> Vod 映射 */
    suspend fun getDetails(ids: List<Int>, url: String? = null): Map<Int, Vod>

    /** 在单个 API 源上搜索（含详情补全），失败时返回带 error 的分组，不抛异常 */
    suspend fun searchOneSource(source: ApiSourceEntity, keyword: String, page: Int = 1): SearchGroupData

    /** 解析播放地址：爬虫源调 playerContent 得到直链与请求头；CMS 源返回 null */
    suspend fun resolvePlayUrl(sourceUrl: String, rawUrl: String): SpiderPlayInfo?

    /**
     * 爬虫 action 协议：网盘源「登入自己网盘 / 清除xxCookie」等列表项，
     * 按生态约定交爬虫 action 处理，返回提示文本（可空）。
     */
    suspend fun spiderAction(sourceUrl: String, vodId: Int, action: String): String = ""

    /**
     * 网盘配置推送：把 Cookie/Token 经本地代理生态路由写入网盘爬虫配置，
     * 返回响应文本；失败（含未找到处理器）以异常表达。
     */
    suspend fun pushPanCookie(drive: String, value: String): Result<String> =
        Result.failure(IllegalStateException("当前端不支持网盘配置推送"))
}
