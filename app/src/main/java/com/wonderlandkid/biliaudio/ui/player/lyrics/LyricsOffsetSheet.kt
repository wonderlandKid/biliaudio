package com.wonderlandkid.biliaudio.ui.player.lyrics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.wonderlandkid.biliaudio.ui.theme.Spacing

/**
 * 歌词偏移面板（T4 弹层，ModalBottomSheet）。
 *
 * 调整立即生效并持久化到本机歌词快照（[LyricsViewModel.adjustOffset]）。
 * 约定：正数 = 歌词提前显示；网络歌词常见需要 +0.1s ~ +0.5s 微调。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LyricsOffsetSheet(
    delayMs: Long,
    onAdjust: (Long) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.xl, vertical = Spacing.md),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.Tune,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(Spacing.xs))
                Text(
                    text = "歌词偏移",
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            Spacer(Modifier.height(Spacing.xxs))
            Text(
                text = "正数 = 歌词提前显示；每档微调 0.1s，快速校正 0.5s",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Spacing.lg))
            Text(
                text = formatLyricOffset(delayMs),
                style = MaterialTheme.typography.displayMedium,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = "当前偏移",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(Spacing.xl))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                OffsetButton(text = "-0.5s", onClick = { onAdjust(-500L) })
                OffsetButton(text = "-0.1s", onClick = { onAdjust(-100L) })
                OffsetButton(text = "+0.1s", onClick = { onAdjust(100L) })
                OffsetButton(text = "+0.5s", onClick = { onAdjust(500L) })
            }
            Spacer(Modifier.height(Spacing.sm))
            TextButton(
                onClick = onReset,
                enabled = delayMs != 0L,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("重置为 0")
            }
            Spacer(Modifier.height(Spacing.md))
        }
    }
}

@Composable
private fun RowScope.OffsetButton(
    text: String,
    onClick: () -> Unit,
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = Modifier.weight(1f),
        contentPadding = PaddingValues(
            horizontal = Spacing.sm,
            vertical = Spacing.xs,
        ),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
        )
    }
}
