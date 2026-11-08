package com.wonderlandkid.biliaudio.player

import androidx.compose.runtime.Immutable

import com.wonderlandkid.biliaudio.data.model.Album
import com.wonderlandkid.biliaudio.data.model.Track
import com.wonderlandkid.biliaudio.data.model.TrackWithAlbum
import kotlinx.coroutines.flow.StateFlow

enum class RepeatMode { OFF, ALL, ONE }

/** 队列中的一首（已带上展示所需的专辑信息，避免 UI 反复查库）。 */
@Immutable
data class QueueEntry(
    val track: Track,
    val albumId: Long,
    val albumTitle: String,
    val artist: String,
    val coverUrl: String,
    /** 用户自定义封面（本机文件绝对路径），null 时使用 [coverUrl] */
    val coverLocalPath: String? = null,
    val albumDurationMs: Long,
) {
    val durationMs: Long get() = track.durationMs
}

@Immutable
data class PlaybackState(
    val currentTrack: Track? = null,
    val currentAlbumId: Long? = null,
    val queue: List<QueueEntry> = emptyList(),
    val currentIndex: Int = -1,
    val isPlaying: Boolean = false,
    val isLoading: Boolean = false,
    /** 当前歌曲内进度 */
    val positionMs: Long = 0L,
    val trackDurationMs: Long = 0L,
    /** 专辑音频流内的绝对进度（= track.startMs + positionMs） */
    val albumPositionMs: Long = 0L,
    val albumDurationMs: Long = 0L,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val shuffle: Boolean = false,
    val error: String? = null,
)

/**
 * 播放控制入口（UI 只依赖此接口）。实现由 Media3 MediaSessionService 提供。
 */
interface PlaybackController {
    val state: StateFlow<PlaybackState>

    /** 播放整张专辑（队列 = 该专辑全部分曲）。 */
    fun playAlbum(albumId: Long, startTrackIndex: Int = 0)

    /** 播放专辑内某一首（队列 = 该专辑，定位到该曲）。 */
    fun playTrack(albumId: Long, trackId: Long)

    /** 播放任意歌曲列表（如搜索结果），跨专辑时会逐张解析。 */
    fun playSongs(songs: List<TrackWithAlbum>, startIndex: Int)

    fun togglePlayPause()
    fun play()
    fun pause()
    fun next()
    fun previous()

    /** 跳转到当前歌曲内的进度。 */
    fun seekToTrackPosition(positionMs: Long)

    /** 跳转到当前专辑流的绝对进度（歌词/视频页同步用）。 */
    fun seekToAlbumPosition(albumPositionMs: Long)

    /** 跳转到队列第 [index] 首（播放列表面板点选用）。 */
    fun jumpToQueueIndex(index: Int)

    fun setRepeatMode(mode: RepeatMode)
    fun cycleRepeatMode()
    fun toggleShuffle()
    fun dismissError()

    /** 把最新 UI 状态同步给系统通知（由 Service 内部实现，UI 不需要调用）。 */
    fun syncMediaSession(album: Album, currentTrack: Track) {}
}
