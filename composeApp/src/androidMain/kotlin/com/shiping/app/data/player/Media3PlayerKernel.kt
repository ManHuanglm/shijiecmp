package com.shiping.app.data.player

import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.C
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.shiping.shared.player.PlayerKernel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * media3 ExoPlayer 播放内核（实现 commonMain PlayerKernel）：
 * - 状态流由 Listener + Handler 轮询驱动（对齐桌面轮询模式）
 * - UA/Referer 经 DefaultHttpDataSource 注入；headers 变化时重建播放器
 * - renderSurface 恒为 null：渲染面由 UI 层 androidMain 的 PlayerView
 *   直接绑定 [player]（AndroidView 嵌入），不经跨端 Any 传递
 */
class Media3PlayerKernel(
    private val onRebuild: (ExoPlayer) -> Unit = {},
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
        override fun onPlayerError(error: PlaybackException) {
            _errorText.value = "播放失败：${error.errorCodeName}（${error.message ?: "未知错误"}）"
        }
    }

    private fun buildPlayer(headers: Map<String, String>): ExoPlayer {
        val httpFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)
        headers.entries.firstOrNull { it.key.equals("User-Agent", true) }
            ?.let { httpFactory.setUserAgent(it.value) }
        val player = ExoPlayer.Builder(applicationContext)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(DefaultDataSource.Factory(applicationContext, httpFactory)),
            )
            .build()
        player.addListener(listener)
        return player
    }

    /** 宿主上下文（ShipingApp 注入）：ExoPlayer 构造必需 */
    lateinit var applicationContext: android.content.Context

    override fun play(url: String, headers: Map<String, String>, startMs: Long) {
        mainHandler.post {
            try {
                _errorText.value = null
                // headers 变化需重建数据源工厂（ExoPlayer 工厂绑定于实例）
                val p = if (player == null || headers != lastHeaders) {
                    player?.release()
                    val rebuilt = buildPlayer(headers)
                    player = rebuilt
                    lastHeaders = headers
                    onRebuild(rebuilt)
                    rebuilt
                } else {
                    player!!
                }
                _timeMs.value = 0
                _durationMs.value = 0
                _isBuffering.value = true
                p.setMediaItem(MediaItem.fromUri(url))
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

    override fun stop() {
        mainHandler.post {
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
            player?.release()
            player = null
        }
    }

    override fun setSurfaceVisible(visible: Boolean) = Unit
}
