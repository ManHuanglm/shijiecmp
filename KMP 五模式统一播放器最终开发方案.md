# 🎯 KMP 五模式统一播放器最终开发方案

基于 **Kotlin Multiplatform + Compose Multiplatform**，实现一套共享代码覆盖 Android、iOS、Web（及鸿蒙Beta）三大平台，5 种播放模式无缝切换。

---

## 一、技术选型矩阵

| 层级 | Android | iOS | Web | 鸿蒙 |
|------|---------|-----|-----|------|
| 播放内核 | ExoPlayer (Media3) | AVPlayer | HTML5 Video + HLS.js | 原生媒体引擎 |
| UI 框架 | Compose Multiplatform | Compose Multiplatform | Compose Web / React Wrapper | CMP 鸿蒙版 |
| 状态管理 | StateFlow + Kotlin Coroutines | 同左 | 同左 | 同左 |
| 依赖注入 | Koin | Koin | Koin | Koin |
| 网络/解析 | Ktor + kotlinx.serialization | 同左 | 同左 | 同左 |

---

## 二、项目架构设计

```
KMP-Player/
├── shared/                          # 共享模块
│   ├── src/commonMain/              # 平台无关代码
│   │   ├── api/                     # 播放器接口定义
│   │   │   └── IPlayer.kt           # 核心播放契约
│   │   ├── model/                   # 数据模型
│   │   │   ├── PlayerMode.kt        # 5种模式枚举
│   │   │   ├── PlayerState.kt       # 播放状态
│   │   │   ├── Episode.kt           # 选集数据
│   │   │   └── Channel.kt           # 频道数据
│   │   ├── state/                   # 状态管理
│   │   │   └── PlayerViewModel.kt   # 统一状态机
│   │   ├── strategy/                # 模式策略
│   │   │   ├── ModeStrategy.kt      # 策略接口
│   │   │   └── ModeManager.kt       # 策略管理器
│   │   └── utils/                   # 工具类
│   ├── src/androidMain/             # Android 实现
│   │   └── player/
│   │       └── ExoPlayerImpl.kt     # ExoPlayer 适配器
│   ├── src/iosMain/                 # iOS 实现
│   │   └── player/
│   │       └── AVPlayerImpl.kt      # AVPlayer 适配器
│   ├── src/jsMain/                  # Web 实现
│   │   └── player/
│   │       └── Html5PlayerImpl.kt   # HTML5 适配器
│   └── src/harmonyMain/             # 鸿蒙实现 (Beta)
│       └── player/
│           └── HarmonyPlayerImpl.kt
│
├── androidApp/                      # Android 宿主
│   └── src/main/java/
│       └── ui/
│           ├── PlayerScreen.kt      # 播放器 Compose UI
│           ├── InlinePlayerView.kt  # 嵌入式
│           └── FullscreenActivity.kt # 全屏Activity
│
├── iosApp/                          # iOS 宿主
│   └── iosApp/
│       ├── PlayerView.swift         # 宿主 ViewController
│       └── PlayerScreen.swift       # Compose iOS 入口
│
└── webApp/                          # Web 宿主
    └── src/main/kotlin/
        └── main.kt                  # Compose Web / React 桥接
```

---

## 三、核心接口设计（共享层）

### 3.1 模式枚举与配置

```kotlin
// shared/src/commonMain/kotlin/model/PlayerMode.kt
package com.kmp.player.model

/**
 * 五种播放模式
 */
enum class PlayerMode {
    /** 嵌入式：页面内固定尺寸，含完整控制栏 + 进度条 */
    INLINE,
    
    /** 全屏：100% viewport，完整控制栏 + 进度条 + 扩展功能 */
    FULLSCREEN,
    
    /** 画中画：系统级浮动小窗，极简控制 */
    PIP,
    
    /** 直播：页面内固定尺寸，无进度条，含频道列表 + EPG */
    LIVE_INLINE,
    
    /** 直播全屏：100% viewport，无进度条，含频道列表 + EPG */
    LIVE_FULLSCREEN;

    /** 是否为直播模式 */
    val isLive: Boolean get() = this == LIVE_INLINE || this == LIVE_FULLSCREEN
    
    /** 是否为全屏模式 */
    val isFullscreen: Boolean get() = this == FULLSCREEN || this == LIVE_FULLSCREEN
    
    /** 是否显示进度条 */
    val showProgress: Boolean get() = this == INLINE || this == FULLSCREEN
    
    /** 是否显示频道列表 */
    val showChannelList: Boolean get() = isLive
}
```

### 3.2 统一状态模型

```kotlin
// shared/src/commonMain/kotlin/model/PlayerState.kt
package com.kmp.player.model

import kotlinx.coroutines.flow.StateFlow

/**
 * 播放器统一状态
 */
data class PlayerState(
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val currentTime: Long = 0L,        // 毫秒
    val duration: Long = 0L,           // 毫秒（直播为0）
    val volume: Float = 1.0f,
    val isMuted: Boolean = false,
    val playbackSpeed: Float = 1.0f,
    val currentQuality: String? = null,
    val availableQualities: List<String> = emptyList(),
    val error: PlayerError? = null,
    val mode: PlayerMode = PlayerMode.INLINE,
    // 直播专属
    val currentChannel: Channel? = null,
    val currentEpg: EPGItem? = null,
    // 点播专属
    val currentEpisode: Episode? = null,
    val isDanmakuEnabled: Boolean = true,
)

/**
 * 播放器错误
 */
sealed class PlayerError(val message: String) {
    data class NetworkError(val url: String) : PlayerError("网络错误: $url")
    data class DecodeError(val reason: String) : PlayerError("解码错误: $reason")
    data class SourceError(val reason: String) : PlayerError("资源错误: $reason")
    data class UnknownError(val code: Int, val reason: String) : PlayerError("未知错误($code): $reason")
}

/**
 * 选集数据
 */
data class Episode(
    val id: String,
    val title: String,
    val url: String,
    val poster: String? = null,
    val duration: Long = 0L,
    val isPlayed: Boolean = false,
)

/**
 * 频道数据
 */
data class Channel(
    val id: String,
    val name: String,
    val logo: String? = null,
    val url: String,
    val category: String = "综合",
    val currentProgram: String? = null,
)

/**
 * EPG 节目单
 */
data class EPGItem(
    val channelId: String,
    val programName: String,
    val startTime: Long,  // 时间戳
    val endTime: Long,
    val description: String? = null,
)
```

