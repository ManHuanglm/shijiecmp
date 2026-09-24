package com.shiping.desktop.ui

import com.shiping.desktop.data.model.PlaySource
import com.shiping.desktop.data.spider.BiliQualityOption

/** 线路显示名（TVBox 线路名可能为空或冗长，取前 10 字） */
internal fun sourceName(source: PlaySource, index: Int): String =
    source.name.ifBlank { "线路${index + 1}" }.take(10)

/** 页面路由 */
sealed class Screen {
    data object Home : Screen()
    data object Search : Screen()
    data object Tv : Screen()
    data object Favorites : Screen()
    data object History : Screen()
    data object Settings : Screen()

    /** 「我的」合并页（Android 底部 Tab：收藏+历史页签；桌面不导航到该页） */
    data object Mine : Screen()

    /** 下载管理（Android 专属：任务队列与本地导出文件；桌面不导航到该页） */
    data object Downloads : Screen()

    /** 详情页：vodId + 源地址（跨源打开用），title/pic 为列表页已知信息（详情未回前先显示） */
    data class Detail(
        val vodId: Int,
        val sourceUrl: String,
        val title: String,
        val pic: String,
    ) : Screen()
}

/**
 * 一次播放会话：播放器层据此拉流、写历史、切清晰度。
 * vodId=0 表示无历史记录场景（直播/推送直链）。
 */
data class PlaybackSession(
    val title: String,
    val episodeName: String,
    val vodId: Int = 0,
    val sourceUrl: String = "",
    val vodPic: String = "",
    /** 内核可直接播放的地址（已按需包装 /hdr 代理） */
    val playUrl: String,
    /** 原始播放地址（展示/诊断） */
    val rawUrl: String,
    val headers: Map<String, String> = emptyMap(),
    val startMs: Long = 0L,
    /** B 站清晰度切换上下文（非 B 站源为 0/空） */
    val biliCid: Long = 0L,
    val biliAid: Long = 0L,
    val qualityOptions: List<BiliQualityOption> = emptyList(),
    val currentQn: Int = 0,
    /** 点播附加信息：选集按钮列表（空则隐藏选集面板） */
    val episodes: List<String> = emptyList(),
    /** 当前集序号（episodes 内索引，-1 表示无上下文） */
    val currentEpisodeIndex: Int = -1,
    /** 点播线路列表（sourceIndex 对齐 DetailViewModel.selectedSource） */
    val playSources: List<PlaySource> = emptyList(),
    /** 当前线路序号（playSources 内索引） */
    val currentSourceIndex: Int = 0,
    /** 页内播放（详情页/直播页内嵌承载）；false 时由独立 PlayerFrame 弹窗承载（局域网推送） */
    val inPage: Boolean = true,
    /** 起播序号：重播同集/重复切清晰度时递增，驱动会话流转（规避 StateFlow 值相等去重） */
    val seq: Long = 0,
)
