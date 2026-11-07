package com.wonderlandkid.biliaudio.data.impl

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.wonderlandkid.biliaudio.data.model.CoverCandidate
import com.wonderlandkid.biliaudio.data.net.NetEaseCoverApi
import com.wonderlandkid.biliaudio.data.repository.AlbumCoverRepository
import com.wonderlandkid.biliaudio.util.Diag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * 专辑封面仓库实现：
 * - 在线搜索：网易云专辑搜索（[NetEaseCoverApi]）；
 * - 封面文件：解码图片 → 缩放（长边 ≤1080）→ 压缩 JPEG(88) 存到 `files/covers/album-<id>-<ts>.jpg`；
 * - 保存新封面时删除该专辑旧封面文件。
 */
class AlbumCoverRepositoryImpl(
    context: Context,
    private val api: NetEaseCoverApi = NetEaseCoverApi(),
) : AlbumCoverRepository {

    private val appContext = context.applicationContext
    private val coversDir: File = File(appContext.filesDir, "covers")

    override suspend fun searchNetEaseAlbums(query: String, limit: Int): List<CoverCandidate> =
        runCatching { api.searchAlbums(query, limit) }
            .onSuccess { Diag.log(TAG, "netease search q='$query' results=${it.size}") }
            .onFailure { Diag.log(TAG, "netease search failed q='$query'", it) }
            .getOrDefault(emptyList())

    override suspend fun fetchCoverBytes(url: String): ByteArray? =
        runCatching { api.fetchImage(url) }
            .onSuccess { Diag.log(TAG, "cover download bytes=${it?.size ?: 0}") }
            .onFailure { Diag.log(TAG, "cover download failed url=$url", it) }
            .getOrNull()

    override suspend fun saveAlbumCover(albumId: Long, bytes: ByteArray): String? =
        withContext(Dispatchers.IO) {
            if (bytes.isEmpty()) return@withContext null
            val bitmap = runCatching {
                val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            }.getOrNull() ?: run {
                Diag.log(TAG, "save cover decode failed album=$albumId bytes=${bytes.size}")
                return@withContext null
            }

            val scaled = scaleDown(bitmap, MAX_EDGE_PX).also {
                if (it !== bitmap) bitmap.recycle()
            }
            val file = File(coversDir, "album-$albumId-${System.currentTimeMillis()}.jpg")
            runCatching {
                coversDir.mkdirs()
                FileOutputStream(file).use { out ->
                    scaled.compress(Bitmap.CompressFormat.JPEG, 88, out)
                }
            }.onFailure {
                Diag.log(TAG, "save cover write failed album=$albumId", it)
                scaled.recycle()
                return@withContext null
            }
            scaled.recycle()

            // 删除该专辑旧的本地封面（保留刚写入的文件）
            coversDir.listFiles { candidate -> candidate.name.startsWith("album-$albumId-") }
                ?.filter { it.absolutePath != file.absolutePath }
                ?.forEach { runCatching { it.delete() } }

            Diag.log(TAG, "save cover album=$albumId bytes=${file.length()} path=${file.name}")
            file.absolutePath
        }

    override fun deleteAlbumCoverFile(path: String?) {
        val file = path?.takeIf { it.isNotBlank() }?.let(::File) ?: return
        if (file.isFile) runCatching { file.delete() }
    }

    private fun scaleDown(source: Bitmap, maxEdge: Int): Bitmap {
        val edge = maxOf(source.width, source.height)
        if (edge <= maxEdge) return source
        val scale = maxEdge.toFloat() / edge
        val width = (source.width * scale).toInt().coerceAtLeast(1)
        val height = (source.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, width, height, true)
    }

    private companion object {
        const val TAG = "Cover"
        const val MAX_EDGE_PX = 1080
    }
}
