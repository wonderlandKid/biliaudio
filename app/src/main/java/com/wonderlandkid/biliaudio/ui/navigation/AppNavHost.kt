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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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

/**
 * 应用导航壳（v0.6.2 动效重写，依据 Material Design 3 官方 motion 规范）。
 *
 * 转场系统（见 docs 与 ui/theme/Motion.kt）：
 * - **顶级 Tab**（发现/音乐库/设置）→ Material **Fade through**：旧页 100ms 淡出，新页延后 100ms
 *   以 200ms 淡入并从 92% 放大到 100%，共 300ms（无重叠、不出现两页同时清晰叠影）；
 * - **层级页面**（专辑/分曲/艺术家/导入/登录/设置子页等）→ Material **Shared axis X**：
 *   旧页 100ms 淡出并左移 30dp，新页延后 100ms 以 200ms 淡入并从右侧 30dp 回位，共 300ms；
 * - **播放页**（迷你条向上展开）→ **Shared axis Y**（垂直 30dp + 相同 100/200ms 淡入淡出）；
 * - **歌词页**（沉浸式覆盖）→ **Fade**（200/100ms）；
 * - 页面内容不再叠加自定义淡入，避免双重动画与「先透明后闪白」；Tab 不透明叠影问题由此消除。
 *
 * @see BiliMotion 动效 token（M3 Emphasized 曲线 + duration token）
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

    // 迷你播放条收起状态：下滑收起、上滑/点击展开；打开播放页时恢复展开
    var miniPlayerCollapsed by rememberSaveable { mutableStateOf(false) }

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

    // Shared axis 平移量：Material 规范 30dp（按当前 density 换算 px）
    val axisOffsetPx = with(LocalDensity.current) { BiliMotion.AxisTranslationDp.dp.roundToPx() }

    Scaffold(
        bottomBar = {
            if (showChrome || showMiniPlayerOnly) {
                Column {
                    // 播放状态在 MiniPlayerHost 内部订阅：避免播放进度 tick 触发整个导航壳重组
                    MiniPlayerHost(
                        playbackController = container.playbackController,
                        collapsed = miniPlayerCollapsed,
                        onCollapsedChange = { miniPlayerCollapsed = it },
                        onOpenPlayer = {
                            miniPlayerCollapsed = false
                            navController.navigate(Routes.PLAYER)
                        },
                    )
                    if (showChrome) {
                        BiliBottomBar(
                            current = currentTab,
                            onSelect = { switchTab(it) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        val contentPadding =
            if (isImmersive) PaddingValues(0.dp) else innerPadding

        NavHost(
            navController = navController,
            startDestination = Routes.HOME,
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
            // 层级页面：Material Shared axis X（退出淡出 100ms / 进入淡入 200ms 延迟 100ms + 30dp 平移）
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
                // 从迷你条向上展开：Shared axis Y（垂直 30dp + 100/200ms 淡入淡出）
                enterTransition = { sharedAxisYEnter(axisOffsetPx) },
                exitTransition = { fadeThroughExit() },
                popEnterTransition = { fadeEnter() },
                popExitTransition = { sharedAxisYPopExit(axisOffsetPx) },
            ) {
                NowPlayingScreen(
                    onBack = { navController.popBackStack() },
                    onOpenVideo = { navController.navigate(Routes.VIDEO) },
                    onOpenAlbum = { albumId -> navController.navigate(Routes.album(albumId)) },
                )
            }
            composable(
                Routes.VIDEO,
                // 沉浸式视频页：Material Fade
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
    }
}

// ---------------------------------------------------------------------------
// Material motion 转场实现
// ---------------------------------------------------------------------------

/**
 * 顶级 Tab：Material **Fade through**。
 * 旧页 100ms 淡出 → 新页延后 100ms 以 200ms 淡入并从 92% 放大到 100%（总 300ms）。
 * 两页没有清晰的叠加期，避免不透明双页叠影；也不会出现长时间空白。
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
 * 层级页面：Material **Shared axis X**（前进）。
 * 旧页 100ms 淡出并向左平移 30dp；新页延后 100ms 以 200ms 淡入并从右侧 30dp 回位（总 300ms）。
 */
