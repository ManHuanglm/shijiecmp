package com.shiping.desktop.player

import com.shiping.desktop.data.prefs.DesktopPrefs
import com.shiping.desktop.util.AppLog
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.io.File

/**
 * libmpv C API（JNA 动态绑定）。仅声明用到的函数；
 * mpv_command 的 char** 直接映射 String[]（JNA 自动补 NULL 结尾）。
 */
private interface LibMpv : Library {
    fun mpv_create(): Pointer?
    fun mpv_initialize(handle: Pointer): Int
    fun mpv_terminate_destroy(handle: Pointer)
    fun mpv_set_option_string(handle: Pointer, name: String, value: String): Int
    fun mpv_command(handle: Pointer, args: Array<String>): Int
    fun mpv_set_property_string(handle: Pointer, name: String, value: String): Int
    fun mpv_get_property_string(handle: Pointer, name: String): Pointer?
    fun mpv_free(data: Pointer)
    fun mpv_wait_event(handle: Pointer, timeout: Double): Pointer?
    fun mpv_request_log_messages(handle: Pointer, minLevel: String): Int
}

/**
 * mpv 播放内核控制器（JNA 绑定 libmpv-2.dll，与 VLC/MDK 内核可切换共存）。
 *
 * 嵌入方式：wid 方案——视频面 Canvas 的原生窗口句柄在 initialize 前以
 * mpv_set_option_string("wid", HWND) 注入，视频渲染进该窗口。
 *
 * 状态同步：后台事件线程以 mpv_wait_event(250ms) 驱动——
 * 排水事件（PLAYBACK_RESTART 触发续播 seek / END_FILE 错误检测）+
 * 轮询 time-pos/duration/pause/idle-active/paused-for-cache 更新 StateFlow。
 *
 * 用户设置（硬解码/字幕样式/画面比例）在初始化时从 DesktopPrefs 读取注入，
 * 修改后对下次打开的播放窗口生效。
 */
class MpvPlayerController(prefs: DesktopPrefs) : AbstractKernelController(prefs) {

    companion object {
        // mpv_event.event_id（client.h mpv_event_id）
        private const val EVENT_NONE = 0
        private const val EVENT_SHUTDOWN = 1
        private const val EVENT_LOG_MESSAGE = 3
        private const val EVENT_END_FILE = 7
        private const val EVENT_PLAYBACK_RESTART = 21

        // mpv_event_end_file.reason（MPV_END_FILE_REASON_*）
        private const val END_FILE_REASON_ERROR = 3

        /** 视频面 HWND 变动（移除/重挂）后多久内的加载中断按重挂容错自动恢复 */
        private const val HWND_RECOVERY_WINDOW_MS = 5000L

        /** 定位捆绑/自带 mpv 目录（含 libmpv-2.dll），未找到返回 null */
        fun findMpvDir(): String? {
            val candidates = mutableListOf<String>()
            System.getProperty("compose.application.resources.dir")?.let { candidates += "$it/mpv" }
            // 安装包（msi/exe）不含 resources 目录，从应用 jar 内提取兜底
            BundledNative.ensureDir("mpv")?.let { candidates += it.absolutePath }
            val userDir = System.getProperty("user.dir")
            candidates += listOf(
                "$userDir/mpv",
                "$userDir/desktop-app/app-resources/mpv",
                "$userDir/app-resources/mpv",
            )
            return candidates.firstOrNull { dir -> File(dir, "libmpv-2.dll").exists() }
        }
    }

    override val logTag: String = "MpvPlayer"

    override val initThreadName: String = "mpv-init"

    override fun initErrorMessage(t: Throwable): String =
        "未检测到 mpv 播放内核（libmpv-2.dll）。可在设置页切换到 VLC 内核。\n${t.message ?: ""}"

    private var lib: LibMpv? = null

    @Volatile
    private var mpv: Pointer? = null

    override val isReady: Boolean get() = mpv != null

    /** 事件线程生命周期标记 */
    @Volatile
    private var running = false

    private var eventThread: Thread? = null

