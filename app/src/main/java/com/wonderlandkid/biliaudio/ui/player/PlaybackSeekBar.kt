package com.wonderlandkid.biliaudio.ui.player

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wonderlandkid.biliaudio.ui.theme.Spacing

/**
 * 普通播放进度条：标准 Material 3 Slider + 拖动时暂存值。
 * 不再绘制频谱/波形，保持可读、可拖动和低交互成本。
 */
@Composable
internal fun PlaybackSeekBar(
    positionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val safeDuration = durationMs.coerceAtLeast(1L)
    val actual = (positionMs.toFloat() / safeDuration).coerceIn(0f, 1f)
    var dragging by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }

    Slider(
        value = if (isDragging) dragging else actual,
        onValueChange = {
            isDragging = true
            dragging = it
        },
        onValueChangeFinished = {
            onSeek((dragging * safeDuration).toLong())
            isDragging = false
        },
        colors = SliderDefaults.colors(
            thumbColor = MaterialTheme.colorScheme.primary,
            activeTrackColor = MaterialTheme.colorScheme.primary,
            inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.xs),
    )
}
