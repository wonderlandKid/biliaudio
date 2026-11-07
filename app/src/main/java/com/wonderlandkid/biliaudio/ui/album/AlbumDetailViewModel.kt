package com.wonderlandkid.biliaudio.ui.album

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wonderlandkid.biliaudio.data.model.Album
import com.wonderlandkid.biliaudio.data.model.Track
import com.wonderlandkid.biliaudio.data.repository.AlbumRepository
import com.wonderlandkid.biliaudio.data.repository.BiliRepository
import com.wonderlandkid.biliaudio.player.PlaybackController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 专辑详情 UI 状态。 */
data class AlbumDetailUiState(
    val isLoading: Boolean = true,
    val album: Album? = null,
    val tracks: List<Track> = emptyList(),
    /** 当前播放曲目（用于高亮） */
    val currentTrackId: Long? = null,
    val isPlaying: Boolean = false,
    /** 播放器里正在播放的是否就是本专辑 */
    val isThisAlbumPlaying: Boolean = false,
    val isRefreshing: Boolean = false,
    val isDeleting: Boolean = false,
    /** 删除成功后由页面收口返回上一页 */
    val deleted: Boolean = false,
    /** 一次性提示（Snackbar） */
    val message: String? = null,
)

/**
 * 专辑详情 ViewModel：订阅 [AlbumRepository.albumWithTracks] 实时数据，并桥接播放状态。
 */
class AlbumDetailViewModel(
    private val albumId: Long,
    private val albumRepository: AlbumRepository,
    private val biliRepository: BiliRepository,
    private val playbackController: PlaybackController,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AlbumDetailUiState())
    val uiState: StateFlow<AlbumDetailUiState> = _uiState.asStateFlow()

    init {
        observeAlbum()
        observePlayback()
    }

    private fun observeAlbum() {
        viewModelScope.launch {
            albumRepository.albumWithTracks(albumId)
                .catch { throwable ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            message = "加载专辑失败：${throwable.message ?: "未知错误"}",
                        )
                    }
                }
                .collect { albumWithTracks ->
                    _uiState.update { state ->
                        state.copy(
                            isLoading = false,
                            album = albumWithTracks?.album,
                            tracks = albumWithTracks?.tracks.orEmpty().sortedBy { track -> track.startMs },
                        )
                    }
                }
        }
    }

    private fun observePlayback() {
        viewModelScope.launch {
            playbackController.state.collect { playback ->
                _uiState.update {
                    it.copy(
                        currentTrackId = playback.currentTrack?.id,
                        isPlaying = playback.isPlaying,
                        isThisAlbumPlaying = playback.currentAlbumId == albumId && playback.isPlaying,
                    )
                }
            }
        }
    }

    /** 播放全部（队列 = 该专辑全部分曲）。 */
    fun playAll() {
        playbackController.playAlbum(albumId)
    }

    /** 播放专辑内某一首。 */
    fun playTrack(trackId: Long) {
        playbackController.playTrack(albumId, trackId)
    }

    /** 更多菜单 → 刷新视频信息（标题/艺术家/封面/时长/分 P 信息）。 */
    fun refreshVideoInfo() {
        val album = _uiState.value.album ?: return
        if (_uiState.value.isRefreshing) return
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true) }
            try {
                val info = biliRepository.previewVideo(album.bvid)
                albumRepository.updateAlbum(
                    album.copy(
                        title = info.title,
                        artist = info.ownerName,
                        coverUrl = info.coverUrl,
                        durationMs = info.durationMs,
                        cid = info.pages.firstOrNull()?.cid ?: album.cid,
                        page = info.pages.firstOrNull()?.page ?: album.page,
                        pageCount = info.pages.size,
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
                _uiState.update { it.copy(isRefreshing = false, message = "视频信息已刷新") }
            } catch (throwable: Exception) {
                _uiState.update {
                    it.copy(
                        isRefreshing = false,
                        message = "刷新失败：${throwable.message ?: "未知错误"}",
                    )
                }
            }
        }
    }

    /** 更多菜单 → 删除专辑（确认弹窗由页面负责）。 */
    fun deleteAlbum() {
        if (_uiState.value.isDeleting) return
        viewModelScope.launch {
            _uiState.update { it.copy(isDeleting = true) }
            try {
                albumRepository.deleteAlbum(albumId)
                _uiState.update { it.copy(isDeleting = false, deleted = true) }
            } catch (throwable: Exception) {
                _uiState.update {
                    it.copy(
                        isDeleting = false,
                        message = "删除失败：${throwable.message ?: "未知错误"}",
                    )
                }
            }
        }
    }

    /** Snackbar 展示过后清空一次性提示。 */
    fun consumeMessage() {
        _uiState.update { it.copy(message = null) }
    }
}
