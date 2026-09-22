package com.shiping.desktop.player

import com.shiping.desktop.util.AppLog
import com.sun.jna.Function
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import java.io.File

/** mdk-sdk C API 直接导出函数（include/mdk/c/Player.h 底部） */
private interface MdkLib : Library {
    fun mdkPlayerAPI_new(): Pointer?
    fun mdkPlayerAPI_delete(api: Pointer)

    /** 读取视频流编码参数（含宽高，见 include/mdk/c/MediaInfo.h） */
    fun MDK_VideoStreamCodecParameters(stream: Pointer, params: Pointer)
}

/**
 * mdkSeekCallback / mdkPrepareCallback 的按值传参结构（{函数指针, opaque}）。
 * 置零即表示无回调，与 C++ 封装默认参数行为一致。
 * 注意：JNA 经反射读写 Structure 字段，类必须对外可见，不能用 private 修饰。
 */
@Structure.FieldOrder("callback", "opaque")
class MdkCallbackPair : Structure(), Structure.ByValue {
    @JvmField
    var callback: Pointer? = null

    @JvmField
    var opaque: Pointer? = null
}

/**
 * MDK 播放内核控制器（JNA 绑定 mdk-sdk 的 mdk.dll，与 mpv 内核可切换共存）。
 *
 * C API 形态：mdkPlayerAPI_new() 返回一张函数指针表（mdkPlayerAPI 结构），
 * 首成员为 mdkPlayer 句柄。JNA 以 Structure 按头文件声明顺序映射整表，
 * 调用时经 Function.getFunction(函数指针) 动态调用。
 *
 * 嵌入方式：视频面 Canvas 获得原生窗口句柄（addNotify 后）调用
 * updateNativeSurface(HWND, w, h, Auto)，mdk 自建 GL 上下文渲染进该窗口；
 * Canvas 尺寸变化时重复调用以请求重排版面。
 *
 * 状态同步：后台线程 200ms 轮询 state()/mediaStatus()/position()/mediaInfo().duration
 * 更新 StateFlow（mdk 读接口均为线程安全查询，无需注册回调），另含视频面几何 watchdog。
 *
 * 用户设置（画面比例）在初始化时从 DesktopPrefs 读取注入，修改后对下次
 * 打开的播放窗口生效；硬解码开关为 mpv 页专属设置，MDK 固定硬件解码优先、
 * 自动回退软解（decoders 列表尾部为 FFDBG）。
 */
class MdkPlayerController(prefs: com.shiping.shared.prefs.KeyValueStore) : AbstractKernelController(prefs) {

