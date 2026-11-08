package com.wonderlandkid.biliaudio.ui.navigation

import android.app.Activity
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.wonderlandkid.biliaudio.player.PlaybackController
import com.wonderlandkid.biliaudio.ui.album.AlbumDetailScreen
import com.wonderlandkid.biliaudio.ui.album.SplitEditorScreen
import com.wonderlandkid.biliaudio.ui.components.BiliBottomBar
import com.wonderlandkid.biliaudio.ui.components.MainTab
import com.wonderlandkid.biliaudio.ui.components.MiniPlayerBar
import com.wonderlandkid.biliaudio.ui.home.HomeScreen
import com.wonderlandkid.biliaudio.ui.imports.ImportScreen
import com.wonderlandkid.biliaudio.ui.imports.ImportTab
import com.wonderlandkid.biliaudio.ui.library.ArtistDetailScreen
import com.wonderlandkid.biliaudio.ui.library.LibraryScreen
import com.wonderlandkid.biliaudio.ui.login.LoginScreen
import com.wonderlandkid.biliaudio.ui.player.NowPlayingScreen
import com.wonderlandkid.biliaudio.ui.player.VideoScreen
import com.wonderlandkid.biliaudio.ui.rememberAppContainer
import com.wonderlandkid.biliaudio.ui.settings.DiagnosticsScreen
import com.wonderlandkid.biliaudio.ui.settings.SettingsScreen
import com.wonderlandkid.biliaudio.ui.theme.BiliMotion
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.launch

