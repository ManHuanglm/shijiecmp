package com.shiping.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.shiping.desktop.data.model.FavoriteEntity
import com.shiping.desktop.data.model.PlayHistoryEntity
import com.shiping.desktop.data.model.Vod
import com.shiping.desktop.player.IntroMark
import com.shiping.desktop.ui.common.ErrorView
import com.shiping.desktop.ui.common.LoadingView
import com.shiping.desktop.ui.common.NetImage
import com.shiping.shared.graph.AppGraph
import com.shiping.shared.prefs.PrefsKeys
import com.shiping.shared.player.PlayerKernel
import com.shiping.desktop.util.Constants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 详情页 UI 状态 */
data class DetailUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val vod: Vod? = null,
    val isFavorite: Boolean = false,
    val historyPositionMs: Long = 0,
)

/** 详情状态管理：详情加载、收藏切换、续播进度 */
class DetailViewModel(
    private val scope: CoroutineScope,
    private val graph: AppGraph,
    private val target: Screen.Detail,
) {
    private val _uiState = MutableStateFlow(DetailUiState())
    val uiState: StateFlow<DetailUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    fun load() {
        scope.launch {
            _uiState.value = DetailUiState(loading = true)
            runCatching { graph.vodRepository.getDetail(target.vodId, target.sourceUrl) }
                .onSuccess { detail ->
                    _uiState.value = _uiState.value.copy(
                        loading = false,
                        vod = detail?.let {
                            // 用详情补全列表带来的字段（封面/备注）
                            it.copy(vodName = it.vodName.ifBlank { target.title }, vodPic = it.vodPic.ifBlank { target.pic })
                        },
                        error = if (detail == null) "未获取到详情数据" else null,
                    )
                }
                .onFailure { e ->
                    _uiState.value = DetailUiState(loading = false, error = e.message ?: "加载失败")
                }
            // 收藏与历史（失败不影响主流程）
            _uiState.value = _uiState.value.copy(
                isFavorite = graph.favoriteRepository.isFavorite(target.vodId, target.sourceUrl),
                historyPositionMs = graph.playHistoryRepository
                    .getByVod(target.vodId, target.sourceUrl)?.lastPositionMs ?: 0L,
            )
        }
    }

    /** 切换收藏 */
    fun toggleFavorite() {
        val vod = _uiState.value.vod ?: return
        scope.launch {
            val favorite = graph.favoriteRepository
            if (_uiState.value.isFavorite) {
                favorite.remove(vod.vodId, target.sourceUrl)
            } else {
                favorite.add(
                    FavoriteEntity(
                        vodId = vod.vodId,
                        sourceUrl = target.sourceUrl,
                        title = vod.vodName.ifBlank { target.title },
                        vodPic = vod.safePic,
                    ),
                )
            }
            _uiState.value = _uiState.value.copy(isFavorite = !_uiState.value.isFavorite)
        }
    }

    /** 播放指定集（索引定位线路与集，详情页内嵌播放器起播） */
    fun play(sourceIndex: Int, episodeIndex: Int, app: AppViewModel) {
        val vod = _uiState.value.vod ?: return
        app.playEpisode(vod, target.sourceUrl, sourceIndex, episodeIndex)
    }
}

