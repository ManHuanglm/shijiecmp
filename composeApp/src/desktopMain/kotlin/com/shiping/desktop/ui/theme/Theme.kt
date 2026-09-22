package com.shiping.desktop.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.shiping.desktop.data.prefs.DesktopPrefs

/** 主题色档位：名称 + 主色 + 浅色容器色（设置页「主题 → 颜色」可选） */
data class ThemeColorItem(val name: String, val primary: Color, val lightContainer: Color)

/** 可选主题色（对齐 app：蓝/黄/青/红/绿/紫/橙） */
val ThemeColors = listOf(
    ThemeColorItem("蓝", Color(0xFF2196F3), Color(0xFFBBDEFB)),
    ThemeColorItem("黄", Color(0xFFFFC107), Color(0xFFFFECB3)),
    ThemeColorItem("青", Color(0xFF00BCD4), Color(0xFFB2EBF2)),
    ThemeColorItem("红", Color(0xFFF44336), Color(0xFFFFCDD2)),
    ThemeColorItem("绿", Color(0xFF4CAF50), Color(0xFFC8E6C9)),
    ThemeColorItem("紫", Color(0xFF9C27B0), Color(0xFFE1BEE7)),
    ThemeColorItem("橙", Color(0xFFFF9800), Color(0xFFFFE0B2)),
)

/** 深色配色（primary/primaryContainer 随主题色变化） */
private fun darkScheme(color: ThemeColorItem) = darkColorScheme(
    primary = color.primary,
    onPrimary = Color.White,
    primaryContainer = color.lightContainer.copy(alpha = 0.30f),
    onPrimaryContainer = Color(0xFFE6E6E6),
    secondary = Color(0xFF7FB069),
    background = Color(0xFF121316),
    onBackground = Color(0xFFE6E6E6),
    surface = Color(0xFF1B1D22),
    onSurface = Color(0xFFE6E6E6),
    surfaceVariant = Color(0xFF252830),
    onSurfaceVariant = Color(0xFFA8ADB8),
    secondaryContainer = Color(0xFF232E42),
    onSecondaryContainer = Color(0xFFD5E2F7),
    error = Color(0xFFCF6679),
)

/** 浅色配色（primary/primaryContainer 随主题色变化） */
private fun lightScheme(color: ThemeColorItem) = lightColorScheme(
    primary = color.primary,
    onPrimary = Color.White,
    primaryContainer = color.lightContainer,
    onPrimaryContainer = Color(0xFF212121),
    background = Color(0xFFF6F7F9),
    surface = Color.White,
    secondaryContainer = Color(0xFFD9E6FB),
    onSecondaryContainer = Color(0xFF17325F),
)

/** 主题模式（设置页「界面/语言 → 主题」可选，持久化于 DesktopPrefs） */
object ThemeState {
    const val MODE_SYSTEM = 0
    const val MODE_LIGHT = 1
    const val MODE_DARK = 2

    var mode by mutableStateOf(MODE_SYSTEM)
        private set

    /** 当前主题色名称（对应 ThemeColors.name） */
    var colorName by mutableStateOf("蓝")
        private set

    /** 应用启动时从偏好恢复 */
    fun init(prefs: DesktopPrefs) {
        mode = prefs.getInt(DesktopPrefs.KEY_THEME_MODE, MODE_SYSTEM).coerceIn(MODE_SYSTEM, MODE_DARK)
        val stored = prefs.getString(DesktopPrefs.KEY_THEME_COLOR, "蓝")
        colorName = if (ThemeColors.any { it.name == stored }) stored else "蓝"
    }

    /** 切换主题模式（持久化由设置页写入 prefs） */
    fun update(value: Int) {
        mode = value.coerceIn(MODE_SYSTEM, MODE_DARK)
    }

    /** 切换主题色（持久化由设置页写入 prefs） */
    fun updateColor(name: String) {
        if (ThemeColors.any { it.name == name }) colorName = name
    }
}

/** 字体缩放比例状态（设置页 ± 调节，实时生效） */
object UiScale {
    var fontScale by mutableStateOf(1f)
        private set

    /** 应用启动时从偏好恢复（KEY_FONT_SCALE 存百分比） */
    fun init(prefs: DesktopPrefs) {
        fontScale = prefs.getInt(DesktopPrefs.KEY_FONT_SCALE, 100).coerceIn(80, 140) / 100f
    }

    /** 更新缩放比例（持久化由设置页写入 prefs） */
    fun update(scale: Float) {
        fontScale = scale.coerceIn(0.8f, 1.4f)
    }
}

/** 应用主题：模式跟随系统/浅色/深色，主题色 7 档，均实时生效 */
@Composable
fun ShipingTheme(content: @Composable () -> Unit) {
    val dark = when (ThemeState.mode) {
        ThemeState.MODE_LIGHT -> false
        ThemeState.MODE_DARK -> true
        else -> isSystemInDarkTheme()
    }
    val color = ThemeColors.firstOrNull { it.name == ThemeState.colorName } ?: ThemeColors.first()
    MaterialTheme(
        colorScheme = if (dark) darkScheme(color) else lightScheme(color),
        content = content,
    )
}

/**
 * 固定语义色板：不随主题模式/主题色切换的页面专用色，集中此处统一管理。
 * （对齐规范：页面内禁止散落 Color(0x...) 硬编码）
 */
object FixedPalette {

    /** 直播分组绿点（直播页 rail 与侧栏色板共用） */
    val TV_GROUP_GREEN = Color(0xFF4CAF50)

    /** 日志预览深色底 */
    val LOG_BACKGROUND = Color(0xFF121212)

    /** 日志等级文字色：V=灰 D=绿 I=浅灰 W=琥珀 E=红 */
    val LOG_LEVEL_V = Color(0xFF9E9E9E)
    val LOG_LEVEL_D = Color(0xFF4CAF50)
    val LOG_LEVEL_I = Color(0xFFE0E0E0)
    val LOG_LEVEL_W = Color(0xFFFFB300)
    val LOG_LEVEL_E = Color(0xFFE5484D)
}
