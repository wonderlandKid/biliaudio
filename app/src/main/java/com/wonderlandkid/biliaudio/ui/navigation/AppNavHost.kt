package com.wonderlandkid.biliaudio.ui.navigation

import com.wonderlandkid.biliaudio.ui.layers.PlayerSheetLayer
import com.wonderlandkid.biliaudio.ui.layers.MiniPlayerLayer
import com.wonderlandkid.biliaudio.ui.layers.BottomBarLayer
import com.wonderlandkid.biliaudio.ui.layers.AppLayer
import com.wonderlandkid.biliaudio.ui.layers.AppLayerHost
import com.wonderlandkid.biliaudio.ui.layers.layer

import androidx.compose.runtime.LaunchedEffect

import androidx.compose.foundation.shape.RoundedCornerShape

import androidx.compose.ui.zIndex

import android.app.Activity
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.geometry.Rect
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
import com.wonderlandkid.biliaudio.ui.album.MultiPageTrackEditorScreen
import com.wonderlandkid.biliaudio.ui.album.SplitEditorScreen
import com.wonderlandkid.biliaudio.ui.components.BiliBottomBar
import com.wonderlandkid.biliaudio.ui.components.MainTab
import com.wonderlandkid.biliaudio.ui.components.MiniPlayerBar
import com.wonderlandkid.biliaudio.ui.home.HomeScreen
import com.wonderlandkid.biliaudio.ui.home.RecentAlbumsScreen
import com.wonderlandkid.biliaudio.ui.imports.ImportScreen
import com.wonderlandkid.biliaudio.ui.imports.ImportTab
import com.wonderlandkid.biliaudio.ui.library.ArtistDetailScreen
import com.wonderlandkid.biliaudio.ui.library.LibraryScreen
import com.wonderlandkid.biliaudio.ui.login.LoginScreen
import com.wonderlandkid.biliaudio.ui.player.NowPlayingScreen
import com.wonderlandkid.biliaudio.ui.playlist.PlaylistDetailScreen
import com.wonderlandkid.biliaudio.ui.playlist.PlaylistsScreen
import com.wonderlandkid.biliaudio.ui.rememberAppContainer
import com.wonderlandkid.biliaudio.ui.settings.CacheManagementScreen
import com.wonderlandkid.biliaudio.ui.settings.DiagnosticsScreen
import com.wonderlandkid.biliaudio.ui.settings.SettingsScreen
import com.wonderlandkid.biliaudio.ui.stats.StatsScreen
import com.wonderlandkid.biliaudio.ui.theme.BiliLayout
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
 * - **歌词页 = 播放页上方覆盖层**（非导航目的地）：从右整页盖在播放页上/向右滑出，
 *   底层始终是播放页；打开时隐藏迷你条与播放页拖拽手势。
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
    val playbackState by container.playbackController.state.collectAsStateWithLifecycle()

    val tabRoutes = setOf(Routes.HOME, Routes.LIBRARY, Routes.SETTINGS)
    val miniPlayerOnlyRoutes = setOf(
        Routes.ALBUM,
        Routes.SPLIT_EDITOR,
        Routes.PLAYLIST_DETAIL,
        Routes.STATS,
        Routes.RECENT_ALBUMS,
    )
    val showChrome = currentRoute in tabRoutes
    val showMiniPlayerOnly = currentRoute in miniPlayerOnlyRoutes
    val showMiniPlayer = showChrome || showMiniPlayerOnly

    val hazeState = rememberHazeState()
    // 根部 Tab 的滚动位置由 NavHost 外层持有：push 详情页再返回时不会因重组/inset 动画而跳位。
    val settingsListState = rememberLazyListState()
    val homeListState = rememberLazyListState()
    val librarySongsListState = rememberLazyListState()
    val libraryAlbumsGridState = rememberLazyGridState()
    val libraryArtistsListState = rememberLazyListState()
    var homeScrollSnapshot by remember { mutableStateOf<ScrollSnapshot?>(null) }
    var libraryScrollSnapshot by remember { mutableStateOf<LibraryScrollSnapshot?>(null) }
    var settingsScrollSnapshot by remember { mutableStateOf<ScrollSnapshot?>(null) }

    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val screenHeightPx = with(density) { configuration.screenHeightDp.dp.roundToPx() }
    var bottomBarHeightPx by remember { mutableIntStateOf(0) }
    // 首次出现才做高度展开动画；从详情页返回根 Tab 时底栏直接以最终高度出现，
    // 这样 LazyColumn 第一帧就能按正确视口恢复滚动位置，不会先错位再补。
    var bottomBarShownOnce by remember { mutableStateOf(false) }
    // 非 Tab 页面没有底部导航时，迷你条平滑下移到系统手势区上方，而不是瞬间掉落
    val systemBottomInset = WindowInsets.navigationBars
        .asPaddingValues()
        .calculateBottomPadding()
    // 下拉关闭热区从状态栏下方开始：贴顶的 48dp 会被系统通知栏手势吃掉，这就是之前「下拉没反应」的根因。
    val statusBarInset = WindowInsets.statusBars
        .asPaddingValues()
        .calculateTopPadding()
    val animatedMiniBottomPadding: Dp by animateDpAsState(
        targetValue = if (showChrome) with(density) { bottomBarHeightPx.toDp() } else systemBottomInset,
        animationSpec = spring(dampingRatio = 1f, stiffness = 400f),
        label = "miniBottomPadding",
    )
    // spring 不能出现过冲：padding 为负会直接抛 IllegalArgumentException（真机 v0.8.3 闪退根因）
    val miniBottomPadding: Dp = animatedMiniBottomPadding.coerceAtLeast(0.dp)

    // ---------------- 播放页连续展开状态 ----------------
    val scope = rememberCoroutineScope()

    // 根 Tab 的滚动位置跨详情页保持：
    // 进入详情时底栏收起、NavHost 视口变高，LazyList 会把「到底」状态向上 clamp；
    // 因此离开根 Tab 时快照位置，返回根 Tab 且底栏动画结束后再恢复。
    DisposableEffect(navController) {
        var lastRootRoute = if (currentRoute in tabRoutes) currentRoute else null
        val listener = androidx.navigation.NavController.OnDestinationChangedListener { _, destination, _ ->
            val route = destination.route ?: return@OnDestinationChangedListener
            if (route in tabRoutes) {
                lastRootRoute = route
                // 等两帧：第一帧 Scaffold 才完成底栏/inset 测量，此时再 requestScrollToItem，
                // 位置不会先被 clamp；两帧内的闪动基本不可见。
                scope.launch {
                    androidx.compose.runtime.withFrameNanos { }
                    androidx.compose.runtime.withFrameNanos { }
                    when (route) {
                        Routes.HOME -> homeScrollSnapshot?.let {
                            homeListState.requestScrollToItem(it.index, it.offset)
                            homeScrollSnapshot = null
                        }
                        Routes.LIBRARY -> libraryScrollSnapshot?.let {
                            librarySongsListState.requestScrollToItem(it.songsIndex, it.songsOffset)
                            libraryAlbumsGridState.requestScrollToItem(it.albumsIndex, it.albumsOffset)
                            libraryArtistsListState.requestScrollToItem(it.artistsIndex, it.artistsOffset)
                            libraryScrollSnapshot = null
                        }
                        Routes.SETTINGS -> settingsScrollSnapshot?.let {
                            settingsListState.requestScrollToItem(it.index, it.offset)
                            settingsScrollSnapshot = null
                        }
                    }
                }
            } else {
                when (lastRootRoute) {
                    Routes.HOME -> homeScrollSnapshot = homeListState.snapshot()
                    Routes.LIBRARY -> libraryScrollSnapshot = LibraryScrollSnapshot(
                        songsIndex = librarySongsListState.firstVisibleItemIndex,
                        songsOffset = librarySongsListState.firstVisibleItemScrollOffset,
                        albumsIndex = libraryAlbumsGridState.firstVisibleItemIndex,
                        albumsOffset = libraryAlbumsGridState.firstVisibleItemScrollOffset,
                        artistsIndex = libraryArtistsListState.firstVisibleItemIndex,
                        artistsOffset = libraryArtistsListState.firstVisibleItemScrollOffset,
                    )
                    Routes.SETTINGS -> {
                        settingsScrollSnapshot = settingsListState.snapshot()
                    }
                }
            }
        }
        navController.addOnDestinationChangedListener(listener)
        onDispose { navController.removeOnDestinationChangedListener(listener) }
    }
    // 0f = 迷你条，1f = 全屏播放页；拖拽期间实时变化
    var playerProgress by remember { mutableFloatStateOf(0f) }
    var playerOpenTarget by rememberSaveable { mutableStateOf(false) }
    // 收起时记住当前页（0 封面 / 1 歌词），重新拉起时恢复同一页。
    var playerPage by rememberSaveable { mutableIntStateOf(0) }
    var settleJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    fun animatePlayerTo(open: Boolean, velocity: Float = 0f) {
        settleJob?.cancel()
        playerOpenTarget = open
        settleJob = scope.launch {
            Animatable(playerProgress).animateTo(
                targetValue = if (open) 1f else 0f,
                animationSpec = spring(
                    // 临界阻尼：不回弹/不过冲，避免松手后「闪一下」；
                    // 配合较小初始速度，让惯性自然收敛而不是瞬间到顶。
                    dampingRatio = 1f,
                    stiffness = 380f,
                    visibilityThreshold = 0.001f,
                ),
                initialVelocity = velocity.coerceIn(-2.5f, 2.5f),
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
            // 方向 + 速度 + 预测最终位置共同决定，保留松手瞬间的惯性：
            // 用 220ms 的位移预测，避免「慢慢拖到一半松手」时被硬阈值瞬间甩到顶/底。
            val progressVelocity = -velocity / screenHeightPx.coerceAtLeast(1).toFloat()
            val projected = (playerProgress + progressVelocity * 0.22f).coerceIn(0f, 1f)
            val open = when {
                velocity < -600f -> true
                velocity > 600f -> false
                else -> projected >= 0.5f
            }
            animatePlayerTo(open, progressVelocity)
        },
    )

    // 播放页 sheet 的滑动锚点：迷你条静止位置（不再使用封面/标题位置做 morph）。
    // 迷你条静止时的 top（不跟随 graphicsLayer translationY 变化），作为 sheet 的滑动锚点，
    // 避免「读取被平移后的 rect → 再影响 sheet → 抖动」。
    var miniAnchorTopPx by remember { mutableFloatStateOf(0f) }

    val currentTab = when (currentRoute) {
        Routes.HOME -> MainTab.HOME
        Routes.LIBRARY -> MainTab.LIBRARY
        else -> MainTab.SETTINGS
    }

    fun switchTab(tab: MainTab) {
        // 播放页正在展开/收起时切 Tab：先收起，避免留下半展开的 morph 覆盖层。
        if (playerProgress > 0.001f) animatePlayerTo(false)
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

    /** 导入成功后直接进入专辑详情：用一次 navigate + popUpTo 收掉导入页，避免先 pop 再 push 的双转场。 */
    fun openImportedAlbum(albumId: Long) {
        val fromRoute = navController.currentDestination?.route ?: Routes.HOME
        navController.navigate(Routes.album(albumId)) {
            popUpTo(fromRoute) { inclusive = true }
            launchSingleTop = true
        }
    }

    // 全局底部安全区：
    // - 迷你条可见时，所有页面内容整体抬到毛玻璃上方，避免最后一项被盖住；
    // - 键盘弹出时改用 IME inset，不再叠加迷你条高度（此时迷你条本身被键盘遮住）。
    val imeVisible = WindowInsets.isImeVisible
    // 不要跟随展开进度移除 mini 内容 inset：如果拖到 16% 突然少一块底部 padding，
    // 正在拖动的设置/音乐库会重新布局，正是底栏区域「按钮没了/遮罩还在」抖动的来源。
    // inset 在播放页完全铺满前保持不变（被 player 覆盖时不可见），关闭时也不会跳。
    val miniPlayerContentInset = if (
        showMiniPlayer &&
        playbackState.currentTrack != null &&
        !imeVisible
    ) {
        BiliLayout.miniPlayerContentInset
    } else {
        0.dp
    }

    Scaffold(
        // 底栏不再放在 Scaffold.bottomBar：它会参与内容 viewport 测量，
        // 从详情返回时第一帧的 inset/高度动画会导致滚动位置被 clamp。
        // 现在改为内容层之上的固定高度 overlay，滚动位置只由下面显式的 bottom padding 决定。
        bottomBar = { },
    ) { innerPadding ->
        AppLayerHost {
            NavHost(
                navController = navController,
                startDestination = Routes.HOME,
                modifier = Modifier
                    .fillMaxSize()
                    .hazeSource(hazeState)
                    .padding(innerPadding)
                    // 根 Tab 显式预留底栏高度（overlay 不参与 Scaffold 测量）；详情页仍用系统底 inset。
                    .padding(
                        bottom = if (showChrome && bottomBarHeightPx > 0) {
                            (with(density) { bottomBarHeightPx.toDp() } -
                                innerPadding.calculateBottomPadding()).coerceAtLeast(0.dp)
                        } else {
                            0.dp
                        },
                    )
                    .padding(bottom = miniPlayerContentInset)
                    .imePadding(),
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
                            listState = homeListState,
                            onOpenAlbum = { albumId -> navController.navigate(Routes.album(albumId)) },
                            onOpenLogin = { navController.navigate(Routes.LOGIN) },
                            onOpenImport = { navController.navigate(Routes.IMPORT) },
                            onOpenPlaylists = { navController.navigate(Routes.PLAYLISTS) },
                            onOpenPlaylist = { playlistId ->
                                navController.navigate(Routes.playlist(playlistId))
                            },
                            onOpenStats = { navController.navigate(Routes.STATS) },
                            onOpenRecentAlbums = { navController.navigate(Routes.RECENT_ALBUMS) },
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
                            songsListState = librarySongsListState,
                            albumsGridState = libraryAlbumsGridState,
                            artistsListState = libraryArtistsListState,
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
                            onOpenCacheManagement = { navController.navigate(Routes.CACHE_MANAGEMENT) },
                            listState = settingsListState,
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
                            onOpenTrackNameEditor = { id ->
                                navController.navigate(Routes.multiPageTrackEditor(id))
                            },
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
                composable(
                    route = Routes.MULTI_PAGE_TRACK_EDITOR,
                    arguments = listOf(navArgument(Routes.ARG_ALBUM_ID) { type = NavType.LongType }),
                ) { entry ->
                    val albumId = entry.arguments?.getLong(Routes.ARG_ALBUM_ID) ?: 0L
                    Page {
                        MultiPageTrackEditorScreen(
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
                            onImported = { albumId -> openImportedAlbum(albumId) },
                        )
                    }
                }
                composable(Routes.SEARCH) {
                    Page {
                        ImportScreen(
                            onBack = { navController.popBackStack() },
                            onImported = { albumId -> openImportedAlbum(albumId) },
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
                composable(Routes.CACHE_MANAGEMENT) {
                    Page {
                        CacheManagementScreen(onBack = { navController.popBackStack() })
                    }
                }
                composable(Routes.PLAYLISTS) {
                    Page {
                        PlaylistsScreen(
                            onBack = { navController.popBackStack() },
                            onOpenPlaylist = { playlistId ->
                                navController.navigate(Routes.playlist(playlistId))
                            },
                        )
                    }
                }
                composable(
                    route = Routes.PLAYLIST_DETAIL,
                    arguments = listOf(
                        navArgument(Routes.ARG_PLAYLIST_ID) { type = NavType.LongType },
                    ),
                ) { entry ->
                    val playlistId = entry.arguments?.getLong(Routes.ARG_PLAYLIST_ID) ?: 0L
                    Page {
                        PlaylistDetailScreen(
                            playlistId = playlistId,
                            onBack = { navController.popBackStack() },
                        )
                    }
                }
                composable(Routes.STATS) {
                    Page {
                        StatsScreen(
                            onBack = { navController.popBackStack() },
                            onOpenAlbum = { albumId -> navController.navigate(Routes.album(albumId)) },
                        )
                    }
                }
                composable(Routes.RECENT_ALBUMS) {
                    Page {
                        RecentAlbumsScreen(
                            onBack = { navController.popBackStack() },
                            onOpenAlbum = { albumId -> navController.navigate(Routes.album(albumId)) },
                        )
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

            }

            // 持久 chrome 层：顺序固定在 AppLayer 中，所有层都是 AppLayerHost 的直接子节点。
            BottomBarLayer(
                showChrome = showChrome,
                shownOnce = bottomBarShownOnce,
                onShown = { bottomBarShownOnce = true },
                heightPx = bottomBarHeightPx,
                onHeightMeasured = { bottomBarHeightPx = it },
                playerProgress = playerProgress,
                currentTab = currentTab,
                onSelect = { switchTab(it) },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
            MiniPlayerLayer(
                visible = showMiniPlayer,
                playbackController = container.playbackController,
                playerProgress = playerProgress,
                anchorTopPx = miniAnchorTopPx,
                onClick = { animatePlayerTo(true) },
                dragModifier = playerDragModifier,
                onBarBounds = { rect ->
                    if (playerProgress <= 0.001f) miniAnchorTopPx = rect.top
                },
                bottomPadding = miniBottomPadding,
                hazeState = hazeState,
            )
            PlayerSheetLayer(
                playerProgress = playerProgress,
                playerPage = playerPage,
                onPageChanged = { playerPage = it },
                travelPx = if (miniAnchorTopPx > 0f) miniAnchorTopPx else screenHeightPx * 0.72f,
                statusBarInset = statusBarInset,
                dragModifier = playerDragModifier,
                onClose = { animatePlayerTo(false) },
            )

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
private fun Page(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
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

// ---------------------------------------------------------------------------
// 连续展开插值
// ---------------------------------------------------------------------------

// ---------------------------------------------------------------------------
// 根 Tab 滚动位置快照（详见 AppNavHost 中 OnDestinationChangedListener 的注释）
// ---------------------------------------------------------------------------

private data class ScrollSnapshot(val index: Int, val offset: Int)

private data class LibraryScrollSnapshot(
    val songsIndex: Int,
    val songsOffset: Int,
    val albumsIndex: Int,
    val albumsOffset: Int,
    val artistsIndex: Int,
    val artistsOffset: Int,
)

private fun androidx.compose.foundation.lazy.LazyListState.snapshot() =
    ScrollSnapshot(firstVisibleItemIndex, firstVisibleItemScrollOffset)
