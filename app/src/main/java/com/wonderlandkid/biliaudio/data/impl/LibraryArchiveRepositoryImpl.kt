package com.wonderlandkid.biliaudio.data.impl

import android.content.Context
import com.wonderlandkid.biliaudio.data.model.Album
import com.wonderlandkid.biliaudio.data.model.AlbumImportRequest
import com.wonderlandkid.biliaudio.data.model.ArchiveAlbum
import com.wonderlandkid.biliaudio.data.model.ArchiveImportMode
import com.wonderlandkid.biliaudio.data.model.ArchiveImportReport
import com.wonderlandkid.biliaudio.data.model.ArchiveItemAction
import com.wonderlandkid.biliaudio.data.model.ArchiveItemResult
import com.wonderlandkid.biliaudio.data.model.ArchivePreview
import com.wonderlandkid.biliaudio.data.model.ArchivePreviewItem
import com.wonderlandkid.biliaudio.data.model.ArchiveSettings
import com.wonderlandkid.biliaudio.data.model.AudioQualityPreference
import com.wonderlandkid.biliaudio.data.model.ImportMode
import com.wonderlandkid.biliaudio.data.model.ImportResult
import com.wonderlandkid.biliaudio.data.model.LibraryArchive
import com.wonderlandkid.biliaudio.data.model.SegmentSpec
import com.wonderlandkid.biliaudio.data.model.Track
import com.wonderlandkid.biliaudio.data.net.BiliUrlParser
import com.wonderlandkid.biliaudio.data.repository.AlbumRepository
import com.wonderlandkid.biliaudio.data.repository.BiliRepository
import com.wonderlandkid.biliaudio.data.repository.LibraryArchiveRepository
import com.wonderlandkid.biliaudio.data.repository.SettingsRepository
import kotlinx.coroutines.flow.first
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 音乐库归档仓库实现（docs/LIBRARY_ARCHIVE.md）。
 *
 * - 导出：把 Room 里的专辑/分曲 + 设置序列化成归档 JSON；
 * - 导入：按模式（合并 / 仅分割点 / 覆盖全库）落库，覆盖全库前自动备份到 `files/backups/`；
 * - 新建专辑需要联网（通过 B 站 view 补全标题/封面/时长），已存在专辑的更新/分割点替换可离线完成。
 */
