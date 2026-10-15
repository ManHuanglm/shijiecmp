package com.shiping.desktop.ui

import android.app.Activity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ClosedCaption
import androidx.compose.material.icons.outlined.FastForward
import androidx.compose.material.icons.outlined.FastRewind
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PictureInPictureAlt
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.RocketLaunch
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shiping.app.data.player.PipController
import com.shiping.desktop.player.IntroMark
import com.shiping.desktop.ui.player.PlayerMode
import com.shiping.desktop.ui.player.VideoScaleMode
import com.shiping.desktop.ui.player.behavior.GestureText
import com.shiping.desktop.ui.player.behavior.RateCycle
import com.shiping.desktop.ui.player.behavior.PlayerGestureSession
import com.shiping.desktop.ui.player.behavior.CONTROLS_AUTO_HIDE_MS
import com.shiping.desktop.ui.player.behavior.GESTURE_SEEK_MAX_MS
import com.shiping.desktop.ui.player.behavior.GESTURE_SEEK_MS_PER_PX
import com.shiping.desktop.ui.player.configFor
import com.shiping.shared.player.PlayerKernel
import kotlinx.coroutines.delay

/** 中央提示自动消失毫秒数 */
private const val HINT_DURATION_MS = 2500L

/** 右侧内核/设置面板宽度（dp） */
private const val PANEL_WIDTH_DP = 230

/** 面板选中态强调色（图4 EXO 绿色高亮） */
private val PanelAccent = Color(0xFF4CC38A)

/** 倍速循环档位（Android 内嵌控制层） */
private val RATE_STEPS = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

/** 手势方向判定阈值（dp，累计位移超过才认定模式；经 Options 转 px 传入共享会话） */
private val GESTURE_DECIDE_DP = 8.dp

/** 进度手势取消区半径（dp，拖回起点附近松手取消） */
private val GESTURE_CANCEL_DP = 44.dp

/**
 * Android 页内控制层两种形态：
 * 默认（竖屏 16:9 内嵌）：顶栏（返回/分辨率/画中画）+ 中央 ±10s + 底部（时间/进度/进退集/弹幕/倍速/播放/全屏）；
 * 横屏全屏：顶栏（返回/标题/分辨率/退全屏）+ 左列（片头/选集/弹幕/清晰度/倍速/播放）+
 * 右列（画中画/定时/多线程/分享/内核）+ 中央 ±10s + 底部进度。
 * 点击视频面唤出/隐藏；播放中 3s 无操作自动隐藏；面板/侧栏打开时常显。
 */
