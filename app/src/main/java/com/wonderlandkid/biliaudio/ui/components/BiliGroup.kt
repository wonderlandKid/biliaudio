package com.wonderlandkid.biliaudio.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wonderlandkid.biliaudio.ui.theme.Dimens
import com.wonderlandkid.biliaudio.ui.theme.Spacing

/**
 * 通用分组卡片 / 分组条目模板（v0.22.0）。
 *
 * 背景、圆角、分组内边距、标题/副标题字体、点击区域统一在这里维护。
 * 新页面不要再手写 `Surface(shape=RoundedCornerShape(22.dp), color=surfaceContainerHigh)`。
 *
 * 用法：
 * ```
 * BiliGroup(contentPadding = PaddingValues(0.dp)) {
 *     BiliGroupItem(title = "...", subtitle = "...", leading = {...}, onClick = {...})
 *     BiliGroupItem(...)
 * }
 * ```
 */
@Composable
fun BiliGroup(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = Spacing.lg, vertical = Spacing.sm),
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Dimens.cardCorner),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            modifier = Modifier.padding(contentPadding),
            content = content,
        )
    }
}

/**
 * 分组内标准条目：leading + 标题/副标题 + trailing，整行可点击。
 * 只负责“行内排版”，不自己画卡片背景；通常放在 [BiliGroup] 内。
 */
@Composable
fun BiliGroupItem(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: (@Composable RowScope.() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
) {
    val rowModifier = modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(14.dp))
        .let { base ->
            if (onClick != null) {
                base.clickable(enabled = enabled, onClick = onClick)
            } else {
                base
            }
        }
        .padding(horizontal = Spacing.xs, vertical = Spacing.sm + 2.dp)
    Row(
        modifier = rowModifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.let {
            it()
            Spacer(Modifier.width(Spacing.md))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing?.let {
            Spacer(Modifier.width(Spacing.sm))
            it()
        }
    }
}

/** 分组外的小节标题（与 Settings 页 SectionTitle 同一视觉 token）。 */
@Composable
fun BiliGroupSectionTitle(
    title: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = Spacing.xs, top = Spacing.xs),
    )
}

/** 分组内分割线（需要时显式插入，避免每个 item 自己猜间距）。 */
@Composable
fun BiliGroupDivider() {
    Spacer(Modifier.height(Spacing.xxs))
    androidx.compose.material3.HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
    )
    Spacer(Modifier.height(Spacing.xxs))
}
