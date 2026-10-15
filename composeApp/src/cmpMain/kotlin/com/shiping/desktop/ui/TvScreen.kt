package com.shiping.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.LiveTv
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shiping.desktop.data.model.TvChannel
import com.shiping.desktop.data.model.TvSourceEntity
import com.shiping.shared.graph.AppGraph
import com.shiping.shared.prefs.PrefsKeys
import com.shiping.shared.player.PlayerKernel
import com.shiping.desktop.ui.common.CategoryTagFlow as GroupTagFlow
import com.shiping.desktop.ui.common.sheetBodyWidth
import com.shiping.desktop.ui.common.EmptyView
import com.shiping.desktop.ui.common.ErrorView
import com.shiping.desktop.ui.common.ImageCache
import com.shiping.desktop.ui.common.LoadingView
import com.shiping.desktop.ui.common.PullRefreshContainer
import com.shiping.desktop.ui.common.TextWidthTabRow
import com.shiping.desktop.ui.common.fetchImageBitmap
import com.shiping.desktop.ui.player.PagePlayerModeState
import com.shiping.desktop.ui.theme.FixedPalette
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 直播 UI 状态：频道列表 */
data class TvUiState(
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val error: String? = null,
    val channels: List<TvChannel> = emptyList(),
)

/** 直播状态管理：直播源切换 + 频道列表拉取（状态单例跨导航缓存） */
class TvViewModel(
    private val scope: CoroutineScope,
    private val graph: AppGraph,
) {
    private val tvRepository = graph.tvRepository

    private val _uiState = MutableStateFlow(TvUiState())
    val uiState: StateFlow<TvUiState> = _uiState.asStateFlow()

    val sources: StateFlow<List<TvSourceEntity>> = tvRepository.sources

    /** 当前直播源（持久化） */
    private val _currentSource = MutableStateFlow<TvSourceEntity?>(null)
    val currentSource: StateFlow<TvSourceEntity?> = _currentSource.asStateFlow()

    init {
        restoreCurrentSource()
    }

    private fun restoreCurrentSource() {
        scope.launch {
            val storedId = tvRepository.currentTvId()
            val source = tvRepository.sources.value.firstOrNull { it.id == storedId }
                ?: tvRepository.sources.value.firstOrNull()
            _currentSource.value = source
            source?.let { loadChannels(it) }
        }
    }

    /** 切换直播源并重新拉取频道 */
    fun selectSource(source: TvSourceEntity) {
        _currentSource.value = source
        graph.prefs.put(PrefsKeys.KEY_CURRENT_TV_ID, source.id)
        loadChannels(source)
    }

    fun loadChannels(source: TvSourceEntity) {
        scope.launch {
            _uiState.value = TvUiState(loading = true)
            runCatching { tvRepository.loadChannels(source) }
                .onSuccess { channels -> _uiState.value = TvUiState(channels = channels) }
                .onFailure { e -> _uiState.value = TvUiState(error = e.message ?: "频道拉取失败") }
        }
    }

    /** 下拉刷新：重新拉取当前源频道（保留旧列表展示，仅转指示器） */
    fun refresh() {
        val source = _currentSource.value ?: return
        val state = _uiState.value
        if (state.loading || state.refreshing) return
        scope.launch {
            _uiState.value = state.copy(refreshing = true, error = null)
            runCatching { tvRepository.loadChannels(source) }
                .onSuccess { channels ->
                    _uiState.value = _uiState.value.copy(refreshing = false, channels = channels)
                }
                .onFailure { e ->
                    _uiState.value = _uiState.value.copy(
                        refreshing = false,
                        error = if (_uiState.value.channels.isEmpty()) e.message ?: "频道拉取失败" else null,
                    )
                }
        }
    }
}

/**
 * 直播页双态：
 * - 列表态：顶栏（台标 + 源切换 + 频道数 + 刷新）+ 横向分类标签 + 频道卡片网格（收藏星标），点频道进入播放态
 * - 播放态：左 16:9 内嵌播放器（下方 全屏/收藏）+ 中竖排 Tab + 右频道卡片列表
 */