@Composable
internal fun AndroidInPageControls(
    kernel: PlayerKernel,
    app: AppViewModel,
    actions: AndroidInPageActions,
    mode: PlayerMode,
) {
    val config = actions.config ?: return
    val isPlaying by kernel.isPlaying.collectAsState()
    val isBuffering by kernel.isBuffering.collectAsState()
    val timeMs by kernel.timeMs.collectAsState()
    val durationMs by kernel.durationMs.collectAsState()
    // 形态与允许集由统一模式派生（模式由页面状态机投影，含画中画）
    val uiConfig = configFor(mode)
    // 倍速档位（内核不回读，本地循环记录；起播档位取全局倍速设置）
    val rateCycle = remember { RateCycle(RATE_STEPS) }
    var rate by remember {
        val initialIndex = RATE_STEPS.indexOf(actions.globalSpeed).takeIf { it >= 0 }
            ?: RATE_STEPS.indexOf(1.0f).coerceAtLeast(0)
        mutableFloatStateOf(RATE_STEPS[initialIndex])
    }

    // 起播应用全局倍速（与桌面 PlayerFrame 行为对齐；新内核会话默认 1x）
    LaunchedEffect(kernel) { kernel.setRate(actions.globalSpeed) }
    // 全局倍速设置变化时循环档位跟随（面板内改全局倍速后按钮从新档位起循环）
    LaunchedEffect(actions.globalSpeed) {
        rateCycle.resetTo(actions.globalSpeed)
        rate = rateCycle.current
    }

    // 手动唤出计数：点视频面时递增，驱动自动隐藏倒计时重启
    var wakeTick by remember { mutableIntStateOf(0) }

    // 自动隐藏：播放中且无面板时 3s 无操作隐藏；暂停/面板打开时常显
    LaunchedEffect(isPlaying, actions.sheet, actions.sidePanelVisible, actions.kernelPanelVisible, wakeTick) {
        if (isPlaying && actions.sheet == null && !actions.sidePanelVisible && !actions.kernelPanelVisible) {
            actions.controlsVisible = true
            delay(CONTROLS_AUTO_HIDE_MS)
            actions.controlsVisible = false
        } else {
            actions.controlsVisible = true
        }
    }

    // 中央提示自动消失
    LaunchedEffect(actions.hint) {
        if (actions.hint != null) {
            delay(HINT_DURATION_MS)
            actions.hint = null
        }
    }

    fun cycleRate() {
        rate = rateCycle.next()
        kernel.setRate(rate)
        actions.showHint(GestureText.speed(rate))
    }

    // region 视频面手势（横向调进度 / 左半屏竖向调亮度 / 右半屏竖向调音量）
    val density = LocalDensity.current
    val gestureDecidePx = with(density) { GESTURE_DECIDE_DP.toPx() }
    val gestureCancelPx = with(density) { GESTURE_CANCEL_DP.toPx() }
    var gestureSize by remember { mutableStateOf(IntSize.Zero) }
    val gestureSession = remember(actions.showSeekCancel) {
        PlayerGestureSession(
            PlayerGestureSession.Options(
                decidePx = gestureDecidePx,
                cancelPx = gestureCancelPx,
                seekMsPerPx = GESTURE_SEEK_MS_PER_PX,
                seekMaxMs = GESTURE_SEEK_MAX_MS,
                enableBrightness = true,
                enableSeekCancel = actions.showSeekCancel,
            ),
        )
    }
    val hostActivity = LocalContext.current as? Activity
    var savedBrightness by remember { mutableStateOf<Float?>(null) }

    // 离开播放页恢复进入前亮度（未调过亮度则原样归还）
    DisposableEffect(Unit) {
        onDispose {
            savedBrightness?.let { original ->
                hostActivity?.window?.let { window ->
                    window.attributes = window.attributes.also { it.screenBrightness = original }
                }
            }
        }
    }

    /** 当前窗口亮度（系统默认 -1 时取 0.5 基准） */
    fun currentBrightness(): Float =
        hostActivity?.window?.attributes?.screenBrightness?.takeIf { it in 0.01f..1f } ?: 0.5f

    fun onGestureStart(startX: Float) {
        // 首次调亮度前记录原窗口亮度，离开播放页恢复
        if (savedBrightness == null) {
            savedBrightness = hostActivity?.window?.attributes?.screenBrightness
        }
        gestureSession.start(
            startX = startX,
            width = gestureSize.width.toFloat(),
            height = gestureSize.height.toFloat(),
            seekBaseMs = kernel.timeMs.value,
            volumeBase = actions.playerVolume,
            brightnessBase = currentBrightness(),
        )
    }

    fun onGestureDelta(dx: Float, dy: Float) {
        if (actions.isLocked) return
        when (val feedback = gestureSession.onDelta(dx, dy, kernel.durationMs.value)) {
            is PlayerGestureSession.Feedback.Seek -> actions.gestureFeedback = feedback.text
            is PlayerGestureSession.Feedback.Brightness -> {
                hostActivity?.window?.let { window ->
                    window.attributes = window.attributes.also { it.screenBrightness = feedback.value }
                }
                actions.gestureFeedback = feedback.text
            }
            is PlayerGestureSession.Feedback.Volume -> {
                actions.adjustVolume(feedback.percent)
                actions.gestureFeedback = feedback.text
            }
            null -> Unit
        }
    }

    fun onGestureEnd() {
        when (val commit = gestureSession.onEnd()) {
            is PlayerGestureSession.Commit.Seek -> kernel.seekTo(commit.targetMs)
            PlayerGestureSession.Commit.CancelSeek -> actions.showHint(GestureText.SEEK_CANCELLED)
            null -> Unit
        }
        actions.gestureFeedback = null
    }
    // endregion

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { gestureSize = it }
            .pointerInput(kernel) {
                // 点按唤出/隐藏控制层；长按倍速（松手恢复）；拖动调进度/亮度/音量
                detectTapGestures(
                    onTap = {
                        // 锁定态点画面不唤出控制层，仅可点锁形图标解锁
                        if (!actions.isLocked) {
                            actions.controlsVisible = !actions.controlsVisible
                            wakeTick++
                        }
                    },
                    onLongPress = { if (!actions.isLocked) actions.beginSpeedBoost() },
                    onPress = {
                        try {
                            awaitRelease()
                        } finally {
                            // 松手/滑动取消时长按倍速（未生效时为空操作）
                            actions.endSpeedBoost(rate)
                        }
                    },
                )
            }
            .pointerInput(kernel) {
                detectDragGestures(
                    onDragStart = { offset -> onGestureStart(offset.x) },
                    onDrag = { change, amount ->
                        onGestureDelta(amount.x, amount.y)
                        change.consume()
                    },
                    onDragEnd = { onGestureEnd() },
                    onDragCancel = { onGestureEnd() },
                )
            },
    ) {
        // 中央缓冲指示：先于控制层绘制（处于控制组件下一层级，缓冲中仍可呼出控制组件盖在其上）
        if (isBuffering) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center).size(28.dp),
                color = Color.White,
                strokeWidth = 3.dp,
            )
        }
        AnimatedVisibility(
            // 可见性由用户呼出状态决定（缓冲中同样可呼出；转圈处于下层不拦截手势）
            visible = actions.controlsVisible || actions.sheet != null ||
                actions.sidePanelVisible || actions.kernelPanelVisible,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // 形态由统一模式决定：FULLSCREEN/LIVE_FULLSCREEN 为完整布局，其余为紧凑内嵌
                if (!uiConfig.compact) {
                    // 横屏全屏形态：顶栏 + 底部进度/按钮排
                    AndroidFullscreenControls(
                        kernel, app, config, actions, isPlaying, timeMs, durationMs,
                        rate, ::cycleRate,
                    )
                } else {
                    // 竖屏内嵌形态：顶栏 + 底部控制排
                    Column(modifier = Modifier.fillMaxSize()) {
                        AndroidInlineTopBar(kernel, config, actions)
                        Spacer(modifier = Modifier.weight(1f))
                        AndroidProgressArea(kernel, timeMs, durationMs, actions.showTotalDuration, uiConfig.showProgress)
                        AndroidInlineTransport(
                            kernel, app, config, actions, isPlaying, rate, ::cycleRate,
                        )
                    }
                }
                // 中央：快进/快退（带秒数标签，0 秒档不展示；与下层缓冲转圈错位不遮挡）
                if (actions.seekStepSec > 0) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(56.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.align(Alignment.Center),
                    ) {
                        SeekStepButton(Icons.Outlined.FastRewind, "快退", actions.seekStepSec) {
                            kernel.seekTo((kernel.timeMs.value - actions.seekStepSec * 1000L).coerceAtLeast(0))
                        }
                        SeekStepButton(Icons.Outlined.FastForward, "快进", actions.seekStepSec) {
                            kernel.seekTo(kernel.timeMs.value + actions.seekStepSec * 1000L)
                        }
                    }
                }
            }
        }
        // 锁定指示：左缘居中锁形按钮（控制层隐藏时仍显示，点击解锁）
        if (actions.isLocked) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 16.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.45f))
                    .clickable { actions.toggleLock() }
                    .padding(10.dp),
            ) {
                Icon(Icons.Outlined.Lock, contentDescription = "解锁", tint = Color.White)
            }
        }
        // 右侧内核/设置滑出面板（图3/图4）
        KernelSettingsPanel(config, actions)
        // 手势反馈（拖动调亮度/音量/进度的实时值，松手消失；缓冲时隐藏）
        if (!isBuffering) {
            actions.gestureFeedback?.let { text ->
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .background(Color.Black.copy(alpha = 0.7f), MaterialTheme.shapes.small)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Text(text = text, color = Color.White, fontSize = 14.sp)
                }
            }
        }
        // 中央提示（seek 提示/定时提示等，最高层；缓冲时隐藏）
        if (!isBuffering) {
            actions.hint?.let { text ->
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .background(Color.Black.copy(alpha = 0.7f), MaterialTheme.shapes.small)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Text(text = text, color = Color.White, fontSize = 14.sp)
                }
            }
        }
    }
}

