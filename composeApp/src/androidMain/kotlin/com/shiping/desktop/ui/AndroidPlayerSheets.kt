package com.shiping.desktop.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.shiping.desktop.data.model.TvChannel
import com.shiping.shared.prefs.PrefsKeys

/** 粉色强调色（选中项/动作行边框，对齐桌面 Govin 风格） */
private val AccentPink = Color(0xFF, 0xE5, 0x3F, 0x3F)

/** 选集网格列数 */
private const val EPISODE_GRID_COLUMNS = 5

/** 内核切换选项（ID + 展示名，顺序与桌面一致） */
private val kernelOptions = listOf(
    KernelIds.MPV to "MPV",
    KernelIds.VLC to "VLC",
    KernelIds.MDK to "MDK",
    KernelIds.MEDIA3 to "Media3",
)

/**
 * Android 控制层面板集：底部面板（选集/源/清晰度/内核/定时/多线程/分享）+ 直播频道右缘侧栏。
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
        ModalBottomSheet(
            onDismissRequest = { actions.sheet = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = Color.White,
        ) {
            when (sheet) {
                SheetKind.EPISODE -> EpisodeSheet(config, actions)
                SheetKind.SOURCE -> SourceSheet(config, actions)
                SheetKind.QUALITY -> QualitySheet(config, actions)
                SheetKind.KERNEL -> KernelSheet(config, actions)
                SheetKind.TIMER -> TimerSheet(actions)
                SheetKind.MULTI_THREAD -> MultiThreadSheet(app, actions)
                SheetKind.SHARE -> ShareSheet(config, actions)
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
            Text(text = title, color = Color(0xFF, 0x33, 0x33, 0x33), fontSize = 16.sp)
            Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                Text(text = "✕", color = Color(0xFF, 0x99, 0x99, 0x99), fontSize = 14.sp)
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        content()
    }
}

/** 选集面板：源 Tab 行（多源时）+ 集数网格，当前集红底白字 */
@Composable
private fun EpisodeSheet(config: InPagePlayerConfig, actions: AndroidInPageActions) {
    SheetScaffold(title = "选集", onClose = { actions.sheet = null }) {
        if (config.sources.size > 1) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp),
            ) {
                config.sources.forEachIndexed { index, name ->
                    val selected = index == config.currentSourceIndex
                    Text(
                        text = name,
                        color = if (selected) Color.White else Color(0xFF, 0x66, 0x66, 0x66),
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .clipClickable { config.onSourceSelected(index); actions.sheet = null }
                            .background(
                                if (selected) AccentPink else Color(0xFF, 0xF2, 0xF2, 0xF2),
                                RoundedCornerShape(12.dp),
                            )
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(EPISODE_GRID_COLUMNS),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.55f),
        ) {
            itemsIndexed(config.episodes) { index, name ->
                val selected = index == config.currentEpisodeIndex
                Text(
                    text = name,
                    color = if (selected) Color.White else Color(0xFF, 0x33, 0x33, 0x33),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier
                        .clipClickable { config.onEpisodeSelected(index); actions.sheet = null }
                        .background(
                            if (selected) AccentPink else Color(0xFF, 0xF7, 0xF7, 0xF7),
                            RoundedCornerShape(10.dp),
                        )
                        .padding(vertical = 9.dp, horizontal = 2.dp),
                )
            }
        }
    }
}

/** 源列表面板：名称 + 蓝点副行（接口名）+ 更新至，当前源粉色高亮 */
@Composable
private fun SourceSheet(config: InPagePlayerConfig, actions: AndroidInPageActions) {
    SheetScaffold(title = "切换源", onClose = { actions.sheet = null }) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.6f),
        ) {
            itemsIndexed(config.sources) { index, name ->
                val selected = index == config.currentSourceIndex
                val borderColor = if (selected) AccentPink else Color(0xFF, 0xEE, 0xEE, 0xEE)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clipClickable { config.onSourceSelected(index); actions.sheet = null }
                        .background(Color.White, RoundedCornerShape(12.dp))
                        .border(1.dp, borderColor, RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = name,
                        color = if (selected) AccentPink else Color(0xFF, 0x33, 0x33, 0x33),
                        fontSize = 14.sp,
                    )
                    if (config.sourceSubtexts.getOrNull(index)?.isNotBlank() == true) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .background(Color(0xFF, 0x2F, 0x6F, 0xED), CircleShape),
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = config.sourceSubtexts[index],
                                color = Color(0xFF, 0x99, 0x99, 0x99),
                                fontSize = 11.sp,
                            )
                        }
                    }
                    config.sourceUpdateTexts.getOrNull(index)?.let {
                        Text(text = it, color = Color(0xFF, 0x99, 0x99, 0x99), fontSize = 11.sp)
                    }
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
                    color = if (selected) AccentPink else Color(0xFF, 0x33, 0x33, 0x33),
                    fontSize = 14.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clipClickable { config.onQualitySelected(option.qn); actions.sheet = null }
                        .background(
                            if (selected) Color(0xFF, 0xFD, 0xEF, 0xEF) else Color(0xFF, 0xF7, 0xF7, 0xF7),
                            RoundedCornerShape(10.dp),
                        )
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
        }
    }
}

