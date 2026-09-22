package com.shiping.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import com.shiping.desktop.player.PlayerController
import com.shiping.desktop.player.PlayerControllerFactory
import com.shiping.desktop.player.PlayerKernel as DesktopPlayerKernel
import com.shiping.desktop.player.PlayerOverlay
import com.shiping.desktop.player.PlayerOverlayConfig
import com.shiping.desktop.player.PlayerQuality
import com.shiping.desktop.player.PlayerWindowActions
import com.shiping.shared.graph.AppGraph
import com.shiping.shared.player.PlayerKernel
import kotlinx.coroutines.delay

/** 创建页内播放内核：按偏好选择 mpv/vlc/MDK（工厂同步创建，保证播放区工厂拿到真实视频面） */
actual fun createInPageKernel(graph: AppGraph): PlayerKernel =
    PlayerControllerFactory.create(PlayerControllerFactory.fromPrefs(graph.prefs), graph.prefs)

/**
 * 等待渲染面就绪：mpv/vlc 需要渲染面挂上显示层级后才能拿到 wid，
 * 轮询 AWT isDisplayable；MDK 同理。超时照常返回（内核侧自会报错）。
 */
actual suspend fun awaitSurfaceReady(kernel: PlayerKernel, timeoutMs: Long, pollIntervalMs: Long): Boolean {
    val surface = (kernel as? PlayerController)?.swingComponent ?: return true
    var waited = 0L
    while (!surface.isDisplayable && waited < timeoutMs) {
        delay(pollIntervalMs)
        waited += pollIntervalMs
    }
    return surface.isDisplayable
}

/** 释放页内播放内核：优先走 PlayerController.releaseAsync（后台线程拆 native，防 EDT 卡顿） */
actual fun releasePageKernel(kernel: PlayerKernel) {
    val controller = kernel as? PlayerController
    if (controller != null) {
        controller.releaseAsync()
    } else {
        Thread {
            runCatching {
                kernel.stop()
                kernel.release()
            }
        }.apply { isDaemon = true }.start()
    }
}

/** 页内播放区（桌面）：PlayerOverlay Swing 组合承载渲染面与控制层 */
@Composable
actual fun InPagePlayerSurface(
    kernel: PlayerKernel,
    app: AppViewModel,
    scope: PagePlayerScope,
    config: InPagePlayerConfig,
    modifier: Modifier,
    onActionsReady: (InPagePlayerActions) -> Unit,
) {
    val controller = kernel as PlayerController
    val overlay = remember(controller) { PlayerOverlay(controller.swingComponent, controller) }
    // 注册到导航层：切 Tab/切页时命令式隐藏控制层与视频面（heavyweight 面会盖住前台页面）
    DisposableEffect(overlay) {
        app.registerInPageOverlay(overlay)
        onDispose { app.unregisterInPageOverlay(overlay) }
    }
    // 窗口动作：按播放归属路由详情/直播全屏状态（overlay 内部按 actions.isFullscreen 同步按钮态）
    remember(overlay) { overlay.windowActions = windowActionsFor(scope, app, overlay) }
    // 配置刷新：不重建视频面，仅更新面板数据与按钮可见性
    SideEffect { overlay.updateUi(config.toOverlayConfig(overlay)) }
    // 动作集回传（一次性，供 Compose 信息列调起重量级弹出面板）
    LaunchedEffect(overlay) { onActionsReady(OverlayActions(overlay)) }
    SwingPanel(factory = remember(overlay) { { overlay } }, modifier = modifier)
}

/** 控制层动作集：映射 PlayerOverlay 对应面板 */
private class OverlayActions(private val overlay: PlayerOverlay) : InPagePlayerActions {
    override fun setBackground(hidden: Boolean) = overlay.setBackground(hidden)
    override fun showEpisodeSheet() = overlay.showEpisodeSheet()
    override fun showSourceSheet() = overlay.showSourceSheet()
    override fun showTimerSheet() = overlay.showTimerSheet()
    override fun showMultiThreadSheet() = overlay.showMultiThreadSheet()
    override fun showShareSheet() = overlay.showShareSheet()
}

/** 窗口动作按播放归属路由：详情页与直播页各自独立的全屏状态 */
private fun windowActionsFor(scope: PagePlayerScope, app: AppViewModel, overlay: PlayerOverlay): PlayerWindowActions =
    object : PlayerWindowActions {
        override fun toggleMaximize() = toggleFullscreen()
        override fun toggleFullscreen() = when (scope) {
            PagePlayerScope.DETAIL -> app.toggleDetailFullscreen()
            PagePlayerScope.TV -> app.toggleTvFullscreen()
        }
        override fun toggleMiniWindow() = overlay.defaultWindowActions.toggleMiniWindow()
        override val isFullscreen: Boolean
            get() = when (scope) {
                PagePlayerScope.DETAIL -> app.detailFullscreen.value
                PagePlayerScope.TV -> app.tvFullscreen.value
            }
    }

/** cmpMain 通用配置 → 桌面控制层配置（回调包 Runnable；频道/设置侧栏为 Swing 面板） */
private fun InPagePlayerConfig.toOverlayConfig(overlay: PlayerOverlay): PlayerOverlayConfig = PlayerOverlayConfig(
    qualities = qualities.map { PlayerQuality(it.label, it.qn) },
    currentQuality = qualities.firstOrNull { it.qn == currentQn }?.let { PlayerQuality(it.label, it.qn) },
    onQualitySelected = { quality -> onQualitySelected(quality.qn) },
    onPrevEpisode = onPrevEpisode?.let { action -> Runnable { action() } },
    onNextEpisode = onNextEpisode?.let { action -> Runnable { action() } },
    title = title,
    onBack = onBack?.let { action -> Runnable { action() } },
    episodes = episodes,
    currentEpisodeIndex = currentEpisodeIndex,
    onEpisodeSelected = onEpisodeSelected,
    sources = sources,
    currentSourceIndex = currentSourceIndex,
    onSourceSelected = onSourceSelected,
    sourceSubtexts = sourceSubtexts,
    sourceUpdateTexts = sourceUpdateTexts,
    shareUrl = shareUrl,
    currentKernel = DesktopPlayerKernel.fromId(kernelId),
    onKernelSelected = { kernel -> onKernelSelected(kernel.id) },
    introTarget = introMark,
    onDownloadEpisode = onDownloadEpisode,
    onChannelList = channelPanel?.let { spec ->
        Runnable {
            overlay.showSidePanel(
                TvChannelSidePanel(
                    initialTabIndex = spec.initialTabIndex,
                    groupNames = spec.groupNames,
                    currentChannelKey = spec.currentChannelKey,
                    channelsProvider = spec.channelsProvider,
                    onSelectTab = spec.onSelectTab,
                    onPlay = spec.onPlay,
                ),
                TV_CHANNEL_PANEL_WIDTH_PX,
            )
        }
    },
    onPlayerSettings = if (showPlayerSettings) {
        Runnable { overlay.showSidePanel(PlayerSettingsPanel(), TV_SETTINGS_PANEL_WIDTH_PX) }
    } else {
        null
    },
)
