package com.shiping.desktop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.HourglassTop
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shiping.desktop.ui.common.deleteDownloadTask
import com.shiping.desktop.ui.common.downloadCompletedHint
import com.shiping.desktop.ui.common.downloadWifiOnlyApplicable
import com.shiping.desktop.ui.common.EmptyView
import com.shiping.desktop.ui.common.notifyDownloadSettingsChanged
import com.shiping.desktop.ui.common.observeDownloadTasks
import com.shiping.desktop.ui.common.pauseDownloadTask
import com.shiping.desktop.ui.common.resumeDownloadTask
import com.shiping.desktop.ui.common.openDownloadedFile
import com.shiping.desktop.ui.common.DownloadTaskUi
import com.shiping.desktop.ui.common.convertDownloadToMp4
import com.shiping.desktop.ui.common.supportsConvertToMp4
import com.shiping.shared.download.DownloadStatus
import com.shiping.shared.prefs.PrefsKeys
import com.shiping.desktop.util.Constants
import kotlinx.coroutines.launch

/**
 * 双端共享下载管理页：按剧集分组的任务列表（组头=剧名·集数）+ 暂停/恢复/重试/删除/
 * 本地产物打开/完成态播放 + 多选删除 + 限速档位选择。
 * 数据经平台 actual（Android=Room 流 / 桌面=引擎 StateFlow），页面零平台感知。
 *
 * @param onBack 返回动作（Android 栈内进入时传入；桌面侧栏导航传 null 隐藏返回箭头）
 */
