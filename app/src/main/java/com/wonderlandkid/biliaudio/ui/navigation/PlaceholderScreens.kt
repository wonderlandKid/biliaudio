package com.wonderlandkid.biliaudio.ui.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wonderlandkid.biliaudio.ui.theme.Spacing

/**
 * 临时占位页面。
 *
 * 各功能页面由对应 teammate 交付后，在 [AppNavHost] 中替换为真实 Screen；
 * 此文件在集成完成后可删除。
 */
@Composable
internal fun PlaceholderScreen(
    title: String,
    description: String? = null,
    onBack: (() -> Unit)? = null,
) {
    Box(
        modifier = Modifier.fillMaxSize().padding(Spacing.lg),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.headlineLarge)
            if (description != null) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
