package com.shiping.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.ManageAccounts
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shiping.desktop.data.model.ApiSourceEntity
import com.shiping.desktop.data.model.TvSourceEntity
import com.shiping.shared.prefs.PrefsKeys
import com.shiping.shared.spider.SpiderProxyServer
import com.shiping.desktop.player.formatSpeed
import com.shiping.desktop.ui.common.DropdownSelector
import com.shiping.desktop.ui.common.TextInputRow
import com.shiping.desktop.ui.common.StoragePathPageContent
import com.shiping.desktop.ui.common.clearLogs
import com.shiping.desktop.ui.common.copyTextToClipboard
import com.shiping.desktop.ui.common.launchConfigScanner
import com.shiping.desktop.ui.common.pickImportTextFile
import com.shiping.desktop.ui.common.snapshotLogEntries
import com.shiping.desktop.ui.theme.FixedPalette
import com.shiping.desktop.ui.theme.ThemeColors
import com.shiping.desktop.ui.theme.ThemeState
import com.shiping.desktop.ui.theme.UiScale
import com.shiping.desktop.util.Constants
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 设置页内导航（二级/三级页，离开设置页复位） */
private enum class SettingsPage(val title: String) {
    DATA_MANAGE("数据管理"),
    SUBSCRIBE("订阅"),
    VIDEO_SOURCE("视频源"),
    TV_SOURCE("直播源"),
    STORAGE_PATH("自定义存储路径"),
    FEATURE("功能设置"),
    LOG("应用日志"),
    PLAY("播放设置"),
    UI_LANG("界面 / 语言"),
    THEME("主题"),
    TABS("Tab 设置"),
}

/**
 * 设置页（对齐 app 设置结构）：
 * 首页分组导航 → 数据管理 / 功能设置 / 播放设置 / 界面语言 二级页 → 三级页。
 */
@Composable
fun SettingsScreen(app: AppViewModel) {
    val stack = remember { mutableStateListOf<SettingsPage>() }
    val page = stack.lastOrNull()
    if (page == null) {
        SettingsHome(app, open = { stack.add(it) })
    } else {
        SettingsScaffold(
            title = page.title,
            onBack = { stack.removeAt(stack.lastIndex) },
            showTopBar = page != SettingsPage.VIDEO_SOURCE && page != SettingsPage.TV_SOURCE,
        ) {
            when (page) {
                SettingsPage.DATA_MANAGE -> DataManagePage(app, open = { stack.add(it) })
                SettingsPage.SUBSCRIBE -> SubscribePage(app)
                SettingsPage.VIDEO_SOURCE -> VideoSourcePage(app, onBack = { stack.removeAt(stack.lastIndex) })
                SettingsPage.TV_SOURCE -> TvSourcePage(app, onBack = { stack.removeAt(stack.lastIndex) })
                SettingsPage.STORAGE_PATH -> StoragePathPageContent(app)
                SettingsPage.FEATURE -> FeatureSettingsPage(app)
                SettingsPage.LOG -> LogPage()
                SettingsPage.PLAY -> PlaySettingsPage(app)
                SettingsPage.UI_LANG -> InterfaceLanguagePage(app, open = { stack.add(it) })
                SettingsPage.THEME -> ThemePage(app)
                SettingsPage.TABS -> TabsSettingPage(app)
            }
        }
    }
}

// region 通用组件

/** 二级页脚手架：顶栏（返回 + 居中标题，可扩展右侧动作）+ 内容 */
@Composable
private fun SettingsScaffold(
    title: String,
    onBack: () -> Unit,
    showTopBar: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        if (showTopBar) {
            SettingsTopBar(title = title, onBack = onBack)
        }
        content()
    }
}

/** 设置页顶栏：返回 + 居中标题 + 右侧动作区 */
@Composable
private fun SettingsTopBar(
    title: String,
    onBack: () -> Unit,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.Center),
        )
        if (actions != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.align(Alignment.CenterEnd),
            ) { actions() }
        }
    }
}

