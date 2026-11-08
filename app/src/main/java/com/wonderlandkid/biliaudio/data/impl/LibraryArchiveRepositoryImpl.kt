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
import com.wonderlandkid.biliaudio.data.model.ArchiveParseError
import com.wonderlandkid.biliaudio.data.model.ArchivePreview
import com.wonderlandkid.biliaudio.data.model.ArchivePreviewItem
import com.wonderlandkid.biliaudio.data.model.ArchiveSegment
import com.wonderlandkid.biliaudio.data.model.ArchiveSettings
import com.wonderlandkid.biliaudio.data.model.ArchiveTreeEntry
import com.wonderlandkid.biliaudio.data.model.AudioQualityPreference
import com.wonderlandkid.biliaudio.data.model.ImportMode
import com.wonderlandkid.biliaudio.data.model.ImportResult
import com.wonderlandkid.biliaudio.data.model.LibraryArchive
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
 * 音乐库归档仓库实现（v2，docs/ARCHIVE_SCHEMA_V2.md）。
 *
 * - 导出：Room 专辑/分曲 → v2 归档（albumKey / segment.id / cover sha256 / kind / settings）；
 * - 目录形态：根索引 kind=index + 每专辑 album.json / segments.json；
 * - 导入：按 albumKey=source:bvid#page upsert，覆盖全库前自动备份；
 * - v2 不兼容旧 v1 归档（旧文件请用旧版 App 重新导出）。
 */
