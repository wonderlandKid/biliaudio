package com.wonderlandkid.biliaudio.ui.layers

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wonderlandkid.biliaudio.player.PlaybackController
import com.wonderlandkid.biliaudio.ui.DiagTag
import com.wonderlandkid.biliaudio.ui.components.CoverImage
import com.wonderlandkid.biliaudio.ui.components.MiniPlayerBar
import com.wonderlandkid.biliaudio.ui.components.coverModel
import com.wonderlandkid.biliaudio.ui.diagNumber
import com.wonderlandkid.biliaudio.ui.diagProgress
import com.wonderlandkid.biliaudio.ui.diagRect
import com.wonderlandkid.biliaudio.ui.player.NowPlayingScreen
import com.wonderlandkid.biliaudio.ui.theme.BiliMotion
import com.wonderlandkid.biliaudio.util.Diag
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
 * v0.57.0 修复：
 * 1. 进场动画（v0.56 及以前）绑定在「本层首次组合」上，而专辑页等路由上本层在点播放前
 *    就已经处于组合中，动画早早跑完，导致迷你条**突然出现**。现在改为按「是否正在播放」
 *    驱动，并且迷你条只保留**一个稳定的调用点**（以前 rect 未测量时走 early-return 分支
 *    再渲染一个 MiniPlayerBar，测到 rect 后换成主分支实例，会中断 AnimatedVisibility 进场动画）。
 * 2. 顶部下拉关闭热区从「播放页内容」里提出来单独常驻组合：以前它随 `p>=0.06` 的播放页内容
 *    一起被销毁，如果手指还在拖动，draggable 节点在 detach 状态下会丢弃 onDragStopped，
 *    玩家进度就卡死在 0.06 附近，迷你条看起来「完全卡住」。现在热区跟随 surface 平移、
 *    收起态放到屏幕外，不会在拖拽中途消失。
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
    // 维测：dispose 回调里读不到最新参数，用 rememberUpdatedState 取当帧 progress。
    val latestProgress by rememberUpdatedState(playerProgress)

    // 维测：播放器表面层进入/退出组合（排查「迷你条突然出现/消失、层被销毁导致手势丢失」）。
    DisposableEffect(Unit) {
        Diag.log(
            DiagTag.SURFACE,
            "layer ENTER hasTrack=${state.currentTrack != null} progress=${diagProgress(playerProgress)}",
        )
        onDispose {
            Diag.log(DiagTag.SURFACE, "layer EXIT progress=${diagProgress(latestProgress)}")
        }
    }

    var miniCoverRect by remember { mutableStateOf<Rect?>(null) }
    var playerCoverMeasured by remember { mutableStateOf<MeasuredRect?>(null) }
    var coverTargetEverMeasured by remember { mutableStateOf(false) }
    var coverTargetAnomaly by remember { mutableStateOf(false) }

    val hasTrack = state.currentTrack != null
    // 从「无播放」进入迷你条时，整块 surface + 迷你条一起从下方淡入；停止播放时反向淡出。
    // key 必须是「是否正在播放」而不是层首次组合：专辑页/根 Tab 上本层一直存在，
    // 若在层组合时播放进场动画，等真正开始播放时动画早已结束，迷你条就会突然出现。
    val appear = remember { Animatable(if (hasTrack) 1f else 0f) }
    LaunchedEffect(hasTrack) {
        val target = if (hasTrack) 1f else 0f
        // 已经是目标值（例如首次组合时会话本来就无播放）就不做无意义的动画/日志。
        if (kotlin.math.abs(appear.value - target) < 0.001f) return@LaunchedEffect
        val durationMs =
            if (hasTrack) BiliMotion.MiniPlayerEnterMs else BiliMotion.MiniPlayerExitMs
        // 维测：进场/退场动画起止（每次开始/停止播放各一条，不会刷屏）。
        Diag.log(
            DiagTag.SURFACE,
            "appear ${if (hasTrack) "ENTER" else "EXIT"} from=${diagProgress(appear.value)} " +
                "duration=${durationMs}ms",
        )
        appear.animateTo(
            targetValue = if (hasTrack) 1f else 0f,
            animationSpec = tween(
                durationMillis = durationMs,
                easing = if (hasTrack) BiliMotion.EmphasizedDecelerate else BiliMotion.EmphasizedAccelerate,
            ),
        )
        Diag.log(
            DiagTag.SURFACE,
            "appear done value=${diagProgress(appear.value)} hasTrack=$hasTrack",
        )
    }

    // v0.59.0：必须用「真实窗口像素尺寸」而不是 LocalConfiguration.screenWidthDp（整数 dp 取整后
    // 会差 1~2px）。表面背景比迷你条胶囊宽 1~2px 时，圆角处会露出 surfaceContainer 色块
    // （浅色主题下看起来就是胶囊外接矩形有一层白底）。容器未测量时回退旧值。
    val windowSize = LocalWindowInfo.current.containerSize
    val screenHeightPx =
        if (windowSize.height > 0) windowSize.height.toFloat() else with(density) { screenHeight.toPx() }
    val screenWidthPx =
        if (windowSize.width > 0) windowSize.width.toFloat()
        else with(density) { LocalConfiguration.current.screenWidthDp.dp.toPx() }
    val statusBarInsetPx = with(density) { statusBarInset.toPx() }
    val rect = miniRect

    // 迷你条实际 rect（窗口坐标）→ 当前 surface 边界；p=1 时顶到 0，底到屏幕底。
    val topPx = if (rect != null) rect.top * (1f - playerProgress) else 0f
    val bottomPx = if (rect != null) rect.bottom + (screenHeightPx - rect.bottom) * playerProgress else 0f
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
    // 只有「已经在播放且量到迷你条位置」时才绘制，避免无播放时闪出一块空卡片。
    if (hasTrack && rect != null) {
        // p=0 时 surface 左右边界要贴齐迷你条卡片（迷你条两侧各有 Spacing.md 的内边距），
        // 否则收起态会在迷你条左右露出一截「翅膀」；展开过程中平滑过渡到全屏宽。
        val sideInsetPx = ((screenWidthPx - rect.width) / 2f) * (1f - playerProgress)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(0, topPx.roundToInt()) }
                .height(with(density) { heightPx.toDp() })
                .padding(horizontal = with(density) { sideInsetPx.toDp() })
                .graphicsLayer {
                    // 静止迷你条（p=0）时不画表面背景：胶囊自身是 Haze 玻璃，外接矩形应保持透明。
                    // 开始拖动后表面背景才淡入，和迷你条内容淡出/胶囊转为不透明同步，作为展开中的卡片底。
                    alpha = appear.value * smoothStep(0f, 0.01f, playerProgress)
                    this.shape = shape
                    clip = true
                }
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .layer(if (playerProgress >= 0.06f) AppLayer.Player else AppLayer.MiniPlayer),
        )
    }

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
            // 维测：播放页内容按 progress>=0.06 组合/销毁（手势节点生命周期、卡死问题的关键阈值）。
            DisposableEffect(Unit) {
                Diag.log(
                    DiagTag.SURFACE,
                    "player content ENTER progress=${diagProgress(playerProgress)}",
                )
                onDispose {
                    Diag.log(
                        DiagTag.SURFACE,
                        "player content EXIT progress=${diagProgress(latestProgress)}",
                    )
                }
            }
            BackHandler(enabled = playerProgress > 0.01f) { onClose() }
            NowPlayingScreen(
                initialPage = playerPage,
                onPageChanged = onPageChanged,
                onCoverBounds = { bounds ->
                    playerCoverMeasured = MeasuredRect(bounds, topPx)
                    // 维测：共享封面目标必须是正方形。v0.57.0 修复的形变问题就是目标被窗口裁剪成
                    // 896x50 这类扁矩形导致；这里在「健康↔异常」跃迁时记录，不会每帧刷屏。
                    val w = bounds.width
                    val h = bounds.height
                    val pending = w <= 1f || h <= 1f
                    val anomaly = !pending &&
                        kotlin.math.abs(w - h) > (h * 0.02f).coerceAtLeast(2f)
                    if (!coverTargetEverMeasured) {
                        coverTargetEverMeasured = true
                        Diag.log(
                            DiagTag.SURFACE,
                            "cover target first=${bounds.diagRect()} " +
                                "progress=${diagProgress(playerProgress)}",
                        )
                    } else if (anomaly != coverTargetAnomaly) {
                        Diag.log(
                            DiagTag.SURFACE,
                            "cover target ${if (anomaly) "ANOMALY" else "OK"} " +
                                "rect=${bounds.diagRect()} " +
                                "aspect=${if (h > 1f) diagNumber(w / h) else "?"} " +
                                "progress=${diagProgress(playerProgress)}",
                        )
                    }
                    coverTargetAnomaly = anomaly
                },
                sharedElementsAlpha = 1f,
                coverAlpha = smoothStep(0.94f, 0.995f, playerProgress),
                contentAlpha = smoothStep(0.55f, 0.92f, playerProgress),
                modifier = Modifier.fillMaxSize(),
            )
        }
    }

    // 顶部下拉关闭热区：独立于播放页内容常驻组合，避免收起过程中（p 越过 0.06）节点被销毁，
    // 导致进行中的拖拽手势被取消、onDragStopped 丢失、进度卡在中间值。
    // 收起态（p≈0）时移到屏幕外，保证不拦截迷你条自身的点击/拖拽。
    if (hasTrack) {
        val hotZoneTopPx = if (playerProgress > 0.001f) topPx + statusBarInsetPx else screenHeightPx
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(0, hotZoneTopPx.roundToInt()) }
                .height(112.dp)
                .layer(AppLayer.Player + 2f)
                .then(dragModifier),
        )
    }

    // 迷你条内容：它只在 p<10% 可见，但始终保留组合以维持拖拽 pointerInput。
    // 注意这里只能有一个稳定的调用点：不能在 rect 未测量时走另一条分支再渲染一个实例，
    // 否则实例切换会中止 MiniPlayerBar 内部 AnimatedVisibility 的进场动画。
    MiniPlayerBar(
        state = state,
        onClick = onClick,
        onTogglePlay = { playbackController.togglePlayPause() },
        onPrevious = { playbackController.previous() },
        onNext = { playbackController.next() },
        hazeState = hazeState,
        surfaceOpaque = playerProgress > 0.001f,
        contentAlpha = 1f - smoothStep(0f, 0.10f, playerProgress),
        dragModifier = dragModifier,
        onBarBounds = onBarBounds,
        onCoverBounds = { miniCoverRect = it },
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .layer(if (playerProgress >= 0.06f) AppLayer.MiniPlayer else AppLayer.MiniPlayer + 1f)
            .padding(bottom = miniBottomPadding),
    )

    // ---- 迷你条 → 播放页：只保留封面作为共享元素 ----
    // 标题/艺术家/控件都不插值，避免字号、省略号和布局在拖动中抽动；
    // 迷你条自身元素先淡出，封面单独放大/移动到播放页封面位置。
    val realAlpha = smoothStep(0.94f, 0.995f, playerProgress)
    val sharingCover = hasTrack && playerProgress > 0.001f && realAlpha < 0.999f
    // 维测：共享封面 overlay 显隐（排查「封面飞错位置/没有共享元素」）。
    // 首次组合时不记 HIDE（本来就没显示），避免每次进入播放器表面都多一条噪音。
    var sharedCoverShown by remember { mutableStateOf(false) }
    LaunchedEffect(sharingCover) {
        if (sharingCover) {
            sharedCoverShown = true
            Diag.log(
                DiagTag.SURFACE,
                "shared cover SHOW progress=${diagProgress(playerProgress)} page=$playerPage",
            )
        } else if (sharedCoverShown) {
            Diag.log(
                DiagTag.SURFACE,
                "shared cover HIDE progress=${diagProgress(playerProgress)} page=$playerPage",
            )
        }
    }
    // 关键：只要在播放且当前是封面页，共享封面 overlay 就保持组合（透明度由共享进度控制）。
    // 以前 overlay 只在开始拖动/展开时才进入组合，此时 Coil 才发起加载，第一帧会先画
    // surfaceVariant 占位方块，而迷你条自己的封面正在淡出 → 拖动开始时「封面先消失、再出现
    // 一个新的取代」，中间闪一下。提前组合后图片在拖动前就加载完成，跟手第一帧即连续。
    val sourceCover = miniCoverRect
    if (hasTrack && playerPage == 0 && sourceCover != null) {
        val targetCover = playerCoverMeasured
            ?.takeIf { it.rect.width > 1f && it.rect.height > 1f }
            ?.let { it.rect.translate(0f, -it.topOffset) }
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
                    // 不在共享过程中时 alpha=0：overlay 仍然留在组合里预热 Coil 图片。
                    alpha = if (sharingCover) 1f - realAlpha else 0f
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

private data class MeasuredRect(val rect: Rect, val topOffset: Float)

private fun lerpRect(a: Rect, b: Rect, t: Float): Rect = Rect(
    left = a.left + (b.left - a.left) * t,
    top = a.top + (b.top - a.top) * t,
    right = a.right + (b.right - a.right) * t,
    bottom = a.bottom + (b.bottom - a.bottom) * t,
)
