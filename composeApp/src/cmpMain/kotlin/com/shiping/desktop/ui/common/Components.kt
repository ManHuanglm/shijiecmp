package com.shiping.desktop.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.shiping.desktop.data.model.Vod

/** Tab 下划线粗细 */
private val TAB_INDICATOR_THICKNESS = 3.dp

/** Tab 下划线与文本的间距 */
private val TAB_INDICATOR_GAP = 2.dp

/** 通用确认对话框（删除/清空等操作） */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = { onConfirm(); onDismiss() }) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** 视频卡片：封面 + 名称 + 角标备注，点击回调；[portraitRatio] 封面宽高比（3:4=0.75、4:3≈1.33） */
@Composable
fun VideoCard(
    vod: Vod,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    footer: (@Composable () -> Unit)? = null,
    portraitRatio: Float = 0.75f,
) {
    Column(modifier = modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick)) {
        Box(modifier = Modifier.fillMaxWidth().aspectRatio(portraitRatio)) {
            NetImage(
                url = vod.safePic,
                fallbackText = vod.vodName,
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)),
            )
            if (vod.vodRemarks.isNotBlank()) {
                Text(
                    text = vod.vodRemarks,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.85f))
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = vod.vodName.ifBlank { "未知片名" },
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        footer?.invoke()
    }
}

/** 加载中占位 */
@Composable
fun LoadingView(message: String = "加载中…", modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(12.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium)
    }
}

/** 错误态占位（带重试按钮） */
@Composable
fun ErrorView(message: String, modifier: Modifier = Modifier, onRetry: (() -> Unit)? = null) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        if (onRetry != null) {
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = onRetry) { Text("重试") }
        }
    }
}

