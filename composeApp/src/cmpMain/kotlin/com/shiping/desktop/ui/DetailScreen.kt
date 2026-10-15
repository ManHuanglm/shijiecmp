package com.shiping.desktop.ui

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Cast
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.RocketLaunch
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.shiping.desktop.data.model.ApiSourceEntity
import com.shiping.desktop.data.model.FavoriteEntity
import com.shiping.desktop.data.model.PlayHistoryEntity
import com.shiping.desktop.data.model.Vod
import com.shiping.desktop.player.IntroMark
import com.shiping.desktop.player.isDirectStreamUrl
import com.shiping.desktop.ui.player.PagePlayerModeState
import com.shiping.desktop.ui.common.ErrorView
import com.shiping.desktop.ui.common.EpisodeDownloadRequest
import com.shiping.desktop.ui.common.LoadingView
import com.shiping.desktop.ui.common.NetImage
import com.shiping.desktop.ui.common.VideoCard
import com.shiping.desktop.ui.common.isEpisodeDownloadActive
import com.shiping.desktop.ui.common.isEpisodeDownloadSupported
import com.shiping.desktop.ui.common.isUnsupportedDownloadUrl
import com.shiping.desktop.ui.common.submitEpisodeDownload
import com.shiping.shared.graph.AppGraph
import com.shiping.shared.prefs.PrefsKeys
import com.shiping.shared.player.PlayerKernel
import com.shiping.desktop.util.Constants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** 自动连播末尾判定余量（毫秒；内核 500ms 轮询，结束时进度≈总时长） */
private const val AUTO_NEXT_END_SLACK_MS = 100L

/** 跨源同名搜索每批源数（滚动触底分批加载，避免一次性打满全部源） */
private const val CROSS_BATCH_SIZE = 8

/** 跨源命中数低于该值且还有源时自动续拉下一批（保证推荐排可见性） */
private const val CROSS_MIN_HITS = 4

/** 播放容错：当前线路自动重试次数上限（超过后换线路） */
private const val FAILOVER_RETRY_MAX = 2

/** 播放容错监控：起播无缓冲无播放的空转超时 */
private const val FAILOVER_STALL_TIMEOUT_MS = 10_000

/** 播放容错监控：起播持续缓冲不出画面的超时 */
private const val FAILOVER_BUFFER_TIMEOUT_MS = 15_000

/** 播放容错监控：播放后断流恢复窗口超时（判死流） */
private const val FAILOVER_RECOVER_STALL_MS = 15_000

/** 播放容错监控：单次会话监控总上限 */
private const val FAILOVER_MAX_WAIT_MS = 60_000

/** 播放容错监控轮询间隔 */
private const val FAILOVER_POLL_INTERVAL_MS = 500L

/** 播放结束探测快照（自动连播判定用） */
private data class EndProbe(
    val isPlaying: Boolean,
    val timeMs: Long,
    val durationMs: Long,
    val isBuffering: Boolean,
)

/** 详情页 UI 状态 */
data class DetailUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val vod: Vod? = null,
    val isFavorite: Boolean = false,
    val historyPositionMs: Long = 0,
    /** 其他源同名影片（分批搜索追加，按源去重各取一条） */
    val crossMatches: List<CrossSourceMatch> = emptyList(),
    /** 跨源搜索分批加载中 */
    val crossLoading: Boolean = false,
    /** 已完成搜索的批数（下一批取队列 offset 用） */
    val crossBatchIndex: Int = 0,
    /** 是否还有未搜索的源 */
    val crossHasMore: Boolean = false,
)

