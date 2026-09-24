package com.shiping.app

import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.shiping.app.data.player.PipController
import com.shiping.desktop.ui.AppViewModel
import com.shiping.desktop.ui.DetailScreen
import com.shiping.desktop.ui.DirectPlaybackHostAndroid
import com.shiping.desktop.ui.DownloadsScreen
import com.shiping.desktop.ui.LocalAppGraph
import com.shiping.desktop.ui.Screen
import com.shiping.desktop.ui.SettingsScreen
import com.shiping.desktop.ui.FavoriteScreen
import com.shiping.desktop.ui.HomeScreen
import com.shiping.desktop.ui.HistoryScreen
import com.shiping.desktop.ui.SearchScreen
import com.shiping.desktop.ui.TvScreen
import com.shiping.desktop.ui.theme.ShipingTheme

/**
 * Android 入口：宿主装配（DirectPlaybackHost + LocalAppGraph）+ 底部 Tab 导航。
 * 复用 cmpMain 全部跨端屏（Home/Search/History/Favorite/Settings/Detail/Tv）。
 * 播放中离开应用自动进入画中画（PipController）。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ShipingTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val host = remember { DirectPlaybackHostAndroid() }
                    val app = remember { AppViewModel(ShipingGraph, host) }
                    CompositionLocalProvider(LocalAppGraph provides ShipingGraph) {
                        AndroidRoot(app)
                    }
                }
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // 页内播放中（Detail/Tv 挂载内核）离开应用时进画中画；无播放则不响应
        PipController.tryEnterPip(this)
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        PipController.onPipModeChanged(isInPictureInPictureMode)
    }
}

/** 底部 Tab 项 */
private data class TabItem(val screen: Screen, val label: String, val icon: ImageVector)

private val tabs = listOf(
    TabItem(Screen.Home, "首页", Icons.Filled.Home),
    TabItem(Screen.Search, "搜索", Icons.Filled.Search),
    TabItem(Screen.Favorites, "收藏", Icons.Filled.Star),
    TabItem(Screen.History, "历史", Icons.Filled.History),
    TabItem(Screen.Settings, "设置", Icons.Filled.Settings),
)

/** Android 根导航：栈顶为详情/直播时显示对应页，否则主 Tab 界面 */
@Composable
fun AndroidRoot(app: AppViewModel) {
    val navStack by app.navStack.collectAsState()
    when (val top = navStack.last()) {
        is Screen.Detail -> DetailScreen(top, app)
        is Screen.Tv -> TvScreen(app)
        is Screen.Downloads -> DownloadsScreen(app)
        else -> MainTabs(app, top)
    }
}

/** 主 Tab 界面：底部导航 + 屏内容（切 Tab 复用 AppViewModel 栈导航） */
@Composable
private fun MainTabs(app: AppViewModel, top: Screen) {
    Scaffold(
        bottomBar = {
            NavigationBar {
                for (tab in tabs) {
                    NavigationBarItem(
                        selected = top::class == tab.screen::class,
                        onClick = { app.navigate(tab.screen) },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (top) {
                is Screen.Home -> HomeScreen(app)
                is Screen.Search -> SearchScreen(app)
                is Screen.Favorites -> FavoriteScreen(app)
                is Screen.History -> HistoryScreen(app)
                is Screen.Settings -> SettingsScreen(app)
                else -> Unit
            }
        }
    }
}
