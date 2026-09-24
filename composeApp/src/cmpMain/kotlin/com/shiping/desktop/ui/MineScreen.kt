package com.shiping.desktop.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/**
 * 「我的」合并页：收藏 + 播放历史双页签（Android 底部 Tab 用）。
 * 内容复用收藏/历史屏本体（含清空、下载管理等功能按钮），桌面独立路由不受影响。
 */
@Composable
fun MineScreen(app: AppViewModel) {
    var selectedTab by remember { mutableStateOf(0) }
    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = selectedTab) {
            Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("收藏") })
            Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("历史") })
        }
        when (selectedTab) {
            0 -> FavoriteScreen(app)
            else -> HistoryScreen(app)
        }
    }
}
