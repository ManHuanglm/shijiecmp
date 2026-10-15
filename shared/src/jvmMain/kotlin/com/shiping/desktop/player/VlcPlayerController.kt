package com.shiping.desktop.player

import com.shiping.desktop.util.AppLog
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer
import uk.co.caprica.vlcj.player.embedded.videosurface.ComponentIdVideoSurface
import uk.co.caprica.vlcj.player.embedded.videosurface.VideoSurfaceAdapters

/**
 * VLC 播放内核控制器（vlcj 4.x 绑定 libvlc，与 mpv 内核可切换共存）。
 *
 * 嵌入方式：wid 方案——视频面 Canvas 原生窗口句柄经 VideoSurfaceAdapter 交给 libvlc 渲染。
 *
 * 状态同步：vlcj 事件（playing/paused/finished/error/timeChanged/lengthChanged/buffering）
 * 直接驱动 StateFlow（事件在 libvlc 线程回调，仅写 StateFlow）。
 *
 * 用户设置（画面比例）在起播与切换时应用；硬解码开关为 mpv 页专属，VLC 默认自动硬解。
 */
class VlcPlayerController(prefs: com.shiping.shared.prefs.KeyValueStore) : AbstractKernelController(prefs) {

    companion object {
        /** 缓冲事件阈值：cache < 100% 视为缓冲中 */
        private const val BUFFERING_THRESHOLD = 100f

        /** 应用目录捆绑 VLC 的候选目录名（含 libvlc.dll） */
        private const val BUNDLED_VLC_DIR = "vlc"

        /** 直播暂停恢复的出画面等待（毫秒，超时重载回直播边缘） */
        private const val LIVE_RESUME_STALL_MS = 8000L
    }

    override val logTag: String = "VlcPlayer"

    override val initThreadName: String = "vlc-init"

    override fun initErrorMessage(t: Throwable): String =
        "未检测到 VLC 播放内核（libvlc.dll）。请安装 VLC 或在设置页切换回 mpv 内核。\n${t.message ?: ""}"

    private var factory: MediaPlayerFactory? = null

    @Volatile
    private var player: EmbeddedMediaPlayer? = null

    override val isReady: Boolean get() = player != null

    /** 释放幂等标记 */
    private val released = AtomicBoolean(false)

    /** 视频面重挂：句柄变化后重新把新 HWND 交给 libvlc */
    override fun onSurfaceReattached() {
        val player = player
        if (player != null) {
            runCatching {
                player.videoSurface()
                    .set(ComponentIdVideoSurface(hwndValue, VideoSurfaceAdapters.getVideoSurfaceAdapter()))
            }
            AppLog.i(logTag, "wid 重挂: $hwndValue")
        }
    }

    override fun onCanvasResized() {
        syncSurfaceSize()
    }

    override fun onCanvasMoved() {
        syncSurfaceSize()
    }

    override fun onSurfaceRestored() {
        syncSurfaceSize()
    }

    /** 实际播放：UA/Referer 经 VLC 选项注入，其余 header 已由调用方包装为 /hdr 代理地址 */
    override fun actualPlay(request: PlayRequest) {
        val player = player ?: return
        lastRequest = request
        _errorText.value = null
        _timeMs.value = 0L
        _durationMs.value = 0L
        _isPlaying.value = false
        _isBuffering.value = false
        val options = buildList {
            request.headers["User-Agent"]?.let { add(":http-user-agent=$it") }
            request.headers["Referer"]?.let { add(":http-referrer=$it") }
            if (request.startMs > 0) add(":start-time=${request.startMs / 1000}")
        }
        runCatching {
            player.controls().stop()
            player.media().play(request.url, *options.toTypedArray())
            applyVideoScale(player, prefs.getString(com.shiping.shared.prefs.PrefsKeys.KEY_VIDEO_SCALE, "fit"))
        }.onFailure {
            AppLog.e(logTag, "起播失败: ${it.message}")
            _errorText.value = "播放失败：${it.message ?: "未知错误"}"
        }
        AppLog.i(logTag, "play: ${request.url} startMs=${request.startMs}")
    }

