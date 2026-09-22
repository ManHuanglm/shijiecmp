package com.shiping.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shiping.desktop.data.model.TvChannel
import com.shiping.desktop.data.model.TvSourceEntity
import com.shiping.desktop.data.prefs.DesktopPrefs
import com.shiping.desktop.di.AppContainer
import com.shiping.desktop.player.PlayerController
import com.shiping.desktop.player.PlayerControllerFactory
import com.shiping.desktop.player.PlayerOverlay
import com.shiping.desktop.player.PlayerOverlayConfig
import com.shiping.desktop.player.PlayerWindowActions
import com.shiping.desktop.ui.common.DropdownSelector
import com.shiping.desktop.ui.common.EmptyView
import com.shiping.desktop.ui.common.ErrorView
import com.shiping.desktop.ui.common.ImageLoader
import com.shiping.desktop.ui.common.LoadingView
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
    val error: String? = null,
    val channels: List<TvChannel> = emptyList(),
)

/** 直播状态管理：直播源切换 + 频道列表拉取 */
class TvViewModel(
    private val scope: CoroutineScope,
    private val container: AppContainer,
) {
    private val tvRepository = container.tvRepository

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
        container.prefs.put(DesktopPrefs.KEY_CURRENT_TV_ID, source.id)
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
}

/**
 * 直播页双态：
 * - 列表态：顶栏（台标 + 源切换 + 频道数 + 刷新）+ 横向分类标签 + 频道卡片网格（收藏星标），点频道进入播放态
 * - 播放态：左 16:9 内嵌播放器（下方 全屏/收藏）+ 中竖排 Tab + 右频道卡片列表
 */