### 3.3 播放器核心契约

```kotlin
// shared/src/commonMain/kotlin/api/IPlayer.kt
package com.kmp.player.api

import com.kmp.player.model.PlayerState
import kotlinx.coroutines.flow.Flow

/**
 * 跨平台播放器统一接口
 * 各平台通过 expect/actual 机制提供具体实现
 */
interface IPlayer {
    
    // ====== 基础控制 ======
    
    /** 设置播放源 */
    fun setSource(url: String, type: SourceType = SourceType.AUTO)
    
    /** 播放 */
    fun play()
    
    /** 暂停 */
    fun pause()
    
    /** 停止 */
    fun stop()
    
    /** 跳转到指定位置 */
    fun seekTo(positionMs: Long)
    
    /** 设置音量 [0.0, 1.0] */
    fun setVolume(volume: Float)
    
    /** 设置静音 */
    fun setMuted(muted: Boolean)
    
    /** 设置播放速度 */
    fun setSpeed(speed: Float)
    
    // ====== 全屏控制 ======
    
    /** 进入全屏 */
    suspend fun enterFullscreen(): Boolean
    
    /** 退出全屏 */
    suspend fun exitFullscreen(): Boolean
    
    // ====== 画中画控制 ======
    
    /** 进入画中画 */
    suspend fun enterPiP(): Boolean
    
    /** 退出画中画 */
    suspend fun exitPiP(): Boolean
    
    // ====== 状态与事件 ======
    
    /** 当前播放状态（响应式） */
    val state: Flow<PlayerState>
    
    /** 获取当前进度（毫秒） */
    fun getCurrentPosition(): Long
    
    /** 获取总时长（毫秒），直播返回 0 */
    fun getDuration(): Long
    
    // ====== 生命周期 ======
    
    /** 初始化 */
    fun initialize()
    
    /** 释放资源 */
    fun release()
}

/**
 * 媒体源类型
 */
enum class SourceType {
    AUTO,       // 自动识别
    HLS,        // m3u8
    DASH,       // mpd
    MP4,        // mp4直链
    FLV,        // flv直播
    RTMP,       // rtmp
}
```

### 3.4 模式策略接口

```kotlin
// shared/src/commonMain/kotlin/strategy/ModeStrategy.kt
package com.kmp.player.strategy

import com.kmp.player.model.PlayerMode

/**
 * 模式策略接口
 */
interface ModeStrategy {
    val mode: PlayerMode
    
    /** 应用策略：返回 UI 配置 */
    fun apply(): ModeUIConfig
    
    /** 进入该模式前的准备 */
    suspend fun onEnter()
    
    /** 离开该模式后的清理 */
    suspend fun onExit()
}

/**
 * UI 配置输出
 */
data class ModeUIConfig(
    val showControls: Boolean,
    val showProgress: Boolean,
    val showDuration: Boolean,
    val showEpisodePanel: Boolean,
    val showDanmakuPanel: Boolean,
    val showChannelList: Boolean,
    val showEPG: Boolean,
    val showLiveBadge: Boolean,
    val controlsLayout: ControlsLayout = ControlsLayout.FULL,
)

enum class ControlsLayout {
    FULL,       // 完整控制栏（倍速、画质、弹幕等）
    COMPACT,    // 精简控制栏（仅播放/暂停/音量/全屏）
    MINIMAL,    // 极简（仅播放/暂停）
    NONE,       // 无控制栏（PiP模式）
}
```

### 3.5 模式管理器

