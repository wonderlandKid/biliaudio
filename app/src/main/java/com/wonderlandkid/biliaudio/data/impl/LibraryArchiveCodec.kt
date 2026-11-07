package com.wonderlandkid.biliaudio.data.impl

import com.wonderlandkid.biliaudio.data.model.ArchiveAlbum
import com.wonderlandkid.biliaudio.data.model.ArchiveFormatException
import com.wonderlandkid.biliaudio.data.model.ArchiveParseError
import com.wonderlandkid.biliaudio.data.model.ArchiveSegment
import com.wonderlandkid.biliaudio.data.model.LIBRARY_ARCHIVE_FORMAT
import com.wonderlandkid.biliaudio.data.model.LIBRARY_ARCHIVE_VERSION
import com.wonderlandkid.biliaudio.data.model.LibraryArchive
import com.wonderlandkid.biliaudio.data.net.BiliUrlParser
import com.wonderlandkid.biliaudio.data.net.jsonObjectOrNull
import com.wonderlandkid.biliaudio.data.net.stringOrNull
import com.wonderlandkid.biliaudio.util.TimeUtils
import kotlinx.serialization.json.Json

/** 归档解析结果（recognizedAs 用于 UI 展示识别到的格式）。 */
data class ArchiveParseResult(
    val archive: LibraryArchive,
    val recognizedAs: String,
    val errors: List<ArchiveParseError> = emptyList(),
)

/**
 * 音乐库归档文件编解码器（docs/LIBRARY_ARCHIVE.md）。
 *
 * 职责：
 * 1. 识别新归档格式（JSON 对象 + `format=biliaudio-library`），严格校验并归一化；
 * 2. 兼容旧「快捷导入」格式（JSON 数组 / CSV / 文本块），统一归一化成 [LibraryArchive]；
 * 3. 序列化导出（pretty JSON，省略 null 与默认值）。
 *
 * 所有校验失败抛 [ArchiveFormatException]（message 可直接展示）。
 */