    /** 续播定位：起播（PLAYBACK_RESTART）后一次性 seek */
    @Volatile
    private var pendingSeekMs: Long = 0L

    /** 最近一次播放请求（重挂容错自动恢复时带当前进度重载用） */
    @Volatile
    private var lastRequest: PlayRequest? = null

    /** 最近一次视频面 HWND 变动时间（移除/重挂），0 表示无变动（不启用容错） */
    @Volatile
    private var hwndChangedAtMs: Long = 0L

    /** 本次 HWND 变动窗口内是否已自动恢复过（只恢复一次，防反复失败刷播放） */
    @Volatile
    private var hwndRecoveryUsed = false

    /** 实际播放（须内核就绪；play 与初始化完成回调可能并发，加锁防双重 loadfile） */
    override fun actualPlay(request: PlayRequest) {
        val l = lib ?: return
        val handle = mpv ?: return
        synchronized(actualPlayLock) {
            lastRequest = request
            _errorText.value = null
            _timeMs.value = 0L
            _durationMs.value = 0L
            _isPlaying.value = false
            // 起播即视为缓冲中（首帧到达后由轮询解除），覆盖加载阶段中央动画
            _isBuffering.value = true
            pendingSeekMs = request.startMs
            applyHeaderProperties(l, handle, request.headers)
            val rc = l.mpv_command(handle, arrayOf("loadfile", request.url, "replace"))
            AppLog.i(logTag, "play: ${request.url} rc=$rc")
            if (rc < 0) _errorText.value = "mpv 加载失败（错误码 $rc）"
        }
    }

    private val actualPlayLock = Any()

    /** mpv 命令异步返回，无需执行器排队，直接调用 */
    override fun dispatchPlay(request: PlayRequest) {
        actualPlay(request)
    }

    override fun stop() {
        pendingSeekMs = 0L
        // 主动停止不触发重挂容错（避免停止后又被自动恢复）
        hwndChangedAtMs = 0L
        _isPlaying.value = false
        _isBuffering.value = false
        _timeMs.value = 0L
        _durationMs.value = 0L
        val handle = mpv ?: return
        lib?.mpv_command(handle, arrayOf("stop"))
    }

    override fun togglePlayPause() {
        val handle = mpv ?: return
        val pause = if (_isPlaying.value) "yes" else "no"
        lib?.mpv_set_property_string(handle, "pause", pause)
    }

    override fun seekTo(ms: Long) {
        val handle = mpv ?: return
        val duration = _durationMs.value
        val target = if (duration > 0) ms.coerceIn(0L, duration - 500L) else ms.coerceAtLeast(0L)
        lib?.mpv_command(handle, arrayOf("seek", (target / 1000.0).toString(), "absolute"))
        _timeMs.value = target
    }

    override fun setRate(rate: Float) {
        val handle = mpv ?: return
        lib?.mpv_set_property_string(handle, "speed", rate.toString())
    }

    override fun setVolume(volume: Int) {
        val handle = mpv ?: return
        lib?.mpv_set_property_string(handle, "volume", volume.coerceIn(0, 100).toString())
    }

    /** 画面比例：fit=原比例（mpv 默认）/ crop=裁剪铺满 / stretch=拉伸 */
    override fun applyVideoScaleNow(scale: String) {
        val handle = mpv ?: return
        val l = lib ?: return
        l.mpv_set_property_string(handle, "panscan", if (scale == "crop") "1.0" else "0.0")
        l.mpv_set_property_string(handle, "keepaspect", if (scale == "stretch") "no" else "yes")
    }

    /** 截图：mpv screenshot-to-file 存到 用户目录/Pictures/视界Desktop（保留字幕画面） */
    override fun snapshot(): String? {
        val handle = mpv ?: return null
        val dir = File(System.getProperty("user.home"), "Pictures/视界Desktop")
        if (!runCatching { dir.mkdirs() }.getOrDefault(false) && !dir.isDirectory) return null
        val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss").format(java.util.Date())
        val file = File(dir, "screenshot_$stamp.png")
        val rc = lib?.mpv_command(handle, arrayOf("screenshot-to-file", file.absolutePath, "subtitles"))
        AppLog.i(logTag, "snapshot: rc=$rc ${file.absolutePath}")
        return file.absolutePath.takeIf { rc != null && rc >= 0 }
    }

