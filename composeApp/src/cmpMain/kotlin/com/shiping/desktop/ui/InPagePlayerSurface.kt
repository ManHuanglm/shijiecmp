package com.shiping.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.shiping.desktop.data.model.TvChannel
import com.shiping.desktop.data.spider.BiliQualityOption
import com.shiping.desktop.player.IntroMark
import com.shiping.desktop.ui.player.PagePlayerModeState
import com.shiping.shared.graph.AppGraph
import com.shiping.shared.player.PlayerKernel

/**
 * 页内播放面抽象（cmpMain 跨端）：Detail/Tv 播放编排面向本文件 API，
 * 平台 actual 承载渲染面与控制层（桌面=PlayerOverlay Swing 组合，Android=PlayerView）。
 */

/** 页内全屏归属：窗口动作与全屏状态的读写目标（详情页/直播页各自独立状态） */
enum class PagePlayerScope { DETAIL, TV }

/**
 * 页内播放控制层动作集（cmpMain 播放态 UI 经此调起平台控制层）：
 * 桌面=PlayerOverlay 对应面板映射；Android 简版为空实现。
 * setBackground 继承 [InPageOverlayHost]（切页命令式隐藏视频面与悬浮层）。
 */
interface InPagePlayerActions : InPageOverlayHost {

    /** 选集面板（页内重量级弹出，防被视频面遮挡） */
    fun showEpisodeSheet()

    /** 线路/源列表面板 */
    fun showSourceSheet()

    /** 定时关闭面板 */
    fun showTimerSheet()

    /** 多线程配置面板 */
    fun showMultiThreadSheet()

    /** 分享面板 */
    fun showShareSheet()
}

/** 直播频道侧栏规格（桌面=右缘 Swing 滑出面板；Android 简版暂忽略） */
data class TvChannelPanelSpec(
    /** 初始 Tab：0=收藏 1=更新时间 2+=分组 */
    val initialTabIndex: Int,
    val groupNames: List<String>,
    val currentChannelKey: String,
    /** 按 Tab 取频道列表（实时读取，面板内切 Tab 时调用） */
    val channelsProvider: (Int) -> List<TvChannel>,
    val onSelectTab: (Int) -> Unit,
    val onPlay: (TvChannel, Int) -> Unit,
)

/**
 * 页内播放区配置（跨端通用；桌面映射 PlayerOverlayConfig，Android 简版消费 title/back 等）。
 * 会话展示与回调随重组刷新，控制层内部增量更新不重建视频面。
 */
