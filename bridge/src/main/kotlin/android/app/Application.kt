package android.app

import android.content.Context
import android.os.Bundle

/**
 * 桌面 stub：android.app.Application（部分爬虫类的字段/签名引用该类型，
 * 继承 stub Context 以兼容 instance-of 与基础方法调用）。
 * ActivityLifecycleCallbacks：爬虫 jar 的 Init 引导实现该接口注册生命周期监听
 * （2026-10-13 easy 系 jar 引导报 ClassNotFoundException 后补），桌面空实现。
 */
open class Application : Context() {

    fun registerActivityLifecycleCallbacks(@Suppress("UNUSED_PARAMETER") callback: ActivityLifecycleCallbacks) {
    }

    fun unregisterActivityLifecycleCallbacks(@Suppress("UNUSED_PARAMETER") callback: ActivityLifecycleCallbacks) {
    }

    interface ActivityLifecycleCallbacks {

        fun onActivityCreated(activity: Application, savedInstanceState: Bundle?) {}

        fun onActivityStarted(activity: Application) {}

        fun onActivityResumed(activity: Application) {}

        fun onActivityPaused(activity: Application) {}

        fun onActivityStopped(activity: Application) {}

        fun onActivitySaveInstanceState(activity: Application, outState: Bundle?) {}

        fun onActivityDestroyed(activity: Application) {}
    }
}
