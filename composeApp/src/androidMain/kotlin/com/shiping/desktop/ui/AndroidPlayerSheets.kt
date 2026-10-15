package com.shiping.desktop.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.DownloadDone
import androidx.compose.material.icons.outlined.PictureInPictureAlt
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.shiping.app.data.player.PipController
import com.shiping.desktop.data.model.TvChannel
import com.shiping.shared.prefs.PrefsKeys

/** 主题适配色约定：面板/文字/选中态全部走 MaterialTheme.colorScheme（随设置页主题色切换） */

/** 选集网格列数（图2 双列大按钮） */
private const val EPISODE_GRID_COLUMNS = 2

/** 源平台标识点固定蓝（图1/图3：不随主题色变化） */
internal val SourceDotBlue = Color(0xFF1E78FF)

/**
 * Android 控制层面板集：底部面板（选集/源/清晰度/定时/多线程/分享）+ 直播频道右缘侧栏。
 * 全部读 [AndroidInPageActions] 状态驱动，选择回调走 config 回调。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AndroidPlayerSheets(
    app: AppViewModel,
    actions: AndroidInPageActions,
) {
    val config = actions.config ?: return
    val sheet = actions.sheet
    if (sheet != null) {
        // 自绘底部面板：ModalBottomSheet 跨 window 渲染在 PlayerView(SurfaceView) 页面
        // 存在内容 alpha 异常（面板文字变浅不可读），改用 Popup（同频道侧栏模式）
        val scrimInteraction = remember { MutableInteractionSource() }
        Popup(
            alignment = Alignment.BottomCenter,
            onDismissRequest = { actions.sheet = null },
            properties = PopupProperties(focusable = true),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // 点击遮罩关闭面板
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.4f))
                        .clickable(interactionSource = scrimInteraction, indication = null) {
                            actions.sheet = null
                        },
                )
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                        .padding(top = 10.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .width(36.dp)
                            .height(4.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(2.dp)),
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    when (sheet) {
                        SheetKind.EPISODE -> EpisodeSheet(config, actions)
                        SheetKind.SOURCE -> SourceSheet(config, actions)
                        SheetKind.QUALITY -> QualitySheet(config, actions)
                        SheetKind.TIMER -> TimerSheet(actions)
                        SheetKind.MULTI_THREAD -> MultiThreadSheet(app, actions)
                        SheetKind.SHARE -> ShareSheet(config, actions)
                    }
                }
            }
        }
    }
    if (actions.sidePanelVisible) {
        ChannelSidePanel(config, actions)
    }
}

/** 面板统一骨架：标题 + 关闭按钮 + 内容 */
@Composable
private fun SheetScaffold(title: String, onClose: () -> Unit, content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = title, color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp)
            Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                Text(text = "✕", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        content()
    }
}

/** 面板顶栏图标按钮（图2 右上操作区：定时/画中画/下载/关闭） */
@Composable
private fun SheetIconButton(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(32.dp)) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * 选集面板（图1/图2 统一布局）：右上操作图标行 + 源 Tab（下划线式）+ 双列集数网格，当前集白边标记。
 * 第一个 ⊘ 图标进入/退出勾选模式：勾选模式下「全选」一键全选、「下载全」下载所选集。
 */
