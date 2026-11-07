package com.wonderlandkid.biliaudio.player

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.wonderlandkid.biliaudio.data.model.Album
import com.wonderlandkid.biliaudio.data.repository.MediaStreamResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/**
 * “歌词页”视频播放器：独立于音频引擎的第二路 ExoPlayer，只解码视频轨（静音渲染）。
 *
 * 设计（docs/DESIGN.md）：
 * - 音频管线完全不动，视频页打开时按“专辑绝对进度”定位；
 * - UI 每 ~500ms 用 [VideoSync] 校正漂移（>300ms 才 seek）；
 * - 播放/暂停/拖动与音频联动，离开页面调用 [release] 释放。
 *
 * 使用方式（推荐放在 ViewModel 里持有，`onCleared` 时 [release]）：
 * ```
 * private val videoPlayer = AlbumVideoPlayer(context, streamResolver, viewModelScope)
 * // 页面进入：videoPlayer.prepare(album, audioState.albumPositionMs, autoPlay = audioState.isPlaying)
 * // PlayerView：view.player = videoPlayer.player
 * ```
 */
@androidx.annotation.OptIn(UnstableApi::class)
class AlbumVideoPlayer(
    context: Context,
    private val streamResolver: MediaStreamResolver,
    private val scope: CoroutineScope,
) : AlbumVideoController {

    companion object {
        private const val TAG = "AlbumVideoPlayer"
        private const val MEDIA_ID_PREFIX = "biliaudio:video:"
    }

    private val appContext: Context = context.applicationContext
    private val httpClient: OkHttpClient = MediaHttp.createClient()

    /** 视频数据源（同一时刻只有一路视频流，直接在每次解析后设置请求头即可）。 */
    private val videoDataSourceFactory = OkHttpDataSource.Factory(httpClient)

    private var exoPlayer: ExoPlayer? = null
    private var resolveJob: Job? = null
    private var currentAlbumId: Long? = null

    private val _state = MutableStateFlow(VideoPlaybackState())
    override val state: StateFlow<VideoPlaybackState> = _state.asStateFlow()

    override val player: Player? get() = exoPlayer

    override fun prepare(album: Album, startAlbumPositionMs: Long, autoPlay: Boolean) {
        currentAlbumId = album.id
        _state.value = VideoPlaybackState(albumId = album.id, isPrepared = false)
        val exo = ensurePlayer()
        // 切专辑时先停掉旧视频，避免旧画面继续播放
        exo.pause()
        resolveJob?.cancel()
        resolveJob = scope.launch {
            try {
                val streams = streamResolver.resolve(album)
                val videoUrl = streams.videoUrl?.takeIf { it.isNotBlank() }
                    ?: throw IllegalStateException(PlayerErrorMessages.VIDEO_STREAM_UNAVAILABLE)
                // 视频流同样必须带 UA/Referer/Cookie，否则 403
                videoDataSourceFactory.setDefaultRequestProperties(MediaHttp.mergeHeaders(streams.headers))
                val metadata = MediaMetadata.Builder()
                    .setTitle(album.title)
                    .setArtist(album.artist)
                    .setAlbumTitle(album.title)
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .apply {
                        if (album.coverUrl.isNotBlank()) setArtworkUri(Uri.parse(album.coverUrl))
                    }
                    .build()
                exo.setMediaItem(
                    MediaItem.Builder()
                        .setMediaId(MEDIA_ID_PREFIX + album.id)
                        .setUri(videoUrl)
                        .setMediaMetadata(metadata)
                        .build(),
                )
                exo.prepare()
                val startPositionMs = startAlbumPositionMs.coerceAtLeast(0L)
                if (startPositionMs > 0L) exo.seekTo(startPositionMs)
                if (autoPlay) exo.play() else exo.pause()
                if (currentAlbumId == album.id) {
                    _state.update {
                        it.copy(
                            albumId = album.id,
                            isPrepared = exo.playbackState == Player.STATE_READY,
                            error = null,
                        )
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                Log.w(TAG, "准备视频流失败", error)
                _state.update {
                    it.copy(
                        albumId = album.id,
                        isPrepared = false,
                        isPlaying = false,
                        error = PlayerErrorMessages.VIDEO_STREAM_UNAVAILABLE,
                    )
                }
            }
        }
    }

    override fun play() {
        exoPlayer?.play()
    }

    override fun pause() {
        exoPlayer?.pause()
    }

    override fun seekTo(albumPositionMs: Long) {
        exoPlayer?.seekTo(albumPositionMs.coerceAtLeast(0L))
    }

    override fun release() {
        resolveJob?.cancel()
        resolveJob = null
        currentAlbumId = null
        exoPlayer?.release()
        exoPlayer = null
        _state.value = VideoPlaybackState()
    }

    /** 懒创建 ExoPlayer：页面首次 prepare 时才分配解码资源。 */
    private fun ensurePlayer(): ExoPlayer {
        exoPlayer?.let { return it }
        val mediaSourceFactory = DefaultMediaSourceFactory(appContext)
            .setDataSourceFactory(videoDataSourceFactory)
        val exo = ExoPlayer.Builder(appContext)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
        // 只渲染视频轨：即使流里混有音轨也不出声（避免和音频引擎双重播放）
        exo.volume = 0f
        exo.playWhenReady = false
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    _state.update { it.copy(isPrepared = true, error = null) }
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _state.update { it.copy(isPlaying = isPlaying) }
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.w(TAG, "视频播放出错：${error.errorCodeName}(${error.errorCode})", error)
                _state.update {
                    it.copy(
                        isPrepared = false,
                        isPlaying = false,
                        error = PlayerErrorMessages.VIDEO_STREAM_UNAVAILABLE,
                    )
                }
            }
        })
        exoPlayer = exo
        return exo
    }
}
