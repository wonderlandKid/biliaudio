package com.wonderlandkid.biliaudio.data.impl

import androidx.room.withTransaction
import com.wonderlandkid.biliaudio.data.db.AlbumEntity
import com.wonderlandkid.biliaudio.data.db.AppDatabase
import com.wonderlandkid.biliaudio.data.db.toEntity
import com.wonderlandkid.biliaudio.data.db.toModel
import com.wonderlandkid.biliaudio.data.model.Album
import com.wonderlandkid.biliaudio.data.model.AlbumImportRequest
import com.wonderlandkid.biliaudio.data.model.AlbumWithTracks
import com.wonderlandkid.biliaudio.data.model.BatchImportReport
import com.wonderlandkid.biliaudio.data.model.BiliApiException
import com.wonderlandkid.biliaudio.data.model.ImportMode
import com.wonderlandkid.biliaudio.data.model.ImportResult
import com.wonderlandkid.biliaudio.data.model.SegmentSpec
import com.wonderlandkid.biliaudio.data.model.Track
import com.wonderlandkid.biliaudio.data.model.TrackWithAlbum
import com.wonderlandkid.biliaudio.data.repository.AlbumRepository
import com.wonderlandkid.biliaudio.data.repository.BiliRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * 专辑 / 曲目库：Room 持久化 + 单条/批量导入。
 *
 * 装配：`AlbumRepositoryImpl(database = AppDatabase.getInstance(context), biliRepository = biliRepository)`。
 *
 * 导入行为（与 docs/IMPORT_FORMATS.md 一致）：
 * - 同一 bvid 已存在时**覆盖更新**：保留原 id / createdAt / lastPlayedAt / playCount，替换元信息与分曲；
 * - 单条失败不影响其他条目（批量导入逐条收集 [ImportResult]）；
 * - 多 P 视频自动分 P 暂不支持（契约 `Track` 无 cid 字段，MVP 仅支持单 P，按 DESIGN.md 约定，
 *   用户可用单视频模式导入后手动分曲）。
 */