```kotlin
// shared/src/commonMain/kotlin/strategy/ModeManager.kt
package com.kmp.player.strategy

import com.kmp.player.model.PlayerMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 模式管理器：统一管理 5 种模式的切换与 UI 配置
 */
class ModeManager {
    
    private val _currentMode = MutableStateFlow(PlayerMode.INLINE)
    val currentMode: StateFlow<PlayerMode> = _currentMode.asStateFlow()
    
    private val _uiConfig = MutableStateFlow(getDefaultConfig(PlayerMode.INLINE))
    val uiConfig: StateFlow<ModeUIConfig> = _uiConfig.asStateFlow()
    
    private val strategies: Map<PlayerMode, ModeStrategy> = mapOf(
        PlayerMode.INLINE to InlineStrategy(),
        PlayerMode.FULLSCREEN to FullscreenStrategy(),
        PlayerMode.PIP to PiPStrategy(),
        PlayerMode.LIVE_INLINE to LiveInlineStrategy(),
        PlayerMode.LIVE_FULLSCREEN to LiveFullscreenStrategy(),
    )
    
    /**
     * 切换模式
     */
    suspend fun switchMode(newMode: PlayerMode) {
        if (_currentMode.value == newMode) return
        
        // 退出旧模式
        strategies[_currentMode.value]?.onExit()
        
        // 切换
        _currentMode.value = newMode
        
        // 应用新模式
        strategies[newMode]?.also { strategy ->
            strategy.onEnter()
            _uiConfig.value = strategy.apply()
        }
    }
    
    /**
     * 根据内容类型自动推断模式
     */
    fun inferMode(isLive: Boolean, isFullscreen: Boolean): PlayerMode = when {
        isLive && isFullscreen -> PlayerMode.LIVE_FULLSCREEN
        isLive -> PlayerMode.LIVE_INLINE
        isFullscreen -> PlayerMode.FULLSCREEN
        else -> PlayerMode.INLINE
    }
    
    private fun getDefaultConfig(mode: PlayerMode): ModeUIConfig {
        return strategies[mode]?.apply() ?: ModeUIConfig(
            showControls = true,
            showProgress = true,
            showDuration = true,
            showEpisodePanel = false,
            showDanmakuPanel = false,
            showChannelList = false,
            showEPG = false,
            showLiveBadge = false,
        )
    }
}

// ===== 具体策略实现 =====

class InlineStrategy : ModeStrategy {
    override val mode = PlayerMode.INLINE
    override fun apply() = ModeUIConfig(
        showControls = true, showProgress = true, showDuration = true,
        showEpisodePanel = true, showDanmakuPanel = true,
        showChannelList = false, showEPG = false, showLiveBadge = false,
        controlsLayout = ControlsLayout.COMPACT,
    )
    override suspend fun onEnter() {}
    override suspend fun onExit() {}
}

class FullscreenStrategy : ModeStrategy {
    override val mode = PlayerMode.FULLSCREEN
    override fun apply() = ModeUIConfig(
        showControls = true, showProgress = true, showDuration = true,
        showEpisodePanel = false, showDanmakuPanel = false,
        showChannelList = false, showEPG = false, showLiveBadge = false,
        controlsLayout = ControlsLayout.FULL,
    )
    override suspend fun onEnter() {}
    override suspend fun onExit() {}
}

class PiPStrategy : ModeStrategy {
    override val mode = PlayerMode.PIP
    override fun apply() = ModeUIConfig(
        showControls = false, showProgress = false, showDuration = false,
        showEpisodePanel = false, showDanmakuPanel = false,
        showChannelList = false, showEPG = false, showLiveBadge = false,
        controlsLayout = ControlsLayout.NONE,
    )
    override suspend fun onEnter() {}
    override suspend fun onExit() {}
}

class LiveInlineStrategy : ModeStrategy {
    override val mode = PlayerMode.LIVE_INLINE
    override fun apply() = ModeUIConfig(
        showControls = true, showProgress = false, showDuration = false,
        showEpisodePanel = false, showDanmakuPanel = false,
        showChannelList = true, showEPG = true, showLiveBadge = true,
        controlsLayout = ControlsLayout.COMPACT,
    )
    override suspend fun onEnter() {}
    override suspend fun onExit() {}
}

class LiveFullscreenStrategy : ModeStrategy {
    override val mode = PlayerMode.LIVE_FULLSCREEN
    override fun apply() = ModeUIConfig(
        showControls = true, showProgress = false, showDuration = false,
        showEpisodePanel = false, showDanmakuPanel = false,
        showChannelList = true, showEPG = true, showLiveBadge = true,
        controlsLayout = ControlsLayout.FULL,
    )
    override suspend fun onEnter() {}
    override suspend fun onExit() {}
}
```

---

## 四、平台特定实现（expect/actual）

### 4.1 Android 端：ExoPlayer (Media3)

```kotlin
// shared/src/androidMain/kotlin/player/ExoPlayerImpl.kt
package com.kmp.player.player

import android.content.Context
import android.view.Surface
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.dash.DashMediaSource
import com.kmp.player.api.IPlayer
import com.kmp.player.api.SourceType
import com.kmp.player.model.PlayerError
import com.kmp.player.model.PlayerState
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow

actual class PlatformPlayer(
    private val context: Context
) : IPlayer {
    
    private var exoPlayer: ExoPlayer? = null
    private val _state = MutableStateFlow(PlayerState())
    
    override val state: Flow<PlayerState> = _state
    
    override fun setSource(url: String, type: SourceType) {
        val player = ensurePlayer()
        val mediaItem = MediaItem.fromUri(url)
        
        val source = when (type) {
            SourceType.HLS -> HlsMediaSource.Factory().createMediaSource(mediaItem)
            SourceType.DASH -> DashMediaSource.Factory().createMediaSource(mediaItem)
            else -> null
        }
        
        if (source != null) {
            player.setMediaSource(source)
        } else {
            player.setMediaItem(mediaItem)
        }
        player.prepare()
        player.playWhenReady = true
    }
    
    override fun play() {
        exoPlayer?.play()
    }
    
    override fun pause() {
        exoPlayer?.pause()
    }
    
    override fun stop() {
        exoPlayer?.stop()
    }
    
    override fun seekTo(positionMs: Long) {
        exoPlayer?.seekTo(positionMs)
    }
    
    override fun setVolume(volume: Float) {
        exoPlayer?.volume = volume
    }
    
    override fun setMuted(muted: Boolean) {
        exoPlayer?.volume = if (muted) 0f else 1f
    }
    
    override fun setSpeed(speed: Float) {
        exoPlayer?.setPlaybackSpeed(speed)
    }
    
    override suspend fun enterFullscreen(): Boolean {
        // 通知宿主 Activity 进入全屏
        // 通过回调或事件总线实现
        return true
    }
    
    override suspend fun exitFullscreen(): Boolean {
        return true
    }
    
    override suspend fun enterPiP(): Boolean {
        // Android 8.0+ 支持 PiP
        // 通知宿主 Activity 进入 PiP 模式
        return true
    }
    
    override suspend fun exitPiP(): Boolean {
        return true
    }
    
    override fun getCurrentPosition(): Long = exoPlayer?.currentPosition ?: 0L
    override fun getDuration(): Long = if (exoPlayer?.isCurrentMediaItemLive == true) 0L else (exoPlayer?.duration ?: 0L)
    
    override fun initialize() {
        if (exoPlayer == null) {
            exoPlayer = ExoPlayer.Builder(context).build().apply {
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        when (playbackState) {
                            Player.STATE_BUFFERING -> _state.value = _state.value.copy(isBuffering = true)
                            Player.STATE_READY -> _state.value = _state.value.copy(isBuffering = false, isPlaying = isPlaying)
                            Player.STATE_ENDED -> _state.value = _state.value.copy(isPlaying = false)
                        }
                    }
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        _state.value = _state.value.copy(
                            isPlaying = isPlaying,
                            currentTime = getCurrentPosition(),
                            duration = getDuration(),
                        )
                    }
                    override fun onPlayerError(error: PlaybackException) {
                        _state.value = _state.value.copy(
                            error = PlayerError.UnknownError(error.errorCode, error.message ?: "")
                        )
                    }
                })
            }
        }
    }
    
    override fun release() {
        exoPlayer?.release()
        exoPlayer = null
    }
    
    private fun ensurePlayer(): ExoPlayer {
        if (exoPlayer == null) initialize()
        return exoPlayer!!
    }
    
    /** 绑定渲染 Surface（由宿主 UI 层调用） */
    fun setVideoSurface(surface: Surface) {
        exoPlayer?.setVideoSurface(surface)
    }
}
```

