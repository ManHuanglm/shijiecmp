package com.shiping.desktop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.shiping.shared.clipboard.ClipboardAction
import com.shiping.shared.clipboard.ClipboardClassifier
import kotlinx.coroutines.launch

/** 剪贴板自动检测开关键（设置→功能设置可关，保护隐私） */
internal const val KEY_CLIPBOARD_AUTO_DETECT = "clipboard_auto_detect"

/** 轻量剪贴板历史（仅会话内保留最近 8 条，供「打开链接」页快速取用） */
data class ClipboardHistoryEntry(val kind: String, val text: String)

object ClipboardHistory {
    private const val MAX = 8
    private val _items = mutableStateOf<List<ClipboardHistoryEntry>>(emptyList())
    val items: List<ClipboardHistoryEntry> get() = _items.value

    fun add(kind: String, text: String) {
        val trimmed = text.take(300)
        _items.value = (listOf(ClipboardHistoryEntry(kind, trimmed)) + _items.value.filterNot { it.text == trimmed }).take(MAX)
    }
}

/**
 * 剪贴板自动检测宿主（主界面根部挂载，双端共用）：
 * 应用启动 + 切回前台时读剪贴板，按分类弹窗引导——
 * 配置链接（多仓一键批量添加）/ m3u8-mp4 直链（直接播放）/ m3u 直播（导入）/ 普通文字（跳搜索）。
 * 设置→功能设置可整体关闭；同一文本会话内只弹一次。
 */
@Composable
fun ClipboardDetector(app: AppViewModel) {
    val prefs = LocalAppGraph.current.prefs
    var lastText by remember { mutableStateOf<String?>(null) }
    var pendingText by remember { mutableStateOf<String?>(null) }
    var pendingAction by remember { mutableStateOf<ClipboardAction?>(null) }

    val detect: () -> Unit = {
        if (prefs.getBoolean(KEY_CLIPBOARD_AUTO_DETECT, true)) {
            val text = readClipboardText()?.trim().orEmpty()
            if (text.isNotEmpty() && text != lastText) {
                lastText = text
                when (val action = ClipboardClassifier.classify(text)) {
                    is ClipboardAction.None -> {}
                    else -> {
                        pendingText = text
                        pendingAction = action
                    }
                }
            }
        }
    }
    ForegroundRecallEffect(onForeground = detect)
    // 首次组合延迟触发（前台回调已覆盖 Android 首启；桌面首启动无焦点事件，补一次）
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(600)
        detect()
    }

    val text = pendingText
    val action = pendingAction
    if (text != null && action != null) {
        ClipboardActionDialog(
            action = action,
            app = app,
            onHistory = { kind -> ClipboardHistory.add(kind, text) },
            onDone = {
                pendingText = null
                pendingAction = null
            },
        )
    }
}

/** 剪贴板动作弹窗：按分类给出引导动作（导入/批量加仓/播放/直播导入/搜索） */
@Composable
private fun ClipboardActionDialog(
    action: ClipboardAction,
    app: AppViewModel,
    onHistory: (String) -> Unit,
    onDone: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val graph = LocalAppGraph.current
    var busy by remember { mutableStateOf(false) }
    var warehouses by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var pendingContent by remember { mutableStateOf<String?>(null) }
    var pendingLabel by remember { mutableStateOf("") }

    // 折叠仓库列表视图（批量添加）
    if (warehouses.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = onDone,
            title = { Text("发现多仓配置") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        "共 ${warehouses.size} 个仓库，可一键全部添加：",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    warehouses.forEach { (name, url) ->
                        Text(
                            name,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    warehouses = emptyList()
                                    scope.launch {
                                        val result = graph.tvBoxImporterService.import(url, name)
                                        app.showMessage(result.message)
                                        onHistory("仓库·$name")
                                        onDone()
                                    }
                                }
                                .padding(vertical = 6.dp),
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        val list = warehouses
                        warehouses = emptyList()
                        scope.launch {
                            var ok = 0
                            list.forEach { (name, url) ->
                                runCatching { graph.tvBoxImporterService.import(url, name) }
                                    .onSuccess { if (!it.message.contains("失败")) ok++ }
                            }
                            busy = false
                            app.showMessage("批量添加完成：$ok/${list.size} 个仓库")
                            onHistory("多仓批量")
                            onDone()
                        }
                    },
                ) { Text(if (busy) "添加中…" else "全部添加") }
            },
            dismissButton = { TextButton(onClick = onDone) { Text("取消") } },
        )
        return
    }

    val (title, desc) = when (action) {
        is ClipboardAction.ConfigUrl -> "发现配置链接" to "是否导入该订阅配置？\n${action.url.take(120)}"
        is ClipboardAction.ConfigRaw -> "发现多仓配置内容" to "粘贴内容为多仓配置（${action.label}），可一次性批量添加多个仓库。"
        is ClipboardAction.VideoDirect -> "发现视频直链" to "是否直接播放？\n${action.url.take(120)}"
        is ClipboardAction.LiveM3u -> "发现直播地址" to "是否导入直播频道？\n${action.url.take(120)}"
        is ClipboardAction.SearchText -> "识别到片名" to "是否跳转搜索「${action.keyword}」？"
        is ClipboardAction.None -> return
    }
    val confirmLabel = when (action) {
        is ClipboardAction.ConfigUrl -> "导入配置"
        is ClipboardAction.ConfigRaw -> "批量导入"
        is ClipboardAction.VideoDirect -> "立即播放"
        is ClipboardAction.LiveM3u -> "导入直播"
        is ClipboardAction.SearchText -> "去搜索"
        is ClipboardAction.None -> return
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDone() },
        title = { Text(title) },
        text = { Text(desc, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    busy = true
                    when (action) {
                        is ClipboardAction.ConfigUrl -> {
                            onHistory("配置链接")
                            scope.launch {
                                val result = graph.tvBoxImporterService.import(action.url, "剪贴板")
                                busy = false
                                if (result.warehouses.isNotEmpty()) {
                                    pendingContent = null
                                    warehouses = result.warehouses
                                } else {
                                    app.showMessage(result.message)
                                    onDone()
                                }
                            }
                        }
                        is ClipboardAction.ConfigRaw -> {
                            onHistory(action.label)
                            scope.launch {
                                val result = graph.tvBoxImporterService.importRaw(action.content, action.label)
                                busy = false
                                if (result.warehouses.isNotEmpty()) {
                                    warehouses = result.warehouses
                                } else {
                                    app.showMessage(result.message)
                                    onDone()
                                }
                            }
                        }
                        is ClipboardAction.VideoDirect -> {
                            onHistory("视频直链")
                            app.playDirect("剪贴板播放", action.url)
                            onDone()
                        }
                        is ClipboardAction.LiveM3u -> {
                            onHistory("直播地址")
                            scope.launch {
                                val count = graph.tvRepository.insertUnique(
                                    listOf(com.shiping.desktop.data.model.TvSourceEntity(name = "剪贴板直播", url = action.url, note = "剪贴板导入")),
                                )
                                app.showMessage(if (count > 0) "直播地址已导入" else "直播地址已存在")
                                onDone()
                            }
                        }
                        is ClipboardAction.SearchText -> {
                            onHistory("搜索")
                            app.requestSearch(action.keyword)
                            onDone()
                        }
                        is ClipboardAction.None -> onDone()
                    }
                },
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = { if (!busy) onDone() }) { Text("忽略") } },
    )
}
