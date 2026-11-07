package com.wonderlandkid.biliaudio.player

import android.content.Context
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.wonderlandkid.biliaudio.data.model.Album
import com.wonderlandkid.biliaudio.data.model.ResolvedStreams
import com.wonderlandkid.biliaudio.data.model.Track
import com.wonderlandkid.biliaudio.data.model.TrackWithAlbum
import com.wonderlandkid.biliaudio.data.repository.AlbumRepository
import com.wonderlandkid.biliaudio.data.repository.MediaStreamResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap

/**
 * 单例播放引擎：全局唯一的 [ExoPlayer] + 专辑分曲队列 + [PlaybackState] 状态流。
 *
 * 关键决策（见 docs/DESIGN.md）：
 * - 队列元素 = 一首歌：`MediaItem(audioUrl)` + `ClippingConfiguration(track.startMs, track.endMs)`；
 * - 拉流数据源 = OkHttp（B 站必须带 UA/Referer/Cookie），请求头按 URL 从解析结果动态追加；
 * - 进度：曲内进度 = `player.currentPosition`（裁剪后从 0 开始），专辑绝对进度 = `track.startMs + 曲内进度`；
 * - 播放出错：强制刷新当前专辑的流并恢复本次曲内位置，**只重试一次**；
 * - 音频焦点 / 耳机拔出由 ExoPlayer 自身处理（`handleAudioFocus = true` + `handleAudioBecomingNoisy`）。
 *
 * 生命周期：由 `AppContainer` 通过 [getOrCreate] 单例懒加载；[PlaybackService] 用同一个 ExoPlayer
 * 构建 MediaSession。UI 只依赖 [PlaybackController] 接口，不直接接触 ExoPlayer。
 */
