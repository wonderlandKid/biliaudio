package com.wonderlandkid.biliaudio.ui.player

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import com.wonderlandkid.biliaudio.data.repository.AlbumRepository
import com.wonderlandkid.biliaudio.data.repository.MediaStreamResolver
import com.wonderlandkid.biliaudio.player.AlbumVideoController
import com.wonderlandkid.biliaudio.player.AlbumVideoPlayer
import com.wonderlandkid.biliaudio.player.PlaybackController
import com.wonderlandkid.biliaudio.player.PlaybackState
import com.wonderlandkid.biliaudio.player.VideoPlaybackState
import com.wonderlandkid.biliaudio.player.VideoSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 歌词（原视频）页 ViewModel。
 *
 * 职责：
 * 1. 持有第二路 [AlbumVideoPlayer]（与音频引擎完全独立），页面销毁时 release；
 * 2. 每 [VideoSync.SYNC_INTERVAL_MS] 毫秒同步一次：
 *    - 音频切换专辑 → 重新准备视频流并按“专辑绝对进度”定位；
 *    - 播放/暂停联动；
 *    - 漂移超过 [VideoSync.DRIFT_THRESHOLD_MS] 才 seek（避免频繁打断视频缓冲）。
 *
 * 用 `appViewModel { container -> VideoScreenViewModel(...) }` 创建。
 */
class VideoScreenViewModel(
    private val playbackController: PlaybackController,
    private val albumRepository: AlbumRepository,
    streamResolver: MediaStreamResolver,
    context: Context,
    scope: CoroutineScope,
) : ViewModel() {

    private val videoController: AlbumVideoController =
        AlbumVideoPlayer(context.applicationContext, streamResolver, scope)

    /** 音频播放状态（当前曲目 / 专辑绝对进度 / 播放中）。 */
    val audioState: StateFlow<PlaybackState> = playbackController.state

    /** 视频播放状态（准备中 / 播放中 / 错误）。 */
    val videoState: StateFlow<VideoPlaybackState> = videoController.state

    /** 给 PlayerView 挂载的 ExoPlayer（视频播放器懒创建，未准备时为 null）。 */
    val player: Player? get() = videoController.player

    /** 已请求准备的专辑 id，避免轮询重复触发解析。 */
    private var requestedAlbumId: Long? = null
    private var prepareInFlight = false

    init {
        viewModelScope.launch {
            while (isActive) {
                runCatching { syncTick() }
                delay(VideoSync.SYNC_INTERVAL_MS)
            }
        }
    }

    // ------------------------------------------------------------------ 对外操作

    fun togglePlayPause() = playbackController.togglePlayPause()

    fun previous() = playbackController.previous()

    fun next() = playbackController.next()

    /** 拖动“专辑绝对进度”进度条：音频与视频同时定位。 */
    fun seekToAlbumPosition(albumPositionMs: Long) {
        playbackController.seekToAlbumPosition(albumPositionMs)
        videoController.seekTo(albumPositionMs)
    }

    /** 播放列表面板点选。 */
    fun jumpToQueueIndex(index: Int) = playbackController.jumpToQueueIndex(index)

    /** 页面退到后台 / 被销毁时暂停视频（音频继续后台播放）。 */
    fun pauseVideo() = videoController.pause()

    /** 视频流准备失败后手动重试。 */
    fun retry() {
        requestedAlbumId = null
        val albumId = audioState.value.currentAlbumId ?: return
        viewModelScope.launch {
            prepareAlbum(albumId, audioState.value)
        }
    }

    override fun onCleared() {
        super.onCleared()
        // 离开页面释放第二路视频播放器（docs/DESIGN.md 要求）
        videoController.release()
    }

    // ------------------------------------------------------------------ 同步逻辑

    private suspend fun syncTick() {
        val audio = audioState.value
        val albumId = audio.currentAlbumId ?: return
        if (requestedAlbumId != albumId) {
            prepareAlbum(albumId, audio)
            return
        }
        val video = videoState.value
        if (!video.isPrepared || video.error != null) return

        if (audio.isPlaying) {
            if (!video.isPlaying) videoController.play()
            // 漂移校正：只有超过阈值才 seek，避免打断视频解码
            val videoPositionMs = videoController.player?.currentPosition ?: return
            if (VideoSync.needsCorrection(audio.albumPositionMs, videoPositionMs)) {
                videoController.seekTo(VideoSync.targetPosition(audio.albumPositionMs))
            }
        } else if (video.isPlaying) {
            videoController.pause()
        }
    }

    private suspend fun prepareAlbum(albumId: Long, audio: PlaybackState) {
        if (prepareInFlight) return
        prepareInFlight = true
        // 先记录请求（即使查库失败也不再每 500ms 重试一次，交给用户手动重试）
        requestedAlbumId = albumId
        try {
            val album = albumRepository.getAlbum(albumId) ?: return
            videoController.prepare(
                album = album,
                startAlbumPositionMs = audio.albumPositionMs,
                autoPlay = audio.isPlaying,
            )
        } finally {
            prepareInFlight = false
        }
    }
}
