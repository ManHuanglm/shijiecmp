package com.shiping.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.shiping.desktop.data.model.ApiSourceEntity
import com.shiping.desktop.data.model.Category
import com.shiping.desktop.ui.common.CategoryTagFlow
import com.shiping.desktop.ui.common.sheetBodyWidth

/**
 * 首页顶栏底部弹层：源列表（标题点击）/ 最近使用源（图标点击）/ 分类拖动排序。
 * 参考移动端交互：标题行 + 功能按钮 + 源行列表（圆点 + 名称，选中高亮）。
 */

/** 源列表弹层：当前源 + CMS/爬虫分组切换 + 5 个功能入口（布局切换/分类管理/云盘工具/加锁源/源管理） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SourceListSheet(
    sources: List<ApiSourceEntity>,
    currentSourceId: Long?,
    lockedVisible: Boolean,
    posterLayout: PosterLayout,
    onSelect: (ApiSourceEntity) -> Unit,
    onDismiss: () -> Unit,
    onCyclePosterLayout: () -> Unit,
    onOpenCategoryManager: () -> Unit,
    onOpenCloudTool: () -> Unit,
    onOpenSourceManage: () -> Unit,
    onLockClick: () -> Unit,
) {
    // CMS/爬虫分组切换显示，默认跟随当前源类型；加锁源仅在解锁后显示
    var showSpider by remember {
        mutableStateOf(sources.firstOrNull { it.id == currentSourceId }?.siteType == 3)
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .sheetBodyWidth()
                .padding(horizontal = 16.dp),
        ) {
            // 头部：标题 + 5 个功能按钮
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "源列表",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                SheetAction(
                    icon = posterLayout.icon,
                    label = "海报布局：${posterLayout.label}",
                    onClick = onCyclePosterLayout,
                )
                SheetAction(Icons.Outlined.Category, "分类管理", onClick = onOpenCategoryManager)
                SheetAction(Icons.Outlined.Cloud, "云盘工具", onClick = onOpenCloudTool)
                SheetAction(
                    icon = if (lockedVisible) Icons.Outlined.LockOpen else Icons.Outlined.Lock,
                    label = if (lockedVisible) "隐藏加锁源" else "显示加锁源",
                    tinted = lockedVisible,
                    onClick = onLockClick,
                )
                SheetAction(Icons.Outlined.VideoLibrary, "源列表管理", onClick = onOpenSourceManage)
            }
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = !showSpider,
                    onClick = { showSpider = false },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                ) { Text("CMS源") }
                SegmentedButton(
                    selected = showSpider,
                    onClick = { showSpider = true },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                ) { Text("爬虫源") }
            }
            Text(
                text = sources.firstOrNull { it.id == currentSourceId }?.name?.let { "当前源：$it" } ?: "添加源后点我切换",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            val groupSources = sources.filter { (it.siteType == 3) == showSpider }
            val displaySources = groupSources.filter { it.locked == lockedVisible }
            // 弹层打开/分组切换时自动定位到当前源所在行
            val listState = rememberLazyListState()
            LaunchedEffect(showSpider, lockedVisible) {
                val index = displaySources.indexOfFirst { it.id == currentSourceId }
                if (index >= 0) listState.scrollToItem(index)
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(max = 460.dp),
            ) {
                if (displaySources.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier.fillMaxWidth().height(96.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = when {
                                    lockedVisible -> "暂无加锁源"
                                    showSpider -> "暂无爬虫源"
                                    else -> "暂无CMS源"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                items(displaySources, key = { it.id }) { source ->
                    SourceRow(
                        name = source.name,
                        selected = source.id == currentSourceId,
                        onClick = { onSelect(source) },
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/**
 * 加锁源查看密码框：isSetMode=true 首次设置密码（带确认字段），false 验证密码。
 * onSubmit 返回 false 表示密码错误，弹层内提示；设置/验证成功由调用方关闭弹层。
 */
