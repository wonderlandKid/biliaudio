package com.wonderlandkid.biliaudio.ui.player

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.wonderlandkid.biliaudio.ui.theme.BiliMotion
import kotlin.math.sqrt
import kotlinx.coroutines.isActive

/** 与 [com.wonderlandkid.biliaudio.player.RealSpectrumAnalyzer.BAND_COUNT] 对齐。 */
private const val SPECTRUM_BANDS = 48

/**
 * 播放进度条：实时 FFT 频谱（48 个对数频段）+ 进度圆点。
 *
 * - 竖条高度来自真实音频频谱（Visualizer → Hann → FFT），未授权时为低电平占位，不再使用假波形；
 * - 已播放部分主题色、未播放灰色，圆点位于边界；
 * - 真实回调约 20Hz，这里用 `withFrameNanos` 每帧把显示值平滑逼近目标，视觉 60fps；
 * - 拖动 seek 时振幅压缩到 30%，避免跳动影响定位手感。
 */
@Composable
internal fun PlaybackSeekBar(
    positionMs: Long,
    durationMs: Long,
    isPlaying: Boolean,
    spectrumBands: FloatArray,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val maxValue = durationMs.coerceAtLeast(1L).toFloat()
    var dragValue by remember { mutableStateOf<Float?>(null) }
    var dragging by remember { mutableStateOf(false) }
    val displayValue = (dragValue ?: positionMs.toFloat()).coerceIn(0f, maxValue)
    val progress = (displayValue / maxValue).coerceIn(0f, 1f)

    val thumbRadius by animateDpAsState(
        targetValue = if (dragging) 9.dp else 6.dp,
        animationSpec = tween(
            durationMillis = BiliMotion.Short4,
            easing = BiliMotion.Emphasized,
        ),
        label = "seekThumbRadius",
    )
    val spectrumCompression by animateFloatAsState(
        targetValue = if (dragging) 0.3f else 1f,
        animationSpec = tween(
            durationMillis = BiliMotion.Short4,
            easing = BiliMotion.Emphasized,
        ),
        label = "seekSpectrumCompression",
    )

    // 真实频谱回调频率约 20Hz；用逐帧缓动补到 60fps，避免「一卡一卡」。
    val latestBands by rememberUpdatedState(spectrumBands)
    val latestPlaying by rememberUpdatedState(isPlaying)
    val displayedBands = remember { FloatArray(SPECTRUM_BANDS) }
    val frameTick = remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (isActive) {
            withFrameNanos {
                for (index in 0 until SPECTRUM_BANDS) {
                    val target = if (latestPlaying) latestBands.getOrElse(index) { 0f } else 0f
                    val current = displayedBands[index]
                    val factor = if (target > current) 0.42f else 0.16f
                    displayedBands[index] = current + (target - current) * factor
                }
                frameTick.intValue++
            }
        }
    }

    val density = LocalDensity.current
    val thumbRadiusPx = with(density) { thumbRadius.toPx() }
    val minBarHeightPx = with(density) { 2.5.dp.toPx() }
    val maxBarHeightPx = with(density) { 40.dp.toPx() }
    val haloRadiusPx = with(density) { 2.dp.toPx() }

    val activeColor = MaterialTheme.colorScheme.primary
    val futureColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.34f)
    val haloColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .semantics {
                contentDescription = "播放进度"
                progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f)
            }
            .pointerInput(maxValue) {
                detectTapGestures { offset ->
                    val ratio = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                    onSeek((ratio * maxValue).toLong())
                }
            }
            .pointerInput(maxValue) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        dragging = true
                        dragValue = (
                            offset.x / size.width.toFloat() * maxValue
                            ).coerceIn(0f, maxValue)
                    },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        dragValue = (
                            change.position.x / size.width.toFloat() * maxValue
                            ).coerceIn(0f, maxValue)
                    },
                    onDragEnd = {
                        dragValue?.let { onSeek(it.toLong()) }
                        dragValue = null
                        dragging = false
                    },
                    onDragCancel = {
                        dragValue = null
                        dragging = false
                    },
                )
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            // 读取一帧计数器：displayedBands 是普通数组，需要靠这个 state 在每帧触发 draw 失效。
            val tick = frameTick.intValue
            if (tick < 0) return@Canvas
            val centerY = size.height / 2f
            val activeWidth = size.width * progress
            val step = size.width / SPECTRUM_BANDS
            val barWidth = step * 0.52f
            for (index in 0 until SPECTRUM_BANDS) {
                val left = index * step + (step - barWidth) / 2f
                val barCenter = left + barWidth / 2f
                // 平方根映射提高小信号的视觉高度，避免频谱看起来「一排小点」。
                val norm = sqrt(displayedBands[index].coerceIn(0f, 1f))
                val barHeight = minBarHeightPx +
                    (maxBarHeightPx - minBarHeightPx) * norm * spectrumCompression
                val played = barCenter <= activeWidth + 0.5f
                drawRoundRect(
                    color = if (played) activeColor else futureColor,
                    topLeft = Offset(left, centerY - barHeight / 2f),
                    size = Size(barWidth, barHeight),
                    cornerRadius = CornerRadius(barWidth / 2f),
                )
            }

            // 小圆点：位于已播放/未播放边界，给频谱线「染色」。
            val safeWidth = (size.width - thumbRadiusPx).coerceAtLeast(thumbRadiusPx)
            val centerX = activeWidth.coerceIn(thumbRadiusPx, safeWidth)
            drawCircle(
                color = haloColor,
                radius = thumbRadiusPx + haloRadiusPx,
                center = Offset(centerX, centerY),
            )
            drawCircle(
                color = activeColor,
                radius = thumbRadiusPx,
                center = Offset(centerX, centerY),
            )
        }
    }
}
