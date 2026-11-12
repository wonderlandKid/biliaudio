package com.wonderlandkid.biliaudio.ui.layers

import androidx.compose.ui.platform.LocalConfiguration

import androidx.compose.foundation.layout.width

import com.wonderlandkid.biliaudio.ui.components.coverModel

import com.wonderlandkid.biliaudio.ui.components.CoverImage

import androidx.compose.ui.unit.sp

import androidx.compose.ui.text.style.TextOverflow

import androidx.compose.ui.geometry.CornerRadius

import androidx.compose.ui.draw.clip

import androidx.compose.runtime.setValue

import androidx.compose.runtime.mutableStateOf

import androidx.compose.material3.Text

import androidx.compose.material3.Icon

import androidx.compose.material.icons.rounded.PlayArrow

import androidx.compose.material.icons.rounded.Pause

import androidx.compose.material.icons.Icons

import androidx.compose.foundation.shape.CircleShape

import androidx.compose.foundation.layout.size

import com.wonderlandkid.biliaudio.ui.theme.BiliMotion

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember

import androidx.compose.animation.core.tween

import androidx.compose.animation.core.Animatable

import androidx.compose.ui.geometry.RoundRect

import androidx.compose.foundation.shape.GenericShape

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wonderlandkid.biliaudio.player.PlaybackController
import com.wonderlandkid.biliaudio.ui.components.MiniPlayerBar
import com.wonderlandkid.biliaudio.ui.player.NowPlayingScreen
import dev.chrisbanes.haze.HazeState
import kotlin.math.roundToInt

/**
 * 播放器唯一表面（v0.48）。
 *
 * 迷你条和播放页不再是两个互相遮挡的层，而是**同一个 PlayerSurface**：
 * - p=0：surface 边界等于迷你条 rect；
 * - p=1：surface 边界铺满屏幕；
 * - 中间：surface 的 top/bottom/圆角连续插值，播放页内容在 surface 内被裁剪；
 * - 迷你条内容只在前 5% 淡出，surface 本身始终是一个圆角卡片。
 *
 * 这样收起时不会出现「sheet 背景 + 页面背景 + 迷你条背景」三张画面叠加的一帧。
 */