    /** 释放 native 资源：先停事件线程，再 terminate_destroy（避免并发访问已释放句柄） */
    override fun release() {
        running = false
        runCatching { eventThread?.join(1000) }
        val handle = mpv ?: return
        mpv = null
        runCatching { lib?.mpv_terminate_destroy(handle) }
        lib = null
        AppLog.i(logTag, "mpv 内核已释放")
    }

    /** 重挂：mpv 仍向旧句柄渲染会白屏，运行时重设 wid 让 vo 迁移到新窗口 */
    override fun onSurfaceReattached() {
        // 记录重挂时间窗：重挂间隙 mpv 可能因渲染目标失效中断加载，靠窗口期自动恢复
        hwndChangedAtMs = System.currentTimeMillis()
        hwndRecoveryUsed = false
        val handle = mpv
        if (handle != null) lib?.mpv_set_property_string(handle, "wid", hwndValue.toString())
        AppLog.i(logTag, "wid 重挂: $hwndValue")
    }

    /** 视频面离场同样可能伴随加载中断（HWND 消亡窗口期），纳入恢复窗口 */
    override fun onSurfaceDetached() {
        hwndChangedAtMs = System.currentTimeMillis()
        hwndRecoveryUsed = false
    }

    // region 初始化

    override fun doInitialize() {
        val dir = findMpvDir() ?: throw IllegalStateException("未找到 libmpv-2.dll（捆绑目录/应用目录 mpv 下）")
        // 追加而非覆盖 jna.library.path：保留其他内核路径，同会话切换内核后仍可回退
        val existing = System.getProperty("jna.library.path")
        System.setProperty(
            "jna.library.path",
            if (existing.isNullOrBlank()) dir else "$existing${File.pathSeparator}$dir",
        )
        val l = Native.load("libmpv-2", LibMpv::class.java)
        val handle = l.mpv_create() ?: throw IllegalStateException("mpv_create 返回空")
        // wid 嵌入必须在 initialize 前设置（窗口句柄由 addNotify 捕获）
        if (hwndValue == 0L) throw IllegalStateException("视频面窗口句柄未就绪")
        l.mpv_set_option_string(handle, "wid", hwndValue.toString())
        // 硬解码开关（设置页可关，关闭后软解兼容性更好但更耗 CPU）
        val hwdec = if (prefs.getBoolean(DesktopPrefs.KEY_MPV_HWDEC, true)) "auto-safe" else "no"
        l.mpv_set_option_string(handle, "hwdec", hwdec)
        l.mpv_set_option_string(handle, "keep-open", "no")
        check(l.mpv_initialize(handle) == 0) { "mpv_initialize 失败" }
        // 订阅 mpv 内部日志（info 级）：排障用（loadfile 未执行/TLS/vo 初始化失败等核心层原因）
        val logRc = runCatching { l.mpv_request_log_messages(handle, "info") }.getOrDefault(-99)
        AppLog.i(logTag, "mpv 日志订阅 rc=$logRc")
        applyUserSettings(l, handle)
        lib = l
        mpv = handle
        running = true
        eventThread = Thread({ eventLoop(l, handle) }, "mpv-event").apply {
            isDaemon = true
            start()
        }
        AppLog.i(logTag, "mpv 内核就绪: $dir hwdec=$hwdec")
    }

