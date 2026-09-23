package com.shiping.desktop.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shiping.desktop.player.IntroMark
import com.shiping.shared.player.PlayerKernel
import kotlinx.coroutines.delay

/** 控制层自动隐藏延迟（播放中无操作毫秒数） */
private const val AUTO_HIDE_DELAY_MS = 3000L

/** 中央提示自动消失毫秒数 */
private const val HINT_DURATION_MS = 2500L

/**
 * Android 页内控制层：点击视频面唤出/隐藏；播放中 3s 无操作自动隐藏；
 * 面板/侧栏打开时常显。布局对齐桌面 PlayerOverlay：顶栏（返回/标题/内核）+ 底部（进度/按钮排）。
 */
@Composable
internal fun AndroidInPageControls(
    kernel: PlayerKernel,
    app: AppViewModel,
    actions: AndroidInPageActions,
) {
    val config = actions.config ?: return
    val isPlaying by kernel.isPlaying.collectAsState()
    val isBuffering by kernel.isBuffering.collectAsState()
    val timeMs by kernel.timeMs.collectAsState()
    val durationMs by kernel.durationMs.collectAsState()

    // 手动唤出计数：点视频面时递增，驱动自动隐藏倒计时重启
    var wakeTick by remember { mutableIntStateOf(0) }

    // 自动隐藏：播放中且无面板时 3s 无操作隐藏；暂停/面板打开时常显
    LaunchedEffect(isPlaying, actions.sheet, actions.sidePanelVisible, wakeTick) {
        if (isPlaying && actions.sheet == null && !actions.sidePanelVisible) {
            actions.controlsVisible = true
            delay(AUTO_HIDE_DELAY_MS)
            actions.controlsVisible = false
        } else {
            actions.controlsVisible = true
        }
    }

    // 中央提示自动消失
    LaunchedEffect(actions.hint) {
        if (actions.hint != null) {
            delay(HINT_DURATION_MS)
            actions.hint = null
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                actions.controlsVisible = !actions.controlsVisible
                wakeTick++
            },
    ) {
        AnimatedVisibility(
            visible = actions.controlsVisible || actions.sheet != null || actions.sidePanelVisible,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                AndroidTopBar(config, actions)
                Spacer(modifier = Modifier.weight(1f))
                if (isBuffering) {
                    Text(
                        text = "缓冲中…",
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 13.sp,
                        modifier = Modifier.align(Alignment.CenterHorizontally).padding(8.dp),
                    )
                }
                AndroidBottomBar(kernel, app, config, actions, isPlaying, timeMs, durationMs)
            }
        }
        // 中央提示（seek 提示/定时提示等，最高层）
        actions.hint?.let { text ->
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.7f), MaterialTheme.shapes.small)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(text = text, color = Color.White, fontSize = 14.sp)
            }
        }
    }
}

/** 顶栏：返回 + 标题（片名+集数）+ 内核切换；黑到透明渐变底 */
@Composable
private fun AndroidTopBar(config: InPagePlayerConfig, actions: AndroidInPageActions) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.65f), Color.Transparent)),
            )
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (config.onBack != null) {
            IconButton(onClick = { actions.closeSheets(); config.onBack?.invoke() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = Color.White)
            }
        }
        Text(
            text = config.title,
            color = Color.White,
            fontSize = 16.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
        )
        IconButton(onClick = { actions.showKernelSheet() }) {
            Icon(Icons.Filled.Settings, contentDescription = "播放内核", tint = Color.White)
        }
    }
}

/** 底部控制排：进度条 + 时间 + 播放/上下集 + 面板按钮（横向可滚） */
@Composable
private fun AndroidBottomBar(
    kernel: PlayerKernel,
    app: AppViewModel,
    config: InPagePlayerConfig,
    actions: AndroidInPageActions,
    isPlaying: Boolean,
    timeMs: Long,
    durationMs: Long,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.65f))),
            )
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        if (durationMs > 0) {
            AndroidSeekBar(kernel, timeMs, durationMs)
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        ) {
            IconButton(onClick = { kernel.togglePlayPause() }, modifier = Modifier.size(40.dp)) {
                Icon(
                    imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = "播放/暂停",
                    tint = Color.White,
                )
            }
            if (durationMs > 0) {
                Text(
                    text = "${formatTime(timeMs)} / ${formatTime(durationMs)}",
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 12.sp,
                )
            } else {
                Text(text = "直播", color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
            }
            Spacer(modifier = Modifier.width(4.dp))
            config.onPrevEpisode?.let {
                ControlIconButton(Icons.Filled.KeyboardArrowLeft, "上一集") { it() }
            }
            config.onNextEpisode?.let {
                ControlIconButton(Icons.Filled.KeyboardArrowRight, "下一集") { it() }
            }
            ControlTextButton("频道", enabled = config.channelPanel != null) { actions.showChannelPanel() }
            ControlTextButton("选集", enabled = config.episodes.isNotEmpty()) { actions.showEpisodeSheet() }
            ControlTextButton("源", enabled = config.sources.isNotEmpty()) { actions.showSourceSheet() }
            ControlTextButton("清晰度", enabled = config.qualities.isNotEmpty()) { actions.showQualitySheet() }
            ControlTextButton("定时") { actions.showTimerSheet() }
            ControlTextButton("多线程") { actions.showMultiThreadSheet() }
            ControlTextButton("分享") { actions.showShareSheet() }
            if (config.introMark != null) {
                ControlTextButton("记片头") {
                    val mark = config.introMark ?: return@ControlTextButton
                    val endMs = kernel.timeMs.value
                    IntroMark.save(app.graph.prefs, mark, endMs)
                    actions.showHint("已记录片头 ${formatTime(endMs)}，下次播放自动跳过")
                }
            }
        }
    }
}

/** 进度条：拖动中锁定显示拖动值，松手 seek（与桌面一致；拖动暂不支持取消回退） */
@Composable
private fun AndroidSeekBar(kernel: PlayerKernel, timeMs: Long, durationMs: Long) {
    var dragValue by remember { mutableStateOf<Float?>(null) }
    val totalSeconds = (durationMs / 1000f).coerceAtLeast(1f)
    val sliderColor = Color(0xFF, 0xE5, 0x3F, 0x3F)
    Slider(
        value = dragValue ?: (timeMs / 1000f).coerceIn(0f, totalSeconds),
        onValueChange = { dragValue = it },
        onValueChangeFinished = {
            dragValue?.let { kernel.seekTo((it * 1000).toLong()) }
            dragValue = null
        },
        valueRange = 0f..totalSeconds,
        colors = SliderDefaults.colors(
            thumbColor = sliderColor,
            activeTrackColor = sliderColor,
            inactiveTrackColor = Color.White.copy(alpha = 0.3f),
        ),
        modifier = Modifier.fillMaxWidth().height(28.dp),
    )
}

/** 图标控制按钮（白色） */
@Composable
private fun ControlIconButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
        Icon(icon, contentDescription = label, tint = Color.White)
    }
}

/** 文字控制按钮（白色小字，禁用降透明度） */
@Composable
private fun ControlTextButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        text = label,
        color = Color.White.copy(alpha = if (enabled) 0.95f else 0.35f),
        fontSize = 13.sp,
        modifier = Modifier
            .clickable(enabled = enabled, interactionSource = remember { MutableInteractionSource() }, indication = null) { onClick() }
            .padding(horizontal = 8.dp, vertical = 10.dp),
    )
}

/** 毫秒转 mm:ss / h:mm:ss */
internal fun formatTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val h = totalSeconds / 3600
    val m = totalSeconds % 3600 / 60
    val s = totalSeconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