@Composable
internal fun LockPasswordDialog(
    isSetMode: Boolean,
    onSubmit: (String) -> Boolean,
    onDismiss: () -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isSetMode) "设置加锁源密码" else "输入加锁源密码") },
        text = {
            Column {
                Text(
                    text = if (isSetMode) "首次使用，请设置查看加锁源的密码" else "输入密码以显示加锁源列表",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        error = null
                    },
                    label = { Text("密码") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    isError = error != null,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                if (isSetMode) {
                    OutlinedTextField(
                        value = confirmPassword,
                        onValueChange = {
                            confirmPassword = it
                            error = null
                        },
                        label = { Text("确认密码") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        isError = error != null,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
                error?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = password.isNotBlank() && (!isSetMode || confirmPassword.isNotBlank()),
                onClick = {
                    when {
                        isSetMode && password != confirmPassword -> error = "两次输入的密码不一致"
                        onSubmit(password) -> onDismiss()
                        else -> error = "密码错误"
                    }
                },
            ) { Text(if (isSetMode) "设置" else "确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** 最近使用源弹层：按使用时间倒序，点击快速切换 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RecentSourcesSheet(
    recentSources: List<ApiSourceEntity>,
    currentSourceId: Long?,
    onSelect: (ApiSourceEntity) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .sheetBodyWidth()
                .padding(horizontal = 16.dp),
        ) {
            Text(
                text = "最近使用",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            if (recentSources.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().height(120.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "暂无使用记录，切换源后显示",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(max = 460.dp)) {
                    items(recentSources, key = { it.id }) { source ->
                        SourceRow(
                            name = source.name,
                            selected = source.id == currentSourceId,
                            onClick = { onSelect(source) },
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** 弹层头部的功能按钮（图标 + contentDescription；tinted 表示功能激活态） */
@Composable
private fun SheetAction(icon: ImageVector, label: String, tinted: Boolean = false, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (tinted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 源行：状态圆点 + 名称，选中行高亮（源列表/历史源弹层共用） */
@Composable
private fun SourceRow(name: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(16.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 分类管理弹层：「展示分类」点标签隐藏、长按标签拖动排序，「隐藏分类」点标签恢复；
 * 「展示分类」标题右侧筛选图标进「屏蔽分类」（密码保护）。大屏内容取窗口宽一半。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryManagerSheet(
    categories: List<Category>,
    hiddenCategoryIds: Set<Int>,
    onHide: (Int) -> Unit,
    onShow: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    onOpenBlockedManager: () -> Unit,
    onDismiss: () -> Unit,
) {
    val visibleNames = categories.filter { it.typeId !in hiddenCategoryIds }.map { it.typeName }
    val hiddenNames = categories.filter { it.typeId in hiddenCategoryIds }.map { it.typeName }
    // 标签流索引是「未隐藏子序列」：换位时映射回全量分类索引再回调
    val visibleCategories = categories.filter { it.typeId !in hiddenCategoryIds }
    val moveVisible: (Int, Int) -> Unit = { from, to ->
        val fromCategory = visibleCategories.getOrNull(from)
        val toCategory = visibleCategories.getOrNull(to)
        if (fromCategory != null && toCategory != null) {
            onMove(categories.indexOf(fromCategory), categories.indexOf(toCategory))
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .sheetBodyWidth()
                .padding(horizontal = 16.dp),
        ) {
            Text(
                text = "分类管理",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "点击标签隐藏/恢复 · 长按标签拖动排序",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "展示分类",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                // 屏蔽分类入口（图2）：进入密码保护的排除分类管理页
                IconButton(onClick = onOpenBlockedManager) {
                    Icon(
                        imageVector = Icons.Outlined.FilterList,
                        contentDescription = "屏蔽分类",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            CategoryTagFlow(
                names = visibleNames,
                dimmed = false,
                onClick = { name -> categories.firstOrNull { it.typeName == name }?.let { onHide(it.typeId) } },
                emptyHint = "全部分类均已隐藏",
                onMove = moveVisible,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "隐藏分类",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            CategoryTagFlow(
                names = hiddenNames,
                dimmed = true,
                onClick = { name -> categories.firstOrNull { it.typeName == name }?.let { onShow(it.typeId) } },
                emptyHint = "暂无隐藏分类",
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}