    /**
     * 注入设置页的播放偏好（初始化后设置属性）：
     * 字幕颜色/背景/透明度/大小、特效字幕、画面比例。
     */
    private fun applyUserSettings(l: LibMpv, handle: Pointer) {
        // 特效字幕：开=完全遵循 ASS 脚本样式；关=用用户样式覆盖（特效失效）
        val fxSubtitle = prefs.getBoolean(DesktopPrefs.KEY_MPV_FX_SUBTITLE, true)
        l.mpv_set_property_string(handle, "sub-ass-override", if (fxSubtitle) "no" else "force")
        // 字幕颜色（默认白不注入，避免覆盖脚本样式）
        val subColor = prefs.getString(DesktopPrefs.KEY_SUB_COLOR, "#FFFFFF")
        if (subColor.isNotBlank() && !subColor.equals("#FFFFFF", true)) {
            l.mpv_set_property_string(handle, "sub-color", subColor)
        }
        // 字幕背景颜色 + 透明度（0-100 → AARRGGBB 的 AA；默认 40% 不注入）
        val backColor = prefs.getString(DesktopPrefs.KEY_SUB_BACK_COLOR, "#000000")
        val backAlpha = prefs.getInt(DesktopPrefs.KEY_SUB_BACK_ALPHA, 40)
        if (!backColor.equals("#000000", true) || backAlpha != 40) {
            val alpha = (backAlpha.coerceIn(0, 100) * 255 / 100)
            l.mpv_set_property_string(handle, "sub-back-color", "#%02X%s".format(alpha, backColor.removePrefix("#")))
            l.mpv_set_property_string(handle, "sub-border-style", "4")
        }
        // 字幕大小（百分比 → sub-scale 倍率）
        val subSize = prefs.getInt(DesktopPrefs.KEY_SUB_SIZE, 100).coerceIn(50, 200)
        if (subSize != 100) {
            l.mpv_set_property_string(handle, "sub-scale", (subSize / 100.0).toString())
        }
        // 画面比例：fit=原比例（mpv 默认，无需设置）/ crop=裁剪铺满 / stretch=拉伸
        when (prefs.getString(DesktopPrefs.KEY_VIDEO_SCALE, "fit")) {
            "crop" -> l.mpv_set_property_string(handle, "panscan", "1.0")
            "stretch" -> l.mpv_set_property_string(handle, "keepaspect", "no")
        }
    }

    /**
     * 注入请求头：UA/Referer 用 mpv 专属属性；其余 header 合并为
     * http-header-fields 列表（空串即清空，避免上一会话残留）。
     */
    private fun applyHeaderProperties(l: LibMpv, handle: Pointer, headers: Map<String, String>) {
        val userAgent = headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value.orEmpty()
        val referer = headers.entries.firstOrNull { it.key.equals("Referer", true) }?.value.orEmpty()
        val extra = headers.entries
            .filter { !it.key.equals("User-Agent", true) && !it.key.equals("Referer", true) }
            .joinToString(",") { "${it.key}: ${it.value}" }
        l.mpv_set_property_string(handle, "http-user-agent", userAgent)
        l.mpv_set_property_string(handle, "http-referrer", referer)
        l.mpv_set_property_string(handle, "http-header-fields", extra)
    }

    // endregion

    // region 事件线程

    /**
     * 事件循环：wait_event(250ms) 排水事件后轮询属性更新 StateFlow。
     * PLAYBACK_RESTART 时执行续播 seek（过早 seek 会被 mpv 拒绝）。
     */
    private fun eventLoop(l: LibMpv, handle: Pointer) {
        AppLog.i(logTag, "mpv 事件循环启动")
        while (running) {
            var event = runCatching { l.mpv_wait_event(handle, 0.25) }.getOrNull()
            if (event == null) {
                AppLog.w(logTag, "mpv_wait_event 返回空，事件循环退出")
                break
            }
            var guard = 0
            while (event != null && event.getInt(0) != EVENT_NONE && guard++ < 64) {
                handleEvent(l, handle, event)
                event = runCatching { l.mpv_wait_event(handle, 0.0) }.getOrNull() ?: break
            }
            if (!running) break
            pollPlaybackState(l, handle)
        }
    }

