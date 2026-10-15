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
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowState
import com.shiping.desktop.ui.theme.appBackground
import kotlinx.coroutines.launch

/** 主窗口：导航壳（底部模式=左侧栏 / 抽屉模式=左上抽屉）+ 内容层 + 全局提示 */
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

    Box(modifier = Modifier.fillMaxSize().appBackground()) {
        // 任一页内播放全屏中：隐藏导航 chrome（侧栏/顶栏），内容区铺满。
        // MainScreenContent 固定同一组合槽位（只切 chrome 显隐、不换分支），防止播放页销毁重建导致内核重启。
        // 注意：勿在此做 WindowPlacement.Fullscreen 切换——placement 变更会重建窗口 peer，
        // Compose 状态整树丢失（实测 TvScreen 销毁、播放中断），页内全屏保持布局级铺满
        val detailFullscreen by app.detailFullscreen.collectAsState()
        val tvFullscreen by app.tvFullscreen.collectAsState()
        val pageFullscreen = detailFullscreen || tvFullscreen
        val onNavSelect: (Screen) -> Unit = { screen -> app.navigate(screen) }
        if (MainTabsState.position == TabPosition.DRAWER) {
            DrawerShell(currentScreen, app, chromeVisible = !pageFullscreen)
        } else {
            Row(modifier = Modifier.fillMaxSize()) {
                if (!pageFullscreen) {
                    AppSidebar(currentScreen, onSelect = onNavSelect)
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .background(MaterialTheme.colorScheme.background),
                ) {
                    MainScreenContent(currentScreen, app)
                }
            }
        }
        // 解析播放地址的轻量加载提示（首播会话未建立时；页内播放器由详情页承载）
        if (loading && session == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("正在解析播放地址…", color = MaterialTheme.colorScheme.primary)
            }
        }
        // 剪贴板自动检测（可开关）：弹窗引导导入/播放/搜索
        ClipboardDetector(app)

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

/** 当前栈顶屏幕内容分发 */
@Composable
private fun MainScreenContent(currentScreen: Screen, app: AppViewModel) {
    when (currentScreen) {
        Screen.Search -> SearchScreen(app)
        Screen.Tv -> TvScreen(app)
        Screen.Favorites -> FavoriteScreen(app)
        Screen.History -> HistoryScreen(app)
        Screen.Downloads -> DownloadsPageContent(app)
        Screen.Mine -> MineScreen(app)
        Screen.Settings -> SettingsScreen(app)
        is Screen.Detail -> DetailScreen(currentScreen, app)
        else -> HomeScreen(app)
    }
}

/** 左上抽屉导航壳（Tab 位置=左上抽屉）：顶部条汉堡按钮 + 导航抽屉；页内全屏时 [chromeVisible]=false 隐藏顶栏 */
@Composable
private fun DrawerShell(currentScreen: Screen, app: AppViewModel, chromeVisible: Boolean) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            MainNavDrawerContent(currentScreen) { screen ->
                scope.launch { drawerState.close() }
                app.navigate(screen)
            }
        },
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 顶部条：汉堡按钮 + 品牌，避免遮挡页内顶栏
            if (chromeVisible) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .background(MaterialTheme.colorScheme.surface),
                ) {
                    IconButton(onClick = { scope.launch { drawerState.open() } }) {
                        Icon(Icons.Outlined.Menu, contentDescription = "打开导航抽屉")
                    }
                    Text(
                        text = "视界",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(MaterialTheme.colorScheme.background),
            ) {
                MainScreenContent(currentScreen, app)
            }
        }
    }
}

/** 左侧导航栏（按 Tab 设置过滤显隐与平台能力，首页/设置固定显示） */
@Composable
private fun AppSidebar(currentScreen: Screen, onSelect: (Screen) -> Unit) {
    val visibleItems = MainTabsState.visibleTabs.filterNot { it.androidOnly }
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