/** 其他源同名影片匹配（点击跳转对应源详情/播放） */
data class CrossSourceMatch(
    val sourceId: Long,
    val sourceName: String,
    val sourceUrl: String,
    val vod: Vod,
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
                    // 详情就绪后启动其他源同名影片搜索（源列表弹层追加 + 底部推荐排）
                    startCrossSearch()
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

    /**
     * 下载指定集：解析直链（复用播发链路 resolvePlayUrl）后交平台下载器导出。
     * 网页解析流（parse=1 且非直链）与 DASH/代理流无法离线导出，明确提示。
     */
    fun downloadEpisode(sourceIndex: Int, episodeIndex: Int, app: AppViewModel) {
        if (!isEpisodeDownloadSupported) {
            app.showMessage("当前平台不支持下载")
            return
        }
        val vod = _uiState.value.vod ?: return
        val episode = vod.parsePlaySources()
            .getOrNull(sourceIndex)?.episodes?.getOrNull(episodeIndex) ?: return
        val episodeName = episode.name.ifBlank { "第${episodeIndex + 1}集" }
        scope.launch {
            app.showMessage("「$episodeName」解析下载地址中…")
            runCatching {
                val info = graph.vodRepository.resolvePlayUrl(target.sourceUrl, episode.url)
                val playUrl = info?.url ?: episode.url
                when {
                    info != null && info.parse == 1 && !isDirectStreamUrl(info.url) ->
                        "该集为网页解析流，暂不支持下载"
                    isUnsupportedDownloadUrl(playUrl) ->
                        "该播放地址（DASH/代理流）暂不支持下载"
                    else -> submitEpisodeDownload(
                        EpisodeDownloadRequest(
                            vodId = vod.vodId,
                            sourceUrl = target.sourceUrl,
                            title = vod.vodName.ifBlank { target.title },
                            vodPic = vod.safePic,
                            episodeName = episodeName,
                            playUrl = playUrl,
                            headers = info?.headers ?: emptyMap(),
                        ),
                    ) ?: "当前平台不支持下载"
                }
            }.onSuccess { message -> app.showMessage(message) }
                .onFailure { app.showMessage("下载解析失败：${it.message ?: "未知错误"}") }
        }
    }

    /**
     * 批量下载指定集（选集弹窗勾选模式/全部下载共用）：串行逐集解析直链并入队
     * （活跃任务去重；网页解析流/DASH 跳过；单集异常不中断整批），结束汇总提示。
     */
    fun downloadEpisodes(sourceIndex: Int, indexes: List<Int>, app: AppViewModel) {
        if (!isEpisodeDownloadSupported) {
            app.showMessage("当前平台不支持下载")
            return
        }
        val vod = _uiState.value.vod ?: return
        val source = vod.parsePlaySources().getOrNull(sourceIndex) ?: return
        val targets = indexes.mapNotNull { index ->
            source.episodes.getOrNull(index)?.let { index to it }
        }
        if (targets.isEmpty()) return
        scope.launch {
            var queued = 0
            var skipped = 0
            targets.forEach { (episodeIndex, episode) ->
                val episodeName = episode.name.ifBlank { "第${episodeIndex + 1}集" }
                runCatching {
                    if (isEpisodeDownloadActive(vod.vodId, target.sourceUrl, episodeName)) {
                        skipped++
                        return@runCatching
                    }
                    app.showMessage("批量下载 ${queued + skipped + 1}/${targets.size}：「$episodeName」解析中…")
                    val info = graph.vodRepository.resolvePlayUrl(target.sourceUrl, episode.url)
                    val playUrl = info?.url ?: episode.url
                    when {
                        info != null && info.parse == 1 && !isDirectStreamUrl(info.url) -> skipped++
                        isUnsupportedDownloadUrl(playUrl) -> skipped++
                        else -> {
                            val message = submitEpisodeDownload(
                                EpisodeDownloadRequest(
                                    vodId = vod.vodId,
                                    sourceUrl = target.sourceUrl,
                                    title = vod.vodName.ifBlank { target.title },
                                    vodPic = vod.safePic,
                                    episodeName = episodeName,
                                    playUrl = playUrl,
                                    headers = info?.headers ?: emptyMap(),
                                ),
                            )
                            if (message != null) queued++ else skipped++
                        }
                    }
                }.onFailure { skipped++ }
            }
            app.showMessage("批量下载完成：新加入 $queued 集，跳过 $skipped 集")
        }
    }

    /** 批量下载全部集 */
    fun downloadAllEpisodes(sourceIndex: Int, app: AppViewModel) {
        val episodes = _uiState.value.vod?.parsePlaySources()?.getOrNull(sourceIndex)?.episodes ?: return
        downloadEpisodes(sourceIndex, episodes.indices.toList(), app)
    }

    // region 其他源同名影片（跨源搜索）

    /** 待搜索源队列（load() 时重建，分批并发消费） */
    private var crossQueue: List<ApiSourceEntity> = emptyList()

    /** 批内搜索协程（防重入） */
    private var crossJob: Job? = null

    /** 标题归一化：去空白与 []()【】 括注（[全集]/(2023)/【高清】等），提升跨源同名命中率 */
    private fun normalizeTitle(raw: String): String =
        raw.replace(Regex("\\s+"), "").replace(Regex("[\\[（(【].*?[\\]）)】]]"), "").trim()

    /** 启动跨源同名搜索：排除当前源，取启用+可搜索源分批并发（详情重载时重置队列），立即加载第一批 */
    private fun startCrossSearch() {
        val name = normalizeTitle(_uiState.value.vod?.vodName ?: target.title)
        if (name.isEmpty()) return
        crossQueue = graph.apiSourceRepository.sources.value
            .filter { it.enabled && it.searchable && it.url != target.sourceUrl }
        _uiState.value = _uiState.value.copy(
            crossMatches = emptyList(),
            crossLoading = false,
            crossBatchIndex = 0,
            crossHasMore = crossQueue.isNotEmpty(),
        )
        // 首批自动加载（此后批间自动续拉 + UI 触底补充触发）
        VmLog.i("CrossSearch", "start name=$name queue=${crossQueue.size}")
        loadNextCrossBatch()
    }

    /** 搜索下一批源（滚动触底调用）：批内并发，命中归一化同名影片各取一条按源追加 */
    fun loadNextCrossBatch() {
        val name = normalizeTitle(_uiState.value.vod?.vodName ?: target.title)
        val state = _uiState.value
        if (name.isEmpty() || crossJob?.isActive == true || !state.crossHasMore || state.crossLoading) return
        val batch = crossQueue.drop(state.crossBatchIndex * CROSS_BATCH_SIZE).take(CROSS_BATCH_SIZE)
        if (batch.isEmpty()) {
            _uiState.value = state.copy(crossHasMore = false)
            return
        }
        _uiState.value = state.copy(crossLoading = true)
        crossJob = scope.launch {
            val hits = batch.mapNotNull { source ->
                val result = runCatching { graph.vodRepository.searchOneSource(source, name) }.getOrNull()
                result?.results
                    ?.firstOrNull {
                        val other = normalizeTitle(it.vodName)
                        other == name || (other.length >= 2 && other.contains(name)) || (name.length >= 2 && name.contains(other))
                    }
                    ?.let { CrossSourceMatch(source.id, source.name, source.url, it) }
            }
            val newMatches = _uiState.value.crossMatches + hits
            val newIndex = _uiState.value.crossBatchIndex + 1
            val hasMore = newIndex * CROSS_BATCH_SIZE < crossQueue.size
            _uiState.value = _uiState.value.copy(
                crossMatches = newMatches,
                crossLoading = false,
                crossBatchIndex = newIndex,
                crossHasMore = hasMore,
            )
            // 命中数不足且还有源：自动续拉下一批（推荐排持续有内容/加载态，避免空窗期不再触发）
            VmLog.i("CrossSearch", "batch=${newIndex} hits=${hits.size} total=${newMatches.size} hasMore=$hasMore")
            // 当前批已收尾，先解除防重入标记再续拉（否则被自身 isActive 检查拒绝）
            crossJob = null
            if (newMatches.size < CROSS_MIN_HITS && hasMore) loadNextCrossBatch()
        }
    }

    // endregion
}

