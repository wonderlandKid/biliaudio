package com.wonderlandkid.biliaudio.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wonderlandkid.biliaudio.ui.theme.BiliLayout

/**
 * 页面区块模板（v0.24.0）：统一「页面左右留白 + 区块标题/操作 + 分组卡片」。
 *
 * 发现页四个区块、后续列表页的分区都用它；不要再在页面里手写
 * `BiliGroup(modifier = Modifier.padding(horizontal = ...))`。
 */
@Composable
fun BiliSection(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = BiliLayout.pageHorizontal),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = BiliLayout.sectionHeaderVertical),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.titleLarge)
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            actions()
        }
        BiliGroup(contentPadding = PaddingValues(0.dp)) {
            content()
        }
    }
}
