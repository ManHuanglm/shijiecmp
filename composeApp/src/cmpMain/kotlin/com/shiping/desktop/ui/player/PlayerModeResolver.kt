package com.shiping.desktop.ui.player

import com.shiping.desktop.ui.PagePlayerScope

/**
 * 模式推断（纯派生，无副作用）：PIP 优先级最高，直播/点播由页内作用域决定，
 * 全屏标志由页面布局真源（AppViewModel.detailFullscreen/tvFullscreen）提供。
 */
fun inferMode(
    scope: PagePlayerScope,
    isFullscreen: Boolean,
    isPip: Boolean = false,
): PlayerMode = when {
    isPip -> PlayerMode.PIP
    scope == PagePlayerScope.TV && isFullscreen -> PlayerMode.LIVE_FULLSCREEN
    scope == PagePlayerScope.TV -> PlayerMode.LIVE_INLINE
    isFullscreen -> PlayerMode.FULLSCREEN
    else -> PlayerMode.INLINE
}