### 4.2 iOS 端：AVPlayer

```kotlin
// shared/src/iosMain/kotlin/player/AVPlayerImpl.kt
package com.kmp.player.player

import com.kmp.player.api.IPlayer
import com.kmp.player.api.SourceType
import com.kmp.player.model.PlayerError
import com.kmp.player.model.PlayerState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.setActive
import platform.AVFoundation.*
import platform.Foundation.*
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidEnterBackgroundNotification

actual class PlatformPlayer : IPlayer {
    
    private var avPlayer: AVPlayer? = null
    private var avPlayerLayer: AVPlayerLayer? = null
    private var timeObserver: Any? = null
    
    private val _state = MutableStateFlow(PlayerState())
    override val state: Flow<PlayerState> = _state
    
    override fun setSource(url: String, type: SourceType) {
        val nsUrl = NSURL.URLWithString(url) ?: return
        val asset = AVURLAsset.uRLAssetWithURL(nsUrl)
        val item = AVPlayerItem.playerItemWithAsset(asset)
        
        avPlayer?.replaceCurrentItemWithPlayerItem(item)
        avPlayer?.play()
        
        setupObservers()
    }
    
    override fun play() {
        avPlayer?.play()
        _state.value = _state.value.copy(isPlaying = true)
    }
    
    override fun pause() {
        avPlayer?.pause()
        _state.value = _state.value.copy(isPlaying = false)
    }
    
    override fun stop() {
        avPlayer?.pause()
        avPlayer?.replaceCurrentItemWithPlayerItem(null)
        _state.value = _state.value.copy(isPlaying = false, currentTime = 0L)
    }
    
    override fun seekTo(positionMs: Long) {
        val cmTime = CMTimeMakeWithSeconds(positionMs / 1000.0, 600)
        avPlayer?.seekToTime(cmTime)
    }
    
    override fun setVolume(volume: Float) {
        avPlayer?.volume = volume.toDouble()
    }
    
    override fun setMuted(muted: Boolean) {
        avPlayer?.volume = if (muted) 0.0 else 1.0
    }
    
    override fun setSpeed(speed: Float) {
        avPlayer?.rate = speed.toDouble()
    }
    
    override suspend fun enterFullscreen(): Boolean {
        // 通知 iOS 宿主进入全屏
        return true
    }
    
    override suspend fun exitFullscreen(): Boolean {
        return true
    }
    
    override suspend fun enterPiP(): Boolean {
        // iOS 14+ 支持 PiP
        // 通过 AVPictureInPictureController 实现
        return true
    }
    
    override suspend fun exitPiP(): Boolean {
        return true
    }
    
    override fun getCurrentPosition(): Long {
        val seconds = avPlayer?.currentTime()?.seconds ?: 0.0
        return (seconds * 1000).toLong()
    }
    
    override fun getDuration(): Long {
        val seconds = avPlayer?.currentItem?.duration?.seconds ?: 0.0
        return if (seconds.isInfinite() || seconds.isNaN()) 0L else (seconds * 1000).toLong()
    }
    
    override fun initialize() {
        if (avPlayer == null) {
            avPlayer = AVPlayer()
            // 配置音频会话
            try {
                AVAudioSession.sharedInstance().setCategory(AVAudioSessionCategoryPlayback, null)
                AVAudioSession.sharedInstance().setActive(true, null)
            } catch (e: Exception) {
                // iOS 音频会话配置失败
            }
        }
    }
    
    override fun release() {
        if (timeObserver != null) {
            avPlayer?.removeTimeObserver(timeObserver!!)
            timeObserver = null
        }
        avPlayer?.pause()
        avPlayer?.replaceCurrentItemWithPlayerItem(null)
        avPlayer = null
    }
    
    private fun setupObservers() {
        // 播放状态观察
        avPlayer?.currentItem?.addObserver(
            this,
            forKeyPath = "status",
            options = NSKeyValueObservingOptionNew,
            context = null
        )
        
        // 定时更新进度
        val interval = CMTimeMakeWithSeconds(0.5, 600)
        timeObserver = avPlayer?.addPeriodicTimeObserverForInterval(
            interval = interval,
            queue = null,
            usingBlock = { _ ->
                _state.value = _state.value.copy(
                    currentTime = getCurrentPosition(),
                    duration = getDuration(),
                )
            }
        )
    }
    
    /** 绑定渲染 Layer（由宿主 UI 层调用） */
    fun setPlayerLayer(layer: AVPlayerLayer) {
        this.avPlayerLayer = layer
        avPlayerLayer?.player = avPlayer
    }
}
```

### 4.3 统一接口暴露

```kotlin
// shared/src/commonMain/kotlin/api/PlayerFactory.kt
package com.kmp.player.api

/**
 * 平台播放器工厂
 * 通过 expect/actual 在各平台创建具体实例
 */
expect fun createPlayer(): IPlayer
```

```kotlin
// shared/src/androidMain/kotlin/api/PlayerFactory.android.kt
package com.kmp.player.api

import android.content.Context
import com.kmp.player.player.PlatformPlayer

actual fun createPlayer(): IPlayer {
    return PlatformPlayer(context = getApplicationContext())
}

// 通过 Koin 注入 Context
private fun getApplicationContext(): Context {
    return org.koin.java.KoinJavaComponent.getKoin().get()
}
```

```kotlin
// shared/src/iosMain/kotlin/api/PlayerFactory.ios.kt
package com.kmp.player.api

import com.kmp.player.player.PlatformPlayer

actual fun createPlayer(): IPlayer {
    return PlatformPlayer()
}
```

