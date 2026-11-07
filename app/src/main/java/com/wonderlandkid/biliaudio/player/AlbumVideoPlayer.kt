package com.wonderlandkid.biliaudio.player

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import com.wonderlandkid.biliaudio.data.model.Album
import com.wonderlandkid.biliaudio.data.repository.MediaStreamResolver
import com.wonderlandkid.biliaudio.util.Diag
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.IOException

/**
 * “歌词页”视频播放器：独立于音频引擎的第二路 ExoPlayer，只解码视频轨（静音渲染）。
 *
 * 设计（docs/DESIGN.md）：
 * - 音频管线完全不动，视频页打开时按“专辑绝对进度”定位；
 * - UI 每 ~500ms 用 [VideoSync] 校正漂移（>300ms 才 seek）；
 * - 播放/暂停/拖动与音频联动，离开页面调用 [release] 释放。
 *
 * 诊断（排查“转圈很久”，日志 tag = `Video`，`adb logcat -s BiliDiag` 可见）：
 * - prepare START → resolver 返回耗时/分辨率/CDN host → prepare+seek 下发耗时 →
 *   BUFFERING/READY 耗时 → 首帧渲染耗时 → 播放中/错误；
 * - 首次视频格式（codecs/分辨率/码率）、解码器名（硬件/软件解码）、前几次网络加载
 *   （字节数/耗时）各记一条，避免日志噪音；
 * - [VideoPlaybackState.phase] 驱动歌词页覆盖层文案（获取流 / 准备播放器 / 缓冲 / 就绪）。
 */
