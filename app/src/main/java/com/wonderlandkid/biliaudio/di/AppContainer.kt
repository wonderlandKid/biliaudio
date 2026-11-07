package com.wonderlandkid.biliaudio.di

import android.content.Context
import com.wonderlandkid.biliaudio.data.db.AppDatabase
import com.wonderlandkid.biliaudio.data.impl.AlbumRepositoryImpl
import com.wonderlandkid.biliaudio.data.impl.AuthRepositoryImpl
import com.wonderlandkid.biliaudio.data.impl.BiliRepositoryImpl
import com.wonderlandkid.biliaudio.data.impl.LibraryArchiveRepositoryImpl
import com.wonderlandkid.biliaudio.data.impl.MediaStreamResolverImpl
import com.wonderlandkid.biliaudio.data.impl.SettingsRepositoryImpl
import com.wonderlandkid.biliaudio.data.net.BiliApi
import com.wonderlandkid.biliaudio.data.net.CookieStore
import com.wonderlandkid.biliaudio.data.repository.AlbumRepository
import com.wonderlandkid.biliaudio.data.repository.AuthRepository
import com.wonderlandkid.biliaudio.data.repository.BiliRepository
import com.wonderlandkid.biliaudio.data.repository.LibraryArchiveRepository
import com.wonderlandkid.biliaudio.data.repository.MediaStreamResolver
import com.wonderlandkid.biliaudio.data.repository.SettingsRepository
import com.wonderlandkid.biliaudio.player.AlbumVideoController
import com.wonderlandkid.biliaudio.player.AlbumVideoPlayer
import com.wonderlandkid.biliaudio.player.PlaybackController
import com.wonderlandkid.biliaudio.player.PlaybackEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.wonderlandkid.biliaudio.util.Diag

/**
 * 应用级依赖容器（手写 DI，避免额外框架）。
 *
 * 装配顺序有依赖关系：
 * CookieStore → BiliApi → 各 Repository → MediaStreamResolver → PlaybackEngine（懒加载）。
 * 登录态变化（登录/退出/Cookie 失效）时清空媒体流 URL 缓存，避免继续用旧 Cookie 拿到的流。
 */
class AppContainer(context: Context) {

    val appContext: Context = context.applicationContext
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // ---------------- 网络 / 数据层 ----------------

    val cookieStore: CookieStore = CookieStore(appContext)

    private val biliApi: BiliApi = BiliApi(cookieStore)

    val settingsRepository: SettingsRepository = SettingsRepositoryImpl(appContext)

    val biliRepository: BiliRepository = BiliRepositoryImpl(biliApi)

    private val streamResolverImpl: MediaStreamResolverImpl = MediaStreamResolverImpl(
        biliApi = biliApi,
        settingsRepository = settingsRepository,
    )

    val streamResolver: MediaStreamResolver = streamResolverImpl

    val albumRepository: AlbumRepository = AlbumRepositoryImpl(
        database = AppDatabase.getInstance(appContext),
        biliRepository = biliRepository,
    )

    val authRepository: AuthRepository = AuthRepositoryImpl(
        context = appContext,
        api = biliApi,
        cookieStore = cookieStore,
        scope = appScope,
        onSessionChanged = { streamResolverImpl.invalidateAll() },
    )

    /** 音乐库归档（导出备份 / 导入恢复），见 docs/LIBRARY_ARCHIVE.md */
    val libraryArchiveRepository: LibraryArchiveRepository = LibraryArchiveRepositoryImpl(
        context = appContext,
        albumRepository = albumRepository,
        biliRepository = biliRepository,
        settingsRepository = settingsRepository,
    )

    // ---------------- 播放层 ----------------

    /** 进程内唯一播放引擎（持有 ExoPlayer），懒加载避免启动即初始化音频栈。 */
    private val playbackEngine: PlaybackEngine by lazy {
        PlaybackEngine.getOrCreate(
            context = appContext,
            scope = appScope,
            albumRepository = albumRepository,
            streamResolver = streamResolver,
        )
    }

    val playbackController: PlaybackController
        get() = playbackEngine

    /** 歌词页视频播放器（每页一个实例，页面销毁时 release）。 */
    fun createVideoController(context: Context): AlbumVideoController =
        AlbumVideoPlayer(
            context = context,
            streamResolver = streamResolver,
            scope = appScope,
        )

    /** 应用启动时触发一次登录态校验（不阻塞启动）。 */
    fun warmUp() {
        appScope.launch {
            runCatching { authRepository.refresh() }
            // 清理旧版本遗留的「整张专辑占位曲」（新版未分割专辑应显示 0 首）
            runCatching { albumRepository.cleanupLegacyPlaceholderTracks() }
                .onSuccess { removed -> if (removed > 0) Diag.log("App", "legacy placeholder cleaned=$removed") }
        }
    }
}