/** 可点击设置行（跳转/弹窗/动作），带副标题 */
@Composable
private fun SettingsRow(
    title: String,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        if (leading != null) {
            leading()
            Spacer(modifier = Modifier.width(12.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) {
            trailing()
        } else if (onClick != null) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 开关设置行（绑定偏好键持久化） */
@Composable
private fun PrefSwitchRow(title: String, key: String, def: Boolean, subtitle: String? = null) {
    val prefs = LocalAppGraph.current.prefs
    var checked by remember(key) { mutableStateOf(prefs.getBoolean(key, def)) }
    SettingsRow(title = title, subtitle = subtitle) {
        Switch(checked = checked, onCheckedChange = {
            checked = it
            prefs.put(key, it)
        })
    }
}

/** ± 步进设置行（百分比类偏好，实时回调） */
@Composable
private fun PercentPrefRow(
    title: String,
    key: String,
    def: Int,
    range: IntRange,
    step: Int,
    subtitle: String? = null,
    onChange: (Int) -> Unit = {},
) {
    val prefs = LocalAppGraph.current.prefs
    var value by remember(key) { mutableStateOf(prefs.getInt(key, def).coerceIn(range.first, range.last)) }
    StepperRow(
        title = title,
        subtitle = subtitle,
        value = "$value%",
        onMinusEnabled = value - step >= range.first,
        onPlusEnabled = value + step <= range.last,
        onMinus = {
            value = (value - step).coerceAtLeast(range.first)
            prefs.put(key, value)
            onChange(value)
        },
        onPlus = {
            value = (value + step).coerceAtMost(range.last)
            prefs.put(key, value)
            onChange(value)
        },
    )
}

/** ± 步进设置行（浮点档位类偏好：倍速等） */
@Composable
private fun SpeedPrefRow(title: String, key: String, values: List<Float>, defIndex: Int, subtitle: String? = null) {
    val prefs = LocalAppGraph.current.prefs
    var index by remember(key) {
        mutableStateOf(values.indexOfFirst { it == prefs.getString(key).toFloatOrNull() }.takeIf { it >= 0 } ?: defIndex)
    }
    StepperRow(
        title = title,
        subtitle = subtitle,
        value = formatSpeed(values[index]),
        onMinusEnabled = index > 0,
        onPlusEnabled = index < values.lastIndex,
        onMinus = {
            index = (index - 1).coerceAtLeast(0)
            prefs.put(key, values[index])
        },
        onPlus = {
            index = (index + 1).coerceAtMost(values.lastIndex)
            prefs.put(key, values[index])
        },
    )
}

/** ± 步进设置行（整数档位类偏好：快进快退秒数） */
@Composable
private fun SeekStepPrefRow() {
    val prefs = LocalAppGraph.current.prefs
    val values = Constants.SEEK_STEPS
    var index by remember {
        mutableStateOf(values.indexOfFirst { it == prefs.getInt(PrefsKeys.KEY_SEEK_STEP, 10) }.takeIf { it >= 0 } ?: 2)
    }
    StepperRow(
        title = "快进快退",
        value = if (values[index] == 0) "0s（不展示按钮）" else "${values[index]}s",
        onMinusEnabled = index > 0,
        onPlusEnabled = index < values.lastIndex,
        onMinus = {
            index = (index - 1).coerceAtLeast(0)
            prefs.put(PrefsKeys.KEY_SEEK_STEP, values[index])
        },
        onPlus = {
            index = (index + 1).coerceAtMost(values.lastIndex)
            prefs.put(PrefsKeys.KEY_SEEK_STEP, values[index])
        },
    )
}

/** ± 步进行骨架：标题 + 值 + 减/增按钮 */
@Composable
private fun StepperRow(
    title: String,
    value: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    onMinusEnabled: Boolean,
    onPlusEnabled: Boolean,
    subtitle: String? = null,
) {
    SettingsRow(title = title, subtitle = subtitle) {
        TextButton(onClick = onMinus, enabled = onMinusEnabled) { Text("−", style = MaterialTheme.typography.titleMedium) }
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(96.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        TextButton(onClick = onPlus, enabled = onPlusEnabled) { Text("+", style = MaterialTheme.typography.titleMedium) }
    }
}

// endregion

// region 设置首页

/** 设置首页：居中标题 + 图标导航（数据管理/功能/播放/界面语言/日志）+ 版本与推送信息 */
@Composable
private fun SettingsHome(app: AppViewModel, open: (SettingsPage) -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
    ) {
        Text(
            text = "设置",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        )
        NavIconRow(Icons.Filled.Storage, "数据管理") { open(SettingsPage.DATA_MANAGE) }
        NavIconRow(Icons.Filled.Settings, "功能设置") { open(SettingsPage.FEATURE) }
        NavIconRow(Icons.Filled.SmartDisplay, "播放设置") { open(SettingsPage.PLAY) }
        NavIconRow(Icons.Filled.Palette, "界面 / 语言") { open(SettingsPage.UI_LANG) }
        NavIconRow(Icons.Filled.Description, "日志") { open(SettingsPage.LOG) }
        Spacer(Modifier.height(16.dp))
        Text(
            text = "当前版本 v${Constants.APP_VERSION}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        Text(
            text = SpiderProxyServer.pushAddress()?.let { "局域网推送：$it" } ?: "局域网推送：本地代理未启动",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
        )
    }
}

/** 首页导航行：图标 + 标题 + 右箭头 */
@Composable
private fun NavIconRow(icon: ImageVector, title: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.width(14.dp))
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// endregion

// region 数据管理

/** 数据管理：订阅/视频源/直播源/扩展/存储路径/清理数据 */
@Composable
private fun DataManagePage(app: AppViewModel, open: (SettingsPage) -> Unit) {
    var showClean by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        NavIconRow(Icons.Filled.Subscriptions, "订阅") { open(SettingsPage.SUBSCRIBE) }
        NavIconRow(Icons.Filled.VideoLibrary, "视频源") { open(SettingsPage.VIDEO_SOURCE) }
        NavIconRow(Icons.Filled.LiveTv, "直播源") { open(SettingsPage.TV_SOURCE) }
        DesktopPlaceholderRow(Icons.Filled.Extension, "推荐扩展", app)
        DesktopPlaceholderRow(Icons.Filled.Cloud, "网盘工具扩展", app)
        DesktopPlaceholderRow(Icons.Filled.Forum, "弹幕扩展", app)
        DesktopPlaceholderRow(Icons.Filled.Terminal, "环境变量", app)
        DesktopPlaceholderRow(Icons.Filled.ManageAccounts, "多平台账户", app)
        NavIconRow(Icons.Filled.Folder, "自定义存储路径") { open(SettingsPage.STORAGE_PATH) }
        NavIconRow(Icons.Filled.CleaningServices, "清理数据") { showClean = true }
    }
    if (showClean) {
        CleanDataDialog(app, onDismiss = { showClean = false })
    }
}

/** 桌面端暂未支持的扩展项：点击提示 */
@Composable
private fun DesktopPlaceholderRow(icon: ImageVector, title: String, app: AppViewModel) {
    NavIconRow(icon, title) { app.showMessage("「$title」桌面端暂未支持") }
}

/** 清理数据弹窗：图片缓存 / 播放历史 / 收藏 */
@Composable
private fun CleanDataDialog(app: AppViewModel, onDismiss: () -> Unit) {
    val graph = LocalAppGraph.current
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("清理数据") },
        text = {
            Column {
                Text(
                    "选择要清理的数据：",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = {
                    com.shiping.desktop.ui.common.clearImageCache()
                    app.showMessage("图片缓存已清空")
                    onDismiss()
                }) { Text("清空图片缓存") }
                TextButton(onClick = {
                    scope.launch {
                        graph.playHistoryRepository.clear()
                        app.showMessage("播放历史已清空")
                    }
                    onDismiss()
                }) { Text("清空播放历史") }
                TextButton(onClick = {
                    scope.launch {
                        graph.favoriteRepository.clear()
                        app.showMessage("收藏已清空")
                    }
                    onDismiss()
                }) { Text("清空收藏") }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

// endregion

// region 源管理三级页（订阅/视频源/直播源）

/** 订阅页：TVBox 配置导入（内置订阅源预设一键导入） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SubscribePage(app: AppViewModel) {
    val graph = LocalAppGraph.current
    val scope = rememberCoroutineScope()
    var configUrl by remember { mutableStateOf("") }
    var importing by remember { mutableStateOf(false) }
    var warehouses by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }

    /** 统一导入入口：拉取配置 → 提示结果，多仓时展开子配置列表 */
    val runImport: (String, String) -> Unit = { url, label ->
        if (!importing) {
            importing = true
            scope.launch {
                val result = graph.tvBoxImporterService.import(url, label)
                app.showMessage(result.message)
                warehouses = result.warehouses
                importing = false
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextInputRow(
            label = "配置地址",
            value = configUrl,
            onValueChange = { configUrl = it },
            placeholder = "https://.../config.json",
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    if (configUrl.isBlank()) return@Button
                    runImport(configUrl.trim(), "")
                },
                enabled = configUrl.isNotBlank() && !importing,
            ) { Text(if (importing) "正在后台导入…" else "拉取导入") }
            // 扫码导入（Android 摄像头识别 TVBox 配置二维码；桌面提示不支持）
            OutlinedButton(
                onClick = {
                    if (!launchConfigScanner()) app.showMessage("当前平台不支持扫码导入")
                },
                enabled = !importing,
            ) { Text("扫码导入") }
        }
        Text("内置订阅源（点击直接导入）", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for ((name, url) in Constants.SUBSCRIPTION_PRESETS) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable(enabled = !importing) { runImport(url, name) }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(
                        name,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                    )
                }
            }
        }
        if (warehouses.isNotEmpty()) {
            Text("发现多仓配置，选择子配置：", style = MaterialTheme.typography.bodyMedium)
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(warehouses, key = { it.second }) { (name, url) ->
                    Text(
                        name,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                warehouses = emptyList()
                                runImport(url, name)
                            }
                            .padding(vertical = 8.dp, horizontal = 8.dp),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

/** 视频源页：顶栏齿轮菜单（管理/添加单个源）+ CMS/爬虫双 Tab + 管理模式 + 添加/编辑源 */
@Composable
private fun VideoSourcePage(app: AppViewModel, onBack: () -> Unit) {
    val graph = LocalAppGraph.current
    val scope = rememberCoroutineScope()
    val sources by graph.apiSourceRepository.sources.collectAsState()
    var tabIndex by remember { mutableIntStateOf(0) }
    var manageMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(emptySet<Long>()) }
    var editing by remember { mutableStateOf<ApiSourceEntity?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    val spider = tabIndex == 1
    val filtered = remember(sources, spider) { sources.filter { (it.siteType == 3) == spider } }
    val currentId = graph.apiSourceRepository.currentApiId

    Column(modifier = Modifier.fillMaxSize()) {
        SettingsTopBar(
            title = when {
                showAdd -> "批量添加源"
                editing != null -> "编辑源"
                else -> "视频源"
            },
            onBack = {
                when {
                    showAdd -> showAdd = false
                    editing != null -> editing = null
                    manageMode -> {
                        manageMode = false
                        selectedIds = emptySet()
                    }
                    else -> onBack()
                }
            },
        ) {
            when {
                showAdd || editing != null -> Unit
                manageMode -> {
                    IconButton(onClick = {
                        selectedIds =
                            if (filtered.isNotEmpty() && selectedIds.size == filtered.size) emptySet() else filtered.map { it.id }.toSet()
                    }) {
                        Icon(
                            imageVector = if (filtered.isNotEmpty() && selectedIds.size == filtered.size) {
                                Icons.Filled.CheckBox
                            } else {
                                Icons.Filled.CheckBoxOutlineBlank
                            },
                            contentDescription = "全选",
                        )
                    }
                    IconButton(onClick = {
                        scope.launch {
                            graph.apiSourceRepository.deleteByIds(selectedIds.toList())
                            selectedIds = emptySet()
                        }
                    }) { Icon(Icons.Filled.Delete, contentDescription = "删除") }
                    IconButton(onClick = {
                        manageMode = false
                        selectedIds = emptySet()
                    }) { Icon(Icons.Filled.Close, contentDescription = "退出管理") }
                }
                else -> {
                    if (!spider) {
                        IconButton(onClick = { showAdd = true }) {
                            Icon(Icons.Filled.Add, contentDescription = "添加源")
                        }
                    }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = "设置")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("管理") },
                            onClick = {
                                menuOpen = false
                                manageMode = true
                                selectedIds = emptySet()
                            },
                        )
                        if (!spider) {
                            DropdownMenuItem(
                                text = { Text("批量添加") },
                                onClick = {
                                    menuOpen = false
                                    showAdd = true
                                },
                            )
                        }
                    }
                }
            }
        }
        val editTarget = editing
        when {
            showAdd -> AddSourceForm(app) { showAdd = false }
            editTarget != null -> EditSourceForm(app, editTarget) { editing = null }
            else -> {
                if (manageMode) {
                    ManageHintCapsules(app, filtered, selectedIds, spider)
                }
                TabRow(selectedTabIndex = tabIndex) {
                    listOf("CMS 源", "爬虫源").forEachIndexed { index, title ->
                        Tab(selected = tabIndex == index, onClick = { tabIndex = index }, text = { Text(title) })
                    }
                }
                if (filtered.isEmpty()) {
                    Text(
                        "暂无源，可在「订阅」页通过 TVBox 配置拉取",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(12.dp),
                    )
                }
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(filtered, key = { it.id }) { source ->
                        if (manageMode) {
                            ManageSourceRow(
                                isCurrent = source.id == currentId,
                                name = source.name,
                                checked = source.id in selectedIds,
                                onCheckChange = { checked ->
                                    selectedIds = if (checked) selectedIds + source.id else selectedIds - source.id
                                },
                                onMoveUp = { scope.launch { graph.apiSourceRepository.moveUp(source.id) } },
                            )
                        } else {
                            VideoSourceRow(
                                name = source.name,
                                detail = if (spider) "${source.spiderKey} · jar=${source.jarUrl.take(40)}" else source.url,
                                locked = source.locked,
                                playRecord = source.playRecord,
                                searchable = source.searchable,
                                isCurrent = source.id == currentId,
                                onClick = { editing = source },
                                onToggleSearch = {
                                    scope.launch {
                                        graph.apiSourceRepository.setSearchable(listOf(source.id), !source.searchable)
                                    }
                                },
                                onTogglePlayRecord = {
                                    scope.launch {
                                        graph.apiSourceRepository.setPlayRecord(listOf(source.id), !source.playRecord)
                                    }
                                },
                                onToggleLock = {
                                    scope.launch {
                                        graph.apiSourceRepository.setLocked(listOf(source.id), !source.locked)
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 源行（普通模式）：蓝点 + 名称 + 地址 + 搜索/播放记录/锁定三开关图标，点击行进入编辑 */
@Composable
private fun VideoSourceRow(
    name: String,
    detail: String,
    locked: Boolean,
    playRecord: Boolean,
    searchable: Boolean,
    isCurrent: Boolean,
    onClick: () -> Unit,
    onToggleSearch: () -> Unit,
    onTogglePlayRecord: () -> Unit,
    onToggleLock: () -> Unit,
) {
    val disabledTint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (isCurrent) MaterialTheme.colorScheme.primary else Color.Transparent),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onToggleSearch) {
                Icon(
                    imageVector = if (searchable) Icons.Filled.Search else Icons.Filled.SearchOff,
                    contentDescription = if (searchable) "参与搜索" else "不参与搜索",
                    tint = if (searchable) MaterialTheme.colorScheme.primary else disabledTint,
                    modifier = Modifier.size(20.dp),
                )
            }
            IconButton(onClick = onTogglePlayRecord) {
                Icon(
                    imageVector = Icons.Filled.History,
                    contentDescription = if (playRecord) "播放记录已开启" else "播放记录已关闭",
                    tint = if (playRecord) MaterialTheme.colorScheme.primary else disabledTint,
                    modifier = Modifier.size(20.dp),
                )
            }
            IconButton(onClick = onToggleLock) {
                Icon(
                    imageVector = if (locked) Icons.Filled.Lock else Icons.Filled.LockOpen,
                    contentDescription = if (locked) "已锁定" else "未锁定",
                    tint = if (locked) MaterialTheme.colorScheme.primary else disabledTint,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Text(
            text = detail,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 14.dp),
        )
    }
}

/** 源行（管理模式）：当前源蓝点 + 名称 + 上移 + 复选框 */
@Composable
private fun ManageSourceRow(
    isCurrent: Boolean,
    name: String,
    checked: Boolean,
    onCheckChange: (Boolean) -> Unit,
    onMoveUp: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(if (isCurrent) MaterialTheme.colorScheme.primary else Color.Transparent),
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onMoveUp) { Icon(Icons.Filled.ArrowUpward, contentDescription = "上移") }
        Checkbox(checked = checked, onCheckedChange = onCheckChange)
    }
}

/** 管理模式操作胶囊：历史记录 / 锁定 / 检测可用（CMS/爬虫两 Tab 通用，作用于选中源，未选则全部） */
@Composable
private fun ManageHintCapsules(
    app: AppViewModel,
    targets: List<ApiSourceEntity>,
    selectedIds: Set<Long>,
    spider: Boolean,
) {
    val graph = LocalAppGraph.current
    val scope = rememberCoroutineScope()
    val ids = if (selectedIds.isEmpty()) targets.map { it.id } else selectedIds.toList()
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        ManageCapsule("点击开启历史记录") {
            scope.launch {
                val first = targets.firstOrNull { it.id == ids.firstOrNull() }
                val value = !(first?.playRecord ?: true)
                graph.apiSourceRepository.setPlayRecord(ids, value)
                app.showMessage("已${if (value) "开启" else "关闭"} ${ids.size} 个源的播放记录")
            }
        }
        ManageCapsule("点击锁定源") {
            scope.launch {
                val first = targets.firstOrNull { it.id == ids.firstOrNull() }
                val value = !(first?.locked ?: false)
                graph.apiSourceRepository.setLocked(ids, value)
                app.showMessage("已${if (value) "锁定" else "解锁"} ${ids.size} 个源")
            }
        }
        ManageCapsule("检测源是否可用") {
            scope.launch {
                if (spider) {
                    app.showMessage("爬虫源为站点地址，请通过播放验证可用性")
                } else {
                    val usable = graph.apiSourceRepository.checkUsable(ids)
                    app.showMessage("检测完成：可用 $usable / 共 ${ids.size} 个")
                }
            }
        }
    }
}

/** 管理模式操作胶囊样式 */
@Composable
private fun ManageCapsule(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

/** 批量添加 CMS 源表单：多行文本（名称,地址 / 名称$地址 / 仅地址）+ 放入首位/上锁/播放记录 */
@Composable
private fun AddSourceForm(app: AppViewModel, onClose: () -> Unit) {
    val graph = LocalAppGraph.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var putFirst by remember { mutableStateOf(false) }
    var locked by remember { mutableStateOf(false) }
    var record by remember { mutableStateOf(true) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        ImmersiveField(
            placeholder = "每行一个源：名称,地址（或 名称\$地址 / 仅地址）",
            value = text,
            onValueChange = { text = it },
            singleLine = false,
            minLines = 8,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            CheckOption("放入首位", putFirst) { putFirst = it }
            CheckOption("上锁", locked) { locked = it }
            CheckOption("播放记录", record) { record = it }
        }
        Spacer(modifier = Modifier.height(20.dp))
        Button(
            onClick = {
                if (text.isBlank()) {
                    app.showMessage("请输入源内容")
                    return@Button
                }
                scope.launch {
                    val count = graph.apiSourceRepository
                        .batchInsertCms(text, putFirst, locked, record)
                    app.showMessage(if (count > 0) "已添加 $count 个源" else "未解析到有效源")
                    if (count > 0) onClose()
                }
            },
            shape = RoundedCornerShape(50),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("批量添加") }
    }
}

/** 编辑源表单：名称/备注/UA + 上锁/播放记录 + 设为当前源/保存 */
@Composable
private fun EditSourceForm(app: AppViewModel, source: ApiSourceEntity, onClose: () -> Unit) {
    val graph = LocalAppGraph.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(source.name) }
    var note by remember { mutableStateOf(source.note) }
    var userAgent by remember { mutableStateOf(source.userAgent) }
    var locked by remember { mutableStateOf(source.locked) }
    var record by remember { mutableStateOf(source.playRecord) }
    var searchable by remember { mutableStateOf(source.searchable) }
    var isCurrent by remember { mutableStateOf(graph.apiSourceRepository.currentApiId == source.id) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        FormLabel("名称", isCurrent)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(if (isCurrent) MaterialTheme.colorScheme.primary else Color.Transparent),
            )
            Spacer(modifier = Modifier.width(6.dp))
            ImmersiveField("源名称", name, onValueChange = { name = it })
        }
        Spacer(modifier = Modifier.height(14.dp))
        Text("备注", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ImmersiveField("备注", note, onValueChange = { note = it })
        Spacer(modifier = Modifier.height(14.dp))
        Text("userAgent", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ImmersiveField("userAgent", userAgent, onValueChange = { userAgent = it })
        Spacer(modifier = Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            CheckOption("上锁", locked) { locked = it }
            CheckOption("播放记录", record) { record = it }
            CheckOption("参与搜索", searchable) { searchable = it }
        }
        Spacer(modifier = Modifier.height(20.dp))
        if (!isCurrent) {
            OutlinedButton(
                onClick = {
                    graph.apiSourceRepository.setCurrentApiId(source.id)
                    isCurrent = true
                    app.showMessage("已设为当前源")
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("设为当前源") }
            Spacer(modifier = Modifier.height(8.dp))
        }
        Button(
            onClick = {
                if (name.isBlank()) {
                    app.showMessage("源名称不能为空")
                    return@Button
                }
                scope.launch {
                    graph.apiSourceRepository.update(
                        source.copy(
                            name = name.trim(),
                            note = note.trim(),
                            userAgent = userAgent.trim(),
                            locked = locked,
                            playRecord = record,
                            searchable = searchable,
                        ),
                    )
                    app.showMessage("已保存")
                    onClose()
                }
            },
            shape = RoundedCornerShape(50),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("保存") }
        Spacer(modifier = Modifier.height(20.dp))
        Text("源信息", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(6.dp))
        SelectionContainer {
            Text(
                text = buildString {
                    appendLine("ID：${source.id}")
                    appendLine("类型：${if (source.siteType == 3) "爬虫源" else "CMS 源"}")
                    appendLine("地址：${source.url}")
                    appendLine("排序：${source.sortOrder}")
                    appendLine("启用：${if (source.enabled) "是" else "否"}")
                    appendLine("锁定：${if (source.locked) "是" else "否"}")
                    appendLine("播放记录：${if (source.playRecord) "开启" else "关闭"}")
                    appendLine("参与搜索：${if (source.searchable) "是" else "否"}")
                    if (source.jarUrl.isNotBlank()) appendLine("jar：${source.jarUrl}")
                    if (source.spiderKey.isNotBlank()) appendLine("spider：${source.spiderKey}")
                    if (source.ext.isNotBlank()) appendLine("ext：${source.ext}")
                    append("备注：${source.note.ifBlank { "无" }}")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(10.dp),
            )
        }
    }
}

/** 表单字段标签（当前源时高亮） */
@Composable
private fun FormLabel(text: String, highlight: Boolean) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = if (highlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 沉浸式无边框输入框（表单通用，支持多行） */
@Composable
private fun ImmersiveField(
    placeholder: String,
    value: String,
    onValueChange: (String) -> Unit,
    singleLine: Boolean = true,
    minLines: Int = 1,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(placeholder, style = MaterialTheme.typography.bodyLarge) },
        singleLine = singleLine,
        minLines = minLines,
        shape = RoundedCornerShape(10.dp),
        colors = borderlessFieldColors(),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** 沉浸式输入框配色：无边框 + 浅色底 */
@Composable
private fun borderlessFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Color.Transparent,
    unfocusedBorderColor = Color.Transparent,
    disabledBorderColor = Color.Transparent,
    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
)

/** 复选选项（勾选框 + 文字） */
@Composable
private fun CheckOption(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clickable { onChange(!checked) },
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

/** 直播源页：顶栏批量添加/文件导入 + 列表点击编辑 + 删除 */
@Composable
private fun TvSourcePage(app: AppViewModel, onBack: () -> Unit) {
    val graph = LocalAppGraph.current
    val scope = rememberCoroutineScope()
    val sources by graph.tvRepository.sources.collectAsState()
    val currentId = graph.tvRepository.currentTvId()
    var showAdd by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<TvSourceEntity?>(null) }
    val editTarget = editing

    Column(modifier = Modifier.fillMaxSize()) {
        SettingsTopBar(
            title = when {
                showAdd -> "批量添加源"
                editTarget != null -> "编辑源"
                else -> "直播源"
            },
            onBack = {
                when {
                    showAdd -> showAdd = false
                    editTarget != null -> editing = null
                    else -> onBack()
                }
            },
        ) {
            if (!showAdd && editTarget == null) {
                IconButton(onClick = { showAdd = true }) {
                    Icon(Icons.Filled.Add, contentDescription = "批量添加")
                }
                IconButton(onClick = {
                    scope.launch {
                        val path = pickImportTextFile("导入直播源")
                        if (path != null) {
                            val count = graph.tvRepository.importFile(path)
                            app.showMessage(if (count > 0) "已导入 $count 个直播源" else "未解析到有效源")
                        }
                    }
                }) { Icon(Icons.Filled.UploadFile, contentDescription = "导入文件") }
            }
        }
        when {
            showAdd -> BatchAddTvForm(app) { showAdd = false }
            editTarget != null -> EditTvSourceForm(app, editTarget) { editing = null }
            else -> {
                if (sources.isEmpty()) {
                    Text(
                        "暂无直播源，可通过右上角批量添加或导入文件",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(12.dp),
                    )
                }
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(sources, key = { it.id }) { source ->
                        TvSourceRow(
                            name = source.name,
                            detail = source.url,
                            isCurrent = source.id == currentId,
                            onClick = { editing = source },
                            onDelete = { scope.launch { graph.tvRepository.deleteById(source.id) } },
                        )
                    }
                }
            }
        }
    }
}

/** 直播源行：当前源蓝点 + 名称 + 地址 + 删除，点击进入编辑 */
@Composable
private fun TvSourceRow(
    name: String,
    detail: String,
    isCurrent: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (isCurrent) MaterialTheme.colorScheme.primary else Color.Transparent),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "删除",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Text(
            text = detail,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 批量添加直播源表单：多行「名称,url」或「仅url」 */
@Composable
private fun BatchAddTvForm(app: AppViewModel, onClose: () -> Unit) {
    val graph = LocalAppGraph.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        ImmersiveField(
            placeholder = "每行一个源：名称,url / 仅url / 名称,url,备注",
            value = text,
            onValueChange = { text = it },
            singleLine = false,
            minLines = 8,
        )
        Spacer(modifier = Modifier.height(20.dp))
        Button(
            onClick = {
                if (text.isBlank()) {
                    app.showMessage("请输入源内容")
                    return@Button
                }
                scope.launch {
                    val count = graph.tvRepository.batchInsert(text)
                    app.showMessage(if (count > 0) "已添加 $count 个直播源" else "未解析到有效源")
                    if (count > 0) onClose()
                }
            },
            shape = RoundedCornerShape(50),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("批量添加") }
    }
}

/** 编辑直播源表单：名称/地址/备注 + 设为当前源/保存 */
@Composable
private fun EditTvSourceForm(app: AppViewModel, source: TvSourceEntity, onClose: () -> Unit) {
    val graph = LocalAppGraph.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(source.name) }
    var url by remember { mutableStateOf(source.url) }
    var note by remember { mutableStateOf(source.note) }
    var isCurrent by remember {
        mutableStateOf(graph.tvRepository.currentTvId() == source.id)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text("名称", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ImmersiveField("名称", name, onValueChange = { name = it })
        Spacer(modifier = Modifier.height(14.dp))
        Text("地址", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ImmersiveField("地址", url, onValueChange = { url = it })
        Spacer(modifier = Modifier.height(14.dp))
        Text("备注", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ImmersiveField("备注", note, onValueChange = { note = it })
        Spacer(modifier = Modifier.height(20.dp))
        if (!isCurrent) {
            OutlinedButton(
                onClick = {
                    graph.prefs.put(PrefsKeys.KEY_CURRENT_TV_ID, source.id)
                    isCurrent = true
                    app.showMessage("已设为当前源")
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("设为当前源") }
            Spacer(modifier = Modifier.height(8.dp))
        }
        Button(
            onClick = {
                if (name.isBlank() || url.isBlank()) {
                    app.showMessage("名称和地址不能为空")
                    return@Button
                }
                scope.launch {
                    graph.tvRepository.update(
                        source.copy(name = name.trim(), url = url.trim(), note = note.trim()),
                    )
                    app.showMessage("已保存")
                    onClose()
                }
            },
            shape = RoundedCornerShape(50),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("保存") }
    }
}

// endregion

// region 功能设置

/** 功能设置：过滤广告 / 播放记录 / 触感 / 代理 / 按键码 */
@Composable
private fun FeatureSettingsPage(app: AppViewModel) {
    val prefs = LocalAppGraph.current.prefs
    var showProxy by remember { mutableStateOf(false) }
    var proxy by remember { mutableStateOf(prefs.getString(PrefsKeys.KEY_HTTP_PROXY)) }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        PrefSwitchRow("过滤切片广告", PrefsKeys.KEY_FILTER_SLICE_AD, false)
        PrefSwitchRow("m3u8 去广告", PrefsKeys.KEY_FILTER_M3U8_AD, false)
        PrefSwitchRow("画中画", PrefsKeys.KEY_PIP_ENABLED, true)
        PrefSwitchRow("播放记录", PrefsKeys.KEY_PLAY_RECORD_ENABLED, true)
        PrefSwitchRow("触感提示", PrefsKeys.KEY_TOUCH_HINT, false)
        SettingsRow("代理", proxy.ifBlank { "未启用" }, onClick = { showProxy = true })
        SettingsRow("按键码", onClick = { app.showMessage("桌面端无需按键码设置") })
    }
    if (showProxy) {
        ProxyDialog(
            current = proxy,
            onSave = {
                prefs.put(PrefsKeys.KEY_HTTP_PROXY, it)
                proxy = it
            },
            onDismiss = { showProxy = false },
        )
    }
}

/** 代理设置弹窗：host:port（对爬虫网络请求生效，重启生效） */
@Composable
private fun ProxyDialog(current: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var value by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("代理") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    placeholder = { Text("host:port，如 127.0.0.1:7890") },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    colors = borderlessFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "留空禁用代理；对爬虫源网络请求生效，重启应用后生效。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(value.trim())
                onDismiss()
            }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

// endregion

// region 播放设置

/** 播放设置：MPV/MDK 内核横幅切换 + MPV 分组（硬解/字幕）+ 播放行为 + 倍速 */
@Composable
private fun PlaySettingsPage(app: AppViewModel) {
    val kernelId by app.kernelId.collectAsState()
    // 内核循环顺序（与播放器内设置一致，media3 为 Android 专属不进桌面循环）
    val kernelOrder = listOf(KernelIds.MPV, KernelIds.VLC, KernelIds.MDK)
    val nextKernelId = kernelOrder[(kernelOrder.indexOf(kernelId).coerceAtLeast(0) + 1) % kernelOrder.size]

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        // 内核胶囊：只显示当前内核，点击循环切换 MPV/VLC/MDK（与播放器内设置一致，下次打开播放器生效）
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.primaryContainer)
                .clickable { app.switchKernel(nextKernelId) }
                .padding(horizontal = 12.dp, vertical = 12.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Memory,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = kernelId.uppercase(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "点击切换",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        if (kernelId == KernelIds.MPV) {
            // MPV 专属：硬解/特效字幕/字幕样式（MDK 页不展示，与设计稿一致）
            PrefSwitchRow("硬解码", PrefsKeys.KEY_MPV_HWDEC, true)
            PrefSwitchRow("特效字幕", PrefsKeys.KEY_MPV_FX_SUBTITLE, true)
            ColorCycleRow("字幕颜色", PrefsKeys.KEY_SUB_COLOR, Constants.SUB_COLORS, "#FFFFFF")
            ColorCycleRow("字幕背景颜色", PrefsKeys.KEY_SUB_BACK_COLOR, Constants.SUB_BACK_COLORS, "#000000")
            PercentPrefRow("字幕背景色透明度", PrefsKeys.KEY_SUB_BACK_ALPHA, 40, 0..100, 10)
            PercentPrefRow("字幕大小", PrefsKeys.KEY_SUB_SIZE, 100, 50..200, 10)
        }
        VideoScaleRow()
        PrefSwitchRow("显示上一集下一集按钮", PrefsKeys.KEY_SHOW_PREV_NEXT, true)
        PrefSwitchRow("进入后台暂停播放", PrefsKeys.KEY_PAUSE_ON_BACKGROUND, false)
        PrefSwitchRow("显示总时长", PrefsKeys.KEY_SHOW_TOTAL_TIME, true)
        PrefSwitchRow("显示松手取消调整进度", PrefsKeys.KEY_SHOW_SEEK_CANCEL, true)
        SpeedPrefRow("全局倍速设置", PrefsKeys.KEY_GLOBAL_SPEED, Constants.PLAYBACK_SPEEDS, Constants.PLAYBACK_SPEEDS.indexOf(1.0f))
        SeekStepPrefRow()
        SpeedPrefRow("长按倍速", PrefsKeys.KEY_LONG_PRESS_SPEED, Constants.LONG_PRESS_SPEEDS, 0)
        Spacer(Modifier.height(16.dp))
    }
}

/** 画面比例行：点击循环切换 原始尺寸/裁剪铺满/拉伸画面（mpv 与 MDK 内核均生效） */
@Composable
private fun VideoScaleRow() {
    val prefs = LocalAppGraph.current.prefs
    val options = listOf("fit" to "原始尺寸", "crop" to "裁剪铺满", "stretch" to "拉伸画面")
    var scale by remember { mutableStateOf(prefs.getString(PrefsKeys.KEY_VIDEO_SCALE, "fit")) }
    val current = options.firstOrNull { it.first == scale } ?: options.first()
    SettingsRow(
        title = "画面：${current.second}",
        onClick = {
            val next = options[(options.indexOf(current) + 1).mod(options.size)]
            scale = next.first
            prefs.put(PrefsKeys.KEY_VIDEO_SCALE, next.first)
        },
    )
}

/** 颜色循环行：点击切换下一档颜色，行尾圆形色块预览当前颜色 */
@Composable
private fun ColorCycleRow(title: String, prefKey: String, colors: List<String>, defaultHex: String) {
    val prefs = LocalAppGraph.current.prefs
    var hex by remember { mutableStateOf(prefs.getString(prefKey, defaultHex).uppercase()) }
    SettingsRow(
        title = title,
        onClick = {
            val next = colors[(colors.indexOf(hex) + 1).mod(colors.size)]
            hex = next
            prefs.put(prefKey, next)
        },
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(parseHexColor(hex))
                .border(1.dp, MaterialTheme.colorScheme.onSurfaceVariant, CircleShape),
        )
    }
}

/** #RRGGBB 字符串转 Compose Color（非法值回退白色） */
private fun parseHexColor(hex: String): Color {
    val raw = hex.removePrefix("#")
    if (raw.length != 6) return Color.White
    val value = raw.toLongOrNull(16) ?: return Color.White
    return Color(0xFF000000L or value)
}

// endregion

// region 界面 / 语言

/** 界面/语言：主题 / 语言 / Tab 设置 / 字体缩放 / 字体 */
@Composable
private fun InterfaceLanguagePage(app: AppViewModel, open: (SettingsPage) -> Unit) {
    val modeName = when (ThemeState.mode) {
        ThemeState.MODE_LIGHT -> "浅色"
        ThemeState.MODE_DARK -> "深色"
        else -> "跟随系统"
    }
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SettingsRow(
            title = "主题",
            onClick = { open(SettingsPage.THEME) },
        ) {
            Text(
                "$modeName · ${ThemeState.colorName}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SettingsRow("语言") {
            Text(
                "简体中文",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SettingsRow("Tab 设置", onClick = { open(SettingsPage.TABS) })
        PercentPrefRow(
            "字体缩放比例",
            PrefsKeys.KEY_FONT_SCALE,
            100,
            80..140,
            10,
        ) { percent -> UiScale.update(percent / 100f) }
        SettingsRow("字体") {
            Text(
                "系统默认",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 主题页：模式（跟随系统/浅色/深色）+ 主题色 7 档，点击即时切换 */
@Composable
private fun ThemePage(app: AppViewModel) {
    val prefs = LocalAppGraph.current.prefs
    val modes = listOf(
        Triple(ThemeState.MODE_SYSTEM, Icons.Filled.DesktopWindows, "跟随系统"),
        Triple(ThemeState.MODE_LIGHT, Icons.Filled.LightMode, "浅色"),
        Triple(ThemeState.MODE_DARK, Icons.Filled.DarkMode, "深色"),
    )
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text(
            "主题",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        modes.forEach { (mode, icon, label) ->
            SettingsRow(
                title = label,
                leading = {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(22.dp),
                    )
                },
                onClick = {
                    ThemeState.update(mode)
                    prefs.put(PrefsKeys.KEY_THEME_MODE, mode)
                },
            ) {
                if (ThemeState.mode == mode) {
                    Icon(Icons.Filled.Check, contentDescription = "已选择", tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
        Text(
            "颜色",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        ThemeColors.forEach { item ->
            SettingsRow(
                title = item.name,
                leading = {
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(item.primary),
                    )
                },
                onClick = {
                    ThemeState.updateColor(item.name)
                    prefs.put(PrefsKeys.KEY_THEME_COLOR, item.name)
                },
            ) {
                if (ThemeState.colorName == item.name) {
                    Icon(Icons.Filled.Check, contentDescription = "已选择", tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

/** Tab 设置页：侧边栏 Tab 显隐开关（首页/设置固定显示） */
@Composable
private fun TabsSettingPage(app: AppViewModel) {
    val prefs = LocalAppGraph.current.prefs
    val sidebarTabs = listOf("首页", "搜索", "直播", "收藏", "历史", "设置")
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        sidebarTabs.forEach { label ->
            val fixed = label !in hideableTabs
            val visible = label !in MainTabsState.hidden
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.DragHandle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (fixed) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = visible,
                    enabled = !fixed,
                    onCheckedChange = { checked ->
                        MainTabsState.setHidden(label, !checked)
                        prefs.put(PrefsKeys.KEY_HIDE_TABS, MainTabsState.hidden.joinToString(","))
                    },
                )
            }
        }
    }
}

// endregion

// region 其他三级页

/** 日志页（对照 Govin 移动端）：级别筛选（V/D/I/W/E 多选）+ 跟随滚动 + 复制/清空 */
@Composable
private fun LogPage() {
    var logs by remember { mutableStateOf(snapshotLogEntries()) }
    // 选中的日志级别（对照移动端 V/D/I/W/E 圆片，可多选过滤）
    var enabledLevels by remember { mutableStateOf(setOf('V', 'D', 'I', 'W', 'E')) }
    var follow by remember { mutableStateOf(true) }
    val listState = rememberLazyListState()
    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            logs = snapshotLogEntries()
        }
    }
    val filtered = remember(logs, enabledLevels) { logs.filter { it.level in enabledLevels } }
    // 跟随：新日志到达时贴底
    LaunchedEffect(filtered.size, follow) {
        if (follow && filtered.isNotEmpty()) listState.scrollToItem(filtered.lastIndex)
    }
    Column(
        modifier = Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            listOf('V', 'D', 'I', 'W', 'E').forEach { level ->
                FilterChip(
                    selected = level in enabledLevels,
                    onClick = {
                        enabledLevels = if (level in enabledLevels) enabledLevels - level else enabledLevels + level
                    },
                    label = { Text("$level") },
                )
            }
            Spacer(Modifier.weight(1f))
            Text("跟随", style = MaterialTheme.typography.labelMedium)
            Switch(checked = follow, onCheckedChange = { follow = it })
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "共 ${filtered.size} 条",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = {
                val text = filtered.joinToString("\n") { it.text }
                copyTextToClipboard(text)
            }) { Text("复制") }
            TextButton(onClick = {
                clearLogs()
                logs = emptyList()
            }) { Text("清空") }
        }
        // 深色日志区：按级别着色（对照移动端 绿D / 白I / 黄W / 红E / 灰V）
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(FixedPalette.LOG_BACKGROUND),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(8.dp),
            ) {
                items(filtered) { entry ->
                    Text(
                        text = entry.text,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = when (entry.level) {
                            'V' -> FixedPalette.LOG_LEVEL_V
                            'D' -> FixedPalette.LOG_LEVEL_D
                            'I' -> FixedPalette.LOG_LEVEL_I
                            'W' -> FixedPalette.LOG_LEVEL_W
                            else -> FixedPalette.LOG_LEVEL_E
                        },
                    )
                }
            }
        }
    }
}

// endregion