    companion object {
        // MDK_State
        private const val STATE_STOPPED = 0
        private const val STATE_RUNNING = 1
        private const val STATE_PAUSED = 2

        // MDK_MediaStatus 位标志
        private const val STATUS_LOADING = 1 shl 1
        private const val STATUS_LOADED = 1 shl 2
        private const val STATUS_BUFFERING = 1 shl 4
        private const val STATUS_BUFFERED = 1 shl 5
        private const val STATUS_END = 1 shl 6
        private const val STATUS_INVALID = 1 shl 31

        // MDK_SeekFlag：FromStart（精确 seek，内部隐含向后）
        private const val SEEK_FROM_START = 1 shl 1

        // MDK_SurfaceType：Auto
        private const val SURFACE_TYPE_AUTO = 0

        /** 起播加载超时：RUNNING 无进度超过该时长判定源不可达（分片 502 等挂源 mdk 会无限 Loading） */
        private const val LOADING_TIMEOUT_MS = 15_000L

        /** 视频面几何 watchdog 周期（轮询拍数，每拍 200ms，10 拍 = 2s） */
        private const val POLL_VERIFY_TICKS = 10

        /** 解码器优先级：硬解码关闭设置不约束 MDK，始终硬解优先并自动回退软解 */
        private const val VIDEO_DECODERS = "d3d11,MFT,NVDEC,FFDBG"

        // mdkMediaInfo 字段偏移（字节）：start_time@0 / duration@8
        private const val MEDIA_INFO_DURATION_OFFSET = 8L

        // mdkMediaInfo：video 流数组指针@64 / nb_video@72
        private const val MEDIA_INFO_VIDEO_PTR_OFFSET = 64L
        private const val MEDIA_INFO_VIDEO_COUNT_OFFSET = 72L

        // mdkVideoCodecParameters：width@64 / height@68（0.38.0 mdk.dll 实测偏移，
        // MDK_VideoStreamCodecParameters 填充；80/84 处恒为 0）
        private const val VIDEO_CODEC_WIDTH_OFFSET = 64L
        private const val VIDEO_CODEC_HEIGHT_OFFSET = 68L
        private const val VIDEO_CODEC_PARAMS_SIZE = 256

        /** FLT_EPSILON：保持视频宽高比在视口内最大化（等比适应，mdk 默认值） */
        private const val ASPECT_FIT = 1.1920929E-7f

        /** 定位捆绑/自带 mdk-sdk 目录（含 mdk.dll），未找到返回 null */
        fun findMdkDir(): String? {
            val candidates = mutableListOf<String>()
            System.getProperty("compose.application.resources.dir")?.let { candidates += "$it/mdk" }
            // 安装包（msi/exe）不含 resources 目录，从应用 jar 内提取兜底
            BundledNative.ensureDir("mdk")?.let { candidates += it.absolutePath }
            val userDir = System.getProperty("user.dir")
            candidates += listOf(
                "$userDir/mdk",
                "$userDir/desktop-app/app-resources/mdk",
                "$userDir/app-resources/mdk",
            )
            return candidates.firstOrNull { dir -> File(dir, "mdk.dll").exists() }
        }
    }

    override val logTag: String = "MdkPlayer"

    override val initThreadName: String = "mdk-init"

    override fun initErrorMessage(t: Throwable): String =
        "未检测到 MDK 播放内核（mdk.dll）。可在设置页切换回 mpv 内核。\n${t.message ?: ""}"

    /** mdk 句柄与函数表访问互斥（初始化/播放/释放跨线程） */
    private val mdkLock = Any()

    private var lib: MdkLib? = null

    /** 函数指针表（mdkPlayerAPI*），mdkLock 保护 */
    private var apiTable: PlayerApiTable? = null

    /** mdkPlayer 句柄，mdkLock 保护 */
    private var api: Pointer? = null

    override val isReady: Boolean get() = api != null

    /** 轮询线程生命周期标记 */
    @Volatile
    private var running = false

    private var pollThread: Thread? = null

    /** Invalid 错误只报一次（play 时复位） */
    @Volatile
    private var errorReported = false

    /** 起播加载起始时刻（0=未在加载；RUNNING 无进度超过 LOADING_TIMEOUT_MS 判定源不可达） */
    @Volatile
    private var loadingSinceMs = 0L

    /** 最近一次 updateNativeSurface 传入的表面尺寸（watchdog 比对视口是否滞后画布） */
    private var lastAttachedWidth = 0

    private var lastAttachedHeight = 0