@Composable
fun DownloadsPageContent(app: AppViewModel, onBack: (() -> Unit)? = null) {
    val tasks by observeDownloadTasks().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    // 多选删除模式与勾选集
    var multiSelectMode by remember { mutableStateOf(false) }
    val selectedIds = remember { mutableStateListOf<String>() }
    var showSettingsDialog by remember { mutableStateOf(false) }
    // 状态分页 Tab：0=下载中（排队/下载/暂停/失败） 1=已完成
    var selectedTab by remember { mutableStateOf(0) }

    /** 置换转换中的任务（防重复点击触发并发转换） */
    val convertingIds = remember { mutableStateListOf<String>() }

    // 状态分桶：下载中 = 未完成任务（排队/下载/暂停/失败），已完成 = 纯完成态
    val visibleTasks = remember(tasks, selectedTab) {
        if (selectedTab == 0) tasks.filter { it.status != DownloadStatus.COMPLETED }
        else tasks.filter { it.status == DownloadStatus.COMPLETED }
    }

    // 按剧分组（保序）：组键 = vodId|sourceUrl（同剧同源），组名 = title 去掉「·集名」尾巴
    val groups = remember(visibleTasks) {
        visibleTasks.groupBy { it.contentId.substringBeforeLast("|") }
            .map { (key, items) ->
                val episodeName = items.first().contentId.substringAfterLast("|")
                val vodTitle = items.first().title.removeSuffix("·$episodeName").ifBlank { items.first().title }
                DownloadGroup(groupKey = key, vodTitle = vodTitle, tasks = items)
            }
    }

    fun exitMultiSelect() {
        multiSelectMode = false
        selectedIds.clear()
    }

    fun toggleSelect(contentId: String) {
        if (contentId in selectedIds) selectedIds.remove(contentId) else selectedIds.add(contentId)
    }

    fun deleteSelected() {
        val count = selectedIds.size
        selectedIds.toList().forEach { deleteDownloadTask(it) }
        selectedIds.clear()
        multiSelectMode = false
        app.showMessage("已删除 $count 项下载任务")
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (multiSelectMode) {
                IconButton(onClick = { exitMultiSelect() }) {
                    Icon(Icons.Outlined.Close, contentDescription = "退出多选")
                }
                Text(
                    "已选 ${selectedIds.size} 项",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = {
                    if (selectedIds.size == visibleTasks.size) selectedIds.clear() else {
                        selectedIds.clear()
                        selectedIds.addAll(visibleTasks.map { it.contentId })
                    }
                }) {
                    Text(if (selectedIds.size == visibleTasks.size && visibleTasks.isNotEmpty()) "清空" else "全选")
                }
                IconButton(enabled = selectedIds.isNotEmpty(), onClick = { deleteSelected() }) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = "删除所选",
                        tint = if (selectedIds.isEmpty()) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    )
                }
            } else {
                if (onBack != null) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                }
                Text("下载管理", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                if (visibleTasks.isNotEmpty()) {
                    TextButton(onClick = { multiSelectMode = true }) { Text("多选") }
                }
                IconButton(onClick = { showSettingsDialog = true }) {
                    Icon(Icons.Outlined.Tune, contentDescription = "下载设置")
                }
                SpeedLimitSelector(app)
            }
        }
        // 状态分页 Tab：下载中（排队/下载/暂停/失败）与已完成
        val activeCount = tasks.count { it.status != DownloadStatus.COMPLETED }
        val completedCount = tasks.count { it.status == DownloadStatus.COMPLETED }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = selectedTab == 0,
                onClick = {
                    selectedTab = 0
                    if (multiSelectMode) exitMultiSelect()
                },
                label = { Text("下载中 ($activeCount)") },
            )
            FilterChip(
                selected = selectedTab == 1,
                onClick = {
                    selectedTab = 1
                    if (multiSelectMode) exitMultiSelect()
                },
                label = { Text("已完成 ($completedCount)") },
            )
        }
        when {
            tasks.isEmpty() -> {
                Column(
                    modifier = Modifier.fillMaxSize().padding(bottom = 80.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "暂无下载任务",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "详情页选集弹窗/选集面板可下载（支持批量勾选）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            visibleTasks.isEmpty() -> EmptyView(
                if (selectedTab == 0) "暂无进行中的任务" else "暂无已完成的任务",
            )
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                groups.forEach { group ->
                    item(key = "group_${group.groupKey}") {
                        GroupHeaderRow(
                            group = group,
                            selectMode = multiSelectMode,
                            selectedIds = selectedIds,
                            onToggleGroup = {
                                val allPicked = group.tasks.all { it.contentId in selectedIds }
                                group.tasks.forEach { task ->
                                    if (allPicked) selectedIds.remove(task.contentId)
                                    else if (task.contentId !in selectedIds) selectedIds.add(task.contentId)
                                }
                            },
                        )
                    }
                    items(group.tasks, key = { it.contentId }) { task ->
                        DownloadTaskCard(
                            task = task,
                            displayTitle = if (groups.size > 1) {
                                task.contentId.substringAfterLast("|").ifBlank { task.title }
                            } else {
                                task.title
                            },
                            selectMode = multiSelectMode,
                            selected = task.contentId in selectedIds,
                            onToggleSelect = { toggleSelect(task.contentId) },
                            onPlay = {
                                app.playDirect(title = task.title, url = task.localPath)
                            },
                            converting = task.contentId in convertingIds,
                            onConvertToMp4 = if (supportsConvertToMp4 &&
                                task.localPath.isNotBlank() && !task.localPath.startsWith("content://")
                            ) {
                                {
                                    // 转换中防重复触发；完成后 Room localPath 更新为相册地址，按钮自动消失
                                    if (task.contentId !in convertingIds) {
                                        convertingIds.add(task.contentId)
                                        scope.launch {
                                            val message = runCatching { convertDownloadToMp4(task.contentId) }
                                                .fold(
                                                    onSuccess = { "已转 MP4 并置入相册" },
                                                    onFailure = { "转换失败：${it.message ?: "未知错误"}" },
                                                )
                                            app.showMessage(message)
                                            convertingIds.remove(task.contentId)
                                        }
                                    }
                                }
                            } else {
                                null
                            },
                        )
                    }
                }
            }
        }
        if (showSettingsDialog) {
            DownloadSettingsDialog(app = app, onDismiss = { showSettingsDialog = false })
        }
    }
}

/** 下载分组（同一部剧/电影的任务集合） */
private data class DownloadGroup(
    val groupKey: String,
    val vodTitle: String,
    val tasks: List<DownloadTaskUi>,
)