@Composable
private fun EpisodeSheet(config: InPagePlayerConfig, actions: AndroidInPageActions) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
        val context = LocalContext.current
        // 勾选模式与已选集（面板打开期间保留）
        var downloadMode by remember { mutableStateOf(false) }
        val selected = remember { mutableStateListOf<Int>() }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            // 第一个图标：进入/退出「选择要下载的集」模式（图2 第一个图标语义）
            SheetIconButton(
                Icons.Outlined.RadioButtonUnchecked,
                if (downloadMode) "退出选择" else "选择要下载的集",
            ) {
                downloadMode = !downloadMode
                selected.clear()
            }
            if (downloadMode) {
                // 原「下载全部」改为「全选」：勾选模式下一键全选/清空
                SheetIconButton(Icons.Outlined.SelectAll, if (selected.size == config.episodes.size) "清空" else "全选") {
                    if (selected.size == config.episodes.size) {
                        selected.clear()
                    } else {
                        selected.clear()
                        selected.addAll(config.episodes.indices)
                    }
                }
                // 「下载全」：下载勾选的集
                if (config.onDownloadEpisodes != null) {
                    SheetIconButton(
                        Icons.Outlined.Download,
                        "下载全",
                        enabled = selected.isNotEmpty(),
                    ) {
                        val indexes = selected.sorted()
                        downloadMode = false
                        selected.clear()
                        config.onDownloadEpisodes?.invoke(indexes)
                        actions.sheet = null
                    }
                }
            } else {
                SheetIconButton(Icons.Outlined.Timer, "定时关闭") { actions.showTimerSheet() }
                SheetIconButton(Icons.Outlined.PictureInPictureAlt, "画中画") {
                    (context as? Activity)?.let { PipController.tryEnterPip(it) }
                        ?: actions.showHint("当前环境不支持画中画")
                }
                SheetIconButton(Icons.Outlined.Download, "下载当前集") {
                    config.onDownloadEpisode?.invoke(config.currentEpisodeIndex.coerceAtLeast(0))
                        ?: actions.showHint("当前平台不支持下载")
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            SheetIconButton(Icons.Outlined.Close, "关闭") { actions.sheet = null }
        }
        if (downloadMode) {
            Text(
                text = "已选 ${selected.size} 集（点击集数勾选，选完点「下载全」）",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp, bottom = 10.dp)
                .horizontalScroll(rememberScrollState()),
        ) {
            config.sources.forEachIndexed { index, name ->
                val selectedSource = index == config.currentSourceIndex
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.clipClickable { config.onSourceSelected(index); actions.sheet = null },
                ) {
                    Text(
                        text = name,
                        color = if (selectedSource) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Box(
                        modifier = Modifier
                            .width(28.dp)
                            .height(2.dp)
                            .background(
                                if (selectedSource) MaterialTheme.colorScheme.primary else Color.Transparent,
                                RoundedCornerShape(1.dp),
                            ),
                    )
                }
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(EPISODE_GRID_COLUMNS),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.55f),
        ) {
            itemsIndexed(config.episodes) { index, name ->
                val isCurrent = index == config.currentEpisodeIndex
                val picked = index in selected
                Text(
                    text = (if (downloadMode && picked) "✓ " else "") + name,
                    color = when {
                        downloadMode && picked -> MaterialTheme.colorScheme.onPrimaryContainer
                        else -> MaterialTheme.colorScheme.onPrimary
                    },
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier
                        .clipClickable {
                            if (downloadMode) {
                                if (picked) selected.remove(index) else selected.add(index)
                            } else {
                                config.onEpisodeSelected(index)
                                actions.sheet = null
                            }
                        }
                        .background(
                            when {
                                downloadMode && picked -> MaterialTheme.colorScheme.primaryContainer
                                else -> MaterialTheme.colorScheme.primary
                            },
                            RoundedCornerShape(8.dp),
                        )
                        .then(
                            when {
                                downloadMode && picked -> Modifier.border(
                                    1.dp,
                                    MaterialTheme.colorScheme.primary,
                                    RoundedCornerShape(8.dp),
                                )
                                isCurrent -> Modifier.border(
                                    1.dp,
                                    Color.White.copy(alpha = 0.85f),
                                    RoundedCornerShape(8.dp),
                                )
                                else -> Modifier
                            },
                        )
                        .padding(vertical = 10.dp, horizontal = 2.dp),
                )
            }
        }
    }
}

