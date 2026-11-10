package com.wonderlandkid.biliaudio.player

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.wonderlandkid.biliaudio.data.model.Album
import com.wonderlandkid.biliaudio.data.model.ResolvedStreams
import com.wonderlandkid.biliaudio.data.model.Track
import com.wonderlandkid.biliaudio.data.model.TrackWithAlbum
import com.wonderlandkid.biliaudio.data.repository.AlbumRepository
import com.wonderlandkid.biliaudio.data.repository.AudioCacheRepository
import com.wonderlandkid.biliaudio.data.repository.MediaStreamResolver
import com.wonderlandkid.biliaudio.data.repository.PlaybackStatsRepository
import com.wonderlandkid.biliaudio.util.Diag
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
    private val audioCacheRepository: AudioCacheRepository,
    private val statsRepository: PlaybackStatsRepository,
) : PlaybackController {

    companion object {
        private const val TAG = "PlaybackEngine"

        /** 上一首：曲内进度超过该值先回到本曲开头（音乐播放器通用交互）。 */
        private const val PREVIOUS_RESTART_THRESHOLD_MS = 4_000L

        /** 进度刷新间隔。 */
        private const val PROGRESS_TICK_MS = 250L

        /** 收听时长先内存累计，每累计约 5 秒落库一次，避免每 250ms 写 DB。 */
        private const val STATS_FLUSH_MS = 5_000L

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
            audioCacheRepository: AudioCacheRepository,
            statsRepository: PlaybackStatsRepository,
        ): PlaybackEngine = instance ?: synchronized(this) {
            instance ?: PlaybackEngine(
                context = context.applicationContext,
                scope = scope,
                albumRepository = albumRepository,
                streamResolver = streamResolver,
                audioCacheRepository = audioCacheRepository,
                statsRepository = statsRepository,
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
    private val httpDataSourceFactory = OkHttpDataSource.Factory(httpClient)

    private val audioDataSourceFactory: DataSource.Factory = ResolvingDataSource.Factory(
        // DefaultDataSource 同时支持 http(s)（在线）和 file://（离线缓存）；
        // 在线请求仍由 OkHttp 发，headers 由下面的 Resolver 按 URL 补。
        DefaultDataSource.Factory(appContext, httpDataSourceFactory),
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
        // 多 P 队列：不暂停在单个 MediaItem 末尾，由 ExoPlayer 直接预加载下一分 P
        .setPauseAtEndOfMediaItems(false)
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

    // ---- 收听时长统计（内存累计，定期落库） ----
    private var statsTrackUid: String? = null
    private var statsAlbumId: Long? = null
    private var statsPendingMs = 0L
    private var statsLastTickMs = 0L

    // ---- 诊断计时（Diag tag = "Play"，避免噪音：每首歌切换最多 2~3 行） ----

    /** 当前曲目开始时间（切歌时重置）。 */
    private var itemStartedAtMs = 0L

    /** 当前曲目是否已记录过音频 READY。 */
    private var itemReadyLogged = false

    /** 前台播放服务是否已拉起过（避免重复日志）。 */
    private var serviceStartLogged = false

    init {
        player.addListener(PlayerEvents())
        startProgressTicker()
    }

    // ------------------------------------------------------------------ 对外控制

    override fun playAlbum(albumId: Long, startTrackIndex: Int) {
        launchOperation {
            Diag.log(TAG, "playAlbum album=$albumId startIndex=$startTrackIndex")
            // 重新播放时从 DB 取最新专辑（换封面后控制中心 artwork 要跟着变）
            albumCache.remove(albumId)
            val album = requireAlbum(albumId)
            val storedTracks = albumRepository.getTracks(albumId).sortedBy { it.index }
            // 未分割专辑：逻辑上作为一首歌参与队列，物理上就是整段专辑音频流（startMs=0, endMs=duration）
            val tracks = storedTracks.ifEmpty { listOf(wholeAlbumTrack(album)) }
            val entries = PlaybackMapping.buildQueueEntries(album, tracks)
            val audioUrls = resolveAudioUrls(entries = entries, forceRefresh = false)
            val specs = PlaybackMapping.buildPlaySpecsForTracks(entries, audioUrls)
            startQueue(
                entries = entries,
                specs = specs,
                artworkByAlbumId = mapOf(albumId to loadArtwork(album)),
                startIndex = startTrackIndex.coerceIn(0, entries.lastIndex),
                startPositionMs = 0L,
            )
        }
    }

    /** 未分割专辑的虚拟整张曲目（不写库，只在播放队列中使用）。 */
    private fun wholeAlbumTrack(album: Album): Track = Track(
        id = -album.id,
        uid = "album:${album.id}",
        albumId = album.id,
        index = 0,
        title = album.title,
        startMs = 0L,
        endMs = album.durationMs.coerceAtLeast(1L),
    )

    override fun playTrack(albumId: Long, trackId: Long) {
        launchOperation {
            Diag.log(TAG, "playTrack album=$albumId track=$trackId")
            albumCache.remove(albumId)
            val album = requireAlbum(albumId)
            val storedTracks = albumRepository.getTracks(albumId).sortedBy { it.index }
            // 未分割专辑：逻辑上作为一首歌参与队列，物理上就是整段专辑音频流（startMs=0, endMs=duration）
            val tracks = storedTracks.ifEmpty { listOf(wholeAlbumTrack(album)) }
            val entries = PlaybackMapping.buildQueueEntries(album, tracks)
            val targetIndex = entries.indexOfFirst { it.track.id == trackId }
            require(targetIndex >= 0) { "歌曲不存在或已被删除" }
            val audioUrls = resolveAudioUrls(entries = entries, forceRefresh = false)
            val specs = PlaybackMapping.buildPlaySpecsForTracks(entries, audioUrls)
            startQueue(
                entries = entries,
                specs = specs,
                artworkByAlbumId = mapOf(albumId to loadArtwork(album)),
                startIndex = targetIndex,
                startPositionMs = 0L,
            )
        }
    }

    override fun playSongs(songs: List<TrackWithAlbum>, startIndex: Int) {
        launchOperation {
            Diag.log(
                TAG,
                "playSongs songs=${songs.size} startIndex=$startIndex " +
                    "albums=${songs.map { it.album.id }.distinct().size}",
            )
            require(songs.isNotEmpty()) { "没有可播放的歌曲" }
            val safeStartIndex = startIndex.coerceIn(0, songs.lastIndex)
            val entries = PlaybackMapping.buildQueueEntries(songs)
            // 搜索结果自带专辑信息，先放进缓存，重试时不必回查数据库
            songs.forEach { albumCache[it.album.id] = it.album }
            val audioUrls = resolveAudioUrls(entries = entries, forceRefresh = false)
            val specs = PlaybackMapping.buildPlaySpecsForTracks(entries, audioUrls)
            startQueue(
                entries = entries,
                specs = specs,
                artworkByAlbumId = loadArtworkByAlbum(entries),
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

    /**
     * 解析本次队列每一首曲目的音频地址。
     *
     * - 普通曲目：共用所属专辑的音频流；
     * - 多 P 曲目：按 sourceCid 解析各自分 P 的音频流；
     * - 所有地址在 [startQueue] 前全部解析完成，随后一次性交给 ExoPlayer，
     *   这样切歌不需要在播放中等待网络解析，才能做到自动无缝衔接。
     */
    private suspend fun resolveAudioUrls(
        entries: List<QueueEntry>,
        forceRefresh: Boolean,
    ): Map<String, String> = coroutineScope {
        val albumsById = mutableMapOf<Long, Album>()
        entries.forEach { entry ->
            albumCache[entry.albumId]?.let { albumsById[entry.albumId] = it }
        }
        for (entry in entries) {
            if (albumsById.containsKey(entry.albumId)) continue
            albumsById[entry.albumId] = requireAlbum(entry.albumId)
        }

        data class Request(val album: Album, val cid: Long, val page: Int, val cacheAlbum: Boolean)

        val requests = mutableMapOf<Pair<Long, Long>, Request>()
        for (entry in entries) {
            val album = albumsById.getValue(entry.albumId)
            val track = entry.track
            if (track.hasOwnSource) {
                val key = album.id to track.sourceCid
                requests.putIfAbsent(
                    key,
                    Request(
                        album = album.copy(cid = track.sourceCid, page = track.sourcePage),
                        cid = track.sourceCid,
                        page = track.sourcePage,
                        cacheAlbum = false,
                    ),
                )
            } else {
                val key = album.id to album.cid
                requests.putIfAbsent(
                    key,
                    Request(album = album, cid = album.cid, page = album.page, cacheAlbum = true),
                )
            }
        }

        val urlBySource = mutableMapOf<Pair<Long, Long>, String>()
        requests.entries.chunked(MAX_CONCURRENT_RESOLVES).forEach { chunk ->
            val resolved = chunk.map { (key, request) ->
                async {
                    val stream = resolveStream(request.album, forceRefresh, request.cacheAlbum)
                    key to stream.audioUrl
                }
            }.awaitAll()
            resolved.forEach { (key, url) -> urlBySource[key] = url }
        }

        val urls = entries.associate { entry ->
            val track = entry.track
            val key = entry.albumId to if (track.hasOwnSource) track.sourceCid else albumsById.getValue(entry.albumId).cid
            entry.track.uid to (urlBySource[key] ?: throw IllegalStateException("音频流获取失败，请重试"))
        }
        Diag.log(
            TAG,
            "resolveAudioUrls tracks=${entries.size} sources=${urlBySource.size} " +
                "multiPageTracks=${entries.count { it.track.hasOwnSource }} force=$forceRefresh",
        )
        urls
    }

    private suspend fun loadArtworkByAlbum(entries: List<QueueEntry>): Map<Long, ByteArray?> =
        coroutineScope {
            val albums = mutableMapOf<Long, Album>()
            entries.forEach { entry -> albumCache[entry.albumId]?.let { albums[entry.albumId] = it } }
            for (entry in entries) {
                if (albums.containsKey(entry.albumId)) continue
                albums[entry.albumId] = requireAlbum(entry.albumId)
            }
            albums.map { (albumId, album) ->
                async { albumId to loadArtwork(album) }
            }.awaitAll().toMap()
        }

    private suspend fun resolveStream(
        album: Album,
        forceRefresh: Boolean = false,
        cacheAlbum: Boolean = true,
    ): ResolvedStreams {
        val startMs = System.currentTimeMillis()
        // 有完整音频缓存时直接播放本地文件：离线可用，也不再消耗流量。
        // 出错重试（forceRefresh）时不走缓存，避免损坏的缓存导致死循环。
        if (!forceRefresh) {
            audioCacheRepository.getCached(album)?.let { cached ->
                val fileUri = android.net.Uri.fromFile(java.io.File(cached.filePath)).toString()
                if (cacheAlbum) albumCache[album.id] = album
                Diag.log(
                    TAG,
                    "resolve CACHE HIT album=${album.id} file=${cached.filePath} " +
                        "quality=${cached.audioQualityLabel} bytes=${cached.bytes}",
                )
                return ResolvedStreams(
                    audioUrl = fileUri,
                    audioQualityId = cached.audioQualityId,
                    audioQualityLabel = cached.audioQualityLabel,
                    expiresAtEpochMs = Long.MAX_VALUE,
                    headers = emptyMap(),
                )
            }
        }
        val stream = streamResolver.resolve(album, forceRefresh)
        val costMs = System.currentTimeMillis() - startMs
        if (stream.audioUrl.isBlank()) {
            throw IllegalStateException(PlayerErrorMessages.AUDIO_STREAM_UNAVAILABLE)
        }
        if (cacheAlbum) albumCache[album.id] = album
        // 记录该流需要的请求头（音频流）
        streamHeadersByUrl[stream.audioUrl] = MediaHttp.mergeHeaders(stream.headers)
        Diag.log(
            TAG,
            "resolve album=${album.id} in ${costMs}ms audio=${stream.audioQualityLabel}" +
                "(${stream.audioQualityId}) force=$forceRefresh",
        )
        return stream
    }

    /**
     * 取专辑封面字节给媒体通知/锁屏（控制中心）。
     *
     * 优先用户自定义封面（本地文件，能立即反映换封面）；否则回退 B 站远程封面。
     * 远程图床需要 UA/Referer，所以这里用 OkHttp 预取再塞 artworkData。
     */
    private suspend fun loadArtwork(album: Album): ByteArray? {
        val localPath = album.coverLocalPath
        if (!localPath.isNullOrBlank()) {
            val local = withContext(Dispatchers.IO) {
                runCatching {
                    val file = java.io.File(localPath)
                    if (file.isFile && file.length() in 1..MAX_ARTWORK_BYTES.toLong()) file.readBytes() else null
                }.getOrNull()
            }
            if (local != null) return local
        }
        return loadRemoteArtwork(album.coverUrl)
    }

    private suspend fun loadRemoteArtwork(coverUrl: String): ByteArray? {
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

        flushListeningStats()
        statsTrackUid = null
        statsAlbumId = null
        statsPendingMs = 0L
        statsLastTickMs = 0L
        queueEntries = entries
        retriedForCurrentItem = false
        lastRecordedTrackId = null
        itemStartedAtMs = System.currentTimeMillis()
        itemReadyLogged = false
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
        Diag.log(
            TAG,
            "startQueue album=${entry.albumId} tracks=${entries.size} startIndex=$safeIndex " +
                "startPos=${safePosition}ms albumMs=${entry.albumDurationMs} clips=${clipSummary(specs)}",
        )
        recordPlayed(entry)
        ensureServiceStarted()
    }

    // ------------------------------------------------------------------ 状态同步

    private fun startProgressTicker() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive) {
                if (player.isPlaying) {
                    publishProgress()
                    accumulateListeningStats()
                } else {
                    flushListeningStats()
                    statsLastTickMs = 0L
                }
                delay(PROGRESS_TICK_MS)
            }
        }
    }

    /** 按真实经过时间累计当前曲目收听时长；每 5 秒落库一次。 */
    private fun accumulateListeningStats() {
        val entry = queueEntries.getOrNull(player.currentMediaItemIndex) ?: return
        val uid = statsUidOf(entry)
        val now = SystemClock.elapsedRealtime()
        val last = statsLastTickMs
        if (statsTrackUid != uid || statsAlbumId != entry.albumId) {
            flushListeningStats()
            statsTrackUid = uid
            statsAlbumId = entry.albumId
            statsPendingMs = 0L
        }
        statsLastTickMs = now
        if (last <= 0L) return
        val delta = (now - last).coerceIn(0L, PROGRESS_TICK_MS * 4)
        statsPendingMs += delta
        if (statsPendingMs >= STATS_FLUSH_MS) flushListeningStats()
    }

    /** 把内存里累计的收听时长异步落库；清空 pending。 */
    private fun flushListeningStats() {
        val uid = statsTrackUid ?: return
        val albumId = statsAlbumId ?: return
        val delta = statsPendingMs
        statsPendingMs = 0L
        if (delta <= 0L) return
        scope.launch {
            runCatching { statsRepository.addListeningTime(albumId, uid, delta) }
                .onFailure { Log.w(TAG, "写入收听时长失败", it) }
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
        val trackUid = statsUidOf(entry)
        scope.launch {
            runCatching {
                albumRepository.markPlayed(entry.albumId, entry.track.id)
                statsRepository.recordTrackStarted(entry.albumId, trackUid)
            }.onFailure { Log.w(TAG, "记录播放统计失败", it) }
        }
    }

    /** 统计用的稳定 uid：未分割专辑的虚拟曲目用 album:<id>。 */
    private fun statsUidOf(entry: QueueEntry): String =
        entry.track.uid.ifBlank { "album:${entry.albumId}" }

    private fun ensureServiceStarted() {
        if (!serviceStartLogged) {
            serviceStartLogged = true
            Diag.log(TAG, "startForegroundService → PlaybackService（开始播放/后台播放）")
        }
        runCatching { PlaybackService.ensureStarted(appContext) }
            .onFailure {
                Diag.log(TAG, "startForegroundService FAILED: ${it.javaClass.simpleName}: ${it.message}", it)
                Log.w(TAG, "拉起播放服务失败", it)
            }
    }

    // ------------------------------------------------------------------ 错误与重试

    private fun onPlaybackError(error: PlaybackException) {
        Log.w(TAG, "播放出错：${error.errorCodeName}(${error.errorCode})", error)
        Diag.log(
            TAG,
            "player ERROR code=${error.errorCode} name=${error.errorCodeName} " +
                "index=${_state.value.currentIndex} retried=$retriedForCurrentItem: ${error.message}",
            error,
        )
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
        val retryStartedAtMs = System.currentTimeMillis()
        Diag.log(
            TAG,
            "forceRefresh retry START album=$albumId resumeIndex=$resumeIndex resumePos=${resumePositionMs}ms",
        )
        operationJob?.cancel()
        operationJob = scope.launch {
            try {
                val album = albumCache[albumId]
                    ?: albumRepository.getAlbum(albumId)
                    ?: throw IllegalStateException("专辑不存在或已被删除")
                val artwork = loadArtwork(album)
                // 只替换当前专辑的媒体项，其它专辑的队列保持不变
                val indices = queueEntries.indices.filter { queueEntries[it].albumId == albumId }
                if (indices.isNotEmpty()) {
                    val retryEntries = indices.map { queueEntries[it] }
                    val audioUrls = resolveAudioUrls(entries = retryEntries, forceRefresh = true)
                    val specs = PlaybackMapping.buildPlaySpecsForTracks(retryEntries, audioUrls)
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
                itemStartedAtMs = System.currentTimeMillis()
                itemReadyLogged = false
                _state.update { it.copy(isLoading = true, error = null) }
                Diag.log(
                    TAG,
                    "forceRefresh retry DONE album=$albumId in ${System.currentTimeMillis() - retryStartedAtMs}ms",
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                Log.w(TAG, "强制刷新播放流失败", error)
                Diag.log(
                    TAG,
                    "forceRefresh retry FAILED album=$albumId in ${System.currentTimeMillis() - retryStartedAtMs}ms: " +
                        "${error.javaClass.simpleName}: ${error.message}",
                    error,
                )
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
            flushListeningStats()
            statsTrackUid = null
            statsAlbumId = null
            statsLastTickMs = 0L
            retriedForCurrentItem = false
            itemStartedAtMs = System.currentTimeMillis()
            itemReadyLogged = false
            publishCurrentItem()
            if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) {
                recordPlayed(queueEntries.getOrNull(player.currentMediaItemIndex))
                // 播放列表首次装载已由 startQueue 记录，这里只记录真正的切歌（自动/手动/跳转）
                if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) {
                    val index = player.currentMediaItemIndex
                    val entry = queueEntries.getOrNull(index)
                    Diag.log(
                        TAG,
                        "transition index=$index trackId=${entry?.track?.id} " +
                            "clip=${entry?.track?.startMs}-${entry?.track?.endMs} reason=$reason",
                    )
                }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.update { it.copy(isPlaying = isPlaying) }
            if (isPlaying) {
                ensureServiceStarted()
                statsLastTickMs = 0L
            } else {
                flushListeningStats()
                statsLastTickMs = 0L
                publishProgress()
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            _state.update {
                it.copy(
                    isLoading = playbackState == Player.STATE_BUFFERING,
                    isPlaying = player.isPlaying,
                )
            }
            if (playbackState == Player.STATE_READY) {
                publishProgress()
                if (!itemReadyLogged) {
                    itemReadyLogged = true
                    Diag.log(
                        TAG,
                        "audio READY index=${player.currentMediaItemIndex} " +
                            "in ${System.currentTimeMillis() - itemStartedAtMs}ms",
                    )
                }
            }
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

/** 诊断用：把队列的分曲裁剪区间压成一行（最多展示 6 个，避免长专辑刷屏）。 */
private fun clipSummary(specs: List<TrackPlaySpec>, maxItems: Int = 6): String {
    if (specs.isEmpty()) return "[]"
    val shown = specs.take(maxItems).joinToString(",") { spec -> "${spec.clipStartMs}-${spec.clipEndMs}" }
    return if (specs.size > maxItems) "[$shown,…+${specs.size - maxItems}]" else "[$shown]"
}
