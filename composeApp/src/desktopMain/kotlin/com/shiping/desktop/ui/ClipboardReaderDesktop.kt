package com.shiping.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import java.awt.AWTEvent
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.event.AWTEventListener
import java.awt.event.WindowEvent

/** 桌面剪贴板文本读取（系统剪贴板 stringFlavor） */
actual fun readClipboardText(): String? = runCatching {
    val content = Toolkit.getDefaultToolkit().systemClipboard.getContents(null) ?: return null
    if (content.isDataFlavorSupported(DataFlavor.stringFlavor)) {
        content.getTransferData(DataFlavor.stringFlavor) as? String
    } else {
        null
    }
}.getOrNull()

/** 桌面任一窗口获得焦点 = 回到前台（全局 AWT 事件监听，主窗口/播放窗口均覆盖） */
@Composable
actual fun ForegroundRecallEffect(onForeground: () -> Unit) {
    DisposableEffect(onForeground) {
        val listener = AWTEventListener { event ->
            if (event is WindowEvent && event.id == WindowEvent.WINDOW_GAINED_FOCUS) onForeground()
        }
        val toolkit = Toolkit.getDefaultToolkit()
        toolkit.addAWTEventListener(listener, AWTEvent.WINDOW_EVENT_MASK)
        onDispose { toolkit.removeAWTEventListener(listener) }
    }
}
