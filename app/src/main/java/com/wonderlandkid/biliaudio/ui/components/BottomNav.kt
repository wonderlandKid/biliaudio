package com.wonderlandkid.biliaudio.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/** 主导航三个 Tab。 */
enum class MainTab(val label: String, val icon: ImageVector) {
    HOME("发现", Icons.Rounded.Explore),
    LIBRARY("音乐库", Icons.Rounded.LibraryMusic),
    SETTINGS("设置", Icons.Rounded.Settings),
}

@Composable
fun BiliBottomBar(
    current: MainTab,
    onSelect: (MainTab) -> Unit,
) {
    NavigationBar(
        containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
    ) {
        MainTab.entries.forEach { tab ->
            NavigationBarItem(
                selected = current == tab,
                onClick = { onSelect(tab) },
                icon = { Icon(tab.icon, contentDescription = tab.label) },
                label = { Text(tab.label) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = androidx.compose.material3.MaterialTheme.colorScheme.onPrimaryContainer,
                    selectedTextColor = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                    indicatorColor = androidx.compose.material3.MaterialTheme.colorScheme.primaryContainer,
                    unselectedIconColor = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        }
    }
}