data class InPagePlayerConfig(
    /** 顶部栏标题（片名 + 集数） */
    val title: String = "",
    /** 返回动作（页内=退出播放回浏览态），空隐藏返回 */
    val onBack: (() -> Unit)? = null,
    // region 选集/线路（详情页）
    val episodes: List<String> = emptyList(),
    val currentEpisodeIndex: Int = -1,
    val onEpisodeSelected: (Int) -> Unit = {},
    val sources: List<String> = emptyList(),
    val currentSourceIndex: Int = -1,
    val onSourceSelected: (Int) -> Unit = {},
    /** 源列表副文案（与 sources 等长，空列表隐藏副行） */
    val sourceSubtexts: List<String> = emptyList(),
    /** 源列表右侧更新信息（与 sources 等长，如「更新至：第 159 集」） */
    val sourceUpdateTexts: List<String> = emptyList(),
    /** 其他源同名影片（源列表弹层追加区，空隐藏；分批搜索动态追加） */
    val crossSources: List<CrossSourceMatch> = emptyList(),
    /** 其他源分批加载中（弹层尾部指示） */
    val crossLoading: Boolean = false,
    /** 其他源是否还有未搜索的源（触底加载开关） */
    val crossHasMore: Boolean = false,
    /** 其他源条目点击（跳对应源详情播放），空隐藏追加区 */
    val onCrossSourceSelected: ((CrossSourceMatch) -> Unit)? = null,
    /** 其他源滚动触底加载下一批源 */
    val onCrossSourceLoadMore: (() -> Unit)? = null,
    // endregion
    // region 清晰度（B 站协议源）
    val qualities: List<BiliQualityOption> = emptyList(),
    val currentQn: Int = 0,
    val onQualitySelected: (Int) -> Unit = {},
    // endregion
    /** 上/下一集（设置开关关闭或越界时为 null 隐藏按钮） */
    val onPrevEpisode: (() -> Unit)? = null,
    val onNextEpisode: (() -> Unit)? = null,
    /** 分享目标链接（空时分享面板各动作提示不可用） */
    val shareUrl: String? = null,
    /** 当前内核 ID（顶部栏切换展示，跨端字符串） */
    val kernelId: String = KernelIds.DEFAULT,
    val onKernelSelected: (String) -> Unit = {},
    /** 片头记录目标（空隐藏「记录片头」按钮） */
    val introMark: IntroMark.Mark? = null,
    /** 下载当前集回调（空时点击提示不支持） */
    val onDownloadEpisode: ((Int) -> Unit)? = null,
    /** 批量下载选中集回调（选集面板勾选模式「下载全」用；空时面板隐藏下载所选入口） */
    val onDownloadEpisodes: ((List<Int>) -> Unit)? = null,
    /** 下载全部集回调（空时两端选集面板隐藏「下载全部」入口） */
    val onDownloadAllEpisodes: (() -> Unit)? = null,
    /** 直播频道侧栏规格（非空时 ▦ 按钮转「频道列表」语义） */
    val channelPanel: TvChannelPanelSpec? = null,
    /** ⚙ 播放设置入口（桌面右缘滑出设置面板；Android 暂无） */
    val showPlayerSettings: Boolean = false,
    /** 控制层全屏切换（Android 控制层 ⛶ 按钮；空时默认切点播 detailFullscreen，直播会话需传 tvFullscreen 切换） */
    val onToggleFullscreen: (() -> Unit)? = null,
)

/** 创建页内播放内核（桌面=按偏好创建 mpv/vlc/MDK；Android=media3） */
expect fun createInPageKernel(graph: AppGraph): PlayerKernel

/**
 * 等待平台渲染面就绪（桌面=AWT displayable 轮询，内核 wid 渲染要求；
 * Android=PlayerView 由组合承载，立即就绪）。超时返回当前状态，调用方照常尝试起播。
 */
expect suspend fun awaitSurfaceReady(kernel: PlayerKernel, timeoutMs: Long, pollIntervalMs: Long): Boolean

/** 释放页内播放内核（桌面=后台线程拆 native 防 EDT 卡顿；Android=内核内部主线程队列） */
expect fun releasePageKernel(kernel: PlayerKernel)

/** 手机端平台标记（Android=true，桌面=false）：手机端专属播放行为（固定比例铺满宽度、播完自动连播）的开关 */
expect val isPhonePlatform: Boolean

/**
 * 页内播放区：渲染面 + 控制层（expect，平台 actual 承载）。
 * actual 内部完成控制层注册（[AppViewModel.registerInPageOverlay]）与配置刷新；
 * 动作集经 [onActionsReady] 一次性回传。
 * [modeState] 为页面持有的统一模式状态机（bind/unbind 由页面负责，
 * 平台 actual 负责同步系统画中画激活态），空时控制层本地派生模式。
 */
@Composable
expect fun InPagePlayerSurface(
    kernel: PlayerKernel,
    app: AppViewModel,
    scope: PagePlayerScope,
    config: InPagePlayerConfig,
    modifier: Modifier = Modifier,
    onActionsReady: (InPagePlayerActions) -> Unit = {},
    modeState: PagePlayerModeState? = null,
)

/** 起播前等待视频面挂载的上限（毫秒） */
internal const val MOUNT_WAIT_TIMEOUT_MS = 5000L

/** 视频面挂载轮询间隔（毫秒） */
internal const val MOUNT_POLL_INTERVAL_MS = 50L