/**
 * 详情页双态共用同一骨架（对齐参考 App）：
 * 顶部块浏览态为海报区（大海报 + 标题），点播放后原地切换为内嵌播放器；
 * 顶部块下方的信息区（操作排一/二 + 标题 + 演职员 + 简介）两态完全一致。
 */
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

    // 统一模式状态机：绑定页内内核后投影 PlayerUiState（全屏真源仍为 app.detailFullscreen）
    val modeState = remember(target) { PagePlayerModeState(scope, app.detailFullscreen) }
    LaunchedEffect(inlineKernel) {
        inlineKernel?.let { modeState.bind(it, PagePlayerScope.DETAIL) }
    }

    // 起播去重：同会话序号不重复 loadfile（重播同集 seq 递增必然触发；会话风暴不重复起播）
    var lastPlayedSeq by remember { mutableStateOf(0L) }

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

    // 播放容错：各线路的重试计数（会话重试同线路递增；换线路自然复位）
    val failoverRetries = remember { mutableStateMapOf<Int, Int>() }

    // 自动起播（播放容错换源/跨源点击进入）：详情就绪后起播第一个有集的线路
    LaunchedEffect(uiState.vod, target.autoPlay) {
        if (!target.autoPlay || uiState.vod == null || inPageSession != null) return@LaunchedEffect
        val vod = uiState.vod ?: return@LaunchedEffect
        val firstLine = vod.parsePlaySources().indexOfFirst { it.episodes.isNotEmpty() }
        if (firstLine >= 0) {
            VmLog.i("Detail", "autoPlay: 自动起播 ${vod.vodName} 线路${firstLine + 1}")
            viewModel.play(firstLine, 0, app)
        }
    }

    /**
     * 播放容错推进（失败后按层级自动切换，直到播放成功）：
     * 当前线路重试（[FAILOVER_RETRY_MAX] 次）→ 同源换下一线路（保持当前集）→
     * 换其他源同名影片（新详情页自动起播，携带已失败源集合防回跳死循环）。
     */
    val failoverPlay: (PlaybackSession) -> Unit = { s ->
        val retried = failoverRetries[s.currentSourceIndex] ?: 0
        if (retried < FAILOVER_RETRY_MAX) {
            failoverRetries[s.currentSourceIndex] = retried + 1
            app.showMessage("播放失败，自动重试（${retried + 1}/$FAILOVER_RETRY_MAX）…")
            viewModel.play(s.currentSourceIndex, s.currentEpisodeIndex, app)
        } else {
            failoverRetries.clear()
            val nextLine = s.currentSourceIndex + 1
            if (nextLine < s.playSources.size) {
                val nextEpisodes = s.playSources[nextLine].episodes.size
                val episodeIndex = s.currentEpisodeIndex.coerceIn(0, (nextEpisodes - 1).coerceAtLeast(0))
                app.showMessage("重试失败，自动切换线路：${sourceName(s.playSources[nextLine], nextLine)}")
                viewModel.play(nextLine, episodeIndex, app)
            } else {
                val match = uiState.crossMatches.firstOrNull { it.sourceUrl !in target.failedSourceUrls }
                if (match == null) {
                    app.showMessage("各线路均播放失败，其他源暂无可用结果，请稍后手动切换")
                } else {
                    app.showMessage("各线路均失败，自动换源：${match.sourceName}")
                    app.push(
                        Screen.Detail(
                            match.vod.vodId,
                            match.sourceUrl,
                            match.vod.vodName,
                            match.vod.safePic,
                            autoPlay = true,
                            failedSourceUrls = target.failedSourceUrls + setOf(s.sourceUrl),
                        ),
                    )
                }
            }
        }
    }

    // 播放容错监控（参考 Tv 容错）：报错/起播超时/持续缓冲/断流 → failoverPlay
    LaunchedEffect(inPageSession, inlineKernel) {
        val s = inPageSession ?: return@LaunchedEffect
        val kernel = inlineKernel ?: return@LaunchedEffect
        var everPlayed = false
        var elapsed = 0L
        var noPlayMs = 0L
        var bufferOnlyMs = 0L
        while (elapsed < FAILOVER_MAX_WAIT_MS) {
            delay(FAILOVER_POLL_INTERVAL_MS)
            elapsed += FAILOVER_POLL_INTERVAL_MS
            if (kernel.isPlaying.value) {
                failoverRetries.clear()
                return@LaunchedEffect
            }
            if (!kernel.errorText.value.isNullOrBlank() || !kernel.initError.value.isNullOrBlank()) break
            // 播放后用户暂停（非缓冲）不自动容错，避免手动暂停被切源
            if (everPlayed && !kernel.isBuffering.value) return@LaunchedEffect
            noPlayMs += FAILOVER_POLL_INTERVAL_MS
            if (kernel.isBuffering.value) bufferOnlyMs += FAILOVER_POLL_INTERVAL_MS
            // 起播持续转圈（分片 404 等源故障）：按缓冲阈值判失败
            if (!everPlayed && bufferOnlyMs >= FAILOVER_BUFFER_TIMEOUT_MS) break
            // 播放后断流（恢复窗口超时判死流）
            if (everPlayed && noPlayMs >= FAILOVER_RECOVER_STALL_MS) break
            // 无缓冲也无播放的空转（连接挂起等）
            if (!everPlayed && noPlayMs >= FAILOVER_STALL_TIMEOUT_MS) break
        }
        // 手动操作期间会话可能已流转：仅当仍是被监控会话时推进容错
        if (app.playerSession.value?.seq != s.seq) return@LaunchedEffect
        VmLog.i("Detail", "播放容错触发: ${s.episodeName} err=${kernel.errorText.value} initErr=${kernel.initError.value}")
        failoverPlay(s)
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

    // 播放结束自动连播下集（手机端）：媒体播到末尾（进度到达总时长、未在缓冲）自动切下一集；
    // 同会话仅触发一次（换集后生成新会话并重启本协程，续播判定不受影响）
    LaunchedEffect(inPageSession, inlineKernel) {
        val targetSession = inPageSession ?: return@LaunchedEffect
        val kernel = inlineKernel ?: return@LaunchedEffect
        if (!isPhonePlatform) return@LaunchedEffect
        val nextIndex = targetSession.currentEpisodeIndex + 1
        if (nextIndex !in targetSession.episodes.indices) return@LaunchedEffect
        var wasPlaying = false
        combine(
            kernel.isPlaying,
            kernel.timeMs,
            kernel.durationMs,
            kernel.isBuffering,
        ) { playing, timeMs, durationMs, buffering ->
            EndProbe(playing, timeMs, durationMs, buffering)
        }.collect { probe ->
            when {
                probe.isPlaying -> wasPlaying = true
                wasPlaying && !probe.isBuffering && probe.durationMs > 0 &&
                    probe.timeMs >= probe.durationMs - AUTO_NEXT_END_SLACK_MS -> {
                    wasPlaying = false
                    VmLog.i("Player", "播放结束自动连播：${targetSession.episodeName} -> 第${nextIndex + 1}集")
                    viewModel.play(targetSession.currentSourceIndex, nextIndex, app)
                }
            }
        }
    }

    // 退出播放：释放 native 资源并清空会话（回浏览态海报区）；仅清理本页会话，防止延迟销毁误清新页会话
    // 释放走平台异步通道：stop/release 的 native 拆卸耗时秒级，同步执行会卡住返回/切页
    val closeInPage: () -> Unit = {
        modeState.unbind()
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
        // 顶栏：仅浏览态显示返回；播放态返回由播放器控制层承载
        if (inPageSession == null) {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                IconButton(onClick = { app.back() }) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                }
            }
        }
        when {
            uiState.loading -> LoadingView()
            uiState.error != null -> ErrorView(uiState.error!!, onRetry = { viewModel.load() })
            else -> DetailBody(
                Modifier.weight(1f), uiState, target, viewModel, app,
                inPageSession, inlineKernel, onExitPlayback = closeInPage,
                modeState = modeState,
            )
        }
    }
}

