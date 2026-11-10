package com.wonderlandkid.biliaudio.data.impl

import android.content.Context
import com.wonderlandkid.biliaudio.data.model.Album
import com.wonderlandkid.biliaudio.data.model.ResolvedStreams
import com.wonderlandkid.biliaudio.data.model.Track
import com.wonderlandkid.biliaudio.data.repository.AudioCacheRepository
import com.wonderlandkid.biliaudio.data.repository.CachedAlbumAudio
import com.wonderlandkid.biliaudio.data.repository.CachedAudio
import com.wonderlandkid.biliaudio.data.repository.MediaStreamResolver
import com.wonderlandkid.biliaudio.util.Diag
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.Properties
import java.util.concurrent.TimeUnit

/**
 * 音频缓存实现：按分 P 下载 DASH 音频完整文件 + 同目录元数据。
 *
 * 为什么缓存“整段音频”而不是每首歌一个文件：
 * - B 站 album 的一个分 P = 一个视频，分曲只是同一音频流上的时间裁剪；
 * - 一个分 P 下载一次，离线时该 P 的所有分曲都能播放；
 * - 多 P 专辑按 [Track.sourceCid] 逐 P 缓存整张专辑，避免“只缓存首 P”的问题。
 *
 * 注意 URL 会过期：缓存时立即用解析结果下载，落盘后离线播放不再依赖 URL。
 */