    /**
     * mdkPlayerAPI 函数指针表（include/mdk/c/Player.h，按声明顺序完整映射）。
     * 仅需其中一部分；未用字段仍须声明以保持偏移一致。
     * 注意：JNA 布局计算经反射读取字段值，类不可见会抛 IllegalAccessException，
     * 因此不能用 private 修饰（此前 MDK 起播失败即栽在这里）。
     */
    @Structure.FieldOrder(
        "handle", "setMute", "setVolume", "setMedia", "setMediaForType", "url",
        "setPreloadImmediately", "setNextMedia", "currentMediaChanged", "setAudioBackends",
        "setAudioDecoders", "setVideoDecoders", "setTimeout", "prepare", "mediaInfo",
        "setState", "state", "onStateChanged", "waitFor", "mediaStatus",
        "onMediaStatusChanged", "updateNativeSurface", "createSurface", "resizeSurface",
        "showSurface", "getVideoFrame", "setVideoSurfaceSize", "setVideoViewport",
        "setAspectRatio", "rotate", "scale", "renderVideo", "setBackgroundColor",
        "setRenderCallback", "onVideo", "onAudio", "beforeVideoRender", "afterVideoRender",
        "position", "seekWithFlags", "seek", "setPlaybackRate", "playbackRate", "buffered",
        "switchBitrate", "switchBitrateSingleConnection", "onEvent", "setBufferRange",
        "snapshot", "setProperty", "getProperty", "record", "setLoopRange", "setLoop",
        "onLoop", "setRange", "setRenderAPI", "renderAPI", "mapPoint", "onSync",
        "setVideoEffect", "setActiveTracks", "setDecoders", "setChannelVolume",
        "setFrameRate", "setPointMap", "setColorSpace", "onMediaStatus", "structSize",
        "enqueueVideo", "bufferedTimeRanges", "appendBuffer", "subtitleText", "setAudioMix",
        "onSubtitleText",
    )
    class PlayerApiTable : Structure() {

        /** 附着到 mdkPlayerAPI_new 返回的函数表内存并读入字段（useMemory 为 protected，仅类内可调） */
        fun attachTo(pointer: Pointer) {
            useMemory(pointer)
            read()
        }

        @JvmField
        var handle: Pointer? = null
        @JvmField var setMute: Pointer? = null
        @JvmField var setVolume: Pointer? = null
        @JvmField var setMedia: Pointer? = null
        @JvmField var setMediaForType: Pointer? = null
        @JvmField var url: Pointer? = null
        @JvmField var setPreloadImmediately: Pointer? = null
        @JvmField var setNextMedia: Pointer? = null
        @JvmField var currentMediaChanged: Pointer? = null
        @JvmField var setAudioBackends: Pointer? = null
        @JvmField var setAudioDecoders: Pointer? = null
        @JvmField var setVideoDecoders: Pointer? = null
        @JvmField var setTimeout: Pointer? = null
        @JvmField var prepare: Pointer? = null
        @JvmField var mediaInfo: Pointer? = null
        @JvmField var setState: Pointer? = null
        @JvmField var state: Pointer? = null
        @JvmField var onStateChanged: Pointer? = null
        @JvmField var waitFor: Pointer? = null
        @JvmField var mediaStatus: Pointer? = null
        @JvmField var onMediaStatusChanged: Pointer? = null
        @JvmField var updateNativeSurface: Pointer? = null
        @JvmField var createSurface: Pointer? = null
        @JvmField var resizeSurface: Pointer? = null
        @JvmField var showSurface: Pointer? = null
        @JvmField var getVideoFrame: Pointer? = null
        @JvmField var setVideoSurfaceSize: Pointer? = null
        @JvmField var setVideoViewport: Pointer? = null
        @JvmField var setAspectRatio: Pointer? = null
        @JvmField var rotate: Pointer? = null
        @JvmField var scale: Pointer? = null
        @JvmField var renderVideo: Pointer? = null
        @JvmField var setBackgroundColor: Pointer? = null
        @JvmField var setRenderCallback: Pointer? = null
        @JvmField var onVideo: Pointer? = null
        @JvmField var onAudio: Pointer? = null
        @JvmField var beforeVideoRender: Pointer? = null
        @JvmField var afterVideoRender: Pointer? = null
        @JvmField var position: Pointer? = null
        @JvmField var seekWithFlags: Pointer? = null
        @JvmField var seek: Pointer? = null
        @JvmField var setPlaybackRate: Pointer? = null
        @JvmField var playbackRate: Pointer? = null
        @JvmField var buffered: Pointer? = null
        @JvmField var switchBitrate: Pointer? = null
        @JvmField var switchBitrateSingleConnection: Pointer? = null
        @JvmField var onEvent: Pointer? = null
        @JvmField var setBufferRange: Pointer? = null
        @JvmField var snapshot: Pointer? = null
        @JvmField var setProperty: Pointer? = null
        @JvmField var getProperty: Pointer? = null
        @JvmField var record: Pointer? = null
        @JvmField var setLoopRange: Pointer? = null
        @JvmField var setLoop: Pointer? = null
        @JvmField var onLoop: Pointer? = null
        @JvmField var setRange: Pointer? = null
        @JvmField var setRenderAPI: Pointer? = null
        @JvmField var renderAPI: Pointer? = null
        @JvmField var mapPoint: Pointer? = null
        @JvmField var onSync: Pointer? = null
        @JvmField var setVideoEffect: Pointer? = null
        @JvmField var setActiveTracks: Pointer? = null
        @JvmField var setDecoders: Pointer? = null
        @JvmField var setChannelVolume: Pointer? = null
        @JvmField var setFrameRate: Pointer? = null
        @JvmField var setPointMap: Pointer? = null
        @JvmField var setColorSpace: Pointer? = null
        @JvmField var onMediaStatus: Pointer? = null
        @JvmField var structSize: Pointer? = null
        @JvmField var enqueueVideo: Pointer? = null
        @JvmField var bufferedTimeRanges: Pointer? = null
        @JvmField var appendBuffer: Pointer? = null
        @JvmField var subtitleText: Pointer? = null
        @JvmField var setAudioMix: Pointer? = null
        @JvmField var onSubtitleText: Pointer? = null
    }

