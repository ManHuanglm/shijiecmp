package com.shiping.desktop.ui

import com.shiping.desktop.player.PlayerControllerFactory
import com.shiping.desktop.player.PlayerFrame
import com.shiping.desktop.player.PlayerKernel
import com.shiping.desktop.util.AppLog
import com.shiping.shared.prefs.KeyValueStore
import javax.swing.SwingUtilities

/**
 * 桌面直链播放宿主：独立 PlayerFrame 窗口承载局域网推送等无详情上下文的播放。
 * EDT 上判断存活窗口：在播窗口切流，死窗口/无窗口则按当前内核新建。
 */
class DesktopDirectPlaybackHost : DirectPlaybackHost {

    /** 主 ViewModel（晚绑定：Main.kt 组装后回接 closePlayer/switchBiliQuality） */
    @Volatile
    private var app: AppViewModel? = null

    fun bind(app: AppViewModel) {
        this.app = app
    }

    /** 推送播放窗口（局域网推送直链；页内播放不经过它） */
    private var playerFrame: PlayerFrame? = null

    private val playerCallbacks = object : PlayerFrame.Callbacks {
        override fun onClose() {
            app?.closePlayer()
        }

        override fun onSwitchQuality(session: PlaybackSession, qn: Int, resumeMs: Long) {
            app?.switchBiliQuality(session, qn, resumeMs)
        }

        // 推送会话无选集/清晰度上下文，对应按钮不展示，回调保底空实现
        override fun onPlayEpisode(sourceIndex: Int, episodeIndex: Int) = Unit

        override fun onDownloadEpisode(episodeIndex: Int) = Unit
    }

    override fun openOrCreate(session: PlaybackSession, kernelId: String, prefs: KeyValueStore) {
        SwingUtilities.invokeLater {
            try {
                val existing = playerFrame
                if (existing != null && existing.isShowing) {
                    existing.playSession(session)
                    return@invokeLater
                }
                // 残留窗口（已关未清）先释放，再新建
                existing?.closeFrame()
                val kernel = PlayerKernel.entries.firstOrNull { it.id == kernelId } ?: PlayerKernel.MPV
                AppLog.i("Player", "创建推送播放窗口 kernel=${kernel.displayName}")
                val frame = PlayerFrame(
                    PlayerControllerFactory.create(kernel, prefs),
                    session,
                    playerCallbacks,
                )
                playerFrame = frame
                frame.isVisible = true
            } catch (e: Throwable) {
                AppLog.e("Player", "推送播放窗口处理失败: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    override fun close() {
        SwingUtilities.invokeLater {
            playerFrame?.closeFrame()
            playerFrame = null
        }
    }
}
