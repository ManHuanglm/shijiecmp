package com.shiping.desktop.player

import android.util.Log
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure

/** user32 最小绑定：视频面 HWND 显隐/尺寸控制（渲染内核接管 HWND 后 AWT 显隐/设界失效） */
private interface User32Binding : Library {
    fun ShowWindow(hwnd: Pointer, cmd: Int): Boolean

    fun MoveWindow(hwnd: Pointer, x: Int, y: Int, width: Int, height: Int, repaint: Boolean): Boolean

    fun GetWindowRect(hwnd: Pointer, rect: Win32Rect): Boolean

    fun GetWindowLongW(hwnd: Pointer, index: Int): Int

    fun GetParent(hwnd: Pointer): Pointer?

    fun GetAncestor(hwnd: Pointer, flags: Int): Pointer?

    fun IsWindowVisible(hwnd: Pointer): Boolean
}

/** GetWindowRect 结果矩形（屏幕物理像素坐标；JNA 反射访问字段，类须对外可见） */
@Structure.FieldOrder("left", "top", "right", "bottom")
open class Win32Rect : Structure() {
    @JvmField
    var left = 0

    @JvmField
    var top = 0

    @JvmField
    var right = 0

    @JvmField
    var bottom = 0
}

/**
 * 视频面 HWND 原生控制：mpv(wid)/mdk(updateNativeSurface) 接管 Canvas 原生窗口后，
 * AWT setVisible/setBounds 对该窗口失效（探针实测），显隐与改尺寸必须直接调原生接口。
 */
internal object Win32Surface {

    private const val TAG = "Win32Surface"

    // ShowWindow 命令：隐藏 / 原尺寸显示
    private const val SW_HIDE = 0
    private const val SW_SHOW = 5

    private val user32 by lazy { Native.load("user32", User32Binding::class.java) }

    /** 原生显隐 HWND（句柄无效忽略；调用失败记日志便于排障） */
    fun setVisible(hwnd: Long, visible: Boolean) {
        if (hwnd == 0L) return
        runCatching { user32.ShowWindow(Pointer(hwnd), if (visible) SW_SHOW else SW_HIDE) }
            .onFailure { Log.w(TAG, "ShowWindow 失败 hwnd=$hwnd visible=$visible", it) }
    }

    /** 原生移动并缩放 HWND（x/y 为父窗口客户区坐标；句柄无效忽略；调用失败记日志便于排障） */
    fun moveWindow(hwnd: Long, x: Int, y: Int, width: Int, height: Int) {
        if (hwnd == 0L) return
        runCatching {
            user32.MoveWindow(
                Pointer(hwnd),
                x,
                y,
                width.coerceAtLeast(1),
                height.coerceAtLeast(1),
                true,
            )
        }.onFailure { Log.w(TAG, "MoveWindow 失败 hwnd=$hwnd rect=($x,$y,$width,$height)", it) }
    }

    // GetWindowLongW 索引与 GetAncestor 标志
    private const val GWL_STYLE = -16
    private const val GWL_EXSTYLE = -20
    private const val GA_ROOT = 2

    // 关键样式位（判读用）：子窗口/可见/裁兄弟/顶层置顶
    private const val WS_CHILD = 0x40000000L
    private const val WS_VISIBLE = 0x10000000L
    private const val WS_CLIPSIBLINGS = 0x04000000L
    private const val WS_EX_TOPMOST = 0x8L

    /** 读 HWND 层级快照（可见性/样式/父链），用于诊断视频面遮挡类问题；失败返回 null */
    fun describeWindow(hwnd: Long): String? {
        if (hwnd == 0L) return null
        return runCatching {
            val p = Pointer(hwnd)
            val style = user32.GetWindowLongW(p, GWL_STYLE).toLong() and 0xFFFFFFFFL
            val exStyle = user32.GetWindowLongW(p, GWL_EXSTYLE).toLong() and 0xFFFFFFFFL
            val parent = user32.GetParent(p)
            val root = user32.GetAncestor(p, GA_ROOT)
            fun fmt(h: Pointer?) = h?.let { "0x${Pointer.nativeValue(it).toString(16)}" } ?: "null"
            buildString {
                append("hwnd=0x").append(hwnd.toString(16))
                append(" visible=").append(user32.IsWindowVisible(p))
                append(" child=").append(style and WS_CHILD != 0L)
                append(" visibleBit=").append(style and WS_VISIBLE != 0L)
                append(" clipsiblings=").append(style and WS_CLIPSIBLINGS != 0L)
                append(" topmost=").append(exStyle and WS_EX_TOPMOST != 0L)
                append(" style=0x").append(style.toString(16))
                append(" exStyle=0x").append(exStyle.toString(16))
                append(" parent=").append(fmt(parent))
                append(" root=").append(fmt(root))
            }
        }.getOrNull()
    }

    /** 读 HWND 屏幕矩形 [left, top, right, bottom]（句柄无效或调用失败返回 null） */
    fun getWindowRect(hwnd: Long): IntArray? {
        if (hwnd == 0L) return null
        val rect = Win32Rect()
        val ok = runCatching { user32.GetWindowRect(Pointer(hwnd), rect) }.getOrDefault(false)
        return if (ok) intArrayOf(rect.left, rect.top, rect.right, rect.bottom) else null
    }
}
