package com.shiping.desktop.ui

import com.shiping.desktop.data.model.PlayEpisode
import com.shiping.desktop.data.model.PlaySource
import com.shiping.desktop.data.model.Vod
import com.shiping.desktop.data.spider.BiliQualityOption
import com.shiping.shared.spider.SpiderProxyServer
import com.shiping.desktop.di.AppContainer
import com.shiping.desktop.player.PlaybackUrlResolver
import com.shiping.desktop.player.PlayerControllerFactory
import com.shiping.desktop.player.PlayerFrame
import com.shiping.desktop.player.PlayerKernel
import com.shiping.desktop.player.PlayerOverlay
import com.shiping.desktop.player.isDirectStreamUrl
import com.shiping.desktop.util.AppLog
import com.shiping.desktop.util.Constants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

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

/**
 * 应用级状态：导航栈、播放会话、全局提示、局域网推送接收。
 */
class AppViewModel(private val container: AppContainer) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _navStack = MutableStateFlow<List<Screen>>(listOf(Screen.Home))
    val navStack: StateFlow<List<Screen>> = _navStack.asStateFlow()

    private val _playerSession = MutableStateFlow<PlaybackSession?>(null)
    val playerSession: StateFlow<PlaybackSession?> = _playerSession.asStateFlow()

    /** 播放解析中（点击剧集到起播之间） */
    private val _playerLoading = MutableStateFlow(false)
    val playerLoading: StateFlow<Boolean> = _playerLoading.asStateFlow()

    /** 全局提示（Snackbar 展示，自动清空） */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        collectPushEvents()
    }

    // region 推送播放窗口（独立 Swing JFrame；详情/直播播放由页内内嵌承载）

    /** 当前播放内核（设置页可切换，持久化；对已打开的播放窗口不生效） */
    private val _playerKernel = MutableStateFlow(PlayerControllerFactory.fromPrefs(container.prefs))
    val playerKernel: StateFlow<PlayerKernel> = _playerKernel.asStateFlow()

    /** 切换播放内核（下次打开播放器生效） */
    fun setPlayerKernel(kernel: PlayerKernel) {
        if (_playerKernel.value == kernel) return
        _playerKernel.value = kernel
        container.prefs.put(PlayerControllerFactory.KEY_PLAYER_KERNEL, kernel.id)
        showMessage("播放内核已切换为 ${kernel.displayName}，下次打开播放器生效")
    }

    /** 推送播放窗口（局域网推送直链；页内播放不经过它） */
    private var playerFrame: PlayerFrame? = null

    private val playerCallbacks = object : PlayerFrame.Callbacks {
        override fun onClose() = closePlayer()
        override fun onSwitchQuality(session: PlaybackSession, qn: Int, resumeMs: Long) =
            switchBiliQuality(session, qn, resumeMs)

        // 推送会话无选集/清晰度上下文，对应按钮不展示，回调保底空实现
        override fun onPlayEpisode(sourceIndex: Int, episodeIndex: Int) = Unit
        override fun onDownloadEpisode(episodeIndex: Int) = Unit
    }

    /** 会话序号（每次起播递增，驱动同集重播流转） */
    private val sessionSeq = AtomicLong(0)

    private fun nextSessionSeq(): Long = sessionSeq.incrementAndGet()

    // endregion

    // region 导航

    /** 当前页内播放器控制层（详情/直播页组合时注册，销毁时注销；切页命令式隐藏用） */
    @Volatile
    private var inPageOverlay: PlayerOverlay? = null

    /** 注册页内播放器控制层（详情/直播播放态组合时调用） */
    fun registerInPageOverlay(overlay: PlayerOverlay) {
        inPageOverlay = overlay
    }

    /** 注销页内播放器控制层（播放态销毁时调用，防误隐藏新页 overlay） */
    fun unregisterInPageOverlay(overlay: PlayerOverlay) {
        if (inPageOverlay === overlay) inPageOverlay = null
    }

    /** 侧边栏切页：清空栈直达目标；切离播放页时立即隐藏视频面与控制悬浮层 */
    fun navigate(screen: Screen) {
        // 视频面 HWND 与控制层 JWindow 浮于一切 Compose 之上，切页必须命令式原生隐藏，防遮挡其它页面
        inPageOverlay?.setBackground(true)
        _navStack.value = listOf(screen)
    }

    /** 压栈打开（详情页） */
    fun push(screen: Screen) {
        inPageOverlay?.setBackground(true)
        _navStack.value = _navStack.value + screen
    }

    /** 返回上一页 */
    fun back(): Boolean {
        val stack = _navStack.value
        if (stack.size <= 1) return false
        inPageOverlay?.setBackground(true)
        _navStack.value = stack.dropLast(1)
        return true
    }

    // endregion

    // region 播放入口

    /**
     * 播放剧集：爬虫源先 resolvePlayUrl（含 B 站协议/header），CMS 源直接播放集地址。
     * 带历史上下文（vodId != 0）时读续播进度并写历史。
     */
    fun playEpisode(vod: Vod, sourceUrl: String, sourceIndex: Int, episodeIndex: Int) {
        val episode = vod.parsePlaySources()
            .getOrNull(sourceIndex)?.episodes?.getOrNull(episodeIndex) ?: return
        // 诊断（临时）：记录调用来源，定位异常的循环切集
        val caller = Throwable().stackTrace
            .filter { it.className.contains("shiping") }
            .take(3)
            .joinToString(" <- ") { "${it.className.substringAfterLast('.')}#${it.methodName}:${it.lineNumber}" }
        AppLog.i("Player", "playEpisode: ${episode.name} by $caller")
        scope.launch {
            _playerLoading.value = true
            runCatching {
                val spiderInfo = container.vodRepository.resolvePlayUrl(sourceUrl, episode.url)
                when {
                    // parse=1 且非直链：网页解析场景桌面无 WebView，明确提示
                    spiderInfo != null && spiderInfo.parse == 1 && !isDirectStreamUrl(spiderInfo.url) -> {
                        showMessage("桌面版暂不支持网页解析播放该集（parse=1）")
                    }
                    spiderInfo != null -> startPlayback(
                        spiderInfo.url,
                        spiderInfo.headers,
                        vod,
                        sourceUrl,
                        sourceIndex,
                        episodeIndex,
                        episode,
                        spiderInfo,
                    )
                    else -> startPlayback(
                        episode.url,
                        emptyMap(),
                        vod,
                        sourceUrl,
                        sourceIndex,
                        episodeIndex,
                        episode,
                        null,
                    )
                }
            }.onFailure {
                AppLog.e("Player", "起播失败: ${it.message}")
                showMessage("起播失败：${it.message ?: "未知错误"}")
            }
            _playerLoading.value = false
        }
    }

    /**
     * 直链直接播放（手机推送）：独立 PlayerFrame 承载（无详情上下文，不进页内播放）。
     * EDT 上判断存活窗口：在播窗口切流，死窗口/无窗口则按当前内核新建（命令式，规避会话去重漏弹窗）。
     */
    fun playDirect(title: String, url: String) {
        val session = PlaybackSession(
            title = title,
            episodeName = "",
            playUrl = url,
            rawUrl = url,
            inPage = false,
            seq = nextSessionSeq(),
        )
        _playerSession.value = session
        javax.swing.SwingUtilities.invokeLater {
            try {
                val existing = playerFrame
                if (existing != null && existing.isShowing) {
                    existing.playSession(session)
                    return@invokeLater
                }
                // 残留窗口（已关未清）先释放，再新建
                existing?.closeFrame()
                AppLog.i("Player", "创建推送播放窗口 kernel=${_playerKernel.value.displayName}")
                val frame = PlayerFrame(
                    PlayerControllerFactory.create(_playerKernel.value, container.prefs),
                    session,
                    playerCallbacks,
                )
                playerFrame = frame
                frame.isVisible = true
            } catch (e: Throwable) {
                AppLog.e("Player", "推送播放窗口处理失败: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    /** 关闭播放器：复位页内全屏与会话，EDT 上关推送窗口（窗口自身回调 onClose 时幂等） */
    fun closePlayer() {
        _detailFullscreen.value = false
        _tvFullscreen.value = false
        _playerSession.value = null
        javax.swing.SwingUtilities.invokeLater {
            playerFrame?.closeFrame()
            playerFrame = null
        }
    }

    // region 页内播放全屏

    /** 详情页内播放全屏（铺满整个应用窗口：隐藏侧栏，播放区占满内容区） */
    private val _detailFullscreen = MutableStateFlow(false)
    val detailFullscreen: StateFlow<Boolean> = _detailFullscreen.asStateFlow()

    /** 切换详情页内播放全屏 */
    fun toggleDetailFullscreen() {
        _detailFullscreen.value = !_detailFullscreen.value
    }

    /** 直播页内播放全屏（铺满整个应用窗口：隐藏侧栏，播放器占满内容区） */
    private val _tvFullscreen = MutableStateFlow(false)
    val tvFullscreen: StateFlow<Boolean> = _tvFullscreen.asStateFlow()

    /** 切换直播页内播放全屏 */
    fun toggleTvFullscreen() {
        _tvFullscreen.value = !_tvFullscreen.value
    }

    /** 退出直播页内播放全屏（离开直播页/退出播放时复位） */
    fun closeTvFullscreen() {
        _tvFullscreen.value = false
    }

    // endregion

    /**
     * B 站清晰度切换：用 cid+aid+qn 换新直链后带当前进度重播。
     * 由播放层携带当前进度调用（控制器持有进度）。
     */
    fun switchBiliQuality(session: PlaybackSession, qn: Int, resumeMs: Long) {
        val option = session.qualityOptions.firstOrNull { it.qn == qn } ?: return
        scope.launch {
            _playerLoading.value = true
            val newUrl = com.shiping.desktop.data.spider.BiliPlayUrl.fetch(session.biliAid, session.biliCid, qn)
            if (newUrl.isNullOrBlank()) {
                showMessage("清晰度切换失败：换取直链失败")
            } else {
                _playerSession.value = session.copy(
                    playUrl = PlaybackUrlResolver.resolve(newUrl, session.headers, SpiderProxyServer.listeningPort),
                    rawUrl = newUrl,
                    currentQn = qn,
                    startMs = resumeMs,
                    seq = nextSessionSeq(),
                )
            }
            _playerLoading.value = false
        }
    }

    // endregion

    /** 展示全局提示（3 秒自动清除由 UI 层处理） */
    fun showMessage(text: String) {
        _message.value = text
    }

    fun clearMessage() {
        _message.value = null
    }

    /** 组装会话：header 注入包装 + 续播进度 + B 站上下文 + 点播选集/线路上下文 */
    private suspend fun startPlayback(
        rawUrl: String,
        headers: Map<String, String>,
        vod: Vod,
        sourceUrl: String,
        sourceIndex: Int,
        episodeIndex: Int,
        episode: PlayEpisode,
        spiderInfo: com.shiping.desktop.data.spider.SpiderPlayInfo?,
    ) {
        val history = container.playHistoryRepository.getByVod(vod.vodId, sourceUrl)
        // 进度按集匹配：仅同一集续播；切集/下一集一律从头播放
        val startMs = history
            ?.takeIf { it.lastEpisodeName == episode.name }
            ?.lastPositionMs
            ?.takeIf { it > Constants.PLAYBACK_MEMORY_MIN_MS }
            ?: 0L
        val sources = vod.parsePlaySources()
        val currentSource = sources.getOrNull(sourceIndex)
        // 页内会话：详情页内嵌播放器消费（episodes/playSources 供 overlay 选集/换源）
        _playerSession.value = PlaybackSession(
            title = vod.vodName,
            episodeName = episode.name,
            vodId = vod.vodId,
            sourceUrl = sourceUrl,
            vodPic = vod.safePic,
            playUrl = PlaybackUrlResolver.resolve(rawUrl, headers, SpiderProxyServer.listeningPort),
            rawUrl = rawUrl,
            headers = headers,
            startMs = startMs,
            biliCid = spiderInfo?.biliCid ?: 0L,
            biliAid = spiderInfo?.biliAid ?: 0L,
            qualityOptions = spiderInfo?.qualityOptions ?: emptyList(),
            currentQn = spiderInfo?.qualityOptions?.firstOrNull()?.qn ?: 0,
            episodes = currentSource?.episodes?.mapIndexed { index, item ->
                item.name.ifBlank { "第${index + 1}集" }
            }.orEmpty(),
            currentEpisodeIndex = episodeIndex,
            playSources = sources,
            currentSourceIndex = sourceIndex,
            inPage = true,
            seq = nextSessionSeq(),
        )
    }

    /**
     * 局域网推送接收：直链直接播放；其余链接引导到源管理（桌面端不支持网页解析）。
     */
    private fun collectPushEvents() {
        scope.launch {
            SpiderProxyServer.pushEvents.collect { push ->
                AppLog.i("Push", "收到推送: ${push.title} ${push.url}")
                if (isDirectStreamUrl(push.url)) {
                    playDirect(push.title.ifBlank { "局域网推送" }, push.url)
                } else {
                    showMessage("收到推送：该链接非直链，桌面版暂不支持播放")
                }
            }
        }
    }
}
