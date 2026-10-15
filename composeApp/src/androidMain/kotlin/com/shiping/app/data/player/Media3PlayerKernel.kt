package com.shiping.app.data.player

import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.C
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import java.net.URLDecoder
import com.shiping.app.util.AppLog
import com.shiping.shared.player.PlayerKernel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

/**
 * media3 ExoPlayer 播放内核（实现 commonMain PlayerKernel）：
 * - 状态流由 Listener + Handler 轮询驱动（对齐桌面轮询模式）
 * - UA/Referer 经 DefaultHttpDataSource 注入；headers 变化时重建播放器
 * - renderSurface 恒为 null：渲染面由 UI 层 androidMain 的 PlayerView
 *   直接绑定 [player]（AndroidView 嵌入），不经跨端 Any 传递
 */
class Media3PlayerKernel(
    private val onRebuild: (ExoPlayer) -> Unit = {},
    /** m3u8 播放级广告过滤开关（ShipingGraph 注入，构建播放器时读取一次） */
    private val isSliceAdFilterEnabled: () -> Boolean = { false },
) : PlayerKernel {

    private val _isPlaying = MutableStateFlow(false)
    override val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _timeMs = MutableStateFlow(0L)
    override val timeMs: StateFlow<Long> = _timeMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    override val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _videoWidth = MutableStateFlow(0)
    override val videoWidth: StateFlow<Int> = _videoWidth.asStateFlow()

    private val _videoHeight = MutableStateFlow(0)
    override val videoHeight: StateFlow<Int> = _videoHeight.asStateFlow()

    private val _isBuffering = MutableStateFlow(false)
    override val isBuffering: StateFlow<Boolean> = _isBuffering.asStateFlow()

    private val _errorText = MutableStateFlow<String?>(null)
    override val errorText: StateFlow<String?> = _errorText.asStateFlow()

    private val _initError = MutableStateFlow<String?>(null)
    override val initError: StateFlow<String?> = _initError.asStateFlow()

    override val renderSurface: Any? = null

    override val isReady: Boolean
        get() = player != null

    /** 当前播放器实例（UI 层 PlayerView 绑定用；headers 变化重建后更新） */
    @Volatile
    var player: ExoPlayer? = null
        private set

    private var lastHeaders: Map<String, String> = emptyMap()

    /** 上次构建播放器时的广告过滤开关（开关切换后下一次起播重建数据源链生效） */
    private var lastAdFilterEnabled: Boolean = isSliceAdFilterEnabled()

    /** 当前播放参数（IO 错误自动重试用；stop/release 后失效） */
    private var currentUrl: String = ""
    private var currentHeaders: Map<String, String> = emptyMap()
    private var currentStartMs: Long = 0L

    /** 播放会话令牌：换源/stop/release 递增，使过期重试任务失效 */
    private var sessionToken = 0

    /** 连续自动恢复计数（起播成功后清零，防止坏源无限重试） */
    private var autoRetryCount = 0

    private val mainHandler = Handler(Looper.getMainLooper())

    private val pollTick = object : Runnable {
        override fun run() {
            val p = player ?: return
            _timeMs.value = p.currentPosition.coerceAtLeast(0)
            _durationMs.value = if (p.duration == C.TIME_UNSET) 0 else p.duration.coerceAtLeast(0)
            _isBuffering.value = p.playbackState == Player.STATE_BUFFERING
            _isPlaying.value = p.isPlaying
            mainHandler.postDelayed(this, 500)
        }
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            // 起播成功清零恢复计数：单次会话内的多次可恢复错误各享独立重试额度
            if (playbackState == Player.STATE_READY) autoRetryCount = 0
        }

        override fun onPlayerError(error: PlaybackException) {
            if (tryAutoRecover(error)) return
            _errorText.value = "播放失败：${error.errorCodeName}（${error.message ?: "未知错误"}）"
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            // 上报显示宽 = 编码宽 × 像素宽高比（SAR≠1 的流按真实显示比例适配，避免画面拉伸变形）
            _videoWidth.value = if (videoSize.width > 0) {
                (videoSize.width * videoSize.pixelWidthHeightRatio).roundToInt()
            } else {
                0
            }
            _videoHeight.value = videoSize.height
        }
    }

    /**
     * 可恢复错误自动重试：赛事直播多为 2 秒分片超短窗口（约 12 秒），首次拉流错过窗口
     * 或短暂断流即分片 404，整体重放会重新拉最新清单重新对齐直播窗口；点播网络抖动
     * 恢复后从断点续播。仅处理落后直播窗口与 IO 类错误，解码/渲染类错误重试无意义。
     */
    private fun tryAutoRecover(error: PlaybackException): Boolean {
        if (currentUrl.isBlank()) return false
        val code = error.errorCode
        val liveWindowBehind = code == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW
        val ioError = code >= PlaybackException.ERROR_CODE_IO_UNSPECIFIED &&
            code <= PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE
        if (!liveWindowBehind && !ioError) return false
        if (autoRetryCount >= AUTO_RETRY_MAX) return false
        autoRetryCount++
        val token = sessionToken
        val attempt = autoRetryCount
        // 已知时长（点播）从断点续播；直播/未起播回直播边缘（startMs=0 或原始起播点）
        val resumeMs = when {
            _durationMs.value > 0 -> _timeMs.value
            currentStartMs > 0 -> currentStartMs
            else -> 0L
        }
        mainHandler.postDelayed({
            if (token != sessionToken || currentUrl.isBlank()) return@postDelayed
            AppLog.w(TAG, "播放错误自动重试($attempt/$AUTO_RETRY_MAX): ${error.errorCodeName}")
            play(currentUrl, currentHeaders, resumeMs)
        }, AUTO_RETRY_DELAY_MS)
        return true
    }

    private fun buildPlayer(headers: Map<String, String>): ExoPlayer {
        val httpFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)
        headers.entries.firstOrNull { it.key.equals("User-Agent", true) }
            ?.let { httpFactory.setUserAgent(it.value) }
        // 其余 header（Referer 防盗链等）经默认请求属性注入（对齐桌面 media options 语义）
        val extraHeaders = headers.filterKeys { !it.equals("User-Agent", true) }
        if (extraHeaders.isNotEmpty()) httpFactory.setDefaultRequestProperties(extraHeaders)
        // m3u8 播放级广告过滤：开关开启时包一层过滤数据源（对齐源项目接线）
        val baseFactory: DataSource.Factory =
            DefaultDataSource.Factory(applicationContext, httpFactory)
        val dataSourceFactory = if (isSliceAdFilterEnabled()) {
            AdFilterDataSourceFactory(baseFactory)
        } else {
            baseFactory
        }
        val player = ExoPlayer.Builder(applicationContext)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build()
        player.addListener(listener)
        return player
    }

    /** 宿主上下文（ShipingApp 注入）：ExoPlayer 构造必需 */
    lateinit var applicationContext: android.content.Context

    override fun play(url: String, headers: Map<String, String>, startMs: Long) {
        mainHandler.post {
            try {
                // 记录本次播放会话（自动重试用）并使旧重试任务失效
                sessionToken++
                currentUrl = url
                currentHeaders = headers
                currentStartMs = startMs
                _errorText.value = null
                // headers 或广告过滤开关变化需重建数据源工厂（ExoPlayer 工厂绑定于实例）
                val adFilterEnabled = isSliceAdFilterEnabled()
                val p = if (player == null || headers != lastHeaders || adFilterEnabled != lastAdFilterEnabled) {
                    player?.release()
                    val rebuilt = buildPlayer(headers)
                    player = rebuilt
                    lastHeaders = headers
                    lastAdFilterEnabled = adFilterEnabled
                    onRebuild(rebuilt)
                    rebuilt
                } else {
                    player!!
                }
                _timeMs.value = 0
                _durationMs.value = 0
                _isBuffering.value = true
                // 清零上一条流的分辨率：新流 onVideoSizeChanged 到来前容器回落 16:9
                _videoWidth.value = 0
                _videoHeight.value = 0
                // URL 全文含 .m3u8/.mpd（含 query/代理携带，如 /hdr?url=...type=mpd）时显式声明流媒体
                // mimeType：/hdr 包装地址无后缀，按扩展名推断会被误判为 progressive 而拒播
                val mediaItem = when {
                    url.substringBefore("#").lowercase().contains(".m3u8") ->
                        MediaItem.Builder().setUri(url).setMimeType(MimeTypes.APPLICATION_M3U8).build()
                    isMpdPlaybackUrl(url) ->
                        MediaItem.Builder().setUri(url).setMimeType(MimeTypes.APPLICATION_MPD).build()
                    else -> MediaItem.fromUri(url)
                }
                p.setMediaItem(mediaItem)
                p.prepare()
                if (startMs > 0) p.seekTo(startMs)
                p.playWhenReady = true
                mainHandler.removeCallbacks(pollTick)
                mainHandler.postDelayed(pollTick, 500)
            } catch (t: Throwable) {
                _errorText.value = "起播失败：${t.message ?: t.javaClass.simpleName}"
            }
        }
    }

    /** MPD（DASH 清单）地址判定：直链 .mpd 后缀，或 /hdr 代理 query 内 type=mpd（解码后匹配） */
    private fun isMpdPlaybackUrl(url: String): Boolean {
        val decoded = runCatching { URLDecoder.decode(url, "UTF-8") }.getOrDefault(url)
        val lower = decoded.lowercase()
        return lower.substringBefore('?').endsWith(".mpd") || lower.contains("type=mpd&") ||
            lower.endsWith("type=mpd") || lower.contains(".mpd?") || lower.contains(".mpd&")
    }

    override fun stop() {
        mainHandler.post {
            // 停止后取消自动重试（用户主动停止不自动恢复）
            sessionToken++
            currentUrl = ""
            player?.stop()
            _isPlaying.value = false
            _isBuffering.value = false
            _timeMs.value = 0
            _durationMs.value = 0
        }
    }

    override fun togglePlayPause() {
        val p = player ?: return
        mainHandler.post {
            if (p.isPlaying) p.pause() else p.play()
        }
    }

    override fun seekTo(ms: Long) {
        mainHandler.post { player?.seekTo(ms.coerceAtLeast(0)) }
        _timeMs.value = ms
    }

    override fun setRate(rate: Float) {
        mainHandler.post { player?.setPlaybackSpeed(rate) }
    }

    override fun setVolume(volume: Int) {
        mainHandler.post { player?.volume = (volume / 100f).coerceIn(0f, 1f) }
    }

    /** 画面比例：Android 端由 PlayerView.resizeMode 承担，内核层无操作 */
    override fun setVideoScale(scale: String) = Unit

    /** 截帧：ExoPlayer 无直接截帧 API，暂不支持（返回 null 由 UI 隐藏入口） */
    override fun snapshot(): String? = null

    override fun release() {
        mainHandler.removeCallbacks(pollTick)
        mainHandler.post {
            // 释放后取消自动重试（内核销毁不再恢复播放）
            sessionToken++
            currentUrl = ""
            player?.release()
            player = null
        }
    }

    override fun setSurfaceVisible(visible: Boolean) = Unit

    companion object {
        private const val TAG = "Media3Kernel"

        /** IO/直播窗口类错误自动恢复上限（每次起播成功后清零） */
        private const val AUTO_RETRY_MAX = 2

        /** 自动恢复延迟（毫秒）：直播窗口已滚过，立即重试仍会拉到过期分片 */
        private const val AUTO_RETRY_DELAY_MS = 1_500L
    }
}
