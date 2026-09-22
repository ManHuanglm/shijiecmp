package com.shiping.desktop.ui

import androidx.compose.runtime.staticCompositionLocalOf
import com.shiping.shared.graph.AppGraph

/** 应用依赖图组合注入：平台入口 provide（桌面 Main.kt / Android MainActivity），屏幕层经 LocalAppGraph.current 消费 */
val LocalAppGraph = staticCompositionLocalOf<AppGraph> {
    error("LocalAppGraph 未提供：平台入口需 CompositionLocalProvider 包裹")
}
