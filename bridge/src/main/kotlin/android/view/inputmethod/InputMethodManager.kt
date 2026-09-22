package android.view.inputmethod

/** 桌面 stub：输入法管理器（桌面端无输入法体系） */
object InputMethodManager {

    @JvmStatic
    fun hideSoftInputFromWindow(token: Any?, flags: Int): Boolean = false

    @JvmStatic
    fun showSoftInput(view: android.view.View?, flags: Int): Boolean = false
}