/** 内核切换面板：列表 + 「下次打开播放器生效」说明 */
@Composable
private fun KernelSheet(config: InPagePlayerConfig, actions: AndroidInPageActions) {
    SheetScaffold(title = "播放内核", onClose = { actions.sheet = null }) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            itemsIndexed(kernelOptions) { _, (id, label) ->
                val selected = id == config.kernelId
                Text(
                    text = label,
                    color = if (selected) AccentPink else Color(0xFF, 0x33, 0x33, 0x33),
                    fontSize = 14.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clipClickable {
                            config.onKernelSelected(id)
                            actions.showHint("播放内核已切换为 $label，下次打开播放器生效")
                            actions.sheet = null
                        }
                        .background(
                            if (selected) Color(0xFF, 0xFD, 0xEF, 0xEF) else Color(0xFF, 0xF7, 0xF7, 0xF7),
                            RoundedCornerShape(10.dp),
                        )
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
            item {
                Text(
                    text = "切换后下次打开播放器生效",
                    color = Color(0xFF, 0x99, 0x99, 0x99),
                    fontSize = 11.sp,
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
            color = AccentPink,
            fontSize = 14.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier
                .clipClickable { actions.scheduleSleepTimer(hours * 3600 + minutes * 60 + seconds) }
                .fillMaxWidth()
                .border(1.dp, Color(0xFF, 0xF8, 0xC9, 0xC9), RoundedCornerShape(16.dp))
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
            Text(text = "分片大小(kb)", color = Color(0xFF, 0x33, 0x33, 0x33), fontSize = 13.sp)
            Spacer(modifier = Modifier.weight(1f))
            StepperRow("", chunkKb, 10..4096, step = 10) {
                chunkKb = it
                prefs.put(PrefsKeys.KEY_M3U8_CHUNK_KB, it)
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(text = "线程数", color = Color(0xFF, 0x33, 0x33, 0x33), fontSize = 13.sp)
            Spacer(modifier = Modifier.weight(1f))
            StepperRow("", threads, 1..64) {
                threads = it
                prefs.put(PrefsKeys.KEY_M3U8_THREADS, it)
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = "「默认」恢复默认配置",
            color = AccentPink,
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
            color = AccentPink,
            fontSize = 11.sp,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(text = "知晓风险，确认使用", color = Color(0xFF, 0x22, 0x22, 0x22), fontSize = 13.sp)
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
        color = Color(0xFF, 0x33, 0x33, 0x33),
        fontSize = 13.sp,
        modifier = Modifier
            .clipClickable(onClick = onClick)
            .fillMaxWidth()
            .border(1.dp, Color(0xFF, 0xF8, 0xC9, 0xC9), RoundedCornerShape(16.dp))
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
            color = Color(0xFF, 0x33, 0x33, 0x33),
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
        color = if (enabled) Color(0xFF, 0x33, 0x33, 0x33) else Color(0xFF, 0xCC, 0xCC, 0xCC),
        fontSize = 16.sp,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        modifier = Modifier
            .clipClickable(enabled = enabled, onClick = onClick)
            .size(32.dp)
            .border(1.dp, Color(0xFF, 0xDD, 0xDD, 0xDD), CircleShape),
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
                    Text(text = "频道列表", color = Color(0xFF, 0x33, 0x33, 0x33), fontSize = 15.sp)
                    Spacer(modifier = Modifier.weight(1f))
                    IconButton(onClick = { actions.sidePanelVisible = false }, modifier = Modifier.size(28.dp)) {
                        Text(text = "✕", color = Color(0xFF, 0x99, 0x99, 0x99), fontSize = 13.sp)
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
                            color = if (selected) Color.White else Color(0xFF, 0x66, 0x66, 0x66),
                            fontSize = 11.sp,
                            maxLines = 1,
                            modifier = Modifier
                                .clipClickable {
                                    tabIndex = index
                                    spec.onSelectTab(index)
                                }
                                .background(
                                    if (selected) AccentPink else Color(0xFF, 0xF2, 0xF2, 0xF2),
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
                                    if (selected) Color(0xFF, 0xFD, 0xEF, 0xEF) else Color(0xFF, 0xF7, 0xF7, 0xF7),
                                    RoundedCornerShape(10.dp),
                                )
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            Text(
                                text = channel.name,
                                color = if (selected) AccentPink else Color(0xFF, 0x33, 0x33, 0x33),
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (channel.urls.size > 1) {
                                Text(
                                    text = "${channel.urls.size} 条线路",
                                    color = Color(0xFF, 0x99, 0x99, 0x99),
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
