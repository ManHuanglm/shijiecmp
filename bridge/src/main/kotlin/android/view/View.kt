package android.view

import android.content.Context

/** 桌面 stub：Display 信息（仅类加载校验） */
class Display {

    fun getWidth(): Int = 1920

    fun getHeight(): Int = 1080
}

/** 桌面 stub：按键事件（仅类加载校验） */
class KeyEvent {

    companion object {

        const val KEYCODE_BACK = 4
        const val KEYCODE_MENU = 82
        const val ACTION_DOWN = 0
        const val ACTION_UP = 1
    }
}

/** 桌面 stub：触摸事件（仅类加载校验） */
class MotionEvent {

    companion object {

        const val ACTION_DOWN = 0
        const val ACTION_MOVE = 2
        const val ACTION_UP = 1
    }
}

/** 桌面 stub：父视图标记接口（仅类加载校验） */
interface ViewParent

/** 桌面 stub：View 基类（桌面端不渲染，仅满足校验与引用） */
open class View {

    /** 所属上下文（payload 经 decorView 反查时提供） */
    private var attachedContext: Context? = null

    open fun getContext(): Context? = attachedContext

    open fun getRootView(): View = this

    open fun findViewById(id: Int): View? = null

    open var visibility: Int = VISIBLE

    open var enabled: Boolean = true

    open fun setOnClickListener(l: OnClickListener?) = Unit

    open fun setOnLongClickListener(l: OnLongClickListener?) = Unit

    open fun setOnTouchListener(l: OnTouchListener?) = Unit

    open fun setOnFocusChangeListener(l: OnFocusChangeListener?) = Unit

    open fun setOnKeyListener(l: OnKeyListener?) = Unit

    open fun setTag(any: Any?) = Unit

    open fun setLayoutParams(params: ViewGroup.LayoutParams?) = Unit

    open fun setPadding(left: Int, top: Int, right: Int, bottom: Int) = Unit

    open fun setPaddingRelative(left: Int, top: Int, right: Int, bottom: Int) = Unit

    open fun setAlpha(alpha: Float) = Unit

    open fun setElevation(elevation: Float) = Unit

    open fun setBackgroundColor(color: Int) = Unit

    open fun setVerticalScrollBarEnabled(enabled: Boolean) = Unit

    open fun setHorizontalScrollBarEnabled(enabled: Boolean) = Unit

    open fun setOverScrollMode(mode: Int) = Unit

    open fun setClipToPadding(clip: Boolean) = Unit

    open fun getTag(): Any? = null

    open fun requestLayout() = Unit

    open fun invalidate() = Unit

    open fun post(action: Runnable?): Boolean = false

    interface OnLayoutChangeListener {

        fun onLayoutChange(
            v: View?,
            left: Int,
            top: Int,
            right: Int,
            bottom: Int,
            oldLeft: Int,
            oldTop: Int,
            oldRight: Int,
            oldBottom: Int,
        )
    }

    interface OnAttachStateChangeListener {

        fun onViewAttachedToWindow(v: View?)

        fun onViewDetachedFromWindow(v: View?)
    }

    interface OnScrollChangeListener {

        fun onScrollChange(v: View?, scrollX: Int, scrollY: Int, oldScrollX: Int, oldScrollY: Int)
    }

    interface OnHoverListener {

        fun onHover(v: View?, event: MotionEvent?): Boolean
    }

    interface OnGenericMotionListener {

        fun onGenericMotion(v: View?, event: MotionEvent?): Boolean
    }

    interface OnClickListener {
        fun onClick(v: View?)
    }

    interface OnLongClickListener {
        fun onLongClick(v: View?): Boolean
    }

    interface OnTouchListener {
        fun onTouch(v: View?, event: MotionEvent?): Boolean
    }

    interface OnFocusChangeListener {
        fun onFocusChange(v: View?, hasFocus: Boolean)
    }

    interface OnKeyListener {
        fun onKey(v: View?, keyCode: Int, event: KeyEvent?): Boolean
    }

    companion object {

        const val VISIBLE = 0
        const val INVISIBLE = 4
        const val GONE = 8
    }
}

/** 桌面 stub：View 容器基类 */
open class ViewGroup : View() {

    open class LayoutParams {

        var width: Int = WRAP_CONTENT
        var height: Int = WRAP_CONTENT

        constructor()

        constructor(width: Int, height: Int) {
            this.width = width
            this.height = height
        }

        companion object {

            const val MATCH_PARENT = -1
            const val WRAP_CONTENT = -2
        }
    }

    open class MarginLayoutParams : LayoutParams {

        var leftMargin: Int = 0
        var topMargin: Int = 0
        var rightMargin: Int = 0
        var bottomMargin: Int = 0

        constructor()

        constructor(width: Int, height: Int) : super(width, height)
    }
}

/** 桌面 stub：窗口（仅类加载校验） */
class Window {

    private val decorView = View()

    fun getDecorView(): View = decorView

    fun setContentView(view: View?) = Unit

    fun setFlags(flags: Int, mask: Int) = Unit

    fun addFlags(flags: Int): Window = this

    companion object {

        const val FLAG_FULLSCREEN = 1024
        const val FLAG_KEEP_SCREEN_ON = 128
    }
}

/** 桌面 stub：窗口管理器（仅类加载校验） */
interface WindowManager {

    class LayoutParams : ViewGroup.MarginLayoutParams()
}