@androidx.annotation.OptIn(UnstableApi::class)
class AlbumVideoPlayer(
    context: Context,
    private val streamResolver: MediaStreamResolver,
    private val scope: CoroutineScope,
) : AlbumVideoController {

    companion object {
        /** 诊断日志 tag（Diag 输出形如 `[Video] ...`）。 */
        private const val TAG = "Video"

        /** 原始 Logcat tag（仅错误用）。 */
        private const val LOG_TAG = "AlbumVideoPlayer"

        private const val MEDIA_ID_PREFIX = "biliaudio:video:"

        /** 单次 prepare 最多记录的加载事件条数，避免日志噪音。 */
        private const val MAX_LOAD_LOGS = 3

        /** 软件解码器名特征（诊断「首帧慢」是否因为走软解）。 */
        private val SOFTWARE_DECODER_MARKERS = listOf("c2.android", "ffmpeg", "sw.", "software")
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

    // ------------------------------------------------------------------
    // 诊断计时 / 一次性标记（每次 prepare 重置）
    // ------------------------------------------------------------------

    /** 每次 prepare 递增，用于丢弃过期协程/回调的状态更新。 */
    private var prepareSession = 0
    private var prepareStartedAtMs = 0L
    private var resolveElapsedMs = 0L
    private var currentVideoHost: String? = null

    private var bufferingLogged = false
    private var readyLogged = false
    private var firstFrameLogged = false
    private var playingLogged = false
    private var videoFormatLogged = false
    private var decoderLogged = false
    private var loadEventCount = 0

    override fun prepare(album: Album, startAlbumPositionMs: Long, autoPlay: Boolean) {
        val session = ++prepareSession
        currentAlbumId = album.id
        prepareStartedAtMs = System.currentTimeMillis()
        resolveElapsedMs = 0L
        currentVideoHost = null
        bufferingLogged = false
        readyLogged = false
        firstFrameLogged = false
        playingLogged = false
        videoFormatLogged = false
        decoderLogged = false
        loadEventCount = 0
        _state.value = VideoPlaybackState(albumId = album.id, phase = VideoPhase.RESOLVING)
        Diag.log(
            TAG,
            "prepare START album=${album.id} cid=${album.cid} startPos=${startAlbumPositionMs}ms autoPlay=$autoPlay",
        )

        val exo = ensurePlayer()
        // 切专辑时先停掉旧视频，避免旧画面继续播放
        exo.pause()
        resolveJob?.cancel()
        resolveJob = scope.launch {
            try {
                // 1) 解析流（数据层有缓存，通常几十 ms 内返回；数据层自身会打 Resolver 日志）
                val resolveStartMs = System.currentTimeMillis()
                val streams = streamResolver.resolve(album)
                val resolveCostMs = System.currentTimeMillis() - resolveStartMs
                if (session != prepareSession) return@launch

                val videoUrl = streams.videoUrl?.takeIf { it.isNotBlank() }
                    ?: throw IllegalStateException(PlayerErrorMessages.VIDEO_STREAM_UNAVAILABLE)
                val host = runCatching { Uri.parse(videoUrl).host }.getOrNull().orEmpty()
                resolveElapsedMs = resolveCostMs
                currentVideoHost = host
                Diag.log(
                    TAG,
                    "resolve DONE in ${resolveCostMs}ms audio=${streams.audioQualityLabel}(id=${streams.audioQualityId}) " +
                        "video=${streams.videoHeight?.let { "${it}p" } ?: "-"} host=$host",
                )
                updateIfCurrent(session) {
                    it.copy(
                        albumId = album.id,
                        phase = VideoPhase.PREPARING,
                        videoHeight = streams.videoHeight,
                        error = null,
                    )
                }

                // 2) 注入请求头并下发播放器命令（同步耗时；真正的加载看后面的 BUFFERING/READY/load 日志）
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
                val issuedStartMs = System.currentTimeMillis()
                val startPositionMs = startAlbumPositionMs.coerceAtLeast(0L)
                // 直接用带起始位置的 setMediaItem，省掉 prepare 之后的一次 seek 往返（更快出首帧）
                exo.setMediaItem(
                    MediaItem.Builder()
                        .setMediaId(MEDIA_ID_PREFIX + album.id)
                        .setUri(videoUrl)
                        .setMediaMetadata(metadata)
                        .build(),
                    startPositionMs,
                )
                exo.prepare()
                if (autoPlay) exo.play() else exo.pause()
                val issuedCostMs = System.currentTimeMillis() - issuedStartMs
                Diag.log(
                    TAG,
                    "player prepare ISSUED in ${issuedCostMs}ms " +
                        "(setMediaItem(start=${startPositionMs}ms)+prepare+play, " +
                        "elapsed=${System.currentTimeMillis() - prepareStartedAtMs}ms)",
                )
                val playerState = exo.playbackState
                updateIfCurrent(session) {
                    it.copy(
                        albumId = album.id,
                        phase = if (playerState == Player.STATE_READY) VideoPhase.READY else it.phase,
                        isPrepared = playerState == Player.STATE_READY,
                        videoHeight = it.videoHeight ?: streams.videoHeight,
                        error = null,
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                Log.w(LOG_TAG, "准备视频流失败", error)
                Diag.log(
                    TAG,
                    "prepare FAILED after ${System.currentTimeMillis() - prepareStartedAtMs}ms album=${album.id} " +
                        "host=$currentVideoHost: ${error.javaClass.simpleName}: ${error.message}",
                    error,
                )
                updateIfCurrent(session) {
                    it.copy(
                        albumId = album.id,
                        phase = VideoPhase.ERROR,
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
        Diag.log(TAG, "release album=$currentAlbumId")
        resolveJob?.cancel()
        resolveJob = null
        currentAlbumId = null
        exoPlayer?.release()
        exoPlayer = null
        _state.value = VideoPlaybackState()
    }

    /** 只应用当前 prepare 会话的状态更新，避免旧协程覆盖新状态。 */
    private inline fun updateIfCurrent(
        session: Int,
        transform: (VideoPlaybackState) -> VideoPlaybackState,
    ) {
        if (session == prepareSession) _state.update(transform)
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
        exo.addListener(PlayerDiagnosticsListener())
        exo.addAnalyticsListener(LoadDiagnosticsListener())
        exoPlayer = exo
        return exo
    }

    // ------------------------------------------------------------------
    // 播放器事件 → phase 状态机 + 诊断日志
    // ------------------------------------------------------------------

    private inner class PlayerDiagnosticsListener : Player.Listener {

        override fun onPlaybackStateChanged(playbackState: Int) {
            val elapsedMs = System.currentTimeMillis() - prepareStartedAtMs
            when (playbackState) {
                Player.STATE_BUFFERING -> {
                    if (!bufferingLogged) {
                        bufferingLogged = true
                        Diag.log(
                            TAG,
                            "BUFFERING after ${elapsedMs}ms (resolve=${resolveElapsedMs}ms host=$currentVideoHost)",
                        )
                    }
                    _state.update {
                        if (it.albumId != null && it.phase != VideoPhase.ERROR) {
                            it.copy(phase = VideoPhase.BUFFERING)
                        } else {
                            it
                        }
                    }
                }

                Player.STATE_READY -> {
                    if (!readyLogged) {
                        readyLogged = true
                        Diag.log(
                            TAG,
                            "READY after ${elapsedMs}ms (resolve=${resolveElapsedMs}ms buffering=$bufferingLogged)",
                        )
                    }
                    _state.update {
                        it.copy(phase = VideoPhase.READY, isPrepared = true, error = null)
                    }
                }

                Player.STATE_ENDED -> {
                    Diag.log(TAG, "ENDED after ${elapsedMs}ms")
                    _state.update { it.copy(isPlaying = false) }
                }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying && !playingLogged) {
                playingLogged = true
                Diag.log(
                    TAG,
                    "playing=true after ${System.currentTimeMillis() - prepareStartedAtMs}ms " +
                        "(ready=${_state.value.phase})",
                )
            }
            _state.update { it.copy(isPlaying = isPlaying) }
        }

        override fun onRenderedFirstFrame() {
            if (!firstFrameLogged) {
                firstFrameLogged = true
                Diag.log(
                    TAG,
                    "first frame RENDERED after ${System.currentTimeMillis() - prepareStartedAtMs}ms " +
                        "host=$currentVideoHost",
                )
            }
            // 首帧到了但没收到 READY 的极端情况下也解除转圈
            _state.update {
                if (it.phase == VideoPhase.PREPARING || it.phase == VideoPhase.BUFFERING) {
                    it.copy(phase = VideoPhase.READY, isPrepared = true)
                } else {
                    it
                }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.w(LOG_TAG, "视频播放出错：${error.errorCodeName}(${error.errorCode})", error)
            Diag.log(
                TAG,
                "player ERROR code=${error.errorCode} name=${error.errorCodeName} " +
                    "after ${System.currentTimeMillis() - prepareStartedAtMs}ms host=$currentVideoHost: ${error.message}",
                error,
            )
            _state.update {
                it.copy(
                    phase = VideoPhase.ERROR,
                    isPrepared = false,
                    isPlaying = false,
                    error = PlayerErrorMessages.VIDEO_STREAM_UNAVAILABLE,
                )
            }
        }
    }

    /** 网络加载 / 编解码器诊断：只记前几条，避免日志噪音。 */
    private inner class LoadDiagnosticsListener : AnalyticsListener {

        override fun onVideoInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: Format,
            decoderReuseEvaluation: DecoderReuseEvaluation?,
        ) {
            if (videoFormatLogged) return
            videoFormatLogged = true
            Diag.log(
                TAG,
                "video FORMAT codecs=${format.codecs ?: "-"} ${format.width}x${format.height} " +
                    "bw=${format.bitrate} fps=${format.frameRate}",
            )
            // 数据层解析结果可能没有高度（跨版本），用格式里的高度兜底
            val formatHeight = format.height
            if (formatHeight > 0) {
                _state.update { if (it.videoHeight == null) it.copy(videoHeight = formatHeight) else it }
            }
        }

        override fun onVideoDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) {
            logDecoderInitialized(decoderName, initializationDurationMs)
        }

        override fun onLoadCompleted(
            eventTime: AnalyticsListener.EventTime,
            loadEventInfo: LoadEventInfo,
            mediaLoadData: MediaLoadData,
        ) {
            loadEventCount++
            if (loadEventCount > MAX_LOAD_LOGS) return
            val dataType = when (mediaLoadData.dataType) {
                C.DATA_TYPE_MANIFEST -> "manifest"
                C.DATA_TYPE_MEDIA -> "media"
                C.DATA_TYPE_MEDIA_INITIALIZATION -> "init"
                C.DATA_TYPE_DRM -> "drm"
                else -> "type=${mediaLoadData.dataType}"
            }
            val format = mediaLoadData.trackFormat
            Diag.log(
                TAG,
                "load#$loadEventCount $dataType ${loadEventInfo.bytesLoaded}B in ${loadEventInfo.loadDurationMs}ms " +
                    "codecs=${format?.codecs ?: "-"} height=${format?.height ?: "-"}",
            )
        }

        override fun onLoadError(
            eventTime: AnalyticsListener.EventTime,
            loadEventInfo: LoadEventInfo,
            mediaLoadData: MediaLoadData,
            error: IOException,
            wasCanceled: Boolean,
        ) {
            if (wasCanceled) return
            Diag.log(
                TAG,
                "load ERROR ${loadEventInfo.bytesLoaded}B in ${loadEventInfo.loadDurationMs}ms: " +
                    "${error.javaClass.simpleName}: ${error.message}",
                error,
            )
        }
    }

    private fun logDecoderInitialized(decoderName: String, initializationDurationMs: Long) {
        if (decoderLogged) return
        decoderLogged = true
        val software = SOFTWARE_DECODER_MARKERS.any { marker ->
            decoderName.contains(marker, ignoreCase = true)
        }
        Diag.log(
            TAG,
            "video DECODER=$decoderName init=${initializationDurationMs}ms " +
                (if (software) "(软件解码，首帧可能偏慢)" else "(硬件解码)"),
        )
    }
}