@Composable
fun TvScreen(app: AppViewModel) {
    val container = AppContainer.get()
    val scope = rememberCoroutineScope()
    val viewModel = remember { TvViewModel(scope, container) }
    val uiState by viewModel.uiState.collectAsState()
    val sources by viewModel.sources.collectAsState()
    val currentSource by viewModel.currentSource.collectAsState()

    val groups = remember(uiState.channels) {
        uiState.channels.groupBy { it.group }.map { group -> GroupedChannels(group.key, group.value) }
    }
    // 分组列表变化（切源/刷新）时自动回到第一个分组
    var selectedGroupIndex by remember(groups) { mutableStateOf(0) }
    val visibleChannels = groups.getOrNull(selectedGroupIndex)?.channels ?: uiState.channels

    // 收藏频道（favoriteKey 集合，持久化到 prefs）
    val prefs = container.prefs
    var favoriteKeys by remember {
        mutableStateOf(
            prefs.getString(DesktopPrefs.KEY_TV_FAVORITES)
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
        prefs.put(DesktopPrefs.KEY_TV_FAVORITES, next.joinToString(","))
    }

    // 播放态右侧栏选中项：0=收藏 1=更新时间 2+=分组（默认第一个分组）
    var railTabIndex by remember(groups) { mutableStateOf(2) }
    val railChannels = when (railTabIndex) {
        0 -> uiState.channels.filter { it.favoriteKey in favoriteKeys }
        1 -> uiState.channels
        else -> groups.getOrNull(railTabIndex - 2)?.channels ?: uiState.channels
    }

    // 内嵌播放状态：当前频道 / 线路序号 / 控制器（首次点频道按内核设置创建）
    var currentChannel by remember { mutableStateOf<TvChannel?>(null) }
    var currentLineIndex by remember { mutableStateOf(0) }
    var playerController by remember { mutableStateOf<PlayerController?>(null) }

    // 失败自动容错：连续换源计数（播放成功清零，超过上限停止防死循环）
    var failoverCount by remember { mutableStateOf(0) }

    // 离开直播页释放 native 播放器并复位全屏（释放走后台线程，避免卡住切页）
    DisposableEffect(Unit) {
        onDispose {
            app.closeTvFullscreen()
            playerController?.releaseAsync()
            playerController = null
        }
    }

    /**
     * 手动播放/切台/切线路：重置容错计数，同步创建控制器（保证 SwingPanel factory
     * 拿到真实视频面），起播由下方协程在视频面挂载后执行。
     */
    val startPlay: (TvChannel, Int) -> Unit = { channel, lineIndex ->
        failoverCount = 0
        currentChannel = channel
        currentLineIndex = lineIndex
        if (playerController == null) {
            playerController = PlayerControllerFactory.create(
                PlayerControllerFactory.fromPrefs(container.prefs),
                container.prefs,
            )
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

    // 起播流程：等视频面挂载（内核要求 displayable）→ 播放 → 失败监控（报错/长时间无画面自动换源）
    LaunchedEffect(currentChannel, currentLineIndex) {
        val channel = currentChannel ?: return@LaunchedEffect
        val controller = playerController ?: return@LaunchedEffect
        val surface = controller.swingComponent
        var waited = 0
        while (surface != null && !surface.isDisplayable && waited < MOUNT_WAIT_TIMEOUT_MS) {
            delay(MOUNT_POLL_INTERVAL_MS.toLong())
            waited += MOUNT_POLL_INTERVAL_MS
        }
        controller.play(channel.urls.getOrElse(currentLineIndex) { channel.url }, emptyMap(), 0L)
        var everPlayed = false
        var elapsed = 0
        var stallMs = 0
        while (elapsed < FAILOVER_MAX_WAIT_MS) {
            delay(FAILOVER_POLL_INTERVAL_MS.toLong())
            elapsed += FAILOVER_POLL_INTERVAL_MS
            if (controller.isPlaying.value) {
                everPlayed = true
                failoverCount = 0
                return@LaunchedEffect
            }
            if (!controller.errorText.value.isNullOrBlank() || !controller.initError.value.isNullOrBlank()) break
            // 已播放成功后的暂停/断流不自动容错（避免用户手动暂停被切台）
            if (everPlayed) return@LaunchedEffect
            stallMs = if (controller.isBuffering.value) 0 else stallMs + FAILOVER_POLL_INTERVAL_MS
            if (stallMs >= FAILOVER_STALL_TIMEOUT_MS) break
        }
        failoverPlay()
    }

    /** 停止播放并释放控制器，回到列表态（释放走后台线程，避免卡住点击响应） */
    val stopPlay: () -> Unit = {
        playerController?.releaseAsync()
        playerController = null
        currentChannel = null
    }

    val channel = currentChannel
    val activeController = playerController
    if (channel == null || activeController == null) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 顶栏：台标 + 源切换 + 频道数 + 刷新
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.LiveTv,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                DropdownSelector(
                    currentText = currentSource?.name ?: "选择直播源",
                    items = sources,
                    itemText = { it.name },
                    onSelect = { viewModel.selectSource(it) },
                    modifier = Modifier.width(200.dp),
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "共 ${uiState.channels.size} 个频道",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (uiState.channels.isNotEmpty()) {
                    IconButton(onClick = { currentSource?.let { viewModel.loadChannels(it) } }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                    }
                }
            }
            when {
                uiState.loading -> LoadingView()
                uiState.error != null -> ErrorView(uiState.error!!, onRetry = {
                    currentSource?.let { viewModel.loadChannels(it) }
                })
                uiState.channels.isEmpty() -> EmptyView("暂无频道")
                else -> Column(modifier = Modifier.fillMaxSize()) {
                    // 横向分类标签（选中带下划线）
                    ScrollableTabRow(
                        selectedTabIndex = selectedGroupIndex,
                        modifier = Modifier.fillMaxWidth(),
                        edgePadding = 16.dp,
                        containerColor = Color.Transparent,
                    ) {
                        groups.forEachIndexed { index, group ->
                            Tab(
                                selected = index == selectedGroupIndex,
                                onClick = { selectedGroupIndex = index },
                                text = {
                                    Text(
                                        text = group.name,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                            )
                        }
                    }
                    // 频道卡片网格
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(4),
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
    } else {
        TvPlayMode(
            app = app,
            channel = channel,
            lineIndex = currentLineIndex,
            controller = activeController,
            groups = groups,
            allChannels = uiState.channels,
            railTabIndex = railTabIndex,
            onSelectRailTab = { railTabIndex = it },
            channels = railChannels,
            favoriteKeys = favoriteKeys,
            onToggleFavorite = toggleFavorite,
            onPlayChannel = { target, line -> startPlay(target, line) },
            onBack = stopPlay,
        )
    }
}

/**
 * 播放态：顶栏（返回 + 频道名 + 收藏）+ 16:9 内嵌播放器（下方居中 全屏/收藏）+
 * 中竖排 Tab（收藏/更新时间/分组）+ 右频道卡片列表；全屏时播放器铺满应用窗口。
 */
@Composable
private fun TvPlayMode(
    app: AppViewModel,
    channel: TvChannel,
    lineIndex: Int,
    controller: PlayerController,
    groups: List<GroupedChannels>,
    allChannels: List<TvChannel>,
    railTabIndex: Int,
    onSelectRailTab: (Int) -> Unit,
    channels: List<TvChannel>,
    favoriteKeys: Set<String>,
    onToggleFavorite: (TvChannel) -> Unit,
    onPlayChannel: (TvChannel, Int) -> Unit,
    onBack: () -> Unit,
) {
    val prefs = AppContainer.get().prefs
    val displayTitle = if (lineIndex > 0) "${channel.name}（线路${lineIndex + 1}）" else channel.name
    val isFavorite = channel.favoriteKey in favoriteKeys
    // 覆盖式控制层：与详情页同一播放器组件（频道列表/播放设置走右缘滑出面板）
    val overlay = remember(controller) { PlayerOverlay(controller.swingComponent, controller) }
    // 切页命令式隐藏：注册到 AppViewModel（navigate/push/back 时立即原生隐藏视频面与控制层）
    DisposableEffect(overlay) {
        app.registerInPageOverlay(overlay)
        onDispose { app.unregisterInPageOverlay(overlay) }
    }
    // 直播页被其他 Tab 覆盖时隐藏视频面与控制层（heavyweight 面会盖住前台页面），回前台恢复
    val navStack by app.navStack.collectAsState()
    LaunchedEffect(navStack.lastOrNull()) {
        overlay.setBackground(navStack.lastOrNull() != Screen.Tv)
    }
    // 直播页内全屏（隐藏侧栏铺满应用窗口；返回键/双击/Esc 先退全屏）
    val fullscreen by app.tvFullscreen.collectAsState()
    DisposableEffect(Unit) {
        onDispose { app.closeTvFullscreen() }
    }
    remember(overlay) {
        overlay.windowActions = object : PlayerWindowActions {
            override fun toggleMaximize() = app.toggleTvFullscreen()
            override fun toggleFullscreen() = app.toggleTvFullscreen()
            override fun toggleMiniWindow() = overlay.defaultWindowActions.toggleMiniWindow()
            override val isFullscreen: Boolean get() = app.tvFullscreen.value
        }
    }
    SideEffect {
        overlay.updateUi(
            PlayerOverlayConfig(
                title = displayTitle,
                onBack = Runnable { onBack() },
                onChannelList = Runnable {
                    overlay.showSidePanel(
                        TvChannelSidePanel(
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
                        ),
                        TV_CHANNEL_PANEL_WIDTH_PX,
                    )
                },
                onPlayerSettings = Runnable {
                    overlay.showSidePanel(PlayerSettingsPanel(), TV_SETTINGS_PANEL_WIDTH_PX)
                },
                currentKernel = PlayerControllerFactory.fromPrefs(prefs),
                onKernelSelected = { kernel ->
                    prefs.put(PlayerControllerFactory.KEY_PLAYER_KERNEL, kernel.id)
                },
            ),
        )
    }
    Column(modifier = Modifier.fillMaxSize()) {
        // 顶栏（全屏时隐藏，控制层顶栏已有返回/标题）
        if (!fullscreen) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
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
                        imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
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
        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
            // 左：16:9 播放器（全屏铺满）+ 下方居中 全屏/收藏
            Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                BoxWithConstraints(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    // 16:9 固定比例：宽度优先，可用高度不足时按高反推宽度（水平居中）
                    val fitHeight = maxWidth * (9f / 16f)
                    val fits = fitHeight <= maxHeight
                    val boxWidth = if (fits) maxWidth else maxHeight * (16f / 9f)
                    val boxHeight = if (fits) fitHeight else maxHeight
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(if (fullscreen) Color.Black else Color.Transparent),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        // 全屏/非全屏共用同一 SwingPanel 槽位，切换不重建视频面（防 wid 重挂）
                        SwingPanel(
                            factory = remember(overlay) { { overlay } },
                            modifier = if (fullscreen) {
                                Modifier.fillMaxSize()
                            } else {
                                Modifier.size(boxWidth, boxHeight)
                            },
                        )
                    }
                }
                // 播放器下方居中：全屏 + 收藏
                if (!fullscreen) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = { overlay.toggleFullscreen() }) {
                            Icon(Icons.Filled.Fullscreen, contentDescription = "全屏")
                        }
                        IconButton(onClick = { onToggleFavorite(channel) }) {
                            Icon(
                                imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
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
            // 中：竖排 Tab（收藏/更新时间/分组）
            if (!fullscreen) {
                TvRailColumn(
                    groupNames = groups.map { it.name },
                    selectedIndex = railTabIndex,
                    onSelect = onSelectRailTab,
                    modifier = Modifier.width(120.dp).fillMaxHeight(),
                )
                // 右：频道卡片列表
                LazyColumn(
                    modifier = Modifier.width(300.dp).fillMaxHeight(),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(channels, key = { it.favoriteKey }) { item ->
                        TvPlayChannelCard(
                            channel = item,
                            selected = item.favoriteKey == channel.favoriteKey,
                            onPlay = { onPlayChannel(item, 0) },
                            onSelectLine = { line -> onPlayChannel(item, line) },
                        )
                    }
                }
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
                    Icons.Filled.Schedule,
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
            .background(if (selected) scheme.secondaryContainer else Color.Transparent)
            .border(
                width = if (badge) 1.dp else 0.dp,
                color = if (badge) scheme.primary else Color.Transparent,
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
            maxLines = 1,
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
                        imageVector = Icons.Filled.MoreVert,
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
                imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
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
    val bitmap by produceState<ImageBitmap?>(initialValue = ImageLoader.cached(url), key1 = url) {
        if (url.isBlank()) return@produceState
        value = ImageLoader.load(url)
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

/** 频道分组 */
private data class GroupedChannels(val name: String, val channels: List<TvChannel>)

/** 起播前等待视频面挂载的上限（毫秒） */
private const val MOUNT_WAIT_TIMEOUT_MS = 5000

/** 视频面挂载轮询间隔（毫秒） */
private const val MOUNT_POLL_INTERVAL_MS = 50

/** 起播失败监控最长等待（毫秒，超时未播放自动换源） */
private const val FAILOVER_MAX_WAIT_MS = 60_000

/** 起播失败监控：无画面无缓冲持续阈值（毫秒） */
private const val FAILOVER_STALL_TIMEOUT_MS = 15_000

/** 失败监控轮询间隔（毫秒） */
private const val FAILOVER_POLL_INTERVAL_MS = 500
