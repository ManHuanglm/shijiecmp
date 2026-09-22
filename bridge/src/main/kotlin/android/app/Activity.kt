package android.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Resources

/**
 * 桌面 stub：android.app.Activity（payload 经 InitOrigin.getActivity 反射获取后
 * 会调用组件信息/UI 调度等方法，提供无害默认实现避免中断取播放地址的流程）。
 */
open class Activity : Context() {

    /** payload 经反射读取该字段筛选未暂停的 Activity（InitOrigin.getActivity） */
    @JvmField
    var paused: Boolean = false

    private val componentName = ComponentName("com.shiping.desktop", "Activity")

    private val window = android.view.Window()

    private val resources = Resources()

    open fun isFinishing(): Boolean = false

    open fun runOnUiThread(action: Runnable?) {
        action?.run()
    }

    open fun finish() = Unit

    open fun getComponentName(): ComponentName = componentName

    open fun getIntent(): Intent = Intent()

    open fun getWindow(): android.view.Window = window

    open fun getResources(): Resources = resources

    open fun getApplication(): Application = Application()

    open fun hasWindowFocus(): Boolean = true

    open fun startActivity(intent: Intent?) = Unit

    override fun getPackageName(): String = "com.shiping.desktop"
}