    // region 函数表调用辅助
    // mdk 各 vtable 函数首参均为 mdkPlayer 句柄（C++ 宏 MDK_CALL(p, fn, ...) 即 p->fn(p, ...)），
    // 以下辅助统一前置句柄后再附加业务参数。

    private fun PlayerApiTable.fn(selector: (PlayerApiTable) -> Pointer?): Function? =
        selector(this)?.let { Function.getFunction(it) }

    private fun PlayerApiTable.call(selector: (PlayerApiTable) -> Pointer?, vararg args: Any?) {
        val handle = api ?: return
        fn(selector)?.invoke(arrayOf(handle, *args))
    }

    private fun PlayerApiTable.callInt(selector: (PlayerApiTable) -> Pointer?, vararg args: Any?): Int {
        val handle = api ?: return 0
        return fn(selector)?.invokeInt(arrayOf(handle, *args)) ?: 0
    }

    private fun PlayerApiTable.callLong(selector: (PlayerApiTable) -> Pointer?, vararg args: Any?): Long {
        val handle = api ?: return 0L
        return fn(selector)?.invokeLong(arrayOf(handle, *args)) ?: 0L
    }

    private fun PlayerApiTable.callPointer(selector: (PlayerApiTable) -> Pointer?, vararg args: Any?): Pointer? {
        val handle = api ?: return null
        return fn(selector)?.invokePointer(arrayOf(handle, *args))
    }

    // endregion

    /** 实际播放（须内核就绪；与初始化完成回调可能并发，mdkLock 串行化） */
    override fun actualPlay(request: PlayRequest) {
        synchronized(mdkLock) {
            val table = apiTable ?: return
            _errorText.value = null
            _timeMs.value = 0L
            _durationMs.value = 0L
            _isPlaying.value = false
            _isBuffering.value = false
            errorReported = false
            loadingSinceMs = 0L
            // io 选项与解码器列表须在 setMedia 前设置（avio.* 为 demuxer 打开参数）
            applyHeaderProperties(table, request.headers)
            table.call({ it.setProperty }, "video.decoders", VIDEO_DECODERS)
            // 播放中切新地址：先停干净再 setMedia（mdk 要求 Stopped 后再 Playing）
            if (table.callInt({ it.state }) != STATE_STOPPED) {
                table.call({ it.setState }, STATE_STOPPED)
                table.callInt({ it.waitFor }, STATE_STOPPED, 800L)
            }
            table.call({ it.setMedia }, request.url)
            if (request.startMs > 0) {
                // prepare(ms) 预载到指定位置（完成后处于 Paused），再置 Playing 起播
                table.call({ it.prepare }, request.startMs, MdkCallbackPair(), SEEK_FROM_START)
            }
            table.call({ it.setState }, STATE_RUNNING)
            applyUserSettings(table)
            AppLog.i(logTag, "play: ${request.url} startMs=${request.startMs}")
        }
    }

