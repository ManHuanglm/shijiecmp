package com.shiping.desktop.ui

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

private var appContext: Context? = null

/** 注入应用上下文（MainActivity.onCreate 调用） */
fun initClipboardReader(context: Context) {
    appContext = context.applicationContext
}

/** Android 剪贴板文本读取（前台读取不受 10+ 后台限制） */
actual fun readClipboardText(): String? = runCatching {
    val context = appContext ?: return null
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
    val clip = cm.primaryClip ?: return null
    if (clip.itemCount > 0) clip.getItemAt(0).coerceToText(context)?.toString() else null
}.getOrNull()

/** Android ON_RESUME = 回到前台（含首次启动） */
@Composable
actual fun ForegroundRecallEffect(onForeground: () -> Unit) {
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, onForeground) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) onForeground()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}
