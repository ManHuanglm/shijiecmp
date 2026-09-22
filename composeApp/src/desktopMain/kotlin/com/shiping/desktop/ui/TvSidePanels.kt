package com.shiping.desktop.ui

import com.shiping.desktop.data.model.TvChannel
import com.shiping.desktop.data.prefs.DesktopPrefs
import com.shiping.desktop.di.AppContainer
import com.shiping.desktop.player.PlayerControllerFactory
import com.shiping.desktop.player.PlayerKernel
import com.shiping.desktop.util.Constants
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridLayout
import java.awt.Image
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JScrollPane
import javax.swing.ScrollPaneConstants
import javax.swing.SwingConstants
import javax.swing.SwingUtilities

/** 直播频道侧栏宽度（px：左 Tab 栏 + 右频道卡片列） */
const val TV_CHANNEL_PANEL_WIDTH_PX = 520

/** 直播播放设置侧栏宽度（px） */
const val TV_SETTINGS_PANEL_WIDTH_PX = 360

/** 直播侧栏行高（px） */
private const val SIDE_ROW_HEIGHT_PX = 42

/** 直播侧栏统一字体（微软雅黑，与界面观感一致；默认 Java 字体在中文系统会退到宋体） */
private fun sideFont(style: Int = Font.PLAIN, size: Float = 13f): Font =
    Font("Microsoft YaHei", style, size.toInt())

/** 直播频道卡片高度（px） */
private const val CHANNEL_CARD_HEIGHT_PX = 56

/** 直播台标区域宽度（px） */
private const val LOGO_WIDTH_PX = 64

/** 直播侧栏 Tab 图标种类（自绘，避免特殊字符缺字形变豆腐块） */
private enum class RailGlyph { HEART, CLOCK, DOT }

/** 直播台标加载器：异步读取远程图片并缓存（Swing 绘制用，加载完成回调刷新） */
private object TvLogoLoader {

    private val cache = ConcurrentHashMap<String, Image>()

    /** 读取台标：缓存命中同步返回；未命中后台线程加载，完成后回调（须重绘） */
    fun load(url: String, onLoaded: Runnable): Image? {
        if (url.isBlank()) return null
        cache[url]?.let { return it }
        Thread {
            runCatching {
                val image = ImageIO.read(URL(url)) ?: return@Thread
                cache[url] = image
                SwingUtilities.invokeLater { onLoaded.run() }
            }
        }.apply {
            isDaemon = true
            name = "tv-logo-loader"
        }.start()
        return null
    }
}

/** 直播内核胶囊高度（px） */
private const val KERNEL_PILL_HEIGHT_PX = 44

/** 面板配色（红底选中胶囊、粉底开关） */
private object TvSidePalette {

    /** 当前频道/选中胶囊红 */
    val RED = Color(0xE5, 0x48, 0x4D)

    /** 选中文字红 */
    val RED_TEXT = Color(0xD9, 0x3F, 0x3F)

    /** 未选中胶囊粉底 */
    val PINK = Color(0xF8, 0xC9, 0xC9)

    /** 选中 Tab 浅粉底/卡片描边 */
    val PINK_LIGHT = Color(0xF6, 0xD7, 0xD7)

    /** 主文字色 */
    val INK = Color(0x22, 0x22, 0x22)

    /** 次要文字色 */
    val SUB = Color(0x66, 0x66, 0x66)

    /** 分组绿点（Swing 色板，与 Compose FixedPalette.TV_GROUP_GREEN 同值 0xFF4CAF50） */
    val GREEN = Color(0x4C, 0xAF, 0x50)
}

/**
 * 直播频道侧栏（经 PlayerOverlay.showSidePanel 从右缘滑出，全屏/非全屏共用）：
 * 左侧竖排 Tab（收藏 / 更新时间 / 各分组绿点标记），右侧频道卡片列（当前频道红底高亮）。
 * 频道数据经 channelsProvider 实时读取，选中 Tab 回写 onSelectTab 与页面状态保持同步。
 */