    override fun stop() {
        synchronized(mdkLock) {
            val table = apiTable ?: return
            table.call({ it.setState }, STATE_STOPPED)
        }
        _isPlaying.value = false
        _isBuffering.value = false
        _timeMs.value = 0L
        _durationMs.value = 0L
        _errorText.value = null
    }

    override fun togglePlayPause() {
        synchronized(mdkLock) {
            val table = apiTable ?: return
            when (table.callInt({ it.state })) {
                STATE_RUNNING -> table.call({ it.setState }, STATE_PAUSED)
                STATE_PAUSED -> table.call({ it.setState }, STATE_RUNNING)
            }
        }
    }

    override fun seekTo(ms: Long) {
        synchronized(mdkLock) {
            val table = apiTable ?: return
            val duration = _durationMs.value
            val target = if (duration > 0) ms.coerceIn(0L, duration - 500L) else ms.coerceAtLeast(0L)
            table.callInt({ it.seekWithFlags }, target, SEEK_FROM_START, MdkCallbackPair())
            _timeMs.value = target
        }
    }

    override fun setRate(rate: Float) {
        synchronized(mdkLock) {
            apiTable?.call({ it.setPlaybackRate }, rate)
        }
    }

    override fun setVolume(volume: Int) {
        synchronized(mdkLock) {
            apiTable?.call({ it.setVolume }, volume.coerceIn(0, 100) / 100f)
        }
    }

    override fun applyVideoScaleNow(scale: String) {
        synchronized(mdkLock) {
            apiTable?.let { applyVideoScale(it, scale) }
        }
    }

    /** mdk C API 未暴露截图接口，暂不支持 */
    override fun snapshot(): String? = null

    /** 重挂：重新 updateNativeSurface 让渲染迁移到新窗口 */
    override fun onSurfaceReattached() {
        attachSurface(canvas.width.coerceAtLeast(1), canvas.height.coerceAtLeast(1))
    }

    override fun onCanvasResized() {
        // mdk 绑定后 AWT setBounds 对原生窗口失效，画布变化须原生 MoveWindow 同步，
        // 再同句柄重复 updateNativeSurface 请求按新尺寸重排视口
        syncSurfaceSize()
        attachSurface(canvas.width, canvas.height)
    }

    override fun onCanvasMoved() {
        // 布局移动（如详情页从初始位滚动/定位到内嵌位）不改尺寸，单独同步位置
        syncSurfaceSize()
    }

    override fun onSurfaceRestored() {
        syncSurfaceSize()
        attachSurface(canvas.width.coerceAtLeast(1), canvas.height.coerceAtLeast(1))
    }

    /** 释放 native 资源：先停播放执行器与轮询线程，再 delete 函数表与播放器 */
    override fun release() {
        running = false
        shutdownPlayExecutor()
        runCatching { pollThread?.join(1000) }
        synchronized(mdkLock) {
            val table = apiTable ?: return
            apiTable = null
            val handle = api
            api = null
            val l = lib
            lib = null
            if (handle != null) {
                // mdkPlayerAPI_delete(const mdkPlayerAPI**)：传指针的指针
                val holder = Memory(Native.POINTER_SIZE.toLong())
                holder.setPointer(0, handle)
                runCatching { l?.mdkPlayerAPI_delete(holder) }
            }
            AppLog.i(logTag, "mdk 内核已释放")
        }
    }

    // region 初始化