/** 分辨率文案（未取到视频尺寸时返回 null 隐藏） */
@Composable
private fun resolutionText(kernel: PlayerKernel): String? {
    val width by kernel.videoWidth.collectAsState()
    val height by kernel.videoHeight.collectAsState()
    return if (width > 0 && height > 0) "分辨率:$width x $height" else null
}

/** 竖屏顶栏：返回 + 分辨率 + 画中画；黑到透明渐变底 */
@Composable
private fun AndroidInlineTopBar(kernel: PlayerKernel, config: InPagePlayerConfig, actions: AndroidInPageActions) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.65f), Color.Transparent)),
            )
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (config.onBack != null) {
            IconButton(onClick = { actions.closeSheets(); config.onBack?.invoke() }) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回", tint = Color.White)
            }
        }
        Text(
            text = resolutionText(kernel) ?: "",
            color = Color.White.copy(alpha = 0.85f),
            fontSize = 13.sp,
            maxLines = 1,
        )
        Spacer(modifier = Modifier.weight(1f))
        val context = LocalContext.current
        IconButton(onClick = {
            (context as? Activity)?.let { PipController.tryEnterPip(it) } ?: actions.showHint("当前环境不支持画中画")
        }) {
            Icon(Icons.Outlined.PictureInPictureAlt, contentDescription = "画中画", tint = Color.White)
        }
    }
}

