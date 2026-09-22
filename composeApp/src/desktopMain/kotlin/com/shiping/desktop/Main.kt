package com.shiping.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.shiping.desktop.di.AppContainer
import com.shiping.desktop.ui.AppViewModel
import com.shiping.desktop.ui.DesktopDirectPlaybackHost
import com.shiping.desktop.ui.MainTabsState
import com.shiping.desktop.ui.MainWindow
import com.shiping.desktop.ui.VmLog
import com.shiping.desktop.ui.theme.ShipingTheme
import com.shiping.desktop.ui.theme.ThemeState
import com.shiping.desktop.ui.theme.UiScale
import com.shiping.desktop.util.AppLog
import java.awt.Dimension

// Swing 文字必须灰度抗锯齿：LCD 子像素 AA 在半透明分层窗口（播放器控制层 JWindow）上会渲染成黑色
private val swingTextAntialiasing = run {
    System.setProperty("awt.useSystemAAFontSettings", "on")
    System.setProperty("swing.aatext", "true")
}

/** 桌面版入口：初始化数据容器 → 主题 → 主窗口（导航 + 播放器） */
fun main() = application {
    // 首次访问触发初始化（数据目录/爬虫运行时/本地代理/仓库预热）
    val container = AppContainer.get()
    // 设置页全局状态：主题模式 / 字体缩放 / 侧边栏 Tab 显隐
    ThemeState.init(container.prefs)
    UiScale.init(container.prefs)
    MainTabsState.init(container.prefs)
    // cmpMain 统一日志出口装配：UI 层 VmLog → 桌面 AppLog
    VmLog.sink = { level, tag, message ->
        when (level) {
            'w' -> AppLog.w(tag, message)
            'e' -> AppLog.e(tag, message)
            else -> AppLog.i(tag, message)
        }
    }
    val windowState = rememberWindowState(width = 1280.dp, height = 800.dp)

    Window(
        onCloseRequest = ::exitApplication,
        title = "视界 Desktop",
        state = windowState,
    ) {
        window.minimumSize = Dimension(960, 600)
        ShipingTheme {
            // 字体缩放比例（设置页可调，实时生效）
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, UiScale.fontScale),
            ) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val host = remember { DesktopDirectPlaybackHost() }
                    val appViewModel = remember { AppViewModel(container, host).also { host.bind(it) } }
                    MainWindow(appViewModel, windowState)
                }
            }
        }
    }
}
