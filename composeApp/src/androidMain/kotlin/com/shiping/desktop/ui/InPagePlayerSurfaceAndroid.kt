package com.shiping.desktop.ui

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.shiping.app.ShipingApp
import com.shiping.app.data.player.Media3PlayerKernel
import com.shiping.app.data.player.PipController
import com.shiping.shared.graph.AppGraph
import com.shiping.shared.player.PlayerKernel
import com.shiping.shared.prefs.PrefsKeys
import com.shiping.desktop.ui.player.behavior.GestureText
import com.shiping.desktop.ui.player.behavior.MuteSession
import com.shiping.desktop.ui.player.PagePlayerModeState
import com.shiping.desktop.ui.player.VideoScaleMode
import com.shiping.desktop.ui.player.inferMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 创建页内播放内核：media3（应用上下文取宿主 Application），接入 m3u8 播放级广告过滤开关 */
actual fun createInPageKernel(graph: AppGraph): PlayerKernel =
    Media3PlayerKernel(
        isSliceAdFilterEnabled = {
            graph.prefs.getBoolean(PrefsKeys.KEY_FILTER_M3U8_AD, true)
        },
    ).apply { applicationContext = ShipingApp.context }

/** 等待渲染面就绪：TextureView 由组合承载（AndroidView factory 同步创建），立即就绪 */
actual suspend fun awaitSurfaceReady(kernel: PlayerKernel, timeoutMs: Long, pollIntervalMs: Long): Boolean = true

/** 释放页内播放内核：media3 内核内部主线程队列执行 release，直接调用即安全 */
actual fun releasePageKernel(kernel: PlayerKernel) {
    runCatching { kernel.release() }
}

/** 手机端平台标记：Android 为手机端 */
actual val isPhonePlatform: Boolean = true