    override fun stop() {
        val player = player ?: return
        runCatching { player.controls().stop() }
        _isPlaying.value = false
        _isBuffering.value = false
        _timeMs.value = 0L
        _durationMs.value = 0L
        _errorText.value = null
    }

    override fun togglePlayPause() {
        val player = player ?: return
        val wasPlaying = runCatching { player.status().isPlaying }.getOrDefault(false)
        runCatching { player.controls().pause() }
        // 直播流暂停恢复：live 窗口暂停期间滚过，恢复后可能追不上（持续转圈）；
        // 恢复后短时间仍未出画面则整体重载当前地址，重新对齐直播边缘
        if (!wasPlaying && _durationMs.value <= 0L) monitorLiveResume()
    }

    /** 最近一次播放请求（直播恢复监控重载用） */
    @Volatile
    private var lastRequest: PlayRequest? = null

    /** 直播恢复监控序号（新恢复动作使旧监控线程放弃干预） */
    private val resumeMonitorSeq = java.util.concurrent.atomic.AtomicLong(0)

    /** 恢复播放后 [LIVE_RESUME_STALL_MS] 仍未出画面则重载当前流（回直播边缘）；已播放/已暂停/已换流则不干预 */
    private fun monitorLiveResume() {
        val request = lastRequest ?: return
        val seq = resumeMonitorSeq.incrementAndGet()
        Thread {
            try {
                Thread.sleep(LIVE_RESUME_STALL_MS)
            } catch (_: InterruptedException) {
                return@Thread
            }
            val player = player ?: return@Thread
            if (seq != resumeMonitorSeq.get()) return@Thread
            val playing = runCatching { player.status().isPlaying }.getOrDefault(true)
            if (playing || lastRequest !== request) return@Thread
            AppLog.i(logTag, "直播恢复超时未出画面，重载对齐直播边缘")
            _isBuffering.value = true
            actualPlay(request)
        }.apply {
            name = "live-resume-watch"
            isDaemon = true
            start()
        }
    }

    override fun seekTo(ms: Long) {
        val player = player ?: return
        runCatching { player.controls().setTime(ms) }
        _timeMs.value = ms
    }

    override fun setRate(rate: Float) {
        val player = player ?: return
        runCatching { player.controls().setRate(rate) }
    }

    override fun setVolume(volume: Int) {
        val player = player ?: return
        runCatching { player.audio().setVolume(volume) }
    }

    override fun applyVideoScaleNow(scale: String) {
        val player = player ?: return
        runCatching { applyVideoScale(player, scale) }
    }

    override fun snapshot(): String? {
        val player = player ?: return null
        val file = File.createTempFile("shiping-snap-", ".png")
        val ok = runCatching { player.snapshots().save(file) }.getOrDefault(false)
        return if (ok && file.exists()) file.absolutePath else null
    }

    /** 释放 native 资源（窗口关闭时调用，不可重复） */
    override fun release() {
        if (!released.compareAndSet(false, true)) return
        shutdownPlayExecutor()
        val p = player
        val f = factory
        this.player = null
        this.factory = null
        runCatching { p?.release() }
        runCatching { f?.release() }
        AppLog.i(logTag, "vlc 内核已释放")
    }

    // region 初始化