/** 空态占位 */
@Composable
fun EmptyView(message: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * 底部弹层内容宽度修饰：大屏（可用宽 ≥ 720dp，桌面/平板）内容取约束宽一半并水平居中，窄屏铺满。
 * 纯 layout 实现，用于 ModalBottomSheet 内容根组件。
 */
fun Modifier.sheetBodyWidth(): Modifier = layout { measurable, constraints ->
    val targetWidth = if (constraints.maxWidth >= SHEET_HALF_WIDTH_THRESHOLD.roundToPx()) {
        constraints.maxWidth / 2
    } else {
        constraints.maxWidth
    }
    val placeable = measurable.measure(Constraints(maxWidth = targetWidth))
    layout(constraints.maxWidth, placeable.height) {
        placeable.placeRelative(x = (constraints.maxWidth - placeable.width) / 2, y = 0)
    }
}

/** 大屏弹层收窄阈值：可用宽度达到该值按一半宽度展示 */
private val SHEET_HALF_WIDTH_THRESHOLD = 720.dp

/** 分类标签流式排布；dimmed 为隐藏/屏蔽态弱化样式，展示态用主题色容器背景（首页/直播分类管理共用）。
 *  传入 onMove 时展示区标签支持长按拖动换位（隐藏态标签不参与拖动）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CategoryTagFlow(
    names: List<String>,
    dimmed: Boolean,
    onClick: (String) -> Unit,
    emptyHint: String,
    onMove: ((from: Int, to: Int) -> Unit)? = null,
) {
    if (names.isEmpty()) {
        Text(
            text = emptyHint,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        return
    }
    // 拖动换位判定：onDragStart 冻结各标签 bounds 快照，布局重排不影响判定，onDragEnd 释放
    var rowOrigin by remember { mutableStateOf(Offset.Zero) }
    val liveBounds = remember { mutableStateMapOf<String, Rect>() }
    var frozenBounds by remember { mutableStateOf<Map<String, Rect>?>(null) }
    var draggingIndex by remember { mutableIntStateOf(-1) }
    var dragPoint by remember { mutableStateOf(Offset.Zero) }
    val currentOnMove by rememberUpdatedState(onMove)
    val currentNames by rememberUpdatedState(names)
    val currentDimmed by rememberUpdatedState(dimmed)
    val currentOnClick by rememberUpdatedState(onClick)
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .onGloballyPositioned { rowOrigin = it.positionInWindow() },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        names.forEachIndexed { index, name ->
            val dragging = index == draggingIndex
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                color = if (dimmed) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onPrimaryContainer
                },
                modifier = Modifier
                    .zIndex(if (dragging) 1f else 0f)
                    .onGloballyPositioned { coordinates ->
                        liveBounds[name] = Rect(
                            offset = coordinates.positionInWindow() - rowOrigin,
                            size = Size(coordinates.size.width.toFloat(), coordinates.size.height.toFloat()),
                        )
                    }
                    .clip(RoundedCornerShape(8.dp))
                    .border(
                        width = 1.dp,
                        color = if (dragging) MaterialTheme.colorScheme.primary else Color.Transparent,
                        shape = RoundedCornerShape(8.dp),
                    )
                    .background(
                        when {
                            dimmed -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                            else -> MaterialTheme.colorScheme.primaryContainer
                        },
                    )
                    .pointerInput(Unit) {
                        // key 用 Unit：手势协程跨重组存活，避免换位重组打断拖动；数据经 rememberUpdatedState 取新
                        detectDragGesturesAfterLongPress(
                            onDragStart = { startOffset ->
                                if (currentOnMove != null && !currentDimmed) {
                                    frozenBounds = liveBounds.toMap()
                                    draggingIndex = index
                                    dragPoint = (frozenBounds?.get(name) ?: Rect.Zero).topLeft + startOffset
                                }
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                if (draggingIndex < 0) return@detectDragGesturesAfterLongPress
                                dragPoint += amount
                                val snapshot = frozenBounds ?: return@detectDragGesturesAfterLongPress
                                val currentName = currentNames.getOrNull(draggingIndex)
                                    ?: return@detectDragGesturesAfterLongPress
                                val target = snapshot.entries.firstOrNull { (key, rect) ->
                                    key != currentName && rect.contains(dragPoint)
                                } ?: return@detectDragGesturesAfterLongPress
                                val targetIndex = currentNames.indexOf(target.key)
                                if (targetIndex >= 0 && targetIndex != draggingIndex) {
                                    currentOnMove?.invoke(draggingIndex, targetIndex)
                                    draggingIndex = targetIndex
                                }
                            },
                            onDragEnd = {
                                draggingIndex = -1
                                frozenBounds = null
                            },
                            onDragCancel = {
                                draggingIndex = -1
                                frozenBounds = null
                            },
                        )
                    }
                    .clickable { currentOnClick(name) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

/**
 * 下拉选择器：显示当前项文本，点击弹出菜单。
 * items 为 (展示文本, 点击回调) 对。
 */
@Composable
fun <T> DropdownSelector(
    currentText: String,
    items: List<T>,
    itemText: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable { expanded = true }
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = currentText,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                imageVector = Icons.Outlined.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(14.dp).rotate(if (expanded) -90f else 90f),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (items.isEmpty()) {
                DropdownMenuItem(text = { Text("暂无源") }, onClick = { expanded = false })
            }
            items.forEach { item ->
                DropdownMenuItem(
                    text = { Text(itemText(item), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    onClick = {
                        expanded = false
                        onSelect(item)
                    },
                )
            }
        }
    }
}

/** 文本输入行（标签 + 沉浸式无边框输入框），设置页导入复用 */
@Composable
fun TextInputRow(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.width(8.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text(placeholder, style = MaterialTheme.typography.bodySmall) },
            singleLine = true,
            shape = RoundedCornerShape(10.dp),
            colors = immersiveFieldColors(),
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

/** 沉浸式输入框配色：无边框 + 浅色底 */
@Composable
private fun immersiveFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Color.Transparent,
    unfocusedBorderColor = Color.Transparent,
    disabledBorderColor = Color.Transparent,
    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
)

/**
 * 分类横向标签栏：可滚动，选中项文本主色高亮，下划线宽度与文本实际宽度一致。
 * 跨端共用（首页/直播/我的），自定义 indicator 置空以替换 Material 默认的全宽指示器。
 */
@Composable
fun TextWidthTabRow(
    tabNames: List<String>,
    selectedTabIndex: Int,
    onSelectTab: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    ScrollableTabRow(
        selectedTabIndex = selectedTabIndex,
        modifier = modifier.fillMaxWidth(),
        edgePadding = 16.dp,
        containerColor = Color.Transparent,
        indicator = { },
    ) {
        tabNames.forEachIndexed { index, name ->
            Tab(
                selected = index == selectedTabIndex,
                onClick = { onSelectTab(index) },
                text = { TabTextWithUnderline(text = name, selected = index == selectedTabIndex) },
            )
        }
    }
}

/** Tab 文本：选中主色 + 底部等宽下划线（drawBehind 按文本布局宽度绘制，越界绘制不裁剪） */
@Composable
fun TabTextWithUnderline(text: String, selected: Boolean) {
    val indicatorColor = MaterialTheme.colorScheme.primary
    Text(
        text = text,
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.drawBehind {
            if (selected) {
                val thickness = TAB_INDICATOR_THICKNESS.toPx()
                drawRoundRect(
                    color = indicatorColor,
                    topLeft = Offset(0f, size.height + TAB_INDICATOR_GAP.toPx()),
                    size = Size(size.width, thickness),
                    cornerRadius = CornerRadius(thickness / 2f),
                )
            }
        },
    )
}

/** 下拉刷新容器：内容顶部下拉触发 [onRefresh]，顶部居中显示旋转指示器（主题色） */
@OptIn(ExperimentalMaterialApi::class)
@Composable
fun PullRefreshContainer(
    refreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val pullState = rememberPullRefreshState(refreshing = refreshing, onRefresh = onRefresh)
    Box(modifier.pullRefresh(pullState)) {
        content()
        PullRefreshIndicator(
            refreshing = refreshing,
            state = pullState,
            modifier = Modifier.align(Alignment.TopCenter),
            contentColor = MaterialTheme.colorScheme.primary,
        )
    }
}