class TvChannelSidePanel(
    initialTabIndex: Int,
    groupNames: List<String>,
    private val currentChannelKey: String,
    private val channelsProvider: (Int) -> List<TvChannel>,
    private val onSelectTab: (Int) -> Unit,
    private val onPlay: (TvChannel, Int) -> Unit,
) : JPanel(BorderLayout(0, 0)) {

    /** 选中 Tab：0=收藏 1=更新时间 2+=分组（与页面 railTabIndex 同语义） */
    private var selectedTab = initialTabIndex

    private val railRows = mutableListOf<JComponent>()

    private val railView = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
    }

    private val cardsView = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
    }

    init {
        isOpaque = false
        border = BorderFactory.createEmptyBorder(12, 12, 12, 12)
        // 左：Tab 栏（收藏 / 更新时间红框徽标 / 分组绿点，图标自绘）
        railRows += railItem(0, "收藏", RailGlyph.HEART, badge = false)
        railRows += railItem(1, "更新时间", RailGlyph.CLOCK, badge = true)
        groupNames.forEachIndexed { index, name ->
            railRows += railItem(index + 2, name, RailGlyph.DOT, badge = false)
        }
        railRows.forEach(railView::add)
        val railScroll = JScrollPane(railView).apply {
            border = null
            isOpaque = false
            viewport.isOpaque = false
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            preferredSize = Dimension(150, 1)
        }
        add(railScroll, BorderLayout.WEST)
        // 右：频道卡片列
        rebuildCards()
        val cardsScroll = JScrollPane(cardsView).apply {
            border = BorderFactory.createEmptyBorder(0, 12, 0, 0)
            isOpaque = false
            viewport.isOpaque = false
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        }
        add(cardsScroll, BorderLayout.CENTER)
    }

    /** 白色圆角底（贴 per-pixel 透明窗口） */
    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = Color.WHITE
        g2.fillRoundRect(0, 0, width, height, 18, 18)
        g2.dispose()
    }

    /** 切换 Tab 后重建右侧频道卡片列 */
    private fun rebuildCards() {
        cardsView.removeAll()
        val channels = channelsProvider(selectedTab)
        if (channels.isEmpty()) {
            cardsView.add(
                JLabel(if (selectedTab == 0) "暂无收藏，点击 ♡ 收藏频道" else "暂无频道").apply {
                    foreground = TvSidePalette.SUB
                    font = sideFont()
                    border = BorderFactory.createEmptyBorder(24, 12, 24, 12)
                },
            )
        } else {
            channels.forEach { cardsView.add(ChannelCard(it)) }
        }
        cardsView.revalidate()
        cardsView.repaint()
    }

    /** Tab 行：自绘图标 + 文字，选中浅粉底，「更新时间」恒带红框徽标 */
    private fun railItem(index: Int, text: String, glyph: RailGlyph, badge: Boolean): JComponent {
        val row = object : JComponent() {
            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                if (index == selectedTab) {
                    g2.color = TvSidePalette.PINK_LIGHT
                    g2.fillRoundRect(0, 0, width - 1, height - 1, 10, 10)
                }
                if (badge) {
                    g2.color = TvSidePalette.RED
                    g2.drawRoundRect(0, 0, width - 1, height - 1, 10, 10)
                }
                g2.dispose()
            }
        }
        row.layout = BorderLayout(6, 0)
        row.isOpaque = false
        row.preferredSize = Dimension(150, SIDE_ROW_HEIGHT_PX)
        row.maximumSize = Dimension(150, SIDE_ROW_HEIGHT_PX)
        row.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        row.add(
            object : JComponent() {
                init {
                    isOpaque = false
                    preferredSize = Dimension(20, SIDE_ROW_HEIGHT_PX)
                }

                override fun paintComponent(g: Graphics) {
                    val g2 = g.create() as Graphics2D
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    val cx = width / 2
                    val cy = height / 2
                    when (glyph) {
                        RailGlyph.HEART -> {
                            g2.color = TvSidePalette.RED_TEXT
                            g2.fillOval(cx - 5, cy - 5, 6, 6)
                            g2.fillOval(cx - 1, cy - 5, 6, 6)
                            g2.fillPolygon(intArrayOf(cx - 5, cx + 5, cx), intArrayOf(cy - 1, cy - 1, cy + 6), 3)
                        }
                        RailGlyph.CLOCK -> {
                            g2.color = if (index == selectedTab) TvSidePalette.RED_TEXT else TvSidePalette.INK
                            g2.drawOval(cx - 5, cy - 5, 10, 10)
                            g2.drawLine(cx, cy, cx, cy - 3)
                            g2.drawLine(cx, cy, cx + 3, cy)
                        }
                        RailGlyph.DOT -> {
                            g2.color = TvSidePalette.GREEN
                            g2.fillOval(cx - 4, cy - 4, 8, 8)
                        }
                    }
                    g2.dispose()
                }
            },
            BorderLayout.WEST,
        )
        row.add(
            JLabel(text).apply {
                foreground = if (index == selectedTab) TvSidePalette.RED_TEXT else TvSidePalette.INK
                font = sideFont()
            },
            BorderLayout.CENTER,
        )
        row.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (selectedTab == index) return
                selectedTab = index
                onSelectTab(index)
                railRows.forEach { it.repaint() }
                rebuildCards()
            }
        })
        return row
    }

    /** 频道卡片：台标（等比缩放居左）+ 频道名；当前频道红底白字，其余白底粉描边；多线路时 ⋮ 弹线路菜单 */
    private inner class ChannelCard(private val channel: TvChannel) : JComponent() {

        private val current = channel.favoriteKey == currentChannelKey

        init {
            layout = BorderLayout(6, 0)
            isOpaque = false
            preferredSize = Dimension(1, CHANNEL_CARD_HEIGHT_PX)
            maximumSize = Dimension(Int.MAX_VALUE, CHANNEL_CARD_HEIGHT_PX)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            add(LogoView(channel), BorderLayout.WEST)
            add(
                JLabel(channel.name).apply {
                    foreground = if (current) Color.WHITE else TvSidePalette.INK
                    font = sideFont(Font.BOLD, 13f)
                    border = BorderFactory.createEmptyBorder(0, 2, 0, 4)
                },
                BorderLayout.CENTER,
            )
            if (channel.urls.size > 1) {
                add(lineMenuButton(channel), BorderLayout.EAST)
            }
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) = onPlay(channel, 0)
            })
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            if (current) {
                g2.color = TvSidePalette.RED
                g2.fillRoundRect(0, 0, width - 1, height - 1, 12, 12)
            } else {
                g2.color = Color.WHITE
                g2.fillRoundRect(0, 0, width - 1, height - 1, 12, 12)
                g2.color = TvSidePalette.PINK
                g2.drawRoundRect(0, 0, width - 1, height - 1, 12, 12)
            }
            g2.dispose()
        }
    }

    /** 台标视图：异步加载远程图片并等比缩小绘制在固定宽区域内 */
    private inner class LogoView(channel: TvChannel) : JComponent() {

        private var logo: Image? = TvLogoLoader.load(channel.logo) { repaint() }

        init {
            isOpaque = false
            preferredSize = Dimension(LOGO_WIDTH_PX, CHANNEL_CARD_HEIGHT_PX)
        }

        override fun paintComponent(g: Graphics) {
            val image = logo ?: return
            val imageWidth = image.getWidth(null)
            val imageHeight = image.getHeight(null)
            if (imageWidth <= 0 || imageHeight <= 0) return
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            val scale = minOf(
                width.toDouble() / imageWidth,
                height.toDouble() / imageHeight,
            )
            val drawWidth = (imageWidth * scale).toInt().coerceAtLeast(1)
            val drawHeight = (imageHeight * scale).toInt().coerceAtLeast(1)
            g2.drawImage(image, (width - drawWidth) / 2, (height - drawHeight) / 2, drawWidth, drawHeight, null)
            g2.dispose()
        }
    }

    /** 线路菜单按钮（自绘竖排三点，避免 ⋮ 字符缺字形）：弹出该频道线路列表，选中后切播 */
    private fun lineMenuButton(channel: TvChannel): JComponent {
        val button = object : JComponent() {
            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = TvSidePalette.SUB
                val cx = width / 2
                for (offset in intArrayOf(-8, 0, 8)) {
                    g2.fillOval(cx - 2, height / 2 + offset - 2, 4, 4)
                }
                g2.dispose()
            }
        }
        button.isOpaque = false
        button.preferredSize = Dimension(36, CHANNEL_CARD_HEIGHT_PX)
        button.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        button.toolTipText = "切换线路"
        button.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val menu = JPopupMenu()
                menu.isLightWeightPopupEnabled = false
                menu.font = sideFont()
                channel.urls.forEachIndexed { index, _ ->
                    menu.add(JMenuItem("线路${index + 1}").apply {
                        addActionListener { onPlay(channel, index) }
                    })
                }
                menu.show(button, 0, button.height + 2)
            }
        })
        return button
    }
}

