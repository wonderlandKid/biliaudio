package com.wonderlandkid.biliaudio.ui.navigation

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
import com.wonderlandkid.biliaudio.ui.player.PlayerMorphLayer
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
                scope.launch {
                    // 不再延迟 450ms：延迟恢复会表现为「页面先到错位，再手动滑一下」。
                    // 立即恢复，后续底栏 inset 动画不会再次改变 firstVisibleItem。
                    when (route) {
                        Routes.HOME -> homeScrollSnapshot?.let {
                            homeListState.scrollToItem(it.index, it.offset)
                            homeScrollSnapshot = null
                        }
                        Routes.LIBRARY -> libraryScrollSnapshot?.let {
                            librarySongsListState.scrollToItem(it.songsIndex, it.songsOffset)
                            libraryAlbumsGridState.scrollToItem(it.albumsIndex, it.albumsOffset)
                            libraryArtistsListState.scrollToItem(it.artistsIndex, it.artistsOffset)
                            libraryScrollSnapshot = null
                        }
                        Routes.SETTINGS -> settingsScrollSnapshot?.let {
                            settingsListState.scrollToItem(it.index, it.offset)
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
                    Routes.SETTINGS -> settingsScrollSnapshot = settingsListState.snapshot()
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

    // 连续展开（v0.34.0）：迷你条与播放页不再分两层硬切。
    // - 播放页 NowPlayingScreen 从 p>0 就组合（alpha 从 0 逐渐出现），上报封面/标题/艺术家位置；
    // - PlayerMorphLayer 把迷你条的封面/标题/艺术家连续插值到播放页位置，卡片背景同步长到全屏；
    // - p→1 时 morph 层淡出，交给真正的播放页（两端位置已对齐，无跳变）。
    var miniBarRect by remember { mutableStateOf<Rect?>(null) }
    var miniCoverRect by remember { mutableStateOf<Rect?>(null) }
    var miniTitleRect by remember { mutableStateOf<Rect?>(null) }
    var miniArtistRect by remember { mutableStateOf<Rect?>(null) }
    var playerCoverRect by remember { mutableStateOf<Rect?>(null) }
    var playerTitleRect by remember { mutableStateOf<Rect?>(null) }
    var playerArtistRect by remember { mutableStateOf<Rect?>(null) }

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
    val miniPlayerContentInset = if (
        showMiniPlayer &&
        playbackState.currentTrack != null &&
        playerProgress < 0.16f &&
        !imeVisible
    ) {
        BiliLayout.miniPlayerContentInset
    } else {
        0.dp
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
                    .padding(innerPadding)
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

            // 悬浮迷你播放条：只负责上报自身/封面/标题/艺术家在窗口中的位置（供 morph 层插值）。
            // 开启播放页时快速淡出，交给 morph 层做连续展开。
            // 展开超过 20% 后 morph 已接管视觉，继续组合 Haze 迷你条只会增加每帧成本（满屏时卡顿/闪烁）。
            if (showMiniPlayer && playerProgress < 0.20f) {
                // morph 层就绪（拿到播放页封面/标题位置）前保持迷你条完全可见，
                // 避免「迷你条先淡出、morph 还没画出来」的空白帧。
                // 标题/艺术家在封面页与歌词页共用的顶部区域，始终存在；
                // 用它们判断 morph 就绪，这样从歌词页收起/拉起也能连续展开。
                val morphReady = playerTitleRect != null && miniCoverRect != null
                val miniAlpha = if (!morphReady) {
                    1f
                } else {
                    1f - smoothStep(0.04f, 0.20f, playerProgress)
                }
                MiniPlayerHost(
                    playbackController = container.playbackController,
                    onClick = { animatePlayerTo(true) },
                    dragModifier = playerDragModifier,
                    onBarBounds = { miniBarRect = it },
                    onCoverBounds = { miniCoverRect = it },
                    onTitleBounds = { miniTitleRect = it },
                    onArtistBounds = { miniArtistRect = it },
                    hazeState = hazeState,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .graphicsLayer { alpha = miniAlpha }
                        .padding(bottom = miniBottomPadding),
                )
            }

            // 正在播放页：始终铺满全屏（不再上下位移），alpha 在展开后半段淡入；
            // 低进度时的视觉由 PlayerMorphLayer 的连续卡片/封面/标题负责。
            if (playerProgress > 0.001f) {
                // 播放页内容随展开进度从底部轻微上浮：控件不再在原地淡入，而是像被页面一起拖上来。
                val playerReveal = smoothStep(0.62f, 0.96f, playerProgress)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        // 必须先 graphicsLayer 再 background：alpha 才会作用于背景，
                        // 否则展开前半段会出现「不透明的播放页底色盖掉下面页面」。
                        .graphicsLayer {
                            alpha = playerReveal
                            translationY = (1f - playerReveal) * 56.dp.toPx()
                        }
                        .background(MaterialTheme.colorScheme.surface),
                ) {
                    // 先于 NowPlayingScreen 注册：播放页内的弹层（播放列表 ModalBottomSheet）
                    // 自己的返回处理会晚于这里注册，从而优先关闭弹层而不是收起播放页。
                    BackHandler(enabled = playerProgress > 0.01f) {
                        animatePlayerTo(false)
                    }
                    NowPlayingScreen(
                        initialPage = playerPage,
                        onPageChanged = { playerPage = it },
                        onCoverBounds = { playerCoverRect = it },
                        onTitleBounds = { playerTitleRect = it },
                        onArtistBounds = { playerArtistRect = it },
                    )
                    // 播放页顶部下拉关闭区域：从状态栏下方开始，覆盖标题/艺术家区（112dp）。
                    // 放在状态栏下面，避免向下滑时被系统通知栏/快捷设置抢走手势。
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .offset(y = statusBarInset)
                            .height(112.dp)
                            .then(playerDragModifier),
                    )
                }
            }

            // 连续展开层：迷你条封面/标题/艺术家 → 播放页对应位置，卡片背景同步长到全屏
            val morphAlpha = 1f - smoothStep(0.90f, 0.995f, playerProgress)
            if (playerProgress > 0.001f && playerProgress < 0.96f && morphAlpha > 0.001f) {
                PlayerMorphLayer(
                    progress = playerProgress,
                    state = playbackState,
                    miniBarRect = miniBarRect,
                    miniCoverRect = miniCoverRect,
                    miniTitleRect = miniTitleRect,
                    miniArtistRect = miniArtistRect,
                    playerCoverRect = playerCoverRect,
                    playerTitleRect = playerTitleRect,
                    playerArtistRect = playerArtistRect,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = morphAlpha },
                )
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

@Composable
private fun MiniPlayerHost(
    playbackController: PlaybackController,
    onClick: () -> Unit,
    dragModifier: Modifier,
    onBarBounds: (Rect) -> Unit,
    onCoverBounds: (Rect) -> Unit,
    onTitleBounds: (Rect) -> Unit,
    onArtistBounds: (Rect) -> Unit,
    hazeState: dev.chrisbanes.haze.HazeState?,
    modifier: Modifier = Modifier,
) {
    val state by playbackController.state.collectAsStateWithLifecycle()
    MiniPlayerBar(
        state = state,
        onClick = onClick,
        onTogglePlay = { playbackController.togglePlayPause() },
        onPrevious = { playbackController.previous() },
        onNext = { playbackController.next() },
        hazeState = hazeState,
        dragModifier = dragModifier,
        onBarBounds = onBarBounds,
        onCoverBounds = onCoverBounds,
        onTitleBounds = onTitleBounds,
        onArtistBounds = onArtistBounds,
        modifier = modifier,
    )
}

// ---------------------------------------------------------------------------
// 连续展开插值
// ---------------------------------------------------------------------------

/** 平滑阶跃：progress 在 [start, end] 内从 0 平滑过渡到 1。 */
private fun smoothStep(start: Float, end: Float, progress: Float): Float {
    if (end <= start) return if (progress >= end) 1f else 0f
    val t = ((progress - start) / (end - start)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

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
