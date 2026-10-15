package com.shiping.desktop.ui

import com.shiping.desktop.data.model.PlayEpisode
import com.shiping.desktop.data.model.PlaySource
import com.shiping.desktop.data.model.Vod
import com.shiping.desktop.data.spider.BiliQualityOption
import com.shiping.desktop.data.spider.BiliPlayUrl
import com.shiping.desktop.player.PlaybackUrlResolver
import com.shiping.desktop.player.isDirectStreamUrl
import com.shiping.desktop.util.Constants
import com.shiping.shared.graph.AppGraph
import com.shiping.shared.prefs.PrefsKeys
import com.shiping.shared.spider.SpiderProxyServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * 应用级状态：导航栈、播放会话、全局提示、局域网推送接收。
 * graph 公开给屏幕层：屏内局部 ViewModel 与直接仓库访问经它取依赖。
 * 播放承载经 [DirectPlaybackHost]（桌面=PlayerFrame 弹窗，Android=全屏页），
 * 页内控制层经 [InPageOverlayHost] 注册（桌面=PlayerOverlay，Android=Compose 控制层）。
 */
class AppViewModel(
    val graph: AppGraph,
    private val directHost: DirectPlaybackHost,
) {

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

    /** 设置页深链待打开的二级页（外部入口指定，SettingsScreen 进入时消费一次） */
    private val _pendingSettingsPage = MutableStateFlow<SettingsPage?>(null)

    init {
        collectPushEvents()
    }

    /** 跳转设置页并直达指定二级页（如首页源列表弹层跳「视频源」管理） */
    internal fun openSettingsPage(page: SettingsPage) {
        _pendingSettingsPage.value = page
        navigate(Screen.Settings)
    }

    /** 消费待打开的设置二级页（一次性） */
    internal fun consumePendingSettingsPage(): SettingsPage? {
        val page = _pendingSettingsPage.value
        _pendingSettingsPage.value = null
        return page
    }

    // region 播放内核与独立直链播放宿主

    /** 当前播放内核 ID（字符串跨端通用；设置页可切换，持久化；对已打开的播放窗口不生效） */
    private val _kernelId = MutableStateFlow(graph.prefs.getString(PrefsKeys.KEY_PLAYER_KERNEL, KernelIds.DEFAULT))
    val kernelId: StateFlow<String> = _kernelId.asStateFlow()

    /** 切换播放内核（下次打开播放器生效） */
    fun switchKernel(id: String) {
        if (_kernelId.value == id) return
        _kernelId.value = id
        graph.prefs.put(PrefsKeys.KEY_PLAYER_KERNEL, id)
        showMessage("播放内核已切换为 $id，下次打开播放器生效")
    }

    /** 会话序号（每次起播递增，驱动同集重播流转） */
    private val sessionSeq = AtomicLong(0)

    private fun nextSessionSeq(): Long = sessionSeq.incrementAndGet()

    // endregion

    // region 导航

    /** 当前页内播放器控制层（详情/直播页组合时注册，销毁时注销；切页命令式隐藏用） */
    @Volatile
    private var inPageOverlay: InPageOverlayHost? = null

    /** 注册页内播放器控制层（详情/直播播放态组合时调用） */
    fun registerInPageOverlay(overlay: InPageOverlayHost) {
        inPageOverlay = overlay
    }

    /** 注销页内播放器控制层（播放态销毁时调用，防误隐藏新页 overlay） */
    fun unregisterInPageOverlay(overlay: InPageOverlayHost) {
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

    // region 页面级状态单例

    /** 首页/直播状态跨导航缓存：单例持有，重进页面不重新拉取（下拉刷新/切源时更新） */
    val homeViewModel: HomeViewModel by lazy { HomeViewModel(scope, graph) }
    val tvViewModel: TvViewModel by lazy { TvViewModel(scope, graph) }

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
        VmLog.i("Player", "playEpisode: ${episode.name} by $caller")
        scope.launch {
            _playerLoading.value = true
            runCatching {
                val spiderInfo = graph.vodRepository.resolvePlayUrl(sourceUrl, episode.url)
                when {
                    // parse=1 且非直链：网页解析场景。Android 走 WebView 嗅探真实直链，
                    // 桌面无 WebView 提示更换线路
                    spiderInfo != null && spiderInfo.parse == 1 && !isDirectStreamUrl(spiderInfo.url) -> {
                        if (supportsWebSniffing) {
                            val fallback = episode.url.takeIf { it.isNotBlank() && it != spiderInfo.url }
                            val sniffed = sniffParsePlayUrl(spiderInfo.url, fallback, spiderInfo.headers)
                            if (sniffed != null) {
                                startPlayback(
                                    sniffed.url,
                                    sniffed.headers,
                                    vod,
                                    sourceUrl,
                                    sourceIndex,
                                    episodeIndex,
                                    episode,
                                    spiderInfo,
                                )
                            } else {
                                showMessage("网页解析失败：未嗅探到播放地址，请更换线路")
                            }
                        } else {
                            showMessage("暂不支持网页解析播放该集（parse=1），请更换线路")
                        }
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
                VmLog.e("Player", "起播失败: ${it.message}")
                showMessage("起播失败：${it.message ?: "未知错误"}")
            }
            _playerLoading.value = false
        }
    }

    /**
     * 直链直接播放（手机推送）：经 [DirectPlaybackHost] 承载（无详情上下文，不进页内播放）。
     * 宿主实现负责窗口复用/新建（命令式，规避会话去重漏弹窗）。
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
        directHost.openOrCreate(session, _kernelId.value, graph.prefs)
    }

    /** 剪贴板/外部分享发起的搜索（SearchScreen 进入时消费一次并自动搜索） */
    private val _pendingSearch = MutableStateFlow<String?>(null)

    /** 外部发起搜索：记录待搜关键词并跳转搜索页 */
    fun requestSearch(keyword: String) {
        _pendingSearch.value = keyword
        navigate(Screen.Search)
    }

    /** 消费待搜关键词（消费即清空，防重复触发） */
    fun consumePendingSearch(): String? = _pendingSearch.value.also { _pendingSearch.value = null }

    /** 关闭播放器：复位页内全屏与会话，并关闭独立播放宿主（窗口自身回调 onClose 时幂等） */
    fun closePlayer() {
        _detailFullscreen.value = false
        _tvFullscreen.value = false
        _playerSession.value = null
        directHost.close()
    }

    // region 页内播放视频尺寸

    /** 当前页内播放视频尺寸（宽, 高），未知为 (0, 0)；Android 全屏方向决策用（竖屏视频全屏不旋转），由挂载内核的平台页面回传 */
    private val _playerVideoSize = MutableStateFlow(0 to 0)
    val playerVideoSize: StateFlow<Pair<Int, Int>> = _playerVideoSize.asStateFlow()

    /** 回传当前页内播放视频尺寸 */
    fun updatePlayerVideoSize(width: Int, height: Int) {
        _playerVideoSize.value = width to height
    }

    // endregion

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

    /** 切换直播页内播放全屏（诊断：记录调用方链，定位异常的全屏复位） */
    fun toggleTvFullscreen() {
        _tvFullscreen.value = !_tvFullscreen.value
        VmLog.i("Fullscreen", "toggleTvFullscreen -> ${_tvFullscreen.value} by ${callerTrace()}")
    }

    /** 退出直播页内播放全屏（离开直播页/退出播放时复位；诊断：记录调用方链） */
    fun closeTvFullscreen() {
        VmLog.i("Fullscreen", "closeTvFullscreen by ${callerTrace()}")
        _tvFullscreen.value = false
    }

    /** 诊断用调用方链（shiping 包内前 4 帧） */
    private fun callerTrace(): String = Throwable().stackTrace
        .filter { it.className.contains("shiping") }
        .take(4)
        .joinToString(" <- ") { "${it.className.substringAfterLast('.')}#${it.methodName}:${it.lineNumber}" }

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
        val history = graph.playHistoryRepository.getByVod(vod.vodId, sourceUrl)
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
                VmLog.i("Push", "收到推送: ${push.title} ${push.url}")
                if (isDirectStreamUrl(push.url)) {
                    playDirect(push.title.ifBlank { "局域网推送" }, push.url)
                } else {
                    showMessage("收到推送：该链接非直链，桌面版暂不支持播放")
                }
            }
        }
    }
}
