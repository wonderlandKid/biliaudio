package com.wonderlandkid.biliaudio.data.impl

import android.content.Context
import com.wonderlandkid.biliaudio.BuildConfig
import com.wonderlandkid.biliaudio.data.model.Album
import com.wonderlandkid.biliaudio.data.model.AlbumImportRequest
import com.wonderlandkid.biliaudio.data.model.ArchiveAlbum
import com.wonderlandkid.biliaudio.data.model.ArchiveCover
import com.wonderlandkid.biliaudio.data.model.ArchiveFormatException
import com.wonderlandkid.biliaudio.data.model.ArchiveImportMode
import com.wonderlandkid.biliaudio.data.model.ArchiveImportReport
import com.wonderlandkid.biliaudio.data.model.ArchiveItemAction
import com.wonderlandkid.biliaudio.data.model.ArchiveItemResult
import com.wonderlandkid.biliaudio.data.model.ArchiveKind
import com.wonderlandkid.biliaudio.data.model.ArchiveManifest
import com.wonderlandkid.biliaudio.data.model.ArchiveParseError
import com.wonderlandkid.biliaudio.data.model.ArchivePlaylist
import com.wonderlandkid.biliaudio.data.model.ArchivePreview
import com.wonderlandkid.biliaudio.data.model.ArchivePreviewItem
import com.wonderlandkid.biliaudio.data.model.ArchiveSegment
import com.wonderlandkid.biliaudio.data.model.ArchiveSettingsDocument
import com.wonderlandkid.biliaudio.data.model.ArchiveTreeEntry
import com.wonderlandkid.biliaudio.data.model.AudioQualityPreference
import com.wonderlandkid.biliaudio.data.model.ImportMode
import com.wonderlandkid.biliaudio.data.model.ImportResult
import com.wonderlandkid.biliaudio.data.model.LibraryArchive
import com.wonderlandkid.biliaudio.data.model.PlaylistsArchive
import com.wonderlandkid.biliaudio.data.model.Track
import com.wonderlandkid.biliaudio.data.net.BiliUrlParser
import com.wonderlandkid.biliaudio.data.repository.AlbumCoverRepository
import com.wonderlandkid.biliaudio.data.repository.AlbumRepository
import com.wonderlandkid.biliaudio.data.repository.BiliRepository
import com.wonderlandkid.biliaudio.data.repository.LibraryArchiveRepository
import com.wonderlandkid.biliaudio.data.repository.SettingsRepository
import com.wonderlandkid.biliaudio.util.Diag
import kotlinx.coroutines.flow.first
import okio.ByteString.Companion.decodeBase64
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * 音乐库归档仓库（v3 分域 bundle）。
 *
 * - 整库导出 = 目录 bundle：manifest.json + library.json + settings.json(可选) + covers/；
 * - 单专辑/分割点仍支持单 JSON 分享；
 * - 导入按 albumKey upsert；REPLACE 前自动备份为私有目录下的 bundle；
 * - 设置只在 REPLACE 恢复时应用（默认不覆盖本地设置）。
 */