```kotlin
// shared/src/jsMain/kotlin/api/PlayerFactory.js.kt
package com.kmp.player.api

import com.kmp.player.player.PlatformPlayer

actual fun createPlayer(): IPlayer {
    return PlatformPlayer()
}
```

---

## 五、ViewModel 状态管理

```kotlin
// shared/src/commonMain/kotlin/state/PlayerViewModel.kt
package com.kmp.player.state

import com.kmp.player.api.IPlayer
import com.kmp.player.api.createPlayer
import com.kmp.player.model.PlayerMode
import com.kmp.player.model.PlayerState
import com.kmp.player.strategy.ModeManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * 统一播放器 ViewModel
 * 连接 播放器内核 + 模式管理器 + UI层
 */
class PlayerViewModel : IDisposable {
    
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    private val player: IPlayer = createPlayer()
    private val modeManager = ModeManager()
    
    private val _episodes = MutableStateFlow<List<Episode>>(emptyList())
    val episodes: StateFlow<List<Episode>> = _episodes
    
    private val _channels = MutableStateFlow<List<Channel>>(emptyList())
    val channels: StateFlow<List<Channel>> = _channels
    
    private val _epgData = MutableStateFlow<Map<String, List<EPGItem>>>(emptyMap())
    val epgData: StateFlow<Map<String, List<EPGItem>>> = _epgData
    
    /** 组合后的 UI 状态 */
    val uiState = combine(
        player.state,
        modeManager.currentMode,
        modeManager.uiConfig,
        _episodes,
        _channels,
    ) { playerState, mode, uiConfig, episodes, channels ->
        PlayerUIState(
            playerState = playerState,
            mode = mode,
            uiConfig = uiConfig,
            episodes = episodes,
            channels = channels,
            currentChannel = channels.find { it.id == playerState.currentChannel?.id },
        )
    }
    
    init {
        player.initialize()
        // 监听播放器内部状态变化
        player.state.onEach { state ->
            if (state.error != null) {
                handleError(state.error)
            }
        }.launchIn(scope)
    }
    
    // ====== 模式切换 ======
    
    fun switchMode(mode: PlayerMode) {
        scope.launch {
            modeManager.switchMode(mode)
            // 联动全屏/PiP
            when (mode) {
                PlayerMode.FULLSCREEN -> player.enterFullscreen()
                PlayerMode.LIVE_FULLSCREEN -> player.enterFullscreen()
                PlayerMode.PIP -> player.enterPiP()
                else -> { /* 退出全屏/PiP由平台处理 */ }
            }
        }
    }
    
    // ====== 播放控制 ======
    
    fun loadEpisode(episode: Episode) {
        _episodes.value = _episodes.value.map { it.copy(isPlayed = it.id == episode.id) }
        player.setSource(episode.url)
        player.play()
    }
    
    fun loadChannel(channel: Channel) {
        player.setSource(channel.url, SourceType.HLS)
        player.play()
    }
    
    fun togglePlayPause() {
        if (player.state.value.isPlaying) {
            player.pause()
        } else {
            player.play()
        }
    }
    
    fun seekTo(positionMs: Long) {
        if (!modeManager.currentMode.value.isLive) {
            player.seekTo(positionMs)
        }
    }
    
    fun setVolume(volume: Float) {
        player.setVolume(volume)
    }
    
    fun setSpeed(speed: Float) {
        player.setSpeed(speed)
    }
    
    // ====== 全屏 / PiP ======
    
    fun toggleFullscreen() {
        scope.launch {
            val current = modeManager.currentMode.value
            if (current.isFullscreen) {
                player.exitFullscreen()
                switchMode(modeManager.inferMode(current.isLive, false))
            } else {
                player.enterFullscreen()
                switchMode(modeManager.inferMode(current.isLive, true))
            }
        }
    }
    
    fun togglePiP() {
        scope.launch {
            val current = modeManager.currentMode.value
            if (current == PlayerMode.PIP) {
                player.exitPiP()
                switchMode(modeManager.inferMode(current.isLive, false))
            } else {
                player.enterPiP()
                switchMode(PlayerMode.PIP)
            }
        }
    }
    
    // ====== 数据设置 ======
    
    fun setEpisodes(list: List<Episode>) {
        _episodes.value = list
    }
    
    fun setChannels(list: List<Channel>) {
        _channels.value = list
    }
    
    fun setEPGData(data: Map<String, List<EPGItem>>) {
        _epgData.value = data
    }
    
    // ====== 错误处理 ======
    
    private fun handleError(error: com.kmp.player.model.PlayerError) {
        // 重试策略
        scope.launch {
            kotlinx.coroutines.delay(2000)
            // 自动重试逻辑...
        }
    }
    
    // ====== 生命周期 ======
    
    override fun dispose() {
        scope.cancel()
        player.release()
    }
}

interface IDisposable {
    fun dispose()
}

/**
 * UI 层组合状态
 */
data class PlayerUIState(
    val playerState: PlayerState,
    val mode: PlayerMode,
    val uiConfig: com.kmp.player.strategy.ModeUIConfig,
    val episodes: List<Episode>,
    val channels: List<Channel>,
    val currentChannel: Channel?,
)
```

---

## 六、Compose Multiplatform UI 层

