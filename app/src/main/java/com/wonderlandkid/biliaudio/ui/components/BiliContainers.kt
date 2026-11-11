package com.wonderlandkid.biliaudio.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wonderlandkid.biliaudio.ui.theme.BiliLayout
import com.wonderlandkid.biliaudio.ui.theme.Spacing

/**
 * 容器族「Shelf」：带标题/操作的横向滚动内容区。
 *
 * 与 [BiliSection] 一样，容器只负责「页面留白 + 标题栏 + 横向滚动」这些一致性属性；
 * 内容槽是 [LazyListScope]，可以放任意 item（专辑卡、榜单卡、歌单卡、自定义 Composable），
 * 不是只能放某一种行。页面也可以不用 Shelf，直接用 `BiliSection(container = Plain) { LazyRow {...} }`。
 *
 * 完整容器族说明见 docs/DESIGN_SYSTEM.md「容器族」。
 */
@Composable
fun BiliShelf(
    title: String? = null,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(
        start = BiliLayout.pageHorizontal,
        end = BiliLayout.pageHorizontal,
        top = Spacing.xs,
        bottom = Spacing.sm,
    ),
    horizontalArrangement: Arrangement.Horizontal = Arrangement.spacedBy(Spacing.md),
    content: LazyListScope.() -> Unit,
) {
    Column(modifier = modifier) {
        if (title != null) {
            SectionHeader(
                title = title,
                subtitle = subtitle,
                actionLabel = actionLabel,
                onAction = onAction,
            )
        }
        LazyRow(
            horizontalArrangement = horizontalArrangement,
            contentPadding = contentPadding,
            content = content,
        )
    }
}