/**
 * 应用导航壳（v0.8.0）。
 *
 * 动画（用户明确要求）：
 * - **全部页面导航使用左右滑动结构**，不再用淡入淡出：
 *   进入 = 从右侧滑入、返回 = 向右滑出，方向严格「从哪来回哪去」；
 *   三个 Tab 之间按 Tab 顺序决定左右方向（发现→音乐库→设置 向右推进）。
 * - **迷你播放条 → 正在播放页 = 上下关系**：迷你条跟手上滑，播放页以底部卡片形式连续展开
 *   （进度 0→1 由拖拽距离连续控制，松手弹簧收敛；下滑/返回键收起），不是跳转淡入。
 *
 * 布局：NavHost 作为 Haze source；悬浮迷你条 + 播放页卡片覆盖在内容层上；底部导航在播放页展开时隐藏。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppNavHost() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val container = rememberAppContainer()

    val tabRoutes = setOf(Routes.HOME, Routes.LIBRARY, Routes.SETTINGS)
    val miniPlayerOnlyRoutes = setOf(Routes.ALBUM, Routes.SPLIT_EDITOR)
    val immersiveRoutes = setOf(Routes.VIDEO)
    val showChrome = currentRoute in tabRoutes
    val showMiniPlayerOnly = currentRoute in miniPlayerOnlyRoutes
    val isImmersive = currentRoute in immersiveRoutes
    val showMiniPlayer = (showChrome || showMiniPlayerOnly) && !isImmersive

    val hazeState = rememberHazeState()
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val screenHeightPx = with(density) { configuration.screenHeightDp.dp.roundToPx() }
    var bottomBarHeightPx by remember { mutableIntStateOf(0) }
    // 非 Tab 页面没有底部导航时，迷你条平滑下移到系统手势区上方，而不是瞬间掉落
    val systemBottomInset = WindowInsets.navigationBars
        .asPaddingValues()
        .calculateBottomPadding()
    val animatedMiniBottomPadding: Dp by animateDpAsState(
        targetValue = if (showChrome) with(density) { bottomBarHeightPx.toDp() } else systemBottomInset,
        animationSpec = spring(dampingRatio = 1f, stiffness = 400f),
        label = "miniBottomPadding",
    )
    // spring 不能出现过冲：padding 为负会直接抛 IllegalArgumentException（真机 v0.8.3 闪退根因）
    val miniBottomPadding: Dp = animatedMiniBottomPadding.coerceAtLeast(0.dp)

    // ---------------- 播放页连续展开状态 ----------------
    val scope = rememberCoroutineScope()
    // 0f = 迷你条，1f = 全屏播放页；拖拽期间实时变化
    var playerProgress by remember { mutableFloatStateOf(0f) }
    var playerOpenTarget by rememberSaveable { mutableStateOf(false) }
    var reopenPlayerAfterVideo by rememberSaveable { mutableStateOf(false) }
    var settleJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    fun animatePlayerTo(open: Boolean, velocity: Float = 0f) {
        settleJob?.cancel()
        playerOpenTarget = open
        settleJob = scope.launch {
            Animatable(playerProgress).animateTo(
                targetValue = if (open) 1f else 0f,
                animationSpec = spring(
                    dampingRatio = 0.90f,
                    stiffness = 320f,
                    visibilityThreshold = 0.001f,
                ),
                initialVelocity = velocity.coerceIn(-6f, 6f),
            ) { playerProgress = value.coerceIn(0f, 1f) }
            playerProgress = if (open) 1f else 0f
        }
    }

    val playerDragState = rememberDraggableState { delta ->
        settleJob?.cancel()
        playerProgress = (playerProgress - delta / screenHeightPx.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
    }
    val playerDragModifier = Modifier.draggable(
        state = playerDragState,
        orientation = Orientation.Vertical,
        onDragStopped = { velocity ->
            // 方向 + 速度 + 进度共同决定：向下快滑关闭、向上快滑展开，否则看是否过半
            val open = when {
                velocity < -800f -> true
                velocity > 800f -> false
                else -> playerProgress > 0.5f
            }
            animatePlayerTo(open, (-velocity / screenHeightPx.coerceAtLeast(1).toFloat()).coerceIn(-6f, 6f))
        },
    )

    // 视频页返回后恢复播放页（视频是从播放页打开的）
    LaunchedEffect(currentRoute) {
        if (currentRoute == Routes.VIDEO) {
            // 播放页卡片让位给视频页；返回时再恢复
            settleJob?.cancel()
            playerProgress = 0f
        } else if (reopenPlayerAfterVideo) {
            reopenPlayerAfterVideo = false
            animatePlayerTo(true)
        }
    }

    val currentTab = when (currentRoute) {
        Routes.HOME -> MainTab.HOME
        Routes.LIBRARY -> MainTab.LIBRARY
        else -> MainTab.SETTINGS
    }

    fun switchTab(tab: MainTab) {
        val route = when (tab) {
            MainTab.HOME -> Routes.HOME
            MainTab.LIBRARY -> Routes.LIBRARY
            MainTab.SETTINGS -> Routes.SETTINGS
        }
        if (route == currentRoute) return
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    Scaffold(
        bottomBar = {
            // 底栏出现/消失做高度 + 淡入淡出，避免页面切换时“啪”一下出现；
            // Scaffold 会跟着测量高度，Tab 内容与迷你条避让同步过渡。
            AnimatedVisibility(
                visible = showChrome && playerProgress < 0.02f,
                enter = expandVertically(
                    animationSpec = tween(
                        durationMillis = BiliMotion.BottomBarEnterMs,
                        easing = BiliMotion.EmphasizedDecelerate,
                    ),
                    expandFrom = Alignment.Bottom,
                ) + fadeIn(
                    animationSpec = tween(
                        durationMillis = BiliMotion.BottomBarEnterMs,
                        easing = BiliMotion.EmphasizedDecelerate,
                    ),
                ),
                exit = shrinkVertically(
                    animationSpec = tween(
                        durationMillis = BiliMotion.BottomBarExitMs,
                        easing = BiliMotion.EmphasizedAccelerate,
                    ),
                    shrinkTowards = Alignment.Bottom,
                ) + fadeOut(
                    animationSpec = tween(
                        durationMillis = BiliMotion.BottomBarExitMs,
                        easing = BiliMotion.EmphasizedAccelerate,
                    ),
                ),
            ) {
                Box(modifier = Modifier.onSizeChanged { bottomBarHeightPx = it.height }) {
                    BiliBottomBar(
                        current = currentTab,
                        onSelect = { switchTab(it) },
                    )
                }
            }
        },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize()) {
            NavHost(
                navController = navController,
                startDestination = Routes.HOME,
                modifier = Modifier
                    .fillMaxSize()
                    .hazeSource(hazeState)
                    .padding(innerPadding),
                // 层级页面统一 push/pop：整页从右侧进入，退出向左 1/4 视差；返回严格反向。
                // 一级 Tab 自身无转场（见各 composable 的 None），所以底层不会“往右撤”或缩小。
                enterTransition = { slideInFromRight() },
                exitTransition = { slideOutToLeft() },
                popEnterTransition = { slideInFromLeft() },
                popExitTransition = { slideOutToRight() },
                // 预测式返回（预输入）只做“无变化”预览：手势滑动过程中页面不动，
                // 真正完成返回后才走普通 pop 转场；取消手势不会看到页面已经退了一半。
                predictivePopEnterTransition = { EnterTransition.None },
                predictivePopExitTransition = { ExitTransition.None },
            ) {
                // ---------------- Tabs（按 Tab 顺序左右滑动） ----------------
                composable(
                    Routes.HOME,
                    enterTransition = { tabEnterTransition() },
                    exitTransition = { tabExitTransition() },
                    popEnterTransition = { tabPopEnterTransition() },
                    popExitTransition = { ExitTransition.None },
                ) {
                    Page {
                        HomeScreen(
                            onOpenSearch = { switchTab(MainTab.LIBRARY) },
                            onOpenAlbum = { albumId -> navController.navigate(Routes.album(albumId)) },
                            onOpenLogin = { navController.navigate(Routes.LOGIN) },
                            onOpenImport = { navController.navigate(Routes.IMPORT) },
                            onOpenLibrary = { switchTab(MainTab.LIBRARY) },
                        )
                    }
                }
                composable(
                    Routes.LIBRARY,
                    enterTransition = { tabEnterTransition() },
                    exitTransition = { tabExitTransition() },
                    popEnterTransition = { tabPopEnterTransition() },
                    popExitTransition = { ExitTransition.None },
                ) {
                    Page {
                        LibraryScreen(
                            onOpenAlbum = { albumId -> navController.navigate(Routes.album(albumId)) },
                            onOpenArtist = { artistName -> navController.navigate(Routes.artist(artistName)) },
                        )
                    }
                }
                composable(
                    Routes.SETTINGS,
                    enterTransition = { tabEnterTransition() },
                    exitTransition = { tabExitTransition() },
                    popEnterTransition = { tabPopEnterTransition() },
                    popExitTransition = { ExitTransition.None },
                ) {
                    Page {
                        SettingsScreen(
                            onOpenLogin = { navController.navigate(Routes.LOGIN) },
                            onOpenBackupImport = { navController.navigate(Routes.IMPORT_ARCHIVE) },
                            onOpenDiagnostics = { navController.navigate(Routes.DIAGNOSTICS) },
                        )
                    }
                }

                // ---------------- 艺术家 / 专辑 / 分曲（右进右出） ----------------
                composable(
                    route = Routes.ARTIST,
                    arguments = listOf(navArgument(Routes.ARG_ARTIST_NAME) { type = NavType.StringType }),
                ) { entry ->
                    val artistName = entry.arguments?.getString(Routes.ARG_ARTIST_NAME).orEmpty()
                    Page {
                        ArtistDetailScreen(
                            artistName = android.net.Uri.decode(artistName),
                            onBack = { navController.popBackStack() },
                            onOpenAlbum = { albumId -> navController.navigate(Routes.album(albumId)) },
                        )
                    }
                }
                composable(
                    route = Routes.ALBUM,
                    arguments = listOf(navArgument(Routes.ARG_ALBUM_ID) { type = NavType.LongType }),
                ) { entry ->
                    val albumId = entry.arguments?.getLong(Routes.ARG_ALBUM_ID) ?: 0L
                    Page {
                        AlbumDetailScreen(
                            albumId = albumId,
                            onBack = { navController.popBackStack() },
                            onOpenSplitEditor = { id -> navController.navigate(Routes.splitEditor(id)) },
                        )
                    }
                }
                composable(
                    route = Routes.SPLIT_EDITOR,
                    arguments = listOf(navArgument(Routes.ARG_ALBUM_ID) { type = NavType.LongType }),
                ) { entry ->
                    val albumId = entry.arguments?.getLong(Routes.ARG_ALBUM_ID) ?: 0L
                    Page {
                        SplitEditorScreen(
                            albumId = albumId,
                            onBack = { navController.popBackStack() },
                        )
                    }
                }

                // ---------------- 导入 / 登录 / 诊断 ----------------
                composable(Routes.IMPORT) {
                    Page {
                        ImportScreen(
                            onBack = { navController.popBackStack() },
                            onImported = { albumId ->
                                navController.popBackStack()
                                navController.navigate(Routes.album(albumId))
                            },
                        )
                    }
                }
                composable(Routes.SEARCH) {
                    Page {
                        ImportScreen(
                            onBack = { navController.popBackStack() },
                            onImported = { albumId ->
                                navController.popBackStack()
                                navController.navigate(Routes.album(albumId))
                            },
                            initialTab = ImportTab.SEARCH,
                        )
                    }
                }
                composable(Routes.IMPORT_ARCHIVE) {
                    Page {
                        ImportScreen(
                            onBack = { navController.popBackStack() },
                            onImported = { albumId ->
                                navController.popBackStack()
                                navController.navigate(Routes.album(albumId))
                            },
                            initialTab = ImportTab.BATCH,
                        )
                    }
                }
                composable(Routes.DIAGNOSTICS) {
                    Page {
                        DiagnosticsScreen(onBack = { navController.popBackStack() })
                    }
                }
                composable(Routes.LOGIN) {
                    Page {
                        LoginScreen(
                            onBack = { navController.popBackStack() },
                            onLoggedIn = { navController.popBackStack() },
                        )
                    }
                }

                // ---------------- 歌词视频页（左右滑动进出） ----------------
                composable(
                    Routes.VIDEO,
                    enterTransition = { slideInFromRight() },
                    exitTransition = { slideOutToLeft() },
                    popEnterTransition = { slideInFromLeft() },
                    popExitTransition = { slideOutToRight() },
                ) {
                    Page {
                        VideoScreen(
                            onBack = { navController.popBackStack() },
                        )
                    }
                }
            }

            // 悬浮迷你播放条（播放页展开时淡出并让位给播放页卡片）
            if (showMiniPlayer) {
                val miniAlpha = (1f - playerProgress / 0.16f).coerceIn(0f, 1f)
                MiniPlayerHost(
                    playbackController = container.playbackController,
                    onClick = { animatePlayerTo(true) },
                    dragModifier = playerDragModifier,
                    hazeState = hazeState,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .graphicsLayer { alpha = miniAlpha }
                        .padding(bottom = miniBottomPadding),
                )
            }

            // 正在播放页：以底部卡片形式连续展开（progress 0→1）
            if (playerProgress > 0.001f) {
                val horizontalInset = (18.dp * (1f - playerProgress))
                    .coerceAtLeast(0.dp)
                val topRadius = (28.dp * (1f - playerProgress))
                    .coerceAtLeast(0.dp)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            translationY = (1f - playerProgress) * size.height
                        }
                        .padding(horizontal = horizontalInset)
                        .clip(
                            RoundedCornerShape(
                                topStart = topRadius,
                                topEnd = topRadius,
                            ),
                        )
                        .background(MaterialTheme.colorScheme.surface),
                ) {
                    // 先于 NowPlayingScreen 注册：播放页内的弹层（播放列表 ModalBottomSheet）
                    // 自己的返回处理会晚于这里注册，从而优先关闭弹层而不是收起播放页。
                    BackHandler(enabled = playerProgress > 0.01f) {
                        animatePlayerTo(false)
                    }
                    NowPlayingScreen(
                        onBack = { animatePlayerTo(false) },
                        dragModifier = playerDragModifier,
                        onOpenVideo = {
                            reopenPlayerAfterVideo = true
                            settleJob?.cancel()
                            playerProgress = 0f
                            navController.navigate(Routes.VIDEO)
                        },
                        onOpenAlbum = { albumId ->
                            animatePlayerTo(false)
                            navController.navigate(Routes.album(albumId))
                        },
                    )
                    // 播放页顶部下拉关闭区域（48dp 高、上滑展开后手指可在顶部向下拖回迷你条）
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .height(48.dp)
                            .then(playerDragModifier),
                    )
                }
            }

            // 平级 Tab 的返回：第一次提示，2 秒内连续第二次才真正退出应用。
            val context = LocalContext.current
            var lastBackAt by remember { mutableLongStateOf(0L) }
            val isTabRoot = currentRoute in tabRoutes &&
                playerProgress <= 0.01f &&
                !WindowInsets.isImeVisible
            BackHandler(enabled = isTabRoot) {
                val now = SystemClock.elapsedRealtime()
                if (now - lastBackAt in 1..2_000L) {
                    (context as? Activity)?.finish()
                } else {
                    lastBackAt = now
                    Toast.makeText(context, "再返回一次退出应用", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}

/**
 * 每个导航目的地都包一层不透明 Surface：左右滑动转场时两个页面是「实体」，
 * 避免透明背景导致两页内容叠加透视。
 */
