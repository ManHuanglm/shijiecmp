package com.shiping.desktop.player

import com.shiping.desktop.util.AppLog
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.awt.Canvas
import java.awt.Color
import java.awt.Component
import java.awt.Point
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.util.concurrent.Executors
import javax.swing.SwingUtilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 三内核（mpv/vlc/mdk）播放控制器公共骨架（模板方法）：
 * - 状态流 9 组 + 视频面 Canvas（addNotify 后台线程延迟初始化、HWND 捕获与重挂分发）
 * - pendingPlay 挂起排队与双重检查、playExecutor 串行派发
 * - 原生 ShowWindow 显隐、HWND 位置/尺寸原生同步（locationOnScreen 换算）
 * 子类仅实现内核差异：native 初始化与播放控制、重挂/画布变化的迁移动作、事件驱动方式。
 */
abstract class AbstractKernelController(protected val prefs: com.shiping.shared.prefs.KeyValueStore) : PlayerController {

    /** 日志 TAG（各内核命名，如 MpvPlayer） */
    protected abstract val logTag: String

    /** 延迟初始化后台线程名（如 mpv-init） */
    protected abstract val initThreadName: String

    /** 内核初始化失败引导文案（缺 dll 等时展示） */
    protected abstract fun initErrorMessage(t: Throwable): String

    // region 状态流（子类事件/轮询线程写入，StateFlow 线程安全）

    protected val _isPlaying = MutableStateFlow(false)
    final override val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    protected val _timeMs = MutableStateFlow(0L)
    final override val timeMs: StateFlow<Long> = _timeMs.asStateFlow()

    protected val _durationMs = MutableStateFlow(0L)
    final override val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    protected val _videoWidth = MutableStateFlow(0)
    final override val videoWidth: StateFlow<Int> = _videoWidth.asStateFlow()

    protected val _videoHeight = MutableStateFlow(0)
    final override val videoHeight: StateFlow<Int> = _videoHeight.asStateFlow()

    protected val _isBuffering = MutableStateFlow(false)
    final override val isBuffering: StateFlow<Boolean> = _isBuffering.asStateFlow()

    protected val _errorText = MutableStateFlow<String?>(null)
    final override val errorText: StateFlow<String?> = _errorText.asStateFlow()

    protected val _initError = MutableStateFlow<String?>(null)
    final override val initError: StateFlow<String?> = _initError.asStateFlow()

    // endregion

    /** 视频面：各内核均渲染进此 Canvas 的原生窗口句柄 */
    protected val canvas: Canvas = object : Canvas() {
        init {
            // 渲染未就绪/失败时显示黑色而非默认白底
            background = Color.BLACK
        }

        override fun addNotify() {
            super.addNotify()
            // peer 创建后立即捕获 HWND（须在 EDT）；peer 重建（组件树重挂/全屏切换）后
            // 句柄变化，内核仍向旧句柄渲染会花屏/黑屏，需运行时重挂视频面
            val hwnd = runCatching { Pointer.nativeValue(Native.getComponentPointer(this)) }
                .getOrElse { 0L }
            if (hwnd == 0L) return
            val reattach = hwndValue != 0L && hwnd != hwndValue && isReady
            hwndValue = hwnd
            if (reattach) {
                onSurfaceReattached()
                return
            }
            Thread({ ensureInitialized() }, initThreadName).apply { isDaemon = true }.start()
        }

        override fun removeNotify() {
            // 视频面离场通知（子类按需处理，如 mpv 的重挂容错计时）
            onSurfaceDetached()
            super.removeNotify()
        }
    }

    init {
        canvas.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                // 内核接管 HWND 后 AWT setBounds 失效，画布变化由子类原生同步
                onCanvasResized()
            }