    override fun doInitialize() {
        val dir = findMdkDir() ?: throw IllegalStateException("未找到 mdk.dll（捆绑目录/应用目录 mdk 下）")
        // 追加而非覆盖 jna.library.path：保留 mpv 内核路径，同会话切换内核后仍可回退
        val existing = System.getProperty("jna.library.path")
        System.setProperty(
            "jna.library.path",
            if (existing.isNullOrBlank()) dir else "$existing${File.pathSeparator}$dir",
        )
        val l = Native.load("mdk", MdkLib::class.java)
        val apiPtr = l.mdkPlayerAPI_new() ?: throw IllegalStateException("mdkPlayerAPI_new 返回空")
        val table = PlayerApiTable()
        table.attachTo(apiPtr)
        if (table.handle == null) throw IllegalStateException("mdkPlayerAPI 句柄为空")
        lib = l
        apiTable = table
        api = table.handle
        table.call({ it.setProperty }, "video.decoders", VIDEO_DECODERS)
        attachSurface(canvas.width, canvas.height)
        applyUserSettings(table)
        running = true
        pollThread = Thread({ pollLoop(table) }, "mdk-poll").apply {
            isDaemon = true
            start()
        }
        AppLog.i(logTag, "mdk 内核就绪: $dir")
    }

    /** 视频面附着：把 Canvas 原生窗口句柄交给 mdk 渲染（同句柄重复调用即重排视口） */
    private fun attachSurface(width: Int, height: Int) {
        synchronized(mdkLock) {
            val table = apiTable ?: return
            if (hwndValue == 0L) return
            val w = width.coerceAtLeast(1)
            val h = height.coerceAtLeast(1)
            lastAttachedWidth = w
            lastAttachedHeight = h
            table.call(
                { it.updateNativeSurface },
                Pointer(hwndValue),
                w,
                h,
                SURFACE_TYPE_AUTO,
            )
        }
    }

    /** 注入设置页的播放偏好：画面比例（fit=等比适应默认 / crop=裁剪铺满 / stretch=拉伸） */
    private fun applyUserSettings(table: PlayerApiTable) {
        applyVideoScale(table, prefs.getString(com.shiping.shared.prefs.PrefsKeys.KEY_VIDEO_SCALE, "fit"))
    }

    /** 画面比例应用：crop=等比外扩铺满 / stretch=无视宽高比拉伸 / fit=等比适应（mdk 默认） */
    private fun applyVideoScale(table: PlayerApiTable, scale: String) {
        when (scale) {
            "crop" -> table.call({ it.setAspectRatio }, -ASPECT_FIT, null as Pointer?)
            "stretch" -> table.call({ it.setAspectRatio }, 0f, null as Pointer?)
        }
    }

    /**
     * 注入请求头：UA/Referer 走 avio.* 属性（setMedia 前设置生效）；
     * 其余 header 已由 PlaybackUrlResolver 包装为本地 /hdr 代理。
     */
    private fun applyHeaderProperties(table: PlayerApiTable, headers: Map<String, String>) {
        val userAgent = headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value.orEmpty()
        val referer = headers.entries.firstOrNull { it.key.equals("Referer", true) }?.value.orEmpty()
        table.call({ it.setProperty }, "avio.user_agent", userAgent)
        table.call({ it.setProperty }, "avio.referer", referer)
    }

    // endregion

    // region 状态轮询

    /** 200ms 轮询 state/mediaStatus/position/duration 更新 StateFlow（读接口线程安全） */
    private fun pollLoop(table: PlayerApiTable) {
        var verifyTick = 0
        while (running) {
            try {
                Thread.sleep(200)
            } catch (_: InterruptedException) {
                break
            }
            if (!running) break
            runCatching { pollOnce(table) }
            // 每 2s 校对视频面几何：事件驱动有时序漏洞（重挂后 AWT bounds 恰好未变则无事件，
            // HWND 停在挂载初位），周期比对实际矩形与布局期望并原生纠正
            if (++verifyTick >= POLL_VERIFY_TICKS) {
                verifyTick = 0
                javax.swing.SwingUtilities.invokeLater { verifySurfaceGeometry() }
            }
        }
    }

