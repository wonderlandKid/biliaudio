package com.wonderlandkid.biliaudio.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.wonderlandkid.biliaudio.data.impl.SplitTextParser
import com.wonderlandkid.biliaudio.ui.theme.Spacing

/**
 * 分割点文本的时间格式选择器（分曲编辑 / 导入预览共用）。
 *
 * 用户可手动指定，消除两段式时间的歧义（`1:03` 是 1 分 3 秒还是 1 小时 3 分）；
 * 演唱会/现场专辑这类总时长超过 99 分钟、分钟位写成 3 位数（如 `103:48`）的列表选「分:秒」即可。
 */
@Composable
fun ImportTimeFormatSelector(
    selected: SplitTextParser.TimeFormat,
    onSelect: (SplitTextParser.TimeFormat) -> Unit,
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Text(
            text = "时间格式",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            FilterChip(
                selected = selected == SplitTextParser.TimeFormat.AUTO,
                onClick = { onSelect(SplitTextParser.TimeFormat.AUTO) },
                label = { Text("自动") },
            )
            FilterChip(
                selected = selected == SplitTextParser.TimeFormat.MINUTES_SECONDS,
                onClick = { onSelect(SplitTextParser.TimeFormat.MINUTES_SECONDS) },
                label = { Text("分:秒") },
            )
            FilterChip(
                selected = selected == SplitTextParser.TimeFormat.HOURS_MINUTES_SECONDS,
                onClick = { onSelect(SplitTextParser.TimeFormat.HOURS_MINUTES_SECONDS) },
                label = { Text("时:分:秒") },
            )
        }
        Text(
            text = when (selected) {
                SplitTextParser.TimeFormat.AUTO ->
                    "自动识别：三段按「时:分:秒」，两段按「分:秒」（分钟可超过 99）"
                SplitTextParser.TimeFormat.MINUTES_SECONDS ->
                    "两段按「分:秒」解析：103:48 = 1 小时 43 分 48 秒"
                SplitTextParser.TimeFormat.HOURS_MINUTES_SECONDS ->
                    "两段按「时:分」解析：1:03 = 1 小时 3 分；三段仍按「时:分:秒」"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
