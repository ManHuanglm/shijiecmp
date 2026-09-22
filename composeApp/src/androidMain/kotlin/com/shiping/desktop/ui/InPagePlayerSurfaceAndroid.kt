package com.shiping.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.shiping.app.ShipingApp
import com.shiping.app.data.player.Media3PlayerKernel
import com.shiping.shared.graph.AppGraph
import com.shiping.shared.player.PlayerKernel

/** 创建页内播放内核：media3（应用上下文取宿主 Application） */
actual fun createInPageKernel(graph: AppGraph): PlayerKernel =
    Media3PlayerKernel().apply { applicationContext = ShipingApp.context }

/** 等待渲染面就绪：PlayerView 由组合承载（AndroidView factory 同步创建），立即就绪 */
actual suspend fun awaitSurfaceReady(kernel: PlayerKernel, timeoutMs: Long, pollIntervalMs: Long): Boolean = true

/** 释放页内播放内核：media3 内核内部主线程队列执行 release，直接调用即安全 */
actual fun releasePageKernel(kernel: PlayerKernel) {
    runCatching { kernel.release() }
}

/** 页内播放区（Android 简版）：PlayerView 内置控制层（顶栏/选集/侧栏等完整控制层延至 M5） */
@OptIn(UnstableApi::class)
@Composable
actual fun InPagePlayerSurface(
    kernel: PlayerKernel,
    app: AppViewModel,
    scope: PagePlayerScope,
    config: InPagePlayerConfig,
    modifier: Modifier,
    onActionsReady: (InPagePlayerActions) -> Unit,
) {
    val media3 = kernel as? Media3PlayerKernel
    // 组合作用域持有 PlayerView 引用：视图销毁时解绑，防悬挂引用（内核释放由页面编排负责）
    var playerView by remember { mutableStateOf<PlayerView?>(null) }
    DisposableEffect(media3) {
        onDispose { playerView?.player = null }
    }
    Box(modifier = modifier.background(Color.Black)) {
        media3?.let { target ->
            AndroidView(
                factory = { context ->
                    PlayerView(context).apply {
                        useController = true
                        player = target.player
                        playerView = this
                    }
                },
                update = { view ->
                    // 重组时对齐最新 player（内核重建后自动重绑）
                    view.player = target.player
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
    LaunchedEffect(Unit) { onActionsReady(NoopActions) }
}

/** Android 简版动作集：控制层暂无重量级面板，全部空实现 */
private object NoopActions : InPagePlayerActions {
    override fun setBackground(hidden: Boolean) {}
    override fun showEpisodeSheet() {}
    override fun showSourceSheet() {}
    override fun showTimerSheet() {}
    override fun showMultiThreadSheet() {}
    override fun showShareSheet() {}
}
