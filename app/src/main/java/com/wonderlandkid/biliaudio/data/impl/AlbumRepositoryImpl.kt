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
import com.wonderlandkid.biliaudio.data.model.BiliVideoInfo
import com.wonderlandkid.biliaudio.data.model.ImportMode
import com.wonderlandkid.biliaudio.data.model.ImportResult
import com.wonderlandkid.biliaudio.data.model.SegmentSpec
import com.wonderlandkid.biliaudio.data.model.Track
import com.wonderlandkid.biliaudio.data.model.TrackWithAlbum
import com.wonderlandkid.biliaudio.data.repository.AlbumRepository
import com.wonderlandkid.biliaudio.data.repository.BiliRepository
import com.wonderlandkid.biliaudio.util.Diag
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** 诊断日志域标签（data 层导入链路）。 */
private const val TAG = "Import"
private const val SOURCE_BILIBILI = "bilibili"

/**
 * 专辑 / 曲目库：Room 持久化 + 单条/批量导入。
 *
 * 装配：`AlbumRepositoryImpl(database = AppDatabase.getInstance(context), biliRepository = biliRepository)`。
 *
 * 导入行为（与 docs/IMPORT_FORMATS.md 一致）：
 * - 同一 bvid 已存在时**覆盖更新**：保留原 id / createdAt / lastPlayedAt / playCount，替换元信息与分曲；
 * - 单条失败不影响其他条目（批量导入逐条收集 [ImportResult]）；
 * - 多 P 视频支持 [ImportMode.MULTI_PAGE]：每个分 P 自动成为一首曲目；曲目记录 sourceCid/page，
 *   播放层按曲目解析分 P 音频流，实现无需手动切歌的无缝队列。
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

    override suspend fun getAlbumBySourceKey(source: String, bvid: String, page: Int): Album? =
        albumDao.getBySourceKey(source = source, bvid = bvid.trim(), page = page)?.toModel()

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
            // 重写分曲时按 startMs 保留已有 uid（稳定 ID 不因重新导入/编辑而改变）
            val oldUids = if (entity.id > 0L) {
                trackDao.getByAlbum(albumId).associate { it.startMs to it.uid }
            } else {
                emptyMap()
            }
            trackDao.deleteByAlbum(albumId)
            if (tracks.isNotEmpty()) {
                trackDao.insertAll(
                    tracks.mapIndexed { index, track ->
                        track.copy(
                            albumId = albumId,
                            index = index,
                            uid = track.uid.ifBlank { oldUids[track.startMs].orEmpty() },
                        ).toEntity().copy(id = 0L)
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
            val oldUids = trackDao.getByAlbum(albumId).associate { it.startMs to it.uid }
            trackDao.deleteByAlbum(albumId)
            if (tracks.isNotEmpty()) {
                trackDao.insertAll(
                    tracks.mapIndexed { index, track ->
                        track.copy(
                            albumId = albumId,
                            index = index,
                            uid = track.uid.ifBlank { oldUids[track.startMs].orEmpty() },
                        ).toEntity().copy(id = 0L)
                    },
                )
            }
            albumDao.getById(albumId)?.let { album ->
                albumDao.update(album.copy(updatedAt = System.currentTimeMillis()))
            }
        }
    }

    override suspend fun deleteAlbum(albumId: Long) {
        // 先删本地自定义封面文件，避免孤儿文件占用空间
        val coverPath = albumDao.getById(albumId)?.coverLocalPath
        albumDao.deleteById(albumId)
        coverPath?.let { path ->
            runCatching {
                java.io.File(path).takeIf { it.isFile }?.delete()
            }
        }
    }

    override suspend fun updateAlbumCover(albumId: Long, coverLocalPath: String?): Boolean {
        val entity = albumDao.getById(albumId) ?: return false
        albumDao.update(
            entity.copy(
                coverLocalPath = coverLocalPath,
                updatedAt = System.currentTimeMillis(),
            ),
        )
        Diag.log("Cover", "update album=$albumId cover=${coverLocalPath ?: "<bilibili>"}")
        return true
    }

    /**
     * 记录播放：更新专辑最近播放时间 + 播放次数。
     * [trackId] 仅用于标识播的是哪首，当前统计粒度是专辑，故不落库。
     */
    override suspend fun markPlayed(albumId: Long, trackId: Long) {
        albumDao.markPlayed(albumId, System.currentTimeMillis())
    }

    override suspend fun cleanupLegacyPlaceholderTracks(): Int {
        var removed = 0
        albums.first().forEach { album ->
            val tracks = getTracks(album.id)
            if (isLegacyPlaceholder(album, tracks)) {
                database.withTransaction { trackDao.deleteByAlbum(album.id) }
                removed++
                Diag.log(TAG, "cleanup legacy placeholder album=${album.id} bvid=${album.bvid} title=${album.title}")
            }
        }
        if (removed > 0) Diag.log(TAG, "cleanup legacy placeholder tracks removed=$removed")
        return removed
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
        val importStartMs = System.currentTimeMillis()
        Diag.log(TAG, "import START input=$input mode=${request.mode} segments=${request.segments.size}")

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
            return importMultiPage(request = request, info = info, importStartMs = importStartMs)
        }

        val pageNo = request.page.coerceAtLeast(1)
        val page = info.pages.firstOrNull { it.page == pageNo }
            ?: info.pages.firstOrNull()?.takeIf { pageNo == 1 }
            ?: return ImportResult.Failure(input, "该视频没有第 $pageNo 个分 P，可能已失效")
        val cid = page.cid
        if (cid <= 0L) return ImportResult.Failure(input, "该视频缺少可播放的分 P 信息，可能已失效")

        val durationMs = info.durationMs.takeIf { it > 0L } ?: page.durationMs
        if (durationMs <= 0L) return ImportResult.Failure(input, "视频时长异常，无法导入")

        val albumTitle = request.albumTitle?.trim()?.takeIf { it.isNotEmpty() }
            ?: if (pageNo > 1 && page.title.isNotBlank()) "${info.title} P${pageNo}" else info.title
        val artist = request.artist?.trim()?.takeIf { it.isNotEmpty() } ?: info.ownerName
        val segmentSpecs = buildSegments(request.segments, durationMs, albumTitle)
        val tracks = segmentSpecs.mapIndexed { index, segment ->
            val endMs = segmentSpecs.getOrNull(index + 1)?.startMs ?: durationMs
            Track(id = 0L, albumId = 0L, index = index, title = segment.title, startMs = segment.startMs, endMs = endMs)
        }

        val existing = getAlbumBySourceKey(SOURCE_BILIBILI, info.bvid, pageNo)
        val now = System.currentTimeMillis()
        val album = Album(
            id = existing?.id ?: 0L,
            bvid = info.bvid,
            cid = cid,
            page = pageNo,
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
            Diag.log(
                TAG,
                "import DONE input=$input bvid=${info.bvid} albums id=$albumId " +
                    "tracks=${tracks.size} durationMs=$durationMs in ${System.currentTimeMillis() - importStartMs}ms",
            )
            ImportResult.Success(albumId = albumId, albumTitle = albumTitle, trackCount = tracks.size)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ImportResult.Failure(input, "保存专辑失败：${e.message ?: "数据库错误"}")
        }
    }

    /**
     * 多 P 视频导入：每个分 P 一首曲目。
     *
     * 曲目的 startMs/endMs 是整张专辑的累计时间轴（进度条/歌词用）；
     * sourceCid/sourcePage/sourceOffsetMs 记录实际音频来源，播放层按曲目解析各自的分 P 流。
     * 所有分 P 的音频 URL 会在开始播放前一次性解析并全部入 ExoPlayer 队列，实现自动无缝切歌。
     */
    private suspend fun importMultiPage(
        request: AlbumImportRequest,
        info: BiliVideoInfo,
        importStartMs: Long,
    ): ImportResult {
        val input = request.bvidOrUrl.trim()
        val pages = info.pages
            .sortedBy { it.page }
            .filter { it.cid > 0L && it.durationMs > 0L }
        if (pages.isEmpty()) {
            return ImportResult.Failure(input, "该视频没有可用的分 P 音频，无法导入")
        }
        val albumTitle = request.albumTitle?.trim()?.takeIf { it.isNotEmpty() } ?: info.title
        val artist = request.artist?.trim()?.takeIf { it.isNotEmpty() } ?: info.ownerName
        val totalDurationMs = pages.sumOf { it.durationMs }

        var cursorMs = 0L
        val tracks = pages.mapIndexed { index, page ->
            val startMs = cursorMs
            val endMs = startMs + page.durationMs
            cursorMs = endMs
            Track(
                id = 0L,
                albumId = 0L,
                index = index,
                title = page.title.trim().ifBlank { "P${page.page}" },
                startMs = startMs,
                endMs = endMs,
                sourceCid = page.cid,
                sourcePage = page.page,
                sourceOffsetMs = 0L,
            )
        }
        val firstPage = pages.first()
        val existing = getAlbumBySourceKey(SOURCE_BILIBILI, info.bvid, 1)
        val now = System.currentTimeMillis()
        val album = Album(
            id = existing?.id ?: 0L,
            bvid = info.bvid,
            // 多 P 专辑以第一 P 作为专辑封面/视频入口，实际播放按曲目 cid 解析
            cid = firstPage.cid,
            page = 1,
            pageCount = pages.size,
            title = albumTitle,
            artist = artist,
            coverUrl = info.coverUrl,
            durationMs = totalDurationMs,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
            lastPlayedAt = existing?.lastPlayedAt,
            playCount = existing?.playCount ?: 0,
        )
        return try {
            val albumId = saveAlbumWithTracks(album, tracks)
            Diag.log(
                TAG,
                "import MULTI_PAGE DONE input=$input bvid=${info.bvid} albums id=$albumId " +
                    "pages=${pages.size} durationMs=$totalDurationMs in ${System.currentTimeMillis() - importStartMs}ms",
            )
            ImportResult.Success(albumId = albumId, albumTitle = albumTitle, trackCount = tracks.size)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ImportResult.Failure(input, "保存专辑失败：${e.message ?: "数据库错误"}")
        }
    }

    override suspend fun importBatchText(text: String): BatchImportReport {
        val parsed = parser.parse(text)
        Diag.log(TAG, "importBatch START items=${parsed.items.size} errors=${parsed.errors.size}")
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

/**
 * 判断是否为旧版「整张专辑占位曲」：
 * 专辑下只有 1 条曲目、从 0 开始、覆盖到专辑结束、且标题等于专辑名。
 */
internal fun isLegacyPlaceholder(album: Album, tracks: List<Track>): Boolean {
    if (tracks.size != 1) return false
    val only = tracks.first()
    return only.startMs == 0L &&
        only.endMs >= album.durationMs &&
        only.title.trim() == album.title.trim()
}