private fun sharedAxisXEnter(offsetPx: Int): EnterTransition =
    slideInHorizontally(
        animationSpec = tween(BiliMotion.AxisTotalMs, easing = BiliMotion.Standard),
    ) { offsetPx } + fadeIn(
        animationSpec = tween(
            durationMillis = BiliMotion.AxisEnterFadeMs,
            delayMillis = BiliMotion.AxisEnterDelayMs,
            easing = BiliMotion.EmphasizedDecelerate,
        ),
    )

private fun sharedAxisXExit(offsetPx: Int): ExitTransition =
    slideOutHorizontally(
        animationSpec = tween(BiliMotion.AxisTotalMs, easing = BiliMotion.Standard),
    ) { -offsetPx } + fadeOut(
        animationSpec = tween(
            durationMillis = BiliMotion.AxisExitFadeMs,
            easing = BiliMotion.EmphasizedAccelerate,
        ),
    )

/** 返回：当前页 100ms 淡出并右移 30dp，上一页延后 100ms 左侧 30dp 回位。 */
private fun sharedAxisXPopEnter(offsetPx: Int): EnterTransition =
    slideInHorizontally(
        animationSpec = tween(BiliMotion.AxisTotalMs, easing = BiliMotion.Standard),
    ) { -offsetPx } + fadeIn(
        animationSpec = tween(
            durationMillis = BiliMotion.AxisEnterFadeMs,
            delayMillis = BiliMotion.AxisEnterDelayMs,
            easing = BiliMotion.EmphasizedDecelerate,
        ),
    )

private fun sharedAxisXPopExit(offsetPx: Int): ExitTransition =
    slideOutHorizontally(
        animationSpec = tween(BiliMotion.AxisTotalMs, easing = BiliMotion.Standard),
    ) { offsetPx } + fadeOut(
        animationSpec = tween(
            durationMillis = BiliMotion.AxisExitFadeMs,
            easing = BiliMotion.EmphasizedAccelerate,
        ),
    )

/** 播放页：Material **Shared axis Y**（从底部向上）。 */
private fun sharedAxisYEnter(offsetPx: Int): EnterTransition =
    slideInVertically(
        animationSpec = tween(BiliMotion.AxisTotalMs, easing = BiliMotion.Standard),
    ) { offsetPx } + fadeIn(
        animationSpec = tween(
            durationMillis = BiliMotion.AxisEnterFadeMs,
            delayMillis = BiliMotion.AxisEnterDelayMs,
            easing = BiliMotion.EmphasizedDecelerate,
        ),
    )

private fun sharedAxisYPopExit(offsetPx: Int): ExitTransition =
    slideOutVertically(
        animationSpec = tween(BiliMotion.AxisTotalMs, easing = BiliMotion.Standard),
    ) { offsetPx } + fadeOut(
        animationSpec = tween(
            durationMillis = BiliMotion.AxisExitFadeMs,
            easing = BiliMotion.EmphasizedAccelerate,
        ),
    )

/** 沉浸/覆盖层：Material **Fade**。 */
private fun fadeEnter(): EnterTransition =
    fadeIn(
        animationSpec = tween(BiliMotion.FadeInMs, easing = BiliMotion.EmphasizedDecelerate),
    )

private fun fadeExit(): ExitTransition =
    fadeOut(
        animationSpec = tween(BiliMotion.FadeOutMs, easing = BiliMotion.EmphasizedAccelerate),
    )

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
) {
    val state by playbackController.state.collectAsStateWithLifecycle()
    MiniPlayerBar(
        state = state,
        collapsed = collapsed,
        onCollapsedChange = onCollapsedChange,
        onOpenPlayer = onOpenPlayer,
        onTogglePlay = { playbackController.togglePlayPause() },
        onNext = { playbackController.next() },
    )
}