/** 分组头：剧名 + 集数（多选模式下附组全选框） */
@Composable
private fun GroupHeaderRow(
    group: DownloadGroup,
    selectMode: Boolean,
    selectedIds: List<String>,
    onToggleGroup: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectMode) {
            Checkbox(
                checked = group.tasks.isNotEmpty() && group.tasks.all { it.contentId in selectedIds },
                onCheckedChange = { onToggleGroup() },
            )
        }
        Text(
            text = group.vodTitle,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "${group.tasks.size} 集",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 下载设置弹窗：并发任务数 / HLS 分片并发 / 仅 WiFi（Android）——写偏好即时生效 */
@Composable
private fun DownloadSettingsDialog(app: AppViewModel, onDismiss: () -> Unit) {
    var maxParallel by remember {
        mutableStateOf(app.graph.prefs.getInt(PrefsKeys.KEY_DOWNLOAD_MAX_PARALLEL, 1).coerceIn(1, 3))
    }
    var segmentConcurrency by remember {
        mutableStateOf(app.graph.prefs.getInt(PrefsKeys.KEY_DOWNLOAD_SEGMENT_CONCURRENCY, 3).coerceIn(1, 6))
    }
    var wifiOnly by remember { mutableStateOf(app.graph.prefs.getBoolean(PrefsKeys.KEY_DOWNLOAD_WIFI_ONLY, false)) }
    val segmentOptions = listOf(1, 2, 4, 6)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("下载设置") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("并发任务数", modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        maxParallel = if (maxParallel >= 3) 1 else maxParallel + 1
                        app.graph.prefs.put(PrefsKeys.KEY_DOWNLOAD_MAX_PARALLEL, maxParallel)
                        notifyDownloadSettingsChanged()
                    }) {
                        Text(
                            "$maxParallel 个",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("分片并发", modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        segmentConcurrency = segmentOptions[
                            (segmentOptions.indexOf(segmentConcurrency) + 1).mod(segmentOptions.size),
                        ]
                        app.graph.prefs.put(PrefsKeys.KEY_DOWNLOAD_SEGMENT_CONCURRENCY, segmentConcurrency)
                    }) {
                        Text(
                            "$segmentConcurrency",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                if (downloadWifiOnlyApplicable) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("仅 WiFi 下载", modifier = Modifier.weight(1f))
                        Switch(
                            checked = wifiOnly,
                            onCheckedChange = {
                                wifiOnly = it
                                app.graph.prefs.put(PrefsKeys.KEY_DOWNLOAD_WIFI_ONLY, it)
                                notifyDownloadSettingsChanged()
                            },
                        )
                    }
                }
                Text(
                    "并发任务 = 同时下载的集数；分片并发 = 单个 m3u8 同时请求的分片数（过大易被 CDN 限流）。修改即时生效。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        },
    )
}

/** 限速档位选择器：当前档位文案按钮 + 下拉菜单（写偏好即时生效，下一任务起执行） */
@Composable
private fun SpeedLimitSelector(app: AppViewModel) {
    var expanded by remember { mutableStateOf(false) }
    var currentKb by remember { mutableStateOf(app.graph.prefs.getInt(PrefsKeys.KEY_DOWNLOAD_SPEED_LIMIT_KB, 0)) }
    val index = Constants.DOWNLOAD_SPEED_OPTIONS.indexOf(currentKb).coerceAtLeast(0)

    TextButton(onClick = { expanded = true }) {
        Icon(
            Icons.Outlined.KeyboardArrowDown,
            contentDescription = "选择限速档位",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "限速：${Constants.DOWNLOAD_SPEED_LABELS[index]}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        Constants.DOWNLOAD_SPEED_OPTIONS.forEachIndexed { i, option ->
            DropdownMenuItem(
                text = { Text(Constants.DOWNLOAD_SPEED_LABELS[i]) },
                onClick = {
                    currentKb = option
                    app.graph.prefs.put(PrefsKeys.KEY_DOWNLOAD_SPEED_LIMIT_KB, option)
                    expanded = false
                },
            )
        }
    }
}

/**
 * 单个下载任务卡片：多选模式整卡点击勾选（隐藏操作排）；普通模式展示
 * 标题/状态/进度条/字节与速度/操作排（完成态可点播放/打开文件夹）。
 */
@Composable
private fun DownloadTaskCard(
    task: DownloadTaskUi,
    displayTitle: String,
    selectMode: Boolean,
    selected: Boolean,
    onToggleSelect: () -> Unit,
    onPlay: () -> Unit,
    converting: Boolean = false,
    onConvertToMp4: (() -> Unit)? = null,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (selectMode && selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.then(
            if (selectMode) Modifier.clickable { onToggleSelect() } else Modifier,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selectMode) {
                Checkbox(checked = selected, onCheckedChange = { onToggleSelect() })
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = displayTitle,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                when (task.status) {
                    DownloadStatus.DOWNLOADING -> {
                        LinearProgressIndicator(
                            progress = { task.progress / 100f },
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)),
                        )
                        Text(
                            text = buildString {
                                append("下载中 ${task.progress}%")
                                if (task.totalBytes > 0) {
                                    append(" · ${formatBytes(task.bytesDownloaded)}/${formatBytes(task.totalBytes)}")
                                } else if (task.bytesDownloaded > 0) {
                                    append(" · ${formatBytes(task.bytesDownloaded)}")
                                }
                                if (task.speedBytesPerSecond > 0) append(" · ${formatBytes(task.speedBytesPerSecond)}/s")
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    DownloadStatus.QUEUED -> StatusText("排队中")
                    DownloadStatus.PAUSED -> StatusText(
                        if (task.progress in 1..99) "已暂停（续传将从头下载）" else "已暂停",
                    )
                    DownloadStatus.FAILED -> StatusText("失败：${task.errorMessage ?: "未知错误"}")
                    DownloadStatus.COMPLETED -> StatusText(
                        "已下载 · ${formatBytes(task.bytesDownloaded)} · $downloadCompletedHint",
                    )
                }
            }
            if (!selectMode) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (task.status == DownloadStatus.DOWNLOADING || task.status == DownloadStatus.QUEUED) {
                        ActionIcon(Icons.Outlined.Pause, "暂停") { pauseDownloadTask(task.contentId) }
                    }
                    if (task.status == DownloadStatus.PAUSED) {
                        ActionIcon(Icons.Outlined.PlayArrow, "继续") { resumeDownloadTask(task.contentId) }
                    }
                    if (task.status == DownloadStatus.FAILED) {
                        ActionIcon(Icons.Outlined.Refresh, "重试") { resumeDownloadTask(task.contentId) }
                    }
                    if (task.status == DownloadStatus.COMPLETED && task.localPath.isNotBlank()) {
                        ActionIcon(Icons.Outlined.PlayArrow, "播放") { onPlay() }
                        // 置换：本地产物（如 HLS 拼接 TS）转封装 MP4 置入相册，仅支持平台且未置入时显示
                        if (onConvertToMp4 != null) {
                            ActionIcon(
                                if (converting) Icons.Outlined.HourglassTop else Icons.Outlined.SwapHoriz,
                                if (converting) "转换中" else "转MP4",
                                enabled = !converting,
                            ) { onConvertToMp4() }
                        }
                        ActionIcon(Icons.Outlined.FolderOpen, "打开") { openDownloadedFile(task.contentId) }
                    }
                    if (task.status != DownloadStatus.DOWNLOADING) {
                        ActionIcon(Icons.Outlined.Delete, "删除") { deleteDownloadTask(task.contentId) }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusText(text: String) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ActionIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(icon, contentDescription = description, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 字节格式化（B/KB/MB/GB） */
private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "${bytes}B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1fKB".format(kb)
    val mb = kb / 1024
    if (mb < 1024) return "%.1fMB".format(mb)
    return "%.2fGB".format(mb / 1024)
}