/** 竖屏底部区：时间分列进度条两端（showTotalDuration=false 仅当前时间；直播模式仅「直播」角标） */
@Composable
private fun AndroidProgressArea(
    kernel: PlayerKernel,
    timeMs: Long,
    durationMs: Long,
    showTotalDuration: Boolean,
    showProgress: Boolean,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // 透明背景：不与视频画面形成断层（时间文字自身带白底可读性，无需渐变垫底）
            .padding(start = 8.dp, end = 8.dp, top = 4.dp),
    ) {
        if (showProgress && durationMs > 0) {
            // 时间分列进度条两端（当前=左 / 总时长=右）
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = formatTime(timeMs),
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 12.sp,
                )
                AndroidSeekBar(
                    kernel = kernel,
                    timeMs = timeMs,
                    durationMs = durationMs,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
                if (showTotalDuration) {
                    Text(
                        text = formatTime(durationMs),
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 12.sp,
                    )
                }
            }
        } else {
            Text(
                text = "直播",
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

/** 竖屏底部控制排：上一集/下一集/弹幕/倍速 + 播放暂停 + 全屏 */
@Composable
private fun AndroidInlineTransport(
    kernel: PlayerKernel,
    app: AppViewModel,
    config: InPagePlayerConfig,
    actions: AndroidInPageActions,
    isPlaying: Boolean,
    rate: Float,
    onCycleRate: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        if (actions.showPrevNext) {
            config.onPrevEpisode?.let {
                ControlIconButton(Icons.Outlined.SkipPrevious, "上一集") { it() }
            }
            config.onNextEpisode?.let {
                ControlIconButton(Icons.Outlined.SkipNext, "下一集") { it() }
            }
        }
        ControlTextButton("弹") { actions.showHint("弹幕功能开发中，敬请期待") }
        ControlTextButton("x$rate", onClick = onCycleRate)
        Spacer(modifier = Modifier.weight(1f))
        ControlIconButton(
            if (isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
            "播放/暂停",
        ) { kernel.togglePlayPause() }
        ControlIconButton(Icons.Outlined.Fullscreen, "全屏") {
            actions.closeSheets()
            (config.onToggleFullscreen ?: { app.toggleDetailFullscreen() }).invoke()
        }
    }
}

/**
 * 横屏全屏形态（图2）：顶栏（退出全屏/片名/分辨率/定时/多线程/分享/内核/画中画）
 * + 中央 ±10s（带 10s 标签）+ 底部（时间 / 横向进度条 / 按钮排：上一集·下一集·记录片头 | 选集·清晰度·倍速·播放·退出全屏）
 */
@Composable
private fun AndroidFullscreenControls(
    kernel: PlayerKernel,
    app: AppViewModel,
    config: InPagePlayerConfig,
    actions: AndroidInPageActions,
    isPlaying: Boolean,
    timeMs: Long,
    durationMs: Long,
    rate: Float,
    onCycleRate: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        // 顶栏
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent),
                    ),
                )
                .padding(horizontal = 4.dp, vertical = 2.dp),
        ) {
            ControlIconButton(Icons.AutoMirrored.Outlined.ArrowBack, "退出全屏") {
                actions.closeSheets()
                (config.onToggleFullscreen ?: { app.toggleDetailFullscreen() }).invoke()
            }
            Column(modifier = Modifier.weight(1f).padding(horizontal = 6.dp)) {
                if (config.title.isNotEmpty()) {
                    Text(
                        text = config.title,
                        color = Color.White,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val res = resolutionText(kernel)
                if (res != null) {
                    Text(text = res, color = Color.White.copy(alpha = 0.65f), fontSize = 11.sp)
                }
            }
            val pipContext = LocalContext.current
            ControlIconButton(Icons.Outlined.LockOpen, "锁定屏幕") { actions.toggleLock() }
            ControlIconButton(Icons.Outlined.ClosedCaption, "字幕") { actions.showHint("字幕功能开发中，敬请期待") }
            ControlIconButton(Icons.Outlined.PhotoCamera, "截图") {
                val capture = actions.onCaptureFrame
                if (capture == null) actions.showHint("当前环境不支持截图") else capture()
            }
            ControlIconButton(Icons.Outlined.Timer, "定时关闭") { actions.showTimerSheet() }
            ControlIconButton(Icons.Outlined.RocketLaunch, "多线程配置") { actions.showMultiThreadSheet() }
            ControlIconButton(Icons.Outlined.Settings, "播放器设置") { actions.showKernelPanel() }
            ControlIconButton(Icons.Outlined.PictureInPictureAlt, "画中画") {
                (pipContext as? Activity)?.let { PipController.tryEnterPip(it) }
                    ?: actions.showHint("当前环境不支持画中画")
            }
        }
        // 中部弹性区（中央 ±10s / 缓冲由外层 Box 居中承载）
        Spacer(modifier = Modifier.weight(1f))
        // 底部：时间行 + 进度条 + 按钮排
        var dragValue by remember { mutableStateOf<Float?>(null) }
        val totalSeconds = (durationMs / 1000f).coerceAtLeast(1f)
        val sliderColor = MaterialTheme.colorScheme.primary
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // 透明背景：底部控制区与画面无断层（对齐竖屏形态处理）
                .padding(horizontal = 8.dp),
        ) {
            if (durationMs > 0) {
                val timeText = if (actions.showTotalDuration) {
                    "${formatTime(timeMs)} / ${formatTime(durationMs)}"
                } else {
                    formatTime(timeMs)
                }
                Text(
                    text = timeText,
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )
            }
            Slider(
                value = dragValue ?: (timeMs / 1000f).coerceIn(0f, totalSeconds),
                onValueChange = { dragValue = it },
                onValueChangeFinished = {
                    dragValue?.let { kernel.seekTo((it * 1000).toLong()) }
                    dragValue = null
                },
                valueRange = 0f..totalSeconds,
                colors = SliderDefaults.colors(
                    thumbColor = sliderColor,
                    activeTrackColor = sliderColor,
                    inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            ) {
                if (actions.showPrevNext) {
                    config.onPrevEpisode?.let {
                        ControlIconButton(Icons.Outlined.SkipPrevious, "上一集") { it() }
                    }
                    config.onNextEpisode?.let {
                        ControlIconButton(Icons.Outlined.SkipNext, "下一集") { it() }
                    }
                }
                if (config.introMark != null) {
                    ControlTextButton("记录片头") {
                        val mark = config.introMark ?: return@ControlTextButton
                        val endMs = kernel.timeMs.value
                        IntroMark.save(app.graph.prefs, mark, endMs)
                        actions.showHint("已记录片头 ${formatTime(endMs)}，下次播放自动跳过")
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
                if (config.episodes.isNotEmpty()) {
                    GlowPillButton(text = "选集", onClick = { actions.showEpisodeSheet() })
                }
                val currentSource = config.sources.getOrNull(config.currentSourceIndex)
                    ?: config.sources.firstOrNull()
                if (currentSource != null) {
                    Spacer(modifier = Modifier.width(6.dp))
                    GlowPillButton(text = currentSource, dot = true, onClick = { actions.showSourceSheet() })
                }
                if (config.qualities.isNotEmpty()) {
                    ControlTextButton("清晰度") { actions.showQualitySheet() }
                }
                ControlTextButton("x$rate", onClick = onCycleRate)
                ControlIconButton(
                    if (isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                    "播放/暂停",
                ) { kernel.togglePlayPause() }
                ControlIconButton(Icons.Outlined.FullscreenExit, "退出全屏") {
                    actions.closeSheets()
                    (config.onToggleFullscreen ?: { app.toggleDetailFullscreen() }).invoke()
                }
            }
        }
    }
}

/**
 * 右侧滑出面板（图3/图4）：上半内核切换（当前内核绿色高亮，Android 仅支持 EXO），
 * 下半播放器设置项（开关 + 步进值循环）。遮罩点击关闭，自右缘滑入/滑出。
 */
@Composable
private fun KernelSettingsPanel(
    config: InPagePlayerConfig,
    actions: AndroidInPageActions,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedVisibility(visible = actions.kernelPanelVisible, enter = fadeIn(), exit = fadeOut()) {
            // 遮罩：点击仅关闭面板，不触发控制层显隐
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.35f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { actions.closeKernelPanel() },
            )
        }
        AnimatedVisibility(
            visible = actions.kernelPanelVisible,
            enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
            exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(),
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(PANEL_WIDTH_DP.dp)
                    .background(Color(0xEE14171C))
                    .verticalScroll(rememberScrollState())
                    .windowInsetsPadding(WindowInsets.safeDrawing),
            ) {
                Text(
                    text = "播放器设置",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
                Text(
                    text = "内核切换",
                    color = Color.White.copy(alpha = 0.45f),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                PanelKernelRow("MPV", config.kernelId == KernelIds.MPV) {
                    actions.showHint("MPV 内核暂未支持，敬请期待")
                }
                PanelKernelRow("MDK", config.kernelId == KernelIds.MDK) {
                    actions.showHint("MDK 内核暂未支持，敬请期待")
                }
                PanelKernelRow("EXO", config.kernelId == KernelIds.MEDIA3) {
                    actions.closeKernelPanel()
                }
                PanelDivider()
                PanelSwitchRow("显示上一集下一集按钮", actions.showPrevNext) { actions.updateShowPrevNext(it) }
                PanelSwitchRow("进入后台暂停播放", actions.pauseOnBackground) { actions.updatePauseOnBackground(it) }
                PanelSwitchRow("显示总时长", actions.showTotalDuration) { actions.updateShowTotalDuration(it) }
                PanelSwitchRow("显示松手取消调整进度", actions.showSeekCancel) { actions.updateShowSeekCancel(it) }
                PanelSwitchRow("静音", actions.isMuted) { actions.updateMuted(it) }
                PanelValueRow("全局倍速设置", "x${actions.globalSpeed}") { cycleGlobalSpeed(actions) }
                PanelValueRow(
                    label = "快进快退",
                    value = if (actions.seekStepSec == 0) "不展示" else "${actions.seekStepSec}秒",
                    onClick = { cycleSeekStep(actions) },
                )
                PanelValueRow(
                    label = "长按倍速",
                    value = if (actions.longPressRate <= 0f) "关闭" else "x${actions.longPressRate}",
                    onClick = { cycleLongPressRate(actions) },
                )
                PanelValueRow(
                    label = "画面调节",
                    value = actions.videoScale.label,
                    onClick = { actions.updateVideoScale(VideoScaleMode.nextOf(actions.videoScale)) },
                )
            }
        }
    }
}

/** 内核行：当前内核绿色文字 + 对勾（图4 EXO 高亮样式） */
@Composable
private fun PanelKernelRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 11.dp),
    ) {
        Text(
            text = label,
            color = if (selected) PanelAccent else Color.White.copy(alpha = 0.9f),
            fontSize = 14.sp,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(
                Icons.Outlined.Check,
                contentDescription = "当前内核",
                tint = PanelAccent,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** 开关设置行 */
@Composable
private fun PanelSwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
    ) {
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.9f),
            fontSize = 13.sp,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = PanelAccent,
                checkedTrackColor = PanelAccent.copy(alpha = 0.45f),
                uncheckedThumbColor = Color.White.copy(alpha = 0.7f),
                uncheckedTrackColor = Color.White.copy(alpha = 0.18f),
            ),
        )
    }
}