/** 详情页双态：浏览态（海报+信息+选集）/ 播放态（左内嵌播放器 + 右信息，对齐 Govin） */
@Composable
fun DetailScreen(target: Screen.Detail, app: AppViewModel) {
    val graph = app.graph
    val scope = rememberCoroutineScope()
    val viewModel = remember(target) { DetailViewModel(scope, graph, target) }
    val uiState by viewModel.uiState.collectAsState()
    // 页内播放会话（详情页主动点播；推送会话 inPage=false 不在此消费）
    val session by app.playerSession.collectAsState()
    val inPageSession = session?.takeIf { it.inPage && it.sourceUrl == target.sourceUrl }
    var inlineKernel by remember { mutableStateOf<PlayerKernel?>(null) }

    // 起播去重：同会话序号不重复 loadfile（重播同集 seq 递增必然触发；会话风暴不重复起播）
    var lastPlayedSeq by remember { mutableStateOf(0L) }

    // 进入详情即自动起播（对照参考 App：详情页常驻播放态）；手动退出播放后不再自动重启
    var autoPlayStarted by remember(target) { mutableStateOf(false) }
    LaunchedEffect(uiState.vod, inPageSession) {
        val vod = uiState.vod ?: return@LaunchedEffect
        if (autoPlayStarted || inPageSession != null) return@LaunchedEffect
        if (vod.parsePlaySources().isEmpty()) return@LaunchedEffect
        autoPlayStarted = true
        viewModel.play(0, 0, app)
    }

    // 起播/切流：内核同步创建（保证播放区工厂拿到真实视频面），渲染面就绪后 play
    LaunchedEffect(inPageSession) {
        val targetSession = inPageSession ?: return@LaunchedEffect
        val kernel = inlineKernel ?: createInPageKernel(graph).also { inlineKernel = it }
        awaitSurfaceReady(kernel, MOUNT_WAIT_TIMEOUT_MS, MOUNT_POLL_INTERVAL_MS)
        if (targetSession.seq == lastPlayedSeq) return@LaunchedEffect
        lastPlayedSeq = targetSession.seq
        // 起播跳片头（记录过片头且起始位置早于片头结束点时定位到片头后）
        val introEnd = IntroMark.endMs(
            graph.prefs,
            IntroMark.Mark(targetSession.vodId, targetSession.sourceUrl, targetSession.episodeName),
        )
        VmLog.i("Detail", "起播: ${targetSession.episodeName} url=${targetSession.playUrl}")
        kernel.play(
            targetSession.playUrl,
            targetSession.headers,
            maxOf(targetSession.startMs, introEnd),
        )
    }

    // 播放中定期落库进度（设置关闭播放记录或源关闭记录时跳过）
    LaunchedEffect(inPageSession) {
        val targetSession = inPageSession ?: return@LaunchedEffect
        while (true) {
            delay(Constants.HISTORY_SAVE_INTERVAL_MS)
            if (!graph.prefs.getBoolean(PrefsKeys.KEY_PLAY_RECORD_ENABLED, true)) continue
            val sourceRecordOn = graph.apiSourceRepository.sources.value
                .firstOrNull { it.url == targetSession.sourceUrl }?.playRecord ?: true
            if (!sourceRecordOn) continue
            val time = inlineKernel?.timeMs?.value ?: continue
            if (targetSession.vodId != 0 && time > Constants.PLAYBACK_MEMORY_MIN_MS) {
                runCatching {
                    graph.playHistoryRepository.save(
                        PlayHistoryEntity(
                            vodId = targetSession.vodId,
                            sourceUrl = targetSession.sourceUrl,
                            title = targetSession.title,
                            vodPic = targetSession.vodPic,
                            lastEpisodeName = targetSession.episodeName,
                            lastPositionMs = time,
                            durationMs = inlineKernel?.durationMs?.value ?: 0L,
                        ),
                    )
                }
            }
        }
    }

    // 退出播放：释放 native 资源并清空会话（回浏览态）；仅清理本页会话，防止延迟销毁误清新页会话
    // 释放走平台异步通道：stop/release 的 native 拆卸耗时秒级，同步执行会卡住返回/切页
    val closeInPage: () -> Unit = {
        inlineKernel?.let(::releasePageKernel)
        inlineKernel = null
        val currentSession = app.playerSession.value
        if (currentSession == null || currentSession.sourceUrl == target.sourceUrl) {
            app.closePlayer()
        }
    }

    // 以 target 为 key：详情 A→详情 B 切换时先销毁 A 的播放，避免旧内核与新页面错配
    DisposableEffect(target) {
        onDispose { closeInPage() }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 顶栏：仅返回（播放中返回=退出播放回浏览态；浏览态=回上一页）
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
            IconButton(onClick = { if (inPageSession != null) closeInPage() else app.back() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
        }
        when {
            uiState.loading -> LoadingView()
            uiState.error != null -> ErrorView(uiState.error!!, onRetry = { viewModel.load() })
            else -> DetailBody(
                Modifier.weight(1f), uiState, target, viewModel, app,
                inPageSession, inlineKernel, onExitPlayback = closeInPage,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailBody(
    modifier: Modifier,
    uiState: DetailUiState,
    target: Screen.Detail,
    viewModel: DetailViewModel,
    app: AppViewModel,
    session: PlaybackSession?,
    kernel: PlayerKernel?,
    onExitPlayback: () -> Unit,
) {
    val vod = uiState.vod ?: return
    val sources = remember(vod) { vod.parsePlaySources() }
    var selectedSource by remember(vod) { mutableStateOf(0) }
    val episodes = sources.getOrNull(selectedSource)?.episodes.orEmpty()
    val hasHistory = uiState.historyPositionMs > 5000
    // 浏览态选集弹窗（轻量 Compose 弹窗；播放态选集走控制层重量级弹出防被视频面遮挡）
    var showEpisodeDialog by remember { mutableStateOf(false) }

    // 播放态：左内嵌播放器 + 右信息/选集（对齐 Govin 点播播放页）
    if (session != null && kernel != null) {
        DetailPlayMode(
            modifier, vod, session, kernel, target, viewModel, app,
            uiState.isFavorite, onExitPlayback,
        )
        return
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        // 信息区：大海报 + 标题/操作/演职员
        Row(modifier = Modifier.fillMaxWidth()) {
            NetImage(
                url = vod.safePic,
                fallbackText = vod.vodName.ifBlank { target.title },
                modifier = Modifier
                    .width(300.dp)
                    .aspectRatio(0.72f)
                    .clip(RoundedCornerShape(8.dp)),
            )
            Spacer(Modifier.width(20.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = vod.vodName.ifBlank { target.title },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                // 操作排：蓝色圆形播放钮 + 播放文字 + 源胶囊
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    FilledIconButton(
                        onClick = { viewModel.play(selectedSource, 0, app) },
                        enabled = episodes.isNotEmpty(),
                        modifier = Modifier.size(48.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = "播放")
                    }
                    Text(
                        text = if (hasHistory) "继续播放" else "立即播放",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (sources.isNotEmpty()) {
                        // 源胶囊：蓝点 + 当前线路名
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primary),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = sourceName(sources[selectedSource], selectedSource),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
                // 功能排：收藏（桌面版支持的快捷操作）
                IconButton(onClick = { viewModel.toggleFavorite() }) {
                    Icon(
                        imageVector = if (uiState.isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        contentDescription = "收藏",
                        tint = if (uiState.isFavorite) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (hasHistory) {
                    Text(
                        text = "上次看到 ${java.util.concurrent.TimeUnit.MILLISECONDS.toMinutes(uiState.historyPositionMs)} 分钟",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (vod.vodActor.isNotBlank()) {
                    Text(
                        text = "演职员：${vod.vodActor}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (vod.vodDirector.isNotBlank()) {
                    Text(
                        text = "导演：${vod.vodDirector}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        // 简介：清洗 HTML 标签与实体后完整展示
        if (vod.vodContent.isNotBlank()) {
            Text(
                text = "简介：${cleanRichText(vod.vodContent)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
        }
        // 线路切换 + 选集
        if (sources.isEmpty()) {
            Text(
                "该剧集暂无播放线路",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(vertical = 4.dp),
            ) {
                sources.take(6).forEachIndexed { index, source ->
                    FilterChip(
                        selected = index == selectedSource,
                        onClick = { selectedSource = index },
                        label = { Text(sourceName(source, index)) },
                    )
                }
            }
            Text(
                "共 ${episodes.size} 集",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            // 选集入口：弹窗网格代替整页集数区
            AssistChip(
                onClick = { showEpisodeDialog = true },
                enabled = episodes.isNotEmpty(),
                label = { Text("选集") },
            )
            if (showEpisodeDialog) {
                Dialog(onDismissRequest = { showEpisodeDialog = false }) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "选集（共 ${episodes.size} 集）",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            Spacer(Modifier.height(10.dp))
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 420.dp)
                                    .verticalScroll(rememberScrollState()),
                            ) {
                                episodes.forEachIndexed { index, episode ->
                                    AssistChip(
                                        onClick = {
                                            showEpisodeDialog = false
                                            viewModel.play(selectedSource, index, app)
                                        },
                                        label = {
                                            Text(
                                                episode.name.ifBlank { "第${index + 1}集" },
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                style = MaterialTheme.typography.labelMedium,
                                            )
                                        },
                                        modifier = Modifier.width(110.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 清洗富文本简介：去 HTML 标签（转分段）、解码常见实体、压缩空白 */
private fun cleanRichText(raw: String): String = raw
    .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
    .replace(Regex("</p>", RegexOption.IGNORE_CASE), "\n")
    .replace(Regex("<[^>]*>"), "")
    .replace("&nbsp;", " ")
    .replace("&amp;", "&")
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&quot;", "\"")
    .replace(Regex("[ \\t]+"), " ")
    .split("\n")
    .joinToString("\n") { it.trim() }
    .replace(Regex("\n{2,}"), "\n")
    .trim()

/** 播放态布局：左内嵌播放器 + 右信息列（标题/操作/演职员/简介），右列独立滚动 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailPlayMode(
    modifier: Modifier,
    vod: Vod,
    session: PlaybackSession,
    kernel: PlayerKernel,
    target: Screen.Detail,
    viewModel: DetailViewModel,
    app: AppViewModel,
    isFavorite: Boolean,
    onExitPlayback: () -> Unit,
) {
    val graph = app.graph
    // 上一集/下一集（设置开关；索引由会话携带，找不到时（-1）不展示）
    val showPrevNext = graph.prefs.getBoolean(PrefsKeys.KEY_SHOW_PREV_NEXT, true)
    val episodeIndex = session.currentEpisodeIndex
    val onPrevEpisode = if (showPrevNext && episodeIndex > 0) {
        { viewModel.play(session.currentSourceIndex, episodeIndex - 1, app) }
    } else {
        null
    }
    val onNextEpisode = if (showPrevNext && episodeIndex in 0 until session.episodes.lastIndex) {
        { viewModel.play(session.currentSourceIndex, episodeIndex + 1, app) }
    } else {
        null
    }
    // 播放态订阅（直接读 StateFlow.value 不会触发重组，图标无法切换）
    val isPlaying by kernel.isPlaying.collectAsState()
    // 页内全屏状态（铺满整个应用窗口：隐藏侧栏、视频区占满）
    val fullscreen by app.detailFullscreen.collectAsState()
    val kernelId by app.kernelId.collectAsState()
    // 控制层动作集（平台 actual 回传；Compose 信息列调起重量级弹出面板）
    var playerActions by remember { mutableStateOf<InPagePlayerActions?>(null) }
    val apiName = graph.apiSourceRepository.sources.value
        .firstOrNull { it.url == target.sourceUrl }?.name ?: "当前源"
    val surfaceConfig = InPagePlayerConfig(
        qualities = session.qualityOptions,
        currentQn = session.currentQn,
        onQualitySelected = { qn -> app.switchBiliQuality(session, qn, kernel.timeMs.value) },
        onPrevEpisode = onPrevEpisode,
        onNextEpisode = onNextEpisode,
        title = listOf(session.title, session.episodeName)
            .filter { it.isNotBlank() }
            .joinToString(" "),
        onBack = onExitPlayback,
        episodes = session.episodes,
        currentEpisodeIndex = session.currentEpisodeIndex,
        onEpisodeSelected = { index -> viewModel.play(session.currentSourceIndex, index, app) },
        sources = session.playSources.mapIndexed { index, source -> sourceName(source, index) },
        currentSourceIndex = session.currentSourceIndex,
        onSourceSelected = { index -> viewModel.play(index, 0, app) },
        sourceSubtexts = List(session.playSources.size) { apiName },
        sourceUpdateTexts = session.playSources.map { source ->
            if (source.episodes.size > 1) {
                "更新至：第 ${source.episodes.size} 集"
            } else {
                "更新至：全集"
            }
        },
        shareUrl = session.playUrl,
        kernelId = kernelId,
        onKernelSelected = { id -> app.switchKernel(id) },
        introMark = IntroMark.Mark(session.vodId, session.sourceUrl, session.episodeName)
            .takeIf { session.vodId != 0 && session.sourceUrl.isNotBlank() },
        onDownloadEpisode = { index ->
            val name = session.episodes.getOrNull(index) ?: "第${index + 1}集"
            app.showMessage("「$name」下载功能开发中，敬请期待")
        },
    )

    // 选集：页内统一走控制层重量级弹出，避免 Compose 弹窗被视频面遮挡
    BoxWithConstraints(modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        // 视频面（16:9 固定 + 覆盖控制层；全屏时铺满整个窗口）
        val playerBox: @Composable () -> Unit = {
            Box(
                modifier = if (fullscreen) {
                    Modifier.fillMaxSize().background(Color.Black)
                } else {
                    Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black)
                },
            ) {
                InPagePlayerSurface(
                    kernel = kernel,
                    app = app,
                    scope = PagePlayerScope.DETAIL,
                    config = surfaceConfig,
                    modifier = Modifier.fillMaxSize(),
                    onActionsReady = { playerActions = it },
                )
            }
        }
        // 信息区（两行操作排 + 标题 + 演职员 + 简介），单/双列共用
        val infoContent: @Composable (Modifier) -> Unit = { infoModifier ->
            Column(
                modifier = infoModifier,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // 操作排一：全屏 + 红圆播放/暂停 + 选集 + 源胶囊（点按弹出源列表）
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    IconButton(onClick = { app.toggleDetailFullscreen() }) {
                        Icon(Icons.Filled.Fullscreen, contentDescription = "全屏")
                    }
                    FilledIconButton(
                        onClick = { kernel.togglePlayPause() },
                        modifier = Modifier.size(44.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = "播放/暂停",
                        )
                    }
                    AssistChip(
                        onClick = { playerActions?.showEpisodeSheet() },
                        enabled = session.episodes.isNotEmpty(),
                        label = { Text("选集") },
                    )
                    if (session.playSources.isNotEmpty()) {
                        // 源胶囊：蓝点 + 当前线路名，点按弹出源列表面板
                        Surface(
                            onClick = { playerActions?.showSourceSheet() },
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primary),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = session.playSources.getOrNull(session.currentSourceIndex)
                                        ?.let { sourceName(it, session.currentSourceIndex) }
                                        ?: "当前源",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
                // 操作排二：片头/片尾(占位) 定时 多线程 分享 投屏(占位) 收藏
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { app.showMessage("片头/片尾跳过功能开发中，敬请期待") }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    ) {
                        Icon(
                            Icons.Filled.RocketLaunch,
                            contentDescription = "片头/片尾",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "片头/片尾",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { playerActions?.showTimerSheet() }) {
                        Icon(
                            Icons.Filled.Timer,
                            contentDescription = "定时关闭",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { playerActions?.showMultiThreadSheet() }) {
                        Icon(
                            Icons.Filled.Tune,
                            contentDescription = "多线程配置",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { playerActions?.showShareSheet() }) {
                        Icon(
                            Icons.Filled.Share,
                            contentDescription = "分享",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { app.showMessage("投屏功能开发中，敬请期待") }) {
                        Icon(
                            Icons.Filled.Cast,
                            contentDescription = "投屏",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { viewModel.toggleFavorite() }) {
                        Icon(
                            imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                            contentDescription = "收藏",
                            tint = if (isFavorite) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
                // 标题（对照参考图位于操作排下方）
                Text(
                    text = session.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (vod.vodActor.isNotBlank()) {
                    Text(
                        text = "演职员：${vod.vodActor}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (vod.vodDirector.isNotBlank()) {
                    Text(
                        text = "导演：${vod.vodDirector}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (vod.vodContent.isNotBlank()) {
                    Text(
                        text = "简介：${cleanRichText(vod.vodContent)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        // 自适应排版：宽屏双列（播放器左上 + 右侧信息列）；窄屏单列（播放器上、信息下滚动）
        // 全屏时视频区铺满、隐藏信息列。播放器槽位恒定在 Row 首位（跨 900dp 断点只变修饰符、
        // 不换组合分支），避免窗口缩放时视频面被销毁重建（wid 重挂）导致播放中断
        val isWide = maxWidth >= 900.dp
        val scrollState = rememberScrollState()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .then(if (!isWide && !fullscreen) Modifier.verticalScroll(scrollState) else Modifier),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (isWide) Modifier.fillMaxHeight() else Modifier),
            ) {
                Box(
                    modifier = when {
                        fullscreen -> Modifier.fillMaxSize()
                        isWide -> Modifier.weight(1f).fillMaxHeight()
                        else -> Modifier.fillMaxWidth()
                    },
                ) {
                    playerBox()
                }
                if (isWide && !fullscreen) {
                    Spacer(Modifier.width(16.dp))
                    infoContent(
                        Modifier
                            .width(360.dp)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState()),
                    )
                }
            }
            if (!isWide && !fullscreen) {
                infoContent(Modifier.fillMaxWidth().padding(bottom = 24.dp))
            }
        }
    }
}
