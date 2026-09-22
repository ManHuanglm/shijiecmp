package com.shiping.desktop.util

/** 桌面版全局常量（对齐 app 模块 Constants 的桌面子集） */
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

    /** 直链后缀 */
    val DIRECT_STREAM_SUFFIXES = listOf(".m3u8", ".mp4", ".flv", ".ts")

    // region B 站直链

    /** B 站 playurl 接口（清晰度切换时用 cid+aid+qn 换取对应码率直链） */
    const val BILI_PLAYURL_API = "https://api.bilibili.com/x/player/playurl"

    /** B 站直链防盗链 Referer */
    const val BILI_REFERER = "https://www.bilibili.com/"

    /** B 站清晰度 qn -> 名称兜底映射 */
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

    /** 倍速档位 */
    val PLAYBACK_SPEEDS = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 3.0f)

    /** 快进快退步长档位（秒，0 表示不展示按钮） */
    val SEEK_STEPS = listOf(0, 5, 10, 15, 30, 60)

    /** 长按倍速档位 */
    val LONG_PRESS_SPEEDS = listOf(2.0f, 3.0f)

    /** 字幕颜色循环档位（点击切换，#RRGGBB） */
    val SUB_COLORS = listOf(
        "#FFFFFF", "#FF0000", "#FFA500", "#FFD700", "#00FF00", "#00FFFF", "#0000FF", "#FF00FF",
    )

    /** 字幕背景颜色循环档位（点击切换，#RRGGBB） */
    val SUB_BACK_COLORS = listOf(
        "#000000", "#808080", "#FFFFFF", "#0000FF", "#008000", "#8B0000",
    )

    /** 应用版本号（与打包 packageVersion 保持一致） */
    const val APP_VERSION = "1.0.0"

    /** 进度记忆阈值（小于该进度不记录，避免重头播放） */
    const val PLAYBACK_MEMORY_MIN_MS = 5_000L

    /** 续播剩余阈值（剩余时长小于该值则从头播放） */
    const val RESUME_THRESHOLD_MS = 10_000L

    /** 历史进度保存间隔毫秒 */
    const val HISTORY_SAVE_INTERVAL_MS = 5_000L

    /** 内置订阅源预设（名称 to 配置地址），订阅页一键导入 */
    val SUBSCRIPTION_PRESETS: List<Pair<String, String>> = listOf(
        "饭太硬" to "http://www.饭太硬.net/tv",
        "饭太硬·备用1" to "http://www.饭太硬.art/tv",
        "饭太硬·备用2" to "http://fty.xxooo.cf/tv",
        "饭太硬·备用3" to "http://fty.888484.xyz/tv",
        "饭太硬·备用4" to "http://fty.333232.xyz/tv",
        "小米" to "https://gh-proxy.org/raw.githubusercontent.com/ggrrttyyiii/CatVodSpider/refs/heads/main/json/demo.json",
        "肥猫" to "http://肥猫.net/tv",
        "王二小" to "https://9280.kstore.vip/newwex.json",
        "嗷呜" to "https://9763.kstore.vip/aowu.json",
        "摸鱼儿" to "http://我不是.摸鱼儿.top",
        "集多" to "http://rihou.cc:88/demo.php",
        "潇洒" to "https://9877.kstore.space/one.json",
        "欧歌" to "https://xn--jory77o.v.nxog.top/m",
        "小虎斑" to "http://hb.小虎斑.site:25252/仅供测试",
        "南风" to "https://gh-proxy.com/raw.githubusercontent.com/yoursmile66/TVBox/refs/heads/main/XC.json",
        "少儿频道" to "https://gh-proxy.com/raw.githubusercontent.com/lubin776/0/refs/heads/main/tvbox/b2.json",
        "戏曲音乐" to "https://z.qiqiv.cn/666",
        "匿名单仓" to "https://12586.kstore.space/123.txt",
        "游魂单仓" to "https://www.iyouhun.com/tv/dc",
        "无名单仓" to "https://github.catvod.com/raw.githubusercontent.com/tushen6/Tomorrow/master/lmw.json",
        "欧歌单仓" to "https://双龙.v.nxog.top/nxog/oua.php",
        "匿名多仓" to "https://12586.kstore.space/123.json",
    )
}
