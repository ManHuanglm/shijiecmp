package com.shiping.desktop.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LiveTv
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import com.shiping.shared.prefs.KeyValueStore
import com.shiping.shared.prefs.PrefsKeys

/** 主导航 Tab 位置：底部导航栏 / 左上抽屉 */
enum class TabPosition { BOTTOM, DRAWER }

/** 主导航 Tab 定义（双端壳层与「Tab 设置」共用；androidOnly 页面桌面端不展示） */
data class MainTabSpec(
    val label: String,
    val screen: Screen,
    val icon: ImageVector,
    val androidOnly: Boolean = false,
)

/** 主导航全部 Tab（顺序即展示顺序；下载页双端均由各自引擎承载） */
val mainTabs = listOf(
    MainTabSpec("首页", Screen.Home, Icons.Outlined.Home),
    MainTabSpec("搜索", Screen.Search, Icons.Outlined.Search),
    MainTabSpec("直播", Screen.Tv, Icons.Outlined.LiveTv),
    MainTabSpec("收藏", Screen.Favorites, Icons.Outlined.Favorite),
    MainTabSpec("历史", Screen.History, Icons.Outlined.History),
    MainTabSpec("下载", Screen.Downloads, Icons.Outlined.Download),
    MainTabSpec("我的", Screen.Mine, Icons.Outlined.Person),
    MainTabSpec("设置", Screen.Settings, Icons.Outlined.Settings),
)

/** 首次使用默认隐藏的 Tab（存储为空时套用；可在「Tab 设置」重新开启） */
private val DEFAULT_HIDDEN_TABS = setOf("搜索", "收藏", "历史")

/** 可隐藏 Tab（首页/设置固定显示） */
val hideableTabs: Set<String> = mainTabs.map { it.label }.toSet() - setOf("首页", "设置")

/** 主导航 Tab 显隐与位置状态（设置页修改，双端壳层响应式过滤） */
object MainTabsState {
    var hidden by mutableStateOf(emptySet<String>())
        private set
    var position by mutableStateOf(TabPosition.BOTTOM)
        private set

    /** 当前应展示的 Tab（按显隐过滤，顺序保持 mainTabs 定义；androidOnly 由各端自行过滤） */
    val visibleTabs: List<MainTabSpec>
        get() = mainTabs.filter { it.label !in hidden }

    /** 应用启动时从偏好恢复（KEY_HIDE_TABS 逗号分隔；位置 bottom/drawer） */
    fun init(prefs: KeyValueStore) {
        val savedHidden = prefs.getString(PrefsKeys.KEY_HIDE_TABS)
        hidden = if (savedHidden.isBlank()) {
            DEFAULT_HIDDEN_TABS
        } else {
            savedHidden.split(',').map { it.trim() }.filter { it.isNotBlank() }.toSet()
        }
        position = if (prefs.getString(PrefsKeys.KEY_TAB_POSITION) == "drawer") {
            TabPosition.DRAWER
        } else {
            TabPosition.BOTTOM
        }
    }

    /** 设置/恢复某个 Tab 显隐（持久化由设置页写入 prefs） */
    fun setHidden(label: String, hiddenTab: Boolean) {
        hidden = if (hiddenTab) hidden + label else hidden - label
    }

    /** 设置 Tab 位置并持久化（底部导航 / 左上抽屉） */
    fun setPosition(value: TabPosition, prefs: KeyValueStore) {
        position = value
        prefs.put(PrefsKeys.KEY_TAB_POSITION, if (value == TabPosition.DRAWER) "drawer" else "bottom")
    }
}