/**
 * 播放设置面板（所有播放器复用）：独立播放窗口底部弹出、设置页同一套内容与偏好键。
 * 头部仅显示当前内核胶囊（红底，点击循环切换播放内核）+ MPV 专属行（硬解/字幕样式，其他内核隐藏）+ 公共播放行为行。
 *
 * @param paintBackground 是否自绘白色圆角底（独立透明窗口内为 true；嵌入底部 sheet 由外层画底时为 false）
 */
class PlayerSettingsPanel(private val paintBackground: Boolean = true) : JPanel(BorderLayout(0, 10)) {

    private val prefs get() = AppContainer.get().prefs

    private val header = JPanel(GridLayout(1, 1)).apply { isOpaque = false }

    private val rowsView = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
    }

    init {
        isOpaque = false
        border = BorderFactory.createEmptyBorder(14, 16, 14, 16)
        rebuildHeader()
        add(header, BorderLayout.NORTH)
        val scroll = JScrollPane(rowsView).apply {
            border = null
            isOpaque = false
            viewport.isOpaque = false
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        }
        add(scroll, BorderLayout.CENTER)
        rebuildRows()
    }

    /** 白色圆角底（贴 per-pixel 透明窗口；嵌入 sheet 时由外层绘制） */
    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        if (!paintBackground) return
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = Color.WHITE
        g2.fillRoundRect(0, 0, width, height, 18, 18)
        g2.dispose()
    }

    /** 头部仅显示当前内核胶囊（点击循环切换内核后整体重建以刷新行） */
    private fun rebuildHeader() {
        header.removeAll()
        header.add(KernelPill(PlayerControllerFactory.fromPrefs(prefs)))
        header.revalidate()
        header.repaint()
    }

    /** 设置行（MPV 专属行仅 MPV 内核展示，与设置页一致） */
    private fun rebuildRows() {
        rowsView.removeAll()
        val kernel = PlayerControllerFactory.fromPrefs(prefs)
        if (kernel == PlayerKernel.MPV) {
            rowsView.add(row("硬解码", SwitchButton(DesktopPrefs.KEY_MPV_HWDEC, true)))
            rowsView.add(row("特效字幕", SwitchButton(DesktopPrefs.KEY_MPV_FX_SUBTITLE, true)))
            rowsView.add(row("字幕颜色", ColorDotButton(DesktopPrefs.KEY_SUB_COLOR, Constants.SUB_COLORS, "#FFFFFF")))
            rowsView.add(
                row("字幕背景颜色", ColorDotButton(DesktopPrefs.KEY_SUB_BACK_COLOR, Constants.SUB_BACK_COLORS, "#000000")),
            )
            rowsView.add(percentRow("字幕背景色透明度", DesktopPrefs.KEY_SUB_BACK_ALPHA, 40, 0, 100, 10))
            rowsView.add(percentRow("字幕大小", DesktopPrefs.KEY_SUB_SIZE, 100, 50, 200, 10))
        }
        rowsView.add(videoScaleRow())
        rowsView.add(row("显示上一集下一集按钮", SwitchButton(DesktopPrefs.KEY_SHOW_PREV_NEXT, true)))
        rowsView.add(row("进入后台暂停播放", SwitchButton(DesktopPrefs.KEY_PAUSE_ON_BACKGROUND, false)))
        rowsView.add(row("显示总时长", SwitchButton(DesktopPrefs.KEY_SHOW_TOTAL_TIME, true)))
        rowsView.add(row("显示松手取消调整进度", SwitchButton(DesktopPrefs.KEY_SHOW_SEEK_CANCEL, true)))
        rowsView.add(
            speedRow(
                "全局倍速设置",
                DesktopPrefs.KEY_GLOBAL_SPEED,
                Constants.PLAYBACK_SPEEDS,
                Constants.PLAYBACK_SPEEDS.indexOf(1.0f),
            ),
        )
        rowsView.add(seekStepRow())
        rowsView.add(speedRow("长按倍速", DesktopPrefs.KEY_LONG_PRESS_SPEED, Constants.LONG_PRESS_SPEEDS, 0))
        rowsView.revalidate()
        rowsView.repaint()
    }

    /** 行骨架：左标题 + 右控件（控件包 FlowLayout 容器，防 BorderLayout 垂直拉伸把开关拉成圆） */
    private fun row(title: String, control: JComponent): JComponent = JPanel(BorderLayout(8, 0)).apply {
        isOpaque = false
        preferredSize = Dimension(1, SIDE_ROW_HEIGHT_PX)
        maximumSize = Dimension(Int.MAX_VALUE, SIDE_ROW_HEIGHT_PX)
        add(
            JLabel(title).apply {
                foreground = TvSidePalette.INK
                font = sideFont()
            },
            BorderLayout.CENTER,
        )
        add(
            JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
                isOpaque = false
                add(control)
            },
            BorderLayout.EAST,
        )
    }

    /** 扁平小按钮（− / +） */
    private fun flatButton(text: String, onClick: () -> Unit): JButton = JButton(text).apply {
        isContentAreaFilled = false
        isBorderPainted = false
        isFocusPainted = false
        isOpaque = false
        foreground = TvSidePalette.INK
        font = sideFont(Font.BOLD, 15f)
        preferredSize = Dimension(36, 30)
        addActionListener { onClick() }
    }

    /** − 值 + 步进：档位值经 onCommit 回写偏好 */
    private fun stepperRow(title: String, values: List<String>, initialIndex: Int, onCommit: (Int) -> Unit): JComponent {
        var index = initialIndex.coerceIn(0, values.lastIndex.coerceAtLeast(0))
        val valueLabel = JLabel(values.getOrNull(index) ?: "").apply {
            foreground = TvSidePalette.INK
            font = sideFont()
            horizontalAlignment = SwingConstants.CENTER
            preferredSize = Dimension(90, 30)
        }
        val minus = flatButton("−") {
            if (index > 0) {
                index--
                valueLabel.text = values[index]
                onCommit(index)
            }
        }
        val plus = flatButton("+") {
            if (index < values.lastIndex) {
                index++
                valueLabel.text = values[index]
                onCommit(index)
            }
        }
        val control = JPanel(GridLayout(1, 3, 4, 0)).apply {
            isOpaque = false
            preferredSize = Dimension(170, SIDE_ROW_HEIGHT_PX)
            add(minus)
            add(valueLabel)
            add(plus)
        }
        return row(title, control)
    }

    /** 整数范围档位行（透明度/字幕大小：步进值整数显示） */
    private fun percentRow(title: String, key: String, default: Int, min: Int, max: Int, step: Int): JComponent {
        val values = (min..max step step).map(Int::toString)
        val initial = (prefs.getInt(key, default) - min) / step
        return stepperRow(title, values, initial) { index -> prefs.put(key, min + index * step) }
    }

    /** 浮点档位行（全局倍速/长按倍速） */
    private fun speedRow(title: String, key: String, speeds: List<Float>, defaultIndex: Int): JComponent =
        stepperRow(title, speeds.map { "%.1f".format(it) }, defaultIndex) { index ->
            prefs.put(key, speeds[index])
        }

    /** 快进快退档位行（0s 不展示按钮） */
    private fun seekStepRow(): JComponent {
        val steps = Constants.SEEK_STEPS
        val initial = steps.indexOfFirst { it == prefs.getInt(DesktopPrefs.KEY_SEEK_STEP, 10) }.takeIf { it >= 0 } ?: 2
        return stepperRow("快进快退", steps.map { if (it == 0) "0s（不展示按钮）" else "${it}s" }, initial) { index ->
            prefs.put(DesktopPrefs.KEY_SEEK_STEP, steps[index])
        }
    }

    /** 画面比例行：整行点击循环切换 原始尺寸/裁剪铺满/拉伸画面 */
    private fun videoScaleRow(): JComponent {
        val options = listOf("fit" to "原始尺寸", "crop" to "裁剪铺满", "stretch" to "拉伸画面")
        var index = options.indexOfFirst { it.first == prefs.getString(DesktopPrefs.KEY_VIDEO_SCALE, "fit") }
            .takeIf { it >= 0 } ?: 0
        val label = JLabel().apply {
            foreground = TvSidePalette.INK
            font = sideFont()
        }
        fun sync() {
            label.text = "画面：${options[index].second}"
        }
        sync()
        return JPanel(BorderLayout()).apply {
            isOpaque = false
            preferredSize = Dimension(1, SIDE_ROW_HEIGHT_PX)
            maximumSize = Dimension(Int.MAX_VALUE, SIDE_ROW_HEIGHT_PX)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            add(label, BorderLayout.CENTER)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    index = (index + 1).mod(options.size)
                    prefs.put(DesktopPrefs.KEY_VIDEO_SCALE, options[index].first)
                    sync()
                }
            })
        }
    }

    /** 拨动开关（对照设置页行尾胶囊开关）：点击切换并写偏好 */
    private inner class SwitchButton(private val key: String, default: Boolean) : JComponent() {

        private var value = prefs.getBoolean(key, default)

        init {
            isOpaque = false
            preferredSize = Dimension(44, 24)
            maximumSize = Dimension(44, 24)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = "点击切换"
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    value = !value
                    prefs.put(key, value)
                    repaint()
                }
            })
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = if (value) TvSidePalette.RED else TvSidePalette.PINK
            g2.fillRoundRect(0, 0, width - 1, height - 1, height, height)
            g2.color = Color.WHITE
            val knobX = if (value) width - height else 0
            g2.fillOval(knobX + 2, 2, height - 5, height - 5)
            g2.dispose()
        }
    }

    /** 颜色循环圆点（字幕颜色/背景色）：点击切下一档颜色 */
    private inner class ColorDotButton(
        private val key: String,
        private val colors: List<String>,
        defaultHex: String,
    ) : JComponent() {

        private var hex = prefs.getString(key, defaultHex).uppercase()

        init {
            isOpaque = false
            preferredSize = Dimension(28, 24)
            maximumSize = Dimension(28, 24)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = hex
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    val next = colors[(colors.indexOf(hex) + 1).mod(colors.size)]
                    hex = next
                    prefs.put(key, next)
                    toolTipText = next
                    repaint()
                }
            })
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = runCatching { Color.decode(hex) }.getOrDefault(Color.WHITE)
            g2.fillOval(2, 2, width - 5, height - 5)
            g2.color = TvSidePalette.PINK
            g2.drawOval(2, 2, width - 5, height - 5)
            g2.dispose()
        }
    }

    /** 内核胶囊：只显示当前内核（红底），点击循环切换内核并重建设置行 */
    private inner class KernelPill(private val kernel: PlayerKernel) : JComponent() {

        init {
            isOpaque = false
            preferredSize = Dimension(1, KERNEL_PILL_HEIGHT_PX)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = "点击切换播放内核（下次打开播放器生效）"
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    val next = PlayerKernel.entries[(kernel.ordinal + 1) % PlayerKernel.entries.size]
                    prefs.put(PlayerControllerFactory.KEY_PLAYER_KERNEL, next.id)
                    rebuildHeader()
                    rebuildRows()
                }
            })
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = TvSidePalette.RED
            g2.fillRoundRect(0, 0, width - 1, height - 1, 12, 12)
            g2.color = Color.WHITE
            g2.font = sideFont(Font.BOLD, 14f)
            val metrics = g2.getFontMetrics(g2.font)
            g2.drawString(kernel.displayName.uppercase(), 14, (height + metrics.ascent - metrics.descent) / 2)
            g2.dispose()
        }
    }
}