@Composable
internal fun BoxScope.PlayerSurfaceLayer(
    visible: Boolean,
    playbackController: PlaybackController,
    playerProgress: Float,
    playerPage: Int,
    onPageChanged: (Int) -> Unit,
    miniRect: Rect?,
    miniBottomPadding: Dp,
    screenHeight: Dp,
    statusBarInset: Dp,
    dragModifier: Modifier,
    onClick: () -> Unit,
    onBarBounds: (Rect) -> Unit,
    hazeState: HazeState?,
    onClose: () -> Unit,
) {
    if (!visible) return
    val state by playbackController.state.collectAsStateWithLifecycle()
    val density = LocalDensity.current
    var miniCoverRect by remember { mutableStateOf<Rect?>(null) }
    var miniTitleRect by remember { mutableStateOf<Rect?>(null) }
    var miniArtistRect by remember { mutableStateOf<Rect?>(null) }
    var miniPlayRect by remember { mutableStateOf<Rect?>(null) }
    var playerCoverMeasured by remember { mutableStateOf<MeasuredRect?>(null) }
    var playerTitleMeasured by remember { mutableStateOf<MeasuredRect?>(null) }
    var playerArtistMeasured by remember { mutableStateOf<MeasuredRect?>(null) }
    var playerPlayMeasured by remember { mutableStateOf<MeasuredRect?>(null) }
    // 从“无播放”进入迷你条时，整块 surface + 迷你条一起从下方淡入，避免突然出现。
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        appear.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = BiliMotion.MiniPlayerEnterMs,
                easing = BiliMotion.EmphasizedDecelerate,
            ),
        )
    }
    val appearOffsetPx = (1f - appear.value) * with(density) { 48.dp.toPx() }
    val screenHeightPx = with(density) { screenHeight.toPx() }
    val screenWidthPx = with(density) { LocalConfiguration.current.screenWidthDp.dp.toPx() }
    val rect = miniRect
    if (rect == null) {
        // 第一帧还没测到迷你条位置时只画迷你条，避免画错位置的 surface 闪一下。
        MiniPlayerBar(
            state = state,
            onClick = onClick,
            onTogglePlay = { playbackController.togglePlayPause() },
            onPrevious = { playbackController.previous() },
            onNext = { playbackController.next() },
            hazeState = hazeState,
            surfaceOpaque = playerProgress > 0.001f,
            dragModifier = dragModifier,
            onBarBounds = onBarBounds,
            onCoverBounds = { miniCoverRect = it },
            onTitleBounds = { miniTitleRect = it },
            onArtistBounds = { miniArtistRect = it },
            onPlayBounds = { miniPlayRect = it },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = miniBottomPadding),
        )
        return
    }

    // 迷你条实际 rect（窗口坐标）→ 当前 surface 边界；p=1 时顶到 0，底到屏幕底。
    val topPx = rect.top * (1f - playerProgress)
    val bottomPx = rect.bottom + (screenHeightPx - rect.bottom) * playerProgress
    val heightPx = (bottomPx - topPx).coerceAtLeast(1f)
    val shape = RoundedCornerShape(24.dp * (1f - playerProgress))

    // 底层页面压暗（仅播放页展开后）
    if (playerProgress > 0.001f) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .layer(AppLayer.Player - 1f)
                .graphicsLayer { alpha = playerProgress * 0.18f }
                .background(MaterialTheme.colorScheme.scrim),
        )
    }

    // 唯一 surface 的背景：迷你条时就是那张卡片，展开后就是全屏背景。
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .offset { IntOffset(0, (topPx + appearOffsetPx).roundToInt()) }
            .height(with(density) { heightPx.toDp() })
            .graphicsLayer {
                alpha = appear.value
                this.shape = shape
                clip = true
            }
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .layer(if (playerProgress >= 0.06f) AppLayer.Player else AppLayer.MiniPlayer),
    )

    // 播放页内容始终按“全屏尺寸”布局，再由 mask 裁剪到当前 surface 边界。
    // 不把内容放进高度变化的 surface，避免内部 Layout 约束变化导致标题/封面错位。
    if (playerProgress >= 0.06f) {
        val maskCornerPx = with(density) { 24.dp.toPx() } * (1f - playerProgress)
        val mask = GenericShape { size, _ ->
            addRoundRect(
                RoundRect(
                    left = 0f,
                    top = 0f,
                    right = size.width,
                    bottom = heightPx,
                    radiusX = maskCornerPx,
                    radiusY = maskCornerPx,
                ),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationY = topPx
                    this.shape = mask
                    clip = true
                }
                .layer(AppLayer.Player),
        ) {
            BackHandler(enabled = playerProgress > 0.01f) { onClose() }
            NowPlayingScreen(
                initialPage = playerPage,
                onPageChanged = onPageChanged,
                onCoverBounds = { playerCoverMeasured = MeasuredRect(it, topPx) },
                onTitleBounds = { playerTitleMeasured = MeasuredRect(it, topPx) },
                onArtistBounds = { playerArtistMeasured = MeasuredRect(it, topPx) },
                onPlayBounds = { playerPlayMeasured = MeasuredRect(it, topPx) },
                sharedElementsAlpha = 1f,
                coverAlpha = smoothStep(0.94f, 0.995f, playerProgress),
                contentAlpha = smoothStep(0.55f, 0.92f, playerProgress),
                modifier = Modifier.fillMaxSize(),
            )
            // 顶部下拉关闭热区（相对播放页内容）。
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .offset(y = statusBarInset)
                    .height(112.dp)
                    .then(dragModifier),
            )
        }
    }

    // 迷你条内容：它只在 p<5% 可见；始终保留组合以维持拖拽 pointerInput。
    // 迷你条内容（文字/按钮）快速淡出；卡片背景保留，直到被 PlayerSurface 覆盖。
    val miniContentAlpha = 1f - smoothStep(0f, 0.10f, playerProgress)
    MiniPlayerBar(
        state = state,
        onClick = onClick,
        onTogglePlay = { playbackController.togglePlayPause() },
        onPrevious = { playbackController.previous() },
        onNext = { playbackController.next() },
        hazeState = hazeState,
        surfaceOpaque = playerProgress > 0.001f,
        contentAlpha = miniContentAlpha,
        dragModifier = dragModifier,
        onBarBounds = onBarBounds,
        onCoverBounds = { miniCoverRect = it },
        onTitleBounds = { miniTitleRect = it },
        onArtistBounds = { miniArtistRect = it },
        onPlayBounds = { miniPlayRect = it },
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .layer(if (playerProgress >= 0.06f) AppLayer.MiniPlayer else AppLayer.MiniPlayer + 1f)
            .graphicsLayer {
                alpha = appear.value
                translationY = appearOffsetPx
            }
            .padding(bottom = miniBottomPadding),
    )

    // ---- 迷你条 → 播放页：只保留封面作为共享元素 ----
    // 标题/艺术家/控件都不插值，避免字号、省略号和布局在拖动中抽动；
    // 迷你条自身元素先淡出，封面单独放大/移动到播放页封面位置。
    val realAlpha = smoothStep(0.94f, 0.995f, playerProgress)
    if (playerProgress > 0.001f && realAlpha < 0.999f) {
        val targetCover = playerCoverMeasured
            ?.takeIf { it.rect.width > 1f && it.rect.height > 1f }
            ?.let { it.rect.translate(0f, -it.topOffset) }
        val sourceCover = miniCoverRect
        if (playerPage == 0 && sourceCover != null) {
            val morph = ((playerProgress - 0.06f) / 0.94f).coerceIn(0f, 1f)
            val coverRect = lerpRect(sourceCover, targetCover ?: sourceCover, morph)
            val cornerPx = with(density) { 24.dp.toPx() } * (1f - playerProgress)
            val overlayShape = GenericShape { size, _ ->
                addRoundRect(
                    RoundRect(
                        left = 0f,
                        top = topPx,
                        right = size.width,
                        bottom = bottomPx,
                        radiusX = cornerPx,
                        radiusY = cornerPx,
                    ),
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .layer(AppLayer.Player + 1f)
                    .graphicsLayer {
                        alpha = 1f - realAlpha
                        this.shape = overlayShape
                        clip = true
                    },
            ) {
                CoverImage(
                    url = state.queue.getOrNull(state.currentIndex)?.coverModel(),
                    modifier = Modifier
                        .offset { IntOffset(coverRect.left.roundToInt(), coverRect.top.roundToInt()) }
                        .size(
                            with(density) { coverRect.width.toDp() },
                            with(density) { coverRect.height.toDp() },
                        ),
                    cornerRadius = (14f + 8f * morph).dp,
                )
            }
        }
    }
}

private data class MeasuredRect(val rect: Rect, val topOffset: Float)

private fun lerpRect(a: Rect, b: Rect, t: Float): Rect = Rect(
    left = a.left + (b.left - a.left) * t,
    top = a.top + (b.top - a.top) * t,
    right = a.right + (b.right - a.right) * t,
    bottom = a.bottom + (b.bottom - a.bottom) * t,
)
