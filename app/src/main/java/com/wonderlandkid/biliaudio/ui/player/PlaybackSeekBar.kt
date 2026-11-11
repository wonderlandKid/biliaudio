package com.wonderlandkid.biliaudio.ui.player

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import kotlin.math.PI
import kotlin.math.sin

/**
 * 自绘播放进度条：全宽实时频谱 + 进度圆点。
 *
 * - 未播放部分灰色、已播放部分主题色，整条从歌曲开始就可见/跳动；
 * - 真实频谱（Visualizer）优先，未授权回退合成包络；
 * - 支持点击/拖动定位、拖动时振幅压缩到 30%、无障碍进度语义。
 */
@Composable
internal fun PlaybackSeekBar(
    positionMs: Long,
    durationMs: Long,
    isPlaying: Boolean,
    spectrumBands: FloatArray,
    /** 当前曲目稳定种子：用于生成「整首波形」的不规则包络（不同歌曲形状不同）。 */
    waveSeed: Int,
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
    // 频谱进度条（v0.38，成熟 seekbar 中常见的「实时频谱/波形」方案）：
    // - 整条由竖条频谱组成，已播放部分用主题色「染色」，未播放部分完全透明；
    // - 播放时优先用真实频谱（Visualizer 授权），否则用合成频谱回退；
    // - 拖动 seek 时把振幅压缩到 30%，避免跳动影响定位手感。
    val waveTransition = rememberInfiniteTransition(label = "seekSpectrum")
    val wavePhase by waveTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = 2_400,
                easing = BiliMotion.Emphasized,
            ),
            repeatMode = RepeatMode.Restart,
        ),
        label = "seekSpectrumPhase",
    )
    val spectrumActive by animateFloatAsState(
        targetValue = if (isPlaying) 1f else 0f,
        animationSpec = tween(
            durationMillis = 420,
            easing = BiliMotion.Emphasized,
        ),
        label = "seekSpectrumActive",
    )
    val spectrumCompression by animateFloatAsState(
        targetValue = if (dragging) 0.3f else 1f,
        animationSpec = tween(
            durationMillis = BiliMotion.Short4,
            easing = BiliMotion.Emphasized,
        ),
        label = "seekSpectrumCompression",
    )

    val density = LocalDensity.current
    val thumbRadiusPx = with(density) { thumbRadius.toPx() }
    val barWidthPx = with(density) { 4.dp.toPx() }
    val barGapPx = with(density) { 3.dp.toPx() }
    val minBarHeightPx = with(density) { 2.dp.toPx() }
    val maxBarHeightPx = with(density) { 28.dp.toPx() }
    val haloRadiusPx = with(density) { 2.dp.toPx() }

    val activeColor = MaterialTheme.colorScheme.primary
    val futureColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.34f)
    val haloColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
    val liveBands = spectrumBands

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp)
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
            val centerY = size.height / 2f
            val activeWidth = size.width * progress
            val barCount = (((size.width + barGapPx) / (barWidthPx + barGapPx)).toInt())
                .coerceIn(12, 64)
            val step = if (barCount > 1) (size.width - barWidthPx) / (barCount - 1) else 0f
            val scale = spectrumActive * spectrumCompression
            val hasLiveSpectrum = liveBands.size >= 2

            // 全宽频谱：未播放部分用灰色，已播放部分用主题色；这样歌曲一开始就能看到整条频谱在跳动。
            // 60fps 的 wavePhase 让竖条持续轻微起伏，真实频段（~20Hz 回调）负责整体能量包络。
            for (index in 0 until barCount) {
                val left = index * step
                val barCenter = left + barWidthPx / 2f
                val fraction = index / (barCount - 1f).coerceAtLeast(1f)
                // 整首「波形」的不规则包络：由曲目种子生成平滑噪声，避免 8 段频谱插值导致的
                // 规则条纹；两端低、中间高，像 SoundCloud/CamelPlayer 的整曲 waveform。
                val noise = (
                    hashNoise(index, waveSeed) * 0.60f +
                        hashNoise(index - 1, waveSeed) * 0.20f +
                        hashNoise(index + 1, waveSeed) * 0.20f
                    )
                val envelope = 0.52f + 0.48f * sin(fraction * PI.toFloat())
                val energy = if (hasLiveSpectrum) {
                    liveBands.average().toFloat().coerceIn(0f, 1f)
                } else {
                    (0.5f + 0.5f * sin(wavePhase * 1.3f)).coerceIn(0f, 1f)
                }
                // 整体能量让波形随歌曲律动；单条相位微动保持 60fps 的细腻起伏。
                val shimmer = 0.90f + 0.10f * sin(index * 0.9f + wavePhase * 2.2f)
                val norm = (
                    (0.10f + 0.90f * noise) * envelope *
                        (0.55f + 0.75f * energy) * shimmer
                    ).coerceIn(0f, 1f)
                val barHeight = minBarHeightPx + (maxBarHeightPx - minBarHeightPx) * norm * scale
                val played = barCenter <= activeWidth + 0.5f
                drawRoundRect(
                    color = if (played) activeColor else futureColor,
                    topLeft = Offset(left, centerY - barHeight / 2f),
                    size = Size(barWidthPx, barHeight),
                    cornerRadius = CornerRadius(barWidthPx / 2f),
                )
            }

            // 小圆点：位于已播放/未播放边界，像给频谱线「染色」。
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

/** 稳定、平滑的伪随机噪声：同一曲目的同一位置永远相同，用来画不规则整曲波形。 */
private fun hashNoise(index: Int, seed: Int): Float {
    var h = index * 374761393 + seed * 668265263
    h = (h xor (h ushr 13)) * 1274126177
    h = h xor (h ushr 16)
    return (h and 0x7fffffff) / 2147483647f
}