/** 值步进行：点击循环切换值 */
@Composable
private fun PanelValueRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 11.dp),
    ) {
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.9f),
            fontSize = 13.sp,
            modifier = Modifier.weight(1f),
        )
        Text(text = value, color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
        Icon(
            Icons.Outlined.ChevronRight,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.4f),
            modifier = Modifier.size(16.dp),
        )
    }
}

/** 面板内分隔线 */
@Composable
private fun PanelDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .height(0.5.dp)
            .background(Color.White.copy(alpha = 0.12f)),
    )
}

/** 全局倍速档位循环：点击切至下一档并即时生效 */
private fun cycleGlobalSpeed(actions: AndroidInPageActions) {
    val index = RATE_STEPS.indexOf(actions.globalSpeed)
    val next = RATE_STEPS[(index + 1).mod(RATE_STEPS.size)]
    actions.updateGlobalSpeed(next)
}

/** 快进快退秒数循环：0(不展示)→10→15→20→30→60→0 */
private fun cycleSeekStep(actions: AndroidInPageActions) {
    val steps = listOf(0, 10, 15, 20, 30, 60)
    val next = steps[(steps.indexOf(actions.seekStepSec) + 1).mod(steps.size)]
    actions.updateSeekStepSec(next)
}

/** 长按倍速循环：关闭→2.0x→3.0x→关闭 */
private fun cycleLongPressRate(actions: AndroidInPageActions) {
    val steps = listOf(0f, 2.0f, 3.0f)
    val next = steps[(steps.indexOf(actions.longPressRate) + 1).mod(steps.size)]
    actions.updateLongPressRate(next)
}