```kotlin
// shared/src/commonMain/kotlin/ui/PlayerScreen.kt
package com.kmp.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.kmp.player.model.PlayerMode
import com.kmp.player.state.PlayerViewModel
import com.kmp.player.strategy.ControlsLayout
import kotlinx.coroutines.launch

/**
 * 统一播放器 UI（跨平台共享）
 * 根据 ModeUIConfig 动态渲染不同布局
 */
@Composable
fun PlayerScreen(
    viewModel: PlayerViewModel,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()
    val scope = rememberCoroutineScope()
    
    val state = uiState.playerState
    val mode = uiState.mode
    val config = uiState.uiConfig
    
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        // 1. 视频渲染区域（由平台实际渲染，这里占位）
        VideoSurface(
            modifier = Modifier.fillMaxSize()
        )
        
        // 2. 加载指示器
        if (state.isBuffering) {
            CircularProgressIndicator(
                color = Color.White,
                modifier = Modifier.align(Alignment.Center)
            )
        }
        
        // 3. 直播标识
        if (config.showLiveBadge) {
            LiveBadge(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(16.dp)
            )
        }
        
        // 4. 底部控制区
        if (config.showControls) {
            PlayerControls(
                state = state,
                mode = mode,
                config = config,
                onPlayPause = { viewModel.togglePlayPause() },
                onSeek = { viewModel.seekTo(it) },
                onVolumeChange = { viewModel.setVolume(it) },
                onSpeedChange = { viewModel.setSpeed(it) },
                onFullscreen = { viewModel.toggleFullscreen() },
                onPiP = { viewModel.togglePiP() },
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
        
        // 5. 侧边面板（选集 / 频道列表 / EPG）
        SidePanels(
            config = config,
            episodes = uiState.episodes,
            channels = uiState.channels,
            currentEpisode = state.currentEpisode,
            currentChannel = uiState.currentChannel,
            onEpisodeSelect = { viewModel.loadEpisode(it) },
            onChannelSelect = { viewModel.loadChannel(it) },
        )
    }
}

/**
 * 视频渲染组件（expect/actual 平台实现）
 */
@Composable
expect fun VideoSurface(modifier: Modifier)

/**
 * 控制栏组件
 */
@Composable
fun PlayerControls(
    state: com.kmp.player.model.PlayerState,
    mode: PlayerMode,
    config: com.kmp.player.strategy.ModeUIConfig,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onVolumeChange: (Float) -> Unit,
    onSpeedChange: (Float) -> Unit,
    onFullscreen: () -> Unit,
    onPiP: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        // 进度条（非直播）
        if (config.showProgress && state.duration > 0) {
            PlayerProgressBar(
                currentTime = state.currentTime,
                duration = state.duration,
                onSeek = onSeek,
            )
        }
        
        // 控制按钮行
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0x80000000))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左侧：播放/暂停 + 音量
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onPlayPause) {
                    Icon(
                        painter = if (state.isPlaying) painterResource("ic_pause") else painterResource("ic_play"),
                        contentDescription = if (state.isPlaying) "暂停" else "播放",
                        tint = Color.White
                    )
                }
                
                if (config.controlsLayout != ControlsLayout.MINIMAL) {
                    VolumeSlider(
                        value = state.volume,
                        onValueChange = onVolumeChange,
                        muted = state.isMuted,
                    )
                }
                
                // 时间显示
                if (config.showDuration && state.duration > 0) {
                    Text(
                        text = "${formatTime(state.currentTime)} / ${formatTime(state.duration)}",
                        color = Color.White,
                        style = MaterialTheme.typography.body2
                    )
                } else if (mode.isLive) {
                    Text(
                        text = "直播中",
                        color = Color.Red,
                        style = MaterialTheme.typography.body2
                    )
                }
            }
            
            // 右侧：倍速 + 画质 + PiP + 全屏
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (config.controlsLayout == ControlsLayout.FULL) {
                    // 倍速
                    SpeedButton(
                        currentSpeed = state.playbackSpeed,
                        onSpeedChange = onSpeedChange,
                    )
                    // 画质
                    QualityButton(
                        current = state.currentQuality,
                        available = state.availableQualities,
                    )
                }
                
                // PiP 按钮（非直播模式）
                if (!mode.isLive) {
                    IconButton(onClick = onPiP) {
                        Icon(
                            painter = painterResource("ic_pip"),
                            contentDescription = "画中画",
                            tint = Color.White
                        )
                    }
                }
                
                // 全屏按钮
                IconButton(onClick = onFullscreen) {
                    Icon(
                        painter = if (mode.isFullscreen) painterResource("ic_exit_fullscreen") else painterResource("ic_fullscreen"),
                        contentDescription = if (mode.isFullscreen) "退出全屏" else "全屏",
                        tint = Color.White
                    )
                }
            }
        }
    }
}

/**
 * 进度条组件
 */
@Composable
fun PlayerProgressBar(
    currentTime: Long,
    duration: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var isDragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableStateOf(0f) }
    
    val progress = if (duration > 0) currentTime.toFloat() / duration else 0f
    val displayProgress = if (isDragging) dragValue else progress
    
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Slider(
            value = displayProgress,
            onValueChange = { 
                isDragging = true
                dragValue = it 
            },
            onValueChangeFinished = {
                isDragging = false
                onSeek((dragValue * duration).toLong())
            },
            colors = SliderDefaults.colors(
                thumbColor = Color.Red,
                activeTrackColor = Color.Red,
                inactiveTrackColor = Color(0x40FFFFFF),
            )
        )
    }
}

/**
 * 侧边面板（选集 / 频道 / EPG）
 */
@Composable
fun SidePanels(
    config: com.kmp.player.strategy.ModeUIConfig,
    episodes: List<Episode>,
    channels: List<Channel>,
    currentEpisode: Episode?,
    currentChannel: Channel?,
    onEpisodeSelect: (Episode) -> Unit,
    onChannelSelect: (Channel) -> Unit,
) {
    // 频道列表（直播模式）
    if (config.showChannelList && channels.isNotEmpty()) {
        ChannelListPanel(
            channels = channels,
            currentChannel = currentChannel,
            onChannelSelect = onChannelSelect,
        )
    }
    
    // 选集面板（点播模式）
    if (config.showEpisodePanel && episodes.isNotEmpty()) {
        EpisodePanel(
            episodes = episodes,
            currentEpisode = currentEpisode,
            onEpisodeSelect = onEpisodeSelect,
        )
    }
}

// ===== 辅助函数 =====

fun formatTime(ms: Long): String {
    val seconds = ms / 1000
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) {
        String.format("%d:%02d:%02d", h, m, s)
    } else {
        String.format("%02d:%02d", m, s)
    }
}

@Composable
expect fun painterResource(name: String): androidx.compose.ui.graphics.painter.Painter
```

### 6.1 Android 端 VideoSurface 实际渲染