/** 页内播放区（Android）：TextureView 渲染面 + Compose 自绘控制层（顶栏/底部按钮排/底部面板/频道侧栏） */
@OptIn(UnstableApi::class)
@Composable
actual fun InPagePlayerSurface(
    kernel: PlayerKernel,
    app: AppViewModel,
    scope: PagePlayerScope,
    config: InPagePlayerConfig,
    modifier: Modifier,
    onActionsReady: (InPagePlayerActions) -> Unit,
    modeState: PagePlayerModeState?,
) {
    val media3 = kernel as? Media3PlayerKernel
    val composeScope = rememberCoroutineScope()
    // 播放页期间屏幕常亮（FLAG_KEEP_SCREEN_ON 只防息屏，不阻止手动锁屏）
    val hostActivity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
    DisposableEffect(hostActivity) {
        hostActivity?.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            hostActivity?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
    // 视频尺寸上报：MainActivity 全屏方向决策用（竖屏视频全屏不旋转）；离开播放区复位未知
    DisposableEffect(kernel) {
        onDispose { app.updatePlayerVideoSize(0, 0) }
    }
    LaunchedEffect(kernel) {
        combine(kernel.videoWidth, kernel.videoHeight) { w, h -> w to h }
            .collect { (width, height) -> app.updatePlayerVideoSize(width, height) }
    }
    val actions = remember(kernel, app) { AndroidInPageActions(kernel, app, composeScope) }
    // 渲染面引用提升到播放区作用域：截图（TextureView.getBitmap）需要访问
    var textureView by remember { mutableStateOf<TextureView?>(null) }
    val hostContext = LocalContext.current
    SideEffect {
        actions.onCaptureFrame = {
            val bitmap = textureView?.bitmap
            if (bitmap == null) {
                actions.showHint("画面尚未就绪，无法截图")
            } else {
                composeScope.launch(Dispatchers.IO) {
                    val saved = runCatching { saveBitmapToGallery(hostContext, bitmap) }.getOrDefault(false)
                    composeScope.launch { actions.showHint(if (saved) "截图已保存到相册" else "截图保存失败") }
                }
            }
        }
    }
    // 统一模式：优先消费页面状态机投影（含画中画）；未绑定时本地派生兜底
    val detailFullscreen by app.detailFullscreen.collectAsState()
    val tvFullscreen by app.tvFullscreen.collectAsState()
    val pipActive by PipController.isInPip.collectAsState()
    val mode = modeState?.uiState?.collectAsState()?.value?.mode
        ?: inferMode(scope, detailFullscreen || tvFullscreen, pipActive)
    // PiP 激活态同步进统一模式状态机（isInPip 由 Activity onPictureInPictureModeChanged 回调驱动）
    LaunchedEffect(modeState) {
        modeState?.let { state -> PipController.isInPip.collect { state.setPip(it) } }
    }
    Box(modifier = modifier.background(Color.Black).clipToBounds()) {
        media3?.let { target ->
            // 画面调节：容器按模式定尺寸（自动=视频比例适配 / 固定比例 / 裁剪=等比外扩裁边 / 铺满=拉伸），
            // TextureView 填满容器；视频尺寸未就绪时铺满
            val videoW by kernel.videoWidth.collectAsState()
            val videoH by kernel.videoHeight.collectAsState()
            val videoRatio = if (videoW > 0 && videoH > 0) videoW.toFloat() / videoH else 0f
            val contentModifier = when (actions.videoScale) {
                VideoScaleMode.FIT ->
                    if (videoRatio > 0f) Modifier.aspectRatio(videoRatio) else Modifier.fillMaxSize()
                VideoScaleMode.R16_9 -> Modifier.aspectRatio(16f / 9f)
                VideoScaleMode.R9_16 -> Modifier.aspectRatio(9f / 16f)
                VideoScaleMode.R4_3 -> Modifier.aspectRatio(4f / 3f)
                VideoScaleMode.CROP -> Modifier.fillMaxSize().coverLayout(videoRatio)
                VideoScaleMode.STRETCH -> Modifier.fillMaxSize()
            }
            // TextureView 走普通视图层级渲染：避免 SurfaceView punch-hole 在模拟器/部分 ROM 上整面黑屏；
            // 视频面（自动/固定比例缩放后不满容器）居中显示
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                AndroidView(
                    factory = { context -> TextureView(context).also { textureView = it } },
                    modifier = contentModifier,
                )
            }
            // 内核的 ExoPlayer 由播放会话懒创建：组合时为 null，需等 player 就绪后绑定渲染面，
            // 否则视频帧无处输出（黑屏有声）。轻量轮询比对实例，覆盖内核内重建 player 的情形
            LaunchedEffect(target, textureView) {
                val view = textureView ?: return@LaunchedEffect
                var bound: ExoPlayer? = null
                while (isActive) {
                    val player = target.player
                    if (player != null && player != bound) {
                        runCatching { player.setVideoTextureView(view) }
                        bound = player
                    }
                    delay(200)
                }
            }
        }
        // 小窗态只留画面：控制层/面板全部隐藏（isInPip 由 Activity 回调驱动）
        if (!PipController.isInPip.collectAsState().value) {
            AndroidInPageControls(kernel = kernel, app = app, actions = actions, mode = mode)
            AndroidPlayerSheets(app = app, actions = actions)
        }
    }
    // 设置开启时进入后台自动暂停（画中画除外：PiP 是可见态的延续）
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(actions, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE &&
                actions.pauseOnBackground &&
                !PipController.isInPip.value &&
                kernel.isPlaying.value
            ) {
                kernel.togglePlayPause()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(media3) {
        media3?.let(PipController::attachKernel)
        onDispose {
            media3?.let(PipController::detachKernel)
            runCatching { media3?.player?.clearVideoSurface() }
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
internal enum class SheetKind { EPISODE, SOURCE, QUALITY, TIMER, MULTI_THREAD, SHARE }

/**
 * Android 控制层动作集：面板/提示/显隐状态驱动 + 播放器设置状态 + 定时关闭协程。
 * 设置项（图3 面板）读写均经 prefs 持久化，与桌面端键名对齐。
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

    /** 画面锁定（会话级，不持久化）：锁定后点画面不唤出控制层，仅可点锁解锁 */
    var isLocked: Boolean by mutableStateOf(false)
        private set

    /** 右侧内核/设置滑出面板可见性（图3/图4） */
    var kernelPanelVisible: Boolean by mutableStateOf(false)

    /** 显示上一集/下一集按钮 */
    var showPrevNext: Boolean by mutableStateOf(true)
        private set

    /** 进入后台暂停播放 */
    var pauseOnBackground: Boolean by mutableStateOf(false)
        private set

    /** 时间行显示总时长（false 仅显示当前时间） */
    var showTotalDuration: Boolean by mutableStateOf(true)
        private set

    /** 显示松手取消调整进度（进度手势拖回起点时提示并可取消） */
    var showSeekCancel: Boolean by mutableStateOf(true)
        private set

    /** 全局倍速（起播默认速率） */
    var globalSpeed: Float by mutableStateOf(1.0f)
        private set

    /** 快进快退秒数（0 不展示快进快退按钮） */
    var seekStepSec: Int by mutableStateOf(10)
        private set

    /** 长按倍速（0=关闭，手势在视频面长按触发） */
    var longPressRate: Float by mutableStateOf(0f)
        private set

    /** 画面调节模式（KEY_VIDEO_SCALE 持久化，跨端共享键；应用由布局层 TextureView 容器承担） */
    var videoScale: VideoScaleMode by mutableStateOf(VideoScaleMode.FIT)
        private set

    /** 手势反馈文案（拖动调亮度/音量/进度期间中央实时显示，松手清空） */
    var gestureFeedback: String? by mutableStateOf(null)

    /** 会话级播放音量（0-100；内核无音量回读，本地记录） */
    var playerVolume: Int by mutableStateOf(100)
        private set

    /** 静音（会话级）：静音=音量 0，解除恢复上次音量 */
    var isMuted: Boolean by mutableStateOf(false)
        private set

    /** 长按倍速生效中（松手恢复原倍速） */
    var isSpeedBoost: Boolean by mutableStateOf(false)
        private set

    /** 截图回调（播放面组合注入，依赖 TextureView 引用；null=当前环境不支持） */
    var onCaptureFrame: (() -> Unit)? by mutableStateOf(null)

    private val prefs get() = app.graph.prefs

    /** 定时关闭一次性任务（重复添加替换） */
    private var sleepJob: Job? = null

    private var disposed = false

    init {
        showPrevNext = prefs.getBoolean(PrefsKeys.KEY_PLAYER_SHOW_PREV_NEXT, true)
        pauseOnBackground = prefs.getBoolean(PrefsKeys.KEY_PLAYER_PAUSE_ON_BACKGROUND, false)
        showTotalDuration = prefs.getBoolean(PrefsKeys.KEY_PLAYER_SHOW_TOTAL_DURATION, true)
        showSeekCancel = prefs.getBoolean(PrefsKeys.KEY_SHOW_SEEK_CANCEL, true)
        globalSpeed = prefs.getFloat(PrefsKeys.KEY_GLOBAL_SPEED, 1.0f)
        seekStepSec = prefs.getInt(PrefsKeys.KEY_PLAYER_SEEK_STEP_SEC, 10)
        longPressRate = prefs.getFloat(PrefsKeys.KEY_PLAYER_LONG_PRESS_RATE, 0f)
        videoScale = VideoScaleMode.fromId(prefs.getString(PrefsKeys.KEY_VIDEO_SCALE, VideoScaleMode.FIT.id))
    }

    /** 组合销毁时取消定时任务（防悬挂协程） */
    fun dispose() {
        disposed = true
        sleepJob?.cancel()
    }

    override fun setBackground(hidden: Boolean) {
        if (!hidden) return
        sheet = null
        sidePanelVisible = false
        kernelPanelVisible = false
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

    /** 右侧内核/设置滑出面板（图3/图4） */
    fun showKernelPanel() {
        sheet = null
        sidePanelVisible = false
        kernelPanelVisible = true
        controlsVisible = true
    }

    /** 关闭右侧内核/设置面板 */
    fun closeKernelPanel() {
        kernelPanelVisible = false
    }

    /** 切换画面锁定：锁定时收起全部面板与控制层 */
    fun toggleLock() {
        isLocked = !isLocked
        if (isLocked) {
            closeSheets()
            kernelPanelVisible = false
            controlsVisible = false
            showHint("已锁定屏幕，点击左侧锁形图标解锁")
        } else {
            showHint("已解锁")
        }
    }

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

    /** 更新「显示上一集/下一集按钮」并持久化（改名 update 避免与 var 私有 setter JVM 签名冲突） */
    fun updateShowPrevNext(value: Boolean) {
        showPrevNext = value
        prefs.put(PrefsKeys.KEY_PLAYER_SHOW_PREV_NEXT, value)
    }

    /** 更新「进入后台暂停播放」并持久化 */
    fun updatePauseOnBackground(value: Boolean) {
        pauseOnBackground = value
        prefs.put(PrefsKeys.KEY_PLAYER_PAUSE_ON_BACKGROUND, value)
    }

    /** 更新「显示总时长」并持久化 */
    fun updateShowTotalDuration(value: Boolean) {
        showTotalDuration = value
        prefs.put(PrefsKeys.KEY_PLAYER_SHOW_TOTAL_DURATION, value)
    }

    /** 更新「显示松手取消调整进度」并持久化 */
    fun updateShowSeekCancel(value: Boolean) {
        showSeekCancel = value
        prefs.put(PrefsKeys.KEY_SHOW_SEEK_CANCEL, value)
    }

    /** 更新全局倍速：立即生效于当前播放并持久化 */
    fun updateGlobalSpeed(value: Float) {
        globalSpeed = value
        prefs.put(PrefsKeys.KEY_GLOBAL_SPEED, value)
        kernel.setRate(value)
    }

    /** 更新快进快退秒数（0 不展示按钮）并持久化 */
    fun updateSeekStepSec(value: Int) {
        seekStepSec = value
        prefs.put(PrefsKeys.KEY_PLAYER_SEEK_STEP_SEC, value)
    }

    /** 更新长按倍速（0=关闭）并持久化 */
    fun updateLongPressRate(value: Float) {
        longPressRate = value
        prefs.put(PrefsKeys.KEY_PLAYER_LONG_PRESS_RATE, value)
    }

    /** 更新画面调节模式并持久化（布局层随重组即时生效） */
    fun updateVideoScale(mode: VideoScaleMode) {
        videoScale = mode
        prefs.put(PrefsKeys.KEY_VIDEO_SCALE, mode.id)
    }

    /** 会话级静音（共享行为）：静音=音量 0，解除恢复上次音量；调高音量自动解除 */
    private val muteSession = MuteSession(kernel::setVolume).also { session ->
        session.onMuteChanged = { isMuted = it }
    }

    /** 更新静音（音量落地由 MuteSession 经内核生效） */
    fun updateMuted(value: Boolean) {
        muteSession.setMuted(value, playerVolume)
    }

    /** 手势调音量（0-100 即时生效；调高时顺带解除静音） */
    fun adjustVolume(percent: Int) {
        playerVolume = muteSession.onVolumeAdjusted(percent)
    }

    /** 进入长按倍速（0 档不生效） */
    fun beginSpeedBoost() {
        if (longPressRate <= 0f || isSpeedBoost) return
        isSpeedBoost = true
        kernel.setRate(longPressRate)
        showHint(GestureText.speedBoost(longPressRate))
    }

    /** 退出长按倍速：恢复循环档位速率（内核不回读，由控制层回调写回） */
    fun endSpeedBoost(restoreRate: Float) {
        if (!isSpeedBoost) return
        isSpeedBoost = false
        kernel.setRate(restoreRate)
    }
}

/** 裁剪铺满：视频面按视频比例外扩至完全覆盖容器并居中，溢出边由外层 clipToBounds 裁掉 */
private fun Modifier.coverLayout(videoRatio: Float): Modifier = layout { measurable, constraints ->
    val w = constraints.maxWidth
    val h = constraints.maxHeight
    if (videoRatio <= 0f) {
        val placeable = measurable.measure(constraints)
        layout(w, h) { placeable.place(0, 0) }
    } else {
        val coverWidth = maxOf(w, (h * videoRatio).toInt())
        val coverHeight = maxOf(h, (w / videoRatio).toInt())
        val placeable = measurable.measure(
            Constraints(minWidth = coverWidth, maxWidth = coverWidth, minHeight = coverHeight, maxHeight = coverHeight),
        )
        layout(w, h) { placeable.place(-(coverWidth - w) / 2, -(coverHeight - h) / 2) }
    }
}

/** 截图保存到系统相册「Pictures/视界」目录（IO 线程调用），返回是否成功 */
private fun saveBitmapToGallery(context: Context, bitmap: Bitmap): Boolean {
    val resolver = context.contentResolver
    val uri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val name = "shijie_%s.jpg".format(SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(Date()))
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/视界")
        }
        resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
    } else {
        @Suppress("DEPRECATION")
        MediaStore.Images.Media.insertImage(resolver, bitmap, "视界", null)?.let(Uri::parse)
    }
    if (uri == null) return false
    resolver.openOutputStream(uri)?.use { stream ->
        bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream)
    } ?: return false
    return true
}