            override fun componentMoved(e: ComponentEvent) {
                onCanvasMoved()
            }
        })
    }

    final override val swingComponent: Component? get() = canvas

    /** Canvas 原生窗口句柄（addNotify 后有效） */
    @Volatile
    protected var hwndValue: Long = 0L
        private set

    // region 模板钩子（子类按内核差异覆写）

    /** 视频面重挂（HWND 变化）后的迁移动作（默认无） */
    protected open fun onSurfaceReattached() {}

    /** 视频面移除（HWND 消亡窗口期）通知（默认无） */
    protected open fun onSurfaceDetached() {}

    /** 画布尺寸变化后的原生同步（默认无；vlc/mdk 搬窗，mdk 另重排视口） */
    protected open fun onCanvasResized() {}

    /** 画布位置变化后的原生同步（默认无） */
    protected open fun onCanvasMoved() {}

    /** 恢复显示视频面后的额外动作（vlc/mdk 搬窗，mdk 另重排视口） */
    protected open fun onSurfaceRestored() {}

    // endregion

    // region 播放请求排队（模板方法）

    /** 一次播放请求（内核未就绪时挂起排队） */
    protected data class PlayRequest(val url: String, val headers: Map<String, String>, val startMs: Long)

    @Volatile
    private var pendingPlay: PlayRequest? = null

    final override fun play(url: String, headers: Map<String, String>, startMs: Long) {
        val request = PlayRequest(url, headers, startMs)
        if (!isReady) {
            pendingPlay = request
            // 双重检查：pendingPlay 写入与初始化完成并发，内核可能刚好已就绪
            if (isReady) {
                pendingPlay = null
                dispatchPlay(request)
            }
            return
        }
        dispatchPlay(request)
    }

    /**
     * 播放串行执行器（懒创建，避免未用线程；setMedia/prepare 等阻塞调用
     * 必须离开调用线程执行，否则阻塞 Compose/EDT 线程导致整应用卡死）
     */
    private val playExecutor by lazy {
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "$initThreadName-play").apply { isDaemon = true }
        }
    }

    /** 播放请求派发（默认后台执行器串行保序；mpv 命令异步无需排队，覆写直连） */
    protected open fun dispatchPlay(request: PlayRequest) {
        playExecutor.execute { actualPlay(request) }
    }

    /** 实际播放（须内核就绪） */
    protected abstract fun actualPlay(request: PlayRequest)

    /** 关停播放执行器（release 时调用） */
    protected fun shutdownPlayExecutor() {
        playExecutor.shutdownNow()
    }

    // endregion

    /** 即时切换画面比例（双写偏好保证下次起播仍生效，内核应用由子类实现） */
    final override fun setVideoScale(scale: String) {
        prefs.put(com.shiping.shared.prefs.PrefsKeys.KEY_VIDEO_SCALE, scale)
        applyVideoScaleNow(scale)
    }

    /** 应用画面比例到内核（fit=原始尺寸 / crop=裁剪铺满 / stretch=拉伸画面） */
    protected abstract fun applyVideoScaleNow(scale: String)

    // region 延迟初始化（模板方法）

    /** 初始化互斥（仅执行一次，失败不重试避免刷日志） */
    private val initMutex = Any()

    @Volatile
    private var initAttempted = false

    /** 确保初始化只尝试一次（Canvas addNotify 后由后台线程调用） */
    protected fun ensureInitialized() {
        synchronized(initMutex) {
            if (initAttempted) return
            initAttempted = true
        }
        try {
            doInitialize()
            // 初始化晚于 play() 的情况：起播挂起的请求
            pendingPlay?.let { pending ->
                pendingPlay = null
                dispatchPlay(pending)
            }
        } catch (t: Throwable) {
            AppLog.e(logTag, "内核初始化失败: ${t.message}\n${t.stackTraceToString()}")
            _initError.value = initErrorMessage(t)
        }
    }

    /** 内核初始化（完成后由基类派发挂起播放） */
    protected abstract fun doInitialize()

    // endregion

    /**
     * 原生 ShowWindow 控制 HWND 显隐：内核接管后 AWT setVisible 对原生窗口失效，
     * 显隐统一走原生接口；恢复显示后的搬窗/重排由子类 onSurfaceRestored 完成。
     */
    final override fun setSurfaceVisible(visible: Boolean) {
        Win32Surface.setVisible(hwndValue, visible)
        if (visible) onSurfaceRestored()
    }

    /** 当前视频面在 AWT 逻辑布局中的期望屏幕位置（原生搬窗换算用） */
    private fun canvasScreenLocation(): Point? = runCatching {
        val window = SwingUtilities.getWindowAncestor(canvas) ?: return null
        val compLoc = canvas.locationOnScreen
        val winLoc = window.locationOnScreen
        val insets = window.insets
        Point(compLoc.x - winLoc.x - insets.left, compLoc.y - winLoc.y - insets.top)
    }.getOrNull()

    /** 原生同步 HWND 位置与尺寸（内核接管后 AWT 设界失效） */
    protected fun syncSurfaceSize() {
        if (!isReady) return
        val hwnd = hwndValue
        if (hwnd == 0L) return
        val loc = canvasScreenLocation() ?: return
        Win32Surface.moveWindow(hwnd, loc.x, loc.y, canvas.width, canvas.height)
    }
}
