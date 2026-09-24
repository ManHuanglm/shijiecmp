package com.shiping.app

import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Bundle
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.shiping.app.data.player.Media3PlayerKernel
import com.shiping.app.data.player.PipController
import com.shiping.app.util.AppLog

/**
 * 直链全屏播放页（局域网推送等无详情上下文的会话；对齐桌面 PlayerFrame 位）。
 * 单实例 singleTop：在播时 onNewIntent 换流；横屏 + 息屏常亮；播放中离开自动进画中画。
 */
class DirectPlaybackActivity : Activity() {

    private var kernel: Media3PlayerKernel? = null
    private var playerView: PlayerView? = null

    @OptIn(UnstableApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val view = PlayerView(this)
        setContentView(
            view,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        playerView = view

        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        if (url.isBlank()) {
            AppLog.w("DirectPlay", "空播放地址，退出")
            finish()
            return
        }
        AppLog.i("DirectPlay", "起播: $title url=$url")
        // 独立内核实例：不与页内播放共用（headers 重建互不干扰）
        val k = Media3PlayerKernel(onRebuild = { rebuilt -> view.player = rebuilt })
        k.applicationContext = applicationContext
        kernel = k
        view.player = k.player
        // 挂载 PiP 候选内核：播放中 onUserLeaveHint 自动进画中画
        PipController.attachKernel(k)
        k.play(url, emptyMap(), 0L)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        if (url.isNotBlank()) kernel?.play(url, emptyMap(), 0L)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        PipController.tryEnterPip(this)
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        PipController.onPipModeChanged(isInPictureInPictureMode)
    }

    override fun onDestroy() {
        kernel?.let(PipController::detachKernel)
        playerView?.player = null
        kernel?.release()
        kernel = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "extra_url"
        const val EXTRA_TITLE = "extra_title"
    }
}