/**
 * 详情页主体：顶部块（浏览态=海报区/播放态=播放器）+ 共用信息区。
 * 窄屏单列滚动（信息区在顶部块下方）；宽屏双列（顶部块左、信息区右）。
 */
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
    modeState: PagePlayerModeState,
) {
    val vod = uiState.vod ?: return
    val sources = remember(vod) { vod.parsePlaySources() }
    var selectedSource by remember(vod) { mutableStateOf(0) }
    val episodes = sources.getOrNull(selectedSource)?.episodes.orEmpty()
    val hasHistory = uiState.historyPositionMs > 5000
    // 浏览态选集/线路弹窗（轻量 Compose 弹窗；播放态选集走控制层重量级弹出防被视频面遮挡）
    var showEpisodeDialog by remember { mutableStateOf(false) }
    var showSourceDialog by remember { mutableStateOf(false) }
    // 选集弹窗的批量下载勾选模式
    var downloadSelectionMode by remember { mutableStateOf(false) }
    val selectedDownloadIndexes = remember { mutableStateListOf<Int>() }

    if (session != null && kernel != null) {
        // 播放态：顶部海报区就地切换为内嵌播放器，信息区与浏览态共用
        DetailPlayMode(
            modifier, vod, session, kernel, target, viewModel, app,
            uiState.isFavorite, onExitPlayback, modeState,
        )
    } else {
        // 浏览态：海报区占顶部块，信息区与播放态共用（点播放后海报区换成播放器、信息区纹丝不动）
        DetailPageScaffold(
            modifier = modifier,
            fullscreen = false,
            topBlock = { _, _ ->
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    NetImage(
                        url = vod.safePic,
                        fallbackText = vod.vodName.ifBlank { target.title },
                        modifier = Modifier
                            // 海报固定占窗口宽 50%
                            .fillMaxWidth(0.5f)
                            .aspectRatio(0.72f)
                            .clip(RoundedCornerShape(8.dp)),
                    )
                    Spacer(Modifier.width(20.dp))
                    Text(
                        text = vod.vodName.ifBlank { target.title },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        // 弹性占剩余宽度，片名自动换行完整显示
                        modifier = Modifier.weight(1f),
                    )
                }
            },
            infoContent = { infoModifier ->
                DetailInfoSection(
                    modifier = infoModifier,
                    vod = vod,
                    title = vod.vodName.ifBlank { target.title },
                    sourceLabel = sources.getOrNull(selectedSource)
                        ?.let { sourceName(it, selectedSource) } ?: "暂无线路",
                    playbackState = false,
                    isPlayingNow = false,
                    isFavorite = uiState.isFavorite,
                    crossMatches = uiState.crossMatches,
                    crossLoading = uiState.crossLoading,
                    crossHasMore = uiState.crossHasMore,
                    onCrossClick = { match ->
                        app.push(Screen.Detail(match.vod.vodId, match.sourceUrl, match.vod.vodName, match.vod.safePic, autoPlay = true))
                    },
                    onCrossLoadMore = viewModel::loadNextCrossBatch,
                    historyHint = if (hasHistory) {
                        "上次看到 ${java.util.concurrent.TimeUnit.MILLISECONDS.toMinutes(uiState.historyPositionMs)} 分钟"
                    } else {
                        null
                    },
                    episodesEnabled = episodes.isNotEmpty(),
                    sourcesAvailable = sources.isNotEmpty(),
                    onFullscreen = {
                        // 全屏播放：起播并直接进入页内全屏
                        viewModel.play(selectedSource, 0, app)
                        app.toggleDetailFullscreen()
                    },
                    onPlayToggle = { viewModel.play(selectedSource, 0, app) },
                    onEpisodes = { showEpisodeDialog = true },
                    onSource = { showSourceDialog = true },
                    onTimer = { app.showMessage("开始播放后可使用该功能") },
                    onMultiThread = { app.showMessage("开始播放后可使用该功能") },
                    onShare = { app.showMessage("开始播放后可使用该功能") },
                    onFavorite = { viewModel.toggleFavorite() },
                    onToast = { app.showMessage(it) },
                )
            },
        )
        // 浏览态选集弹窗（统一图1布局：线路 Tab + 图标条 + 双列网格；勾选模式批量下载）
        if (showEpisodeDialog) {
            // 倒序展示（图标条 ↑↓ 切换）
            var descending by remember { mutableStateOf(false) }
            Dialog(onDismissRequest = {
                showEpisodeDialog = false
                downloadSelectionMode = false
                selectedDownloadIndexes.clear()
            }) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            // 线路 Tab（下划线式，点击切换线路弹窗）
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { showSourceDialog = true },
                            ) {
                                Text(
                                    text = sources.getOrNull(selectedSource)
                                        ?.let { sourceName(it, selectedSource) } ?: "线路",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(Modifier.height(3.dp))
                                Box(
                                    Modifier
                                        .width(28.dp)
                                        .height(3.dp)
                                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)),
                                )
                            }
                            Spacer(Modifier.weight(1f))
                            // 图标条（对齐图2）：选择模式 / 倒序 / 全选 / 下载全 / 关闭
                            IconButton(onClick = {
                                downloadSelectionMode = !downloadSelectionMode
                                selectedDownloadIndexes.clear()
                            }) {
                                Icon(
                                    Icons.Outlined.RadioButtonUnchecked,
                                    contentDescription = if (downloadSelectionMode) "退出选择" else "选择要下载的集",
                                    tint = if (downloadSelectionMode) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            }
                            IconButton(onClick = { descending = !descending }) {
                                Icon(
                                    Icons.Outlined.SwapVert,
                                    contentDescription = if (descending) "正序" else "倒序",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (downloadSelectionMode) {
                                IconButton(onClick = {
                                    if (selectedDownloadIndexes.size == episodes.size) {
                                        selectedDownloadIndexes.clear()
                                    } else {
                                        selectedDownloadIndexes.clear()
                                        selectedDownloadIndexes.addAll(episodes.indices)
                                    }
                                }) {
                                    Icon(
                                        Icons.Outlined.SelectAll,
                                        contentDescription = if (selectedDownloadIndexes.size == episodes.size) "清空" else "全选",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                IconButton(
                                    enabled = selectedDownloadIndexes.isNotEmpty(),
                                    onClick = {
                                        val indexes = selectedDownloadIndexes.sorted()
                                        downloadSelectionMode = false
                                        selectedDownloadIndexes.clear()
                                        showEpisodeDialog = false
                                        viewModel.downloadEpisodes(selectedSource, indexes, app)
                                    },
                                ) {
                                    Icon(
                                        Icons.Outlined.Download,
                                        contentDescription = "下载全",
                                        tint = if (selectedDownloadIndexes.isEmpty()) {
                                            MaterialTheme.colorScheme.outline
                                        } else {
                                            MaterialTheme.colorScheme.primary
                                        },
                                    )
                                }
                            }
                            IconButton(onClick = {
                                showEpisodeDialog = false
                                downloadSelectionMode = false
                                selectedDownloadIndexes.clear()
                            }) {
                                Icon(Icons.Outlined.Close, contentDescription = "关闭", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (downloadSelectionMode) {
                            Text(
                                text = "已选 ${selectedDownloadIndexes.size} 集（点击集数勾选，选完点「下载全」）",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 4.dp),
                            )
                        }
                        // 双列网格（图1）
                        val orderedIndexes =
                            if (descending) episodes.indices.reversed().toList() else episodes.indices.toList()
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(2),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                        ) {
                            items(orderedIndexes, key = { it }) { index ->
                                val episode = episodes.getOrNull(index)
                                val picked = index in selectedDownloadIndexes
                                Text(
                                    text = (if (downloadSelectionMode && picked) "✓ " else "") +
                                        episode?.name?.ifBlank { "第${index + 1}集" }?.ifBlank { "第${index + 1}集" }
                                            ?: "第${index + 1}集",
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable {
                                            if (downloadSelectionMode) {
                                                if (picked) selectedDownloadIndexes.remove(index)
                                                else selectedDownloadIndexes.add(index)
                                            } else {
                                                showEpisodeDialog = false
                                                viewModel.play(selectedSource, index, app)
                                            }
                                        }
                                        .background(
                                            when {
                                                downloadSelectionMode && picked -> MaterialTheme.colorScheme.primaryContainer
                                                else -> MaterialTheme.colorScheme.surfaceVariant
                                            },
                                            RoundedCornerShape(10.dp),
                                        )
                                        .padding(vertical = 12.dp, horizontal = 4.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
        // 浏览态线路切换弹窗
        if (showSourceDialog) {
            Dialog(onDismissRequest = { showSourceDialog = false }) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "播放线路",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(10.dp))
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 420.dp)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            sources.forEachIndexed { index, source ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable {
                                            selectedSource = index
                                            showSourceDialog = false
                                        }
                                        .padding(horizontal = 8.dp, vertical = 10.dp),
                                ) {
                                    Text(
                                        text = sourceName(source, index),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (index == selectedSource) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        },
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(
                                        text = if (source.episodes.size > 1) {
                                            "更新至：第 ${source.episodes.size} 集"
                                        } else {
                                            "更新至：全集"
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    if (index == selectedSource) {
                                        Icon(
                                            Icons.Outlined.Check,
                                            contentDescription = "当前线路",
                                            tint = MaterialTheme.colorScheme.primary,
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
}

/**
 * 详情页共用骨架：顶部块 + 信息区，宽屏双列、窄屏单列滚动。
 * 顶部块接收 BoxWithConstraints 的宽高约束（播放器按视频比例自适应高度需要）；
 * 全屏时顶部块铺满、隐藏信息区。跨 900dp 断点只变修饰符、不换组合分支，
 * 避免窗口缩放时顶部块（视频面）被销毁重建导致播放中断。
 */
@Composable
private fun DetailPageScaffold(
    modifier: Modifier,
    fullscreen: Boolean,
    topBlock: @Composable (maxWidth: Dp, maxHeight: Dp) -> Unit,
    infoContent: @Composable (Modifier) -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val isWide = maxWidth >= 900.dp
        // 先落地约束值：嵌套 Box 的内容 lambda 里不能隐式访问外层 BoxWithConstraintsScope 属性
        val constraintWidth = maxWidth
        val constraintHeight = maxHeight
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
                    topBlock(constraintWidth, constraintHeight)
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

/**
 * 详情信息区（操作排一/二 + 标题 + 演职员 + 简介）：浏览态/播放态共用。
 * 浏览态：全屏=全屏播放、播放钮=起播、选集/线路走轻量弹窗、定时等多线程提示先播放；
 * 播放态：全屏=页内全屏、播放钮=暂停/恢复、选集/线路/定时等走控制层重量级面板。
 */
@Composable
private fun DetailInfoSection(
    modifier: Modifier,
    vod: Vod,
    title: String,
    sourceLabel: String,
    playbackState: Boolean,
    isPlayingNow: Boolean,
    isFavorite: Boolean,
    historyHint: String?,
    episodesEnabled: Boolean,
    sourcesAvailable: Boolean,
    crossMatches: List<CrossSourceMatch>,
    crossLoading: Boolean,
    crossHasMore: Boolean,
    onCrossClick: (CrossSourceMatch) -> Unit,
    onCrossLoadMore: () -> Unit,
    onFullscreen: () -> Unit,
    onPlayToggle: () -> Unit,
    onEpisodes: () -> Unit,
    onSource: () -> Unit,
    onTimer: () -> Unit,
    onMultiThread: () -> Unit,
    onShare: () -> Unit,
    onFavorite: () -> Unit,
    onToast: (String) -> Unit,
) {
    Column(
        modifier = modifier.padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // 操作排一：全屏 + 播放/暂停 + 选集 + 源胶囊（点按弹出源列表）
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            IconButton(onClick = onFullscreen, enabled = playbackState || episodesEnabled) {
                Icon(
                    Icons.Outlined.Fullscreen,
                    contentDescription = if (playbackState) "全屏" else "全屏播放",
                )
            }
            FilledIconButton(
                onClick = onPlayToggle,
                enabled = playbackState || episodesEnabled,
                modifier = Modifier.size(44.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                Icon(
                    imageVector = if (playbackState && isPlayingNow) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                    contentDescription = if (playbackState) "播放/暂停" else "播放",
                )
            }
            AssistChip(
                onClick = onEpisodes,
                enabled = episodesEnabled,
                label = { Text("选集") },
            )
            if (sourcesAvailable) {
                // 源胶囊：蓝点 + 当前线路名，点按弹出源列表
                Surface(
                    onClick = onSource,
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
                            text = sourceLabel,
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
                    .clickable { onToast("片头/片尾跳过功能开发中，敬请期待") }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                Icon(
                    Icons.Outlined.RocketLaunch,
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
            IconButton(onClick = onTimer) {
                Icon(
                    Icons.Outlined.Timer,
                    contentDescription = "定时关闭",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onMultiThread) {
                Icon(
                    Icons.Outlined.Tune,
                    contentDescription = "多线程配置",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onShare) {
                Icon(
                    Icons.Outlined.Share,
                    contentDescription = "分享",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { onToast("投屏功能开发中，敬请期待") }) {
                Icon(
                    Icons.Outlined.Cast,
                    contentDescription = "投屏",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onFavorite) {
                Icon(
                    imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Outlined.Favorite,
                    contentDescription = "收藏",
                    tint = if (isFavorite) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
        if (!sourcesAvailable) {
            Text(
                "该剧集暂无播放线路",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // 标题（对照参考图位于操作排下方）
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        historyHint?.let { hint ->
            Text(
                text = hint,
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
        if (vod.vodContent.isNotBlank()) {
            Text(
                text = "简介：${cleanRichText(vod.vodContent)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // 其他源同名影片推荐排：横排海报，滚动到末端自动加载下一批源，点击跳对应源详情播放
        if (crossMatches.isNotEmpty() || crossLoading) {
            Text(
                text = "其他源播放",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            val rowState = rememberLazyListState()
            val crossNearEnd by remember {
                derivedStateOf {
                    val info = rowState.layoutInfo
                    val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
                    info.totalItemsCount > 0 && last >= info.totalItemsCount - 3
                }
            }
            LaunchedEffect(crossNearEnd, crossMatches.size, crossHasMore) {
                if (crossNearEnd && crossHasMore && !crossLoading) onCrossLoadMore()
            }
            LazyRow(
                state = rowState,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                items(crossMatches, key = { it.sourceId }) { match ->
                    Column(modifier = Modifier.width(110.dp)) {
                        VideoCard(
                            vod = match.vod,
                            onClick = { onCrossClick(match) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            text = match.sourceName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (crossLoading) {
                    item(key = "cross_loading", contentType = "loading") {
                        Box(
                            modifier = Modifier.width(110.dp).height(160.dp),
                            contentAlignment = Alignment.Center,
                        ) { CircularProgressIndicator(modifier = Modifier.height(22.dp)) }
                    }
                }
            }
        }
    }
}

/** 播放态：顶部海报区原地切换为内嵌播放器，信息区与浏览态共用（对齐参考 App 详情页点播） */
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
    modeState: PagePlayerModeState,
) {
    val graph = app.graph
    // 其他源同名影片状态（信息区尾部推荐排）
    val uiState by viewModel.uiState.collectAsState()
    // 音频源（听书/音乐/电台等）：播放面用音乐布局（大封面 + 控制条），内核仍在下层出声
    val sourceEntity = graph.apiSourceRepository.sources.value.firstOrNull { it.url == target.sourceUrl }
    val isAudioSource = AUDIO_SOURCE_PATTERN.containsMatchIn(sourceEntity?.name.orEmpty())
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
        // 源列表弹层追加其他源同名影片（分批搜索，触底加载更多）
        crossSources = uiState.crossMatches,
        crossLoading = uiState.crossLoading,
        crossHasMore = uiState.crossHasMore,
        onCrossSourceSelected = { match ->
            onExitPlayback()
            app.push(Screen.Detail(match.vod.vodId, match.sourceUrl, match.vod.vodName, match.vod.safePic, autoPlay = true))
        },
        onCrossSourceLoadMore = viewModel::loadNextCrossBatch,
        shareUrl = session.playUrl,
        kernelId = kernelId,
        onKernelSelected = { id -> app.switchKernel(id) },
        introMark = IntroMark.Mark(session.vodId, session.sourceUrl, session.episodeName)
            .takeIf { session.vodId != 0 && session.sourceUrl.isNotBlank() },
        onDownloadEpisode = { index -> viewModel.downloadEpisode(session.currentSourceIndex, index, app) },
        onDownloadEpisodes = { indexes -> viewModel.downloadEpisodes(session.currentSourceIndex, indexes, app) },
        onDownloadAllEpisodes = { viewModel.downloadAllEpisodes(session.currentSourceIndex, app) },
    )

    DetailPageScaffold(
        modifier = modifier,
        fullscreen = fullscreen,
        topBlock = { boxMaxWidth, boxMaxHeight ->
            // 音频源：占满可用高度的容器 + 音乐布局覆盖（内核在下层出声，封面层盖住黑屏视频面）
            if (isAudioSource) {
                Box(modifier = Modifier.fillMaxWidth().height(boxMaxHeight)) {
                    InPagePlayerSurface(
                        kernel = kernel,
                        app = app,
                        scope = PagePlayerScope.DETAIL,
                        config = surfaceConfig,
                        modifier = Modifier.fillMaxSize(),
                        onActionsReady = { playerActions = it },
                        modeState = modeState,
                    )
                    MusicPlayOverlay(
                        kernel = kernel,
                        coverUrl = vod.safePic,
                        fallbackText = session.title,
                        title = session.title,
                        episodeName = session.episodeName,
                        sourceLabel = apiName,
                        isPlaying = isPlaying,
                        onSeek = { ms -> kernel.seekTo(ms) },
                        onTogglePlay = { kernel.togglePlayPause() },
                        onPrev = onPrevEpisode,
                        onNext = onNextEpisode,
                        onExit = onExitPlayback,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            } else {
            // 视频面：宽度始终铺满、高度按视频真实比例等比自适应（未取到分辨率回落 16:9）；
            // 仅当比例极端（如 1:3）高度超出可用空间时才保底限高收窄。
            // 手机端不限制宽度铺满：按视频方向固定比例容器（横屏 16:9 / 竖屏 9:16，未知按横屏）
            val videoWidth by kernel.videoWidth.collectAsState()
            val videoHeight by kernel.videoHeight.collectAsState()
            val videoRatio = if (videoWidth > 0 && videoHeight > 0) {
                videoWidth.toFloat() / videoHeight.toFloat()
            } else {
                16f / 9f
            }
            val phoneRatio = when {
                videoWidth <= 0 || videoHeight <= 0 -> 16f / 9f
                videoWidth >= videoHeight -> 16f / 9f
                else -> 9f / 16f
            }
            val fitsWidth = boxMaxWidth / videoRatio <= boxMaxHeight
            // 视频面（等比容器 + 覆盖控制层；全屏时铺满整个窗口）
            Box(
                modifier = Modifier.fillMaxWidth().background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = when {
                        fullscreen -> Modifier.fillMaxSize()
                        isPhonePlatform -> Modifier.fillMaxWidth().aspectRatio(phoneRatio)
                        fitsWidth -> Modifier.fillMaxWidth().aspectRatio(videoRatio)
                        else -> Modifier.height(boxMaxHeight).aspectRatio(videoRatio)
                    },
                ) {
                    InPagePlayerSurface(
                        kernel = kernel,
                        app = app,
                        scope = PagePlayerScope.DETAIL,
                        config = surfaceConfig,
                        modifier = Modifier.fillMaxSize(),
                        onActionsReady = { playerActions = it },
                        modeState = modeState,
                    )
                }
            }
            }
        },
        infoContent = { infoModifier ->
            DetailInfoSection(
                modifier = infoModifier,
                vod = vod,
                title = session.title,
                sourceLabel = session.playSources.getOrNull(session.currentSourceIndex)
                    ?.let { sourceName(it, session.currentSourceIndex) } ?: "当前源",
                playbackState = true,
                isPlayingNow = isPlaying,
                isFavorite = isFavorite,
                crossMatches = uiState.crossMatches,
                crossLoading = uiState.crossLoading,
                crossHasMore = uiState.crossHasMore,
                onCrossClick = { match ->
                    onExitPlayback()
                    app.push(Screen.Detail(match.vod.vodId, match.sourceUrl, match.vod.vodName, match.vod.safePic, autoPlay = true))
                },
                onCrossLoadMore = viewModel::loadNextCrossBatch,
                historyHint = null,
                episodesEnabled = session.episodes.isNotEmpty(),
                sourcesAvailable = session.playSources.isNotEmpty(),
                onFullscreen = { app.toggleDetailFullscreen() },
                onPlayToggle = { kernel.togglePlayPause() },
                onEpisodes = { playerActions?.showEpisodeSheet() },
                onSource = { playerActions?.showSourceSheet() },
                onTimer = { playerActions?.showTimerSheet() },
                onMultiThread = { playerActions?.showMultiThreadSheet() },
                onShare = { playerActions?.showShareSheet() },
                onFavorite = { viewModel.toggleFavorite() },
                onToast = { app.showMessage(it) },
            )
        },
    )
}

/** 音频类源名关键词：命中即用音乐播放布局（听书/音乐/电台等） */
private val AUDIO_SOURCE_PATTERN = Regex("听书|音乐|电台|相声|评书|戏曲|广播")

/** 毫秒 → m:ss（负值/未知显示 --:--） */
private fun formatAudioTime(ms: Long): String {
    if (ms <= 0) return "--:--"
    val totalSeconds = ms / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

/**
 * 音乐播放布局（听书/音乐/电台等音频源）：大封面 + 曲目信息 + 进度条 + 上一集/播放/下一集。
 * 覆盖在 InPagePlayerSurface 之上（内核在下层出声，此层盖住无画面的视频黑面）。
 */
@Composable
private fun MusicPlayOverlay(
    kernel: PlayerKernel,
    coverUrl: String,
    fallbackText: String,
    title: String,
    episodeName: String,
    sourceLabel: String,
    isPlaying: Boolean,
    onSeek: (Long) -> Unit,
    onTogglePlay: () -> Unit,
    onPrev: (() -> Unit)?,
    onNext: (() -> Unit)?,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val timeMs by kernel.timeMs.collectAsState()
    val durationMs by kernel.durationMs.collectAsState()
    // 拖动进度条期间的本地值（松手才真正 seek，避免进度被轮询值顶回）
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableStateOf(0f) }
    val duration = durationMs.coerceAtLeast(0L)
    val progress = when {
        duration <= 0L -> 0f
        dragging -> dragValue
        else -> if (timeMs >= duration) 1f else timeMs.toFloat() / duration
    }
    Box(modifier = Modifier.background(Color(0xFF121212))) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onExit) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "退出播放", tint = Color.White)
                }
                Spacer(Modifier.width(8.dp))
                Column {
                    Text("正在播放", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.6f))
                    Text(sourceLabel, style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.85f))
                }
            }
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                NetImage(
                    url = coverUrl,
                    fallbackText = fallbackText,
                    modifier = Modifier.size(220.dp).clip(RoundedCornerShape(16.dp)),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                )
                Spacer(Modifier.height(24.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (episodeName.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = episodeName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.65f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Slider(
                value = progress.coerceIn(0f, 1f),
                onValueChange = {
                    dragging = true
                    dragValue = it
                },
                onValueChangeFinished = {
                    onSeek((dragValue * duration).toLong())
                    dragging = false
                },
                enabled = duration > 0L,
            )
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(formatAudioTime(if (dragging) (dragValue * duration).toLong() else timeMs), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.6f))
                Spacer(Modifier.weight(1f))
                Text(formatAudioTime(duration), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.6f))
            }
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { onPrev?.invoke() }, enabled = onPrev != null) {
                    Icon(
                        Icons.Outlined.SkipPrevious,
                        contentDescription = "上一集",
                        tint = if (onPrev != null) Color.White else Color.White.copy(alpha = 0.3f),
                        modifier = Modifier.size(36.dp),
                    )
                }
                Spacer(Modifier.width(28.dp))
                FilledIconButton(onClick = onTogglePlay, modifier = Modifier.size(64.dp)) {
                    Icon(
                        if (isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                        contentDescription = if (isPlaying) "暂停" else "播放",
                        modifier = Modifier.size(36.dp),
                    )
                }
                Spacer(Modifier.width(28.dp))
                IconButton(onClick = { onNext?.invoke() }, enabled = onNext != null) {
                    Icon(
                        Icons.Outlined.SkipNext,
                        contentDescription = "下一集",
                        tint = if (onNext != null) Color.White else Color.White.copy(alpha = 0.3f),
                        modifier = Modifier.size(36.dp),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