class LibraryArchiveRepositoryImpl(
    private val backupDir: File?,
    private val albumRepository: AlbumRepository,
    private val biliRepository: BiliRepository,
    private val settingsRepository: SettingsRepository,
    private val albumCoverRepository: AlbumCoverRepository = NoopAlbumCoverRepository,
    private val codec: LibraryArchiveCodec = LibraryArchiveCodec(),
    private val bundleCodec: ArchiveBundleCodec = ArchiveBundleCodec(codec),
) : LibraryArchiveRepository {

    constructor(
        context: Context,
        albumRepository: AlbumRepository,
        biliRepository: BiliRepository,
        settingsRepository: SettingsRepository,
        albumCoverRepository: AlbumCoverRepository = NoopAlbumCoverRepository,
        codec: LibraryArchiveCodec = LibraryArchiveCodec(),
    ) : this(
        backupDir = File(context.applicationContext.filesDir, "backups"),
        albumRepository = albumRepository,
        biliRepository = biliRepository,
        settingsRepository = settingsRepository,
        albumCoverRepository = albumCoverRepository,
        codec = codec,
    )

    // ------------------------------------------------------------------
    // 导出
    // ------------------------------------------------------------------

    /** 单专辑分享 JSON（kind=album，封面内嵌 base64 以保持单文件可分享）。 */
    override suspend fun exportAlbumJson(albumId: Long): String? {
        val album = albumRepository.getAlbum(albumId) ?: return null
        val tracks = albumRepository.getTracks(albumId)
        val albumEntry = album.toArchiveAlbum(tracks, inlineCover = true)
        return codec.encode(
            LibraryArchive.create(
                kind = ArchiveKind.ALBUM,
                albums = listOf(albumEntry),
                exportedAt = nowIso(),
                appVersion = BuildConfig.VERSION_NAME,
            ),
        )
    }

    /** 整库 bundle 的全部文件（manifest/library/settings/covers）。 */
    override suspend fun exportTreeEntries(): List<ArchiveTreeEntry> {
        val albums = albumRepository.albums.first()
        val archiveAlbums = ArrayList<ArchiveAlbum>(albums.size)
        val coverBytes = LinkedHashMap<String, ByteArray>()
        for (album in albums) {
            val tracks = albumRepository.getTracks(album.id)
            val entry = album.toArchiveAlbum(tracks, inlineCover = false)
            entry.cover?.let { cover ->
                readCoverBytes(album.coverLocalPath)?.let { bytes ->
                    coverBytes[cover.hash] = bytes
                }
            }
            archiveAlbums += entry
        }
        val library = LibraryArchive.create(
            kind = ArchiveKind.LIBRARY,
            albums = archiveAlbums,
            exportedAt = nowIso(),
            appVersion = BuildConfig.VERSION_NAME,
        )
        val settings = ArchiveSettingsDocument(
            audioQuality = settingsRepository.settings.value.audioQuality.name,
            darkTheme = settingsRepository.settings.value.darkTheme,
            diagnosticLogsEnabled = settingsRepository.settings.value.diagnosticLogsEnabled,
        )
        return bundleCodec.buildEntries(
            library = library,
            settings = settings,
            playlists = null,
            coverBytes = coverBytes,
            appVersion = BuildConfig.VERSION_NAME,
        ).also { Diag.log(TAG, "archive export bundle files=${it.size}") }
    }

    private fun Album.toArchiveAlbum(tracks: List<Track>, inlineCover: Boolean): ArchiveAlbum {
        val coverFile = readCoverBytes(coverLocalPath)
        val hash = coverFile?.let(LibraryArchiveCodec::sha256Hex)
        val cover = if (hash != null && coverFile != null) {
            val mime = detectMime(coverFile)
            ArchiveCover(
                hash = hash,
                mime = mime,
                byteLength = coverFile.size,
                dataBase64 = if (inlineCover) okio.ByteString.of(*coverFile).base64() else null,
            )
        } else {
            null
        }
        return ArchiveAlbum(
            albumKey = "$source:$bvid#$page",
            source = source,
            bvid = bvid,
            page = page,
            pageCount = pageCount,
            cidHint = cid,
            title = title,
            artist = artist,
            durationMs = durationMs,
            updatedAt = updatedAt,
            createdAt = createdAt,
            lastPlayedAt = lastPlayedAt,
            playCount = playCount,
            cover = cover,
            segments = tracks.sortedBy { it.startMs }.map { track ->
                ArchiveSegment(
                    id = track.uid.ifBlank { UUID.randomUUID().toString() },
                    startMs = track.startMs,
                    endMs = track.endMs.takeIf { it > track.startMs },
                    title = track.title,
                )
            },
        )
    }

    private fun readCoverBytes(path: String?): ByteArray? {
        val file = path?.let(::File) ?: return null
        if (!file.isFile) return null
        return runCatching { file.readBytes() }.getOrNull()?.takeIf { it.isNotEmpty() && it.size <= MAX_COVER_BYTES }
    }

    private fun detectMime(bytes: ByteArray): String = when {
        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "image/jpeg"
        bytes.size >= 4 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() -> "image/png"
        bytes.size >= 12 && bytes.copyOfRange(0, 4).contentEquals("RIFF".toByteArray()) -> "image/webp"
        else -> "image/jpeg"
    }

    // ------------------------------------------------------------------
    // 单 JSON 预览/导入（仅 album / segments 分享文件）
    // ------------------------------------------------------------------

    override suspend fun preview(text: String, mode: ArchiveImportMode): ArchivePreview {
        val parsed = codec.parse(text)
        requireShareKind(parsed.archive)
        return previewLibrary(parsed.archive, mode, parsed.recognizedAs, parsed.errors)
    }

    override suspend fun importArchive(text: String, mode: ArchiveImportMode): ArchiveImportReport {
        val parsed = codec.parse(text)
        requireShareKind(parsed.archive)
        return importParsed(
            library = parsed.archive,
            settings = null,
            covers = emptyMap(),
            errors = parsed.errors,
            mode = mode,
        )
    }

    private fun requireShareKind(archive: LibraryArchive) {
        if (archive.kind == ArchiveKind.LIBRARY) {
            throw ArchiveFormatException("整库备份现在是目录 bundle（manifest/library/settings/covers），不支持单 JSON 导入")
        }
    }

    // ------------------------------------------------------------------
    // 目录 bundle 导出/导入
    // ------------------------------------------------------------------

    override suspend fun importTreeEntries(
        entries: List<ArchiveTreeEntry>,
        mode: ArchiveImportMode,
    ): ArchiveImportReport {
        val contents = try {
            bundleCodec.parseEntries(entries)
        } catch (e: ArchiveFormatException) {
            return ArchiveImportReport(
                mode = mode,
                items = emptyList(),
                parseErrors = listOf(ArchiveParseError("", e.message ?: "bundle 校验失败")),
            )
        }
        val playlistWarnings = if (contents.playlists?.playlists.isNullOrEmpty()) {
            emptyList()
        } else {
            listOf(
                ArchiveParseError(
                    "playlists.json",
                    "当前版本还不支持导入歌单，已忽略 ${contents.playlists!!.playlists.size} 个歌单",
                ),
            )
        }
        return importParsed(
            library = contents.library,
            settings = contents.settings,
            covers = contents.covers,
            errors = playlistWarnings,
            mode = mode,
        )
    }

    private suspend fun previewLibrary(
        library: LibraryArchive,
        mode: ArchiveImportMode,
        recognizedAs: String,
        errors: List<ArchiveParseError>,
    ): ArchivePreview {
        val items = library.albums.map { album ->
            val offlineBvid = resolveBvidOffline(album)
            val existing = offlineBvid?.let {
                albumRepository.getAlbumBySourceKey(album.source, it, album.page)
            }
            val planned = when {
                mode == ArchiveImportMode.SEGMENTS_ONLY && existing == null -> ArchiveItemAction.SKIPPED
                existing == null -> ArchiveItemAction.CREATED
                mode == ArchiveImportMode.SEGMENTS_ONLY -> ArchiveItemAction.SEGMENTS_UPDATED
                else -> ArchiveItemAction.UPDATED
            }
            ArchivePreviewItem(
                albumKey = album.albumKey ?: displayKey(album),
                title = album.title ?: album.bvid ?: album.url.orEmpty(),
                segmentCount = album.segments?.size ?: 0,
                exists = existing != null,
                plannedAction = planned,
                note = if (album.segments == null) "未包含分曲：更新时保留原分曲" else null,
            )
        }
        return ArchivePreview(
            recognizedAs = recognizedAs,
            kind = library.kind,
            albumCount = library.albums.size,
            trackCount = library.albums.sumOf { it.segments?.size ?: 0 },
            hasSettings = false,
            items = items,
            parseErrors = errors,
        )
    }

    private suspend fun importParsed(
        library: LibraryArchive,
        settings: ArchiveSettingsDocument?,
        covers: Map<String, ByteArray>,
        errors: List<ArchiveParseError>,
        mode: ArchiveImportMode,
    ): ArchiveImportReport {
        val startMs = System.currentTimeMillis()
        Diag.log(TAG, "archive import START mode=$mode albums=${library.albums.size}")
        var backupFilePath: String? = null

        if (mode == ArchiveImportMode.REPLACE) {
            backupFilePath = runCatching { createAutoBackup() }.getOrNull()
            albumRepository.albums.first().forEach { album ->
                runCatching { albumRepository.deleteAlbum(album.id) }
            }
        }
        if (mode == ArchiveImportMode.REPLACE && settings != null) {
            applySettings(settings)
        }

        val results = ArrayList<ArchiveItemResult>(library.albums.size)
        for (archiveAlbum in library.albums) {
            results += importOne(archiveAlbum, covers, mode)
        }
        val report = ArchiveImportReport(
            mode = mode,
            items = results,
            parseErrors = errors,
            backupFilePath = backupFilePath,
        )
        Diag.log(
            TAG,
            "archive import DONE mode=$mode created=${report.createdCount} updated=${report.updatedCount} " +
                "segmentsUpdated=${report.segmentsUpdatedCount} skipped=${report.skippedCount} " +
                "failed=${report.failedCount} backup=${backupFilePath ?: "-"} in ${System.currentTimeMillis() - startMs}ms",
        )
        return report
    }

    private suspend fun importOne(
        archiveAlbum: ArchiveAlbum,
        covers: Map<String, ByteArray>,
        mode: ArchiveImportMode,
    ): ArchiveItemResult {
        val bvid = resolveBvidOnline(archiveAlbum)
            ?: return itemResult(archiveAlbum, ArchiveItemAction.FAILED, "无法解析视频地址（需要联网获取 BV 号）")
        val existing = albumRepository.getAlbumBySourceKey(archiveAlbum.source, bvid, archiveAlbum.page)
        return when {
            mode == ArchiveImportMode.SEGMENTS_ONLY -> {
                if (existing == null) {
                    itemResult(archiveAlbum, ArchiveItemAction.SKIPPED, "本地没有这张专辑，已跳过分割点更新")
                } else {
                    val segments = archiveAlbum.segments
                    if (segments == null) {
                        itemResult(archiveAlbum, ArchiveItemAction.SKIPPED, "文件未包含分曲，已跳过")
                    } else if (applyTracks(existing, segments)) {
                        itemResult(
                            archiveAlbum,
                            ArchiveItemAction.SEGMENTS_UPDATED,
                            if (segments.isEmpty()) "已清空分曲（恢复未分割）" else "已更新 ${segments.size} 个分曲",
                        )
                    } else {
                        itemResult(archiveAlbum, ArchiveItemAction.FAILED, "分曲时间点无效")
                    }
                }
            }
            existing == null -> createNew(archiveAlbum, bvid, covers)
            else -> updateExisting(existing, archiveAlbum, covers)
        }
    }

    private suspend fun createNew(
        archiveAlbum: ArchiveAlbum,
        bvid: String,
        covers: Map<String, ByteArray>,
    ): ArchiveItemResult {
        val request = AlbumImportRequest(
            bvidOrUrl = bvid,
            albumTitle = archiveAlbum.title,
            artist = archiveAlbum.artist,
            mode = ImportMode.SINGLE_VIDEO,
            page = archiveAlbum.page,
            segments = emptyList(),
        )
        return when (val result = albumRepository.importAlbum(request)) {
            is ImportResult.Success -> {
                albumRepository.getAlbum(result.albumId)?.let { created ->
                    val merged = created.copy(
                        source = archiveAlbum.source,
                        title = archiveAlbum.title ?: created.title,
                        artist = archiveAlbum.artist ?: created.artist,
                        pageCount = archiveAlbum.pageCount ?: created.pageCount,
                        durationMs = archiveAlbum.durationMs ?: created.durationMs,
                        updatedAt = archiveAlbum.updatedAt ?: System.currentTimeMillis(),
                        createdAt = archiveAlbum.createdAt ?: created.createdAt,
                        lastPlayedAt = archiveAlbum.lastPlayedAt ?: created.lastPlayedAt,
                        playCount = archiveAlbum.playCount ?: created.playCount,
                    )
                    albumRepository.updateAlbum(merged)
                    archiveAlbum.segments?.let { applyTracks(merged, it) }
                }
                applyCover(result.albumId, archiveAlbum.cover, covers)
                itemResult(archiveAlbum, ArchiveItemAction.CREATED, "已创建，共 ${archiveAlbum.segments?.size ?: 0} 首")
            }
            is ImportResult.Failure -> itemResult(archiveAlbum, ArchiveItemAction.FAILED, result.message)
        }
    }

    private suspend fun updateExisting(
        existing: Album,
        archiveAlbum: ArchiveAlbum,
        covers: Map<String, ByteArray>,
    ): ArchiveItemResult {
        val merged = existing.copy(
            title = archiveAlbum.title ?: existing.title,
            artist = archiveAlbum.artist ?: existing.artist,
            pageCount = archiveAlbum.pageCount ?: existing.pageCount,
            durationMs = archiveAlbum.durationMs ?: existing.durationMs,
            updatedAt = archiveAlbum.updatedAt ?: System.currentTimeMillis(),
            createdAt = archiveAlbum.createdAt ?: existing.createdAt,
            lastPlayedAt = maxOf(existing.lastPlayedAt ?: 0L, archiveAlbum.lastPlayedAt ?: 0L).takeIf { it > 0 },
            playCount = maxOf(existing.playCount, archiveAlbum.playCount ?: 0),
        )
        albumRepository.updateAlbum(merged)
        applyCover(existing.id, archiveAlbum.cover, covers)
        val segments = archiveAlbum.segments
        val message = when {
            segments == null -> "已更新元信息（文件未包含分曲，保留原分曲）"
            applyTracks(merged, segments) ->
                if (segments.isEmpty()) "已清空分曲（恢复未分割）" else "已更新为 ${segments.size} 个分曲"
            else -> return itemResult(archiveAlbum, ArchiveItemAction.FAILED, "分曲时间点无效")
        }
        return itemResult(archiveAlbum, ArchiveItemAction.UPDATED, message)
    }

    private suspend fun applyTracks(album: Album, segments: List<ArchiveSegment>): Boolean {
        if (segments.isEmpty()) {
            albumRepository.replaceTracks(album.id, emptyList())
            return true
        }
        val duration = album.durationMs
        val tracks = segments.mapIndexed { index, segment ->
            val endMs = segment.endMs ?: segments.getOrNull(index + 1)?.startMs ?: duration
            if (endMs <= segment.startMs) return false
            Track(
                id = 0L,
                uid = segment.id ?: UUID.randomUUID().toString(),
                albumId = album.id,
                index = index,
                title = segment.title ?: "曲目 ${index + 1}",
                startMs = segment.startMs,
                endMs = endMs,
                sourceCid = segment.sourceCid ?: 0L,
                sourcePage = segment.sourcePage ?: 0,
                sourceOffsetMs = segment.sourceOffsetMs,
            )
        }
        albumRepository.replaceTracks(album.id, tracks)
        return true
    }

    private suspend fun applyCover(albumId: Long, cover: ArchiveCover?, covers: Map<String, ByteArray>) {
        cover ?: return
        val bytes = if (cover.dataBase64 != null) {
            runCatching { cover.dataBase64.filterNot { it.isWhitespace() }.decodeBase64() }
                .getOrNull()?.toByteArray()
        } else {
            covers[cover.hash]
        } ?: return
        if (bytes.isEmpty() || bytes.size > MAX_COVER_BYTES) return
        if (!LibraryArchiveCodec.sha256Hex(bytes).equals(cover.hash, ignoreCase = true)) {
            Diag.log(TAG, "archive cover hash mismatch album=$albumId, skipped")
            return
        }
        val path = albumCoverRepository.saveAlbumCover(albumId, bytes) ?: return
        albumRepository.updateAlbumCover(albumId, path)
        Diag.log(TAG, "archive cover applied album=$albumId bytes=${bytes.size}")
    }

    private suspend fun applySettings(settings: ArchiveSettingsDocument) {
        val quality = settings.audioQuality?.let { name ->
            runCatching { AudioQualityPreference.valueOf(name) }.getOrNull()
        }
        settings.darkTheme?.let { settingsRepository.setDarkTheme(it) }
        settings.diagnosticLogsEnabled?.let { settingsRepository.setDiagnosticLogsEnabled(it) }
        quality?.let { settingsRepository.setAudioQuality(it) }
    }

    // ------------------------------------------------------------------
    // 自动备份 / 工具
    // ------------------------------------------------------------------

    private suspend fun createAutoBackup(): String? {
        val root = backupDir ?: return null
        val dir = File(root, "biliaudio-backup-${timestampForFile()}")
        dir.mkdirs()
        val entries = exportTreeEntries()
        for (entry in entries) {
            val file = File(dir, entry.relativePath)
            file.parentFile?.mkdirs()
            file.writeBytes(entry.bytes)
        }
        pruneBackups(root)
        return dir.absolutePath
    }

    override fun lastAutoBackupAt(): Long? =
        backupDir?.listFiles()?.filter { it.isDirectory }?.maxOfOrNull { it.lastModified() }

    private fun pruneBackups(root: File) {
        root.listFiles()?.filter { it.isDirectory }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(10)
            ?.forEach { runCatching { it.deleteRecursively() } }
    }

    private suspend fun resolveBvidOnline(archiveAlbum: ArchiveAlbum): String? {
        archiveAlbum.bvid?.let { return it }
        val raw = archiveAlbum.url?.trim().orEmpty()
        if (raw.isEmpty()) return null
        return runCatching { biliRepository.previewVideo(raw).bvid }.getOrNull()
    }

    private fun resolveBvidOffline(archiveAlbum: ArchiveAlbum): String? {
        archiveAlbum.bvid?.let { return it }
        val raw = archiveAlbum.url?.trim().orEmpty()
        if (raw.isEmpty()) return null
        return (BiliUrlParser.parse(raw) as? BiliUrlParser.VideoRef.Bvid)?.bvid
    }

    private fun displayKey(album: ArchiveAlbum): String =
        album.bvid?.let { "${album.source}:$it#${album.page}" } ?: album.url.orEmpty()

    private fun itemResult(album: ArchiveAlbum, action: ArchiveItemAction, message: String? = null): ArchiveItemResult =
        ArchiveItemResult(
            albumKey = album.albumKey ?: displayKey(album),
            title = album.title ?: album.bvid ?: album.url.orEmpty(),
            action = action,
            message = message,
        )

    private fun nowIso(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date())

    private fun timestampForFile(): String =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
}

private const val TAG = "Archive"
private const val MAX_COVER_BYTES = 2 * 1024 * 1024

/** 无封面仓库：用于不关心封面的测试/纯数据场景。 */
internal object NoopAlbumCoverRepository : AlbumCoverRepository {
    override suspend fun searchNetEaseAlbums(query: String, limit: Int): List<com.wonderlandkid.biliaudio.data.model.CoverCandidate> = emptyList()
    override suspend fun fetchCoverBytes(url: String): ByteArray? = null
    override suspend fun saveAlbumCover(albumId: Long, bytes: ByteArray): String? = null
    override fun deleteAlbumCoverFile(path: String?) = Unit
}
