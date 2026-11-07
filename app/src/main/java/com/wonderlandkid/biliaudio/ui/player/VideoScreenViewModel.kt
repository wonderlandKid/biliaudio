package com.wonderlandkid.biliaudio.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import com.wonderlandkid.biliaudio.data.repository.AlbumRepository
import com.wonderlandkid.biliaudio.data.repository.MediaStreamResolver
import com.wonderlandkid.biliaudio.player.AlbumVideoController
import com.wonderlandkid.biliaudio.player.PlaybackController
import com.wonderlandkid.biliaudio.player.PlaybackState
import com.wonderlandkid.biliaudio.player.VideoPhase
import com.wonderlandkid.biliaudio.player.VideoPlaybackState
import com.wonderlandkid.biliaudio.player.VideoSync
import com.wonderlandkid.biliaudio.util.Diag
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 歌词（原视频）页 ViewModel。
 *
 * 职责：
 * 1. 持有第二路 [AlbumVideoController]（由 AppContainer.createVideoController 创建，与音频引擎完全独立），
 *    页面销毁时 release；
 * 2. 每 [VideoSync.SYNC_INTERVAL_MS] 毫秒同步一次：
 *    - 音频切换专辑 → 重新准备视频流并按“专辑绝对进度”定位；
 *    - 播放/暂停联动；
 *    - **只有视频 phase == READY 时才做漂移校正**（缓冲/准备中不 seek，避免打断加载）；
 *    - 漂移超过 [VideoSync.DRIFT_THRESHOLD_MS] 才 seek。
 *
 * 用 `appViewModel { container -> VideoScreenViewModel(...) }` 创建。
 */
class VideoScreenViewModel(
    private val playbackController: PlaybackController,
    private val albumRepository: AlbumRepository,
    private val streamResolver: MediaStreamResolver,
    private val videoController: AlbumVideoController,
) : ViewModel() {

    /** 音频播放状态（当前曲目 / 专辑绝对进度 / 播放中）。 */
    val audioState: StateFlow<PlaybackState> = playbackController.state

    /** 视频播放状态（phase / 清晰度 / 播放中 / 错误）。 */
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

    /**
     * 视频加载失败或加载过慢时手动重试：
     * 丢弃旧的流缓存（地址过期/节点慢时重新解析更可能成功）并重新准备视频。
     */
    fun retry() {
        val albumId = audioState.value.currentAlbumId ?: return
        if (prepareInFlight) return
        Diag.log(
            "Video",
            "retry tapped album=$albumId phase=${videoState.value.phase} " +
                "prepared=${videoState.value.isPrepared} error=${videoState.value.error}",
        )
        requestedAlbumId = null
        streamResolver.invalidate(albumId)
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
        // 只有 READY 才参与同步：准备/缓冲阶段 seek 会打断加载，导致「转圈更久」
        if (video.phase != VideoPhase.READY || video.error != null) return

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