class AlbumRepositoryImpl(
    private val database: AppDatabase,
    private val biliRepository: BiliRepository,
    private val parser: BatchImportParser = BatchImportParser(),
) : AlbumRepository {

    private val albumDao = database.albumDao()
    private val trackDao = database.trackDao()

    override val albums: Flow<List<Album>> =
        albumDao.observeAll().map { entities -> entities.map(AlbumEntity::toModel) }

    override val songs: Flow<List<TrackWithAlbum>> =
        combine(albumDao.observeAll(), trackDao.observeAll()) { albumEntities, trackEntities ->
            val albumsById = albumEntities.associateBy { it.id }
            val albumOrder = albumEntities.mapIndexed { index, entity -> entity.id to index }.toMap()
            trackEntities
                .sortedWith(compareBy({ albumOrder[it.albumId] ?: Int.MAX_VALUE }, { it.index }))
                .mapNotNull { trackEntity ->
                    albumsById[trackEntity.albumId]?.let { albumEntity ->
                        TrackWithAlbum(trackEntity.toModel(), albumEntity.toModel())
                    }
                }
        }

    override val recentAlbums: Flow<List<Album>> =
        albumDao.observeRecent().map { entities -> entities.map(AlbumEntity::toModel) }

    override fun albumWithTracks(albumId: Long): Flow<AlbumWithTracks?> =
        combine(albumDao.observeById(albumId), trackDao.observeByAlbum(albumId)) { albumEntity, trackEntities ->
            albumEntity?.let { AlbumWithTracks(it.toModel(), trackEntities.map { track -> track.toModel() }) }
        }

    override suspend fun getAlbum(albumId: Long): Album? = albumDao.getById(albumId)?.toModel()

    override suspend fun getAlbumByBvid(bvid: String): Album? =
        albumDao.getByBvid(bvid.trim())?.toModel()

    override suspend fun getTracks(albumId: Long): List<Track> =
        trackDao.getByAlbum(albumId).map { it.toModel() }

    override suspend fun getTrack(trackId: Long): Track? = trackDao.getById(trackId)?.toModel()

    override suspend fun saveAlbumWithTracks(album: Album, tracks: List<Track>): Long =
        database.withTransaction {
            val now = System.currentTimeMillis()
            val entity = album.toEntity()
            val albumId = if (entity.id > 0L) {
                albumDao.update(entity.copy(updatedAt = now))
                entity.id
            } else {
                albumDao.insert(entity.copy(createdAt = entity.createdAt.takeIf { it > 0L } ?: now, updatedAt = now))
            }
            trackDao.deleteByAlbum(albumId)
            if (tracks.isNotEmpty()) {
                trackDao.insertAll(
                    tracks.mapIndexed { index, track ->
                        track.copy(albumId = albumId, index = index).toEntity().copy(id = 0L)
                    },
                )
            }
            albumId
        }

    override suspend fun updateAlbum(album: Album) {
        albumDao.update(album.copy(updatedAt = System.currentTimeMillis()).toEntity())
    }

    override suspend fun replaceTracks(albumId: Long, tracks: List<Track>) {
        database.withTransaction {
            trackDao.deleteByAlbum(albumId)
            if (tracks.isNotEmpty()) {
                trackDao.insertAll(
                    tracks.mapIndexed { index, track ->
                        track.copy(albumId = albumId, index = index).toEntity().copy(id = 0L)
                    },
                )
            }
            albumDao.getById(albumId)?.let { album ->
                albumDao.update(album.copy(updatedAt = System.currentTimeMillis()))
            }
        }
    }

    override suspend fun deleteAlbum(albumId: Long) {
        albumDao.deleteById(albumId)
    }

    /**
     * 记录播放：更新专辑最近播放时间 + 播放次数。
     * [trackId] 仅用于标识播的是哪首，当前统计粒度是专辑，故不落库。
     */
    override suspend fun markPlayed(albumId: Long, trackId: Long) {
        albumDao.markPlayed(albumId, System.currentTimeMillis())
    }

    override fun searchSongs(query: String): Flow<List<TrackWithAlbum>> {
        val trimmed = query.trim()
        return songs.map { list ->
            if (trimmed.isEmpty()) {
                list
            } else {
                list.filter { item ->
                    item.track.title.contains(trimmed, ignoreCase = true) ||
                        item.album.title.contains(trimmed, ignoreCase = true) ||
                        item.album.artist.contains(trimmed, ignoreCase = true)
                }
            }
        }
    }

    override fun searchAlbums(query: String): Flow<List<Album>> {
        val trimmed = query.trim()
        return albums.map { list ->
            if (trimmed.isEmpty()) {
                list
            } else {
                list.filter { album ->
                    album.title.contains(trimmed, ignoreCase = true) ||
                        album.artist.contains(trimmed, ignoreCase = true)
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // 导入
    // -----------------------------------------------------------------------

    override suspend fun importAlbum(request: AlbumImportRequest): ImportResult {
        val input = request.bvidOrUrl.trim()
        if (input.isEmpty()) return ImportResult.Failure(request.bvidOrUrl, "请输入 BV 号或视频链接")

        val info = try {
            biliRepository.previewVideo(input)
        } catch (e: CancellationException) {
            throw e
        } catch (e: BiliApiException) {
            return ImportResult.Failure(input, e.message ?: "获取视频信息失败")
        } catch (e: Exception) {
            return ImportResult.Failure(input, "获取视频信息失败：${e.message ?: "请检查网络后重试"}")
        }

        if (request.mode == ImportMode.MULTI_PAGE && info.pages.size > 1) {
            return ImportResult.Failure(
                input,
                "多 P 视频暂不支持自动分 P 导入（当前版本请按单视频导入后手动分曲）",
            )
        }

        val page = info.pages.firstOrNull()
            ?: return ImportResult.Failure(input, "该视频缺少可播放的分 P 信息，可能已失效")
        val cid = page.cid
        if (cid <= 0L) return ImportResult.Failure(input, "该视频缺少可播放的分 P 信息，可能已失效")

        val durationMs = info.durationMs.takeIf { it > 0L } ?: page.durationMs
        if (durationMs <= 0L) return ImportResult.Failure(input, "视频时长异常，无法导入")

        val albumTitle = request.albumTitle?.trim()?.takeIf { it.isNotEmpty() } ?: info.title
        val artist = request.artist?.trim()?.takeIf { it.isNotEmpty() } ?: info.ownerName
        val segmentSpecs = buildSegments(request.segments, durationMs, albumTitle)
        val tracks = segmentSpecs.mapIndexed { index, segment ->
            val endMs = segmentSpecs.getOrNull(index + 1)?.startMs ?: durationMs
            Track(id = 0L, albumId = 0L, index = index, title = segment.title, startMs = segment.startMs, endMs = endMs)
        }

        val existing = getAlbumByBvid(info.bvid)
        val now = System.currentTimeMillis()
        val album = Album(
            id = existing?.id ?: 0L,
            bvid = info.bvid,
            cid = cid,
            page = page.page.coerceAtLeast(1),
            pageCount = info.pages.size,
            title = albumTitle,
            artist = artist,
            coverUrl = info.coverUrl,
            durationMs = durationMs,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
            lastPlayedAt = existing?.lastPlayedAt,
            playCount = existing?.playCount ?: 0,
        )
        return try {
            val albumId = saveAlbumWithTracks(album, tracks)
            ImportResult.Success(albumId = albumId, albumTitle = albumTitle, trackCount = tracks.size)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ImportResult.Failure(input, "保存专辑失败：${e.message ?: "数据库错误"}")
        }
    }

    override suspend fun importBatchText(text: String): BatchImportReport {
        val parsed = parser.parse(text)
        val results = mutableListOf<ImportResult>()
        for ((index, item) in parsed.items.withIndex()) {
            // 网络请求串行 + 间隔，避免 B 站风控（BiliApi 内部另有 300ms 节流）
            if (index > 0) delay(BATCH_IMPORT_INTERVAL_MS)
            results += importAlbum(
                AlbumImportRequest(
                    bvidOrUrl = item.bvidOrUrl,
                    albumTitle = item.albumTitle,
                    artist = item.artist,
                    mode = item.mode,
                    segments = item.segments,
                ),
            )
        }
        // 解析失败项也进入报告，让 UI 能逐条展示
        parsed.errors.forEach { error ->
            results += ImportResult.Failure(error.source, error.message)
        }
        return BatchImportReport(results)
    }

    /**
     * 生成曲目分割方案：
     * - 未指定分割点 → **不生成任何曲目**（专辑处于「未分割」状态，播放时由播放器虚拟成整张一首，
     *   避免导入后出现一个需要手动删除的「整张专辑占位曲」）；
     * - 指定分割点 → 按开始时间排序、去重、裁剪到视频时长内，每首结束于下一首开始（最后一首到视频结束）。
     */
    private fun buildSegments(requested: List<SegmentSpec>, durationMs: Long, albumTitle: String): List<SegmentSpec> {
        if (requested.isEmpty()) return emptyList()
        val normalized = requested
            .filter { it.startMs in 0 until durationMs }
            .sortedBy { it.startMs }
            .distinctBy { it.startMs }
            .mapIndexed { index, segment ->
                SegmentSpec(
                    startMs = segment.startMs,
                    title = segment.title.trim().ifBlank { "曲目 ${index + 1}" },
                )
            }
        return normalized
    }

    private companion object {
        /** 批量导入两条之间的额外等待（BiliApi 请求节流之外）。 */
        const val BATCH_IMPORT_INTERVAL_MS = 350L
    }
}