    /** mpv_event 结构：event_id(int)@0 / error(int)@4 / reply_userdata@8 / data(ptr)@16 */
    private fun handleEvent(l: LibMpv, handle: Pointer, event: Pointer) {
        when (event.getInt(0)) {
            EVENT_LOG_MESSAGE -> {
                // mpv_event_log_message：prefix(char*)@0 / level(char*)@8 / text(char*)@16
                val data = event.getPointer(16)
                if (data != null) {
                    val prefix = runCatching { data.getString(0, "UTF-8") }.getOrDefault("")
                    val level = runCatching { data.getString(8, "UTF-8") }.getOrDefault("")
                    val text = runCatching { data.getString(16, "UTF-8") }.getOrDefault("")
                    AppLog.i(logTag, "mpv[$prefix/$level] ${text.trim()}")
                }
            }
            EVENT_PLAYBACK_RESTART -> {
                _errorText.value = null
                val target = pendingSeekMs
                if (target > 0) {
                    pendingSeekMs = 0L
                    l.mpv_command(handle, arrayOf("seek", (target / 1000.0).toString(), "absolute"))
                }
            }
            EVENT_END_FILE -> {
                val reason = event.getPointer(16)?.getInt(0) ?: 0
                if (reason == END_FILE_REASON_ERROR) {
                    // mpv_event_end_file：reason(int)@0 / mpv_error(int)@4
                    val mpvErr = runCatching { event.getPointer(16)?.getInt(4) }.getOrDefault(-99)
                    AppLog.e(logTag, "load 失败: reason=$reason mpvError=$mpvErr")
                    // 视频面 HWND 变动窗口期内（Compose 重建视频面/全屏切换）mpv 可能因
                    // 渲染目标失效中断加载，带当前进度自动重载恢复，避免误报「播放失败」
                    val request = lastRequest
                    val sinceChange = System.currentTimeMillis() - hwndChangedAtMs
                    if (!hwndRecoveryUsed && request != null && mpv != null &&
                        sinceChange in 0..HWND_RECOVERY_WINDOW_MS
                    ) {
                        hwndRecoveryUsed = true
                        val resumeMs = _timeMs.value.takeIf { it > 0L } ?: request.startMs
                        AppLog.i(logTag, "视频面重挂伴随加载中断，自动恢复播放（续播 $resumeMs）")
                        actualPlay(request.copy(startMs = resumeMs))
                    } else {
                        _isPlaying.value = false
                        _isBuffering.value = false
                        _errorText.value = "播放失败：视频源无法访问或格式不受支持"
                    }
                }
            }
            EVENT_SHUTDOWN -> running = false
        }
    }

    /** 轮询属性（字符串读取即用即释放；StateFlow 值不变时不触发更新） */
    private fun pollPlaybackState(l: LibMpv, handle: Pointer) {
        val idle = l.getStringProp(handle, "idle-active") == "yes"
        val pause = l.getStringProp(handle, "pause") == "yes"
        _isPlaying.value = !idle && !pause
        val pausedForCache = l.getStringProp(handle, "paused-for-cache") == "yes"
        // 缓冲 = 网络停顿（paused-for-cache）或首帧未出（起播加载阶段）
        _isBuffering.value = !idle && (pausedForCache || _timeMs.value <= 0L)
        l.getStringProp(handle, "time-pos")?.toDoubleOrNull()?.let { sec ->
            _timeMs.value = (sec * 1000).toLong()
        }
        l.getStringProp(handle, "duration")?.toDoubleOrNull()?.let { sec ->
            _durationMs.value = (sec * 1000).toLong()
        }
        _videoWidth.value = l.getStringProp(handle, "width")?.toIntOrNull() ?: 0
        _videoHeight.value = l.getStringProp(handle, "height")?.toIntOrNull() ?: 0
    }

    /** 读取字符串属性（返回 null 表示属性不可用/空闲），调用方无需关心 mpv_free */
    private fun LibMpv.getStringProp(handle: Pointer, name: String): String? {
        val ptr = runCatching { mpv_get_property_string(handle, name) }.getOrNull() ?: return null
        val value = runCatching { ptr.getString(0, "UTF-8") }.getOrNull()
        runCatching { mpv_free(ptr) }
        return value
    }

    // endregion
}
