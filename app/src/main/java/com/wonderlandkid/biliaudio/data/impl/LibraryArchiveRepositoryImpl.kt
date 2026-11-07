package com.wonderlandkid.biliaudio.data.impl

import android.content.Context
import com.wonderlandkid.biliaudio.data.model.Album
import com.wonderlandkid.biliaudio.data.model.AlbumImportRequest
import com.wonderlandkid.biliaudio.data.model.ArchiveAlbum
import com.wonderlandkid.biliaudio.data.model.ArchiveFormatException
import com.wonderlandkid.biliaudio.data.model.ArchiveImportMode
import com.wonderlandkid.biliaudio.data.model.ArchiveImportReport
import com.wonderlandkid.biliaudio.data.model.ArchiveItemAction
import com.wonderlandkid.biliaudio.data.model.ArchiveItemResult
import com.wonderlandkid.biliaudio.data.model.ArchiveParseError
import com.wonderlandkid.biliaudio.data.model.ArchivePreview
import com.wonderlandkid.biliaudio.data.model.ArchivePreviewItem
import com.wonderlandkid.biliaudio.data.model.ArchiveSettings
import com.wonderlandkid.biliaudio.data.model.ArchiveTreeEntry
import com.wonderlandkid.biliaudio.data.model.AudioQualityPreference
import com.wonderlandkid.biliaudio.data.model.ImportMode
import com.wonderlandkid.biliaudio.data.model.ImportResult
import com.wonderlandkid.biliaudio.data.model.LibraryArchive
import com.wonderlandkid.biliaudio.data.model.SegmentSpec
import com.wonderlandkid.biliaudio.data.model.Track
import com.wonderlandkid.biliaudio.data.net.BiliUrlParser
import com.wonderlandkid.biliaudio.util.Diag
import com.wonderlandkid.biliaudio.data.repository.AlbumCoverRepository
import com.wonderlandkid.biliaudio.data.repository.AlbumRepository
import com.wonderlandkid.biliaudio.data.repository.BiliRepository
import com.wonderlandkid.biliaudio.data.repository.LibraryArchiveRepository
import com.wonderlandkid.biliaudio.data.repository.SettingsRepository
import kotlinx.coroutines.flow.first
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 音乐库归档仓库实现（docs/LIBRARY_ARCHIVE.md）。
 *
 * - 导出：把 Room 里的专辑/分曲 + 设置序列化成归档 JSON；
 * - 目录形态（v0.6.0）：导出为「根索引 + <艺术家>/<专辑>/album.json + segments.json」的文件树，
 *   导入时按目录合并后再复用同一套落库流程（与单文件 JSON 完全兼容）；
 * - 导入：按模式（合并 / 仅分割点 / 覆盖全库）落库，覆盖全库前自动备份到 `files/backups/`；
 * - 新建专辑需要联网（通过 B 站 view 补全标题/封面/时长），已存在专辑的更新/分割点替换可离线完成。
 */
