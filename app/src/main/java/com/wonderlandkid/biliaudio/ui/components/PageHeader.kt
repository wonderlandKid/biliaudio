package com.wonderlandkid.biliaudio.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wonderlandkid.biliaudio.ui.theme.Spacing

/**
 * 三个主页面（发现 / 音乐库 / 设置）统一的大标题页头。
 *
 * 规范（v0.6.0 起所有 Tab 页必须用这个，保证左上角位置一致）：
 * - 页面内容容器顶部留 [Spacing.md]；
 * - 标题左边距 [Spacing.lg]，字体 displayMedium；
 * - 副标题与标题间距 [Spacing.xs]；
 * - 页头内不放额外 top padding（避免三页不一致）。
 *
 * @param title 大标题
 * @param subtitle 统计/说明行，可空
 * @param actions 标题右侧操作（可选，目前三个 Tab 页未使用）
 */
@Composable
fun BiliPageHeader(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                style = MaterialTheme.typography.displayMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            actions()
        }
        if (!subtitle.isNullOrBlank()) {
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Tab 页内容容器统一顶部留白（与 [BiliPageHeader] 搭配）。 */
val PageContentTopPadding = Spacing.md
