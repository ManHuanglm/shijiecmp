package com.shiping.desktop.player

import com.shiping.desktop.di.AppContainer
import com.shiping.desktop.data.prefs.DesktopPrefs
import com.shiping.desktop.ui.InPageOverlayHost
import com.shiping.desktop.ui.player.behavior.CONTROLS_AUTO_HIDE_MS
import com.shiping.desktop.ui.player.behavior.GESTURE_CANCEL_PX_DESKTOP
import com.shiping.desktop.ui.player.behavior.GESTURE_DECIDE_PX_DESKTOP
import com.shiping.desktop.ui.player.behavior.GESTURE_SEEK_MAX_MS
import com.shiping.desktop.ui.player.behavior.GESTURE_SEEK_MS_PER_PX
import com.shiping.desktop.ui.player.behavior.GestureText
import com.shiping.desktop.ui.player.behavior.MuteSession
import com.shiping.desktop.ui.player.behavior.PlayerGestureSession
import com.shiping.desktop.ui.player.VideoScaleMode
import com.shiping.shared.prefs.PrefsKeys
import com.shiping.desktop.util.AppLog
import com.shiping.desktop.util.Constants
import java.awt.AWTEvent
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.GraphicsEnvironment
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.Point
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.AWTEventListener
import java.awt.event.HierarchyBoundsAdapter
import java.awt.event.HierarchyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.Box
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JLayeredPane
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JWindow
import javax.swing.SwingUtilities
import javax.swing.Timer
import kotlin.math.abs

/** 播放器可选清晰度（与 B 站 qn 解耦的展示模型） */
data class PlayerQuality(val label: String, val qn: Int)

/** 独立播放窗口专有动作（最大化/全屏/小窗）；页内播放为 null */
interface PlayerWindowActions {
    fun toggleMaximize()
    fun toggleFullscreen()
    fun toggleMiniWindow()
    val isFullscreen: Boolean
}

/** overlay 当前会话配置（切集/切清晰度时经 updateUi 刷新，避免重建视频面） */
data class PlayerOverlayConfig(
    val qualities: List<PlayerQuality> = emptyList(),
    val currentQuality: PlayerQuality? = null,
    val onQualitySelected: (PlayerQuality) -> Unit = {},
    val onPrevEpisode: Runnable? = null,
    val onNextEpisode: Runnable? = null,
    /** 顶部栏标题（片名 + 集数） */
    val title: String = "",
    /** 返回动作（全屏中先退全屏；页内=退出播放，独立窗口=关窗），空隐藏返回 */
    val onBack: Runnable? = null,
    /** 选集列表（空隐藏按钮） */
    val episodes: List<String> = emptyList(),
    val currentEpisodeIndex: Int = -1,
    val onEpisodeSelected: (Int) -> Unit = {},
    /** 源列表（少于 2 个隐藏按钮） */
    val sources: List<String> = emptyList(),
    val currentSourceIndex: Int = -1,
    val onSourceSelected: (Int) -> Unit = {},
    /** 源列表副文案（蓝点后的源标识，随 sources 等长；空列表隐藏副行） */
    val sourceSubtexts: List<String> = emptyList(),
    /** 源列表右侧更新信息（随 sources 等长，如「更新至：第 159 集」；空列表隐藏右列） */
    val sourceUpdateTexts: List<String> = emptyList(),
    /** 分享目标链接（空时分享面板各动作提示不可用） */
    val shareUrl: String? = null,
    /** 播放内核（顶部栏切换，保存偏好后下次播放生效） */
    val currentKernel: PlayerKernel = PlayerKernel.MPV,
    val onKernelSelected: (PlayerKernel) -> Unit = {},
    /** 片头记录目标（空隐藏「记录片头」按钮） */
    val introTarget: IntroMark.Mark? = null,
    /** 下载当前集回调（空时点击提示不支持） */
    val onDownloadEpisode: ((Int) -> Unit)? = null,
    /** 下载全部集回调（空时选集面板隐藏「下载全部」按钮） */
    val onDownloadAllEpisodes: (() -> Unit)? = null,
    /** 直播频道列表回调（▦ 按钮优先走它，按钮转「频道列表」语义；空时走默认选集面板） */
    val onChannelList: Runnable? = null,
    /** 播放设置右缘滑出回调（⚙ 按钮优先走它；空时走默认内核面板） */
    val onPlayerSettings: Runnable? = null,
)

/**
 * 覆盖式播放控制层（对照设计稿：左上分辨率、中央快进快退提示、左下 当前/总时长、
 * 红色进度线、底部 上/下集 + 画面/清晰度/倍速/播放 + 窗口动作）。
 *
 * 控制层承载于独立 per-pixel 透明 JWindow（heavyweight，owned 于宿主窗口并贴齐视频区）：
 * mpv/MDK 均以原生 HWND 直接渲染视频，AWT 轻/重组件混排无法压住其画面，
 * 控制层必须是独立原生窗口。播放中 3 秒无操作自动隐藏，鼠标移动/点击唤出；
 * 空格播放暂停、←/→ 快退快进（中央提示）、Esc/F 全屏切换。
 */