@Composable
private fun Page(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        content()
    }
}

// ---------------------------------------------------------------------------
// 层级页面：统一 push/pop（Material/iOS 通用结构，无 alpha、无缩放）
//   push：新页从右侧整页滑入；旧页向左 1/4 视差（像被压到下面）
//   pop ：当前页向右整页滑出；上一页从左侧 1/4 回位
//   一级 Tab 不参与转场（EnterTransition.None），避免平级页面出现方向/速度差异。
// ---------------------------------------------------------------------------

// 对称转场：进入/退出用完全相同的时长与曲线，只改变方向；
// 不能一个 Decelerate 一个 Accelerate，否则同一对手势会觉得两侧速度不一致。
private val PageSlideSpec = tween<androidx.compose.ui.unit.IntOffset>(
    durationMillis = BiliMotion.AxisTotalMs,
    easing = BiliMotion.Emphasized,
)

private fun slideInFromRight(): EnterTransition =
    slideInHorizontally(animationSpec = PageSlideSpec) { it }

private fun slideInFromLeft(): EnterTransition =
    slideInHorizontally(animationSpec = PageSlideSpec) { -it }

private fun slideOutToLeft(): ExitTransition =
    slideOutHorizontally(animationSpec = PageSlideSpec) { -it }

// 一级 Tab 的转场只在「Tab ↔ 详情页」时生效：
// - 从 Tab 进入详情：Tab 整页向左退出（新页从右进入）；
// - 从详情返回 Tab：Tab 从左侧整页回位（详情向右退出）；
// - Tab 之间切换：仍然瞬时（平级，不做方向动画）。
private fun AnimatedContentTransitionScope<NavBackStackEntry>.tabEnterTransition(): EnterTransition =
    if (initialState.destination.route in TAB_ROUTE_SET) EnterTransition.None else slideInFromLeft()