/** 源列表面板（图3）：名称/更新至同行 + 蓝点副行（接口名），当前源主题色填充；尾部追加其他源同名影片（触底分批加载） */
@Composable
private fun SourceSheet(config: InPagePlayerConfig, actions: AndroidInPageActions) {
    val listState = rememberLazyListState()
    val nearEnd by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount > 0 && last >= info.totalItemsCount - 4
        }
    }
    // 触底自动加载下一批其他源（面板内无感追加）
    LaunchedEffect(nearEnd, config.crossSources.size, config.crossHasMore) {
        if (nearEnd && config.crossHasMore && !config.crossLoading) config.onCrossSourceLoadMore?.invoke()
    }
    SheetScaffold(title = "源列表", onClose = { actions.sheet = null }) {
        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.6f),
        ) {
            itemsIndexed(config.sources) { index, name ->
                val selected = index == config.currentSourceIndex
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clipClickable { config.onSourceSelected(index); actions.sheet = null }
                        .background(
                            if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                            RoundedCornerShape(10.dp),
                        )
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = name,
                            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        config.sourceUpdateTexts.getOrNull(index)?.takeIf { it.isNotBlank() }?.let {
                            Text(
                                text = it,
                                color = if (selected) {
                                    MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f)
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                fontSize = 12.sp,
                                maxLines = 1,
                            )
                        }
                    }
                    if (config.sourceSubtexts.getOrNull(index)?.isNotBlank() == true) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 4.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .background(SourceDotBlue, CircleShape),
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = config.sourceSubtexts[index],
                                color = if (selected) {
                                    MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f)
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                fontSize = 11.sp,
                            )
                        }
                    }
                }
            }
            // 其他源同名影片：点击跳对应源详情播放；触底自动搜索下一批源
            if (config.onCrossSourceSelected != null && config.crossSources.isNotEmpty()) {
                item(key = "cross_header", contentType = "header") {
                    Text(
                        text = "其他源播放",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                itemsIndexed(config.crossSources, key = { _, item -> "cross_${item.sourceId}" }) { _, match ->
                    val vod = match.vod
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clipClickable {
                                config.onCrossSourceSelected?.invoke(match)
                                actions.sheet = null
                            }
                            .background(Color.Transparent, RoundedCornerShape(10.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = vod.vodName.ifBlank { match.sourceName },
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 14.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (vod.vodRemarks.isNotBlank()) {
                                Text(
                                    text = "更新至：${vod.vodRemarks}",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                )
                            }
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 4.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .background(SourceDotBlue, CircleShape),
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = match.sourceName,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 11.sp,
                            )
                        }
                    }
                }
            }
            if (config.crossLoading) {
                item(key = "cross_loading", contentType = "loading") {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator(modifier = Modifier.height(22.dp)) }
                }
            }
        }
    }
}

/** 清晰度面板（B 站协议源），当前清晰度粉色高亮 */
@Composable
private fun QualitySheet(config: InPagePlayerConfig, actions: AndroidInPageActions) {
    SheetScaffold(title = "清晰度", onClose = { actions.sheet = null }) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            itemsIndexed(config.qualities) { _, option ->
                val selected = option.qn == config.currentQn
                Text(
                    text = option.label,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clipClickable { config.onQualitySelected(option.qn); actions.sheet = null }
                        .background(
                            if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(10.dp),
                        )
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
        }
    }
}

/** 定时关闭面板：时/分/秒步进 + 添加定时（到时暂停播放；重复添加替换） */
@Composable
private fun TimerSheet(actions: AndroidInPageActions) {
    var hours by rememberSaveable { mutableIntStateOf(0) }
    var minutes by rememberSaveable { mutableIntStateOf(15) }
    var seconds by rememberSaveable { mutableIntStateOf(0) }
    SheetScaffold(title = "定时关闭", onClose = { actions.sheet = null }) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            StepperRow("时", hours, 0..23) { hours = it }
            StepperRow("分", minutes, 0..59) { minutes = it }
            StepperRow("秒", seconds, 0..59) { seconds = it }
        }
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "添加定时",
            color = MaterialTheme.colorScheme.primary,
            fontSize = 14.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier
                .clipClickable { actions.scheduleSleepTimer(hours * 3600 + minutes * 60 + seconds) }
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.45f), RoundedCornerShape(16.dp))
                .padding(vertical = 10.dp),
        )
    }
}

