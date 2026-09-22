package android.app

import android.content.DialogInterface

/** 桌面 stub：对话框群（桌面端不弹窗，仅满足类加载校验） */
open class Dialog : DialogInterface {

    override fun dismiss() = Unit

    fun show() = Unit

    fun setTitle(title: CharSequence?) = Unit

    fun setOnCancelListener(listener: DialogInterface.OnCancelListener?) = Unit

    fun setOnDismissListener(listener: DialogInterface.OnDismissListener?) = Unit
}

open class AlertDialog : Dialog() {

    open class Builder {

        constructor()

        fun setTitle(title: CharSequence?): Builder = this

        fun setMessage(message: CharSequence?): Builder = this

        fun setPositiveButton(text: CharSequence?, listener: DialogInterface.OnClickListener?): Builder = this

        fun setNegativeButton(text: CharSequence?, listener: DialogInterface.OnClickListener?): Builder = this

        fun setCancelable(cancelable: Boolean): Builder = this

        fun setView(view: android.view.View?): Builder = this

        fun create(): AlertDialog = AlertDialog()

        fun show(): AlertDialog = AlertDialog()
    }
}

open class ProgressDialog : Dialog() {

    fun setProgress(value: Int) = Unit

    fun setMax(max: Int) = Unit

    companion object {

        const val STYLE_HORIZONTAL = 1
        const val STYLE_SPINNER = 0

        @JvmStatic
        fun show(context: android.content.Context?, title: CharSequence?, message: CharSequence?): ProgressDialog =
            ProgressDialog()
    }
}
