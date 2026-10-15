package com.shiping.desktop.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 主导航抽屉内容（「Tab 设置」位置选左上抽屉时，双端壳层共用）：
 * 品牌「视界」+ 按显隐设置过滤后的 Tab 导航项。
 */
@Composable
internal fun MainNavDrawerContent(currentScreen: Screen, onSelect: (Screen) -> Unit) {
    ModalDrawerSheet {
        Text(
            text = "视界",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
        MainTabsState.visibleTabs.forEach { tab ->
            NavigationDrawerItem(
                label = { Text(text = tab.label) },
                icon = { Icon(imageVector = tab.icon, contentDescription = tab.label) },
                selected = currentScreen::class == tab.screen::class,
                onClick = { onSelect(tab.screen) },
                modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
            )
        }
    }
}
