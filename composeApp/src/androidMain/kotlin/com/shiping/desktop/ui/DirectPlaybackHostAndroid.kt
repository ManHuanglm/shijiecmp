package com.shiping.desktop.ui

import android.content.Intent
import com.shiping.app.DirectPlaybackActivity
import com.shiping.app.ShipingApp
import com.shiping.shared.prefs.KeyValueStore

/**
 * Android 直链播放宿主：启动全屏 DirectPlaybackActivity（singleTop 复用，onNewIntent 换流）。
 * 页内播放不经过它（与桌面 PlayerFrame 同位）。
 */
class DirectPlaybackHostAndroid : DirectPlaybackHost {

    override fun openOrCreate(session: PlaybackSession, kernelId: String, prefs: KeyValueStore) {
        val context = ShipingApp.context
        context.startActivity(
            Intent(context, DirectPlaybackActivity::class.java).apply {
                putExtra(DirectPlaybackActivity.EXTRA_URL, session.playUrl)
                putExtra(DirectPlaybackActivity.EXTRA_TITLE, session.title)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }

    /** Android 无独立窗口生命周期：Activity 自身返回键关闭，无需命令式释放 */
    override fun close() = Unit
}
