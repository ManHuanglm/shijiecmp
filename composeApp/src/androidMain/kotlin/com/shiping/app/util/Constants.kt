package com.shiping.app.util

import androidx.compose.ui.unit.dp

/**
 * 应用全局常量
 */
object Constants {

    /** 默认测试 API 地址 */
    const val DEFAULT_API_URL = "http://hongniuzy2.com/api.php/provide/vod/from/hnm3u8"
    const val DEFAULT_API_NAME = "鸿牛资源"

    /** API 请求伪装浏览器 UA（部分资源站校验 UA / 按 Referer 防盗链） */
    const val API_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/114.0.0.0 Mobile Safari/537.36"

    /** 默认解析地址 */
    const val DEFAULT_PARSER_URL = "https://yparse.ik9.cc/index.php?url="
    const val DEFAULT_PARSER_NAME = "yparse解析"

    /** 默认电视直播源（M3U 播放列表） */
    const val DEFAULT_TV_SOURCE_URL = "https://cdn.jsdmirror.com/gh/Guovin/iptv-api@gd/output/result.m3u"
    const val DEFAULT_TV_SOURCE_NAME = "默认直播源"

    /** 播放源/剧集分隔符 */
    const val SEPARATOR_SOURCE = "\$\$\$"
    const val SEPARATOR_EPISODE = "#"
    const val SEPARATOR_NAME_URL = "$"

    /** 批量获取详情每批数量 */
    const val DETAIL_BATCH_SIZE = 20

    /** TVBox 站点类型：3=爬虫源（spider），0/1 为 CMS 接口 */
    const val SITE_TYPE_SPIDER = 3

    /** 搜索防抖毫秒 */
    const val SEARCH_DEBOUNCE_MS = 300L

    /** 搜索建议最大数量 */
    const val MAX_SEARCH_SUGGESTIONS = 8

    /** 列表触底加载阈值 */
    const val LOAD_MORE_THRESHOLD = 4

    /** 播放器默认缓冲时长（毫秒） */
    const val DEFAULT_BUFFER_MS = 50_000

    /** 预载参数范围 */
    const val PRELOAD_THREADS_MIN = 1
    const val PRELOAD_THREADS_MAX = 10
    const val PRELOAD_CAPACITY_MIN_MB = 128
    const val PRELOAD_CAPACITY_MAX_MB = 4096
    const val PRELOAD_TIME_MIN_S = 12
    const val PRELOAD_TIME_MAX_S = 120

    /** 缓冲倍数范围 */
    const val BUFFER_MULTIPLIER_MIN = 1
    const val BUFFER_MULTIPLIER_MAX = 10

    /** 直链后缀 */
    val DIRECT_STREAM_SUFFIXES = listOf(".m3u8", ".mp4", ".flv", ".ts")

    // region B 站直链

    /** B 站 playurl 接口（清晰度切换时用 cid+aid+qn 换取对应码率直链） */
    const val BILI_PLAYURL_API = "https://api.bilibili.com/x/player/playurl"

    /** B 站直链防盗链 Referer */
    const val BILI_REFERER = "https://www.bilibili.com/"

    /** B 站清晰度 qn -> 名称兜底映射（协议缺画质映射或数量不符时使用） */
    val BILI_QN_LABELS = mapOf(
        16 to "360P",
        32 to "480P",
        64 to "720P",
        80 to "1080P",
        112 to "1080P+",
        116 to "1080P60",
        120 to "4K",
        125 to "HDR",
        126 to "4K60",
        127 to "杜比视界",
    )

    // endregion

    // region 播放器

    /** 倍速档位 */
    val PLAYBACK_SPEEDS = listOf(0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 3.0f, 4.0f)

