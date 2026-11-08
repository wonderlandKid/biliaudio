package com.wonderlandkid.biliaudio.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wonderlandkid.biliaudio.data.model.Album
import com.wonderlandkid.biliaudio.data.model.Track
import com.wonderlandkid.biliaudio.data.model.TrackWithAlbum
import com.wonderlandkid.biliaudio.data.repository.AlbumRepository
import com.wonderlandkid.biliaudio.data.repository.PlaybackStatsRepository
import com.wonderlandkid.biliaudio.player.PlaybackController
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class AlbumListeningUi(
    val album: Album,
    val listenedMs: Long,
)

data class TrackPlayUi(
    val song: TrackWithAlbum,
    val playCount: Int,
    val listenedMs: Long,
)

data class StatsUiState(
    val isLoading: Boolean = true,
    val albumStats: List<AlbumListeningUi> = emptyList(),
    val trackStats: List<TrackPlayUi> = emptyList(),
    val totalListenedMs: Long = 0L,
    val totalPlayCount: Int = 0,
    val currentTrackId: Long? = null,
    val isPlaying: Boolean = false,
)

private data class Highlight(val trackId: Long?, val isPlaying: Boolean)

class StatsViewModel(
    albumRepository: AlbumRepository,
    statsRepository: PlaybackStatsRepository,
    private val playbackController: PlaybackController,
) : ViewModel() {

    private val playbackHighlight = playbackController.state
        .map { Highlight(trackId = it.currentTrack?.id, isPlaying = it.isPlaying) }
        .distinctUntilChanged()

    val uiState: StateFlow<StatsUiState> = combine(
        albumRepository.albums,
        albumRepository.songs,
        statsRepository.observeAlbumListening(),
        statsRepository.observeTrackPlayCounts(),
        playbackHighlight,
    ) { albums, songs, albumStats, trackStats, highlight ->
        val albumsById = albums.associateBy { it.id }
        val songsByUid = songs.associateBy { it.track.uid }
        val albumUi = albumStats.mapNotNull { stat ->
            albumsById[stat.albumId]?.let { AlbumListeningUi(it, stat.listenedMs) }
        }
        val trackUi = trackStats.mapNotNull { stat ->
            val song = songsByUid[stat.trackUid] ?: run {
                // 未分割专辑没有 tracks 行，播放的是 album:<id> 虚拟整张曲目；
                // 统计页仍然把它显示为一条“整张专辑”，避免播放次数永远是 0。
                if (stat.trackUid.startsWith("album:")) {
                    albumsById[stat.albumId]?.let { album ->
                        TrackWithAlbum(
                            track = Track(
                                uid = stat.trackUid,
                                albumId = album.id,
                                index = 0,
                                title = album.title,
                                startMs = 0L,
                                endMs = album.durationMs.coerceAtLeast(1L),
                            ),
                            album = album,
                        )
                    }
                } else {
                    null
                }
            } ?: return@mapNotNull null
            TrackPlayUi(song, stat.playCount, stat.listenedMs)
        }
        StatsUiState(
            isLoading = false,
            albumStats = albumUi,
            trackStats = trackUi,
            totalListenedMs = albumUi.sumOf { it.listenedMs },
            totalPlayCount = trackUi.sumOf { it.playCount },
            currentTrackId = highlight.trackId,
            isPlaying = highlight.isPlaying,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = StatsUiState(),
    )

    /** 统计页点歌与音乐库一致：播放所属专辑，并从这首歌开始。 */
    fun playSong(song: TrackWithAlbum) {
        if (song.track.id > 0L) {
            playbackController.playTrack(albumId = song.album.id, trackId = song.track.id)
        } else {
            // 未分割专辑的虚拟整张曲目
            playbackController.playAlbum(song.album.id)
        }
    }
}
