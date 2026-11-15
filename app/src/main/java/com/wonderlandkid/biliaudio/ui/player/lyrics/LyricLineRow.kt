package com.wonderlandkid.biliaudio.ui.player.lyrics

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import com.wonderlandkid.biliaudio.data.model.LyricItem
import com.wonderlandkid.biliaudio.data.model.getSentenceContent
import com.wonderlandkid.biliaudio.ui.theme.BiliMotion
import com.wonderlandkid.biliaudio.ui.theme.Spacing

/** 空歌词行（纯音乐分行）的占位文案。 */
internal const val EMPTY_LINE_PLACEHOLDER = "· · ·"

/** 未演唱行透明度；已唱行略高，形成「已唱 / 未唱」层次（当前行恒为 1）。 */
private const val PAST_ALPHA = 0.50f
private const val FUTURE_ALPHA = 0.28f

/** 单行歌词：当前行加粗着色、当前逐字行做卡拉 OK 上色、翻译作副行；只有当前逐字行订阅帧级时间。 */
@Composable
internal fun LyricLineRow(
    line: LyricItem,
    currentTimeState: State<Long>?,
    isActive: Boolean,
    isPast: Boolean,
    isSynced: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    // 歌词统一用 titleLarge（22sp）；切行时不改字号，只用缩放/权重/颜色做强调，避免列表高度抖动。
    val mainStyle = MaterialTheme.typography.titleLarge.copy(
        // 整体略微加粗；当前行再重一档，保证在浅色背景上清楚可读。
        fontWeight = if (isActive) FontWeight.Bold else FontWeight.SemiBold,
    )
    val rowAlpha by animateFloatAsState(
        targetValue = when {
            isActive -> 1f
            isPast -> PAST_ALPHA
            else -> FUTURE_ALPHA
        },
        animationSpec = tween(
            durationMillis = BiliMotion.LyricsActiveMs,
            easing = BiliMotion.Emphasized,
        ),
        label = "lyricLineAlpha",
    )
    val mainColor by animateColorAsState(
        // 当前行用最深的前景色；其余行用 onSurfaceVariant，避免主题色在浅色背景上发灰。
        targetValue = if (isActive) colorScheme.onSurface else colorScheme.onSurfaceVariant,
        animationSpec = tween(
            durationMillis = BiliMotion.LyricsActiveMs,
            easing = BiliMotion.Emphasized,
        ),
        label = "lyricLineColor",
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = rowAlpha }
            .clickable(enabled = isSynced, onClick = onClick)
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        horizontalAlignment = Alignment.Start,
    ) {
        when (line) {
            is LyricItem.NormalLyric -> {
                Text(
                    text = line.content.ifBlank { EMPTY_LINE_PLACEHOLDER },
                    style = mainStyle,
                    color = mainColor,
                    textAlign = TextAlign.Start,
                    overflow = TextOverflow.Ellipsis,
                )
                line.translation?.takeIf { it.isNotBlank() }?.let { translation ->
                    TranslationLine(
                        text = translation,
                        isActive = isActive,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            is LyricItem.WordsLyric -> {
                if (isActive && isSynced && currentTimeState != null && line.words.isNotEmpty()) {
                    // 仅当前行读取帧级时间 → 每帧只重组这一行。
                    val time = currentTimeState.value
                    Text(
                        text = buildProgressWordsText(
                            line = line,
                            currentTimeMs = time,
                            sungColor = colorScheme.onSurface,
                            unsungColor = colorScheme.onSurfaceVariant,
                        ),
                        style = mainStyle,
                        textAlign = TextAlign.Start,
                    )
                } else {
                    Text(
                        text = line.getSentenceContent().ifBlank { EMPTY_LINE_PLACEHOLDER },
                        style = mainStyle,
                        color = mainColor,
                        textAlign = TextAlign.Start,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val translation = line.translation.firstOrNull { it.content.isNotBlank() }?.content
                if (!translation.isNullOrBlank()) {
                    TranslationLine(
                        text = translation,
                        isActive = isActive,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

/** 翻译行：主行下方小一号、低对比度。 */
@Composable
private fun TranslationLine(
    text: String,
    isActive: Boolean,
    style: TextStyle,
) {
    Spacer(Modifier.height(Spacing.xxs))
    Text(
        text = text,
        style = style.copy(fontWeight = FontWeight.Medium),
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
            alpha = if (isActive) 0.85f else 0.55f,
        ),
        textAlign = TextAlign.Start,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * 逐字卡拉 OK：每个 [LyricItem.WordsLyric.WordWithTiming] 一个 SpanStyle。
 *
 * 取色规则（与参考项目一致的推进思路，但改为纯文本 Span 上色，避免手机端自绘 mask 成本）：
 * - 已唱完的 word → [sungColor]；
 * - 尚未开始的 word → [unsungColor]；
 * - 正在唱的 word → 按 (time - startTime) / (endTime - startTime) 在 unsung→sung 之间 [lerp]。
 */
private fun buildProgressWordsText(
    line: LyricItem.WordsLyric,
    currentTimeMs: Long,
    sungColor: Color,
    unsungColor: Color,
): AnnotatedString = buildAnnotatedString {
    line.words.forEach { word ->
        val wordColor = when {
            currentTimeMs >= word.endTime -> sungColor
            currentTimeMs < word.startTime -> unsungColor
            else -> {
                val duration = (word.endTime - word.startTime).coerceAtLeast(1L)
                val progress = ((currentTimeMs - word.startTime).toFloat() / duration)
                    .coerceIn(0f, 1f)
                lerp(unsungColor, sungColor, progress)
            }
        }
        withStyle(SpanStyle(color = wordColor)) {
            append(word.content)
        }
    }
}