/** 进度条：拖动中锁定显示拖动值，松手 seek（与桌面一致；拖动暂不支持取消回退）；圆点缩小、贴底控制排 */
@OptIn(ExperimentalMaterial3Api::class)
/** 细进度条（自绘）：轨道 3dp + 圆点 8dp 严格居中（M3 Slider 压高度会圆点错位）；支持点击/拖拽 seek */
@Composable
private fun AndroidSeekBar(
    kernel: PlayerKernel,
    timeMs: Long,
    durationMs: Long,
    modifier: Modifier = Modifier,
) {
    var dragValue by remember { mutableStateOf<Float?>(null) }
    var boxWidthPx by remember { mutableStateOf(0f) }
    val density = LocalDensity.current
    val totalSeconds = (durationMs / 1000f).coerceAtLeast(1f)
    val progress = ((dragValue ?: (timeMs / 1000f)) / totalSeconds).coerceIn(0f, 1f)
    val valueFromX: (Float, Int) -> Float = { x, width ->
        ((x / width.coerceAtLeast(1)) * totalSeconds).coerceIn(0f, totalSeconds)
    }
    Box(
        modifier = modifier
            .height(14.dp)
            .onSizeChanged { boxWidthPx = it.width.toFloat() }
            .pointerInput(totalSeconds) {
                detectTapGestures { offset ->
                    kernel.seekTo((valueFromX(offset.x, size.width) * 1000).toLong())
                }
            }
            .pointerInput(totalSeconds) {
                detectHorizontalDragGestures(
                    onDragStart = { offset -> dragValue = valueFromX(offset.x, size.width) },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        dragValue = valueFromX(change.position.x, size.width)
                    },
                    onDragEnd = {
                        dragValue?.let { kernel.seekTo((it * 1000).toLong()) }
                        dragValue = null
                    },
                    onDragCancel = { dragValue = null },
                )
            },
    ) {
        // 轨道（3dp 细条，垂直居中）
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxWidth()
                .height(3.dp)
                .background(Color.White.copy(alpha = 0.3f), RoundedCornerShape(2.dp)),
        )
        // 已播段
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxWidth(progress)
                .height(3.dp)
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)),
        )
        // 圆点（8dp）：中心严格对齐进度位置，两端收进条内防溢出
        if (boxWidthPx > 0f) {
            val thumbDp = 8.dp
            val thumbPx = with(density) { thumbDp.toPx() }
            val offsetX = (boxWidthPx * progress - thumbPx / 2).coerceIn(0f, boxWidthPx - thumbPx)
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = with(density) { offsetX.toDp() })
                    .size(thumbDp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
            )
        }
    }
}