/** 多线程配置面板：分片大小/线程数 + 免责声明 + 风险确认开关（偏好持久化，与桌面共用键） */
@Composable
private fun MultiThreadSheet(app: AppViewModel, actions: AndroidInPageActions) {
    val prefs = app.graph.prefs
    val defaultChunkKb = 150
    val defaultThreads = 14
    var chunkKb by rememberSaveable {
        mutableIntStateOf(prefs.getInt(PrefsKeys.KEY_M3U8_CHUNK_KB, defaultChunkKb))
    }
    var threads by rememberSaveable {
        mutableIntStateOf(prefs.getInt(PrefsKeys.KEY_M3U8_THREADS, defaultThreads))
    }
    var riskAck by rememberSaveable {
        mutableStateOf(prefs.getBoolean(PrefsKeys.KEY_M3U8_RISK_ACK, false))
    }
    SheetScaffold(title = "多线程配置", onClose = { actions.sheet = null }) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = "分片大小(kb)", color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp)
            Spacer(modifier = Modifier.weight(1f))
            StepperRow("", chunkKb, 10..4096, step = 10) {
                chunkKb = it
                prefs.put(PrefsKeys.KEY_M3U8_CHUNK_KB, it)
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(text = "线程数", color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp)
            Spacer(modifier = Modifier.weight(1f))
            StepperRow("", threads, 1..64) {
                threads = it
                prefs.put(PrefsKeys.KEY_M3U8_THREADS, it)
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = "「默认」恢复默认配置",
            color = MaterialTheme.colorScheme.primary,
            fontSize = 12.sp,
            modifier = Modifier.clipClickable {
                chunkKb = defaultChunkKb
                threads = defaultThreads
                prefs.put(PrefsKeys.KEY_M3U8_CHUNK_KB, defaultChunkKb)
                prefs.put(PrefsKeys.KEY_M3U8_THREADS, defaultThreads)
                actions.showHint("已恢复默认多线程配置")
            },
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "免责声明：使用过程中可能存在封号、封 IP 等风险，若因此产生任何后果、用户需自行承担责任。",
            color = MaterialTheme.colorScheme.primary,
            fontSize = 11.sp,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(text = "知晓风险，确认使用", color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp)
            Spacer(modifier = Modifier.weight(1f))
            Switch(
                checked = riskAck,
                onCheckedChange = { confirmed ->
                    riskAck = confirmed
                    prefs.put(PrefsKeys.KEY_M3U8_RISK_ACK, confirmed)
                    actions.showHint(if (confirmed) "已确认知晓多线程风险" else "已取消确认")
                },
            )
        }
    }
}

/** 分享面板：外部播放器播放 / 浏览器播放 / 复制播放链接 */
@Composable
private fun ShareSheet(config: InPagePlayerConfig, actions: AndroidInPageActions) {
    val context = LocalContext.current
    val url = config.shareUrl
    SheetScaffold(title = "分享", onClose = { actions.sheet = null }) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ShareRow("外部播放器播放") {
                if (url.isNullOrBlank()) {
                    actions.showHint("分享链接不可用")
                } else {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW)
                                .setDataAndType(Uri.parse(url), "video/*")
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                        actions.sheet = null
                    }.onFailure { actions.showHint("未找到可播放的外部应用") }
                }
            }
            ShareRow("浏览器播放") {
                if (url.isNullOrBlank()) {
                    actions.showHint("分享链接不可用")
                } else {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                        actions.sheet = null
                    }.onFailure { actions.showHint("未找到可打开链接的浏览器") }
                }
            }
            ShareRow("复制播放链接") {
                if (url.isNullOrBlank()) {
                    actions.showHint("分享链接不可用")
                } else {
                    context.getSystemService(Context.CLIPBOARD_SERVICE)?.let { manager ->
                        (manager as? ClipboardManager)?.setPrimaryClip(
                            ClipData.newPlainText("playUrl", url),
                        )
                        actions.showHint("播放链接已复制")
                        actions.sheet = null
                    }
                }
            }
        }
    }
}

