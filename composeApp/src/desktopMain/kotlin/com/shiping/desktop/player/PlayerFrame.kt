package com.shiping.desktop.player

import com.shiping.desktop.data.model.PlayHistoryEntity
import com.shiping.desktop.data.prefs.DesktopPrefs
import com.shiping.desktop.di.AppContainer
import com.shiping.desktop.ui.PlaybackSession
import com.shiping.desktop.ui.sourceName
import com.shiping.desktop.util.Constants
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.JFrame
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.WindowConstants

/**
 * 独立 Swing 播放窗口（点播/直播/局域网推送统一承载）：
 * Compose 窗口内嵌 SwingPanel 时，任何 Compose 控件持续重绘（进度条/时钟）都会引发
 * heavyweight 混合闪烁；独立 JFrame 承载视频面 + Swing 覆盖控制层是原生形态，彻底规避。
 * 视频面与控制层由 [PlayerOverlay] 统一承载，本窗口只负责：
 * 窗口生命周期、最大化/全屏/小窗、进入后台暂停、播放历史、B 站清晰度切换。
 */
class PlayerFrame(
    private val controller: PlayerController,
    session: PlaybackSession,
    private val callbacks: Callbacks,
) : JFrame("视界 Desktop - 播放器"), PlayerWindowActions {

    /** 与 Compose 侧的交互回调 */
    interface Callbacks {
        /** 关闭播放器（通知 AppViewModel 置空会话） */
        fun onClose()

        /** B 站清晰度切换（复用 AppViewModel 逻辑换直链后带进度重播） */
        fun onSwitchQuality(session: PlaybackSession, qn: Int, resumeMs: Long)

        /** 选集/换源/上下集（按索引重新解析起播；点播会话才有，直播/推送为空列表不展示） */
        fun onPlayEpisode(sourceIndex: Int, episodeIndex: Int)

        /** 下载当前集（索引定位集名） */
        fun onDownloadEpisode(episodeIndex: Int)
    }

    /** 覆盖控制层（视频面 + 分辨率/进度/控制排）；独立播放窗口：完整布局 + 保留小窗/最大化 */
    private val overlay = PlayerOverlay(controller.swingComponent, controller, this, standalone = true)

    /** 当前会话（清晰度切换时取上下文） */
    @Volatile
    private var currentSession: PlaybackSession = session

    private val historyTimer = Timer(Constants.HISTORY_SAVE_INTERVAL_MS.toInt()) { saveHistory() }

    private val prefs get() = AppContainer.get().prefs

    /** 最小化前是否正在播放（进入后台暂停 → 恢复时续播） */
    private var resumeOnRestore = false

    /** 是否已关闭（防重入：stop/release/onClose 只执行一次） */
    @Volatile
    private var closed = false

    /** 是否处于全屏（无边框）状态 */
    private var fullscreen = false

    /** 进入全屏前的窗口形态（退出时还原，不丢最大化状态） */
    private var savedExtendedState = JFrame.NORMAL

    /** 小窗模式前的窗口边界（还原用） */
    private var miniRestoreBounds: java.awt.Rectangle? = null

    init {
        defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
        addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent?) {
                closeFrame()
            }

            // 首次显示完成后才起播：内核要求 play 时视频面组件已 displayable，
            // 构造阶段组件尚未挂入窗口，直接 play 会抛 IllegalStateException
            override fun windowOpened(e: WindowEvent?) {
                if (closed) return
                // 起播跳片头（记录过片头且续播位置早于片头结束点时定位到片头后）
                val introEnd = IntroMark.endMs(
                    prefs,
                    IntroMark.Mark(currentSession.vodId, currentSession.sourceUrl, currentSession.episodeName),
                )
                controller.play(
                    currentSession.playUrl,
                    currentSession.headers,
                    maxOf(currentSession.startMs, introEnd),
                )
                // 全局倍速（设置页默认速率，非 1x 时起播即生效并同步按钮文案）
                val speed = prefs.getString(DesktopPrefs.KEY_GLOBAL_SPEED).toFloatOrNull() ?: 1f
                if (speed != 1f) controller.setRate(speed)
                overlay.updateSpeedLabel(speed)
            }

            // 进入后台暂停播放（设置开关）：最小化暂停、还原续播
            override fun windowIconified(e: WindowEvent?) {
                if (prefs.getBoolean(DesktopPrefs.KEY_PAUSE_ON_BACKGROUND, false) && controller.isPlaying.value) {
                    resumeOnRestore = true
                    controller.togglePlayPause()
                }
            }

            override fun windowDeiconified(e: WindowEvent?) {
                if (resumeOnRestore) {
                    resumeOnRestore = false
                    controller.togglePlayPause()
                }
            }
        })
        layout = BorderLayout()
        add(overlay, BorderLayout.CENTER)

        size = Dimension(1280, 760)
        setLocationRelativeTo(null)

        refreshFromSession(session)
        historyTimer.start()
    }

    override fun toggleMaximize() {
        extendedState = if (extendedState and JFrame.MAXIMIZED_BOTH != 0) JFrame.NORMAL else JFrame.MAXIMIZED_BOTH
    }

    /** 全屏切换：无边框 + 最大化（重挂窗口使装饰变更生效；退出还原进入前的窗口形态） */
    override fun toggleFullscreen() {
        runCatching {
            isVisible = false
            isUndecorated = !fullscreen
            fullscreen = !fullscreen
            if (fullscreen) {
                savedExtendedState = extendedState
                extendedState = JFrame.MAXIMIZED_BOTH
            } else {
                extendedState = savedExtendedState
            }
            isVisible = true
            overlay.updateFullscreenState(fullscreen)
        }
    }

    override val isFullscreen: Boolean get() = fullscreen

    /** 小窗口播放：缩到屏幕右下角置顶小窗；再次点击还原原位置原尺寸 */
    override fun toggleMiniWindow() {
        val restore = miniRestoreBounds
        if (restore == null) {
            miniRestoreBounds = bounds
            isAlwaysOnTop = true
            val screen = graphicsConfiguration.bounds
            setSize(MINI_WIDTH, MINI_HEIGHT)
            setLocation(
                screen.x + screen.width - MINI_WIDTH - MINI_MARGIN,
                screen.y + screen.height - MINI_HEIGHT - MINI_MARGIN,
            )
        } else {
            miniRestoreBounds = null
            isAlwaysOnTop = false
            bounds = restore
        }
    }

    /** 会话更新（新影片 / B 站切清晰度换直链）→ 重建会话并重播 */
    fun playSession(session: PlaybackSession) {
        SwingUtilities.invokeLater {
            refreshFromSession(session)
            controller.play(session.playUrl, session.headers, session.startMs)
        }
    }

    /** 刷新标题/清晰度/选集/换源等覆盖层展示 */
    private fun refreshFromSession(session: PlaybackSession) {
        currentSession = session
        title = "视界 Desktop - ${session.title}"
        val qualities = session.qualityOptions.map { PlayerQuality(it.label, it.qn) }
        val episodeIndex = session.currentEpisodeIndex
        val sourceIndex = session.currentSourceIndex
        // 上一集/下一集（设置开关；按当前集索引定位，越界不展示）
        val showPrevNext = prefs.getBoolean(DesktopPrefs.KEY_SHOW_PREV_NEXT, true)
        overlay.updateUi(
            PlayerOverlayConfig(
                qualities = qualities,
                currentQuality = qualities.firstOrNull { it.qn == session.currentQn },
                onQualitySelected = { quality ->
                    callbacks.onSwitchQuality(currentSession, quality.qn, controller.timeMs.value)
                },
                title = listOf(session.title, session.episodeName).filter { it.isNotBlank() }.joinToString(" "),
                onBack = Runnable { closeFrame() },
                episodes = session.episodes,
                currentEpisodeIndex = episodeIndex,
                onEpisodeSelected = { index -> callbacks.onPlayEpisode(sourceIndex, index) },
                sources = session.playSources.mapIndexed { index, source -> sourceName(source, index) },
                currentSourceIndex = sourceIndex,
                sourceSubtexts = List(session.playSources.size) { "当前源" },
                sourceUpdateTexts = session.playSources.map { source ->
                    if (source.episodes.size > 1) "更新至：第 ${source.episodes.size} 集" else "更新至：全集"
                },
                onSourceSelected = { index -> callbacks.onPlayEpisode(index, 0) },
                onPrevEpisode = if (showPrevNext && episodeIndex > 0) {
                    Runnable { callbacks.onPlayEpisode(sourceIndex, episodeIndex - 1) }
                } else {
                    null
                },
                onNextEpisode = if (showPrevNext && episodeIndex in 0 until session.episodes.lastIndex) {
                    Runnable { callbacks.onPlayEpisode(sourceIndex, episodeIndex + 1) }
                } else {
                    null
                },
                shareUrl = session.playUrl.takeIf { it.isNotBlank() },
                currentKernel = PlayerControllerFactory.fromPrefs(prefs),
                onKernelSelected = { kernel ->
                    prefs.put(PlayerControllerFactory.KEY_PLAYER_KERNEL, kernel.id)
                },
                introTarget = IntroMark.Mark(session.vodId, session.sourceUrl, session.episodeName)
                    .takeIf { session.vodId != 0 && session.sourceUrl.isNotBlank() },
                onDownloadEpisode = if (session.episodes.isNotEmpty()) {
                    { index -> callbacks.onDownloadEpisode(index) }
                } else {
                    null
                },
            ),
        )
    }

    /** 定期保存播放进度（仓库为 suspend，经应用级协程作用域异步落库；设置关闭播放记录时跳过） */
    private fun saveHistory() {
        val time = controller.timeMs.value
        if (currentSession.vodId == 0 || time <= Constants.PLAYBACK_MEMORY_MIN_MS) return
        if (!prefs.getBoolean(DesktopPrefs.KEY_PLAY_RECORD_ENABLED, true)) return
        val sourceRecordOn = AppContainer.get().apiSourceRepository.sources.value
            .firstOrNull { it.url == currentSession.sourceUrl }?.playRecord ?: true
        if (!sourceRecordOn) return
        AppContainer.get().appScope.launch {
            runCatching {
                AppContainer.get().playHistoryRepository.save(
                    PlayHistoryEntity(
                        vodId = currentSession.vodId,
                        sourceUrl = currentSession.sourceUrl,
                        title = currentSession.title,
                        vodPic = currentSession.vodPic,
                        lastEpisodeName = currentSession.episodeName,
                        lastPositionMs = time,
                        durationMs = controller.durationMs.value,
                    ),
                )
            }
        }
    }

    /**
     * 关闭窗口（用户点叉/返回键/外部关闭）：终写进度 + 停止释放 native 资源 + 通知置空会话。
     * 进度须先于释放同步终写（释放后台执行前 StateFlow 仍保留最终进度）；
     * 释放走后台线程：native 拆卸耗时秒级，同步执行会卡住关窗与整个 EDT。
     */
    fun closeFrame() {
        if (closed) return
        closed = true
        runCatching {
            saveHistory()
            historyTimer.stop()
        }
        controller.releaseAsync()
        SwingUtilities.invokeLater { dispose() }
        // 回调宿主置空会话：确保下次起播走"新建窗口"分支（否则死窗口引用会吞掉后续播放）
        callbacks.onClose()
    }

    companion object {
        /** 小窗尺寸与屏幕右下角边距 */
        private const val MINI_WIDTH = 420
        private const val MINI_HEIGHT = 240
        private const val MINI_MARGIN = 32
    }
}