```kotlin
// androidApp/src/main/java/ui/VideoSurface.android.kt
package ui

import android.view.SurfaceView
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.kmp.player.player.PlatformPlayer
import org.koin.java.KoinJavaComponent

@Composable
actual fun VideoSurface(modifier: Modifier) {
    val player = remember { 
        org.koin.java.KoinJavaComponent.getKoin().get<PlatformPlayer>() 
    }
    
    AndroidView(
        modifier = modifier,
        factory = { context ->
            SurfaceView(context).also { surfaceView ->
                surfaceView.holder.addCallback(object : android.view.SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: android.view.SurfaceHolder) {
                        player.setVideoSurface(holder.surface)
                    }
                    override fun surfaceChanged(holder: android.view.SurfaceHolder, format: Int, width: Int, height: Int) {}
                    override fun surfaceDestroyed(holder: android.view.SurfaceHolder) {
                        player.setVideoSurface(null)
                    }
                })
            }
        }
    )
}
```

### 6.2 iOS 端 VideoSurface 实际渲染

```kotlin
// iosApp/iosApp/VideoSurface.ios.kt
package iosApp

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import com.kmp.player.player.PlatformPlayer
import platform.AVFoundation.AVPlayerLayer
import platform.CoreAnimation.CATransaction
import platform.QuartzCore.CALayer

@Composable
actual fun VideoSurface(modifier: Modifier) {
    val player = remember { PlatformPlayer() }
    
    UIKitView(
        modifier = modifier,
        factory = {
            AVPlayerLayer().also { layer ->
                player.setPlayerLayer(layer)
            }
        },
        update = { layer ->
            CATransaction.begin()
            CATransaction.setDisableActions(true)
            layer.setNeedsLayout()
            CATransaction.commit()
        }
    )
}
```

---

## 七、宿主层集成

### 7.1 Android 全屏 Activity

```kotlin
// androidApp/src/main/java/FullscreenPlayerActivity.kt
package com.kmp.player.app

import android.app.PictureInPictureParams
import android.content.res.Configuration
import android.os.Bundle
import android.util.Rational
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.kmp.player.ui.PlayerScreen
import org.koin.androidx.viewmodel.ext.android.viewModel

class FullscreenPlayerActivity : ComponentActivity() {
    
    private val viewModel: PlayerViewModel by viewModel()
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 进入全屏
        window.decorView.systemUiVisibility = (
            android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
            or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        setContent {
            PlayerScreen(
                viewModel = viewModel,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
    
    // ====== PiP 支持 ======
    
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            enterPictureInPictureMode(
                PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(16, 9))
                    .build()
            )
        }
    }
    
    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            viewModel.switchMode(com.kmp.player.model.PlayerMode.PIP)
        } else {
            viewModel.switchMode(com.kmp.player.model.PlayerMode.FULLSCREEN)
        }
    }
    
    override fun onDestroy() {
        viewModel.dispose()
        super.onDestroy()
    }
}
```

### 7.2 iOS 宿主 ViewController

```swift
// iosApp/iosApp/PlayerViewController.swift
import UIKit
import AVKit
import SwiftUI
import shared  // KMP 共享模块

class PlayerViewController: UIViewController {
    
    private var viewModel: PlayerViewModel!
    private var playerLayer: AVPlayerLayer!
    
    override func viewDidLoad() {
        super.viewDidLoad()
        
        viewModel = PlayerViewModel()
        
        // 设置视频渲染 Layer
        playerLayer = AVPlayerLayer()
        playerLayer.videoGravity = .resizeAspect
        view.layer.addSublayer(playerLayer)
        
        // 设置 Compose UI
        let hostingController = UIHostingController(
            rootView: PlayerScreenKt.PlayerScreen(viewModel: viewModel)
        )
        addChild(hostingController)
        view.addSubview(hostingController.view)
        hostingController.didMove(toParent: self)
    }
    
    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        playerLayer.frame = view.bounds
    }
    
    // ====== PiP 支持 (iOS 14+) ======
    
    func enterPiP() {
        if #available(iOS 14.0, *) {
            let controller = AVPictureInPictureController(playerLayer: playerLayer)
            controller.startPictureInPicture()
        }
    }
    
    // ====== 旋转监听 ======
    
    override func viewWillTransition(to size: CGSize, with coordinator: UIViewControllerTransitionCoordinator) {
        super.viewWillTransition(to: size, with: coordinator)
        coordinator.animate { _ in
            let isLandscape = size.width > size.height
            if isLandscape {
                self.viewModel.switchMode(mode: self.viewModel.inferMode(isLive: false, isFullscreen: true))
            } else {
                self.viewModel.switchMode(mode: self.viewModel.inferMode(isLive: false, isFullscreen: false))
            }
        }
    }
}
```

---

## 八、Gradle 构建配置

```kotlin
// shared/build.gradle.kts
plugins {
    kotlin("multiplatform") version "2.0.21"
    id("com.android.library")
    id("org.jetbrains.compose") version "1.7.0"
}

kotlin {
    // Android
    androidTarget {
        compilations.all {
            kotlinOptions {
                jvmTarget = "17"
            }
        }
    }
    
    // iOS
    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64()
    ).forEach {
        it.binaries.framework {
            baseName = "shared"
            isStatic = true
        }
    }
    
    // Web (JS)
    js(IR) {
        browser()
        binaries.executable()
    }
    
    // 源集
    sourceSets {
        commonMain.dependencies {
            // Compose Multiplatform
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material)
            implementation(compose.ui)
            implementation(compose.materialIconsExtended)
            
            // 协程
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
            
            // 状态管理
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-flow:1.8.1")
            
            // 序列化
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")
            
            // 依赖注入
            api("io.insert-koin:koin-core:3.5.6")
        }
        
        androidMain.dependencies {
            // Media3 ExoPlayer
            api("androidx.media3:media3-exoplayer:1.4.1")
            api("androidx.media3:media3-exoplayer-hls:1.4.1")
            api("androidx.media3:media3-exoplayer-dash:1.4.1")
            
            // Koin Android
            api("io.insert-koin:koin-android:3.5.6")
        }
        
        iosMain.dependencies {
            // iOS 无需额外播放器依赖（AVFoundation 原生）
        }
        
        jsMain.dependencies {
            implementation(npm("hls.js", "1.5.7"))
        }
    }
}

android {
    namespace = "com.kmp.player"
    compileSdk = 34
    defaultConfig {
        minSdk = 24
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
```