@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackEngine private constructor(
    context: Context,
    private val scope: CoroutineScope,
    private val albumRepository: AlbumRepository,
    private val streamResolver: MediaStreamResolver,
) : PlaybackController {

    companion object {
        private const val TAG = "PlaybackEngine"

        /** 上一首：曲内进度超过该值先回到本曲开头（音乐播放器通用交互）。 */
        private const val PREVIOUS_RESTART_THRESHOLD_MS = 4_000L

        /** 进度刷新间隔。 */
        private const val PROGRESS_TICK_MS = 250L

        /** 封面字节上限（超过则丢弃，避免把超大图塞进通知）。 */
        private const val MAX_ARTWORK_BYTES = 512 * 1024

        /** 跨专辑队列解析流时的最大并发数，避免触发 B 站风控。 */
        private const val MAX_CONCURRENT_RESOLVES = 4

        @Volatile
        private var instance: PlaybackEngine? = null

        /**
         * 获取（或创建）进程内唯一的播放引擎。
         *
         * AppContainer 装配建议：
         * ```
         * val playbackEngine: PlaybackEngine by lazy {
         *     PlaybackEngine.getOrCreate(appContext, appScope, albumRepository, streamResolver)
         * }
         * val playbackController: PlaybackController get() = playbackEngine
         * ```
         */
        fun getOrCreate(
            context: Context,
            scope: CoroutineScope,
            albumRepository: AlbumRepository,
            streamResolver: MediaStreamResolver,
        ): PlaybackEngine = instance ?: synchronized(this) {
            instance ?: PlaybackEngine(
                context = context.applicationContext,
                scope = scope,
                albumRepository = albumRepository,
                streamResolver = streamResolver,
            ).also { instance = it }
        }

        /** 已创建则返回实例，供 [PlaybackService] 复用同一个 ExoPlayer。 */
        fun peek(): PlaybackEngine? = instance
    }

    private val appContext: Context = context.applicationContext

    /** 拉流 / 预取封面共用的 OkHttp。 */
    internal val httpClient: OkHttpClient = MediaHttp.createClient()

    /** URL → 该流必须携带的请求头（来自 [ResolvedStreams.headers]）。 */
    private val streamHeadersByUrl = ConcurrentHashMap<String, Map<String, String>>()

    /**
     * 音频数据源：OkHttpDataSource + 按 URL 追加请求头（Referer/UA/Cookie）。
     *
     * 之所以不直接给 Factory 设一份全局 defaultRequestProperties：
     * 跨专辑队列里不同专辑的 Cookie 可能不同，按 URL 路由最稳妥。
     */
    private val audioDataSourceFactory: DataSource.Factory = ResolvingDataSource.Factory(
        OkHttpDataSource.Factory(httpClient),
        object : ResolvingDataSource.Resolver {
            override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
                val extraHeaders = streamHeadersByUrl[dataSpec.uri.toString()]
                return if (extraHeaders.isNullOrEmpty()) {
                    dataSpec
                } else {
                    dataSpec.withAdditionalHeaders(extraHeaders)
                }
            }
        },
    )

    private val mediaSourceFactory: DefaultMediaSourceFactory =
        DefaultMediaSourceFactory(appContext).setDataSourceFactory(audioDataSourceFactory)

    private val player: ExoPlayer = ExoPlayer.Builder(appContext)
        .setMediaSourceFactory(mediaSourceFactory)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            // handleAudioFocus = true：ExoPlayer 自动处理音频焦点（来电/其它 App 播放时暂停）
            true,
        )
        // 耳机拔出自动暂停
        .setHandleAudioBecomingNoisy(true)
        // 后台播放需要网络唤醒锁（MANIFEST 已声明 WAKE_LOCK）
        .setWakeMode(C.WAKE_MODE_NETWORK)
        .setSeekBackIncrementMs(5_000L)
        .setSeekForwardIncrementMs(5_000L)
        .build()

    /** 供 [PlaybackService] 构建 MediaSession 使用（同一个 ExoPlayer 实例）。 */
    internal val exoPlayer: ExoPlayer get() = player

    private val _state = MutableStateFlow(PlaybackState())
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()

    /** 与 player 媒体项一一对应的队列（用于进度换算 / 队列面板 / 重试）。 */
    private var queueEntries: List<QueueEntry> = emptyList()

    /** albumId → Album 缓存（重试 / 歌词页需要）。 */
    private val albumCache = mutableMapOf<Long, Album>()

    /** 封面 URL → 字节缓存（通知栏与播放列表封面）。 */
    private val artworkCache = ConcurrentHashMap<String, ByteArray>()

    private var operationJob: Job? = null
    private var progressJob: Job? = null

    /** 当前曲目是否已经因播放错误强制刷新过一次（每条曲目最多一次）。 */
    private var retriedForCurrentItem = false

    /** 最近一次记录“播放中”的曲目，避免同一首歌重复累加播放次数。 */
    private var lastRecordedTrackId: Long? = null

    init {
        player.addListener(PlayerEvents())
        startProgressTicker()
    }

    // ------------------------------------------------------------------ 对外控制

    override fun playAlbum(albumId: Long, startTrackIndex: Int) {
        launchOperation {
            val album = requireAlbum(albumId)
            val storedTracks = albumRepository.getTracks(albumId).sortedBy { it.index }
            // 未分割专辑：逻辑上作为一首歌参与队列，物理上就是整段专辑视频（startMs=0, endMs=duration）
            val tracks = storedTracks.ifEmpty { listOf(wholeAlbumTrack(album)) }
            val entries = PlaybackMapping.buildQueueEntries(album, tracks)
            val albumData = loadAlbumPlaybackData(album)
            val specs = PlaybackMapping.buildPlaySpecs(
                entries = entries,
                audioUrlByAlbumId = mapOf(albumId to albumData.stream.audioUrl),
            )
            startQueue(
                entries = entries,
                specs = specs,
                artworkByAlbumId = mapOf(albumId to albumData.artwork),
                startIndex = startTrackIndex.coerceIn(0, entries.lastIndex),
                startPositionMs = 0L,
            )
        }
    }

    /** 未分割专辑的虚拟整张曲目（不写库，只在播放队列中使用）。 */
    private fun wholeAlbumTrack(album: Album): Track = Track(
        id = -album.id,
        albumId = album.id,
        index = 0,
        title = album.title,
        startMs = 0L,
        endMs = album.durationMs.coerceAtLeast(1L),
    )

    override fun playTrack(albumId: Long, trackId: Long) {
        launchOperation {
            val album = requireAlbum(albumId)
            val storedTracks = albumRepository.getTracks(albumId).sortedBy { it.index }
            // 未分割专辑：逻辑上作为一首歌参与队列，物理上就是整段专辑视频（startMs=0, endMs=duration）
            val tracks = storedTracks.ifEmpty { listOf(wholeAlbumTrack(album)) }
            val entries = PlaybackMapping.buildQueueEntries(album, tracks)
            val targetIndex = entries.indexOfFirst { it.track.id == trackId }
            require(targetIndex >= 0) { "歌曲不存在或已被删除" }
            val albumData = loadAlbumPlaybackData(album)
            val specs = PlaybackMapping.buildPlaySpecs(
                entries = entries,
                audioUrlByAlbumId = mapOf(albumId to albumData.stream.audioUrl),
            )
            startQueue(
                entries = entries,
                specs = specs,
                artworkByAlbumId = mapOf(albumId to albumData.artwork),
                startIndex = targetIndex,
                startPositionMs = 0L,
            )
        }
    }

    override fun playSongs(songs: List<TrackWithAlbum>, startIndex: Int) {
        launchOperation {
            require(songs.isNotEmpty()) { "没有可播放的歌曲" }
            val safeStartIndex = startIndex.coerceIn(0, songs.lastIndex)
            val entries = PlaybackMapping.buildQueueEntries(songs)
            // 搜索结果自带专辑信息，先放进缓存，重试时不必回查数据库
            songs.forEach { albumCache[it.album.id] = it.album }
            val albumIds = entries.map { it.albumId }.distinct()
            val albumDataById = loadAlbumPlaybackData(albumIds)
            val specs = PlaybackMapping.buildPlaySpecs(
                entries = entries,
                audioUrlByAlbumId = albumDataById.mapValues { (_, data) -> data.stream.audioUrl },
            )
            startQueue(
                entries = entries,
                specs = specs,
                artworkByAlbumId = albumDataById.mapValues { (_, data) -> data.artwork },
                startIndex = safeStartIndex,
                startPositionMs = 0L,
            )
        }
    }

    override fun togglePlayPause() {
        if (player.playbackState == Player.STATE_ENDED) {
            player.seekToDefaultPosition(0)
            player.play()
            ensureServiceStarted()
            return
        }
        if (player.playWhenReady) pause() else play()
    }

    override fun play() {
        if (queueEntries.isEmpty()) return
        if (player.playbackState == Player.STATE_ENDED) player.seekToDefaultPosition(0)
        player.play()
        ensureServiceStarted()
    }

    override fun pause() {
        player.pause()
    }

    override fun next() {
        if (player.mediaItemCount == 0) return
        if (player.shuffleModeEnabled) {
            // 随机模式交给播放器按随机顺序前进（会遵循循环模式）
            player.seekToNext()
            return
        }
        val currentIndex = player.currentMediaItemIndex
        val nextIndex = when {
            currentIndex < player.mediaItemCount - 1 -> currentIndex + 1
            player.repeatMode == Player.REPEAT_MODE_ALL -> 0
            else -> C.INDEX_UNSET
        }
        if (nextIndex != C.INDEX_UNSET) {
            player.seekToDefaultPosition(nextIndex)
        } else {
            // 已是队列末尾：回到本曲开头
            player.seekTo(0)
        }
    }

    override fun previous() {
        if (player.mediaItemCount == 0) return
        // 播放超过 4 秒时，上一首先回本曲开头（音乐播放器通用行为）
        if (player.currentPosition > PREVIOUS_RESTART_THRESHOLD_MS) {
            player.seekTo(0)
            return
        }
        if (player.shuffleModeEnabled) {
            player.seekToPrevious()
            return
        }
        val currentIndex = player.currentMediaItemIndex
        val previousIndex = when {
            currentIndex > 0 -> currentIndex - 1
            player.repeatMode == Player.REPEAT_MODE_ALL -> player.mediaItemCount - 1
            else -> C.INDEX_UNSET
        }
        if (previousIndex != C.INDEX_UNSET) {
            player.seekToDefaultPosition(previousIndex)
        } else {
            player.seekTo(0)
        }
    }

    override fun seekToTrackPosition(positionMs: Long) {
        val entry = queueEntries.getOrNull(player.currentMediaItemIndex) ?: return
        player.seekTo(positionMs.coerceIn(0L, entry.durationMs.coerceAtLeast(0L)))
        publishProgress()
    }

    override fun seekToAlbumPosition(albumPositionMs: Long) {
        val currentEntry = queueEntries.getOrNull(player.currentMediaItemIndex) ?: return
        val albumDuration = currentEntry.albumDurationMs.coerceAtLeast(currentEntry.durationMs)
        val target = PlaybackMapping.clampAlbumPosition(albumPositionMs, albumDuration)
        val localPosition = target - currentEntry.track.startMs
        if (localPosition in 0L..currentEntry.durationMs) {
            player.seekTo(localPosition)
        } else {
            // 目标落在本专辑别的曲目上：跳队列 + 曲内定位
            val targetIndex = PlaybackMapping.findQueueIndexAtAlbumPosition(
                entries = queueEntries,
                albumId = currentEntry.albumId,
                albumPositionMs = target,
            )
            if (targetIndex < 0) return
            val targetEntry = queueEntries[targetIndex]
            val targetLocal = (target - targetEntry.track.startMs)
                .coerceIn(0L, targetEntry.durationMs.coerceAtLeast(0L))
            player.seekTo(targetIndex, targetLocal)
        }
        publishProgress()
    }

    override fun jumpToQueueIndex(index: Int) {
        if (index !in queueEntries.indices) return
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        player.seekToDefaultPosition(index)
        player.play()
        ensureServiceStarted()
    }

    override fun setRepeatMode(mode: RepeatMode) {
        player.repeatMode = mode.toPlayerRepeatMode()
    }

    override fun cycleRepeatMode() {
        setRepeatMode(_state.value.repeatMode.nextRepeatMode())
    }

    override fun toggleShuffle() {
        player.shuffleModeEnabled = !player.shuffleModeEnabled
    }

    override fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    override fun syncMediaSession(album: Album, currentTrack: Track) {
        // 通知栏信息来自当前 MediaItem 的 MediaMetadata，切歌时 MediaSession 会自动同步，
        // 这里保留空实现以满足契约（UI 无需调用）。
    }

    // ------------------------------------------------------------------ 队列准备

    /** 启动一次“准备队列”的任务（新的操作会取消上一次尚未完成的准备）。 */
    private fun launchOperation(block: suspend () -> Unit) {
        operationJob?.cancel()
        operationJob = scope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            try {
                block()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                Log.w(TAG, "准备播放失败", error)
                _state.update {
                    it.copy(
                        isLoading = false,
                        isPlaying = player.isPlaying,
                        error = PlayerErrorMessages.forException(error),
                    )
                }
            }
        }
    }

    private suspend fun requireAlbum(albumId: Long): Album {
        albumCache[albumId]?.let { return it }
        val album = albumRepository.getAlbum(albumId)
            ?: throw IllegalStateException("专辑不存在或已被删除")
        albumCache[albumId] = album
        return album
    }

    /** 解析专辑音频流并预取封面（并行，封面失败不影响播放）。 */
    private suspend fun loadAlbumPlaybackData(album: Album): AlbumPlaybackData = coroutineScope {
        val streamDeferred = async { resolveStream(album) }
        val artworkDeferred = async { loadArtwork(album.coverUrl) }
        AlbumPlaybackData(
            album = album,
            stream = streamDeferred.await(),
            artwork = artworkDeferred.await(),
        )
    }

    /** 批量解析（跨专辑队列；限制并发度，避免 B 站风控）。 */
    private suspend fun loadAlbumPlaybackData(albumIds: List<Long>): Map<Long, AlbumPlaybackData> =
        coroutineScope {
            val result = mutableMapOf<Long, AlbumPlaybackData>()
            albumIds.chunked(MAX_CONCURRENT_RESOLVES).forEach { chunk ->
                val loaded = chunk.map { albumId ->
                    async {
                        val album = requireAlbum(albumId)
                        albumId to loadAlbumPlaybackData(album)
                    }
                }.awaitAll()
                loaded.forEach { (albumId, data) -> result[albumId] = data }
            }
            result
        }

    private suspend fun resolveStream(album: Album, forceRefresh: Boolean = false): ResolvedStreams {
        val stream = streamResolver.resolve(album, forceRefresh)
        if (stream.audioUrl.isBlank()) {
            throw IllegalStateException(PlayerErrorMessages.AUDIO_STREAM_UNAVAILABLE)
        }
        albumCache[album.id] = album
        // 记录该流需要的请求头（音频；视频地址留给歌词页自己的播放器使用）
        streamHeadersByUrl[stream.audioUrl] = MediaHttp.mergeHeaders(stream.headers)
        return stream
    }

    /** 用 OkHttp 预取封面字节：通知栏 / 锁屏用 artworkData，避免图床缺 Referer 403。 */
    private suspend fun loadArtwork(coverUrl: String): ByteArray? {
        if (coverUrl.isBlank()) return null
        artworkCache[coverUrl]?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder().url(coverUrl).build()
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val body = response.body
                    val bytes = body.bytes()
                    if (bytes.isEmpty() || bytes.size > MAX_ARTWORK_BYTES) {
                        null
                    } else {
                        artworkCache[coverUrl] = bytes
                        bytes
                    }
                }
            }.getOrNull()
        }
    }

    /** 把队列真正交给 ExoPlayer 并开始播放。 */
    private fun startQueue(
        entries: List<QueueEntry>,
        specs: List<TrackPlaySpec>,
        artworkByAlbumId: Map<Long, ByteArray?>,
        startIndex: Int,
        startPositionMs: Long,
    ) {
        require(specs.size == entries.size) { "队列构建失败，请重试" }
        val items = specs.map { spec ->
            PlaybackMapping.toMediaItem(spec = spec, artworkData = artworkByAlbumId[spec.albumId])
        }
        val safeIndex = startIndex.coerceIn(0, items.lastIndex)
        val safePosition = startPositionMs.coerceAtLeast(0L)

        queueEntries = entries
        retriedForCurrentItem = false
        lastRecordedTrackId = null
        player.setMediaItems(items, safeIndex, safePosition)
        player.prepare()
        player.play()

        val entry = entries[safeIndex]
        _state.value = PlaybackState(
            currentTrack = entry.track,
            currentAlbumId = entry.albumId,
            queue = entries,
            currentIndex = safeIndex,
            isPlaying = true,
            isLoading = true,
            positionMs = safePosition,
            trackDurationMs = entry.durationMs,
            albumPositionMs = entry.track.startMs + safePosition,
            albumDurationMs = entry.albumDurationMs,
            repeatMode = player.repeatMode.toAppRepeatMode(),
            shuffle = player.shuffleModeEnabled,
        )
        recordPlayed(entry)
        ensureServiceStarted()
    }

    // ------------------------------------------------------------------ 状态同步

    private fun startProgressTicker() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive) {
                if (player.isPlaying) publishProgress()
                delay(PROGRESS_TICK_MS)
            }
        }
    }

    private fun publishProgress() {
        val entry = queueEntries.getOrNull(player.currentMediaItemIndex) ?: return
        val position = player.currentPosition.coerceIn(0L, entry.durationMs.coerceAtLeast(0L))
        _state.update {
            it.copy(
                positionMs = position,
                trackDurationMs = entry.durationMs,
                albumPositionMs = PlaybackMapping.trackPositionToAlbumPosition(entry.track, position),
                albumDurationMs = entry.albumDurationMs,
            )
        }
    }

    private fun publishCurrentItem() {
        val index = player.currentMediaItemIndex
        val entry = queueEntries.getOrNull(index) ?: return
        val position = player.currentPosition.coerceIn(0L, entry.durationMs.coerceAtLeast(0L))
        _state.update {
            it.copy(
                currentTrack = entry.track,
                currentAlbumId = entry.albumId,
                currentIndex = index,
                queue = queueEntries,
                positionMs = position,
                trackDurationMs = entry.durationMs,
                albumPositionMs = PlaybackMapping.trackPositionToAlbumPosition(entry.track, position),
                albumDurationMs = entry.albumDurationMs,
            )
        }
    }

    /** 记录最近播放（同一首歌在一次会话内只记一次，避免重复累加）。 */
    private fun recordPlayed(entry: QueueEntry?) {
        if (entry == null || entry.track.id == lastRecordedTrackId) return
        lastRecordedTrackId = entry.track.id
        scope.launch {
            runCatching { albumRepository.markPlayed(entry.albumId, entry.track.id) }
                .onFailure { Log.w(TAG, "记录最近播放失败", it) }
        }
    }

    private fun ensureServiceStarted() {
        runCatching { PlaybackService.ensureStarted(appContext) }
            .onFailure { Log.w(TAG, "拉起播放服务失败", it) }
    }

    // ------------------------------------------------------------------ 错误与重试

    private fun onPlaybackError(error: PlaybackException) {
        Log.w(TAG, "播放出错：${error.errorCodeName}(${error.errorCode})", error)
        if (!retriedForCurrentItem && queueEntries.isNotEmpty()) {
            // 地址过期最常见：强制刷新当前专辑的流，恢复本次曲内位置后继续播放（仅一次）
            retriedForCurrentItem = true
            _state.update { it.copy(isLoading = true, error = null) }
            retryCurrentAlbum()
            return
        }
        _state.update {
            it.copy(
                isLoading = false,
                isPlaying = false,
                error = PlayerErrorMessages.forPlaybackErrorCode(error.errorCode),
            )
        }
    }

    /** 强制刷新当前专辑的流并重建该专辑的媒体项，恢复到出错时的曲内位置。 */
    private fun retryCurrentAlbum() {
        val snapshot = _state.value
        val albumId = snapshot.currentAlbumId ?: return
        val resumeIndex = snapshot.currentIndex
        val resumePositionMs = snapshot.positionMs
        operationJob?.cancel()
        operationJob = scope.launch {
            try {
                val album = albumCache[albumId]
                    ?: albumRepository.getAlbum(albumId)
                    ?: throw IllegalStateException("专辑不存在或已被删除")
                val stream = resolveStream(album, forceRefresh = true)
                val artwork = loadArtwork(album.coverUrl)
                // 只替换当前专辑的媒体项，其它专辑的队列保持不变
                val indices = queueEntries.indices.filter { queueEntries[it].albumId == albumId }
                if (indices.isNotEmpty()) {
                    val specs = PlaybackMapping.buildPlaySpecs(
                        entries = indices.map { queueEntries[it] },
                        audioUrlByAlbumId = mapOf(albumId to stream.audioUrl),
                    )
                    indices.forEachIndexed { specIndex, queueIndex ->
                        player.replaceMediaItem(
                            queueIndex,
                            PlaybackMapping.toMediaItem(specs[specIndex], artwork),
                        )
                    }
                }
                if (resumeIndex in queueEntries.indices) {
                    player.seekTo(resumeIndex, resumePositionMs.coerceAtLeast(0L))
                }
                player.prepare()
                player.play()
                _state.update { it.copy(isLoading = true, error = null) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                Log.w(TAG, "强制刷新播放流失败", error)
                _state.update {
                    it.copy(
                        isLoading = false,
                        isPlaying = false,
                        error = PlayerErrorMessages.AUDIO_STREAM_UNAVAILABLE,
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------------ 播放器事件

    private inner class PlayerEvents : Player.Listener {

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            retriedForCurrentItem = false
            publishCurrentItem()
            if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) {
                recordPlayed(queueEntries.getOrNull(player.currentMediaItemIndex))
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.update { it.copy(isPlaying = isPlaying) }
            if (isPlaying) ensureServiceStarted() else publishProgress()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            _state.update {
                it.copy(
                    isLoading = playbackState == Player.STATE_BUFFERING,
                    isPlaying = player.isPlaying,
                )
            }
            if (playbackState == Player.STATE_READY) publishProgress()
        }

        override fun onIsLoadingChanged(isLoading: Boolean) {
            _state.update { it.copy(isLoading = isLoading) }
        }

        override fun onPlayerError(error: PlaybackException) {
            onPlaybackError(error)
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            _state.update { it.copy(repeatMode = repeatMode.toAppRepeatMode()) }
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            _state.update { it.copy(shuffle = shuffleModeEnabled) }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            publishProgress()
        }
    }

    /** 一张专辑的播放准备数据。 */
    private data class AlbumPlaybackData(
        val album: Album,
        val stream: ResolvedStreams,
        val artwork: ByteArray?,
    )
}

// ----------------------------------------------------------------------------
// 枚举 / 常量转换（文件级私有，避免污染公开 API）
// ----------------------------------------------------------------------------

private fun RepeatMode.toPlayerRepeatMode(): Int = when (this) {
    RepeatMode.OFF -> Player.REPEAT_MODE_OFF
    RepeatMode.ALL -> Player.REPEAT_MODE_ALL
    RepeatMode.ONE -> Player.REPEAT_MODE_ONE
}

private fun Int.toAppRepeatMode(): RepeatMode = when (this) {
    Player.REPEAT_MODE_ALL -> RepeatMode.ALL
    Player.REPEAT_MODE_ONE -> RepeatMode.ONE
    else -> RepeatMode.OFF
}

private fun RepeatMode.nextRepeatMode(): RepeatMode = when (this) {
    RepeatMode.OFF -> RepeatMode.ALL
    RepeatMode.ALL -> RepeatMode.ONE
    RepeatMode.ONE -> RepeatMode.OFF
}
