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
 * 页面区块容器（v0.32.0：容器族「Section」）。
 *
 * 只负责「页面左右留白 + 区块标题/操作 + 内容槽」，**不再强制内容一定是卡片**：
 * - [BiliSectionContainer.Card]：默认，内容包在 [BiliGroup] 卡片里（发现页/列表页常用）；
 * - [BiliSectionContainer.Plain]：内容直接放在页面背景上（适合网格、横向墙、编辑式列表等）；
 * - 内容槽是普通 [ColumnScope]，可以放任意 Composable（不是只能放 item/行）。
 *
 * 容器族的完整说明见 docs/DESIGN_SYSTEM.md「容器族」；页面 = 导航节点 + 自由组合容器，
 * 导航拓扑树与页面内容结构是两件事。
 */
enum class BiliSectionContainer { Card, Plain }

@Composable
fun BiliSection(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    container: BiliSectionContainer = BiliSectionContainer.Card,
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
        when (container) {
            BiliSectionContainer.Card -> BiliGroup(contentPadding = PaddingValues(0.dp)) { content() }
            BiliSectionContainer.Plain -> Column(content = content)
        }
    }
}
