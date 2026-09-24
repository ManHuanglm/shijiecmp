package com.shiping.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.shiping.app.ShipingApp
import com.shiping.app.data.player.Media3PlayerKernel
import com.shiping.app.data.player.PipController
import com.shiping.shared.graph.AppGraph
import com.shiping.shared.player.PlayerKernel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 创建页内播放内核：media3（应用上下文取宿主 Application） */
actual fun createInPageKernel(graph: AppGraph): PlayerKernel =
    Media3PlayerKernel().apply { applicationContext = ShipingApp.context }

/** 等待渲染面就绪：PlayerView 由组合承载（AndroidView factory 同步创建），立即就绪 */
actual suspend fun awaitSurfaceReady(kernel: PlayerKernel, timeoutMs: Long, pollIntervalMs: Long): Boolean = true

/** 释放页内播放内核：media3 内核内部主线程队列执行 release，直接调用即安全 */
actual fun releasePageKernel(kernel: PlayerKernel) {
    runCatching { kernel.release() }
}

/** 页内播放区（Android）：PlayerView 渲染面 + Compose 自绘控制层（顶栏/底部按钮排/底部面板/频道侧栏） */
@OptIn(UnstableApi::class)
@Composable
actual fun InPagePlayerSurface(
    kernel: PlayerKernel,
    app: AppViewModel,
    scope: PagePlayerScope,
    config: InPagePlayerConfig,
    modifier: Modifier,
    onActionsReady: (InPagePlayerActions) -> Unit,
) {
    val media3 = kernel as? Media3PlayerKernel
    // 组合作用域持有 PlayerView 引用：视图销毁时解绑，防悬挂引用（内核释放由页面编排负责）
    var playerView by remember { mutableStateOf<PlayerView?>(null) }
    // PiP 候选内核挂载/卸载（播放中 onUserLeaveHint 自动进画中画）+ 小窗态隐藏控制层
    DisposableEffect(media3) {
        media3?.let(PipController::attachKernel)
        onDispose {
            media3?.let(PipController::detachKernel)
            playerView?.player = null
        }
    }
    val inPip by PipController.isInPip.collectAsState()
    val composeScope = rememberCoroutineScope()
    val actions = remember(kernel, app) { AndroidInPageActions(kernel, app, composeScope) }
    Box(modifier = modifier.background(Color.Black)) {
        media3?.let { target ->
            AndroidView(
                factory = { context ->
                    PlayerView(context).apply {
                        useController = false
                        player = target.player
                        playerView = this
                    }
                },
                update = { view ->
                    // 重组时对齐最新 player（内核重建后自动重绑）
                    view.player = target.player
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
        // 小窗态只留画面：控制层/面板全部隐藏（isInPip 由 Activity 回调驱动）
        if (!inPip) {
            AndroidInPageControls(kernel = kernel, app = app, actions = actions)
            AndroidPlayerSheets(app = app, actions = actions)
        }
    }
    DisposableEffect(actions) {
        app.registerInPageOverlay(actions)
        onDispose {
            app.unregisterInPageOverlay(actions)
            actions.dispose()
        }
    }
    // 配置刷新：控制层与面板读 actions.config，随会话重组刷新
    SideEffect { actions.config = config }
    LaunchedEffect(actions) { onActionsReady(actions) }
}

/** 底部面板种类（同一时刻最多一个，互斥弹出） */
internal enum class SheetKind { EPISODE, SOURCE, QUALITY, KERNEL, TIMER, MULTI_THREAD, SHARE }

/**
 * Android 控制层动作集：面板/提示/显隐状态驱动 + 定时关闭协程。
 * setBackground 继承 [InPageOverlayHost]（切页命令式隐藏视频面之上全部覆盖层）。
 */
internal class AndroidInPageActions(
    private val kernel: PlayerKernel,
    private val app: AppViewModel,
    private val scope: CoroutineScope,
) : InPagePlayerActions {

    /** 当前会话配置（SideEffect 随重组刷新） */
    var config: InPagePlayerConfig? by mutableStateOf(null)

    /** 当前弹出的底部面板（null=无） */
    var sheet: SheetKind? by mutableStateOf(null)

    /** 频道侧栏可见性（直播页 ▦） */
    var sidePanelVisible: Boolean by mutableStateOf(false)

    /** 中央提示文案（自动消失） */
    var hint: String? by mutableStateOf(null)

    /** 控制层可见（播放中 3s 无操作自动隐藏；点视频面唤出） */
    var controlsVisible: Boolean by mutableStateOf(true)

    /** 定时关闭一次性任务（重复添加替换） */
    private var sleepJob: Job? = null

    private var disposed = false

    /** 组合销毁时取消定时任务（防悬挂协程） */
    fun dispose() {
        disposed = true
        sleepJob?.cancel()
    }

    override fun setBackground(hidden: Boolean) {
        if (!hidden) return
        sheet = null
        sidePanelVisible = false
        controlsVisible = false
    }

    override fun showEpisodeSheet() {
        if (config?.episodes?.isNotEmpty() == true) openSheet(SheetKind.EPISODE)
    }

    override fun showSourceSheet() {
        if (config?.sources?.isNotEmpty() == true) openSheet(SheetKind.SOURCE)
    }

    override fun showTimerSheet() = openSheet(SheetKind.TIMER)

    override fun showMultiThreadSheet() = openSheet(SheetKind.MULTI_THREAD)

    override fun showShareSheet() = openSheet(SheetKind.SHARE)

    /** 频道侧栏（直播 ▦；config.channelPanel 为空则回落选集） */
    fun showChannelPanel() {
        if (config?.channelPanel != null) {
            sheet = null
            sidePanelVisible = true
        } else {
            showEpisodeSheet()
        }
    }

    /** 清晰度面板（qualities 为空不弹） */
    fun showQualitySheet() {
        if (config?.qualities?.isNotEmpty() == true) openSheet(SheetKind.QUALITY)
    }

    /** 内核切换面板 */
    fun showKernelSheet() = openSheet(SheetKind.KERNEL)

    /** 关闭全部面板与侧栏 */
    fun closeSheets() {
        sheet = null
        sidePanelVisible = false
    }

    /** 展示中央提示（控制层内自动消失，不经全局 Snackbar） */
    fun showHint(text: String) {
        hint = text
    }

    /** 设置定时关闭：到时暂停播放；重复「添加定时」替换旧定时 */
    fun scheduleSleepTimer(totalSeconds: Int) {
        if (totalSeconds <= 0) {
            showHint("请选择定时时长")
            return
        }
        sleepJob?.cancel()
        sleepJob = scope.launch {
            delay(totalSeconds * 1000L)
            if (disposed) return@launch
            if (kernel.isPlaying.value) kernel.togglePlayPause()
            showHint("定时时间到，已暂停播放")
        }
        showHint("已设置 ${formatSleepDuration(totalSeconds)} 后暂停播放")
        sheet = null
    }

    /** 秒数转「x时x分x秒」提示文案 */
    private fun formatSleepDuration(totalSeconds: Int): String {
        val h = totalSeconds / 3600
        val m = totalSeconds % 3600 / 60
        val s = totalSeconds % 60
        return buildString {
            if (h > 0) append("${h}时")
            if (m > 0) append("${m}分")
            if (s > 0 || isEmpty()) append("${s}秒")
        }
    }

    private fun openSheet(kind: SheetKind) {
        sidePanelVisible = false
        sheet = kind
        controlsVisible = true
    }
}
