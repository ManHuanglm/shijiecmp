package android.widget

import android.content.Context
import android.view.View
import android.view.ViewGroup

/** 桌面 stub：View 子类群（桌面端不渲染，仅满足类加载校验） */
open class TextView : View {

    constructor()

    constructor(context: Context?)

    fun setText(text: CharSequence?) = Unit

    fun setText(resId: Int) = Unit

    open fun getText(): CharSequence? = ""
}

open class Button : TextView()

open class EditText : TextView() {

    override fun getText(): android.text.Editable? = null
}

open class ImageView : View() {

    enum class ScaleType {
        MATRIX, FIT_XY, FIT_START, FIT_CENTER, FIT_END, CENTER, CENTER_CROP, CENTER_INSIDE
    }

    fun setScaleType(type: ScaleType?) = Unit

    fun setImageResource(resId: Int) = Unit
}

open class ProgressBar : View() {

    fun setProgress(progress: Int) = Unit

    fun setMax(max: Int) = Unit
}

open class ScrollView : ViewGroup()

open class HorizontalScrollView : ViewGroup()

open class FrameLayout : ViewGroup() {

    class LayoutParams : ViewGroup.MarginLayoutParams {

        constructor()

        constructor(width: Int, height: Int) : super(width, height)
    }
}

open class LinearLayout : ViewGroup() {

    class LayoutParams : ViewGroup.MarginLayoutParams {

        constructor()

        constructor(width: Int, height: Int) : super(width, height)
    }

    companion object {

        const val HORIZONTAL = 0
        const val VERTICAL = 1
    }
}

/** 桌面 stub：数组适配器（桌面端不渲染，构造仅存引用） */
open class ArrayAdapter<T> : ListAdapter {

    constructor()

    constructor(context: Context?, resource: Int)

    constructor(context: Context?, resource: Int, objects: List<T>?)

    override fun getCount(): Int = 0

    override fun getItem(position: Int): Any? = null

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View = View()
}

/** 桌面 stub：Toast（桌面端无弹窗，show 为空操作） */
class Toast private constructor(private val message: CharSequence?) {

    fun show() = Unit

    fun setText(text: CharSequence?): Toast = this

    companion object {

        const val LENGTH_SHORT = 0
        const val LENGTH_LONG = 1

        @JvmStatic
        fun makeText(context: Context?, text: CharSequence?, duration: Int): Toast = Toast(text)

        @JvmStatic
        fun makeText(context: Context?, resId: Int, duration: Int): Toast = Toast("")
    }
}