class LibraryArchiveRepositoryImpl(
    private val backupDir: File?,
    private val albumRepository: AlbumRepository,
    private val biliRepository: BiliRepository,
    private val settingsRepository: SettingsRepository,
    private val codec: LibraryArchiveCodec = LibraryArchiveCodec(),
) : LibraryArchiveRepository {

    /** Android 侧便捷构造：备份目录 = `files/backups`。 */
    constructor(
        context: Context,
        albumRepository: AlbumRepository,
        biliRepository: BiliRepository,
        settingsRepository: SettingsRepository,
        codec: LibraryArchiveCodec = LibraryArchiveCodec(),
    ) : this(
        backupDir = File(context.applicationContext.filesDir, "backups"),
        albumRepository = albumRepository,
        biliRepository = biliRepository,
        settingsRepository = settingsRepository,
        codec = codec,
    )

    // ------------------------------------------------------------------
    // 导出
    // ------------------------------------------------------------------

    override suspend fun exportAllJson(): String = codec.encode(buildLibraryArchive())

    override suspend fun exportAlbumJson(albumId: Long): String? {
        val album = albumRepository.getAlbum(albumId) ?: return null
        val tracks = albumRepository.getTracks(albumId)
        return codec.encode(LibraryArchive.create(albums = listOf(album.toArchiveAlbum(tracks))))
    }

    private suspend fun buildLibraryArchive(): LibraryArchive {
        val albums = albumRepository.albums.first()
        val archiveAlbums = albums.map { album ->
            album.toArchiveAlbum(albumRepository.getTracks(album.id))
        }
        val settings = settingsRepository.settings.value
        return LibraryArchive.create(
            albums = archiveAlbums,
            settings = ArchiveSettings(
                audioQuality = settings.audioQuality.name,
                darkTheme = settings.darkTheme,
            ),
            exportedAt = nowIso(),
        )
    }

    // ------------------------------------------------------------------
    // 预览
    // ------------------------------------------------------------------

    override suspend fun preview(text: String, mode: ArchiveImportMode): ArchivePreview {
        val parsed = codec.parse(text)
        val items = parsed.archive.albums.map { archiveAlbum ->
            val offlineBvid = resolveBvidOffline(archiveAlbum)
            val exists = offlineBvid != null && albumRepository.getAlbumByBvid(offlineBvid) != null
            val plannedAction = when (mode) {
                ArchiveImportMode.SEGMENTS_ONLY ->
                    if (exists) ArchiveItemAction.SEGMENTS_UPDATED else ArchiveItemAction.SKIPPED
                ArchiveImportMode.MERGE ->
                    if (exists) ArchiveItemAction.UPDATED else ArchiveItemAction.CREATED
                ArchiveImportMode.REPLACE -> ArchiveItemAction.CREATED
            }
            ArchivePreviewItem(
                bvid = offlineBvid ?: archiveAlbum.url.orEmpty(),
                page = archiveAlbum.page,
                title = archiveAlbum.title ?: offlineBvid ?: archiveAlbum.url.orEmpty(),
                segmentCount = archiveAlbum.segments.size,
                exists = exists,
                plannedAction = plannedAction,
                note = if (offlineBvid == null) "导入时联网解析视频信息" else null,
            )
        }
        return ArchivePreview(
            recognizedAs = parsed.recognizedAs,
            albumCount = parsed.archive.albums.size,
            trackCount = parsed.archive.albums.sumOf { it.segments.size },
            hasSettings = parsed.archive.settings != null,
            items = items,
            parseErrors = parsed.errors,
        )
    }

    // ------------------------------------------------------------------
    // 导入
    // ------------------------------------------------------------------

    override suspend fun importArchive(text: String, mode: ArchiveImportMode): ArchiveImportReport {
        val parsed = codec.parse(text)
        var backupFilePath: String? = null

        if (mode == ArchiveImportMode.REPLACE) {
            backupFilePath = runCatching { createAutoBackup() }.getOrNull()
            albumRepository.albums.first().forEach { album ->
                runCatching { albumRepository.deleteAlbum(album.id) }
            }
        }
        // 「仅更新分割点」不导入设置，避免意外改配置
        if (mode != ArchiveImportMode.SEGMENTS_ONLY) {
            parsed.archive.settings?.let { applySettings(it) }
        }

        val results = ArrayList<ArchiveItemResult>(parsed.archive.albums.size)
        for (archiveAlbum in parsed.archive.albums) {
            results += importOne(archiveAlbum, mode)
        }
        return ArchiveImportReport(
            mode = mode,
            items = results,
            parseErrors = parsed.errors,
            backupFilePath = backupFilePath,
        )
    }

    private suspend fun importOne(archiveAlbum: ArchiveAlbum, mode: ArchiveImportMode): ArchiveItemResult {
        val bvid = resolveBvidOnline(archiveAlbum)
            ?: return itemResult(
                archiveAlbum,
                ArchiveItemAction.FAILED,
                message = "无法解析视频地址（需要联网获取 BV 号）",
            )
        val existing = albumRepository.getAlbumByBvid(bvid)

        return when {
            mode == ArchiveImportMode.SEGMENTS_ONLY -> {
                if (existing == null) {
                    itemResult(archiveAlbum, ArchiveItemAction.SKIPPED, message = "本地没有这张专辑，已跳过分割点更新")
                } else {
                    val applied = applySegments(existing, archiveAlbum)
                    if (applied) {
                        itemResult(archiveAlbum, ArchiveItemAction.SEGMENTS_UPDATED, message = "已更新 ${archiveAlbum.segments.size} 个分曲")
                    } else {
                        itemResult(archiveAlbum, ArchiveItemAction.FAILED, message = "分割点为空或时间无效")
                    }
                }
            }

            existing == null -> createNew(archiveAlbum)
            else -> updateExisting(existing, archiveAlbum)
        }
    }

    private suspend fun createNew(archiveAlbum: ArchiveAlbum): ArchiveItemResult {
        val request = AlbumImportRequest(
            bvidOrUrl = archiveAlbum.bvid ?: archiveAlbum.url.orEmpty(),
            albumTitle = archiveAlbum.title,
            artist = archiveAlbum.artist,
            mode = ImportMode.SINGLE_VIDEO,
            segments = archiveAlbum.segments.map { SegmentSpec(startMs = it.startMs ?: 0L, title = it.title.orEmpty()) },
        )
        return when (val result = albumRepository.importAlbum(request)) {
            is ImportResult.Success -> {
                // 用归档里的用户数据/元信息补齐（importAlbum 只从 B 站生成，可能丢失播放次数等）
                albumRepository.getAlbum(result.albumId)?.let { created ->
                    albumRepository.updateAlbum(
                        created.copy(
                            title = archiveAlbum.title ?: created.title,
                            artist = archiveAlbum.artist ?: created.artist,
                            coverUrl = archiveAlbum.coverUrl ?: created.coverUrl,
                            durationMs = archiveAlbum.durationMs ?: created.durationMs,
                            createdAt = archiveAlbum.createdAt ?: created.createdAt,
                            lastPlayedAt = archiveAlbum.lastPlayedAt ?: created.lastPlayedAt,
                            playCount = archiveAlbum.playCount ?: created.playCount,
                            updatedAt = System.currentTimeMillis(),
                        ),
                    )
                }
                itemResult(
                    archiveAlbum,
                    ArchiveItemAction.CREATED,
                    title = result.albumTitle,
                    message = "已创建，共 ${result.trackCount} 首",
                )
            }

            is ImportResult.Failure -> itemResult(
                archiveAlbum,
                ArchiveItemAction.FAILED,
                message = result.message,
            )
        }
    }

    private suspend fun updateExisting(existing: Album, archiveAlbum: ArchiveAlbum): ArchiveItemResult {
        val merged = existing.copy(
            title = archiveAlbum.title ?: existing.title,
            artist = archiveAlbum.artist ?: existing.artist,
            coverUrl = archiveAlbum.coverUrl ?: existing.coverUrl,
            durationMs = archiveAlbum.durationMs ?: existing.durationMs,
            cid = archiveAlbum.cid ?: existing.cid,
            page = archiveAlbum.page,
            createdAt = archiveAlbum.createdAt ?: existing.createdAt,
            lastPlayedAt = maxPlayedAt(existing.lastPlayedAt, archiveAlbum.lastPlayedAt),
            playCount = maxOf(existing.playCount, archiveAlbum.playCount ?: 0),
            updatedAt = System.currentTimeMillis(),
        )
        albumRepository.updateAlbum(merged)
        val message = if (archiveAlbum.segments.isEmpty()) {
            "已更新元信息（文件未包含分曲，保留原分曲）"
        } else {
            val applied = applySegments(merged, archiveAlbum)
            if (!applied) return itemResult(archiveAlbum, ArchiveItemAction.FAILED, message = "分曲时间点无效")
            "已更新为 ${archiveAlbum.segments.size} 个分曲"
        }
        return itemResult(archiveAlbum, ArchiveItemAction.UPDATED, title = merged.title, message = message)
    }

    /** 用归档分曲替换专辑曲目；成功返回 true。 */
    private suspend fun applySegments(album: Album, archiveAlbum: ArchiveAlbum): Boolean {
        val segments = archiveAlbum.segments
        if (segments.isEmpty()) return false
        val durationMs = album.durationMs.takeIf { it > 0 }
            ?: archiveAlbum.durationMs?.takeIf { it > 0 }
            ?: return false
        val tracks = segments.mapIndexed { index, segment ->
            val startMs = segment.startMs ?: return false
            val endMs = segments.getOrNull(index + 1)?.startMs ?: durationMs
            Track(
                albumId = album.id,
                index = index,
                title = segment.title?.takeIf { it.isNotBlank() } ?: "曲目 ${index + 1}",
                startMs = startMs,
                endMs = endMs.coerceAtLeast(startMs + 1),
            )
        }
        albumRepository.replaceTracks(album.id, tracks)
        return true
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private fun resolveBvidOffline(archiveAlbum: ArchiveAlbum): String? {
        archiveAlbum.bvid?.let { return it }
        val raw = archiveAlbum.url?.trim().orEmpty()
        if (raw.isEmpty()) return null
        return (BiliUrlParser.parse(raw) as? BiliUrlParser.VideoRef.Bvid)?.bvid
    }

    private suspend fun resolveBvidOnline(archiveAlbum: ArchiveAlbum): String? {
        resolveBvidOffline(archiveAlbum)?.let { return it }
        val raw = archiveAlbum.url?.trim().orEmpty()
        if (raw.isEmpty()) return null
        return runCatching { biliRepository.previewVideo(raw).bvid }.getOrNull()
    }

    private suspend fun applySettings(settings: ArchiveSettings) {
        settings.audioQuality?.let { name ->
            runCatching { AudioQualityPreference.valueOf(name) }.getOrNull()?.let { quality ->
                settingsRepository.setAudioQuality(quality)
            }
        }
        settings.darkTheme?.let { dark -> settingsRepository.setDarkTheme(dark) }
    }

    private suspend fun createAutoBackup(): String? {
        val dir = backupDir ?: return null
        dir.mkdirs()
        val file = File(dir, "biliaudio-before-import-${timestampForFile()}.json")
        file.writeText(exportAllJson())
        pruneBackups(dir)
        return file.absolutePath
    }

    override fun lastAutoBackupAt(): Long? =
        backupDir?.listFiles()?.maxOfOrNull { it.lastModified() }

    private fun pruneBackups(dir: File) {
        val keep = 10
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(keep)?.forEach {
            runCatching { it.delete() }
        }
    }

    private fun maxPlayedAt(a: Long?, b: Long?): Long? {
        val max = maxOf(a ?: 0L, b ?: 0L)
        return max.takeIf { it > 0 }
    }

    private fun itemResult(
        archiveAlbum: ArchiveAlbum,
        action: ArchiveItemAction,
        title: String? = null,
        message: String? = null,
    ): ArchiveItemResult = ArchiveItemResult(
        bvid = archiveAlbum.bvid ?: archiveAlbum.url.orEmpty(),
        page = archiveAlbum.page,
        title = title ?: archiveAlbum.title ?: archiveAlbum.bvid ?: archiveAlbum.url.orEmpty(),
        action = action,
        message = message,
    )

    private fun Album.toArchiveAlbum(tracks: List<Track>): ArchiveAlbum = ArchiveAlbum(
        bvid = bvid,
        page = page,
        cid = cid,
        title = title,
        artist = artist,
        coverUrl = coverUrl,
        durationMs = durationMs,
        createdAt = createdAt,
        lastPlayedAt = lastPlayedAt,
        playCount = playCount,
        segments = tracks.sortedBy { it.startMs }.map { track ->
            com.wonderlandkid.biliaudio.data.model.ArchiveSegment(
                startMs = track.startMs,
                title = track.title,
            )
        },
    )

    private fun nowIso(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(Date())

    private fun timestampForFile(): String =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
}