    /** 详情页内嵌播放器精简倍速档位 */
    val INLINE_PLAYER_SPEEDS = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 3.0f)

    /** 内嵌播放器画面比例自适应范围（过窄/过宽时截断） */
    const val INLINE_PLAYER_MIN_ASPECT = 0.6f
    const val INLINE_PLAYER_MAX_ASPECT = 2.4f

    /** 默认倍速 */
    const val DEFAULT_PLAYBACK_SPEED = 1.0f

    /** 长按快进倍速 */
    const val LONG_PRESS_SPEED = 3.0f

    /** 长按倍速可选档位 */
    val LONG_PRESS_SPEED_OPTIONS = listOf(2.0f, 2.5f, 3.0f, 4.0f, 5.0f)

    /** 手势快进/快退单步毫秒（每像素） */
    const val GESTURE_SEEK_MS_PER_PX = 1000L

    /** 手势快进最大偏移毫秒 */
    const val GESTURE_SEEK_MAX_MS = 600_000L

    /** 片头自动跳过时长（毫秒） */
    const val INTRO_SKIP_MS = 90_000L

    /** 片尾自动跳过时长（毫秒，距离结束前） */
    const val OUTRO_SKIP_MS = 120_000L

    /** 睡眠定时可选分钟 */
    val SLEEP_TIMER_MINUTES = listOf(10, 15, 30, 45, 60, 90)

    /** 控制栏自动隐藏延迟毫秒 */
    const val CONTROL_AUTO_HIDE_MS = 3_000L

    /** 锁屏键自动隐藏延迟毫秒 */
    const val LOCK_AUTO_HIDE_MS = 5_000L

    /** 退出全屏后横屏自动全屏开关的延迟复位时长，避免返回详情页时因仍处横屏而再次进入 */
    const val AUTO_FULLSCREEN_REARM_DELAY_MS = 500L

    /** 双击播放暂停触发间隔毫秒 */
    const val DOUBLE_TAP_INTERVAL_MS = 300L

    /** 逐帧步进毫秒 */
    const val FRAME_STEP_MS = 40L

    /** 进度记忆阈值（小于该进度不记录，避免重头播放） */
    const val PLAYBACK_MEMORY_MIN_MS = 5_000L

    /** 续播剩余阈值（剩余时长小于该值则从头播放） */
    const val RESUME_THRESHOLD_MS = 10_000L

    /** 双击返回退出间隔（毫秒） */
    const val BACK_PRESS_EXIT_INTERVAL_MS = 2000L

    // endregion

    // region 页面转场

    /** 页面切换翻页动画时长（毫秒） */
    const val NAV_ANIM_DURATION_MS = 350

    // endregion

    // region 广告过滤

    /** m3u8 广告切片 URL 关键词（命中即为广告） */
    val AD_URL_KEYWORDS = listOf(
        "ad.", "ads.", "advert", "promo", "preroll",
        "/ad/", "/ads/", "/adverts", "/promoid",
        "adcdn", "advideo", "admaster", "adtech",
        "doubleclick", "googlesyndication", "analytics",
    )

    /** 分块启发式：块平均切片时长与主内容块比值低于该值视为广告块 */
    const val AD_DURATION_RATIO_MIN = 0.6f

    /** 分块启发式：块平均切片时长与主内容块比值高于该值视为广告块 */
    const val AD_DURATION_RATIO_MAX = 1.8f

    /** 分块启发式：候选广告块的最大总时长（秒），超过则认定为正片不删 */
    const val AD_BLOCK_MAX_TOTAL_S = 180

    // endregion

    // region 缓冲线路

    /** 缓冲线路倍数默认值（同时连接数，1=单连接不加速） */
    const val BUFFER_LINE_MULTIPLIER_DEFAULT = 1

    /** 缓冲线路倍数最大值（同时连接数） */
    const val BUFFER_LINE_MULTIPLIER_MAX = 10

    /** 触发多连接并行的最小切片字节数，小于该值单连接直读 */
    const val BUFFER_MIN_PARALLEL_BYTES = 512 * 1024L

    // endregion

    // region 播放渲染

    /** 渲染方式：SurfaceView（性能优先，默认） */
    const val RENDER_MODE_SURFACE_VIEW = 0

    /** 渲染方式：TextureView（兼容性优先） */
    const val RENDER_MODE_TEXTURE_VIEW = 1

    // endregion

    // region 选集

    /** 选集面板每页集数（分段） */
    const val EPISODE_PAGE_SIZE = 40

    /** 选集面板表格列数（5 列，配合懒加载网格） */
    const val EPISODE_GRID_COLUMNS = 5

    /** 选集面板剧集网格高度（懒加载滚动区） */
    val EPISODE_DIALOG_GRID_HEIGHT = 340.dp

    // endregion

    // region 下载缓存

    /** 下载对话框剧集网格高度（懒加载滚动区） */
    val DOWNLOAD_DIALOG_GRID_HEIGHT = 320.dp

    /** 下载进度轮询间隔毫秒 */
    const val DOWNLOAD_PROGRESS_POLL_INTERVAL_MS = 1_000L

    /** 下载限速档位（KB/s），0 表示不限速 */
    val DOWNLOAD_SPEED_OPTIONS = listOf(0, 512, 1024, 2048, 5120)

    /** 下载限速档位显示文本 */
    val DOWNLOAD_SPEED_LABELS = listOf("不限速", "512 KB/s", "1 MB/s", "2 MB/s", "5 MB/s")

    /** 批量下载并发数范围 */
    const val DOWNLOAD_PARALLEL_MIN = 1
    const val DOWNLOAD_PARALLEL_MAX = 5

    // endregion

    /** 热搜词（搜索页标签展示 + 字母联想候选池） */
    val RECOMMEND_SEARCHES = listOf(
        "开心锤锤", "抓特务", "小猪佩奇全集", "新猫和老鼠 第四季",
        "疯狂美人计", "汪汪队立大功全集", "醒来", "早春晴朗",
        "镖人：风起大漠", "战狼2", "熊出没之探险日记", "戴拿奥特曼",
        "熊出没全集", "迷你特工队X", "寒山令", "坦克传奇",
        "新闻联播", "小猪佩奇 第十季", "萌鸡小队全集", "重器",
        "聪明的顺溜 第二季", "披荆斩棘2026", "生逢其时",
        "汪汪队立大功 第十季", "哆啦A梦 第五季", "猎枭风暴",
        "宝宝巴士儿歌", "凡人修仙传", "斗罗大陆", "斗破苍穹",
        "完美世界", "海贼王", "火影忍者", "庆余年",
        "狂飙", "三体", "流浪地球", "长津湖", "满江红",
    )

    /** 输入联想标签最大数量 */
    const val MAX_SUGGESTION_TAGS = 20

    /** 联想候选池拉取的首页页数 */
    const val SUGGESTION_POOL_PAGES = 2
}