@Composable
fun TvScreen(app: AppViewModel) {
    val graph = app.graph
    val scope = rememberCoroutineScope()
    val viewModel = app.tvViewModel
    val uiState by viewModel.uiState.collectAsState()
    val sources by viewModel.sources.collectAsState()
    val currentSource by viewModel.currentSource.collectAsState()

    val groups = remember(uiState.channels) {
        uiState.channels.groupBy { it.group }.map { group -> GroupedChannels(group.key, group.value) }
    }
    // 分类管理：隐藏分组集合（会话级）+ 管理弹层开关；展示位统一用 displayGroups 过滤
    var hiddenGroups by remember { mutableStateOf(setOf<String>()) }
    var showGroupManager by remember { mutableStateOf(false) }
    val displayGroups = groups.filter { it.name !in hiddenGroups }
    // 分组列表变化（切源/刷新）时自动回到第一个分组
    var selectedGroupIndex by remember(groups) { mutableStateOf(0) }
    // 当前选中分组被隐藏时回退到第一个展示分组
    val visibleChannels = displayGroups.getOrNull(selectedGroupIndex)?.channels
        ?: displayGroups.firstOrNull()?.channels
        ?: uiState.channels

    // 收藏频道（favoriteKey 集合，持久化到 prefs）
    val prefs = graph.prefs
    var favoriteKeys by remember {
        mutableStateOf(
            prefs.getString(PrefsKeys.KEY_TV_FAVORITES)
                .split(',')
                .filter(String::isNotBlank)
                .toSet(),
        )
    }
    val toggleFavorite: (TvChannel) -> Unit = { channel ->
        val next = if (channel.favoriteKey in favoriteKeys) {
            favoriteKeys - channel.favoriteKey
        } else {
            favoriteKeys + channel.favoriteKey
        }
        favoriteKeys = next
        prefs.put(PrefsKeys.KEY_TV_FAVORITES, next.joinToString(","))
    }

    // 播放态右侧栏选中项：0=收藏 1=更新时间 2+=分组（默认第一个分组）
    var railTabIndex by remember(groups) { mutableStateOf(2) }
    val railChannels = when (railTabIndex) {
        0 -> uiState.channels.filter { it.favoriteKey in favoriteKeys }
        1 -> uiState.channels
        else -> displayGroups.getOrNull(railTabIndex - 2)?.channels ?: uiState.channels
    }

    // 内嵌播放状态：当前频道 / 线路序号 / 内核（首次点频道按内核设置创建）
    var currentChannel by remember { mutableStateOf<TvChannel?>(null) }
    var currentLineIndex by remember { mutableStateOf(0) }
    var playerKernel by remember { mutableStateOf<PlayerKernel?>(null) }

    // 统一模式状态机：绑定内核后投影 PlayerUiState（全屏真源仍为 app.tvFullscreen）
    val modeState = remember { PagePlayerModeState(scope, app.tvFullscreen) }
    LaunchedEffect(playerKernel) {
        playerKernel?.let { modeState.bind(it, PagePlayerScope.TV) }
    }

    // 失败自动容错：连续换源计数（播放成功清零，超过上限停止防死循环）
    var failoverCount by remember { mutableStateOf(0) }

    // 离开直播页释放 native 播放器并复位全屏（释放走平台异步通道，避免卡住切页）
    DisposableEffect(Unit) {
        onDispose {
            modeState.unbind()
            app.closeTvFullscreen()
            playerKernel?.let(::releasePageKernel)
            playerKernel = null
        }
    }

    /**
     * 手动播放/切台/切线路：重置容错计数，同步创建内核（保证播放区工厂
     * 拿到真实视频面），起播由下方协程在视频面就绪后执行。
     */
    val startPlay: (TvChannel, Int) -> Unit = { channel, lineIndex ->
        failoverCount = 0
        currentChannel = channel
        currentLineIndex = lineIndex
        if (playerKernel == null) {
            playerKernel = createInPageKernel(graph)
        }
    }

    /**
     * 播放失败自动换源：当前线路 +1，线路尽则按右侧栏频道顺序换下一个频道；
     * 连续失败超过「线路数 + 频道数」后停止，避免死循环。
     */
    val failoverPlay: () -> Unit = failover@{
        val channel = currentChannel ?: return@failover
        if (failoverCount >= channel.urls.size + railChannels.size) return@failover
        failoverCount += 1
        val nextLine = currentLineIndex + 1
        if (nextLine < channel.urls.size) {
            currentChannel = channel
            currentLineIndex = nextLine
        } else {
            val index = railChannels.indexOfFirst { it.favoriteKey == channel.favoriteKey }
            currentChannel = railChannels.getOrElse(index + 1) { railChannels.firstOrNull() ?: channel }
            currentLineIndex = 0
        }
    }

    // 起播流程：等视频面就绪 → 播放 → 失败监控（报错/长时间无画面自动换源）。
    // 起播前 350ms 去抖：快速连点换台时旧协程在此取消，只发起最后一次加载（避免互相打断起播更慢）
    LaunchedEffect(currentChannel, currentLineIndex) {
        val channel = currentChannel ?: return@LaunchedEffect
        val kernel = playerKernel ?: return@LaunchedEffect
        awaitSurfaceReady(kernel, MOUNT_WAIT_TIMEOUT_MS, MOUNT_POLL_INTERVAL_MS)
        delay(SWITCH_DEBOUNCE_MS)
        kernel.play(channel.urls.getOrElse(currentLineIndex) { channel.url }, emptyMap(), 0L)
        var everPlayed = false
        var elapsed = 0
        var noPlayMs = 0
        var bufferOnlyMs = 0
        while (elapsed < FAILOVER_MAX_WAIT_MS) {
            delay(FAILOVER_POLL_INTERVAL_MS.toLong())
            elapsed += FAILOVER_POLL_INTERVAL_MS
            if (kernel.isPlaying.value) {
                everPlayed = true
                failoverCount = 0
                return@LaunchedEffect
            }
            if (!kernel.errorText.value.isNullOrBlank() || !kernel.initError.value.isNullOrBlank()) break
            // 已播放后的用户暂停（非缓冲）不自动容错，避免手动暂停被切台
            if (everPlayed && !kernel.isBuffering.value) return@LaunchedEffect
            noPlayMs += FAILOVER_POLL_INTERVAL_MS
            if (kernel.isBuffering.value) bufferOnlyMs += FAILOVER_POLL_INTERVAL_MS
            // 起播持续转圈（分片 404 等源故障会一直缓冲不出画面）：按缓冲阈值换台
            if (!everPlayed && bufferOnlyMs >= FAILOVER_BUFFER_TIMEOUT_MS) break
            // 播放后断流（源中途死亡持续缓冲）：恢复窗口超时判死流换台
            if (everPlayed && noPlayMs >= FAILOVER_RECOVER_STALL_MS) break
            // 无缓冲也无播放的空转（连接挂起等）
            if (!everPlayed && noPlayMs >= FAILOVER_STALL_TIMEOUT_MS) break
        }
        failoverPlay()
    }

    /** 停止播放并释放内核，回到列表态（释放走平台异步通道，避免卡住点击响应） */
    val stopPlay: () -> Unit = {
        playerKernel?.let(::releasePageKernel)
        playerKernel = null
        currentChannel = null
    }

    val channel = currentChannel
    val activeKernel = playerKernel
    if (channel == null || activeKernel == null) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 顶栏：台标 + 源切换 + 频道数 + 刷新
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.LiveTv,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                // 源胶囊（图1）：状态圆点 + 源名，无倒三角；点击从底部弹层选择直播源
                var showSourceSheet by remember { mutableStateOf(false) }
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { showSourceSheet = true }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = currentSource?.name ?: "选择直播源",
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (showSourceSheet) {
                    TvSourceSheet(
                        sources = sources,
                        currentSource = currentSource,
                        onSelect = {
                            viewModel.selectSource(it)
                            showSourceSheet = false
                        },
                        onDismiss = { showSourceSheet = false },
                    )
                }
                Text(
                    "共 ${uiState.channels.size} 个频道",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // 分类管理：点击弹出「展示分类/隐藏分类」管理弹层
                if (uiState.channels.isNotEmpty() && groups.isNotEmpty()) {
                    IconButton(onClick = { showGroupManager = true }) {
                        Icon(Icons.Outlined.Category, contentDescription = "分类管理")
                    }
                }
                if (uiState.channels.isNotEmpty()) {
                    IconButton(onClick = { currentSource?.let { viewModel.loadChannels(it) } }) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "刷新")
                    }
                }
            }
            if (showGroupManager) {
                TvGroupManagerSheet(
                    groups = groups,
                    hiddenGroups = hiddenGroups,
                    onHide = { hiddenGroups = hiddenGroups + it },
                    onShow = { hiddenGroups = hiddenGroups - it },
                    onDismiss = { showGroupManager = false },
                )
            }
            when {
                uiState.loading -> LoadingView()
                uiState.error != null -> ErrorView(uiState.error!!, onRetry = {
                    currentSource?.let { viewModel.loadChannels(it) }
                })
                uiState.channels.isEmpty() -> EmptyView("暂无频道")
                else -> Column(modifier = Modifier.fillMaxSize()) {
                    // 横向分类标签（选中带文本等宽下划线）
                    TextWidthTabRow(
                        tabNames = displayGroups.map { it.name },
                        selectedTabIndex = selectedGroupIndex,
                        onSelectTab = { selectedGroupIndex = it },
                    )
                    // 频道卡片网格（下拉刷新）
                    PullRefreshContainer(
                        refreshing = uiState.refreshing,
                        onRefresh = viewModel::refresh,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(150.dp),
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            gridItems(visibleChannels, key = { it.favoriteKey }) { item ->
                                TvChannelCard(
                                    channel = item,
                                    isFavorite = item.favoriteKey in favoriteKeys,
                                    onToggleFavorite = { toggleFavorite(item) },
                                    onClick = { startPlay(item, 0) },
                                )
                            }
                        }
                    }
                }
            }
        }
    } else {
        TvPlayMode(
            app = app,
            channel = channel,
            lineIndex = currentLineIndex,
            kernel = activeKernel,
            groups = displayGroups,
            allChannels = uiState.channels,
            railTabIndex = railTabIndex,
            onSelectRailTab = { railTabIndex = it },
            channels = railChannels,
            favoriteKeys = favoriteKeys,
            onToggleFavorite = toggleFavorite,
            onPlayChannel = { target, line -> startPlay(target, line) },
            onBack = stopPlay,
            modeState = modeState,
            refreshing = uiState.refreshing,
            onRefresh = viewModel::refresh,
        )
    }
}