class PlayerOverlay(
    video: Component?,
    private val controller: PlayerController,
    var windowActions: PlayerWindowActions? = null,
    /** 独立播放窗口（PlayerFrame）：完整布局 + 保留小窗/最大化窗口动作；页内播放为 false（紧凑/全屏两形态） */
    private val standalone: Boolean = false,
) : JLayeredPane(), InPageOverlayHost {

    private val prefs get() = AppContainer.get().prefs

    /** 当前会话配置（清晰度/上下集回调，随会话刷新） */
    @Volatile
    private var config = PlayerOverlayConfig()

    // region 视图层

    /** 视频面（垫底） */
    private val videoPane: Component = video ?: object : javax.swing.JPanel() {
        init { background = Color.BLACK; isOpaque = true }
    }

    /** 控制层容器（覆盖视频，透明背景让视频透出） */
    private val controlPane = object : JPanel(GridBagLayout()) {
        init { isOpaque = false }
        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            // 底部控制条压一层黑色渐变提升可读性（对照设计稿底部黑条）
            val h = height
            val barH = (h * 0.28f).toInt().coerceAtLeast(72)
            val g2 = g.create() as java.awt.Graphics2D
            g2.paint = java.awt.GradientPaint(
                0f, (h - barH).toFloat(), Color(0, 0, 0, 0),
                0f, h.toFloat(), Color(0, 0, 0, 190),
            )
            g2.fillRect(0, h - barH, width, barH)
            g2.dispose()
        }
    }

    private val resolutionLabel = smallLabel(" ").apply { isVisible = false }
    private val statusLabel = smallLabel(" ").apply { foreground = Color(0xE5, 0x48, 0x4D) }
    // 中央提示常驻占位（空白文本不绘制内容）：GridBag 纵向配重依赖此行，
    // 若 setVisible(false) 该行会被 GridBagLayout 忽略导致整个网格塌陷居中
    private val seekHintLabel = JLabel(" ").apply {
        font = font.deriveFont(Font.BOLD, 18f)
        foreground = Color.WHITE
        horizontalAlignment = javax.swing.SwingConstants.CENTER
    }
    private val timeLabel = smallLabel("00:00")
    private val seekBackButton = overlayIconButton("⏪", "快退")
    private val seekForwardButton = overlayIconButton("⏩", "快进")
    private val bufferingIndicator = BufferingIndicator()
    private val playButton = overlayButton("❚❚", fixedSize = true)
    private val prevButton = overlayButton("|◀", fixedSize = true)
    private val nextButton = overlayButton("▶|", fixedSize = true)
    private val danmakuButton = overlayButton("弹", fixedSize = true)
    private val speedButton = overlayButton("x1.0")
    private val qualityButton = overlayButton("清晰度").apply { isVisible = false }
    private val scaleButton = overlayButton("画面")
    private val muteButton = overlayButton("🔊", fixedSize = true).apply { toolTipText = "静音" }
    private val pinButton = overlayIconButton("📌", "窗口置顶")
    private val fullscreenButton = overlayIconButton("⤢", "全屏")
    private val miniButton = overlayIconButton("⧉", "小窗播放")
    private val maximizeButton = overlayButton("▢", fixedSize = true)
    private val backButton = overlayButton("‹").apply {
        font = font.deriveFont(Font.BOLD, 24f)
        preferredSize = Dimension(TOP_BACK_BUTTON_WIDTH_PX, TOP_BACK_BUTTON_HEIGHT_PX)
        toolTipText = "返回"
    }
    private val titleLabel = smallLabel(" ")
    private val screenshotButton = overlayIconButton("📷", "截图")
    private val kernelButton = overlayIconButton("⚡", "播放内核")
    private val subtitleButton = overlayIconButton("💬", "字幕")
    private val settingsButton = overlayIconButton("⚙", "播放设置")
    private val lockButton = overlayIconButton("🔒", "锁定")
    private val recordIntroButton = overlayIconButton("⚑", "记录片头")
    private val episodeButton = overlayIconButton("▦", "选集")
    private val sourceButton = overlayIconButton("⇄", "切换源")
    private val timerButton = overlayIconButton("⏱", "定时关闭")
    private val multiThreadButton = overlayIconButton("☰", "多线程配置")
    private val shareButton = overlayIconButton("⤴", "分享")
    private val seekBar = SeekBar()

    /** 顶栏/分辨率行/中央行后半段/底部堆栈（锁定时整体隐藏，需持有引用） */
    private val topBar = JPanel(java.awt.BorderLayout()).apply { isOpaque = false }
    private val topRow = JPanel(java.awt.BorderLayout()).apply { isOpaque = false }
    private val centerBox: javax.swing.Box = Box.createHorizontalBox()
    /** 底部堆栈（时长/进度线/控制排）：GridBag 逐行水平撑满整行宽 */
    private val bottomStack = JPanel().apply {
        layout = java.awt.GridBagLayout()
        isOpaque = false
    }

    /** 控制层宿主窗口（独立原生窗口，规避原生视频面覆盖轻量组件） */
    private var controlsWindow: JWindow? = null

    /** 缓冲转圈宿主窗口（独立原生窗口：控制层隐藏时转圈仍显示，且不被 HWND 视频面覆盖） */
    private var bufferingWindow: JWindow? = null

    /** 底部面板窗口（选集/源列表/定时/多线程/分享共用，同一时刻最多一个） */
    private var sheetWindow: JWindow? = null

    /** 右缘滑出面板窗口（直播频道列表/播放设置共用，同一时刻最多一个） */
    private var sidePanelWindow: JWindow? = null

    /** 右缘滑出面板的全局外点关闭监听（点击面板外任意组件即关闭） */
    private var sidePanelOutsideListener: AWTEventListener? = null

    /** 页内默认窗口动作：主窗口边界切换近似全屏；公开供页内自定义动作委托小窗等实现 */
    val defaultWindowActions = object : PlayerWindowActions {

        private val window: Window? get() = SwingUtilities.getWindowAncestor(this@PlayerOverlay)

        override fun toggleMaximize() {
            val w = window ?: return
            val saved = savedWindowBounds
            if (saved == null) {
                savedWindowBounds = w.bounds
                w.setBounds(GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds)
            } else {
                savedWindowBounds = null
                w.bounds = saved
            }
        }

        override fun toggleFullscreen() = toggleMaximize()

        override fun toggleMiniWindow() {
            val w = window ?: return
            val saved = savedWindowBounds
            if (saved == null) {
                savedWindowBounds = w.bounds
                w.isAlwaysOnTop = true
                val screen = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
                w.setBounds(
                    screen.x + screen.width - MINI_WIDTH - MINI_MARGIN,
                    screen.y + screen.height - MINI_HEIGHT - MINI_MARGIN,
                    MINI_WIDTH,
                    MINI_HEIGHT,
                )
            } else {
            savedWindowBounds = null
            // 小窗还原：若用户开启过置顶则保持，否则取消（与 📌 按钮状态一致）
            w.isAlwaysOnTop = pinnedByUser
            w.bounds = saved
        }
    }

    override val isFullscreen: Boolean get() = savedWindowBounds != null
}

    /** 页内默认动作保存的主窗口边界（最大化/小窗还原用） */
    private var savedWindowBounds: Rectangle? = null

    /** 生效的窗口动作（独立窗口传入自身实现，页内用默认实现） */
    private val actions: PlayerWindowActions get() = windowActions ?: defaultWindowActions

    // endregion

    /** 控制层可见性（自动隐藏/唤出） */
    @Volatile
    private var controlsVisible = true

    /** 详情页被其他页面覆盖（后台）时置位：隐藏控制层/面板，防止悬浮窗盖住前台页面 */
    @Volatile
    private var isBackground = false

    /**
     * 前后台切换：后台时隐藏整体（含视频面 HWND 与控制悬浮窗/底部面板），
     * 回到前台由鼠标移动重新唤出控制层。
     * heavyweight 视频面永远浮在 Compose 内容之上，详情页被其他 Tab 覆盖时必须整体隐藏让位；
     * setVisible(false) 只隐藏原生窗口不销毁 HWND，mpv 播放不受影响。
     * MDK 绑定 HWND 后 AWT setVisible 失效，另经内核 setSurfaceVisible 走原生 ShowWindow。
     */
    override fun setBackground(background: Boolean) {
        isBackground = background
        if (background) {
            hideTimer.stop()
            hideSheet()
            hideSidePanel()
            controlsWindow?.isVisible = false
            bufferingWindow?.let { window ->
                window.isAlwaysOnTop = false
                window.isVisible = false
            }
            controlsVisible = false
            isVisible = false
            controller.setSurfaceVisible(false)
        } else {
            isVisible = true
            controller.setSurfaceVisible(true)
        }
    }

    /** 控制层自动隐藏定时器（播放中 3s 无操作隐藏） */
    private val hideTimer = Timer(CONTROLS_AUTO_HIDE_MS.toInt()) { hideControls() }

    /** 控制层状态轮询（播放态/进度/分辨率/错误，250ms）；可见时顺带贴齐视频区（Compose 重排等事件漏通知场景防错位点空） */
    private val syncTimer = Timer(UI_SYNC_INTERVAL_MS) {
        syncFromController()
        wakeControlsOnPointerMove()
        controlsWindow?.takeIf { it.isVisible }?.let(::syncControlWindow)
    }

    /** seek 中央提示自动消失定时器 */
    private val hintTimer = Timer(SEEK_HINT_DURATION_MS) {
        seekHintLabel.text = " "
    }

    /** 定时关闭一次性定时器（到时暂停播放；重复「添加定时」会替换） */
    private var sleepTimer: Timer? = null

    /** 快进快退步长缓存（变化时才刷新按钮，避免每 250ms 重排） */
    private var lastSeekStep = Int.MIN_VALUE

    /** 锁定态：隐藏全部控制只留锁定按钮（防误触），控制层不自动隐藏 */
    @Volatile
    private var isLocked = false

    /** 拖动进度起始位置（松手回起点取消 seek 用） */
    private var dragStartMs = 0L

    /** 手势最近拖动点（视频面坐标） */
    private var lastDragX = 0
    private var lastDragY = 0

    /** 拖动累计位移（像素，仅用于长按倍速中止判定；锁向与目标计算在共享会话内） */
    private var gestureDx = 0
    private var gestureDy = 0

    /** 视频面拖动手势会话（共享行为，每次按下重建以取最新「松手取消」设置） */
    private var gestureSession: PlayerGestureSession? = null

    /** 会话级播放音量（0-100；内核无音量回读，本地记录） */
    private var sessionVolume = 100

    /** 会话级静音（共享行为）：静音=音量 0，解除恢复；调高音量自动解除 */
    private val muteSession = MuteSession(::setSessionVolume).also { session ->
        session.onMuteChanged = { muted ->
            muteButton.text = if (muted) "🔇" else "🔊"
            muteButton.toolTipText = if (muted) "取消静音" else "静音"
        }
    }

    /** 当前倍速（与倍速按钮文案同步；长按倍速松手恢复用） */
    private var currentRate = 1f

    /** 画面调节模式（KEY_VIDEO_SCALE 持久化，跨会话生效） */
    private var videoScaleMode = VideoScaleMode.fromId(prefs.getString(PrefsKeys.KEY_VIDEO_SCALE, VideoScaleMode.FIT.id))

    /** 长按倍速触发定时器（按住不动超过阈值进入倍速，拖动/松手取消） */
    private val longPressTimer = Timer(LONG_PRESS_DELAY_MS) { enterSpeedBoost() }

    /** 长按倍速生效中 */
    private var isSpeedBoost = false

    init {
        layout = null
        // 视频面垫底；控制层与缓冲转圈各自放独立 JWindow（见 controlsWindow/bufferingWindow）：
        // mpv/MDK 以 HWND 渲染视频，根层轻量组件会被画面覆盖，必须用独立原生窗口承载
        add(videoPane, DEFAULT_LAYER)

        buildControlLayout()
        bindVideoGestures()
        bindKeyboard()
        bindOverlayGestures()
        bindFollowListeners()

        prevButton.isVisible = false
        nextButton.isVisible = false
        updateSpeedLabel(1f)
        scaleButton.text = videoScaleMode.label
        applyLayoutMode()

        hideTimer.isRepeats = false
        hintTimer.isRepeats = false
        longPressTimer.isRepeats = false
        syncTimer.isRepeats = true

        // 事件绑定
        playButton.toolTipText = "播放/暂停"
        prevButton.toolTipText = "上一集"
        nextButton.toolTipText = "下一集"
        danmakuButton.toolTipText = "弹幕（暂未支持）"
        speedButton.toolTipText = "倍速"
        scaleButton.toolTipText = "画面调节"
        maximizeButton.toolTipText = "最大化"
        playButton.addActionListener { controller.togglePlayPause() }
        prevButton.addActionListener { config.onPrevEpisode?.run() }
        nextButton.addActionListener { config.onNextEpisode?.run() }
        seekBackButton.addActionListener { seekBy(-1) }
        seekForwardButton.addActionListener { seekBy(1) }
        speedButton.addActionListener { showSpeedPopup() }
        scaleButton.addActionListener { showScalePopup() }
        qualityButton.addActionListener { showQualityPopup() }
        muteButton.addActionListener { toggleMute() }
        danmakuButton.addActionListener { showSeekHint("桌面版暂未支持弹幕") }
        fullscreenButton.addActionListener { actions.toggleFullscreen() }
        // 📌 手动置顶开关：不自动置顶，由用户决定播放器是否浮于其它程序之上
        pinButton.addActionListener { togglePinnedByUser() }
        miniButton.addActionListener { actions.toggleMiniWindow() }
        maximizeButton.addActionListener { actions.toggleMaximize() }
        backButton.addActionListener {
            if (actions.isFullscreen) actions.toggleFullscreen() else config.onBack?.run()
        }
        screenshotButton.addActionListener {
            showSeekHint(if (controller.snapshot() != null) "截图已保存" else "当前内核不支持截图")
        }
        kernelButton.addActionListener { showKernelSheet() }
        subtitleButton.addActionListener { showSeekHint("桌面版暂未支持字幕") }
        settingsButton.addActionListener { config.onPlayerSettings?.run() ?: showKernelSheet() }
        lockButton.addActionListener {
            isLocked = !isLocked
            applyLockState()
        }
        recordIntroButton.addActionListener {
            val target = config.introTarget
            if (target == null) {
                showSeekHint("当前播放不支持记录片头")
            } else {
                val endMs = controller.timeMs.value
                IntroMark.save(prefs, target, endMs)
                showSeekHint("已记录片头 ${formatTime(endMs)}，下次播放自动跳过")
            }
        }
        episodeButton.addActionListener {
            config.onChannelList?.run() ?: showEpisodeSheet()
        }
        sourceButton.addActionListener {
            showSourceSheet()
        }
        timerButton.addActionListener { showTimerSheet() }
        multiThreadButton.addActionListener { showMultiThreadSheet() }
        shareButton.addActionListener { showShareSheet() }
    }

    // region 布局

    /** 控制层布局：顶栏(返回/片名 + 锁定/功能排) + 分辨率行 + 中央(后退10s/提示/前进10s) +
     *  底部三排(时长/进度/控制排)；缓冲转圈独立于本布局在视频面根层居中 */
    private fun buildControlLayout() {
        val gbc = GridBagConstraints().apply {
            weightx = 1.0
            insets = Insets(6, 10, 4, 10)
        }

        // 顶栏：左 返回+片名集数；右 锁定/字幕/截图/定时/多线程/分享/内核/设置（独立窗口附加 小窗/最大化）
        gbc.gridy = 0
        gbc.fill = GridBagConstraints.HORIZONTAL
        gbc.anchor = GridBagConstraints.NORTH
        val topWest = Box.createHorizontalBox().apply {
            add(backButton)
            add(Box.createHorizontalStrut(TITLE_GAP_PX))
            add(titleLabel)
            add(Box.createHorizontalGlue())
        }
        val topEast = Box.createHorizontalBox().apply {
            add(lockButton)
            add(Box.createHorizontalStrut(CONTROL_GAP_PX))
            add(subtitleButton)
            add(Box.createHorizontalStrut(CONTROL_GAP_PX))
            add(screenshotButton)
            add(Box.createHorizontalStrut(CONTROL_GAP_PX))
            add(timerButton)
            add(Box.createHorizontalStrut(CONTROL_GAP_PX))
            add(multiThreadButton)
            add(Box.createHorizontalStrut(CONTROL_GAP_PX))
            add(shareButton)
            add(Box.createHorizontalStrut(CONTROL_GAP_PX))
            add(kernelButton)
            add(Box.createHorizontalStrut(CONTROL_GAP_PX))
            add(settingsButton)
            if (standalone) {
                add(Box.createHorizontalStrut(CONTROL_GAP_PX))
                add(miniButton)
                add(Box.createHorizontalStrut(CONTROL_GAP_PX))
                add(maximizeButton)
            }
        }
        topBar.add(topWest, java.awt.BorderLayout.WEST)
        topBar.add(topEast, java.awt.BorderLayout.EAST)
        controlPane.add(topBar, gbc)

        // 分辨率行：左分辨率、右状态（错误/缓冲文案）
        gbc.gridy = 1
        topRow.add(resolutionLabel, java.awt.BorderLayout.WEST)
        topRow.add(statusLabel, java.awt.BorderLayout.EAST)
        controlPane.add(topRow, gbc)

        // 中央行：后退10s / 缓冲动画·提示 / 前进10s 贴中央均分排列（缓冲态整层隐藏只留根层转圈）
        gbc.gridy = 2
        gbc.weighty = 1.0
        gbc.fill = GridBagConstraints.BOTH
        // 提示与缓冲动画叠放同一格（GridBag 同格居中堆叠）
        val centerStack = JPanel(java.awt.GridBagLayout()).apply { isOpaque = false }
        val cc = GridBagConstraints()
        cc.gridx = 0
        cc.gridy = 0
        centerStack.add(seekHintLabel, cc)
        centerBox.apply {
            add(Box.createHorizontalGlue())
            add(seekBackButton)
            add(Box.createHorizontalStrut(CENTER_GAP_PX))
            add(centerStack)
            add(Box.createHorizontalStrut(CENTER_GAP_PX))
            add(seekForwardButton)
            add(Box.createHorizontalGlue())
        }
        controlPane.add(centerBox, gbc)
        gbc.weighty = 0.0
        gbc.fill = GridBagConstraints.HORIZONTAL

        // 底部三排：时长 → 进度线 → 控制排（每行水平撑满）
        gbc.gridy = 3
        val timeRow = JPanel(java.awt.BorderLayout()).apply {
            isOpaque = false
            add(timeLabel, java.awt.BorderLayout.WEST)
        }
        val controlRow = JPanel().apply {
            layout = java.awt.BorderLayout(8, 0)
            isOpaque = false
            val left = Box.createHorizontalBox().apply {
                add(prevButton)
                add(nextButton)
                add(Box.createHorizontalStrut(CONTROL_GAP_PX))
                add(recordIntroButton)
            }
            val right = Box.createHorizontalBox().apply {
                add(episodeButton)
                add(Box.createHorizontalStrut(CONTROL_GAP_PX))
                add(danmakuButton)
                add(Box.createHorizontalStrut(CONTROL_GAP_PX))
                add(sourceButton)
                add(Box.createHorizontalStrut(CONTROL_GAP_PX))
                add(qualityButton)
                add(Box.createHorizontalStrut(CONTROL_GAP_PX))
                add(scaleButton)
                add(Box.createHorizontalStrut(CONTROL_GAP_PX))
                add(speedButton)
                add(Box.createHorizontalStrut(CONTROL_GAP_PX))
                add(muteButton)
                add(Box.createHorizontalStrut(CONTROL_GAP_PX))
                add(playButton)
                add(Box.createHorizontalStrut(CONTROL_GAP_PX))
                add(pinButton)
                add(Box.createHorizontalStrut(CONTROL_GAP_PX))
                add(fullscreenButton)
            }
            add(left, java.awt.BorderLayout.WEST)
            add(right, java.awt.BorderLayout.EAST)
        }
        bottomStack.apply {
            val bc = java.awt.GridBagConstraints().apply {
                gridx = 0
                weightx = 1.0
                fill = java.awt.GridBagConstraints.HORIZONTAL
            }
            bc.gridy = 0
            add(timeRow, bc)
            bc.gridy = 1
            add(seekBar, bc)
            bc.gridy = 2
            add(controlRow, bc)
        }
        controlPane.add(bottomStack, gbc)
    }

    // endregion

    // region 手势

    /**
     * 视频面手势：移动/单击唤出控制层，双击全屏；
     * 按住拖动横向调进度（含松手取消）/竖向调音量；按住不动进入长按倍速，松手恢复。
     * 注：mpv/MDK 全屏接管 HWND 后视频面鼠标事件可能丢失（见 wakeControlsOnPointerMove），
     * 手势在非全屏下最可靠。
     */
    private fun bindVideoGestures() {
        val l = object : MouseAdapter() {
            override fun mouseMoved(e: MouseEvent) = showControls()

            override fun mousePressed(e: MouseEvent) {
                if (e.button != MouseEvent.BUTTON1) return
                lastDragX = e.x
                lastDragY = e.y
                gestureDx = 0
                gestureDy = 0
                // 每次按下重建会话：锁向/取消判定在共享 PlayerGestureSession，设置取当下值
                gestureSession = PlayerGestureSession(
                    PlayerGestureSession.Options(
                        decidePx = GESTURE_DECIDE_PX_DESKTOP,
                        cancelPx = GESTURE_CANCEL_PX_DESKTOP,
                        seekMsPerPx = GESTURE_SEEK_MS_PER_PX,
                        seekMaxMs = GESTURE_SEEK_MAX_MS,
                        enableBrightness = false,
                        enableSeekCancel = isSeekCancelEnabled,
                    ),
                ).also { session ->
                    session.start(
                        startX = e.x.toFloat(),
                        width = width.toFloat(),
                        height = height.toFloat(),
                        seekBaseMs = controller.timeMs.value,
                        volumeBase = sessionVolume,
                        brightnessBase = 0f,
                    )
                }
                if (!isLocked && prefs.getFloat(DesktopPrefs.KEY_LONG_PRESS_SPEED, 0f) > 0f) {
                    longPressTimer.start()
                }
            }

            override fun mouseDragged(e: MouseEvent) {
                showControls()
                if (isLocked) return
                val dx = e.x - lastDragX
                val dy = e.y - lastDragY
                lastDragX = e.x
                lastDragY = e.y
                gestureDx += dx
                gestureDy += dy
                if (isSpeedBoost) return
                if (abs(gestureDx) > GESTURE_DECIDE_PX_DESKTOP || abs(gestureDy) > GESTURE_DECIDE_PX_DESKTOP) {
                    longPressTimer.stop()
                }
                when (val feedback = gestureSession?.onDelta(dx.toFloat(), dy.toFloat(), controller.durationMs.value)) {
                    is PlayerGestureSession.Feedback.Seek -> showSeekHint(feedback.text)
                    is PlayerGestureSession.Feedback.Volume -> {
                        sessionVolume = muteSession.onVolumeAdjusted(feedback.percent)
                        showSeekHint(GestureText.volume(sessionVolume))
                    }
                    else -> Unit
                }
            }

            override fun mouseReleased(e: MouseEvent) {
                if (e.button != MouseEvent.BUTTON1) return
                longPressTimer.stop()
                if (isSpeedBoost) {
                    exitSpeedBoost()
                } else {
                    when (val commit = gestureSession?.onEnd()) {
                        is PlayerGestureSession.Commit.Seek -> controller.seekTo(commit.targetMs)
                        PlayerGestureSession.Commit.CancelSeek -> showSeekHint(GestureText.SEEK_CANCELLED)
                        null -> Unit
                    }
                }
                gestureSession = null
            }

            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2 && !isLocked) {
                    actions.toggleFullscreen()
                } else {
                    showControls()
                }
            }
        }
        videoPane.addMouseListener(l)
        videoPane.addMouseMotionListener(l)
    }

    /** 「显示松手取消调整进度」设置（进度手势与进度条共用） */
    private val isSeekCancelEnabled: Boolean
        get() = prefs.getBoolean(DesktopPrefs.KEY_SHOW_SEEK_CANCEL, true)

    /** 进入长按倍速（0 档/锁定不生效；需长按期间未拖动） */
    private fun enterSpeedBoost() {
        val rate = prefs.getFloat(DesktopPrefs.KEY_LONG_PRESS_SPEED, 0f)
        if (rate <= 0f || isLocked || isSpeedBoost) return
        isSpeedBoost = true
        controller.setRate(rate)
        showSeekHint(GestureText.speedBoost(rate))
    }

    /** 退出长按倍速：恢复倍速按钮当前档位 */
    private fun exitSpeedBoost() {
        if (!isSpeedBoost) return
        isSpeedBoost = false
        controller.setRate(currentRate)
        showSeekHint(GestureText.speed(currentRate))
    }

    /** 控制层手势：悬停保持可见；移出后恢复自动隐藏 */
    private fun bindOverlayGestures() {
        controlPane.addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) {
                showControls()
                hideTimer.stop()
            }

            override fun mouseExited(e: MouseEvent) {
                if (controller.isPlaying.value) hideTimer.restart()
            }
        })
        controlPane.addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseMoved(e: MouseEvent) = showControls()
        })
    }

    /** 键盘：空格播放暂停、←/→ 快退快进、Esc/F 全屏（WHEN_IN_FOCUSED_WINDOW 全窗口生效） */
    private fun bindKeyboard() {
        fun bind(key: Int, name: String, action: () -> Unit) {
            val input = getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
            input.put(javax.swing.KeyStroke.getKeyStroke(key, 0), name)
            actionMap.put(name, object : javax.swing.AbstractAction() {
                override fun actionPerformed(e: java.awt.event.ActionEvent) = action()
            })
        }
        bind(java.awt.event.KeyEvent.VK_SPACE, "togglePlay") { if (!isLocked) controller.togglePlayPause() }
        bind(java.awt.event.KeyEvent.VK_ESCAPE, "exitFullscreen") {
            if (!isLocked && actions.isFullscreen) actions.toggleFullscreen()
        }
        bind(java.awt.event.KeyEvent.VK_F, "toggleFullscreen") { if (!isLocked) actions.toggleFullscreen() }
        bind(java.awt.event.KeyEvent.VK_M, "toggleMute") { if (!isLocked) toggleMute() }
        getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).apply {
            put(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_LEFT, 0), "seekBack")
            put(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_RIGHT, 0), "seekForward")
        }
        actionMap.put("seekBack", object : javax.swing.AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent) = seekBy(-1)
        })
        actionMap.put("seekForward", object : javax.swing.AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent) = seekBy(1)
        })
    }

    /** 快进/快退（设置页步长，0s/锁定态不响应），并弹出中央提示 */
    private fun seekBy(direction: Int) {
        if (isLocked) return
        val step = prefs.getInt(DesktopPrefs.KEY_SEEK_STEP, 10)
        if (step <= 0) return
        val target = controller.timeMs.value + direction * step * 1000L
        controller.seekTo(target)
        showSeekHint(if (direction < 0) "◀◀ ${step}s" else "${step}s ▶▶")
    }

    // endregion

    // region 会话与状态刷新

    /** 刷新会话相关 UI（上下集/标题/选集/源/片头；切集换源时调用，不重建视频面） */
    fun updateUi(config: PlayerOverlayConfig) {
        this.config = config
        prevButton.isVisible = config.onPrevEpisode != null
        nextButton.isVisible = config.onNextEpisode != null
        titleLabel.text = config.title.let { raw ->
            if (raw.length > TITLE_MAX_CHARS) raw.take(TITLE_MAX_CHARS) + "…" else raw
        }
        episodeButton.toolTipText = if (config.onChannelList != null) "频道列表" else "选集"
        // 清晰度钮：文案随当前清晰度（可见性由 applyLayoutMode 按形态与可选清晰度决定）
        qualityButton.text = config.currentQuality?.label ?: "清晰度"
        applyLayoutMode()
    }

    /** 倍速按钮文案（同步 currentRate，长按倍速松手恢复用） */
    fun updateSpeedLabel(rate: Float) {
        currentRate = rate
        speedButton.text = "x%.1f".format(rate)
    }

    /** 静音切换（共享 MuteSession）：静音=音量 0（记住原音量），解除恢复 */
    private fun toggleMute() {
        muteSession.toggle(sessionVolume)
        showSeekHint(if (muteSession.isMuted) GestureText.MUTED else GestureText.UNMUTED)
    }

    /** 应用会话音量并同步内核（0-100；静音解除由 MuteSession 统一处理） */
    private fun setSessionVolume(percent: Int) {
        sessionVolume = percent.coerceIn(0, 100)
        controller.setVolume(sessionVolume)
    }

    /** 页面操作排全屏入口（与覆盖层全屏按钮/双击手势同动作；锁定中不响应） */
    fun toggleFullscreen() {
        if (!isLocked) actions.toggleFullscreen()
    }

    /** 全屏按钮图标（独立窗口切换全屏后同步） */
    fun updateFullscreenState(isFullscreen: Boolean) {
        lastFullscreenState = isFullscreen
        fullscreenButton.text = if (isFullscreen) "⤡" else "⤢"
        fullscreenButton.toolTipText = if (isFullscreen) "退出全屏" else "全屏"
    }

    /** 全屏按钮文案状态缓存（轮询只在变化时刷新，避免每 250ms 重排） */
    private var lastFullscreenState: Boolean? = null

    /** 250ms 轮询控制器状态 */
    private fun syncFromController() {
        runCatching {
            val playing = controller.isPlaying.value
            playButton.text = if (playing) "❚❚" else "▶"
            timeLabel.text = buildTimeString()
            seekBar.refresh(playing)
            val w = controller.videoWidth.value
            val h = controller.videoHeight.value
            resolutionLabel.isVisible = w > 0 && h > 0
            if (w > 0 && h > 0) resolutionLabel.text = "分辨率:$w x $h"
            statusLabel.text = when {
                !controller.initError.value.isNullOrBlank() -> controller.initError.value
                controller.errorText.value != null -> controller.errorText.value
                controller.isBuffering.value -> "缓冲中…"
                else -> " "
            }
            // 缓冲对齐手机端：转圈独立窗口承载（HWND 视频面之上、控制层窗口之下），缓冲中仍可呼出控制层
            val buffering = controller.isBuffering.value
            if (buffering) {
                ensureBufferingWindow()?.let { window ->
                    syncBufferingWindow(window)
                    // 仅全屏/小窗形态置顶（防被无边框全屏主窗口覆盖）；不抢焦点
                    window.isAlwaysOnTop = pinnedMode
                    if (!window.isVisible) window.isVisible = true
                }
                if (!bufferingIndicator.animator.isRunning) bufferingIndicator.animator.start()
                // 缓冲中不再强制隐藏控制层：呼出状态由用户操作与自动隐藏计时决定，
                // 控制层窗口置顶于转圈之上（缓冲转圈在控制组件下层）
                if (controlsVisible) {
                    ensureControlsWindow()?.let { window ->
                        syncControlWindow(window)
                        window.isAlwaysOnTop = pinnedMode
                        if (!window.isVisible) window.isVisible = true
                        window.toFront()
                    }
                }
            } else {
                bufferingWindow?.let { window ->
                    window.isAlwaysOnTop = false
                    window.isVisible = false
                }
                if (bufferingIndicator.animator.isRunning) bufferingIndicator.animator.stop()
            }
            // 全屏按钮文案随窗口动作状态刷新（页内默认动作切换后同步）
            val fullscreenNow = actions.isFullscreen
            if (fullscreenNow != lastFullscreenState) {
                lastFullscreenState = fullscreenNow
                updateFullscreenState(fullscreenNow)
                applyLayoutMode()
            }
            // 快进快退按钮（设置步长 0 时隐藏，图标固定、tooltip 随步长）
            val step = prefs.getInt(DesktopPrefs.KEY_SEEK_STEP, 10)
            if (step != lastSeekStep) {
                lastSeekStep = step
                seekBackButton.isVisible = step > 0
                seekForwardButton.isVisible = step > 0
                seekBackButton.toolTipText = "快退${step}秒"
                seekForwardButton.toolTipText = "快进${step}秒"
            }
            // 控制层窗口兜底同步：初次显示/宿主拖动监听遗漏时对齐位置；置顶随全屏/小窗形态动态刷新
            if (controlsVisible) {
                ensureControlsWindow()?.let { window ->
                    syncControlWindow(window)
                    window.isAlwaysOnTop = pinnedMode
                    if (!window.isVisible) window.isVisible = true
                }
            }
            // 缓冲转圈窗口跟随视频区（全屏切换/拖动窗口时同步）
            if (controller.isBuffering.value) {
                ensureBufferingWindow()?.let { window ->
                    syncBufferingWindow(window)
                    window.isAlwaysOnTop = pinnedMode
                    if (!window.isVisible) window.isVisible = true
                }
            }
        }
    }

    /** 时间文案：当前 / 总时长（设置关闭总时长或直播无时长时仅当前） */
    private fun buildTimeString(): String {
        val current = formatTime(seekBar.dragMs.takeIf { it >= 0 } ?: controller.timeMs.value)
        val duration = controller.durationMs.value
        val showTotal = prefs.getBoolean(DesktopPrefs.KEY_SHOW_TOTAL_TIME, true)
        return if (showTotal && duration > 0L) "$current / ${formatTime(duration)}" else current
    }

    // endregion

    // region 控制层显隐与提示

    /** 唤出控制层并重置自动隐藏计时（缓冲中同样可呼出：转圈在控制层窗口之下） */
    private fun showControls() {
        if (isBackground) return
        if (!controlsVisible) {
            controlsVisible = true
            ensureControlsWindow()?.let { window ->
                syncControlWindow(window)
                // 仅全屏/小窗形态置顶；不抢焦点；toFront 保证盖在缓冲转圈窗口之上
                window.isAlwaysOnTop = pinnedMode
                window.isVisible = true
                window.toFront()
                controlPane.repaint()
            }
        } else {
            // 已可见时同样兜底提升（缓冲转圈窗口可能在其后显示过）
            controlsWindow?.toFront()
        }
        if (controller.isPlaying.value) {
            hideTimer.restart()
        }
    }

    /** 隐藏控制层（暂停/拖进度/锁定中不隐藏；缓冲中允许隐藏，只留转圈） */
    private fun hideControls() {
        val pausedNotBuffering = !controller.isPlaying.value && !controller.isBuffering.value
        if (isLocked || pausedNotBuffering || seekBar.isDragging || isPopupVisible()) return
        controlsVisible = false
        controlsWindow?.let { window ->
            window.isAlwaysOnTop = false
            window.isVisible = false
        }
    }

    /** 上次轮询采样的全局鼠标位置（null = 未采样/不比较） */
    private var lastPolledPointer: Point? = null

    /**
     * 鼠标移动兜底唤出控制层：隐藏状态下轮询全局鼠标位置，位置变化且命中视频区即唤出。
     * 全屏切换会重建视频面 HWND peer，内核接管渲染后 Canvas 的鼠标事件可能失效
     * （全屏下尤其明显），事件唤出链路不可靠，须以位置轮询兜底；
     * 鼠标停在原地不动则位置不变，不误唤出（保持无操作 3 秒自动隐藏）。
     */
    private fun wakeControlsOnPointerMove() {
        if (isBackground || controlsVisible || isLocked) {
            lastPolledPointer = null
            return
        }
        val pointer = runCatching { java.awt.MouseInfo.getPointerInfo().location }.getOrNull() ?: return
        val last = lastPolledPointer
        lastPolledPointer = pointer
        if (last != null && last == pointer) return
        val loc = runCatching { locationOnScreen }.getOrNull() ?: return
        val insideVideo = pointer.x in loc.x until loc.x + width && pointer.y in loc.y until loc.y + height
        if (insideVideo) showControls()
    }

    /** 是否有弹出菜单打开（打开期间不隐藏） */
    private fun isPopupVisible(): Boolean =
        javax.swing.MenuSelectionManager.defaultManager().selectedPath.isNotEmpty()

    /** 锁定/解锁：锁定时只留锁定按钮（防误触），控制层保持常显 */
    private fun applyLockState() {
        lockButton.text = if (isLocked) "🔓" else "🔒"
        lockButton.toolTipText = if (isLocked) "解锁" else "锁定"
        applyLayoutMode()
        showControls()
        controlPane.revalidate()
        controlPane.repaint()
    }

    /**
     * 布局模式（对照手机端两形态）：页内非全屏用紧凑布局（顶栏仅返回+分辨率，
     * 底部仅 上下集/弹/倍速/播放/全屏），全屏或独立播放窗口用完整布局
     * （顶栏功能排含锁定+底部片头/选集/源/清晰度）。锁定时仅保留顶栏锁定按钮，其余全部隐藏。
     */
    private fun applyLayoutMode() {
        val full = standalone || actions.isFullscreen
        topBar.isVisible = true
        backButton.isVisible = !isLocked
        titleLabel.isVisible = full && !isLocked
        topRow.isVisible = !isLocked
        centerBox.isVisible = !isLocked
        bottomStack.isVisible = !isLocked
        lockButton.isVisible = full
        subtitleButton.isVisible = full && !isLocked
        screenshotButton.isVisible = full && !isLocked
        timerButton.isVisible = full && !isLocked
        multiThreadButton.isVisible = full && !isLocked
        shareButton.isVisible = full && !isLocked
        kernelButton.isVisible = full && !isLocked
        settingsButton.isVisible = full && !isLocked
        if (standalone) {
            miniButton.isVisible = !isLocked
            maximizeButton.isVisible = !isLocked
        }
        recordIntroButton.isVisible = full && !isLocked && config.introTarget != null
        episodeButton.isVisible = full && !isLocked &&
            (config.episodes.isNotEmpty() || config.onChannelList != null)
        sourceButton.isVisible = full && !isLocked && config.sources.size > 1
        qualityButton.isVisible = full && !isLocked && config.qualities.isNotEmpty()
        scaleButton.isVisible = !isLocked
    }

    /** 中央 seek 提示（1s 后自动消失） */
    private fun showSeekHint(text: String) {
        seekHintLabel.text = text
        hintTimer.restart()
    }

    // endregion

    // region 控制排

    /** 倍速弹出菜单 */
    private fun showSpeedPopup() {
        val menu = JPopupMenu()
        Constants.PLAYBACK_SPEEDS.forEach { rate ->
            menu.add(JMenuItem(formatSpeed(rate)).apply {
                addActionListener {
                    controller.setRate(rate)
                    updateSpeedLabel(rate)
                }
            })
        }
        menu.show(speedButton, 0, -menu.preferredSize.height - 4)
    }

    /** 画面调节弹出菜单：6 模式选择，当前项高亮，选中即持久化并应用到内核 */
    private fun showScalePopup() {
        val menu = JPopupMenu()
        VideoScaleMode.entries.forEach { mode ->
            menu.add(JMenuItem(mode.label).apply {
                if (mode == videoScaleMode) foreground = Color(0xE5, 0x48, 0x4D)
                addActionListener { applyVideoScale(mode) }
            })
        }
        menu.show(scaleButton, 0, -menu.preferredSize.height - 4)
    }

    /** 应用画面调节模式：持久化（KEY_VIDEO_SCALE）+ 内核即时生效 + 按钮文案同步 */
    private fun applyVideoScale(mode: VideoScaleMode) {
        videoScaleMode = mode
        controller.setVideoScale(mode.id)
        scaleButton.text = mode.label
        showSeekHint("画面调节：${mode.label}")
    }

    /** 清晰度弹出菜单（B 站协议源 qualities）：当前清晰度高亮，选中回调 onQualitySelected */
    private fun showQualityPopup() {
        if (config.qualities.isEmpty()) return
        val menu = JPopupMenu()
        config.qualities.forEach { quality ->
            menu.add(JMenuItem(quality.label).apply {
                if (quality == config.currentQuality) foreground = Color(0xE5, 0x48, 0x4D)
                addActionListener { config.onQualitySelected(quality) }
            })
        }
        menu.show(qualityButton, 0, -menu.preferredSize.height - 4)
    }

    /** 播放设置：底部弹出完整设置面板（与设置页/直播右滑同一套内容，写同一批偏好键） */
    private fun showKernelSheet() {
        val host = SwingUtilities.getWindowAncestor(this) ?: return
        val width = minOf(420, host.width - 24)
        val height = minOf(640, host.height - 80)
        val content = object : JPanel(BorderLayout(0, 8)) {
            override fun paintComponent(g: Graphics) {
                super.paintComponent(g)
                val g2 = g.create() as java.awt.Graphics2D
                g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = Color.WHITE
                g2.fillRoundRect(0, 0, width, height, 18, 18)
                g2.dispose()
            }
        }.apply {
            isOpaque = false
            border = javax.swing.border.EmptyBorder(14, 16, 14, 16)
            val header = sheetHeader("播放设置")
            header.add(sheetCloseButton { hideSheet() }, BorderLayout.EAST)
            add(header, BorderLayout.NORTH)
            add(com.shiping.desktop.ui.PlayerSettingsPanel(paintBackground = false), BorderLayout.CENTER)
        }
        showSheet(content, width, height)
    }

    /**
     * 选集：底部弹出白色圆角面板（对照 Govin 移动端）——源 Tab + 操作排
     * （选中/列数/排序/下载/关闭）+ 集数网格，当前集红底白字。
     */
    fun showEpisodeSheet() {
        if (config.episodes.isEmpty()) return
        val host = SwingUtilities.getWindowAncestor(this) ?: return
        // 宽度取宿主窗口（屏幕）一半，小窗兜底
        val width = (host.width / 2).coerceAtLeast(380)
        val rows = (config.episodes.size + EPISODE_SHEET_COLUMNS - 1) / EPISODE_SHEET_COLUMNS
        val contentHeight = rows * EPISODE_ROW_HEIGHT_PX + EPISODE_SHEET_HEADER_HEIGHT_PX
        showSheet(EpisodeSheetPanel { hideSheet() }, width, contentHeight)
    }

    /** 源列表：底部弹出白色圆角面板（对照设计稿）——标题行 + 源行（名称/蓝点副行/更新至），当前源高亮 */
    fun showSourceSheet() {
        if (config.sources.isEmpty()) return
        val host = SwingUtilities.getWindowAncestor(this) ?: return
        val width = (host.width / 2).coerceAtLeast(380)
        val contentHeight = config.sources.size * SOURCE_ROW_HEIGHT_PX + SOURCE_SHEET_HEADER_HEIGHT_PX
        showSheet(SourceSheetPanel { hideSheet() }, width, contentHeight)
    }

    /** 定时关闭面板：时/分/秒选择，到时暂停播放 */
    fun showTimerSheet() {
        val host = SwingUtilities.getWindowAncestor(this) ?: return
        showSheet(TimerSheetPanel { hideSheet() }, TIMER_SHEET_WIDTH_PX, TIMER_SHEET_HEIGHT_PX)
    }

    /** 多线程配置面板：分片大小/线程数/免责声明/风险开关 */
    fun showMultiThreadSheet() {
        val host = SwingUtilities.getWindowAncestor(this) ?: return
        showSheet(MultiThreadSheetPanel { hideSheet() }, MULTITHREAD_SHEET_WIDTH_PX, MULTITHREAD_SHEET_HEIGHT_PX)
    }

    /** 分享面板：外部播放器播放/浏览器播放/复制播放链接 */
    fun showShareSheet() {
        val host = SwingUtilities.getWindowAncestor(this) ?: return
        showSheet(ShareSheetPanel { hideSheet() }, SHARE_SHEET_WIDTH_PX, SHARE_SHEET_HEIGHT_PX)
    }

    /** 底部弹出白色圆角面板：独立原生窗口（Compose 弹窗会被 heavyweight 视频面遮挡），居中贴底、失焦自动关闭 */
    private fun showSheet(content: JPanel, width: Int, height: Int) {
        hideSidePanel()
        hideSheet()
        val host = SwingUtilities.getWindowAncestor(this) ?: return
        val window = JWindow(host).apply {
            background = Color(0, 0, 0, 0)
            addWindowFocusListener(object : WindowAdapter() {
                override fun windowLostFocus(e: WindowEvent) = hideSheet()
            })
        }
        window.add(content)
        sheetWindow = window
        val w = width.coerceAtMost(host.width - 24)
        val h = height.coerceAtMost((host.height * 0.7f).toInt()).coerceAtLeast(240)
        window.setBounds(
            host.x + (host.width - w) / 2,
            host.y + host.height - h - EPISODE_SHEET_BOTTOM_GAP_PX,
            w,
            h,
        )
        window.isVisible = true
    }

    /** 关闭并释放底部面板窗口 */
    private fun hideSheet() {
        sheetWindow?.dispose()
        sheetWindow = null
    }

    /**
     * 右缘内侧滑出面板（直播频道列表/播放设置）：独立原生窗口贴齐视频区右缘，
     * 高度铺满视频区，失焦自动关闭；视频面为 heavyweight，Compose 弹层压不住必须用原生窗口。
     */
    fun showSidePanel(content: JPanel, panelWidth: Int) {
        hideSidePanel()
        val host = SwingUtilities.getWindowAncestor(this) ?: return
        val window = JWindow(host).apply {
            background = Color(0, 0, 0, 0)
            addWindowFocusListener(object : WindowAdapter() {
                override fun windowLostFocus(e: WindowEvent) = hideSidePanel()
            })
        }
        window.add(content)
        sidePanelWindow = window
        val loc = runCatching { locationOnScreen }.getOrNull() ?: return
        val w = panelWidth.coerceAtMost(width - 24)
        window.setBounds(loc.x + width - w, loc.y, w, height.coerceAtLeast(1))
        window.isVisible = true
        // 全局外点关闭：mpv 子 HWND 等原生窗口点击不触发 windowLostFocus，兜底监听全局鼠标按下
        val listener = AWTEventListener { event ->
            if (event !is MouseEvent || event.id != MouseEvent.MOUSE_PRESSED) return@AWTEventListener
            val source = event.source as? Component ?: return@AWTEventListener
            if (SwingUtilities.getWindowAncestor(source) === window) return@AWTEventListener
            hideSidePanel()
        }
        Toolkit.getDefaultToolkit().addAWTEventListener(listener, AWTEvent.MOUSE_EVENT_MASK)
        sidePanelOutsideListener = listener
    }

    /** 关闭并释放右缘滑出面板 */
    fun hideSidePanel() {
        sidePanelOutsideListener?.let {
            Toolkit.getDefaultToolkit().removeAWTEventListener(it)
        }
        sidePanelOutsideListener = null
        sidePanelWindow?.dispose()
        sidePanelWindow = null
    }

    /** 面板标题行：左标题（加粗）+ 右动作容器（供各面板追加） */
    private fun sheetHeader(title: String): JPanel = JPanel(BorderLayout()).apply {
        isOpaque = false
        add(
            JLabel(title).apply {
                foreground = Color(0x22, 0x22, 0x22)
                font = font.deriveFont(Font.BOLD, 16f)
            },
            BorderLayout.WEST,
        )
    }

    /** 面板右上关闭按钮（✕ 字形，扁平） */
    private fun sheetCloseButton(onDismiss: Runnable): JButton = JButton("✕").apply {
        isContentAreaFilled = false
        isBorderPainted = false
        isFocusPainted = false
        foreground = Color(0x44, 0x44, 0x44)
        font = font.deriveFont(Font.BOLD, 15f)
        toolTipText = "关闭"
        addActionListener { onDismiss.run() }
    }

    /** 面板右上扁平文字/字形按钮（定时-添加定时 / 多线程-默认 等） */
    private fun sheetTextButton(glyph: String, tip: String, action: () -> Unit): JButton = JButton(glyph).apply {
        isContentAreaFilled = false
        isBorderPainted = false
        isFocusPainted = false
        isOpaque = false
        font = font.deriveFont(Font.PLAIN, 14f)
        toolTipText = tip
        addActionListener { action() }
    }

    /** 选集底部面板：白色圆角容器 + 源 Tab + 操作排（选中/列数/排序/下载/关闭）+ 集数网格 */
    private inner class EpisodeSheetPanel(private val onDismiss: Runnable) : JPanel(BorderLayout(0, 8)) {

        /** 网格列数（「列数」按钮循环 1→4） */
        private var columns = EPISODE_SHEET_COLUMNS

        /** 正序/倒序（「排序」按钮切换） */
        private var ascending = true

        /** 滚动区视口内容（承载网格，重建时只换内部网格） */
        private val gridView = JPanel(java.awt.BorderLayout()).apply { isOpaque = false }

        /** 当前集索引 → 单元格（「选中」按钮滚动定位用） */
        private val cells = mutableMapOf<Int, JComponent>()

        init {
            isOpaque = false
            border = javax.swing.border.EmptyBorder(14, 16, 14, 16)
            val sourceName = config.sources.getOrNull(config.currentSourceIndex)
                .orEmpty().ifBlank { "选集" }
            val header = JPanel(BorderLayout()).apply {
                isOpaque = false
                val tab = JLabel(sourceName).apply {
                    foreground = Color(0xD9, 0x3F, 0x3F)
                    font = font.deriveFont(Font.BOLD, 15f)
                    border = javax.swing.BorderFactory.createMatteBorder(0, 0, 3, 0, Color(0xD9, 0x3F, 0x3F))
                }
                val actions = Box.createHorizontalBox().apply {
                    add(sheetActionButton("◎", "选中当前集") { scrollToCurrent() })
                    add(sheetActionButton("▦", "切换列数") {
                        columns = columns % EPISODE_SHEET_MAX_COLUMNS + 1
                        rebuildGrid()
                    })
                    add(sheetActionButton("↑↓", "正序/倒序") {
                        ascending = !ascending
                        rebuildGrid()
                    })
                    add(sheetActionButton("⇩", "下载当前集") {
                        val index = config.currentEpisodeIndex.coerceAtLeast(0)
                        config.onDownloadEpisode?.invoke(index)
                            ?: showSeekHint("桌面版暂不支持下载")
                    })
                    config.onDownloadAllEpisodes?.let { action ->
                        add(sheetActionButton("⇊", "下载全部") { action() })
                    }
                    add(sheetActionButton("✕", "关闭") { onDismiss.run() })
                }
                add(tab, BorderLayout.WEST)
                add(actions, BorderLayout.EAST)
            }
            add(header, BorderLayout.NORTH)
            add(
                javax.swing.JScrollPane(gridView).apply {
                    border = null
                    isOpaque = false
                    viewport.isOpaque = false
                },
                BorderLayout.CENTER,
            )
            rebuildGrid()
        }

        /** 操作排按钮（扁平图标字） */
        private fun sheetActionButton(glyph: String, tip: String, action: () -> Unit): JButton =
            JButton(glyph).apply {
                isContentAreaFilled = false
                isBorderPainted = false
                isFocusPainted = false
                foreground = Color(0x44, 0x44, 0x44)
                font = font.deriveFont(Font.PLAIN, 15f)
                toolTipText = tip
                addActionListener { action() }
            }

        /** 依列数与排序重建集数网格（当前集红底白字，其余粉底深字） */
        private fun rebuildGrid() {
            cells.clear()
            val grid = JPanel(java.awt.GridLayout(0, columns, 8, 8)).apply { isOpaque = false }
            val order = if (ascending) config.episodes.indices else config.episodes.indices.reversed()
            order.forEach { index ->
                val cell = EpisodeCell(config.episodes[index], index)
                cells[index] = cell
                grid.add(cell)
            }
            gridView.removeAll()
            gridView.add(grid, java.awt.BorderLayout.NORTH)
            gridView.revalidate()
            gridView.repaint()
            scrollToCurrent()
        }

        /** 滚动定位到当前集（「选中」按钮与重建后调用） */
        private fun scrollToCurrent() {
            cells[config.currentEpisodeIndex]?.scrollRectToVisible(java.awt.Rectangle(0, 0, 1, EPISODE_ROW_HEIGHT_PX))
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val g2 = g.create() as java.awt.Graphics2D
            g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = Color.WHITE
            g2.fillRoundRect(0, 0, width, height, 18, 18)
            g2.dispose()
        }

        /** 集数格：圆角背景（当前集红底白字，其余粉底深字），文字居中，点击切集 */
        private inner class EpisodeCell(private val name: String, private val index: Int) : JComponent() {
            init {
                isOpaque = false
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                preferredSize = Dimension(1, EPISODE_ROW_HEIGHT_PX)
                addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) {
                        onDismiss.run()
                        config.onEpisodeSelected(index)
                    }
                })
            }

            override fun paintComponent(g: Graphics) {
                super.paintComponent(g)
                val g2 = g.create() as java.awt.Graphics2D
                g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
                val isCurrent = index == config.currentEpisodeIndex
                g2.color = if (isCurrent) Color(0xD9, 0x3F, 0x3F) else Color(0xF8, 0xC9, 0xC9)
                g2.fillRoundRect(0, 2, width, height - 4, 16, 16)
                g2.color = if (isCurrent) Color.WHITE else Color(0x33, 0x33, 0x33)
                g2.font = font.deriveFont(Font.PLAIN, 13f)
                val fm = g2.fontMetrics
                var text = name
                while (text.length > 1 && fm.stringWidth(text) > width - 12) text = text.dropLast(1)
                if (text != name && text.length > 1) text = text.dropLast(1) + "…"
                g2.drawString(text, (width - fm.stringWidth(text)) / 2, (height + fm.ascent - fm.descent) / 2)
                g2.dispose()
            }
        }
    }

    /** 源列表面板：白色圆角容器 + 标题行（源列表/✕）+ 源行（名称+蓝点副行 | 更新至），当前源粉底高亮 */
    private inner class SourceSheetPanel(private val onDismiss: Runnable) : JPanel(BorderLayout(0, 8)) {

        init {
            isOpaque = false
            border = javax.swing.border.EmptyBorder(14, 16, 14, 16)
            val header = sheetHeader("源列表")
            header.add(sheetCloseButton(onDismiss), BorderLayout.EAST)
            add(header, BorderLayout.NORTH)
            val list = JPanel(java.awt.GridLayout(0, 1, 0, 8)).apply { isOpaque = false }
            config.sources.forEachIndexed { index, name ->
                list.add(
                    SourceRow(
                        name = name,
                        subtext = config.sourceSubtexts.getOrNull(index).orEmpty(),
                        updateText = config.sourceUpdateTexts.getOrNull(index).orEmpty(),
                        isCurrent = index == config.currentSourceIndex,
                    ) {
                        onDismiss.run()
                        config.onSourceSelected(index)
                    },
                )
            }
            add(
                javax.swing.JScrollPane(list).apply {
                    border = null
                    isOpaque = false
                    viewport.isOpaque = false
                },
                BorderLayout.CENTER,
            )
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val g2 = g.create() as java.awt.Graphics2D
            g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = Color.WHITE
            g2.fillRoundRect(0, 0, width, height, 18, 18)
            g2.dispose()
        }

        /** 源行：左（名称行 + 蓝点标识副行）右（更新至），当前源粉底，点击切源 */
        private inner class SourceRow(
            name: String,
            subtext: String,
            updateText: String,
            private val isCurrent: Boolean,
            onClick: Runnable,
        ) : JComponent() {

            private val left = JPanel(java.awt.GridLayout(2, 1, 0, 2)).apply {
                isOpaque = false
                add(
                    JLabel(name).apply {
                        foreground = if (isCurrent) Color(0xD9, 0x3F, 0x3F) else Color(0x22, 0x22, 0x22)
                        font = font.deriveFont(Font.BOLD, 14f)
                    },
                )
                if (subtext.isNotBlank()) {
                    add(
                        JPanel(java.awt.BorderLayout(4, 0)).apply {
                            isOpaque = false
                            add(blueDot(), java.awt.BorderLayout.WEST)
                            add(
                                JLabel(subtext).apply {
                                    foreground = Color(0x55, 0x55, 0x55)
                                    font = font.deriveFont(Font.PLAIN, 12f)
                                },
                                java.awt.BorderLayout.CENTER,
                            )
                        },
                    )
                }
            }

            init {
                isOpaque = false
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                preferredSize = Dimension(1, SOURCE_ROW_HEIGHT_PX)
                addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) = onClick.run()
                })
                layout = java.awt.BorderLayout(8, 0)
                add(left, java.awt.BorderLayout.WEST)
                if (updateText.isNotBlank()) {
                    add(
                        JLabel(updateText).apply {
                            foreground = Color(0x33, 0x33, 0x33)
                            font = font.deriveFont(Font.PLAIN, 12f)
                            horizontalAlignment = javax.swing.SwingConstants.RIGHT
                        },
                        java.awt.BorderLayout.EAST,
                    )
                }
            }

            /** 蓝点图标（源标识副行前缀） */
            private fun blueDot(): JComponent = object : JPanel() {
                init {
                    isOpaque = false
                    preferredSize = Dimension(8, 8)
                }

                override fun paintComponent(g: Graphics) {
                    super.paintComponent(g)
                    val g2 = g.create() as java.awt.Graphics2D
                    g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
                    g2.color = Color(0x1E, 0x6F, 0xE8)
                    g2.fillOval(0, 0, width, height)
                    g2.dispose()
                }
            }

            override fun paintComponent(g: Graphics) {
                super.paintComponent(g)
                val g2 = g.create() as java.awt.Graphics2D
                g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
                if (isCurrent) {
                    g2.color = Color(0xF8, 0xC9, 0xC9)
                    g2.fillRoundRect(0, 0, width, height, 12, 12)
                } else {
                    g2.color = Color(0xF6, 0xD7, 0xD7)
                    g2.drawRoundRect(0, 0, width - 1, height - 1, 12, 12)
                }
                g2.dispose()
            }
        }
    }

    /** 定时关闭面板：时/分/秒 JSpinner + 添加定时，到时暂停播放 */
    private inner class TimerSheetPanel(private val onDismiss: Runnable) : JPanel(BorderLayout(0, 14)) {

        private val hourSpinner = javax.swing.JSpinner(javax.swing.SpinnerNumberModel(0, 0, 23, 1))
        private val minuteSpinner = javax.swing.JSpinner(javax.swing.SpinnerNumberModel(15, 0, 59, 1))
        private val secondSpinner = javax.swing.JSpinner(javax.swing.SpinnerNumberModel(0, 0, 59, 1))

        init {
            isOpaque = false
            border = javax.swing.border.EmptyBorder(14, 16, 14, 16)
            val header = sheetHeader("定时关闭")
            header.add(
                Box.createHorizontalBox().apply {
                    add(
                        sheetTextButton("添加定时", "设置定时暂停") {
                            val totalSeconds = hourSpinner.value as Int * 3600 +
                                minuteSpinner.value as Int * 60 +
                                secondSpinner.value as Int
                            if (totalSeconds <= 0) {
                                showSeekHint("请选择定时时长")
                                return@sheetTextButton
                            }
                            sleepTimer?.stop()
                            sleepTimer = Timer(totalSeconds * 1000) {
                                if (controller.isPlaying.value) controller.togglePlayPause()
                                showSeekHint("定时时间到，已暂停播放")
                            }.apply {
                                isRepeats = false
                                start()
                            }
                            showSeekHint("已设置 ${formatDuration(totalSeconds)} 后暂停播放")
                            onDismiss.run()
                        },
                    )
                    add(Box.createHorizontalStrut(10))
                    add(sheetCloseButton(onDismiss))
                },
                BorderLayout.EAST,
            )
            add(header, BorderLayout.NORTH)

            val pickers = JPanel(java.awt.GridLayout(1, 3, 16, 0)).apply { isOpaque = false }
            listOf(hourSpinner to "时", minuteSpinner to "分", secondSpinner to "秒").forEach { (spinner, unit) ->
                pickers.add(
                    JPanel(java.awt.BorderLayout(6, 0)).apply {
                        isOpaque = false
                        spinner.font = spinner.font.deriveFont(Font.PLAIN, 16f)
                        add(spinner, java.awt.BorderLayout.CENTER)
                        add(
                            JLabel(unit).apply {
                                foreground = Color(0x33, 0x33, 0x33)
                                font = font.deriveFont(Font.PLAIN, 14f)
                            },
                            java.awt.BorderLayout.EAST,
                        )
                    },
                )
            }
            val center = JPanel(java.awt.GridBagLayout()).apply { isOpaque = false }
            center.add(pickers, GridBagConstraints().apply { fill = GridBagConstraints.HORIZONTAL })
            add(center, BorderLayout.CENTER)
        }

        /** 秒数转「x时x分x秒」提示文案 */
        private fun formatDuration(totalSeconds: Int): String {
            val h = totalSeconds / 3600
            val m = totalSeconds % 3600 / 60
            val s = totalSeconds % 60
            return buildString {
                if (h > 0) append("${h}时")
                if (m > 0) append("${m}分")
                if (s > 0 || isEmpty()) append("${s}秒")
            }
        }
    }

    /** 多线程配置面板：分片大小/线程数 + 红色免责声明 + 知晓风险开关（偏好持久化） */
    private inner class MultiThreadSheetPanel(private val onDismiss: Runnable) : JPanel(BorderLayout(0, 12)) {

        private val defaultChunkKb = 150
        private val defaultThreads = 14

        private val chunkSpinner = javax.swing.JSpinner(
            javax.swing.SpinnerNumberModel(
                prefs.getInt(DesktopPrefs.KEY_M3U8_CHUNK_KB, defaultChunkKb),
                10,
                4096,
                10,
            ),
        )
        private val threadSpinner = javax.swing.JSpinner(
            javax.swing.SpinnerNumberModel(
                prefs.getInt(DesktopPrefs.KEY_M3U8_THREADS, defaultThreads),
                1,
                64,
                1,
            ),
        )

        init {
            isOpaque = false
            border = javax.swing.border.EmptyBorder(14, 16, 14, 16)
            val header = sheetHeader("多线程配置").apply {
                (getComponent(0) as JLabel).foreground = Color(0xD9, 0x3F, 0x3F)
                add(
                    sheetTextButton("默认", "恢复默认配置") {
                        chunkSpinner.value = defaultChunkKb
                        threadSpinner.value = defaultThreads
                        prefs.put(DesktopPrefs.KEY_M3U8_CHUNK_KB, defaultChunkKb)
                        prefs.put(DesktopPrefs.KEY_M3U8_THREADS, defaultThreads)
                        showSeekHint("已恢复默认多线程配置")
                    },
                    BorderLayout.EAST,
                )
            }
            add(header, BorderLayout.NORTH)

            val body = JPanel(java.awt.GridLayout(0, 1, 0, 10)).apply { isOpaque = false }
            body.add(
                configRow("分片大小(kb)", chunkSpinner) {
                    prefs.put(DesktopPrefs.KEY_M3U8_CHUNK_KB, chunkSpinner.value)
                },
            )
            body.add(
                configRow("线程数", threadSpinner) {
                    prefs.put(DesktopPrefs.KEY_M3U8_THREADS, threadSpinner.value)
                },
            )
            body.add(
                JLabel(
                    "<html><body style='width:340px'>免责声明：使用过程中可能存在封号、封 IP 等风险，" +
                        "若因此产生任何后果、用户需自行承担责任。</body></html>",
                ).apply {
                    foreground = Color(0xD9, 0x3F, 0x3F)
                    font = font.deriveFont(Font.PLAIN, 12f)
                    verticalAlignment = javax.swing.SwingConstants.TOP
                },
            )
            add(body, BorderLayout.CENTER)

            val riskRow = JPanel(BorderLayout()).apply { isOpaque = false }
            riskRow.add(
                JLabel("知晓风险，确认使用").apply {
                    foreground = Color(0x22, 0x22, 0x22)
                    font = font.deriveFont(Font.PLAIN, 14f)
                },
                BorderLayout.WEST,
            )
            riskRow.add(pillSwitch(prefs.getBoolean(DesktopPrefs.KEY_M3U8_RISK_ACK, false)) { confirmed ->
                prefs.put(DesktopPrefs.KEY_M3U8_RISK_ACK, confirmed)
                showSeekHint(if (confirmed) "已确认知晓多线程风险" else "已取消确认")
            }, BorderLayout.EAST)
            add(riskRow, BorderLayout.SOUTH)
        }

        /** 胶囊开关（对照设计稿：深色底 + 白色滑块） */
        private fun pillSwitch(initial: Boolean, onChange: (Boolean) -> Unit): JComponent =
            object : JComponent() {

                private var isOn = initial

                init {
                    isOpaque = false
                    preferredSize = Dimension(44, 24)
                    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    addMouseListener(object : MouseAdapter() {
                        override fun mouseClicked(e: MouseEvent) {
                            isOn = !isOn
                            repaint()
                            onChange(isOn)
                        }
                    })
                }

                override fun paintComponent(g: Graphics) {
                    super.paintComponent(g)
                    val g2 = g.create() as java.awt.Graphics2D
                    g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
                    g2.color = if (isOn) Color(0x34, 0xC7, 0x59) else Color(0x3A, 0x3A, 0x3A)
                    g2.fillRoundRect(0, 0, width, height, height, height)
                    g2.color = Color.WHITE
                    val knob = height - 6
                    g2.fillOval(if (isOn) width - knob - 3 else 3, 3, knob, knob)
                    g2.dispose()
                }
            }

        /** 配置行：左标签 + 右数值选择（变更即持久化） */
        private fun configRow(label: String, spinner: javax.swing.JSpinner, onSave: () -> Unit): JPanel =
            JPanel(BorderLayout(12, 0)).apply {
                isOpaque = false
                add(
                    JLabel(label).apply {
                        foreground = Color(0x22, 0x22, 0x22)
                        font = font.deriveFont(Font.PLAIN, 14f)
                    },
                    BorderLayout.WEST,
                )
                spinner.addChangeListener { onSave() }
                spinner.font = spinner.font.deriveFont(Font.PLAIN, 14f)
                add(spinner, BorderLayout.EAST)
            }
    }

    /** 分享面板：外部播放器播放 / 浏览器播放 / 复制播放链接 */
    private inner class ShareSheetPanel(private val onDismiss: Runnable) : JPanel(BorderLayout(0, 12)) {

        init {
            isOpaque = false
            border = javax.swing.border.EmptyBorder(14, 16, 14, 16)
            val header = sheetHeader("分享")
            header.add(sheetCloseButton(onDismiss), BorderLayout.EAST)
            add(header, BorderLayout.NORTH)
            val body = JPanel(java.awt.GridLayout(0, 1, 0, 8)).apply { isOpaque = false }
            body.add(shareRow("外部播放器播放") { playInExternalPlayer() })
            body.add(shareRow("浏览器播放") { playInBrowser() })
            body.add(shareRow("复制播放链接") { copyPlayUrl() })
            add(body, BorderLayout.CENTER)
        }

        /** 分享动作行（白底粉边圆角，点击执行） */
        private fun shareRow(text: String, action: () -> Unit): JComponent =
            object : JComponent() {
                init {
                    isOpaque = false
                    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    preferredSize = Dimension(1, EPISODE_ROW_HEIGHT_PX)
                    addMouseListener(object : MouseAdapter() {
                        override fun mouseClicked(e: MouseEvent) {
                            onDismiss.run()
                            action()
                        }
                    })
                }

                override fun paintComponent(g: Graphics) {
                    super.paintComponent(g)
                    val g2 = g.create() as java.awt.Graphics2D
                    g2.setRenderingHint(
                        java.awt.RenderingHints.KEY_ANTIALIASING,
                        java.awt.RenderingHints.VALUE_ANTIALIAS_ON,
                    )
                    g2.color = Color(0xF8, 0xC9, 0xC9)
                    g2.fillRoundRect(0, 2, width, height - 4, 16, 16)
                    g2.color = Color(0x33, 0x33, 0x33)
                    g2.font = font.deriveFont(Font.PLAIN, 13f)
                    val fm = g2.fontMetrics
                    g2.drawString(text, 14, (height + fm.ascent - fm.descent) / 2)
                    g2.dispose()
                }
            }

        /** 系统默认浏览器打开播放链接 */
        private fun playInBrowser() {
            val url = config.shareUrl
            if (url.isNullOrBlank()) {
                showSeekHint("当前播放暂无链接可分享")
                return
            }
            val ok = runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(url)) }.isSuccess
            showSeekHint(if (ok) "已在浏览器中打开播放链接" else "浏览器打开失败")
        }

        /** 唤起外部播放器（优先 mpv，PATH 未找到时复制链接兜底） */
        private fun playInExternalPlayer() {
            val url = config.shareUrl
            if (url.isNullOrBlank()) {
                showSeekHint("当前播放暂无链接可分享")
                return
            }
            val mpvPath = runCatching {
                val process = ProcessBuilder("cmd", "/c", "where", "mpv").start()
                process.inputStream.bufferedReader().readLine()?.trim()
            }.getOrNull()
            if (mpvPath.isNullOrBlank()) {
                copyUrlToClipboard(url)
                showSeekHint("未找到 mpv，已复制链接，请手动选择播放器")
                return
            }
            val ok = runCatching { ProcessBuilder(mpvPath, url).start() }.isSuccess
            showSeekHint(if (ok) "已交给外部播放器播放" else "外部播放器启动失败")
        }

        /** 复制播放链接到剪贴板 */
        private fun copyPlayUrl() {
            val url = config.shareUrl
            if (url.isNullOrBlank()) {
                showSeekHint("当前播放暂无链接可分享")
                return
            }
            copyUrlToClipboard(url)
            showSeekHint("播放链接已复制")
        }

        private fun copyUrlToClipboard(url: String) {
            runCatching {
                java.awt.Toolkit.getDefaultToolkit().systemClipboard
                    .setContents(java.awt.datatransfer.StringSelection(url), null)
            }
        }
    }

    // endregion

    // region 层级尺寸

    /** 控制层窗口跟随：本体移动/缩放或宿主窗口移动/缩放时同步位置 */
    private fun bindFollowListeners() {
        addComponentListener(object : ComponentAdapter() {
            override fun componentMoved(e: ComponentEvent) = repositionControls()
            override fun componentResized(e: ComponentEvent) = repositionControls()
        })
        addHierarchyBoundsListener(object : HierarchyBoundsAdapter() {
            override fun ancestorMoved(e: HierarchyEvent) = repositionControls()
            override fun ancestorResized(e: HierarchyEvent) = repositionControls()
        })
    }

    /** 惰性创建控制层宿主窗口（per-pixel 透明、不抢焦点，owned 于宿主窗口）；
     *  宿主窗口 peer 重建（如全屏切换）后旧 JWindow 失效，需校验 displayable 并重建 */
    private fun ensureControlsWindow(): JWindow? {
        controlsWindow?.takeIf { it.isDisplayable }?.let { return it }
        // 先隐藏再销毁：防 dispose 时序缝隙留下残影窗
        controlsWindow?.isVisible = false
        controlsWindow?.dispose()
        controlsWindow = null
        val owner = SwingUtilities.getWindowAncestor(this) ?: return null
        val window = JWindow(owner)
        // 顶层窗口 per-pixel 透明：背景 alpha=0 即进入非透明（分层窗口）模式
        window.background = Color(0, 0, 0, 0)
        window.focusableWindowState = false
        window.add(controlPane)
        controlsWindow = window
        return window
    }

    /** 惰性创建缓冲转圈宿主窗口（per-pixel 透明、不抢焦点；仅承载居中转圈） */
    private fun ensureBufferingWindow(): JWindow? {
        bufferingWindow?.takeIf { it.isDisplayable }?.let { return it }
        // 先隐藏再销毁：防 dispose 时序缝隙留下残影转圈（双圈根因）
        bufferingWindow?.isVisible = false
        bufferingWindow?.dispose()
        bufferingWindow = null
        val owner = SwingUtilities.getWindowAncestor(this) ?: return null
        val window = JWindow(owner).apply {
            background = Color(0, 0, 0, 0)
            focusableWindowState = false
            layout = null
            add(bufferingIndicator)
        }
        bufferingWindow = window
        return window
    }

    /** 播放窗置顶态：仅用户点「📌 置顶」按钮开启（不自动置顶，避免遮挡其它程序）；小窗画中画由主窗口置顶带动 */
    @Volatile
    private var pinnedByUser = false

    private val pinnedMode: Boolean get() = pinnedByUser

    /** 置顶按钮点击：同步主窗口与全部悬浮窗（控制/转圈/底部面板/侧栏）置顶态，按钮高亮反馈 */
    private fun togglePinnedByUser() {
        pinnedByUser = !pinnedByUser
        val accent = Color(0xFF4F, 0xC3, 0xF7)
        pinButton.foreground = if (pinnedByUser) accent else Color.WHITE
        pinButton.toolTipText = if (pinnedByUser) "取消置顶" else "窗口置顶"
        // 主窗口与全部悬浮窗同步置顶态（悬浮窗轮询也会随 pinnedMode 刷新）
        SwingUtilities.getWindowAncestor(this)?.isAlwaysOnTop = pinnedByUser
        controlsWindow?.isAlwaysOnTop = pinnedMode
        bufferingWindow?.isAlwaysOnTop = pinnedMode
        sheetWindow?.isAlwaysOnTop = pinnedMode
        sidePanelWindow?.isAlwaysOnTop = pinnedMode
    }

    /** 画面缩放档位（fit=按比例留边；crop/stretch 等画面铺满整窗） */
    private fun scaleModeOf(): String =
        prefs.getString(DesktopPrefs.KEY_VIDEO_SCALE).orEmpty().ifBlank { "fit" }

    /** 画面显示区域（屏幕坐标）：mpv 保持比例渲染，按视频宽高比在 overlay 内 fit 居中；未知比例/非 fit 回退整窗 */
    private fun displayVideoRectOnScreen(): Rectangle? {
        val loc = runCatching { locationOnScreen }.getOrNull() ?: return null
        val w = width.coerceAtLeast(1)
        val h = height.coerceAtLeast(1)
        val vw = controller.videoWidth.value
        val vh = controller.videoHeight.value
        if (vw <= 0 || vh <= 0 || scaleModeOf() != "fit") return Rectangle(loc.x, loc.y, w, h)
        val videoRatio = vw.toFloat() / vh
        val boxRatio = w.toFloat() / h
        return if (videoRatio > boxRatio) {
            val shownH = (w / videoRatio).toInt().coerceIn(1, h)
            Rectangle(loc.x, loc.y + (h - shownH) / 2, w, shownH)
        } else {
            val shownW = (h * videoRatio).toInt().coerceIn(1, w)
            Rectangle(loc.x + (w - shownW) / 2, loc.y, shownW, h)
        }
    }

    /** 把缓冲转圈窗口对齐画面显示区域，转圈居中 */
    private fun syncBufferingWindow(window: JWindow) {
        val rect = displayVideoRectOnScreen() ?: return
        window.setBounds(rect.x, rect.y, rect.width, rect.height)
        val size = bufferingIndicator.preferredSize
        bufferingIndicator.setBounds(
            (rect.width - size.width) / 2,
            (rect.height - size.height) / 2,
            size.width,
            size.height,
        )
    }

    /** 把控制层窗口对齐到画面显示区域（控制组件贴画面边缘而非窗口边缘） */
    private fun syncControlWindow(window: JWindow) {
        val rect = displayVideoRectOnScreen() ?: return
        window.setBounds(rect.x, rect.y, rect.width, rect.height)
        controlPane.setSize(rect.width, rect.height)
        // 尺寸变化后立即重排，避免沿用旧宽度（进度线被压成半截的根因）
        controlPane.validate()
    }

    /** 控制层窗口位置同步（可见时贴齐视频区屏幕坐标） */
    private fun repositionControls() {
        controlsWindow?.let { window ->
            if (controlsVisible && window.isVisible) syncControlWindow(window)
        }
        bufferingWindow?.let { window ->
            if (window.isVisible) syncBufferingWindow(window)
        }
        sidePanelWindow?.let { panel ->
            runCatching { locationOnScreen }.getOrNull()?.let { loc ->
                panel.setLocation(loc.x + width - panel.width, loc.y)
                if (panel.height != height) panel.setSize(panel.width, height)
            }
        }
    }

    override fun doLayout() {
        videoPane.setBounds(0, 0, width, height)
        controlPane.setSize(width, height)
        repositionControls()
    }

    override fun addNotify() {
        super.addNotify()
        syncTimer.start()
        hideTimer.restart()
    }

    override fun removeNotify() {
        syncTimer.stop()
        hideTimer.stop()
        hintTimer.stop()
        bufferingIndicator.animator.stop()
        sleepTimer?.stop()
        hideSheet()
        hideSidePanel()
        controlsWindow?.dispose()
        controlsWindow = null
        controlsVisible = true
        // 退出播放复位用户置顶：主窗口与悬浮窗全部取消置顶（下次播放默认不置顶）
        pinnedByUser = false
        pinButton.foreground = Color.WHITE
        pinButton.toolTipText = "窗口置顶"
        SwingUtilities.getWindowAncestor(this)?.isAlwaysOnTop = false
        super.removeNotify()
    }

    // endregion

    // region 进度线（红色细条 + 圆点手柄，可点击/拖拽 seek）

    /** 红色进度线：track 灰 / 已播红 / 手柄红点；支持点击与拖拽 */
    private inner class SeekBar : JComponent() {

        /** 拖拽目标进度（-1 = 非拖拽） */
        var dragMs = -1L
            private set

        /** 松手是否取消本次 seek（拖回起点取消，对照“松手取消调整进度”） */
        private var cancelOnRelease = false

        val isDragging: Boolean get() = dragMs >= 0

        init {
            preferredSize = Dimension(200, 14)
            // BoxLayout 里拉满整行（默认 maximum 取 preferred 尺寸会缩成一小段）
            maximumSize = Dimension(Int.MAX_VALUE, 14)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            val l = object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    dragStartMs = controller.timeMs.value
                    dragTo(e.x)
                }

                override fun mouseDragged(e: MouseEvent) = dragTo(e.x)

                override fun mouseReleased(e: MouseEvent) {
                    val target = dragMs
                    dragMs = -1L
                    if (target < 0) return
                    if (cancelOnRelease) {
                        showSeekHint("已取消调整进度")
                    } else {
                        controller.seekTo(target)
                    }
                    cancelOnRelease = false
                }
            }
            addMouseListener(l)
            addMouseMotionListener(object : MouseMotionAdapter() {
                override fun mouseDragged(e: MouseEvent) = l.mouseDragged(e)
            })
        }

        /** 按横坐标定位拖拽目标进度；拖回起点且原进度足够时提示松手取消 */
        private fun dragTo(x: Int) {
            val duration = controller.durationMs.value
            if (duration <= 0L) return
            val frac = (x.toDouble() / width.coerceAtLeast(1)).coerceIn(0.0, 1.0)
            dragMs = (frac * duration).toLong()
            val cancelEnabled = prefs.getBoolean(DesktopPrefs.KEY_SHOW_SEEK_CANCEL, true)
            cancelOnRelease = cancelEnabled && frac <= CANCEL_FRACTION && dragStartMs > 10_000L
            if (cancelEnabled && cancelOnRelease) {
                showSeekHint("松手取消调整进度")
            }
            repaint()
        }

        /** 播放中刷新进度（拖拽期间保持手绘目标位置） */
        fun refresh(playing: Boolean) {
            if (playing || dragMs >= 0) repaint()
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val duration = controller.durationMs.value
            if (duration <= 0L) return
            val progress = ((dragMs.takeIf { it >= 0 } ?: controller.timeMs.value).toDouble() / duration)
                .coerceIn(0.0, 1.0)
            val trackY = height / 2 - 2
            val playedW = (width * progress).toInt()
            // 轨道用亮色半透明：暗色视频上也清晰可见（深灰在暗背景上像“只有半条”）
            g.color = Color(255, 255, 255, 70)
            g.fillRect(0, trackY, width, 4)
            g.color = Color(0xE5, 0x48, 0x4D)
            g.fillRect(0, trackY, playedW, 4)
            // 手柄红点
            g.fillOval(playedW - 5, trackY - 3, 10, 10)
        }
    }

    // endregion

    // region 缓冲动画（中央旋转圆弧，仅缓冲中显示）

    /** 旋转圆弧缓冲指示器（animator 由状态轮询按缓冲态启停） */
    private inner class BufferingIndicator : JComponent() {

        /** 当前旋转角 */
        private var angle = 0

        /** 动画驱动（80ms 步进旋转） */
        val animator = Timer(ANIMATION_INTERVAL_MS) {
            angle = (angle + ROTATE_STEP_DEG) % 360
            repaint()
        }

        init {
            preferredSize = Dimension(SPINNER_SIZE, SPINNER_SIZE)
            isOpaque = false
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val g2 = g.create() as java.awt.Graphics2D
            g2.setRenderingHint(
                java.awt.RenderingHints.KEY_ANTIALIASING,
                java.awt.RenderingHints.VALUE_ANTIALIAS_ON,
            )
            g2.color = Color.WHITE
            g2.stroke = java.awt.BasicStroke(3f)
            g2.rotate(Math.toRadians(angle.toDouble()), width / 2.0, height / 2.0)
            g2.drawArc(width / 2 - SPINNER_RADIUS, height / 2 - SPINNER_RADIUS, SPINNER_RADIUS * 2, SPINNER_RADIUS * 2, 0, 270)
            g2.dispose()
        }
    }

    // endregion

    companion object {
        private const val UI_SYNC_INTERVAL_MS = 250
        private const val SEEK_HINT_DURATION_MS = 1000

        /** 长按倍速触发阈值（按住不动毫秒数） */
        private const val LONG_PRESS_DELAY_MS = 400

        /** 选集底部面板：行高 / 头部高 / 贴底间距 / 默认与最大列数 */
        private const val EPISODE_ROW_HEIGHT_PX = 40
        private const val EPISODE_SHEET_HEADER_HEIGHT_PX = 46
        private const val EPISODE_SHEET_BOTTOM_GAP_PX = 12
        private const val EPISODE_SHEET_COLUMNS = 2
        private const val EPISODE_SHEET_MAX_COLUMNS = 4

        /** 源列表面板：行高 / 头部高 */
        private const val SOURCE_ROW_HEIGHT_PX = 64
        private const val SOURCE_SHEET_HEADER_HEIGHT_PX = 56

        /** 定时/多线程/分享/内核面板尺寸 */
        private const val TIMER_SHEET_WIDTH_PX = 420
        private const val TIMER_SHEET_HEIGHT_PX = 220
        private const val MULTITHREAD_SHEET_WIDTH_PX = 420
        private const val MULTITHREAD_SHEET_HEIGHT_PX = 280
        private const val SHARE_SHEET_WIDTH_PX = 380
        private const val SHARE_SHEET_HEIGHT_PX = 240

        /** 缓冲动画参数 */
        private const val ANIMATION_INTERVAL_MS = 80
        private const val ROTATE_STEP_DEG = 30
        private const val SPINNER_SIZE = 40
        private const val SPINNER_RADIUS = 12

        /** 中央快进快退按钮与中心堆栈的间距 */
        private const val CENTER_GAP_PX = 90

        /** 顶栏返回按钮与标题间距 */
        private const val TITLE_GAP_PX = 8

        /** 顶栏返回按钮尺寸（14pt 的 ‹ 字形点击区过小，加大到可点） */
        private const val TOP_BACK_BUTTON_WIDTH_PX = 44

        /** 顶栏返回按钮高度 */
        private const val TOP_BACK_BUTTON_HEIGHT_PX = 40

        /** 顶栏/控制排按钮间距 */
        private const val CONTROL_GAP_PX = 10

        /** 顶栏标题最大字符数（超出省略号截断） */
        private const val TITLE_MAX_CHARS = 20

        /** 页内小窗尺寸与屏幕右下角边距 */
        private const val MINI_WIDTH = 420
        private const val MINI_HEIGHT = 240
        private const val MINI_MARGIN = 32

        /** 拖回进度条起点附近判定为“取消调整进度” */
        private const val CANCEL_FRACTION = 0.005
    }
}

