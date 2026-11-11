package com.wonderlandkid.biliaudio.ui.library

import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 索引栏宽度（竖排字母条）。 */
private val BAR_WIDTH = 22.dp

/** 拖动时大字气泡直径。 */
private val BUBBLE_SIZE = 52.dp

/**
 * 右侧「# A B C …」快速索引栏。
 *
 * - 竖排展示 [labels]，点按 / 上下拖动都可以选中；
 * - 选中项（[activeLabelProvider] 返回的当前分组标签）用主题色高亮；
 * - 拖动时在索引栏左侧显示大字气泡，松手消失；
 * - 仅在非搜索状态且列表非空时由调用方显示。
 *
 * @param onSelect 选中标签回调（调用方负责滚动列表到对应分组）
 * @param activeLabelProvider 当前高亮字母的读取回调：**在索引栏内部读取**，
 *        避免父作用域（Lazy 列表所在 composable）因滚动跨字母而重组整个列表。
 */
@Composable
fun AlphabetIndexBar(
    labels: List<String>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    activeLabelProvider: () -> String? = { null },
) {
    if (labels.isEmpty()) return
    var bubbleLabel by remember(labels) { mutableStateOf<String?>(null) }
    val activeLabel = activeLabelProvider()
    val hapticFeedback = LocalHapticFeedback.current

    Box(modifier = modifier.width(BAR_WIDTH)) {
        // 字母列
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .align(Alignment.CenterEnd),
            verticalArrangement = Arrangement.SpaceEvenly,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            labels.forEach { label ->
                val selected = label == activeLabel
                Text(
                    text = label,
                    fontSize = 9.sp,
                    lineHeight = 10.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    color = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }

        // 手势层：覆盖整条索引栏，按下即选中，拖动时连续更新
        Box(
            modifier = Modifier
                .matchParentSize()
                .pointerInput(labels) {
                    val barHeight = size.height.toFloat().coerceAtLeast(1f)
                    fun indexAt(y: Float): Int =
                        ((y / barHeight) * labels.size).toInt().coerceIn(0, labels.lastIndex)
                    awaitPointerEventScope {
                        while (true) {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            var currentIndex = indexAt(down.position.y)
                            bubbleLabel = labels[currentIndex]
                            onSelect(labels[currentIndex])
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            down.consume()
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                                change.consume()
                                val index = indexAt(change.position.y)
                                if (index != currentIndex) {
                                    currentIndex = index
                                    bubbleLabel = labels[index]
                                    onSelect(labels[index])
                                    hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                            }
                            bubbleLabel = null
                        }
                    }
                },
        )

        // 拖动时的大字气泡（画在索引栏左侧，不遮挡列表主体）
        bubbleLabel?.let { label ->
            Surface(
                // requiredSize 让气泡可以超出 22dp 宽的索引栏父容器（否则会被压成竖条）
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(x = -34.dp)
                    .requiredSize(BUBBLE_SIZE),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                shadowElevation = 4.dp,
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
        }
    }
}