/** 分享动作行（白底粉边圆角） */
@Composable
private fun ShareRow(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.onSurface,
        fontSize = 13.sp,
        modifier = Modifier
            .clipClickable(onClick = onClick)
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.45f), RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 11.dp),
    )
}

/** 数字步进行：- 值 + （unit 标签可空） */
@Composable
private fun StepperRow(
    unit: String,
    value: Int,
    range: IntRange,
    step: Int = 1,
    onChange: (Int) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StepButton("−", enabled = value - step >= range.first) { onChange((value - step).coerceIn(range)) }
        Text(
            text = "$value$unit",
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 14.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.width(64.dp),
        )
        StepButton("+", enabled = value + step <= range.last) { onChange((value + step).coerceIn(range)) }
    }
}

/** 步进按钮（圆形描边） */
@Composable
private fun StepButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        text = label,
        color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
        fontSize = 16.sp,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        modifier = Modifier
            .clipClickable(enabled = enabled, onClick = onClick)
            .size(32.dp)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
        style = androidx.compose.ui.text.TextStyle(lineHeight = 30.sp),
    )
}

/** 直播频道右缘侧栏：Tab 头（收藏/更新时间/分组）+ 频道列表，当前频道高亮 */
@Composable
private fun ChannelSidePanel(config: InPagePlayerConfig, actions: AndroidInPageActions) {
    val spec = config.channelPanel ?: return
    var tabIndex by rememberSaveable { mutableIntStateOf(spec.initialTabIndex) }
    Popup(
        alignment = Alignment.CenterEnd,
        onDismissRequest = { actions.sidePanelVisible = false },
        properties = PopupProperties(focusable = true),
    ) {
        Surface(
            color = Color.White,
            shadowElevation = 8.dp,
            modifier = Modifier.fillMaxHeight(0.92f).width(300.dp),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(text = "频道列表", color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp)
                    Spacer(modifier = Modifier.weight(1f))
                    IconButton(onClick = { actions.sidePanelVisible = false }, modifier = Modifier.size(28.dp)) {
                        Text(text = "✕", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    val tabNames = listOf("收藏", "更新时间") + spec.groupNames
                    tabNames.forEachIndexed { index, name ->
                        val selected = index == tabIndex
                        Text(
                            text = name,
                            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp,
                            maxLines = 1,
                            modifier = Modifier
                                .clipClickable {
                                    tabIndex = index
                                    spec.onSelectTab(index)
                                }
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                    RoundedCornerShape(10.dp),
                                )
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
                val channels = spec.channelsProvider(tabIndex)
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(channels, key = { it.favoriteKey }) { channel ->
                        val selected = channel.favoriteKey == spec.currentChannelKey
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clipClickable {
                                    spec.onPlay(channel, 0)
                                    actions.sidePanelVisible = false
                                }
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                    RoundedCornerShape(10.dp),
                                )
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            Text(
                                text = channel.name,
                                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (channel.urls.size > 1) {
                                Text(
                                    text = "${channel.urls.size} 条线路",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 11.sp,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 点击修饰（无涟漪，消费点击事件） */
private fun Modifier.clipClickable(enabled: Boolean = true, onClick: () -> Unit): Modifier =
    clickable(
        enabled = enabled,
        interactionSource = MutableInteractionSource(),
        indication = null,
        onClick = onClick,
    )