private fun AnimatedContentTransitionScope<NavBackStackEntry>.tabExitTransition(): ExitTransition =
    if (targetState.destination.route in TAB_ROUTE_SET) ExitTransition.None else slideOutToLeft()

private fun AnimatedContentTransitionScope<NavBackStackEntry>.tabPopEnterTransition(): EnterTransition =
    if (initialState.destination.route in TAB_ROUTE_SET) EnterTransition.None else slideInFromLeft()

private val TAB_ROUTE_SET = setOf(Routes.HOME, Routes.LIBRARY, Routes.SETTINGS)

private fun slideOutToRight(): ExitTransition =
    slideOutHorizontally(animationSpec = PageSlideSpec) { it }

// ---------------------------------------------------------------------------
// 迷你播放条宿主
// ---------------------------------------------------------------------------

@Composable
private fun MiniPlayerHost(
    playbackController: PlaybackController,
    onClick: () -> Unit,
    dragModifier: Modifier,
    hazeState: dev.chrisbanes.haze.HazeState?,
    modifier: Modifier = Modifier,
) {
    val state by playbackController.state.collectAsStateWithLifecycle()
    MiniPlayerBar(
        state = state,
        onClick = onClick,
        onTogglePlay = { playbackController.togglePlayPause() },
        onNext = { playbackController.next() },
        hazeState = hazeState,
        dragModifier = dragModifier,
        modifier = modifier,
    )
}
