package com.wonderlandkid.biliaudio.player

import androidx.media3.common.Player
import com.wonderlandkid.biliaudio.data.model.Album
import kotlinx.coroutines.flow.StateFlow

data class VideoPlaybackState(
    val albumId: Long? = null,
    val isPrepared: Boolean = false,
    val isPlaying: Boolean = false,
    val error: String? = null,
)

/**
 * “歌词页”使用的 B 站原视频播放器（独立于音频播放器，只解码视频轨）。
 *
 * 使用方式：
 * 1. [prepare] 传入当前专辑与音频在专辑流中的绝对进度；
 * 2. UI 将 [player] 设置到 `PlayerView.player` 渲染画面；
 * 3. 播放/暂停/拖动时调用对应方法，并与 [PlaybackController] 的音频进度保持同步。
 */
interface AlbumVideoController {
    /** ExoPlayer 实例；UI 可挂到 PlayerView。未初始化时为 null。 */
    val player: Player?
    val state: StateFlow<VideoPlaybackState>

    /** 准备某专辑的视频流，并从 [startAlbumPositionMs] 开始播放。 */
    fun prepare(album: Album, startAlbumPositionMs: Long, autoPlay: Boolean = true)
    fun play()
    fun pause()
    fun seekTo(albumPositionMs: Long)
    fun release()
}
