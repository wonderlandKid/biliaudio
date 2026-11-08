package com.wonderlandkid.biliaudio.data.impl

import android.content.Context
import com.wonderlandkid.biliaudio.data.model.Album
import com.wonderlandkid.biliaudio.data.model.ResolvedStreams
import com.wonderlandkid.biliaudio.data.repository.AudioCacheRepository
import com.wonderlandkid.biliaudio.data.repository.CachedAudio
import com.wonderlandkid.biliaudio.data.repository.MediaStreamResolver
import com.wonderlandkid.biliaudio.util.Diag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 音频缓存实现：下载 DASH 音频完整文件 + 同目录元数据。
 *
 * 为什么缓存“整段音频”而不是每首歌一个文件：
 * - B 站 album = 一个视频/分 P，分曲只是同一音频流上的时间裁剪；
 * - 一张专辑下载一次，离线时所有分曲都能播放；
 * - 避免 N 次请求、N 份重复音频。
 *
 * 注意 URL 会过期：缓存时立即用解析结果下载，落盘后离线播放不再依赖 URL。
 */
class AudioCacheRepositoryImpl(
    context: Context,
    private val streamResolver: MediaStreamResolver,
) : AudioCacheRepository {

    private val dir = File(context.filesDir, "audio_cache").apply { mkdirs() }
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    override fun getCached(album: Album): CachedAudio? {
        val key = keyOf(album)
        val audio = File(dir, "$key.m4a")
        val meta = File(dir, "$key.properties")
        if (!audio.isFile || !meta.isFile || audio.length() <= 0L) return null
        return runCatching {
            val props = Properties().apply { meta.inputStream().use { load(it) } }
            CachedAudio(
                albumId = props.getProperty("albumId")?.toLongOrNull() ?: album.id,
                bvid = props.getProperty("bvid") ?: album.bvid,
                cid = props.getProperty("cid")?.toLongOrNull() ?: album.cid,
                filePath = audio.absolutePath,
                audioQualityId = props.getProperty("qualityId")?.toIntOrNull() ?: 0,
                audioQualityLabel = props.getProperty("qualityLabel") ?: "",
                bytes = audio.length(),
                cachedAtMs = props.getProperty("cachedAtMs")?.toLongOrNull() ?: 0L,
            )
        }.getOrNull()
    }

    override suspend fun cache(
        album: Album,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ): CachedAudio = withContext(Dispatchers.IO) {
        getCached(album)?.let { return@withContext it }
        val key = keyOf(album)
        val mutex = locks.getOrPut(key) { Mutex() }
        mutex.withLock {
            getCached(album)?.let { return@withLock it }
            val streams = streamResolver.resolve(album)
            download(album, streams, key, onProgress)
        }
    }

    override suspend fun remove(album: Album) = withContext(Dispatchers.IO) {
        val key = keyOf(album)
        File(dir, "$key.m4a").delete()
        File(dir, "$key.properties").delete()
        Unit
    }

    override suspend fun clearAll() = withContext(Dispatchers.IO) {
        dir.listFiles()?.forEach { it.delete() }
        Unit
    }

    private fun download(
        album: Album,
        streams: ResolvedStreams,
        key: String,
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
                val body = response.body ?: throw IllegalStateException("音频下载失败：响应为空")
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
                val props = Properties().apply {
                    setProperty("albumId", album.id.toString())
                    setProperty("bvid", album.bvid)
                    setProperty("cid", album.cid.toString())
                    setProperty("qualityId", streams.audioQualityId.toString())
                    setProperty("qualityLabel", streams.audioQualityLabel)
                    setProperty("cachedAtMs", cachedAt.toString())
                }
                File(dir, "$key.properties").outputStream().use { props.store(it, "BiliAudio audio cache") }
                Diag.log(
                    "AudioCache",
                    "cached album=${album.id} bvid=${album.bvid} cid=${album.cid} " +
                        "quality=${streams.audioQualityLabel} bytes=${target.length()}",
                )
                return CachedAudio(
                    albumId = album.id,
                    bvid = album.bvid,
                    cid = album.cid,
                    filePath = target.absolutePath,
                    audioQualityId = streams.audioQualityId,
                    audioQualityLabel = streams.audioQualityLabel,
                    bytes = target.length(),
                    cachedAtMs = cachedAt,
                )
            }
        } catch (t: Throwable) {
            temp.delete()
            throw t
        }
    }

    private fun keyOf(album: Album): String =
        "${album.bvid}_${album.cid}"

    private companion object {
        const val DEFAULT_BUFFER_SIZE = 64 * 1024
    }
}
