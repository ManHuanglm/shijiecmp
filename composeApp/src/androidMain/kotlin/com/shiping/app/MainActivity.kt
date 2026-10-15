package com.shiping.app

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.graphics.Color
import com.shiping.desktop.ui.ClipboardDetector
import com.shiping.desktop.ui.initClipboardReader
import com.shiping.desktop.ui.theme.appBackground
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.shiping.app.data.player.PipController
import com.shiping.desktop.ui.AppViewModel
import com.shiping.desktop.ui.DetailScreen
import com.shiping.desktop.ui.DirectPlaybackHostAndroid
import com.shiping.desktop.ui.DownloadsScreen
import com.shiping.desktop.ui.FavoriteScreen
import com.shiping.desktop.ui.HistoryScreen
import com.shiping.desktop.ui.LocalAppGraph
import com.shiping.desktop.ui.MainNavDrawerContent
import com.shiping.desktop.ui.MainTabsState
import com.shiping.desktop.ui.MineScreen
import com.shiping.desktop.ui.Screen
import com.shiping.desktop.ui.SearchScreen
import com.shiping.desktop.ui.SettingsScreen
import com.shiping.desktop.ui.HomeScreen
import com.shiping.desktop.ui.TabPosition
import com.shiping.desktop.ui.TvScreen
import com.shiping.desktop.ui.theme.ShipingTheme
import com.shiping.desktop.ui.theme.ThemeState
import com.shiping.desktop.ui.theme.UiScale
import kotlinx.coroutines.launch

/**
 * Android 入口：宿主装配（DirectPlaybackHost + LocalAppGraph）+ 底部 Tab 导航。
 * 复用 cmpMain 全部跨端屏（Home/Search/History/Favorite/Settings/Detail/Tv）。
 * 播放中离开应用自动进入画中画（PipController）。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 剪贴板读取上下文（自动检测弹窗用）
        initClipboardReader(this)

        // 本地包读取共享存储（TVBox 约定 /sdcard/TVBoxOSC）：未授予「所有文件访问」时引导到系统设置
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R &&
            !android.os.Environment.isExternalStorageManager()
        ) {
            runCatching {
                startActivity(
                    android.content.Intent(
                        android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        android.net.Uri.parse("package:$packageName"),
                    ),
                )
            }
        }
        setContent {
            // 恢复持久化全局状态：Tab 显隐与位置 / 主题模式与主题色 / 字体缩放
            remember {
                MainTabsState.init(ShipingGraph.prefs)
                ThemeState.init(ShipingGraph.prefs)
                UiScale.init(ShipingGraph.prefs)
            }
            ShipingTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Color.Transparent) {
                    val host = remember { DirectPlaybackHostAndroid() }
                    val app = remember { AppViewModel(ShipingGraph, host) }
                    CompositionLocalProvider(LocalAppGraph provides ShipingGraph) {
                        Box(modifier = Modifier.fillMaxSize().appBackground()) {
                            AndroidRoot(app)
                        }
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

/** 切换 NavigationRail 的最小宽度（Material 窗口宽度分级 medium 断点） */
private val NAV_RAIL_MIN_WIDTH = 600.dp

/** NavigationRail 默认容器宽度（Material 规范 80dp；当前 M3 版本无 NavigationRailDefaults.ContainerWidth） */
private val NAV_RAIL_CONTAINER_WIDTH = 80.dp

/** 双击返回退出的判定间隔 */
private const val EXIT_BACK_INTERVAL_MS = 2000L

/** Android 根导航：栈顶为详情/下载时显示对应页，否则主 Tab 界面 */
@Composable
fun AndroidRoot(app: AppViewModel) {
    // 剪贴板自动检测（可开关）：弹窗引导导入/播放/搜索
    ClipboardDetector(app)
    val navStack by app.navStack.collectAsState()
    // 全屏播放形态（点播 detail / 直播 tv）：横屏 + 沉浸式隐藏系统栏；竖屏视频（如 9:16）全屏保持竖屏不旋转；
    // 退出全屏/销毁强制回竖屏并恢复系统栏。视频尺寸起播后才上报，key 挂 videoSize 使方向随分辨率就地纠正
    val detailFullscreen by app.detailFullscreen.collectAsState()
    val tvFullscreen by app.tvFullscreen.collectAsState()
    val fullscreen = detailFullscreen || tvFullscreen
    val playerVideoSize by app.playerVideoSize.collectAsState()
    val activity = LocalContext.current as ComponentActivity
    LaunchedEffect(fullscreen, playerVideoSize) {
        activity.applyFullscreenMode(fullscreen, playerVideoSize.first, playerVideoSize.second)
    }
    DisposableEffect(Unit) {
        onDispose { activity.applyFullscreenMode(false, 0, 0) }
    }
    // 系统返回键：直播全屏先退全屏回播放态（下方频道列表可见）；栈内逐级返回；已到主页时 2 秒内按两次退出应用
    val toast = remember(activity) { Toast.makeText(activity, "再按一次退出应用", Toast.LENGTH_SHORT) }
    var lastBackAt by remember { mutableLongStateOf(0L) }
    BackHandler {
        when {
            tvFullscreen -> app.closeTvFullscreen()
            !app.back() -> {
                val now = System.currentTimeMillis()
                if (now - lastBackAt < EXIT_BACK_INTERVAL_MS) {
                    activity.finish()
                } else {
                    lastBackAt = now
                    toast.show()
                }
            }
        }
    }
    when (val top = navStack.last()) {
        is Screen.Detail -> DetailScreen(top, app)
        // 下载页双形态：「我的」页等栈内 push 进入（栈深>1）独立渲染带返回箭头；
        // 底部 Tab 进入（单层栈）走主 Tab 界面保留底部导航，否则无 Tab 栏且返回失效困死页面
        is Screen.Downloads -> if (navStack.size > 1) DownloadsScreen(app) else MainTabs(app, top)
        else -> MainTabs(app, top)
    }
}

