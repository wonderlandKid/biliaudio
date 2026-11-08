package com.wonderlandkid.biliaudio.ui.home

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wonderlandkid.biliaudio.data.model.Album
import com.wonderlandkid.biliaudio.data.model.HomeStatsMode
import com.wonderlandkid.biliaudio.data.model.Playlist
import com.wonderlandkid.biliaudio.data.model.TrackWithAlbum
import com.wonderlandkid.biliaudio.data.repository.AlbumRepository
import com.wonderlandkid.biliaudio.data.repository.AuthRepository
import com.wonderlandkid.biliaudio.data.repository.AuthState
import com.wonderlandkid.biliaudio.data.repository.PlaybackStatsRepository
import com.wonderlandkid.biliaudio.data.repository.PlaylistRepository
import com.wonderlandkid.biliaudio.data.repository.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.random.Random

@Immutable
data class HomeStatsAlbum(
    val album: Album,
    val listenedMs: Long,
)

data class HomeStatsTrack(
    val song: TrackWithAlbum,
    val playCount: Int,
)

/**
 * 发现页 UI 状态。
 *
 * 结构：头图 + 未登录提示 + 四个“只露前四”的听歌区块：
 * 随机专辑 / 我的歌单 / 听歌统计 / 最近播放。
 */
@Immutable
data class HomeUiState(
    val isLoading: Boolean = true,
    val songCount: Int = 0,
    val albumCount: Int = 0,
    /** 随机专辑（最多 4 张） */
    val randomAlbums: List<Album> = emptyList(),
    /** 我的歌单预览（最多 4 个） */
    val playlists: List<Playlist> = emptyList(),
    val playlistCount: Int = 0,
    /** 听歌统计预览：按专辑累计时长取前 4 */
    val statsAlbums: List<HomeStatsAlbum> = emptyList(),
    /** 听歌统计预览：按歌曲播放次数取前 4 */
    val statsTracks: List<HomeStatsTrack> = emptyList(),
    /** 发现页听歌统计预览的展示口径（持久化） */
    val statsMode: HomeStatsMode = HomeStatsMode.ALBUM_LISTEN_TIME,
    /** 最近播放预览（最多 4 张，详情页展示 10 张） */
    val recentAlbums: List<Album> = emptyList(),
    val isLoggedIn: Boolean = false,
    val errorMessage: String? = null,
) {
    val statsText: String
        get() = when {
            isLoading -> "正在加载音乐库…"
            songCount == 0 && albumCount == 0 -> "音乐库还是空的，导入第一张专辑吧"
            else -> "$songCount 首歌曲 · $albumCount 张专辑"
        }
}

/** 发现页 ViewModel：组合专辑/歌曲/最近/歌单/统计/登录态。 */
class HomeViewModel(
    albumRepository: AlbumRepository,
    authRepository: AuthRepository,
    playlistRepository: PlaylistRepository,
    statsRepository: PlaybackStatsRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private data class LibraryCounts(val songCount: Int, val albumCount: Int)

    private val shuffleTick = MutableStateFlow(0)

    /** 每次换一批或专辑列表变化时，用稳定随机序抽样 4 张。 */
    private val randomAlbumsFlow = combine(albumRepository.albums, shuffleTick) { albums, tick ->
        if (albums.size <= HOME_SECTION_LIMIT) {
            albums
        } else {
            val seed = tick.toLong() * 1_000_003L + albums.size.toLong() * 31L
            albums.shuffled(Random(seed)).take(HOME_SECTION_LIMIT)
        }
    }

    private val statsAlbumsFlow = combine(
        statsRepository.observeAlbumListening(HOME_SECTION_LIMIT),
        albumRepository.albums,
    ) { stats, albums ->
        val albumsById = albums.associateBy { it.id }
        stats.mapNotNull { stat ->
            albumsById[stat.albumId]?.let { HomeStatsAlbum(it, stat.listenedMs) }
        }.take(HOME_SECTION_LIMIT)
    }

    private val statsTracksFlow = combine(
        statsRepository.observeTrackPlayCounts(HOME_SECTION_LIMIT),
        albumRepository.songs,
    ) { stats, songs ->
        val songsByUid = songs.associateBy { it.track.uid }
        stats.mapNotNull { stat ->
            songsByUid[stat.trackUid]?.let { HomeStatsTrack(it, stat.playCount) }
        }.take(HOME_SECTION_LIMIT)
    }

    private val statsModeFlow = settingsRepository.settings
        .map { it.homeStatsMode }
        .distinctUntilChanged()

    val uiState: StateFlow<HomeUiState> = run {
        val countsFlow = combine(
            albumRepository.albums,
            albumRepository.songs,
        ) { albums, songs ->
            LibraryCounts(songCount = songs.size, albumCount = albums.size)
        }

        val baseFlow = combine(
            albumRepository.recentAlbums,
            randomAlbumsFlow,
            countsFlow,
            authRepository.authState,
            playlistRepository.playlists,
        ) { recentAlbums, randomAlbums, counts, authState, playlists ->
            BaseHome(
                recentAlbums = recentAlbums.take(HOME_SECTION_LIMIT),
                randomAlbums = randomAlbums,
                counts = counts,
                isLoggedIn = authState is AuthState.LoggedIn,
                playlists = playlists,
            )
        }

        combine(
            baseFlow,
            statsAlbumsFlow,
            statsTracksFlow,
            statsModeFlow,
        ) { base, statsAlbums, statsTracks, statsMode ->
            HomeUiState(
                isLoading = false,
                songCount = base.counts.songCount,
                albumCount = base.counts.albumCount,
                randomAlbums = base.randomAlbums,
                playlists = base.playlists.take(HOME_SECTION_LIMIT),
                playlistCount = base.playlists.size,
                statsAlbums = statsAlbums,
                statsTracks = statsTracks,
                statsMode = statsMode,
                recentAlbums = base.recentAlbums,
                isLoggedIn = base.isLoggedIn,
            )
        }
            .catch { throwable ->
                emit(
                    HomeUiState(
                        isLoading = false,
                        errorMessage = throwable.message ?: "音乐库加载失败，请稍后重试",
                    ),
                )
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                initialValue = HomeUiState(),
            )
    }

    /** 随机专辑：换一批。 */
    fun shuffleRandomAlbums() {
        shuffleTick.update { it + 1 }
    }

    /** 发现页听歌统计预览：切换专辑时长 / 歌曲次数，并持久化。 */
    fun setHomeStatsMode(mode: HomeStatsMode) {
        if (uiState.value.statsMode == mode) return
        viewModelScope.launch {
            runCatching { settingsRepository.setHomeStatsMode(mode) }
        }
    }

    private data class BaseHome(
        val recentAlbums: List<Album>,
        val randomAlbums: List<Album>,
        val counts: LibraryCounts,
        val isLoggedIn: Boolean,
        val playlists: List<Playlist>,
    )

    private companion object {
        const val HOME_SECTION_LIMIT = 4
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
