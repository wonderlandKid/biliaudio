package com.wonderlandkid.biliaudio.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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

/**
 * 应用导航壳（v0.7.0）。
 *
 * 布局：
 * - 底部导航仍由 Scaffold bottomBar 承载（内容区域在其上方）；
 * - **迷你播放条改为悬浮内容层**（Scaffold 内容区底部对齐），因此可以盖在列表之上，
 *   并使用 Haze backdrop blur 做“毛玻璃”质感（HazeState 由 NavHost 的 hazeSource 提供背景采样）；
 * - 播放页/歌词页沉浸式，无底部 chrome。
 *
 * 动效（Material Design 3 Motion，见 ui/theme/Motion.kt）：
 * - Tab：Fade through（400ms：旧页 200ms 淡出、新页延迟 100ms 以 300ms 淡入并 94%→100%）；
 * - 层级页面：Shared axis X（400ms、屏幕 24% 横向位移 + 淡入淡出，进入/返回严格对称）；
 * - 播放页：Shared axis Y（垂直 14% 屏高）；歌词页：Fade。
 */
@Composable
fun AppNavHost() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val container = rememberAppContainer()

    val tabRoutes = setOf(Routes.HOME, Routes.LIBRARY, Routes.SETTINGS)
    val miniPlayerOnlyRoutes = setOf(Routes.ALBUM, Routes.SPLIT_EDITOR)
    val immersiveRoutes = setOf(Routes.PLAYER, Routes.VIDEO)
    val showChrome = currentRoute in tabRoutes
    val showMiniPlayerOnly = currentRoute in miniPlayerOnlyRoutes
    val isImmersive = currentRoute in immersiveRoutes
    val showMiniPlayer = (showChrome || showMiniPlayerOnly) && !isImmersive

    // 迷你播放条收起状态：下滑收起、上滑/点击展开；打开播放页时恢复展开
    var miniPlayerCollapsed by rememberSaveable { mutableStateOf(false) }

    // Haze 背景采样：NavHost 作为 source，悬浮迷你条作为 effect
    val hazeState = rememberHazeState()

    // Scaffold 内容层可能延伸到屏幕底部（bottomBar 只是 overlay），悬浮迷你条需要自己避开底部导航
    var bottomBarHeightPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current

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
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    val configuration = LocalConfiguration.current
    // Shared axis X 位移：屏幕宽度的 24%（进入/返回对称）；播放页 Y 轴：屏高 14%
    val axisOffsetPx = with(density) {
        (configuration.screenWidthDp * BiliMotion.AxisTranslationFraction).dp.roundToPx()
    }
    val verticalOffsetPx = with(density) {
        (configuration.screenHeightDp * BiliMotion.AxisVerticalFraction).dp.roundToPx()
    }

    Scaffold(
        bottomBar = {
            if (showChrome) {
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
                // 层级页面：Material Shared axis X（进入/返回对称的横向位移 + 分段淡入淡出）
                enterTransition = { sharedAxisXEnter(axisOffsetPx) },
                exitTransition = { sharedAxisXExit(axisOffsetPx) },
                popEnterTransition = { sharedAxisXPopEnter(axisOffsetPx) },
                popExitTransition = { sharedAxisXPopExit(axisOffsetPx) },
            ) {
                // ---------------- Tabs（Fade through） ----------------
                composable(
                    Routes.HOME,
                    enterTransition = { fadeThroughEnter() },
                    exitTransition = { fadeThroughExit() },
                    popEnterTransition = { fadeThroughEnter() },
                    popExitTransition = { fadeThroughExit() },
                ) {
                    HomeScreen(
                        onOpenSearch = { switchTab(MainTab.LIBRARY) },
                        onOpenAlbum = { albumId ->
                            navController.navigate(Routes.album(albumId))
                        },
                        onOpenLogin = { navController.navigate(Routes.LOGIN) },
                        onOpenImport = { navController.navigate(Routes.IMPORT) },
                        onOpenLibrary = { switchTab(MainTab.LIBRARY) },
                    )
                }
                composable(
                    Routes.LIBRARY,
                    enterTransition = { fadeThroughEnter() },
                    exitTransition = { fadeThroughExit() },
                    popEnterTransition = { fadeThroughEnter() },
                    popExitTransition = { fadeThroughExit() },
                ) {
                    LibraryScreen(
                        onOpenAlbum = { albumId ->
                            navController.navigate(Routes.album(albumId))
                        },
                        onOpenArtist = { artistName ->
                            navController.navigate(Routes.artist(artistName))
                        },
                    )
                }
                composable(
                    Routes.SETTINGS,
                    enterTransition = { fadeThroughEnter() },
                    exitTransition = { fadeThroughExit() },
                    popEnterTransition = { fadeThroughEnter() },
                    popExitTransition = { fadeThroughExit() },
                ) {
                    SettingsScreen(
                        onOpenLogin = { navController.navigate(Routes.LOGIN) },
                        onOpenBackupImport = { navController.navigate(Routes.IMPORT_ARCHIVE) },
                        onOpenDiagnostics = { navController.navigate(Routes.DIAGNOSTICS) },
                    )
                }

                // ---------------- 艺术家（Shared axis X） ----------------
                composable(
                    route = Routes.ARTIST,
                    arguments = listOf(navArgument(Routes.ARG_ARTIST_NAME) { type = NavType.StringType }),
                ) { entry ->
                    val artistName = entry.arguments?.getString(Routes.ARG_ARTIST_NAME).orEmpty()
                    ArtistDetailScreen(
                        artistName = android.net.Uri.decode(artistName),
                        onBack = { navController.popBackStack() },
                        onOpenAlbum = { albumId -> navController.navigate(Routes.album(albumId)) },
                    )
                }

                // ---------------- 专辑（Shared axis X） ----------------
                composable(
                    route = Routes.ALBUM,
                    arguments = listOf(navArgument(Routes.ARG_ALBUM_ID) { type = NavType.LongType }),
                ) { entry ->
                    val albumId = entry.arguments?.getLong(Routes.ARG_ALBUM_ID) ?: 0L
                    AlbumDetailScreen(
                        albumId = albumId,
                        onBack = { navController.popBackStack() },
                        onOpenSplitEditor = { id -> navController.navigate(Routes.splitEditor(id)) },
                    )
                }
                composable(
                    route = Routes.SPLIT_EDITOR,
                    arguments = listOf(navArgument(Routes.ARG_ALBUM_ID) { type = NavType.LongType }),
                ) { entry ->
                    val albumId = entry.arguments?.getLong(Routes.ARG_ALBUM_ID) ?: 0L
                    SplitEditorScreen(
                        albumId = albumId,
                        onBack = { navController.popBackStack() },
                    )
                }

                // ---------------- 导入 / 登录 / 诊断（Shared axis X） ----------------
                composable(Routes.IMPORT) {
                    ImportScreen(
                        onBack = { navController.popBackStack() },
                        onImported = { albumId ->
                            navController.popBackStack()
                            navController.navigate(Routes.album(albumId))
                        },
                    )
                }
                composable(Routes.SEARCH) {
                    ImportScreen(
                        onBack = { navController.popBackStack() },
                        onImported = { albumId ->
                            navController.popBackStack()
                            navController.navigate(Routes.album(albumId))
                        },
                        initialTab = ImportTab.SEARCH,
                    )
                }
                composable(Routes.IMPORT_ARCHIVE) {
                    ImportScreen(
                        onBack = { navController.popBackStack() },
                        onImported = { albumId ->
                            navController.popBackStack()
                            navController.navigate(Routes.album(albumId))
                        },
                        initialTab = ImportTab.BATCH,
                    )
                }
                composable(Routes.DIAGNOSTICS) {
                    DiagnosticsScreen(onBack = { navController.popBackStack() })
                }
                composable(Routes.LOGIN) {
                    LoginScreen(
                        onBack = { navController.popBackStack() },
                        onLoggedIn = { navController.popBackStack() },
                    )
                }

                // ---------------- 播放 / 歌词页（沉浸式，页面自行处理 insets） ----------------
                composable(
                    Routes.PLAYER,
                    // 从迷你条向上展开：Shared axis Y
                    enterTransition = { sharedAxisYEnter(verticalOffsetPx) },
                    exitTransition = { fadeThroughExit() },
                    popEnterTransition = { fadeEnter() },
                    popExitTransition = { sharedAxisYPopExit(verticalOffsetPx) },
                ) {
                    NowPlayingScreen(
                        onBack = { navController.popBackStack() },
                        onOpenVideo = { navController.navigate(Routes.VIDEO) },
                        onOpenAlbum = { albumId -> navController.navigate(Routes.album(albumId)) },
                    )
                }
                composable(
                    Routes.VIDEO,
                    enterTransition = { fadeEnter() },
                    exitTransition = { fadeExit() },
                    popEnterTransition = { fadeEnter() },
                    popExitTransition = { fadeExit() },
                ) {
                    VideoScreen(
                        onBack = { navController.popBackStack() },
                    )
                }
            }

            // 悬浮迷你播放条：盖在内容之上（Haze 背景采样 NavHost），位于底部导航之上
            if (showMiniPlayer) {
                MiniPlayerHost(
                    playbackController = container.playbackController,
                    collapsed = miniPlayerCollapsed,
                    onCollapsedChange = { miniPlayerCollapsed = it },
                    onOpenPlayer = {
                        miniPlayerCollapsed = false
                        navController.navigate(Routes.PLAYER)
                    },
                    hazeState = hazeState,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .then(
                            if (showChrome) {
                                // 位于底部导航之上（bottomBar 覆盖在 Scaffold 内容层底部）
                                Modifier.padding(
                                    bottom = with(density) { bottomBarHeightPx.toDp() },
                                )
                            } else {
                                // 迷你条专属路由（专辑详情等）没有底部导航：避开系统手势区
                                Modifier.navigationBarsPadding()
                            },
                        ),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Material motion 转场实现（时长/曲线见 ui/theme/Motion.kt）
// ---------------------------------------------------------------------------

/**
 * 顶级 Tab：Material **Fade through**（M3 推荐节奏，400ms）：
 * 旧页 200ms 淡出 → 新页延后 100ms 以 300ms 淡入并从 94% 放大到 100%。
 */
private fun fadeThroughEnter(): EnterTransition =
    fadeIn(
        animationSpec = tween(
            durationMillis = BiliMotion.TopLevelEnterMs,
            delayMillis = BiliMotion.TopLevelEnterDelayMs,
            easing = BiliMotion.EmphasizedDecelerate,
        ),
    ) + scaleIn(
        initialScale = BiliMotion.TopLevelEnterScale,
        animationSpec = tween(
            durationMillis = BiliMotion.TopLevelEnterMs,
            delayMillis = BiliMotion.TopLevelEnterDelayMs,
            easing = BiliMotion.EmphasizedDecelerate,
        ),
    )

private fun fadeThroughExit(): ExitTransition =
    fadeOut(
        animationSpec = tween(
            durationMillis = BiliMotion.TopLevelExitMs,
            easing = BiliMotion.EmphasizedAccelerate,
        ),
    )

/**
 * 层级页面：Material **Shared axis X**（400ms）。
 * 位移取屏幕 24%，进入从右滑入、返回向右滑出，严格对称（不再是“原地缩小淡出”）。
 */
private fun sharedAxisXEnter(offsetPx: Int): EnterTransition =
    slideInHorizontally(
        animationSpec = tween(BiliMotion.AxisTotalMs, easing = BiliMotion.EmphasizedDecelerate),
    ) { offsetPx } + fadeIn(
        animationSpec = tween(
            durationMillis = BiliMotion.AxisEnterFadeMs,
            delayMillis = BiliMotion.AxisEnterDelayMs,
            easing = BiliMotion.EmphasizedDecelerate,
        ),
    )

private fun sharedAxisXExit(offsetPx: Int): ExitTransition =
    slideOutHorizontally(
        animationSpec = tween(BiliMotion.AxisTotalMs, easing = BiliMotion.EmphasizedAccelerate),
    ) { -offsetPx } + fadeOut(
        animationSpec = tween(
            durationMillis = BiliMotion.AxisExitFadeMs,
            easing = BiliMotion.EmphasizedAccelerate,
        ),
    )

private fun sharedAxisXPopEnter(offsetPx: Int): EnterTransition =
    slideInHorizontally(
        animationSpec = tween(BiliMotion.AxisTotalMs, easing = BiliMotion.EmphasizedDecelerate),
    ) { -offsetPx } + fadeIn(
        animationSpec = tween(
            durationMillis = BiliMotion.AxisEnterFadeMs,
            delayMillis = BiliMotion.AxisEnterDelayMs,
            easing = BiliMotion.EmphasizedDecelerate,
        ),
    )

private fun sharedAxisXPopExit(offsetPx: Int): ExitTransition =
    slideOutHorizontally(
        animationSpec = tween(BiliMotion.AxisTotalMs, easing = BiliMotion.EmphasizedAccelerate),
    ) { offsetPx } + fadeOut(
        animationSpec = tween(
            durationMillis = BiliMotion.AxisExitFadeMs,
            easing = BiliMotion.EmphasizedAccelerate,
        ),
    )

/** 播放页：Material **Shared axis Y**（从迷你条向上）。 */
private fun sharedAxisYEnter(offsetPx: Int): EnterTransition =
    slideInVertically(
        animationSpec = tween(BiliMotion.AxisTotalMs, easing = BiliMotion.EmphasizedDecelerate),
    ) { offsetPx } + fadeIn(
        animationSpec = tween(
            durationMillis = BiliMotion.AxisEnterFadeMs,
            delayMillis = BiliMotion.AxisEnterDelayMs,
            easing = BiliMotion.EmphasizedDecelerate,
        ),
    )

private fun sharedAxisYPopExit(offsetPx: Int): ExitTransition =
    slideOutVertically(
        animationSpec = tween(BiliMotion.AxisTotalMs, easing = BiliMotion.EmphasizedAccelerate),
    ) { offsetPx } + fadeOut(
        animationSpec = tween(
            durationMillis = BiliMotion.AxisExitFadeMs,
            easing = BiliMotion.EmphasizedAccelerate,
        ),
    )

/** 沉浸/覆盖层：Material **Fade**。 */
private fun fadeEnter(): EnterTransition =
    fadeIn(animationSpec = tween(BiliMotion.FadeInMs, easing = BiliMotion.EmphasizedDecelerate))

private fun fadeExit(): ExitTransition =
    fadeOut(animationSpec = tween(BiliMotion.FadeOutMs, easing = BiliMotion.EmphasizedAccelerate))

// ---------------------------------------------------------------------------
// 迷你播放条宿主
// ---------------------------------------------------------------------------

/**
 * 迷你播放条宿主：播放状态在本 composable 内部订阅，
 * 避免 250ms 的播放进度 tick 冒泡到 AppNavHost/Scaffold/NavHost 造成整页重组。
 */
@Composable
private fun MiniPlayerHost(
    playbackController: PlaybackController,
    collapsed: Boolean,
    onCollapsedChange: (Boolean) -> Unit,
    onOpenPlayer: () -> Unit,
    hazeState: dev.chrisbanes.haze.HazeState?,
    modifier: Modifier = Modifier,
) {
    val state by playbackController.state.collectAsStateWithLifecycle()
    MiniPlayerBar(
        state = state,
        collapsed = collapsed,
        onCollapsedChange = onCollapsedChange,
        onOpenPlayer = onOpenPlayer,
        onTogglePlay = { playbackController.togglePlayPause() },
        onNext = { playbackController.next() },
        hazeState = hazeState,
        modifier = modifier,
    )
}
