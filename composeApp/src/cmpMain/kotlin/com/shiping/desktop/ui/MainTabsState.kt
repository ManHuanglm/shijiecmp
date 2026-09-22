package com.shiping.desktop.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.shiping.shared.prefs.KeyValueStore
import com.shiping.shared.prefs.PrefsKeys

/** 可隐藏的侧边栏 Tab（设置页「Tab 设置」配置；首页/设置固定显示） */
val hideableTabs = listOf("搜索", "直播", "收藏", "历史")

/** 侧边栏 Tab 显隐状态（设置页修改，主窗口响应式过滤） */
object MainTabsState {
    var hidden by mutableStateOf(emptySet<String>())
        private set

    /** 应用启动时从偏好恢复（KEY_HIDE_TABS 逗号分隔） */
    fun init(prefs: KeyValueStore) {
        hidden = prefs.getString(PrefsKeys.KEY_HIDE_TABS)
            .split(',').map { it.trim() }.filter { it.isNotBlank() }.toSet()
    }

    /** 设置/恢复某个 Tab 显隐（持久化由设置页写入 prefs） */
    fun setHidden(label: String, hiddenTab: Boolean) {
        hidden = if (hiddenTab) hidden + label else hidden - label
    }
}
