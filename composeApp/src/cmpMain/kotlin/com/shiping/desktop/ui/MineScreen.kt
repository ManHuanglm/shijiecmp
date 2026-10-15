package com.shiping.desktop.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.shiping.desktop.ui.common.TabTextWithUnderline

/**
 * 「我的」合并页：收藏 + 播放历史双页签（Android 底部 Tab 用）。
 * 内容复用收藏/历史屏本体（含清空、下载管理等功能按钮），桌面独立路由不受影响。
 */
@Composable
fun MineScreen(app: AppViewModel) {
    var selectedTab by remember { mutableStateOf(0) }
    Column(modifier = Modifier.fillMaxSize()) {
        // 自定义 indicator 置空，改用文本等宽下划线（与首页/直播分类标签一致）
        TabRow(selectedTabIndex = selectedTab, indicator = { }) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { TabTextWithUnderline(text = "收藏", selected = selectedTab == 0) },
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { TabTextWithUnderline(text = "历史", selected = selectedTab == 1) },
            )
        }
        when (selectedTab) {
            0 -> FavoriteScreen(app)
            else -> HistoryScreen(app)
        }
    }
}