class LibraryArchiveCodec(
    private val json: Json = defaultJson,
    private val legacyParser: BatchImportParser = BatchImportParser(),
) {

    /**
     * 解析文本。无法识别时抛 [ArchiveFormatException]；
     * 旧格式里个别条目解析失败会放在 [ArchiveParseResult.errors] 中继续返回。
     */
    fun parse(text: String): ArchiveParseResult {
        val trimmed = text.trim().trimStart('\uFEFF').trim()
        if (trimmed.isEmpty()) throw ArchiveFormatException("文件内容为空")
        if (trimmed.length > MAX_TEXT_LENGTH) {
            throw ArchiveFormatException("文件过大（上限 ${MAX_TEXT_LENGTH / 1024 / 1024} MB）")
        }

        // 1) 新归档格式：JSON 对象且 format == biliaudio-library
        if (trimmed.startsWith("{")) {
            val root = runCatching { json.parseToJsonElement(trimmed).jsonObjectOrNull() }.getOrNull()
            val format = root?.stringOrNull("format")
            if (format == LIBRARY_ARCHIVE_FORMAT) {
                val archive = runCatching { json.decodeFromString<LibraryArchive>(trimmed) }
                    .getOrElse { e ->
                        throw ArchiveFormatException("归档文件解析失败：${e.message ?: "格式不正确"}")
                    }
                return ArchiveParseResult(
                    archive = validateAndNormalize(archive),
                    recognizedAs = "biliaudio-library",
                )
            }
        }

        // 2) 旧快捷格式（JSON 数组 / CSV / 文本块），归一化成归档模型
        val legacy = legacyParser.parse(trimmed)
        val errors = legacy.errors.map { ArchiveParseError(it.source, it.message) }
        if (legacy.items.isEmpty()) {
            throw ArchiveFormatException(
                errors.firstOrNull()?.message ?: "无法识别的文件格式（既不是归档 JSON，也不是支持的批量导入格式）",
            )
        }
        val archive = legacyToArchive(legacy.items)
        return ArchiveParseResult(
            archive = validateAndNormalize(archive),
            recognizedAs = "legacy-batch",
            errors = errors,
        )
    }

    /** 序列化为归档 JSON 文本。 */
    fun encode(archive: LibraryArchive): String {
        val normalized = archive.copy(
            format = LIBRARY_ARCHIVE_FORMAT,
            version = LIBRARY_ARCHIVE_VERSION,
        )
        return json.encodeToString(LibraryArchive.serializer(), normalized)
    }

    /** 生成一份「最小分割点文件」示例，供 UI 展示/复制。 */
    fun sampleSegmentJson(): String = json.encodeToString(
        LibraryArchive.serializer(),
        LibraryArchive.create(
            albums = listOf(
                ArchiveAlbum(
                    bvid = "BV1GJ411x7h7",
                    page = 1,
                    segments = listOf(
                        ArchiveSegment(startMs = 0, title = "曲目一"),
                        ArchiveSegment(startMs = 45_000, title = "曲目二"),
                        ArchiveSegment(startMs = 120_000, title = "曲目三"),
                    ),
                ),
            ),
        ),
    )

    // ------------------------------------------------------------------
    // 旧格式归一化
    // ------------------------------------------------------------------

    private fun legacyToArchive(items: List<BatchImportItem>): LibraryArchive {
        val albums = items.map { item ->
            val raw = item.bvidOrUrl.trim()
            val bvid = when (val ref = BiliUrlParser.parse(raw)) {
                is BiliUrlParser.VideoRef.Bvid -> ref.bvid
                else -> null
            }
            ArchiveAlbum(
                bvid = bvid,
                // av 号 / 短链 / 普通链接：保留原始输入，导入时再联网解析为 bvid
                url = if (bvid == null && raw.isNotBlank()) raw else null,
                page = 1,
                title = item.albumTitle,
                artist = item.artist,
                segments = item.segments.map { ArchiveSegment(startMs = it.startMs, title = it.title) },
            )
        }
        return LibraryArchive.create(albums = albums)
    }

    // ------------------------------------------------------------------
    // 校验 + 归一化
    // ------------------------------------------------------------------

    private fun validateAndNormalize(archive: LibraryArchive): LibraryArchive {
        if (archive.version > LIBRARY_ARCHIVE_VERSION) {
            throw ArchiveFormatException(
                "文件版本 v${archive.version} 高于当前支持的 v$LIBRARY_ARCHIVE_VERSION，请升级 App",
            )
        }
        if (archive.albums.size > MAX_ALBUMS) {
            throw ArchiveFormatException("专辑数量超过上限（最多 $MAX_ALBUMS 张）")
        }
        val seen = HashSet<String>()
        val albums = archive.albums.mapIndexed { index, album ->
            val normalized = normalizeAlbum(album, index + 1)
            val key = normalized.bvid?.let { "$it#${normalized.page}" }
            if (key != null && !seen.add(key)) {
                throw ArchiveFormatException("文件中存在重复专辑：${normalized.bvid}（第 ${index + 1} 项）")
            }
            normalized
        }
        return archive.copy(albums = albums)
    }

    private fun normalizeAlbum(album: ArchiveAlbum, position: Int): ArchiveAlbum {
        val rawBvid = album.bvid?.trim().orEmpty()
        val rawUrl = album.url?.trim().orEmpty()
        val bvid = rawBvid.takeIf { it.isNotEmpty() }
        if (bvid != null && !BVID_REGEX.matches(bvid)) {
            throw ArchiveFormatException("第 $position 张专辑的 BV 号格式不正确：$bvid")
        }
        if (bvid == null && rawUrl.isEmpty()) {
            throw ArchiveFormatException("第 $position 张专辑缺少 bvid 或 url")
        }

        val title = album.title.cleanText(MAX_TITLE_LENGTH, "第 $position 张专辑的专辑名")
        val artist = album.artist.cleanText(MAX_TITLE_LENGTH, "第 $position 张专辑的艺术家")
        val coverUrl = album.coverUrl.cleanText(MAX_URL_LENGTH, "第 $position 张专辑的封面地址")?.also {
            if (!it.startsWith("http://") && !it.startsWith("https://")) {
                throw ArchiveFormatException("第 $position 张专辑的封面地址必须是 http/https")
            }
        }
        val coverBase64 = album.coverBase64?.trim()?.takeIf { it.isNotEmpty() }?.also {
            if (it.length > MAX_COVER_BASE64_LENGTH) {
                throw ArchiveFormatException("第 $position 张专辑的内嵌封面过大（base64 最多 $MAX_COVER_BASE64_LENGTH 字符）")
            }
        }
        val durationMs = album.durationMs?.takeIf { it > 0 }
        val playCount = album.playCount?.takeIf { it >= 0 }
        val page = album.page.coerceAtLeast(1)

        if (album.segments.size > MAX_SEGMENTS) {
            throw ArchiveFormatException("第 $position 张专辑的分曲超过上限（最多 $MAX_SEGMENTS 首）")
        }
        var lastStart = -1L
        val segments = album.segments.mapIndexed { segIndex, segment ->
            val startMs = segment.startMs
                ?: segment.start?.let { TimeUtils.parseTimeToMs(it) }
                ?: throw ArchiveFormatException(
                    "第 $position 张专辑第 ${segIndex + 1} 个分曲缺少时间点（startMs 或 start）",
                )
            if (startMs < 0) {
                throw ArchiveFormatException("第 $position 张专辑第 ${segIndex + 1} 个分曲时间点不能为负")
            }
            if (startMs <= lastStart) {
                throw ArchiveFormatException(
                    "第 $position 张专辑的分曲时间点必须严格递增（第 ${segIndex + 1} 个分曲 $startMs ms 未递增）",
                )
            }
            if (durationMs != null && startMs >= durationMs) {
                throw ArchiveFormatException(
                    "第 $position 张专辑第 ${segIndex + 1} 个分曲时间点超出专辑时长（${startMs}ms ≥ ${durationMs}ms）",
                )
            }
            lastStart = startMs
            ArchiveSegment(
                startMs = startMs,
                start = null,
                title = (segment.title ?: segment.name).cleanText(MAX_TITLE_LENGTH, "第 $position 张专辑第 ${segIndex + 1} 个分曲名"),
                name = null,
            )
        }

        return Album(
            bvid = bvid,
            url = if (bvid == null) rawUrl else null,
            page = page,
            cid = album.cid?.takeIf { it > 0 },
            title = title,
            artist = artist,
            coverUrl = coverUrl,
            coverBase64 = coverBase64,
            durationMs = durationMs,
            createdAt = album.createdAt?.takeIf { it > 0 },
            lastPlayedAt = album.lastPlayedAt?.takeIf { it > 0 },
            playCount = playCount,
            segments = segments,
        )
    }

    private fun String?.cleanText(maxLength: Int, fieldName: String): String? {
        val trimmed = this?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        if (trimmed.length > maxLength) {
            throw ArchiveFormatException("${fieldName}过长（最多 $maxLength 个字符）")
        }
        return trimmed
    }

    private fun Album(
        bvid: String?,
        url: String?,
        page: Int,
        cid: Long?,
        title: String?,
        artist: String?,
        coverUrl: String?,
        coverBase64: String?,
        durationMs: Long?,
        createdAt: Long?,
        lastPlayedAt: Long?,
        playCount: Int?,
        segments: List<ArchiveSegment>,
    ) = ArchiveAlbum(
        bvid = bvid,
        url = url,
        page = page,
        cid = cid,
        title = title,
        artist = artist,
        coverUrl = coverUrl,
        coverBase64 = coverBase64,
        durationMs = durationMs,
        createdAt = createdAt,
        lastPlayedAt = lastPlayedAt,
        playCount = playCount,
        segments = segments,
    )

    companion object {
        private const val MAX_TEXT_LENGTH = 5 * 1024 * 1024
        /** 内嵌封面 base64 最大长度（约 6MB 原图）。 */
        private const val MAX_COVER_BASE64_LENGTH = 8 * 1024 * 1024
        private const val MAX_ALBUMS = 2000
        private const val MAX_SEGMENTS = 500
        private const val MAX_TITLE_LENGTH = 200
        private const val MAX_URL_LENGTH = 2000
        private val BVID_REGEX = Regex("^BV[0-9A-Za-z]{10}$")

        /** 导出用配置：pretty、省略 null 与默认值。 */
        val defaultJson: Json = Json {
            prettyPrint = true
            prettyPrintIndent = "  "
            encodeDefaults = false
            explicitNulls = false
            ignoreUnknownKeys = true
        }
    }
}
