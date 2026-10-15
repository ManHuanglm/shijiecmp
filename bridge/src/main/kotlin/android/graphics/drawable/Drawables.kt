package android.graphics.drawable

/** 桌面 stub：Drawable 基类（仅满足类加载校验，桌面端不渲染） */
open class Drawable {

    open class ConstantState {

        fun newDrawable(): Drawable = Drawable()
    }
}

open class ColorDrawable : Drawable()

open class GradientDrawable : Drawable()

open class RippleDrawable : Drawable()

open class StateListDrawable : Drawable()