/**
 * 全屏播放形态切换：横屏传感器方向 + 隐藏系统栏（沉浸式，下滑暂显）。
 * 竖屏视频（高>宽，如 9:16 短剧）全屏保持竖屏不旋转，铺满竖屏窗口；
 * 尺寸未知（0）回落横屏（维持既有行为）。
 * 退出全屏强制回竖屏：UNSPECIFIED 会在设备横置/自动旋转开启时停留在横屏，
 * 导致竖屏布局的页面横屏渲染且播放区无法铺满。
 */
private fun ComponentActivity.applyFullscreenMode(fullscreen: Boolean, videoWidth: Int, videoHeight: Int) {
    requestedOrientation = when {
        !fullscreen -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        videoWidth in 1 until videoHeight -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        else -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }
    val insetsController = WindowCompat.getInsetsController(window, window.decorView)
    if (fullscreen) {
        insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insetsController.hide(WindowInsetsCompat.Type.systemBars())
    } else {
        insetsController.show(WindowInsetsCompat.Type.systemBars())
    }
}

/** 主 Tab 界面：Tab 位置=左上抽屉时抽屉导航；否则响应式 — 窄屏底部导航，宽屏 NavigationRail */
@Composable
private fun MainTabs(app: AppViewModel, top: Screen) {
    if (MainTabsState.position == TabPosition.DRAWER) {
        DrawerTabs(app, top)
        return
    }
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // 断点用最小窗口维度（等效 smallestScreenWidthDp，跨端 BoxWithConstraints 实现）：
        // 手机竖屏(360dp)↔横屏(914dp) 用 maxWidth 会跨越 600dp 断点导致 Scaffold↔Rail
        // 布局树切换，页面（如直播播放态 remember）整体销毁重建，全屏状态被 onDispose 连带取消；
        // 最小维度旋转不变，手机恒走底部导航、平板/宽屏恒走 Rail，不再发生跨断点翻转
        val tvFullscreen by app.tvFullscreen.collectAsState()
        if (minOf(maxWidth, maxHeight) >= NAV_RAIL_MIN_WIDTH) {
            Row(modifier = Modifier.fillMaxSize()) {
                // 直播全屏时收起侧边 Rail(宽度归零+裁剪)，播放器铺满整窗，与窄屏隐藏底部导航同理；
                // 不移除节点而是收起宽度，保持 Row 结构与子节点位置不变，避免布局树切换
                // 导致直播播放态 remember 丢失（同上方断点选取的教训）
                NavigationRail(
                    modifier = Modifier
                        .width(if (tvFullscreen) 0.dp else NAV_RAIL_CONTAINER_WIDTH)
                        .clipToBounds(),
                ) {
                    for (tab in MainTabsState.visibleTabs) {
                        NavigationRailItem(
                            selected = top::class == tab.screen::class,
                            onClick = { app.navigate(tab.screen) },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                        )
                    }
                }
                Box(modifier = Modifier.weight(1f)) {
                    MainTabContent(app, top)
                }
            }
        } else {
            Scaffold(
                bottomBar = {
                    // 直播全屏时隐藏底部导航并清零内容边距，播放器才能铺满整窗
                    if (!tvFullscreen) {
                        NavigationBar {
                            for (tab in MainTabsState.visibleTabs) {
                                NavigationBarItem(
                                    selected = top::class == tab.screen::class,
                                    onClick = { app.navigate(tab.screen) },
                                    icon = { Icon(tab.icon, contentDescription = tab.label) },
                                    label = { Text(tab.label) },
                                )
                            }
                        }
                    }
                },
            ) { padding ->
                Box(
                    modifier = Modifier.fillMaxSize()
                        .padding(if (tvFullscreen) PaddingValues(0.dp) else padding),
                ) {
                    MainTabContent(app, top)
                }
            }
        }
    }
}

/** 左上抽屉导航（Tab 位置=左上抽屉）：顶栏汉堡按钮 + 导航抽屉 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DrawerTabs(app: AppViewModel, top: Screen) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            MainNavDrawerContent(top) { screen ->
                scope.launch { drawerState.close() }
                app.navigate(screen)
            }
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("视界") },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Outlined.Menu, contentDescription = "打开导航抽屉")
                        }
                    },
                )
            },
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                MainTabContent(app, top)
            }
        }
    }
}

/** 当前 Tab 对应的屏幕内容 */
@Composable
private fun MainTabContent(app: AppViewModel, top: Screen) {
    when (top) {
        is Screen.Home -> HomeScreen(app)
        is Screen.Search -> SearchScreen(app)
        is Screen.Tv -> TvScreen(app)
        is Screen.Favorites -> FavoriteScreen(app)
        is Screen.History -> HistoryScreen(app)
        is Screen.Downloads -> DownloadsScreen(app)
        is Screen.Mine -> MineScreen(app)
        is Screen.Settings -> SettingsScreen(app)
        else -> Unit
    }
}
