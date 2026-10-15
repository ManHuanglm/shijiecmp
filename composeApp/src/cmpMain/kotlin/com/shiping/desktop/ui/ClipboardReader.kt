package com.shiping.desktop.ui

import androidx.compose.runtime.Composable

/**
 * 跨端剪贴板文本读取（前台调用；无剪贴板内容/读取失败返回 null）。
 * Android 10+ 限制后台读取，前台（ON_RESUME/窗口聚焦）读取不受限。
 */
expect fun readClipboardText(): String?

/**
 * 跨端「应用回到前台」事件挂点：应用启动与每次切回前台各触发一次 [onForeground]。
 * 桌面=窗口焦点获得；Android=Lifecycle ON_RESUME。
 */
@Composable
expect fun ForegroundRecallEffect(onForeground: () -> Unit)