    override fun doInitialize() {
        // 优先用捆绑的 vlc/ 运行时（libvlc.dll + plugins/），否则交给 vlcj 自动发现（本机安装）
        findBundledVlcDir()?.let { dir ->
            val existing = System.getProperty("jna.library.path")
            System.setProperty(
                "jna.library.path",
                if (existing.isNullOrBlank()) dir else "$existing${File.pathSeparator}$dir",
            )
        }
        val f = MediaPlayerFactory()
        val p = f.mediaPlayers().newEmbeddedMediaPlayer() as EmbeddedMediaPlayer
        // 视频面附着：把 Canvas 原生窗口句柄交给 libvlc（windows 经 user32 取 HWND）
        p.videoSurface().set(ComponentIdVideoSurface(hwndValue, VideoSurfaceAdapters.getVideoSurfaceAdapter()))
        p.events().addMediaPlayerEventListener(eventAdapter)
        factory = f
        player = p
        AppLog.i(logTag, "vlc 内核就绪")
        // 视频面 HWND 层级快照（诊断遮挡类问题用）
        AppLog.i(logTag, "视频面层级: ${Win32Surface.describeWindow(hwndValue) ?: "hwnd 未捕获"}")
    }

    /** 定位捆绑/自带 VLC 运行时目录（含 libvlc.dll），未找到返回 null（定位链同 mpv 内核） */
    private fun findBundledVlcDir(): String? {
        val candidates = mutableListOf<String>()
        // 应用镜像（开发/compose run）经 resources 目录快路径
        System.getProperty("compose.application.resources.dir")?.let { candidates += "$it/$BUNDLED_VLC_DIR" }
        // 安装包（msi/exe）不含 resources 目录，从应用 jar 内提取兜底
        BundledNative.ensureDir(BUNDLED_VLC_DIR)?.let { candidates += it.absolutePath }
        val userDir = System.getProperty("user.dir")
        candidates += listOf(
            "$userDir/$BUNDLED_VLC_DIR",
            "$userDir/desktop-app/app-resources/$BUNDLED_VLC_DIR",
            "$userDir/app-resources/$BUNDLED_VLC_DIR",
        )
        return candidates.firstOrNull { dir -> File(dir, "libvlc.dll").isFile }
    }

    /** vlcj 事件适配器：事件回调在 libvlc 线程，仅写 StateFlow（线程安全） */
    private val eventAdapter = object : MediaPlayerEventAdapter() {
        override fun playing(mediaPlayer: MediaPlayer) {
            _isPlaying.value = true
            _errorText.value = null
        }

        override fun paused(mediaPlayer: MediaPlayer) {
            _isPlaying.value = false
        }

        override fun stopped(mediaPlayer: MediaPlayer) {
            _isPlaying.value = false
        }

        override fun finished(mediaPlayer: MediaPlayer) {
            _isPlaying.value = false
        }

        override fun error(mediaPlayer: MediaPlayer) {
            _isPlaying.value = false
            _isBuffering.value = false
            _errorText.value = "播放失败：视频源无法访问或格式不受支持"
        }

        override fun timeChanged(mediaPlayer: MediaPlayer, newTime: Long) {
            _timeMs.value = newTime
        }

        override fun lengthChanged(mediaPlayer: MediaPlayer, newLength: Long) {
            _durationMs.value = newLength
        }

        override fun buffering(mediaPlayer: MediaPlayer, newCache: Float) {
            _isBuffering.value = newCache in 0f..BUFFERING_THRESHOLD && newCache < BUFFERING_THRESHOLD
        }
    }

    // endregion

    /**
     * 应用画面比例：fit=等比适应（清除比率强制）；stretch/crop=按画布宽高比拉伸铺满
     * （libvlc 无独立裁剪语义，crop 与 stretch 同实现；比率为整数最简比格式 "w:h"）。
     */
    private fun applyVideoScale(player: MediaPlayer, scale: String) {
        when (scale) {
            "16:9", "9:16", "4:3" -> player.video().setAspectRatio(scale)
            "stretch", "crop" -> {
                val w = canvas.width.coerceAtLeast(1)
                val h = canvas.height.coerceAtLeast(1)
                val g = gcd(w, h)
                player.video().setAspectRatio("${w / g}:${h / g}")
            }
            else -> player.video().setAspectRatio(null)
        }
    }

    /** 最大公约数（画面比例整数化用） */
    private fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
}