class LibraryArchiveRepositoryImpl(
    private val backupDir: File?,
    private val albumRepository: AlbumRepository,
    private val biliRepository: BiliRepository,
    private val settingsRepository: SettingsRepository,
    private val albumCoverRepository: AlbumCoverRepository = NoopAlbumCoverRepository,
    private val codec: LibraryArchiveCodec = LibraryArchiveCodec(),
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

    override suspend fun exportAllJson(): String {
        val albums = albumRepository.albums.first().map { album ->
            album.toArchiveAlbum(albumRepository.getTracks(album.id), withSegments = true)
        }
        val archive = LibraryArchive.create(
            kind = ArchiveKind.LIBRARY,
            albums = albums,
            settings = currentArchiveSettings(),
            exportedAt = nowIso(),
            appVersion = BuildConfig.VERSION_NAME,
        )
        return codec.encode(archive).also { Diag.log(TAG, "archive export all size=${it.length}B") }
    }

    override suspend fun exportAlbumJson(albumId: Long): String? {
        val album = albumRepository.getAlbum(albumId) ?: return null
        val archive = LibraryArchive.create(
            kind = ArchiveKind.ALBUM,
            albums = listOf(album.toArchiveAlbum(albumRepository.getTracks(albumId), withSegments = true)),
            exportedAt = nowIso(),
            appVersion = BuildConfig.VERSION_NAME,
        )
        return codec.encode(archive)
    }

    /** 目录形态：根索引 kind=index；专辑元信息与分割点分文件。 */
    override suspend fun exportTreeEntries(): List<ArchiveTreeEntry> {
        val albums = albumRepository.albums.first()
        val entries = ArrayList<ArchiveTreeEntry>(albums.size * 2 + 1)
        entries += treeEntry(
            relativePath = ROOT_INDEX_FILE,
            archive = LibraryArchive.create(
                kind = ArchiveKind.INDEX,
                albums = emptyList(),
                settings = currentArchiveSettings(),
                exportedAt = nowIso(),
                appVersion = BuildConfig.VERSION_NAME,
            ),
        )

        val usedAlbumDirNames = HashMap<String, MutableSet<String>>()
        for (album in albums) {
            val tracks = albumRepository.getTracks(album.id)
            val fullAlbum = album.toArchiveAlbum(tracks, withSegments = true)
            val artistDir = sanitizePathSegment(album.artist, FALLBACK_ARTIST)
            val albumDirBase = sanitizePathSegment(album.title, FALLBACK_ALBUM)
            val usedNames = usedAlbumDirNames.getOrPut(artistDir.lowercase(Locale.ROOT)) { mutableSetOf() }
            val albumDir = uniqueChildName(albumDirBase, album.bvid, usedNames)
            val dir = "$artistDir/$albumDir"

            // album.json：元信息（segments 字段省略 = 更新时保留；也是目录形态的分工约定）
            entries += treeEntry(
                relativePath = "$dir/$ALBUM_FILE",
                archive = LibraryArchive.create(
                    kind = ArchiveKind.ALBUM,
                    albums = listOf(fullAlbum.copy(segments = null)),
                    exportedAt = nowIso(),
                    appVersion = BuildConfig.VERSION_NAME,
                ),
            )
            // segments.json：仅定位信息 + 分割点；未分割专辑写空数组（=清空/未分割）
            entries += treeEntry(
                relativePath = "$dir/$SEGMENTS_FILE",
                archive = LibraryArchive.create(
                    kind = ArchiveKind.SEGMENTS,
                    albums = listOf(
                        ArchiveAlbum(
                            albumKey = fullAlbum.albumKey,
                            source = fullAlbum.source,
                            bvid = fullAlbum.bvid,
                            page = fullAlbum.page,
                            segments = fullAlbum.segments ?: emptyList(),
                        ),
                    ),
                    exportedAt = nowIso(),
                    appVersion = BuildConfig.VERSION_NAME,
                ),
            )
        }
        return entries.sortedBy { it.relativePath }
    }

    private fun treeEntry(relativePath: String, archive: LibraryArchive): ArchiveTreeEntry =
        ArchiveTreeEntry(
            relativePath = relativePath,
            bytes = codec.encode(archive).toByteArray(Charsets.UTF_8),
            mimeType = MIME_TYPE_JSON,
        )

    private fun Album.toArchiveAlbum(tracks: List<Track>, withSegments: Boolean): ArchiveAlbum = ArchiveAlbum(
        albumKey = sourceKey(source, bvid, page),
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
        cover = readCover(coverLocalPath),
        segments = if (!withSegments) null else tracks.sortedBy { it.startMs }.map { track ->
            ArchiveSegment(
                id = track.uid.ifBlank { UUID.randomUUID().toString() },
                startMs = track.startMs,
                endMs = track.endMs.takeIf { it > track.startMs },
                title = track.title,
            )
        },
    )

    private fun readCover(path: String?): ArchiveCover? {
        val file = path?.let(::File) ?: return null
        if (!file.isFile) return null
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        if (bytes.isEmpty() || bytes.size > MAX_COVER_BYTES) return null
        val mime = when {
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "image/jpeg"
            bytes.size >= 4 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() -> "image/png"
            bytes.size >= 12 && bytes.copyOfRange(0, 4).contentEquals("RIFF".toByteArray()) -> "image/webp"
            else -> "image/jpeg"
        }
        return ArchiveCover(
            mime = mime,
            sha256 = LibraryArchiveCodec.sha256Hex(bytes),
            byteLength = bytes.size,
            dataBase64 = okio.ByteString.of(*bytes).base64(),
        )
    }

    private fun currentArchiveSettings(): ArchiveSettings {
        val settings = settingsRepository.settings.value
        return ArchiveSettings(
            version = 1,
            audioQuality = settings.audioQuality.name,
            darkTheme = settings.darkTheme,
            diagnosticLogsEnabled = settings.diagnosticLogsEnabled,
        )
    }

    // ------------------------------------------------------------------
    // 预览
    // ------------------------------------------------------------------

    override suspend fun preview(text: String, mode: ArchiveImportMode): ArchivePreview {
        val parsed = codec.parse(text)
        requireImportableKind(parsed.archive)
        val items = parsed.archive.albums.map { album ->
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
            recognizedAs = parsed.recognizedAs,
            kind = parsed.archive.kind,
            albumCount = parsed.archive.albums.size,
            trackCount = parsed.archive.albums.sumOf { it.segments?.size ?: 0 },
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
        requireImportableKind(parsed.archive)
        return importParsed(parsed, mode)
    }

    private fun requireImportableKind(archive: LibraryArchive) {
        if (archive.kind == ArchiveKind.INDEX) {
            throw ArchiveFormatException("这是目录归档的索引文件，不能作为音乐库/专辑直接导入")
        }
    }

    /**
     * 目录形态导入：
     * 1. 根索引只取 settings；
     * 2. 每个目录合并 album.json（kind=album）+ segments.json（kind=segments）；
     * 3. 合并后做一次全局 v2 校验（重复 albumKey、分曲边界等）；
     * 4. 错误逐条记录，不中断整体导入。
     */
    override suspend fun importTreeEntries(
        entries: List<ArchiveTreeEntry>,
        mode: ArchiveImportMode,
    ): ArchiveImportReport {
        val parseErrors = mutableListOf<ArchiveParseError>()
        var rootSettings: ArchiveSettings? = null
        val filesByDir = LinkedHashMap<String, MutableList<Pair<String, ArchiveTreeEntry>>>()

        for (entry in entries) {
            val path = normalizeTreePath(entry.relativePath) ?: continue
            val fileName = path.substringAfterLast('/')
            if (fileName.equals(ROOT_INDEX_FILE, ignoreCase = true)) {
                val archive = parseTreeFile(entry, path, parseErrors) ?: continue
                if (archive.kind != ArchiveKind.INDEX) {
                    parseErrors += ArchiveParseError(path, "根索引文件 kind 应为 index，实际 ${archive.kind}")
                    continue
                }
                rootSettings = archive.settings
                continue
            }
            if (!fileName.equals(ALBUM_FILE, ignoreCase = true) && !fileName.equals(SEGMENTS_FILE, ignoreCase = true)) {
                continue
            }
            val dir = path.substringBeforeLast('/', missingDelimiterValue = "")
            filesByDir.getOrPut(dir) { mutableListOf() } += path to entry
        }

        val mergedAlbums = ArrayList<ArchiveAlbum>(filesByDir.size)
        for ((dir, files) in filesByDir.entries.sortedBy { it.key }) {
            var meta: ArchiveAlbum? = null
            var segments: ArchiveAlbum? = null
            for ((path, entry) in files) {
                val isAlbum = path.substringAfterLast('/').equals(ALBUM_FILE, ignoreCase = true)
                val expectedKind = if (isAlbum) ArchiveKind.ALBUM else ArchiveKind.SEGMENTS
                val archive = parseTreeFile(entry, path, parseErrors, expectedKind) ?: continue
                when {
                    isAlbum && meta == null -> meta = archive.albums.first()
                    !isAlbum && segments == null -> segments = archive.albums.first()
                }
            }
            when {
                meta != null && segments != null -> {
                    if (!sameAlbum(meta, segments)) {
                        parseErrors += ArchiveParseError(
                            "$dir/$SEGMENTS_FILE",
                            "与 album.json 不是同一张专辑，已按 album.json 合并",
                        )
                    }
                    mergedAlbums += meta.copy(segments = segments.segments)
                }
                meta != null -> mergedAlbums += meta
                segments != null -> mergedAlbums += segments
            }
        }

        if (filesByDir.isEmpty()) {
            parseErrors += ArchiveParseError("", "目录中未找到 album.json 或 segments.json")
            return ArchiveImportReport(mode = mode, items = emptyList(), parseErrors = parseErrors)
        }
        if (mergedAlbums.isEmpty() && mode == ArchiveImportMode.REPLACE) {
            parseErrors += ArchiveParseError("", "没有可导入的有效专辑，已取消覆盖全库")
            return ArchiveImportReport(mode = mode, items = emptyList(), parseErrors = parseErrors)
        }

        val mergedArchive = try {
            codec.validateAndNormalize(
                LibraryArchive.create(
                    kind = ArchiveKind.LIBRARY,
                    albums = mergedAlbums,
                    settings = rootSettings,
                    exportedAt = nowIso(),
                    appVersion = BuildConfig.VERSION_NAME,
                ),
            )
        } catch (e: ArchiveFormatException) {
            parseErrors += ArchiveParseError("", e.message ?: "目录归档校验失败")
            if (mode == ArchiveImportMode.REPLACE) {
                return ArchiveImportReport(mode = mode, items = emptyList(), parseErrors = parseErrors)
            }
            LibraryArchive.create(kind = ArchiveKind.LIBRARY, albums = emptyList(), settings = rootSettings)
        }
        Diag.log(TAG, "archive tree import dirs=${filesByDir.size} albums=${mergedArchive.albums.size} errors=${parseErrors.size}")
        return importParsed(
            ArchiveParseResult(archive = mergedArchive, recognizedAs = "tree-directory", errors = parseErrors),
            mode,
        )
    }

    private suspend fun importParsed(parsed: ArchiveParseResult, mode: ArchiveImportMode): ArchiveImportReport {
        val startMs = System.currentTimeMillis()
        Diag.log(TAG, "archive import START mode=$mode kind=${parsed.archive.kind} albums=${parsed.archive.albums.size}")
        var backupFilePath: String? = null

        if (mode == ArchiveImportMode.REPLACE) {
            backupFilePath = runCatching { createAutoBackup() }.getOrNull()
            albumRepository.albums.first().forEach { album ->
                runCatching { albumRepository.deleteAlbum(album.id) }
            }
        }
        if (mode != ArchiveImportMode.SEGMENTS_ONLY) {
            parsed.archive.settings?.let { settings ->
                applySettings(settings)
            }
        }

        val results = ArrayList<ArchiveItemResult>(parsed.archive.albums.size)
        for (archiveAlbum in parsed.archive.albums) {
            results += importOne(archiveAlbum, mode)
        }
        val report = ArchiveImportReport(
            mode = mode,
            items = results,
            parseErrors = parsed.errors,
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

    private suspend fun importOne(archiveAlbum: ArchiveAlbum, mode: ArchiveImportMode): ArchiveItemResult {
        val bvid = resolveBvidOnline(archiveAlbum)
            ?: return itemResult(archiveAlbum, ArchiveItemAction.FAILED, "无法解析视频地址（需要联网获取 BV 号）")
        val source = archiveAlbum.source
        val page = archiveAlbum.page
        val existing = albumRepository.getAlbumBySourceKey(source, bvid, page)

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
            existing == null -> createNew(archiveAlbum, bvid)
            else -> updateExisting(existing, archiveAlbum)
        }
    }

    private suspend fun createNew(archiveAlbum: ArchiveAlbum, bvid: String): ArchiveItemResult {
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
                applyCover(result.albumId, archiveAlbum.cover)
                itemResult(archiveAlbum, ArchiveItemAction.CREATED, "已创建，共 ${archiveAlbum.segments?.size ?: 0} 首")
            }
            is ImportResult.Failure -> itemResult(archiveAlbum, ArchiveItemAction.FAILED, result.message)
        }
    }

    private suspend fun updateExisting(existing: Album, archiveAlbum: ArchiveAlbum): ArchiveItemResult {
        val duration = archiveAlbum.durationMs ?: existing.durationMs
        val merged = existing.copy(
            title = archiveAlbum.title ?: existing.title,
            artist = archiveAlbum.artist ?: existing.artist,
            pageCount = archiveAlbum.pageCount ?: existing.pageCount,
            durationMs = duration,
            updatedAt = archiveAlbum.updatedAt ?: System.currentTimeMillis(),
            createdAt = archiveAlbum.createdAt ?: existing.createdAt,
            lastPlayedAt = maxOf(existing.lastPlayedAt ?: 0L, archiveAlbum.lastPlayedAt ?: 0L).takeIf { it > 0 },
            playCount = maxOf(existing.playCount, archiveAlbum.playCount ?: 0),
        )
        albumRepository.updateAlbum(merged)
        applyCover(existing.id, archiveAlbum.cover)

        val segments = archiveAlbum.segments
        val message = when {
            segments == null -> "已更新元信息（文件未包含分曲，保留原分曲）"
            applyTracks(merged, segments) ->
                if (segments.isEmpty()) "已清空分曲（恢复未分割）" else "已更新为 ${segments.size} 个分曲"
            else -> return itemResult(archiveAlbum, ArchiveItemAction.FAILED, "分曲时间点无效")
        }
        return itemResult(archiveAlbum, ArchiveItemAction.UPDATED, message)
    }

    /** 用归档分曲替换本地 tracks；segments 为空数组表示清空。 */
    private suspend fun applyTracks(album: Album, segments: List<ArchiveSegment>): Boolean {
        if (segments.isEmpty()) {
            albumRepository.replaceTracks(album.id, emptyList())
            return true
        }
        val duration = album.durationMs
        val tracks = segments.mapIndexed { index, segment ->
            val endMs = segment.endMs
                ?: segments.getOrNull(index + 1)?.startMs
                ?: duration
            if (endMs <= segment.startMs) return false
            Track(
                id = 0L,
                uid = segment.id ?: UUID.randomUUID().toString(),
                albumId = album.id,
                index = index,
                title = segment.title ?: "曲目 ${index + 1}",
                startMs = segment.startMs,
                endMs = endMs,
            )
        }
        albumRepository.replaceTracks(album.id, tracks)
        return true
    }

    private suspend fun applyCover(albumId: Long, cover: ArchiveCover?) {
        cover ?: return
        val bytes = runCatching {
            cover.dataBase64.filterNot { it.isWhitespace() }.decodeBase64()
        }.getOrNull()?.toByteArray() ?: return
        if (bytes.isEmpty() || bytes.size > MAX_COVER_BYTES) return
        if (!LibraryArchiveCodec.sha256Hex(bytes).equals(cover.sha256, ignoreCase = true)) {
            Diag.log(TAG, "archive cover hash mismatch album=$albumId, skipped")
            return
        }
        val path = albumCoverRepository.saveAlbumCover(albumId, bytes) ?: return
        albumRepository.updateAlbumCover(albumId, path)
        Diag.log(TAG, "archive cover applied album=$albumId bytes=${bytes.size}")
    }

    private suspend fun applySettings(settings: ArchiveSettings) {
        settings.audioQuality?.let { name ->
            runCatching { AudioQualityPreference.valueOf(name) }.getOrNull()?.let {
                settingsRepository.setAudioQuality(it)
            }
        }
        settings.darkTheme?.let { settingsRepository.setDarkTheme(it) }
        settings.diagnosticLogsEnabled?.let { settingsRepository.setDiagnosticLogsEnabled(it) }
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private suspend fun parseTreeFile(
        entry: ArchiveTreeEntry,
        path: String,
        errors: MutableList<ArchiveParseError>,
        expectedKind: ArchiveKind? = null,
    ): LibraryArchive? {
        val text = runCatching { entry.bytes.toString(Charsets.UTF_8) }.getOrNull()
        if (text.isNullOrBlank()) {
            errors += ArchiveParseError(path, "文件内容为空")
            return null
        }
        return try {
            codec.parse(text, expectedKind).archive
        } catch (e: ArchiveFormatException) {
            errors += ArchiveParseError(path, e.message ?: "文件格式不正确")
            null
        } catch (e: Exception) {
            errors += ArchiveParseError(path, "解析失败：${e.message ?: "未知错误"}")
            null
        }
    }

    private fun sameAlbum(meta: ArchiveAlbum, segments: ArchiveAlbum): Boolean {
        val metaKey = meta.albumKey ?: meta.bvid ?: meta.url
        val segKey = segments.albumKey ?: segments.bvid ?: segments.url
        return metaKey == segKey && meta.page == segments.page
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
        album.bvid?.let { sourceKey(album.source, it, album.page) } ?: album.url.orEmpty()

    private fun sourceKey(source: String, bvid: String, page: Int): String = "$source:$bvid#$page"

    private fun itemResult(album: ArchiveAlbum, action: ArchiveItemAction, message: String? = null): ArchiveItemResult =
        ArchiveItemResult(
            albumKey = album.albumKey ?: displayKey(album),
            title = album.title ?: album.bvid ?: album.url.orEmpty(),
            action = action,
            message = message,
        )

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

    private fun normalizeTreePath(rawPath: String): String? {
        val parts = rawPath.replace('\\', '/').split('/')
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != "." && it != ".." }
        if (parts.isEmpty()) return null
        return parts.joinToString("/")
    }

    private fun sanitizePathSegment(raw: String?, fallback: String): String {
        val source = raw?.trim().orEmpty()
        val cleaned = buildString(source.length) {
            for (char in source) {
                when {
                    char.code < 0x20 || char.code == 0x7F -> Unit
                    char in ILLEGAL_PATH_CHARS -> Unit
                    else -> append(char)
                }
            }
        }.replace(WHITESPACE_REGEX, " ").trim().trim('.', ' ')
        return truncateCodePoints(cleaned, MAX_PATH_SEGMENT_LENGTH).ifBlank { fallback }
    }

    private fun truncateCodePoints(text: String, maxCodePoints: Int): String {
        if (text.length <= maxCodePoints) return text
        var end = 0
        var count = 0
        while (end < text.length && count < maxCodePoints) {
            end += Character.charCount(text.codePointAt(end))
            count++
        }
        return text.substring(0, end)
    }

    private fun uniqueChildName(desired: String, bvid: String, usedNames: MutableSet<String>): String {
        fun reserve(candidate: String): Boolean = usedNames.add(candidate.lowercase(Locale.ROOT))
        if (reserve(desired)) return desired
        for (index in 2..99) {
            val candidate = "$desired ($index)"
            if (reserve(candidate)) return candidate
        }
        val withBvid = "$desired (${bvid.ifBlank { "album" }})"
        if (reserve(withBvid)) return withBvid
        var index = 2
        while (true) {
            val candidate = "$withBvid ($index)"
            if (reserve(candidate)) return candidate
            index++
        }
    }

    private fun nowIso(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date())

    private fun timestampForFile(): String =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
}

private const val TAG = "Archive"
private const val MIME_TYPE_JSON = "application/json"
private const val ROOT_INDEX_FILE = "biliaudio-library.json"
private const val ALBUM_FILE = "album.json"
private const val SEGMENTS_FILE = "segments.json"
private const val FALLBACK_ARTIST = "未知艺术家"
private const val FALLBACK_ALBUM = "未命名专辑"
private const val MAX_PATH_SEGMENT_LENGTH = 60
private const val MAX_COVER_BYTES = 2 * 1024 * 1024
private val ILLEGAL_PATH_CHARS = charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')
private val WHITESPACE_REGEX = Regex("\\s+")

/** 无封面仓库：用于不关心封面的测试/纯数据场景。 */
internal object NoopAlbumCoverRepository : AlbumCoverRepository {
    override suspend fun searchNetEaseAlbums(query: String, limit: Int): List<com.wonderlandkid.biliaudio.data.model.CoverCandidate> = emptyList()
    override suspend fun fetchCoverBytes(url: String): ByteArray? = null
    override suspend fun saveAlbumCover(albumId: Long, bytes: ByteArray): String? = null
    override fun deleteAlbumCoverFile(path: String?) = Unit
}