/**
 * 播放态：顶栏（返回 + 频道名 + 收藏）+ 播放器 + 频道选择区。
 * 宽屏：左 16:9 播放器（下方 全屏/收藏）+ 中竖排 Tab + 右频道卡片列表（三列）。
 * 窄屏（手机竖屏，图2）：播放器 16:9 贴顶 + 下方 左竖排 Tab + 右频道卡片列表；
 * 全屏时播放器铺满应用窗口（横屏+沉浸式），侧栏隐藏。
 * 失败自动换源（线路 +1 → 按侧栏频道顺序换台）随播放会话生效。
 */
@Composable
private fun TvPlayMode(
    app: AppViewModel,
    channel: TvChannel,
    lineIndex: Int,
    kernel: PlayerKernel,
    groups: List<GroupedChannels>,
    allChannels: List<TvChannel>,
    railTabIndex: Int,
    onSelectRailTab: (Int) -> Unit,
    channels: List<TvChannel>,
    favoriteKeys: Set<String>,
    onToggleFavorite: (TvChannel) -> Unit,
    onPlayChannel: (TvChannel, Int) -> Unit,
    onBack: () -> Unit,
    modeState: PagePlayerModeState,
    refreshing: Boolean,
    onRefresh: () -> Unit,
) {
    val displayTitle = if (lineIndex > 0) "${channel.name}（线路${lineIndex + 1}）" else channel.name
    val isFavorite = channel.favoriteKey in favoriteKeys
    // 直播页被其他 Tab 覆盖时隐藏视频面与控制层（heavyweight 面会盖住前台页面），回前台恢复
    var tvActions by remember { mutableStateOf<InPagePlayerActions?>(null) }
    val navStack by app.navStack.collectAsState()
    LaunchedEffect(navStack.lastOrNull()) {
        tvActions?.setBackground(navStack.lastOrNull() != Screen.Tv)
    }
    // 直播页内全屏（隐藏侧栏铺满应用窗口；返回键/双击/Esc 先退全屏）
    val fullscreen by app.tvFullscreen.collectAsState()
    val kernelId by app.kernelId.collectAsState()
    DisposableEffect(Unit) {
        onDispose { app.closeTvFullscreen() }
    }
    // 频道侧栏规格（桌面=右缘滑出面板；▦ 按钮触发）
    val channelPanelSpec = TvChannelPanelSpec(
        initialTabIndex = railTabIndex,
        groupNames = groups.map { it.name },
        currentChannelKey = channel.favoriteKey,
        channelsProvider = { tab ->
            when (tab) {
                0 -> allChannels.filter { it.favoriteKey in favoriteKeys }
                1 -> allChannels
                else -> groups.getOrNull(tab - 2)?.channels ?: allChannels
            }
        },
        onSelectTab = { tab -> onSelectRailTab(tab) },
        onPlay = { picked, line -> onPlayChannel(picked, line) },
    )
    // 播放器配置（两套布局共用；控制层 ⛶ 切直播全屏 tvFullscreen）
    val playerConfig = InPagePlayerConfig(
        title = displayTitle,
        onBack = onBack,
        channelPanel = channelPanelSpec,
        showPlayerSettings = true,
        kernelId = kernelId,
        onKernelSelected = { id -> app.switchKernel(id) },
        onToggleFullscreen = { app.toggleTvFullscreen() },
    )
    Column(modifier = Modifier.fillMaxSize()) {
        // 顶栏（全屏时隐藏，控制层顶栏已有返回/标题）
        if (!fullscreen) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                }
                Text(
                    text = displayTitle,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { onToggleFavorite(channel) }) {
                    Icon(
                        imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Outlined.Favorite,
                        contentDescription = "收藏",
                        tint = if (isFavorite) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
        // 播放态布局自适应：横向窗口（宽>高，图1）三列 = 左播放器 + 中竖排 Tab + 右 3 列网格；
        // 纵向窗口（宽<=高，图2/手机竖屏）= 播放器贴顶 + 下方 左竖排 Tab + 右 2 列网格
        BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
            val showSideColumns = maxWidth > maxHeight
            if (showSideColumns) {
                Row(modifier = Modifier.fillMaxSize()) {
                    // 左：16:9 播放器（全屏铺满）+ 左下角 全屏/收藏（黑区延伸到窗口下缘）
                    BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        // 16:9 固定比例：宽度优先，可用高度不足时按高反推宽度（水平居中）
                        val fitHeight = maxWidth * (9f / 16f)
                        val fits = fitHeight <= maxHeight
                        val boxWidth = if (fits) maxWidth else maxHeight * (16f / 9f)
                        val boxHeight = if (fits) fitHeight else maxHeight
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black),
                            contentAlignment = Alignment.TopCenter,
                        ) {
                            // 全屏/非全屏共用同一播放区槽位，切换不重建视频面（防 wid 重挂）
                            InPagePlayerSurface(
                                kernel = kernel,
                                app = app,
                                scope = PagePlayerScope.TV,
                                config = playerConfig,
                                modifier = if (fullscreen) {
                                    Modifier.fillMaxSize()
                                } else {
                                    Modifier.size(boxWidth, boxHeight)
                                },
                                onActionsReady = { tvActions = it },
                                modeState = modeState,
                            )
                            // 非全屏：左下角 全屏 + 收藏（叠在黑区内，对齐图1）
                            if (!fullscreen) {
                                Row(
                                    modifier = Modifier
                                        .align(Alignment.BottomStart)
                                        .padding(start = 16.dp, bottom = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    IconButton(onClick = { app.toggleTvFullscreen() }) {
                                        Icon(Icons.Outlined.Fullscreen, contentDescription = "全屏")
                                    }
                                    IconButton(onClick = { onToggleFavorite(channel) }) {
                                        Icon(
                                            imageVector = if (isFavorite) {
                                                Icons.Filled.Favorite
                                            } else {
                                                Icons.Outlined.Favorite
                                            },
                                            contentDescription = "收藏",
                                            tint = if (isFavorite) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                    // 中：竖排 Tab（收藏/更新时间/分组）+ 右：频道卡片网格（列数随宽度自适应 1-3）
                    if (!fullscreen) {
                        TvRailColumn(
                            groupNames = groups.map { it.name },
                            selectedIndex = railTabIndex,
                            onSelect = onSelectRailTab,
                            modifier = Modifier.width(120.dp).fillMaxHeight(),
                        )
                        PullRefreshContainer(
                            refreshing = refreshing,
                            onRefresh = onRefresh,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        ) {
                            TvChannelGrid(
                                channels = channels,
                                currentKey = channel.favoriteKey,
                                onPlay = { item -> onPlayChannel(item, 0) },
                                onSelectLine = { item, line -> onPlayChannel(item, line) },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            } else {
                // 窄屏（图2）：播放器贴顶（宽度始终铺满+按视频真实比例等比自适应；仅极端比例高度
                // 超出可用空间才保底限高收窄；全屏铺满）+ 下方 左竖排 Tab + 右频道列表
                val videoWidth by kernel.videoWidth.collectAsState()
                val videoHeight by kernel.videoHeight.collectAsState()
                val videoRatio = if (videoWidth > 0 && videoHeight > 0) {
                    videoWidth.toFloat() / videoHeight.toFloat()
                } else {
                    16f / 9f
                }
                // 手机端不限制宽度铺满：按视频方向固定比例容器（横屏 16:9 / 竖屏 9:16，未知按横屏）
                val phoneRatio = when {
                    videoWidth <= 0 || videoHeight <= 0 -> 16f / 9f
                    videoWidth >= videoHeight -> 16f / 9f
                    else -> 9f / 16f
                }
                // 纵向排列：播放器贴顶 + 下方频道区（此前列表叠在播放器上层将其遮挡）
                // 全屏时占满整窗：旋转过渡期窗口仍竖屏（showSideColumns 未翻转）也保持铺满不缩
                Column(modifier = Modifier.fillMaxSize()) {
                    BoxWithConstraints(
                        modifier = if (fullscreen) Modifier.fillMaxSize() else Modifier.fillMaxWidth(),
                    ) {
                        val maxPlayerHeight = maxHeight
                        val fitsWidth = maxWidth / videoRatio <= maxPlayerHeight
                        Box(
                            modifier = Modifier.fillMaxWidth()
                                .background(if (fullscreen) Color.Black else Color.Transparent),
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                modifier = when {
                                    fullscreen -> Modifier.fillMaxSize()
                                    isPhonePlatform -> Modifier.fillMaxWidth().aspectRatio(phoneRatio)
                                    fitsWidth -> Modifier.fillMaxWidth().aspectRatio(videoRatio)
                                    else -> Modifier.height(maxPlayerHeight).aspectRatio(videoRatio)
                                },
                            ) {
                                InPagePlayerSurface(
                                    kernel = kernel,
                                    app = app,
                                    scope = PagePlayerScope.TV,
                                    config = playerConfig,
                                    modifier = Modifier.fillMaxSize(),
                                    onActionsReady = { tvActions = it },
                                    modeState = modeState,
                                )
                            }
                        }
                    }
                    if (!fullscreen) {
                        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            TvRailColumn(
                                groupNames = groups.map { it.name },
                                selectedIndex = railTabIndex,
                                onSelect = onSelectRailTab,
                                modifier = Modifier.width(140.dp).fillMaxHeight(),
                            )
                            PullRefreshContainer(
                                refreshing = refreshing,
                                onRefresh = onRefresh,
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                            ) {
                                TvChannelGrid(
                                    channels = channels,
                                    currentKey = channel.favoriteKey,
                                    onPlay = { item -> onPlayChannel(item, 0) },
                                    onSelectLine = { item, line -> onPlayChannel(item, line) },
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 频道网格单列最小宽度（可用宽度 ÷ 该值 = 列数，1-3 列封顶） */
private val GRID_COLUMN_MIN_WIDTH = 240.dp

/** 播放态频道网格：列数随可用宽度自适应（每列 ≥ 240dp，1-3 列封顶，对齐参考图 1/2/3 列形态） */
@Composable
private fun TvChannelGrid(
    channels: List<TvChannel>,
    currentKey: String,
    onPlay: (TvChannel) -> Unit,
    onSelectLine: (TvChannel, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier) {
        val columns = (maxWidth / GRID_COLUMN_MIN_WIDTH).toInt().coerceIn(1, 3)
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            gridItems(channels, key = { it.favoriteKey }) { item ->
                TvPlayChannelCard(
                    channel = item,
                    selected = item.favoriteKey == currentKey,
                    onPlay = { onPlay(item) },
                    onSelectLine = { line -> onSelectLine(item, line) },
                )
            }
        }
    }
}

/** 播放态竖排 Tab：收藏 / 更新时间（红框徽标）/ 频道分组（绿点标记） */
@Composable
private fun TvRailColumn(
    groupNames: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    LazyColumn(
        modifier = modifier.background(scheme.surfaceVariant.copy(alpha = 0.4f)),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item {
            TvRailItem(
                index = 0,
                text = "收藏",
                selected = selectedIndex == 0,
                badge = false,
                onSelect = onSelect,
            ) {
                Icon(
                    Icons.Filled.Favorite,
                    contentDescription = null,
                    tint = scheme.primary,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        item {
            TvRailItem(
                index = 1,
                text = "更新时间",
                selected = selectedIndex == 1,
                badge = true,
                onSelect = onSelect,
            ) {
                Icon(
                    Icons.Outlined.Schedule,
                    contentDescription = null,
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        itemsIndexed(groupNames) { groupIndex, name ->
            TvRailItem(
                index = groupIndex + 2,
                text = name,
                selected = selectedIndex == groupIndex + 2,
                badge = false,
                onSelect = onSelect,
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(FixedPalette.TV_GROUP_GREEN),
                )
            }
        }
    }
}

/** Tab 行：图标 + 文字，选中高亮；badge=true 恒带红框徽标（更新时间） */
@Composable
private fun TvRailItem(
    index: Int,
    text: String,
    selected: Boolean,
    badge: Boolean,
    onSelect: (Int) -> Unit,
    icon: @Composable () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .border(
                width = if (selected || badge) 1.dp else 0.dp,
                color = if (selected || badge) scheme.primary else Color.Transparent,
                shape = RoundedCornerShape(8.dp),
            )
            .clickable { onSelect(index) }
            .padding(horizontal = 8.dp, vertical = 10.dp),
    ) {
        icon()
        Spacer(Modifier.width(6.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) scheme.primary else scheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 播放态频道卡片：台标 + 频道名（当前频道红底高亮），⋮ 弹出线路菜单 */
@Composable
private fun TvPlayChannelCard(
    channel: TvChannel,
    selected: Boolean,
    onPlay: () -> Unit,
    onSelectLine: (Int) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    var menuOpen by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) scheme.primary else scheme.surface)
            .border(
                width = if (selected) 0.dp else 1.dp,
                color = scheme.primary.copy(alpha = 0.45f),
                shape = RoundedCornerShape(10.dp),
            )
            .clickable(onClick = onPlay)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            TvChannelLogo(
                url = channel.logo,
                modifier = Modifier.fillMaxWidth().height(40.dp),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = channel.name,
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) scheme.onPrimary else scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (channel.urls.size > 1) {
            Box(modifier = Modifier.align(Alignment.CenterEnd)) {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(28.dp)) {
                    Icon(
                        imageVector = Icons.Outlined.MoreVert,
                        contentDescription = "切换线路",
                        tint = if (selected) scheme.onPrimary else scheme.onSurfaceVariant,
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    channel.urls.forEachIndexed { index, _ ->
                        DropdownMenuItem(
                            text = { Text("线路${index + 1}") },
                            onClick = {
                                menuOpen = false
                                onSelectLine(index)
                            },
                        )
                    }
                }
            }
        }
    }
}

/** 列表态频道卡片：台标/占位块 + 频道名 + 收藏星标，点击进入播放态 */
@Composable
private fun TvChannelCard(
    channel: TvChannel,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(scheme.secondaryContainer)
            .clickable(onClick = onClick),
    ) {
        Column {
            TvChannelLogo(
                url = channel.logo,
                modifier = Modifier.fillMaxWidth().height(56.dp).padding(top = 10.dp),
            )
            Text(
                text = channel.name,
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSecondaryContainer,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            )
        }
        IconButton(
            onClick = onToggleFavorite,
            modifier = Modifier.align(Alignment.TopEnd).size(28.dp),
        ) {
            Icon(
                imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Outlined.Favorite,
                contentDescription = "收藏",
                tint = if (isFavorite) scheme.primary else scheme.onSecondaryContainer,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/** 台标：加载成功显示图片；加载中/失败/无地址显示占位块 */
@Composable
private fun TvChannelLogo(url: String, modifier: Modifier = Modifier) {
    val bitmap by produceState<ImageBitmap?>(initialValue = ImageCache.cached(url), key1 = url) {
        if (url.isBlank()) return@produceState
        ImageCache.cached(url)?.let { value = it }
        fetchImageBitmap(url)?.let { value = it.also { bmp -> ImageCache.put(url, bmp) } }
    }
    val scheme = MaterialTheme.colorScheme
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        val current = bitmap
        if (current != null) {
            Image(
                bitmap = current,
                contentDescription = null,
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                contentScale = ContentScale.Fit,
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(scheme.primary.copy(alpha = 0.15f)),
            )
        }
    }
}

/**
 * 直播源切换底部弹层：标题 + 源行列表（状态圆点 + 名称），选中行主题色高亮。
 * 样式对齐首页「源列表」弹层；大屏内容取窗口宽一半。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TvSourceSheet(
    sources: List<TvSourceEntity>,
    currentSource: TvSourceEntity?,
    onSelect: (TvSourceEntity) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .sheetBodyWidth()
                .padding(horizontal = 16.dp),
        ) {
            Text(
                text = "直播源",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            LazyColumn(modifier = Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(max = 460.dp)) {
                if (sources.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier.fillMaxWidth().height(96.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "暂无直播源",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                items(sources, key = { it.id }) { source ->
                    val selected = source == currentSource
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (selected) {
                                    MaterialTheme.colorScheme.secondaryContainer
                                } else {
                                    Color.Transparent
                                },
                            )
                            .clickable { onSelect(source) }
                            .padding(horizontal = 10.dp, vertical = 10.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(
                                    if (selected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                ),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = source.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (selected) {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/**
 * 分类管理底部弹层（对齐参考样式）：「展示分类」区点标签隐藏，「隐藏分类」区点标签恢复。
 * 空区给占位说明；大屏内容取窗口宽一半。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun TvGroupManagerSheet(
    groups: List<GroupedChannels>,
    hiddenGroups: Set<String>,
    onHide: (String) -> Unit,
    onShow: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val visibleNames = groups.filter { it.name !in hiddenGroups }.map { it.name }
    val hiddenNames = groups.filter { it.name in hiddenGroups }.map { it.name }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .sheetBodyWidth()
                .padding(horizontal = 16.dp),
        ) {
            Text(
                text = "展示分类",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            GroupTagFlow(
                names = visibleNames,
                dimmed = false,
                onClick = onHide,
                emptyHint = "全部分类均已隐藏",
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = "隐藏分类",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            GroupTagFlow(
                names = hiddenNames,
                dimmed = true,
                onClick = onShow,
                emptyHint = "暂无隐藏分类",
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** 频道分组 */
private data class GroupedChannels(val name: String, val channels: List<TvChannel>)

/** 起播失败监控最长等待（毫秒，超时未播放自动换源） */
private const val FAILOVER_MAX_WAIT_MS = 60_000

/** 起播失败监控：无画面无缓冲持续阈值（毫秒） */
private const val FAILOVER_STALL_TIMEOUT_MS = 15_000

/** 起播失败监控：持续缓冲无画面阈值（毫秒，分片 404 等源故障的转圈特征） */
private const val FAILOVER_BUFFER_TIMEOUT_MS = 20_000

/** 播放后断流恢复窗口（毫秒，持续缓冲超此判死流换台） */
private const val FAILOVER_RECOVER_STALL_MS = 15_000

/** 失败监控轮询间隔（毫秒） */
private const val FAILOVER_POLL_INTERVAL_MS = 500

/** 换台起播去抖（毫秒）：快速连点只发起最后一次加载，避免互相打断越切越慢 */
private const val SWITCH_DEBOUNCE_MS = 350L