class LibraryArchiveRepositoryImpl(
    private val backupDir: File?,
    private val albumRepository: AlbumRepository,
    private val biliRepository: BiliRepository,
    private val settingsRepository: SettingsRepository,
    private val albumCoverRepository: AlbumCoverRepository = NoopAlbumCoverRepository,
    private val codec: LibraryArchiveCodec = LibraryArchiveCodec(),
) : LibraryArchiveRepository {

    /** Android 侧便捷构造：备份目录 = `files/backups`。 */
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

    override suspend fun exportAllJson(): String = codec.encode(buildLibraryArchive()).also { json ->
        Diag.log(TAG, "archive export all size=${json.length}B")
    }

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
        return LibraryArchive.create(
            albums = archiveAlbums,
            settings = currentArchiveSettings(),
            exportedAt = nowIso(),
        )
    }

    private fun currentArchiveSettings(): ArchiveSettings {
        val settings = settingsRepository.settings.value
        return ArchiveSettings(
            audioQuality = settings.audioQuality.name,
            darkTheme = settings.darkTheme,
        )
    }

    /**
     * 导出「目录形态」归档（v0.6.0）：
     * ```
     * biliaudio-library.json          // 根索引：format/version/exportedAt/settings（albums 为空）
     * <艺术家>/<专辑>/album.json       // 专辑元信息（含内嵌封面 coverBase64，segments 为空）
     * <艺术家>/<专辑>/segments.json    // 分割点（bvid/page + segments）
     * ```
     * 路径逐段安全化；同一父目录重名时追加 ` (2)` / bvid 后缀避免覆盖；结果按路径排序。
     */
    override suspend fun exportTreeEntries(): List<ArchiveTreeEntry> {
        val albums = albumRepository.albums.first()
        val entries = ArrayList<ArchiveTreeEntry>(albums.size * 2 + 1)
        // 根索引：供 UI/用户识别归档版本与设置（不包含专辑数据）
        entries += jsonTreeEntry(
            relativePath = ROOT_INDEX_FILE,
            archive = LibraryArchive.create(
                albums = emptyList(),
                settings = currentArchiveSettings(),
                exportedAt = nowIso(),
            ),
        )

        val usedAlbumDirNames = HashMap<String, MutableSet<String>>()
        for (album in albums) {
            val artistDir = sanitizePathSegment(album.artist, FALLBACK_ARTIST)
            val albumDirBase = sanitizePathSegment(album.title, FALLBACK_ALBUM)
            val usedNames = usedAlbumDirNames.getOrPut(artistDir.lowercase(Locale.ROOT)) { mutableSetOf() }
            val albumDir = uniqueChildName(albumDirBase, album.bvid, usedNames)
            val dir = "$artistDir/$albumDir"

            val fullAlbum = album.toArchiveAlbum(albumRepository.getTracks(album.id))
            // 元信息文件：segments 清空，分曲放独立 segments.json，避免重复
            entries += jsonTreeEntry(
                relativePath = "$dir/$ALBUM_FILE",
                archive = LibraryArchive.create(albums = listOf(fullAlbum.copy(segments = emptyList()))),
            )
            // 分割点文件：只保留定位信息（bvid/url/page）与分曲
            entries += jsonTreeEntry(
                relativePath = "$dir/$SEGMENTS_FILE",
                archive = LibraryArchive.create(
                    albums = listOf(
                        ArchiveAlbum(
                            bvid = fullAlbum.bvid,
                            url = fullAlbum.url,
                            page = fullAlbum.page,
                            segments = fullAlbum.segments,
                        ),
                    ),
                ),
            )
        }
        return entries.sortedBy { it.relativePath }
    }

    private fun jsonTreeEntry(relativePath: String, archive: LibraryArchive): ArchiveTreeEntry =
        ArchiveTreeEntry(
            relativePath = relativePath,
            bytes = codec.encode(archive).toByteArray(Charsets.UTF_8),
            mimeType = MIME_TYPE_JSON,
        )

    /** 目录/文件名安全化：去掉非法字符与控制字符，去首尾空白/点，限长；空值用 [fallback]。 */
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

    /** 按 Unicode 码点截断，避免把代理对切坏。 */
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

    /** 同一父目录内去重：`名字` → `名字 (2)` … → `名字 (BV号)`。 */
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
        return importParsed(parsed, mode)
    }

    /**
     * 目录形态导入（v0.6.0）：
     * 1. 根索引 `biliaudio-library.json` 只取 settings（按 [mode] 语义应用，与单文件导入一致）；
     * 2. 其它条目按所在目录分组，把 `album.json`（元信息）+ `segments.json`（分割点）合并成完整 [ArchiveAlbum]；
     * 3. 只有 segments.json 时按其中的 bvid/url/page 匹配更新（不覆盖已有元信息）；
     * 4. 非法/缺失文件只记入 [ArchiveImportReport.parseErrors]，不中断整体导入。
     */
    override suspend fun importTreeEntries(
        entries: List<ArchiveTreeEntry>,
        mode: ArchiveImportMode,
    ): ArchiveImportReport {
        val parseErrors = mutableListOf<ArchiveParseError>()
        var rootSettings: ArchiveSettings? = null
        // 目录 → [(规范化路径, 条目)]
        val filesByDir = LinkedHashMap<String, MutableList<Pair<String, ArchiveTreeEntry>>>()

        for (entry in entries) {
            val path = normalizeTreePath(entry.relativePath) ?: continue
            val fileName = path.substringAfterLast('/')
            if (fileName.equals(ROOT_INDEX_FILE, ignoreCase = true)) {
                // 根索引：只用于 settings，不导入其中的 albums
                rootSettings = parseTreeArchive(entry, path, parseErrors)?.settings
                continue
            }
            if (!fileName.equals(ALBUM_FILE, ignoreCase = true) &&
                !fileName.equals(SEGMENTS_FILE, ignoreCase = true)
            ) {
                // 忽略其它文件（README / .DS_Store / 自定义说明等）
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
                when {
                    path.substringAfterLast('/').equals(ALBUM_FILE, ignoreCase = true) -> {
                        if (meta == null) meta = parseTreeAlbum(entry, path, parseErrors)
                    }
                    path.substringAfterLast('/').equals(SEGMENTS_FILE, ignoreCase = true) -> {
                        if (segments == null) segments = parseTreeAlbum(entry, path, parseErrors)
                    }
                }
            }
            when {
                meta != null && segments != null -> {
                    if (!sameArchiveAlbum(meta, segments)) {
                        parseErrors += ArchiveParseError(
                            "$dir/$SEGMENTS_FILE",
                            "与 album.json 的专辑不一致，已按 album.json 合并",
                        )
                    }
                    mergedAlbums += meta.copy(segments = segments.segments)
                }
                meta != null -> mergedAlbums += meta
                segments != null -> mergedAlbums += segments
                else -> Unit // 两个文件都解析失败，错误已记录
            }
        }

        if (filesByDir.isEmpty()) {
            parseErrors += ArchiveParseError("", "目录中未找到 album.json 或 segments.json")
            return ArchiveImportReport(mode = mode, items = emptyList(), parseErrors = parseErrors)
        }
        if (mergedAlbums.isEmpty() && mode == ArchiveImportMode.REPLACE) {
            // 全部文件都无效时不做「覆盖全库」，避免误删用户数据
            parseErrors += ArchiveParseError("", "没有可导入的有效专辑，已取消覆盖全库")
            return ArchiveImportReport(mode = mode, items = emptyList(), parseErrors = parseErrors)
        }
        Diag.log(TAG, "archive tree import dirs=${filesByDir.size} albums=${mergedAlbums.size} errors=${parseErrors.size}")
        return importParsed(
            ArchiveParseResult(
                archive = LibraryArchive.create(albums = mergedAlbums, settings = rootSettings),
                recognizedAs = "tree-directory",
                errors = parseErrors,
            ),
            mode,
        )
    }

    private suspend fun importParsed(parsed: ArchiveParseResult, mode: ArchiveImportMode): ArchiveImportReport {
        val startMs = System.currentTimeMillis()
        Diag.log(TAG, "archive import START mode=$mode albums=${parsed.archive.albums.size} recognized=${parsed.recognizedAs}")
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
                applyArchiveCover(result.albumId, archiveAlbum)
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
        applyArchiveCover(existing.id, archiveAlbum)
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
    // 目录形态解析工具
    // ------------------------------------------------------------------

    /**
     * 规范化相对路径：统一分隔符、去掉空段 / `.` / `..`、trim；
     * 全为空时返回 null（跳过该条目）。
     */
    private fun normalizeTreePath(rawPath: String): String? {
        val parts = rawPath.replace('\\', '/').split('/')
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != "." && it != ".." }
        if (parts.isEmpty()) return null
        return parts.joinToString("/")
    }

    /** 解析目录中的一个 JSON 文件；失败记入 [parseErrors] 并返回 null。 */
    private fun parseTreeArchive(
        entry: ArchiveTreeEntry,
        path: String,
        parseErrors: MutableList<ArchiveParseError>,
    ): LibraryArchive? {
        val text = runCatching { entry.bytes.toString(Charsets.UTF_8) }.getOrNull()
        if (text.isNullOrBlank()) {
            parseErrors += ArchiveParseError(path, "文件内容为空")
            return null
        }
        return try {
            codec.parse(text).archive
        } catch (e: ArchiveFormatException) {
            parseErrors += ArchiveParseError(path, e.message ?: "文件格式不正确")
            null
        } catch (e: Exception) {
            parseErrors += ArchiveParseError(path, "解析失败：${e.message ?: "未知错误"}")
            null
        }
    }

    /** 解析 album.json / segments.json，取其中第一张专辑（多于一张时记错误仍继续）。 */
    private fun parseTreeAlbum(
        entry: ArchiveTreeEntry,
        path: String,
        parseErrors: MutableList<ArchiveParseError>,
    ): ArchiveAlbum? {
        val archive = parseTreeArchive(entry, path, parseErrors) ?: return null
        if (archive.albums.isEmpty()) {
            parseErrors += ArchiveParseError(path, "文件里没有专辑数据")
            return null
        }
        if (archive.albums.size > 1) {
            parseErrors += ArchiveParseError(path, "包含 ${archive.albums.size} 张专辑，只导入第一张")
        }
        return archive.albums.first()
    }

    /** album.json 与 segments.json 是否指向同一张专辑（信息不足时视为一致）。 */
    private fun sameArchiveAlbum(meta: ArchiveAlbum, segments: ArchiveAlbum): Boolean {
        val metaKey = meta.bvid ?: meta.url
        val segmentsKey = segments.bvid ?: segments.url
        if (metaKey.isNullOrBlank() || segmentsKey.isNullOrBlank()) return true
        return metaKey == segmentsKey && meta.page == segments.page
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
        coverBase64 = coverLocalPath?.let { path ->
            runCatching {
                val file = File(path)
                if (file.isFile && file.length() in 1..MAX_INLINE_COVER_BYTES) {
                    file.readBytes().toByteString().base64()
                } else {
                    null
                }
            }.getOrNull()
        },
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

    /** 应用归档内嵌封面（base64 JPEG）：保存本地文件并更新专辑封面路径。 */
    private suspend fun applyArchiveCover(albumId: Long, archiveAlbum: ArchiveAlbum) {
        val base64 = archiveAlbum.coverBase64?.takeIf { it.isNotBlank() } ?: return
        val bytes = runCatching {
            base64.filterNot { it.isWhitespace() }.decodeBase64()?.toByteArray()
        }.getOrNull() ?: return
        val path = albumCoverRepository.saveAlbumCover(albumId, bytes) ?: return
        albumRepository.updateAlbumCover(albumId, path)
        Diag.log(TAG, "archive cover applied album=$albumId bytes=$bytes")
    }

    private fun nowIso(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(Date())

    private fun timestampForFile(): String =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
}

private const val TAG = "Archive"

/** 归档内嵌封面的最大字节数（超过则不写入 base64，避免备份文件过大）。 */
private const val MAX_INLINE_COVER_BYTES = 2L * 1024 * 1024

// ---------------------------------------------------------------------------
// 目录形态归档（v0.6.0）常量
// ---------------------------------------------------------------------------

/** 根索引文件名：只含 format/version/exportedAt/settings。 */
private const val ROOT_INDEX_FILE = "biliaudio-library.json"

/** 单专辑元信息文件名（同目录下）。 */
private const val ALBUM_FILE = "album.json"

/** 单专辑分割点文件名（同目录下）。 */
private const val SEGMENTS_FILE = "segments.json"

private const val MIME_TYPE_JSON = "application/json"

/** 空艺术家 / 空专辑名时的兜底目录名。 */
private const val FALLBACK_ARTIST = "未知艺术家"
private const val FALLBACK_ALBUM = "未命名专辑"

/** 单级目录名最大长度（码点）。 */
private const val MAX_PATH_SEGMENT_LENGTH = 60

/** 文件系统/SAF 不安全的路径字符（导入时也可能来自外部文件，统一清洗）。 */
private val ILLEGAL_PATH_CHARS = charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')

private val WHITESPACE_REGEX = Regex("\\s+")

/** 测试/无 UI 场景的默认空实现。 */
private object NoopAlbumCoverRepository : AlbumCoverRepository {
    override suspend fun searchNetEaseAlbums(query: String, limit: Int) = emptyList<com.wonderlandkid.biliaudio.data.model.CoverCandidate>()
    override suspend fun fetchCoverBytes(url: String): ByteArray? = null
    override suspend fun saveAlbumCover(albumId: Long, bytes: ByteArray): String? = null
    override fun deleteAlbumCoverFile(path: String?) = Unit
}