    /** 校对 HWND 实际屏幕矩形与 AWT 布局期望，不一致（位置/尺寸漂移）即原生纠正 */
    private fun verifySurfaceGeometry() {
        if (!running || !isReady) return
        val hwnd = hwndValue
        if (hwnd == 0L) return
        val loc = runCatching { canvas.locationOnScreen }.getOrElse { return }
        val width = canvas.width
        val height = canvas.height
        val actual = Win32Surface.getWindowRect(hwnd) ?: return
        if (actual[0] != loc.x || actual[1] != loc.y ||
            actual[2] - actual[0] != width || actual[3] - actual[1] != height
        ) {
            AppLog.i(logTag, "视频面几何漂移，纠正: 实际=${actual.toList()} 期望=(${loc.x},${loc.y},${width}x${height})")
            syncSurfaceSize()
            attachSurface(width, height)
        } else if (width != lastAttachedWidth || height != lastAttachedHeight) {
            // HWND 矩形已对齐但 mdk 视口停在旧表面尺寸（componentResized 漏触发）——重排视口
            AppLog.i(logTag, "视口尺寸滞后画布，重排: 视口=${lastAttachedWidth}x${lastAttachedHeight} 画布=${width}x${height}")
            attachSurface(width, height)
        }
    }

    private fun pollOnce(table: PlayerApiTable) {
        synchronized(mdkLock) {
            val state = table.callInt({ it.state })
            val status = table.callInt({ it.mediaStatus })
            val ended = (status and STATUS_END) != 0
            _isPlaying.value = state == STATE_RUNNING && !ended
            _isBuffering.value =
                state == STATE_RUNNING && !ended &&
                (status and STATUS_BUFFERING) != 0 && (status and STATUS_BUFFERED) == 0
            if ((status and STATUS_INVALID) != 0 && !errorReported) {
                errorReported = true
                _isPlaying.value = false
                _isBuffering.value = false
                _errorText.value = "播放失败：视频源无法访问或格式不受支持"
            }
            // 加载超时兜底：源站分片 502 等故障时 mdk 无限 Loading 且不置 Invalid，
            // RUNNING 但 15s 无任何进度即上报错误
            if (!errorReported && state == STATE_RUNNING && _timeMs.value == 0L &&
                (status and STATUS_INVALID) == 0
            ) {
                val now = System.currentTimeMillis()
                if (loadingSinceMs == 0L) {
                    loadingSinceMs = now
                } else if (now - loadingSinceMs >= LOADING_TIMEOUT_MS) {
                    errorReported = true
                    _isPlaying.value = false
                    _isBuffering.value = false
                    _errorText.value = "播放失败：视频源无法访问（加载超时，建议换线路/换源）"
                }
            } else {
                loadingSinceMs = 0L
            }
            if ((status and STATUS_LOADED) != 0 && (status and STATUS_LOADING) == 0) {
                table.callPointer({ it.mediaInfo })?.let { info ->
                    _durationMs.value = info.getLong(MEDIA_INFO_DURATION_OFFSET)
                    readVideoSize(info)
                }
            }
            if (state != STATE_STOPPED) {
                val pos = table.callLong({ it.position })
                if (pos > 0) {
                    _timeMs.value = pos
                    // 加载超时误报后源恢复：有实际进度即清错误横幅
                    if (errorReported) {
                        errorReported = false
                        _errorText.value = null
                    }
                }
            }
        }
    }

    /** 从 mediaInfo 首个视频流读取分辨率（MDK_VideoStreamCodecParameters 导出函数） */
    private fun readVideoSize(info: Pointer) {
        val videoStream = info.getPointer(MEDIA_INFO_VIDEO_PTR_OFFSET) ?: return
        if (info.getInt(MEDIA_INFO_VIDEO_COUNT_OFFSET) <= 0) return
        val l = lib ?: return
        val params = Memory(VIDEO_CODEC_PARAMS_SIZE.toLong())
        runCatching { l.MDK_VideoStreamCodecParameters(videoStream, params) }
        _videoWidth.value = params.getInt(VIDEO_CODEC_WIDTH_OFFSET).coerceAtLeast(0)
        _videoHeight.value = params.getInt(VIDEO_CODEC_HEIGHT_OFFSET).coerceAtLeast(0)
    }

    // endregion
}