class AudioCacheRepositoryImpl(
    context: Context,
    private val streamResolver: MediaStreamResolver,
) : AudioCacheRepository {

    private val dir = File(context.filesDir, "audio_cache").apply { mkdirs() }

    /** 串行化缓存操作：同一专辑重复点击/并发调用不会重复下载同一个分 P。 */
    private val cacheMutex = Mutex()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    override fun getCached(album: Album): CachedAudio? =
        readCached(AudioCacheLogic.keyOf(album.bvid, album.cid), fallbackPage = album.page)

    override fun getCachedSummary(album: Album, tracks: List<Track>): CachedAlbumAudio {
        val targets = AudioCacheLogic.targetPages(album.cid, album.page, tracks)
        val cached = targets.mapNotNull { page ->
            readCachedMeta(AudioCacheLogic.keyOf(album.bvid, page.cid))?.copy(page = page.page)
        }
        return AudioCacheLogic.summarize(
            albumId = album.id,
            bvid = album.bvid,
            targets = targets,
            cached = cached,
        )
    }

    override suspend fun cache(
        album: Album,
        tracks: List<Track>,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ): List<CachedAudio> = withContext(Dispatchers.IO) {
        val targets = AudioCacheLogic.targetPages(album.cid, album.page, tracks)
        if (targets.isEmpty()) throw IllegalStateException("专辑没有可缓存的分 P")
        cacheMutex.withLock {
            val result = mutableListOf<CachedAudio>()
            // 已完成（含原本已存在）分 P 的字节数，用于把进度汇总成整张专辑口径
            var finishedBytes = 0L
            targets.forEachIndexed { index, page ->
                val key = AudioCacheLogic.keyOf(album.bvid, page.cid)
                val existing = readCached(key, fallbackPage = page.page)
                if (existing != null) {
                    finishedBytes += existing.bytes
                    result += existing
                    Diag.log(TAG, "skip exists album=${album.id} cid=${page.cid} page=${page.page}")
                    return@forEachIndexed
                }
                val pageAlbum = album.copy(cid = page.cid, page = page.page)
                val streams = try {
                    streamResolver.resolve(pageAlbum)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (throwable: Throwable) {
                    throw cacheFailure(index, targets.size, result.size, throwable)
                }
                val baseBytes = finishedBytes
                val cached = try {
                    download(key, pageAlbum, streams) { downloaded, total ->
                        onProgress(
                            baseBytes + downloaded,
                            total?.let { baseBytes + it },
                        )
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (throwable: Throwable) {
                    throw cacheFailure(index, targets.size, result.size, throwable)
                }
                finishedBytes += cached.bytes
                result += cached
                // 每个分 P 完成时再报一次，避免 total 已知时分 P 之间进度条看起来“回退”
                onProgress(finishedBytes, finishedBytes)
            }
            result
        }
    }

    override suspend fun listCached(): List<CachedAlbumAudio> = withContext(Dispatchers.IO) {
        // v0.27.0 起不再缓存歌词视频：顺手清理旧版本遗留的视频缓存文件。
        deleteLegacyVideoFiles()
        AudioCacheLogic.groupByAlbum(scanEntries())
    }

    override suspend fun remove(album: Album) = withContext(Dispatchers.IO) {
        val keys = AudioCacheLogic.keysToRemove(
            albumId = album.id,
            bvid = album.bvid,
            cid = album.cid,
            entries = scanEntries(),
        )
        keys.forEach { key -> deleteFiles(key) }
        Unit
    }

    override suspend fun removeCached(item: CachedAlbumAudio) = withContext(Dispatchers.IO) {
        val keys = AudioCacheLogic.keysToRemoveForGroup(item, scanEntries())
        keys.forEach { key -> deleteFiles(key) }
        Unit
    }

    override suspend fun clearAll() = withContext(Dispatchers.IO) {
        dir.listFiles()?.forEach { it.delete() }
        Unit
    }

    // ------------------------------------------------------------------ 内部实现

    /** 下载单个分 P 的音频文件并写元数据；失败清理 .part 后抛出。 */
    private fun download(
        key: String,
        album: Album,
        streams: ResolvedStreams,
        onProgress: (Long, Long?) -> Unit,
    ): CachedAudio {
        val target = File(dir, "$key.m4a")
        val temp = File(dir, "$key.part")
        val request = Request.Builder()
            .url(streams.audioUrl)
            .apply { streams.headers.forEach { (name, value) -> header(name, value) } }
            .build()
        temp.delete()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("音频下载失败：HTTP ${response.code}")
                }
                val body = response.body
                val total = body.contentLength().takeIf { it > 0L }
                var downloaded = 0L
                body.byteStream().use { input ->
                    temp.outputStream().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            onProgress(downloaded, total)
                        }
                    }
                }
                if (downloaded <= 0L) throw IllegalStateException("音频下载失败：文件为空")
                if (total != null && downloaded != total) {
                    throw IllegalStateException("音频下载不完整（$downloaded/$total 字节），请重试")
                }
                if (!temp.renameTo(target)) {
                    temp.copyTo(target, overwrite = true)
                    temp.delete()
                }
                val cachedAt = System.currentTimeMillis()
                writeProperties(key, album, streams, cachedAt)
                Diag.log(
                    TAG,
                    "cached album=${album.id} bvid=${album.bvid} cid=${album.cid} page=${album.page} " +
                        "quality=${streams.audioQualityLabel} bytes=${target.length()}",
                )
                return CachedAudio(
                    albumId = album.id,
                    bvid = album.bvid,
                    cid = album.cid,
                    page = album.page,
                    filePath = target.absolutePath,
                    audioQualityId = streams.audioQualityId,
                    audioQualityLabel = streams.audioQualityLabel,
                    bytes = target.length(),
                    cachedAtMs = cachedAt,
                )
            }
        } catch (throwable: Throwable) {
            temp.delete()
            throw throwable
        }
    }

    private fun cacheFailure(index: Int, total: Int, done: Int, cause: Throwable): Throwable {
        val progress = if (done > 0) "（已缓存 $done/$total 个分P）" else ""
        val reason = cause.message?.takeIf { it.isNotBlank() } ?: "未知错误"
        return IllegalStateException("缓存第 ${index + 1}/$total 个分P失败$progress：$reason", cause)
    }

    private fun writeProperties(
        key: String,
        album: Album,
        streams: ResolvedStreams,
        cachedAtMs: Long,
    ) {
        val props = Properties().apply {
            setProperty("albumId", album.id.toString())
            setProperty("bvid", album.bvid)
            setProperty("cid", album.cid.toString())
            setProperty("page", album.page.toString())
            setProperty("qualityId", streams.audioQualityId.toString())
            setProperty("qualityLabel", streams.audioQualityLabel)
            setProperty("cachedAtMs", cachedAtMs.toString())
        }
        File(dir, "$key.properties").outputStream().use { props.store(it, "BiliAudio audio cache") }
    }

    private fun readCached(key: String, fallbackPage: Int = 0): CachedAudio? {
        val meta = readCachedMeta(key) ?: return null
        return CachedAudio(
            albumId = meta.albumId,
            bvid = meta.bvid,
            cid = meta.cid,
            page = if (meta.page > 0) meta.page else fallbackPage,
            filePath = File(dir, "$key.m4a").absolutePath,
            audioQualityId = meta.audioQualityId,
            audioQualityLabel = meta.audioQualityLabel,
            bytes = meta.bytes,
            cachedAtMs = meta.cachedAtMs,
        )
    }

    private fun readCachedMeta(key: String): CachedAudioMeta? {
        val audio = File(dir, "$key.m4a")
        val meta = File(dir, "$key.properties")
        if (!audio.isFile || !meta.isFile || audio.length() <= 0L) return null
        return runCatching {
            val props = Properties().apply { meta.inputStream().use { load(it) } }
            cachedMetaOf(key, props, audio.length())
        }.getOrNull()
    }

    private fun cachedMetaOf(key: String, props: Properties, bytes: Long): CachedAudioMeta {
        val bvid = props.getProperty("bvid")?.takeIf { it.isNotBlank() }
            ?: key.substringBefore('_')
        val cid = props.getProperty("cid")?.toLongOrNull()
            ?: key.substringAfter('_', "").toLongOrNull()
            ?: 0L
        return CachedAudioMeta(
            key = key,
            albumId = props.getProperty("albumId")?.toLongOrNull() ?: 0L,
            bvid = bvid,
            cid = cid,
            page = props.getProperty("page")?.toIntOrNull() ?: 0,
            audioQualityId = props.getProperty("qualityId")?.toIntOrNull() ?: 0,
            audioQualityLabel = props.getProperty("qualityLabel").orEmpty(),
            bytes = bytes,
            cachedAtMs = props.getProperty("cachedAtMs")?.toLongOrNull() ?: 0L,
        )
    }

    /** 扫描目录里完整（音频文件存在且非空）的音频缓存元数据。 */
    private fun scanEntries(): List<CachedAudioMeta> =
        dir.listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(".properties") }
            .mapNotNull { meta ->
                val key = meta.nameWithoutExtension
                // 旧版歌词视频缓存的 properties（<key>_video.properties）不再属于音频缓存
                if (key.endsWith(VIDEO_SUFFIX)) return@mapNotNull null
                val audio = File(dir, "$key.m4a")
                if (!audio.isFile || audio.length() <= 0L) return@mapNotNull null
                runCatching {
                    val props = Properties().apply { meta.inputStream().use { load(it) } }
                    cachedMetaOf(key, props, audio.length())
                }.getOrNull()
            }

    /** 删除某个 key 对应的全部文件（音频 + 旧版歌词视频遗留文件）。 */
    private fun deleteFiles(key: String) {
        File(dir, "$key.m4a").delete()
        File(dir, "$key.properties").delete()
        File(dir, "$key$VIDEO_SUFFIX.mp4").delete()
        File(dir, "$key$VIDEO_SUFFIX.part").delete()
        File(dir, "$key$VIDEO_SUFFIX.properties").delete()
    }

    /** 清理旧版本（≤v0.26.1）遗留的歌词视频缓存文件。 */
    private fun deleteLegacyVideoFiles() {
        dir.listFiles().orEmpty()
            .filter { it.isFile && it.name.substringBeforeLast('.').endsWith(VIDEO_SUFFIX) }
            .forEach { it.delete() }
    }

    private companion object {
        private const val TAG = "AudioCache"
        private const val DEFAULT_BUFFER_SIZE = 64 * 1024

        /** 旧版歌词视频缓存文件后缀（`<bvid>_<cid>_video.*`）。 */
        private const val VIDEO_SUFFIX = "_video"
    }
}