---

## 九、实现计划

### 阶段一：基础设施搭建（Week 1-2）

| 任务 | 产出 | 平台 | 优先级 |
|------|------|------|--------|
| KMP 项目骨架搭建 | Gradle 多模块工程 | All | P0 |
| 定义 `IPlayer` 接口 | 核心契约 | commonMain | P0 |
| 定义 `PlayerState` 模型 | 统一状态模型 | commonMain | P0 |
| ExoPlayer (Media3) 适配器 | `ExoPlayerImpl` | androidMain | P0 |
| AVPlayer 适配器 | `AVPlayerImpl` | iosMain | P0 |
| HTML5 Player 适配器 | `Html5PlayerImpl` | jsMain | P1 |
| Koin 依赖注入配置 | DI 模块 | All | P0 |

### 阶段二：模式策略与状态管理（Week 3）

| 任务 | 产出 | 平台 | 优先级 |
|------|------|------|--------|
| `PlayerMode` 枚举 + 配置 | 模式定义 | commonMain | P0 |
| `ModeStrategy` 策略接口 | 策略模式 | commonMain | P0 |
| `ModeManager` 管理器 | 5种模式切换 | commonMain | P0 |
| `PlayerViewModel` 状态机 | 组合 Flow 状态 | commonMain | P0 |
| 全屏 API 适配 | Android/iOS 全屏 | android/iOS | P0 |
| PiP API 适配 | Android/iOS 画中画 | android/iOS | P0 |

### 阶段三：Compose UI 开发（Week 4-5）

| 任务 | 产出 | 平台 | 优先级 |
|------|------|------|--------|
| `PlayerScreen` 主组件 | 统一 UI 入口 | commonMain | P0 |
| `PlayerControls` 控制栏 | 播放/暂停/音量/全屏 | commonMain | P0 |
| `PlayerProgressBar` 进度条 | 拖拽/缓冲显示 | commonMain | P0 |
| `VideoSurface` expect/actual | 平台渲染桥接 | All | P0 |
| `EpisodePanel` 选集面板 | 点播选集 | commonMain | P0 |
| `ChannelListPanel` 频道列表 | 直播频道切换 | commonMain | P0 |
| `EPGPanel` 节目单 | 直播 EPG | commonMain | P1 |
| `LiveBadge` 直播角标 | UI 标识 | commonMain | P1 |
| 弹幕渲染引擎 | `DanmakuView` | commonMain | P2 |

### 阶段四：宿主集成与平台适配（Week 6）

| 任务 | 产出 | 平台 | 优先级 |
|------|------|------|--------|
| Android `FullscreenActivity` | 全屏宿主 + PiP回调 | Android | P0 |
| Android 嵌入式 `PlayerView` | RecyclerView/页面嵌入 | Android | P0 |
| iOS `PlayerViewController` | 全屏 + 旋转监听 + PiP | iOS | P0 |
| iOS SwiftUI 包装 | `PlayerView.swift` 桥接 | iOS | P0 |
| Web 宿主集成 | Compose Web / React 桥接 | Web | P1 |
| 手势识别（双击/滑动） | 双击暂停、滑动调音量 | commonMain | P1 |
| 键盘快捷键 | Space/方向键/F键 | Android/iOS | P2 |

### 阶段五：测试与优化（Week 7-8）

| 任务 | 产出 | 平台 | 优先级 |
|------|------|------|--------|
| HLS 流媒体测试 | m3u8 多码率切换 | All | P0 |
| 直播低延迟优化 | 缓冲策略调整 | All | P0 |
| 内存泄漏检测 | LeakCanary + Instruments | Android/iOS | P0 |
| 性能基准测试 | 首帧时间 < 2s | All | P0 |
| 多格式兼容测试 | MP4/HLS/DASH/FLV | All | P0 |
| 弱网降级策略 | 自动降码率 | All | P1 |
| 鸿蒙平台适配 | CMP 鸿蒙版 Beta 接入 | Harmony | P2 |

---

## 十、关键设计原则

```
┌────────────────────────────────────────────────────────┐
│  KMP 五模式播放器核心设计原则                            │
├────────────────────────────────────────────────────────┤
│  1. 共享优先：业务逻辑 100% 共享，平台差异收敛到 actual  │
│  2. 策略模式：新增模式不改引擎，只加 Strategy 实现      │
│  3. 单向数据流：StateFlow 驱动 UI，无直接 DOM 操作      │
│  4. 插件化：弹幕/字幕/水印作为独立插件注册              │
│  5. 降级优雅：PiP 不支持 → 悬浮小窗；全屏不支持 → 撑满  │
│  6. 生命周期感知：页面销毁时自动 release 播放器资源     │
│  7. 类型安全：Kotlin 空安全 + sealed class 错误模型    │
│  8. 响应式：所有状态变化通过 Flow 通知，UI 自动重组     │
└────────────────────────────────────────────────────────┘
```

---

## 十一、模式切换时序图

```
用户操作                    ModeManager              PlayerEngine            UI层
  │                           │                         │                     │
  │── 点击全屏按钮 ──────────>│                         │                     │
  │                           │── switchMode(FULLSCREEN)─>│                  │
  │                           │                         │                     │
  │                           │                         │── enterFullscreen()─>│
  │                           │                         │<── 全屏回调 ────────│
  │                           │                         │                     │
  │                           │── apply() ──────────────>│                     │
  │                           │   (返回 FULL 配置)       │                     │
  │                           │                         │                     │
  │<── UIConfig 更新 ────────────────────────────────────>│                     │
  │                           │                         │                     │
  │                           │                         │                     │── 重组：显示完整控制栏
  │                           │                         │                     │── 重组：显示进度条
  │                           │                         │                     │── 重组：隐藏选集面板
  │                           │                         │                     │── 重组：全屏尺寸
```

---
