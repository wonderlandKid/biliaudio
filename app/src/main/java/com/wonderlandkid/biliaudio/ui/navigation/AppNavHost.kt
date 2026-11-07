package com.wonderlandkid.biliaudio.ui.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
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

/**
 * 应用导航壳：
 * - 三个 Tab（发现 / 音乐库 / 设置），底部固定迷你播放条；
 * - 其余页面（专辑详情、分曲编辑、导入、登录、播放页、歌词页）全屏覆盖。
 *
 * 播放页/歌词页需要沉浸式显示，NavHost 不注入系统栏 padding，由页面自行处理。
 */
@Composable
fun AppNavHost() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val container = rememberAppContainer()
    val playbackState by container.playbackController.state.collectAsState()

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

    Scaffold(
        bottomBar = {
            if (showChrome || showMiniPlayerOnly) {
                Column {
                    MiniPlayerBar(
                        state = playbackState,
                        collapsed = miniPlayerCollapsed,
                        onCollapsedChange = { miniPlayerCollapsed = it },
                        onOpenPlayer = {
                            miniPlayerCollapsed = false
                            navController.navigate(Routes.PLAYER)
                        },
                        onTogglePlay = { container.playbackController.togglePlayPause() },
                        onNext = { container.playbackController.next() },
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
            // 详情页统一「共享轴」横向转场：进入从右侧推入、返回向右退出（不再出现默认的缩放/淡出不一致）
            enterTransition = {
                slideInHorizontally(animationSpec = tween(320)) { it } + fadeIn(tween(220))
            },
            exitTransition = {
                slideOutHorizontally(animationSpec = tween(320)) { -it / 4 } + fadeOut(tween(180))
            },
            popEnterTransition = {
                slideInHorizontally(animationSpec = tween(320)) { -it / 4 } + fadeIn(tween(220))
            },
            popExitTransition = {
                slideOutHorizontally(animationSpec = tween(280)) { it } + fadeOut(tween(200))
            },
        ) {
            // ---------------- Tabs ----------------
            composable(
                Routes.HOME,
                enterTransition = { fadeIn(tween(180)) },
                exitTransition = { fadeOut(tween(140)) },
                popEnterTransition = { fadeIn(tween(180)) },
                popExitTransition = { fadeOut(tween(140)) },
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
                enterTransition = { fadeIn(tween(180)) },
                exitTransition = { fadeOut(tween(140)) },
                popEnterTransition = { fadeIn(tween(180)) },
                popExitTransition = { fadeOut(tween(140)) },
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
                enterTransition = { fadeIn(tween(180)) },
                exitTransition = { fadeOut(tween(140)) },
                popEnterTransition = { fadeIn(tween(180)) },
                popExitTransition = { fadeOut(tween(140)) },
            ) {
                SettingsScreen(
                    onOpenLogin = { navController.navigate(Routes.LOGIN) },
                    onOpenBackupImport = { navController.navigate(Routes.IMPORT_ARCHIVE) },
                    onOpenDiagnostics = { navController.navigate(Routes.DIAGNOSTICS) },
                )
            }

            // ---------------- 艺术家 ----------------
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

            // ---------------- 专辑 ----------------
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

            // ---------------- 导入 / 登录 ----------------
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
                enterTransition = {
                    slideInVertically(animationSpec = tween(340)) { it } + fadeIn(tween(180))
                },
                exitTransition = { fadeOut(tween(140)) },
                popEnterTransition = { fadeIn(tween(160)) },
                popExitTransition = {
                    slideOutVertically(animationSpec = tween(300)) { it } + fadeOut(tween(180))
                },
            ) {
                NowPlayingScreen(
                    onBack = { navController.popBackStack() },
                    onOpenVideo = { navController.navigate(Routes.VIDEO) },
                    onOpenAlbum = { albumId -> navController.navigate(Routes.album(albumId)) },
                )
            }
            composable(
                Routes.VIDEO,
                enterTransition = { fadeIn(tween(220)) },
                exitTransition = { fadeOut(tween(160)) },
                popEnterTransition = { fadeIn(tween(180)) },
                popExitTransition = { fadeOut(tween(180)) },
            ) {
                VideoScreen(
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}
