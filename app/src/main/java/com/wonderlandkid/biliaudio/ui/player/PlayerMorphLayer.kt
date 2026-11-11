package com.wonderlandkid.biliaudio.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wonderlandkid.biliaudio.player.PlaybackState
import com.wonderlandkid.biliaudio.ui.components.CoverImage
import com.wonderlandkid.biliaudio.ui.components.coverModel
import kotlin.math.roundToInt

/**
 * 迷你条 → 播放页的「单一表面」连续展开层（v0.34.0）。
 *
 * 播放页内容（NowPlayingScreen）在底层淡入；这一层负责把**迷你条的封面/标题/艺术家**
 * 按展开进度连续插值到播放页的对应位置，同时把圆角卡片背景从迷你条尺寸扩展到全屏：
 * - 0 = 与迷你条重合；
 * - 1 = 与播放页封面页首屏重合（此时本层淡出，交给真正的播放页）。
 *
 * 所有位置都是窗口坐标，由 MiniPlayerBar / NowPlayingScreen 通过 onGloballyPositioned 上报；
 * 本层只读这些矩形做插值，不改播放/数据逻辑。
 */
@Composable
fun PlayerMorphLayer(
    progress: Float,
    state: PlaybackState,
    miniBarRect: Rect?,
    miniCoverRect: Rect?,
    miniTitleRect: Rect?,
    miniArtistRect: Rect?,
    playerCoverRect: Rect?,
    playerTitleRect: Rect?,
    playerArtistRect: Rect?,
    modifier: Modifier = Modifier,
) {
    val p = progress.coerceIn(0f, 1f)
    if (p <= 0.001f || p >= 0.999f) return
    if (miniBarRect == null) return

    var origin by remember { mutableStateOf(Offset.Zero) }
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { origin = it.positionInWindow() },
    ) {
        val density = LocalDensity.current
        val screenW = constraints.maxWidth.toFloat()
        val screenH = constraints.maxHeight.toFloat()

        fun local(rect: Rect): Rect = Rect(
            left = rect.left - origin.x,
            top = rect.top - origin.y,
            right = rect.right - origin.x,
            bottom = rect.bottom - origin.y,
        )

        // 展开过程中给底层页面加一层很轻的 scrim：视觉上从「页面」过渡到「播放页」，
        // 卡片本身完全不透明（下方不能再透出页面文字）。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.45f * p)),
        )

        val miniBar = local(miniBarRect)
        val coverStart = miniCoverRect?.let(::local) ?: miniBar
        // 从歌词页收起/拉起时播放页没有封面：封面留在迷你条位置并淡出，
        // 卡片继续长到全屏、标题/艺术家飞到顶部标题区，保持连续展开。
        val coverEnd = playerCoverRect?.let(::local)
        val cover = if (coverEnd != null) lerpRect(coverStart, coverEnd, p) else coverStart
        val coverAlpha = if (coverEnd != null) 1f else (1f - p / 0.55f).coerceIn(0f, 1f)

        // 卡片背景：底部固定，顶部随封面一起上升（保证封面始终在卡片内）。
        val cardTop = minOf(
            lerp(miniBar.top, 0f, p),
            cover.top - with(density) { 8.dp.toPx() },
        )
        val cardBottom = lerp(miniBar.bottom, screenH, p)
        val cardLeft = lerp(miniBar.left, 0f, p)
        val cardRight = lerp(miniBar.right, screenW, p)
        val cardRadiusPx = lerp(with(density) { 24.dp.toPx() }, 0f, p)
        val coverRadius = lerp(14f, 22f, p).dp

        // 1) 逐渐长到全屏的卡片背景
        Box(
            modifier = Modifier
                .offset { IntOffset(cardLeft.roundToInt(), cardTop.roundToInt()) }
                .size(
                    width = with(density) { (cardRight - cardLeft).coerceAtLeast(1f).toDp() },
                    height = with(density) { (cardBottom - cardTop).coerceAtLeast(1f).toDp() },
                )
                .clip(RoundedCornerShape(with(density) { cardRadiusPx.toDp() }))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        )

        // 2) 封面：连续放大 + 移动到播放页封面位置；歌词页目标时淡出
        val coverScale = lerp(1.0f, if (state.isPlaying) 1f else 0.92f, p) * coverAlpha
        if (coverAlpha > 0.01f) {
            Box(
                modifier = Modifier
                    .offset { IntOffset(cover.left.roundToInt(), cover.top.roundToInt()) }
                    .size(
                        width = with(density) { cover.width.coerceAtLeast(1f).toDp() },
                        height = with(density) { cover.height.coerceAtLeast(1f).toDp() },
                    )
                    .graphicsLayer {
                        scaleX = coverScale
                        scaleY = coverScale
                        alpha = coverAlpha
                    },
            ) {
                CoverImage(
                    url = state.queue.getOrNull(state.currentIndex)?.coverModel(),
                    modifier = Modifier.fillMaxSize(),
                    cornerRadius = coverRadius,
                )
            }
        }

        // 3) 标题/艺术家：迷你条位置 → 播放页位置。
        // 注意：迷你标题在封面右侧，播放页标题在封面下方，两段路径会与放大的封面交叉，
        // 因此标题/艺术家在前 22% 内快速淡出，交给播放页内容（55% 后淡入）承接，避免视觉重叠。
        val textAlpha = (1f - p / 0.12f).coerceIn(0f, 1f)
        val miniTitle = miniTitleRect?.let(::local)
        val playerTitle = playerTitleRect?.let(::local)
        if (textAlpha > 0.01f && miniTitle != null && playerTitle != null) {
            val titleRect = lerpRect(miniTitle, playerTitle, p)
            val titleStyle = MaterialTheme.typography.titleMedium.copy(
                fontSize = lerp(
                    MaterialTheme.typography.titleMedium.fontSize.value,
                    MaterialTheme.typography.headlineMedium.fontSize.value,
                    p,
                ).sp,
                lineHeight = lerp(
                    MaterialTheme.typography.titleMedium.lineHeight.value,
                    MaterialTheme.typography.headlineMedium.lineHeight.value,
                    p,
                ).sp,
                fontWeight = FontWeight(lerp(500f, 700f, p).roundToInt()),
            )
            Box(
                modifier = Modifier
                    .offset { IntOffset(titleRect.left.roundToInt(), titleRect.top.roundToInt()) }
                    .width(with(density) { titleRect.width.coerceAtLeast(1f).toDp() })
                    .graphicsLayer { alpha = textAlpha },
            ) {
                Text(
                    text = state.currentTrack?.title.orEmpty(),
                    style = titleStyle,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // 4) 艺术家/专辑：小字连续过渡到播放页副标题
        val miniArtist = miniArtistRect?.let(::local)
        val playerArtist = playerArtistRect?.let(::local)
        if (textAlpha > 0.01f && miniArtist != null && playerArtist != null) {
            val artistRect = lerpRect(miniArtist, playerArtist, p)
            val artistStyle = MaterialTheme.typography.bodySmall.copy(
                fontSize = lerp(
                    MaterialTheme.typography.bodySmall.fontSize.value,
                    MaterialTheme.typography.bodyMedium.fontSize.value,
                    p,
                ).sp,
                lineHeight = lerp(
                    MaterialTheme.typography.bodySmall.lineHeight.value,
                    MaterialTheme.typography.bodyMedium.lineHeight.value,
                    p,
                ).sp,
            )
            Box(
                modifier = Modifier
                    .offset { IntOffset(artistRect.left.roundToInt(), artistRect.top.roundToInt()) }
                    .width(with(density) { artistRect.width.coerceAtLeast(1f).toDp() })
                    .graphicsLayer { alpha = textAlpha },
            ) {
                Text(
                    text = listOfNotNull(
                        state.queue.getOrNull(state.currentIndex)?.artist,
                        state.queue.getOrNull(state.currentIndex)?.albumTitle,
                    ).filter { it.isNotBlank() }.joinToString(" · "),
                    style = artistStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                        alpha = lerp(0.66f, 1f, p),
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun lerp(start: Float, stop: Float, fraction: Float): Float =
    start + (stop - start) * fraction

private fun lerpRect(start: Rect, stop: Rect, fraction: Float): Rect = Rect(
    left = lerp(start.left, stop.left, fraction),
    top = lerp(start.top, stop.top, fraction),
    right = lerp(start.right, stop.right, fraction),
    bottom = lerp(start.bottom, stop.bottom, fraction),
)