/** 图标控制按钮（白色） */
@Composable
private fun ControlIconButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
        Icon(icon, contentDescription = label, tint = Color.White)
    }
}

/** 快进/快退按钮：图标 + 「10s」秒数标签横排（对齐图2 中央控件） */
@Composable
private fun SeekStepButton(icon: ImageVector, label: String, stepSec: Int, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onClick() }
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Icon(icon, contentDescription = "$label${stepSec}秒", tint = Color.White)
        Text(text = "${stepSec}s", color = Color.White, fontSize = 12.sp)
    }
}

/** 图1 样式胶囊按钮：深色半透明底 + 白色描边外发光；dot=true 时前置源标识蓝点 */
@Composable
private fun GlowPillButton(text: String, dot: Boolean = false, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .background(Color.Black.copy(alpha = 0.45f), CircleShape)
            .glowPillBorder()
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        if (dot) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(SourceDotBlue, CircleShape),
            )
            Spacer(modifier = Modifier.width(5.dp))
        }
        Text(text = text, color = Color.White, fontSize = 13.sp, maxLines = 1)
    }
}

/** 图1 胶囊发光描边：白色主描边 + 多层渐隐外发光 */
private fun Modifier.glowPillBorder(): Modifier = drawBehind {
    val radius = CornerRadius(size.height / 2)
    listOf(5f, 3.5f, 2f).forEach { widthDp ->
        drawRoundRect(
            color = Color.White.copy(alpha = 0.06f),
            cornerRadius = radius,
            style = Stroke(width = widthDp.dp.toPx()),
        )
    }
    drawRoundRect(
        color = Color.White.copy(alpha = 0.55f),
        cornerRadius = radius,
        style = Stroke(width = 1.dp.toPx()),
    )
}

/** 文字控制按钮（白色小字，禁用降透明度） */
@Composable
private fun ControlTextButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        text = label,
        color = Color.White.copy(alpha = if (enabled) 0.95f else 0.35f),
        fontSize = 13.sp,
        modifier = Modifier
            .clickable(enabled = enabled, interactionSource = remember { MutableInteractionSource() }, indication = null) { onClick() }
            .padding(horizontal = 8.dp, vertical = 10.dp),
    )
}

/** 毫秒转 mm:ss / h:mm:ss */
internal fun formatTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val h = totalSeconds / 3600
    val m = totalSeconds % 3600 / 60
    val s = totalSeconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