/** 小号白色加粗标签（分辨率/时间/状态，对照设计稿醒目白字） */
private fun smallLabel(text: String): JLabel = JLabel(text).apply {
    foreground = Color.WHITE
    font = font.deriveFont(Font.BOLD, 14f)
}

/** 控制排图标按钮固定点击区宽（字形极小，扩大透明热区便于点击） */
private const val OVERLAY_ICON_WIDTH_PX = 40

/** 控制排图标按钮固定点击区高 */
private const val OVERLAY_ICON_HEIGHT_PX = 34

/**
 * 控制排无边框白字按钮（尺寸随文案自适应，避免长文案被截断）；
 * fixedSize=true 时按图标点击区尺寸固定（纯字形按钮），整块透明区域可点。
 */
private fun overlayButton(text: String, fixedSize: Boolean = false): JButton = JButton(text).apply {
    isBorderPainted = false
    isContentAreaFilled = false
    isFocusPainted = false
    foreground = Color.WHITE
    font = font.deriveFont(Font.BOLD, 14f)
    horizontalAlignment = javax.swing.SwingConstants.CENTER
    if (fixedSize) {
        preferredSize = Dimension(OVERLAY_ICON_WIDTH_PX, OVERLAY_ICON_HEIGHT_PX)
        margin = Insets(0, 0, 0, 0)
    }
}

/**
 * 控制排图标按钮（Segoe UI Symbol 单色字形，随前景色渲染 + tooltip）；
 * 显式指定字体族避免默认字体的 emoji 回落渲染成彩色/空框。
 * 字形本身极小，固定加大点击区（内容透明，整块区域可点）+ 零边距。
 */
private fun overlayIconButton(glyph: String, tip: String): JButton = JButton(glyph).apply {
    isBorderPainted = false
    isContentAreaFilled = false
    isFocusPainted = false
    foreground = Color.WHITE
    font = Font("Segoe UI Symbol", Font.BOLD, 16)
    horizontalAlignment = javax.swing.SwingConstants.CENTER
    verticalAlignment = javax.swing.SwingConstants.CENTER
    toolTipText = tip
    preferredSize = Dimension(OVERLAY_ICON_WIDTH_PX, OVERLAY_ICON_HEIGHT_PX)
    margin = Insets(0, 0, 0, 0)
}
