package com.shiping.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowState

/** 侧边栏导航项 */
private data class NavItem(val screen: Screen, val label: String, val icon: ImageVector)

private val navItems = listOf(
    NavItem(Screen.Home, "首页", Icons.Filled.Home),
    NavItem(Screen.Search, "搜索", Icons.Filled.Search),
    NavItem(Screen.Tv, "直播", Icons.Filled.LiveTv),
    NavItem(Screen.Favorites, "收藏", Icons.Filled.Star),
    NavItem(Screen.History, "历史", Icons.Filled.History),
    NavItem(Screen.Settings, "设置", Icons.Filled.Settings),
)

/** 主窗口：左侧导航 + 内容层 + 全局提示 */
@Composable
fun MainWindow(app: AppViewModel, windowState: WindowState) {
    val navStack by app.navStack.collectAsState()
    val session by app.playerSession.collectAsState()
    val loading by app.playerLoading.collectAsState()
    val message by app.message.collectAsState()
    val currentScreen = navStack.last()

    // 全局提示 3 秒自动清除
    LaunchedEffect(message) {
        if (message != null) {
            kotlinx.coroutines.delay(3000)
            app.clearMessage()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // 任一页内播放全屏中：隐藏侧栏，播放区铺满内容区（切页即退出播放，与直播页一致）
        val detailFullscreen by app.detailFullscreen.collectAsState()
        val tvFullscreen by app.tvFullscreen.collectAsState()
        val pageFullscreen = detailFullscreen || tvFullscreen
        val onNavSelect: (Screen) -> Unit = { screen -> app.navigate(screen) }
        Row(modifier = Modifier.fillMaxSize()) {
            if (!pageFullscreen) {
                AppSidebar(currentScreen, onSelect = onNavSelect)
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(MaterialTheme.colorScheme.background),
            ) {
                when (currentScreen) {
                    Screen.Search -> SearchScreen(app)
                    Screen.Tv -> TvScreen(app)
                    Screen.Favorites -> FavoriteScreen(app)
                    Screen.History -> HistoryScreen(app)
                    Screen.Settings -> SettingsScreen(app)
                    is Screen.Detail -> DetailScreen(currentScreen, app)
                    else -> HomeScreen(app)
                }
            }
        }
        // 解析播放地址的轻量加载提示（首播会话未建立时；页内播放器由详情页承载）
        if (loading && session == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("正在解析播放地址…", color = MaterialTheme.colorScheme.primary)
            }
        }
        // 全局提示
        message?.let { text ->
            Snackbar(
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp),
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurface,
                action = { Text("知道了", modifier = Modifier.clickable { app.clearMessage() }) },
            ) { Text(text) }
        }
    }
}

/** 左侧导航栏（按 Tab 设置过滤显隐，首页/设置固定显示） */
@Composable
private fun AppSidebar(currentScreen: Screen, onSelect: (Screen) -> Unit) {
    val visibleItems = navItems.filter { it.label !in MainTabsState.hidden }
    Column(
        modifier = Modifier
            .width(96.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surface),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Spacer(Modifier.height(16.dp))
        Text(
            text = "视界",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(12.dp))
        visibleItems.forEach { item ->
            val selected = currentScreen::class == item.screen::class
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(item.screen) }
                    .padding(vertical = 8.dp),
            ) {
                Icon(
                    imageVector = item.icon,
                    contentDescription = item.label,
                    tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = item.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
