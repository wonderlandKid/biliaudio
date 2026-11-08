package com.wonderlandkid.biliaudio.ui.playlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wonderlandkid.biliaudio.data.model.PlaylistWithTracks
import com.wonderlandkid.biliaudio.data.model.TrackWithAlbum
import com.wonderlandkid.biliaudio.data.repository.AlbumRepository
import com.wonderlandkid.biliaudio.data.repository.PlaylistRepository
import com.wonderlandkid.biliaudio.player.PlaybackController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PlaylistDetailExtra(
    val message: String? = null,
    val deleted: Boolean = false,
)

data class PlaylistDetailUiState(
    val playlist: PlaylistWithTracks? = null,
    val allSongs: List<TrackWithAlbum> = emptyList(),
    val message: String? = null,
    val deleted: Boolean = false,
)

class PlaylistDetailViewModel(
    private val playlistId: Long,
    private val playlistRepository: PlaylistRepository,
    albumRepository: AlbumRepository,
    private val playbackController: PlaybackController,
) : ViewModel() {

    private val extra = MutableStateFlow(PlaylistDetailExtra())

    val uiState: StateFlow<PlaylistDetailUiState> = combine(
        playlistRepository.playlistWithTracks(playlistId),
        albumRepository.songs,
        extra,
    ) { playlist, songs, extraState ->
        PlaylistDetailUiState(
            playlist = playlist,
            allSongs = songs,
            message = extraState.message,
            deleted = extraState.deleted,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = PlaylistDetailUiState(),
    )

    fun playAll() {
        val tracks = uiState.value.playlist?.tracks.orEmpty()
        if (tracks.isNotEmpty()) playbackController.playSongs(tracks, 0)
    }

    fun playAt(index: Int) {
        val tracks = uiState.value.playlist?.tracks.orEmpty()
        if (index in tracks.indices) playbackController.playSongs(tracks, index)
    }

    fun addSongs(songs: List<TrackWithAlbum>) {
        if (songs.isEmpty()) return
        viewModelScope.launch {
            runCatching { playlistRepository.addSongs(playlistId, songs) }
                .onSuccess {
                    extra.update { it.copy(message = "已添加 ${songs.size} 首歌曲") }
                }
                .onFailure { error ->
                    extra.update { it.copy(message = "添加失败：${error.message ?: "未知错误"}") }
                }
        }
    }

    fun removeSong(trackUid: String) {
        viewModelScope.launch {
            playlistRepository.removeSong(playlistId, trackUid)
        }
    }

    fun renamePlaylist(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            runCatching { playlistRepository.renamePlaylist(playlistId, trimmed) }
                .onSuccess { extra.update { it.copy(message = "歌单已重命名") } }
                .onFailure { error -> extra.update { it.copy(message = error.message ?: "重命名失败") } }
        }
    }

    fun deletePlaylist() {
        viewModelScope.launch {
            runCatching { playlistRepository.deletePlaylist(playlistId) }
                .onSuccess { extra.update { it.copy(deleted = true) } }
                .onFailure { error -> extra.update { it.copy(message = error.message ?: "删除失败") } }
        }
    }

    fun consumeMessage() {
        extra.update { it.copy(message = null) }
    }
}
