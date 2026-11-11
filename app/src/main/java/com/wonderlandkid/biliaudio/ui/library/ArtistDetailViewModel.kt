package com.wonderlandkid.biliaudio.ui.library

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wonderlandkid.biliaudio.data.model.Album
import com.wonderlandkid.biliaudio.data.model.TrackWithAlbum
import com.wonderlandkid.biliaudio.data.repository.AlbumRepository
import com.wonderlandkid.biliaudio.player.PlaybackController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 艺术家详情 UI 状态。 */
@Immutable
data class ArtistDetailUiState(
    val isLoading: Boolean = true,
    /** 艺术家展示名（空艺术家统一为「未知艺术家」） */
    val artistName: String = "",
    val albums: List<Album> = emptyList(),
    val songs: List<TrackWithAlbum> = emptyList(),
    val currentTrackId: Long? = null,
    val isPlaying: Boolean = false,
    val errorMessage: String? = null,
) {
    /** 头部封面 = 该艺术家第一张专辑的封面（不随机）。 */
    val firstAlbum: Album? get() = albums.firstOrNull()

    val subtitle: String get() = "${albums.size} 张专辑 · ${songs.size} 首歌曲"
}

/**
 * 艺术家详情 ViewModel：从专辑 / 歌曲 Flow 里按艺术家名过滤。
 *
 * 单曲点击走 [PlaybackController.playTrack]（队列 = 该曲所属专辑的分曲）；
 * 专辑点击由页面回调跳转专辑详情。
 */
class ArtistDetailViewModel(
    artistName: String,
    private val albumRepository: AlbumRepository,
    private val playbackController: PlaybackController,
) : ViewModel() {

    private val normalizedName = artistName.normalizedArtistName()

    private val _uiState = MutableStateFlow(
        ArtistDetailUiState(artistName = normalizedName),
    )
    val uiState: StateFlow<ArtistDetailUiState> = _uiState.asStateFlow()

    init {
        observeArtistData()
        observePlayback()
    }

    private fun observeArtistData() {
        viewModelScope.launch {
            combine(
                albumRepository.albums,
                albumRepository.songs,
            ) { albums, songs ->
                val artistAlbums = albums.filter { album ->
                    album.artist.normalizedArtistName() == normalizedName
                }
                val artistSongs = songs.filter { song ->
                    song.album.artist.normalizedArtistName() == normalizedName
                }
                artistAlbums to artistSongs
            }.catch { throwable ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = throwable.message ?: "加载艺术家数据失败",
                    )
                }
            }.collect { (albums, songs) ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        albums = albums,
                        songs = songs,
                    )
                }
            }
        }
    }

    private fun observePlayback() {
        viewModelScope.launch {
            // 只关心「当前曲目 / 是否播放」，过滤掉 250ms 的播放进度 tick，避免列表滚动时反复重组
            playbackController.state
                .map { playback -> PlaybackHighlight(trackId = playback.currentTrack?.id, isPlaying = playback.isPlaying) }
                .distinctUntilChanged()
                .collect { highlight ->
                    _uiState.update {
                        it.copy(
                            currentTrackId = highlight.trackId,
                            isPlaying = highlight.isPlaying,
                        )
                    }
                }
        }
    }

    /** 点击歌曲：只播放该曲所属专辑（从选中曲开始）。 */
    fun playSong(song: TrackWithAlbum) {
        playbackController.playTrack(albumId = song.album.id, trackId = song.track.id)
    }
}
