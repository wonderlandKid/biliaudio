package com.wonderlandkid.biliaudio.ui.home

import androidx.compose.runtime.Immutable

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wonderlandkid.biliaudio.data.model.Album
import com.wonderlandkid.biliaudio.data.repository.AlbumRepository
import com.wonderlandkid.biliaudio.data.repository.AuthRepository
import com.wonderlandkid.biliaudio.data.repository.AuthState
import com.wonderlandkid.biliaudio.player.PlaybackController
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * 发现页 UI 状态。
 *
 * 数据全部来自 [AlbumRepository] / [AuthRepository] 的 Flow，不写死假数据。
 */
@Immutable
data class HomeUiState(
    val isLoading: Boolean = true,
    /** 音乐库歌曲总数 */
    val songCount: Int = 0,
    /** 音乐库专辑总数 */
    val albumCount: Int = 0,
    /** 最近播放的专辑（横向卡片） */
    val recentAlbums: List<Album> = emptyList(),
    /** 我的专辑（两列网格） */
    val albums: List<Album> = emptyList(),
    val isLoggedIn: Boolean = false,
    val errorMessage: String? = null,
) {
    /** 顶部统计行文案，空库时给引导文案。 */
    val statsText: String
        get() = when {
            isLoading -> "正在加载音乐库…"
            songCount == 0 && albumCount == 0 -> "音乐库还是空的，导入第一张专辑吧"
            else -> "$songCount 首歌曲 · $albumCount 张专辑"
        }
}

/** 发现页 ViewModel：组合专辑/歌曲/最近播放/登录态四个数据源。 */
class HomeViewModel(
    albumRepository: AlbumRepository,
    authRepository: AuthRepository,
    private val playbackController: PlaybackController,
) : ViewModel() {

    private data class LibraryCounts(val songCount: Int, val albumCount: Int)

    val uiState: StateFlow<HomeUiState> = run {
        val countsFlow = combine(
            albumRepository.albums,
            albumRepository.songs,
        ) { albums, songs ->
            LibraryCounts(songCount = songs.size, albumCount = albums.size)
        }

        combine(
            albumRepository.recentAlbums,
            albumRepository.albums,
            countsFlow,
            authRepository.authState,
        ) { recentAlbums, albums, counts, authState ->
            HomeUiState(
                isLoading = false,
                songCount = counts.songCount,
                albumCount = counts.albumCount,
                recentAlbums = recentAlbums.take(MAX_RECENT_ALBUMS),
                albums = albums.sortedByDescending { album -> album.createdAt },
                isLoggedIn = authState is AuthState.LoggedIn,
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

    /** 「最近播放」卡片右上角播放按钮：播放最近一张专辑。 */
    fun playRecentAlbum(albumId: Long) {
        playbackController.playAlbum(albumId)
    }

    private companion object {
        /** 卡片内只展示前几条，其余通过「查看全部」进入音乐库 */
        const val MAX_RECENT_ALBUMS = 6
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
