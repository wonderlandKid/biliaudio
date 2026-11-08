package com.wonderlandkid.biliaudio.data.impl

import com.wonderlandkid.biliaudio.data.model.ArchiveAlbum
import com.wonderlandkid.biliaudio.data.model.ArchiveCover
import com.wonderlandkid.biliaudio.data.model.ArchiveFormatException
import com.wonderlandkid.biliaudio.data.model.ArchiveKind
import com.wonderlandkid.biliaudio.data.model.ArchiveParseError
import com.wonderlandkid.biliaudio.data.model.ArchiveSegment
import com.wonderlandkid.biliaudio.data.model.LIBRARY_ARCHIVE_FORMAT
import com.wonderlandkid.biliaudio.data.model.LIBRARY_ARCHIVE_MIN_READER_VERSION
import com.wonderlandkid.biliaudio.data.model.LIBRARY_ARCHIVE_VERSION
import com.wonderlandkid.biliaudio.data.model.LibraryArchive
import com.wonderlandkid.biliaudio.data.net.BiliUrlParser
import com.wonderlandkid.biliaudio.data.net.jsonObjectOrNull
import com.wonderlandkid.biliaudio.data.net.stringOrNull
import kotlinx.serialization.json.Json
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import java.security.MessageDigest

/** 归档解析结果（recognizedAs 用于 UI 展示识别到的格式）。 */
data class ArchiveParseResult(
    val archive: LibraryArchive,
    val recognizedAs: String,
    val errors: List<ArchiveParseError> = emptyList(),
)

/**
 * library 域 v3 编解码器（docs/ARCHIVE_LAYOUT_V3.md）。
 *
 * - 只支持 v3（分域 bundle 的 library.json，或单 album/segments 分享文件）；
 * - 不再包含 settings/playlists/封面 base64 的整库形态；
 * - 封面在 bundle 中用 hash 引用，单专辑分享可内嵌 base64。
 */
class LibraryArchiveCodec(
    private val json: Json = defaultJson,
    private val legacyParser: BatchImportParser = BatchImportParser(),
) {

    /**
     * 解析文本。无法识别时抛 [ArchiveFormatException]；
     * 旧“快捷导入”文本格式仍归一化成 v3 归档（不是旧归档兼容）。
     */
    fun parse(text: String, expectedKind: ArchiveKind? = null): ArchiveParseResult {
        val trimmed = text.trim().trimStart('\uFEFF').trim()
        if (trimmed.isEmpty()) throw ArchiveFormatException("文件内容为空")
        if (trimmed.length > MAX_TEXT_LENGTH) {
            throw ArchiveFormatException("文件过大（上限 ${MAX_TEXT_LENGTH / 1024 / 1024} MB）")
        }

        if (trimmed.startsWith("{")) {
            val root = runCatching { json.parseToJsonElement(trimmed).jsonObjectOrNull() }.getOrNull()
            if (root?.stringOrNull("format") == LIBRARY_ARCHIVE_FORMAT) {
                val archive = runCatching { json.decodeFromString<LibraryArchive>(trimmed) }
                    .getOrElse { e ->
                        throw ArchiveFormatException("归档文件解析失败：${e.message ?: "格式不正确"}")
                    }
                val normalized = validateAndNormalize(archive)
                if (expectedKind != null && normalized.kind != expectedKind) {
                    throw ArchiveFormatException(
                        "归档类型不匹配：期望 $expectedKind，实际 ${normalized.kind}",
                    )
                }
                return ArchiveParseResult(archive = normalized, recognizedAs = "biliaudio-v3")
            }
        }

        val legacy = legacyParser.parse(trimmed)
        val errors = legacy.errors.map { ArchiveParseError(it.source, it.message) }
        if (legacy.items.isEmpty()) {
            throw ArchiveFormatException(
                errors.firstOrNull()?.message ?: "无法识别的文件格式（既不是归档 JSON，也不是支持的批量导入格式）",
            )
        }
        return ArchiveParseResult(
            archive = validateAndNormalize(legacyToArchive(legacy.items)),
            recognizedAs = "legacy-batch",
            errors = errors,
        )
    }

    /** 序列化为 library/album/segments 域 JSON。 */
    fun encode(archive: LibraryArchive): String {
        val normalized = validateAndNormalize(archive)
        return json.encodeToString(LibraryArchive.serializer(), normalized)
    }

    /** 生成“仅分割点”示例。 */
    fun sampleSegmentJson(): String = encode(
        LibraryArchive.create(
            kind = ArchiveKind.SEGMENTS,
            albums = listOf(
                ArchiveAlbum(
                    bvid = "BV1GJ411x7h7",
                    page = 1,
                    segments = listOf(
                        ArchiveSegment(startMs = 0, endMs = 45_000, title = "曲目一"),
                        ArchiveSegment(startMs = 45_000, endMs = 120_000, title = "曲目二"),
                        ArchiveSegment(startMs = 120_000, endMs = 213_000, title = "曲目三"),
                    ),
                ),
            ),
        ),
    )

    /** 目录 bundle 合并/单独文件导入前做一次校验。 */
    fun validateAndNormalize(archive: LibraryArchive): LibraryArchive {
        if (archive.format != LIBRARY_ARCHIVE_FORMAT) {
            throw ArchiveFormatException("不是 $LIBRARY_ARCHIVE_FORMAT 文件")
        }
        if (archive.version > LIBRARY_ARCHIVE_VERSION) {
            throw ArchiveFormatException(
                "文件版本 v${archive.version} 高于当前支持的 v$LIBRARY_ARCHIVE_VERSION，请升级 App",
            )
        }
        if (archive.version < LIBRARY_ARCHIVE_VERSION) {
            throw ArchiveFormatException(
                "旧版 v${archive.version} 归档不再支持，请用当前 App 重新导出",
            )
        }
        if (archive.minReaderVersion > LIBRARY_ARCHIVE_VERSION) {
            throw ArchiveFormatException(
                "文件要求最低读取版本 v${archive.minReaderVersion}，请升级 App",
            )
        }
        when (archive.kind) {
            ArchiveKind.LIBRARY -> Unit
            ArchiveKind.ALBUM, ArchiveKind.SEGMENTS -> if (archive.albums.size != 1) {
                throw ArchiveFormatException("${archive.kind} 文件必须且只能包含 1 张专辑")
            }
        }
        if (archive.albums.size > MAX_ALBUMS) {
            throw ArchiveFormatException("专辑数量超过上限（最多 $MAX_ALBUMS 张）")
        }
        val seen = HashSet<String>()
        val albums = archive.albums.mapIndexed { index, album ->
            val normalized = normalizeAlbum(album, index + 1)
            val key = normalized.albumKey
            if (key != null && !seen.add(key)) {
                throw ArchiveFormatException("文件中存在重复专辑：$key")
            }
            normalized
        }
        return archive.copy(
            version = LIBRARY_ARCHIVE_VERSION,
            minReaderVersion = LIBRARY_ARCHIVE_MIN_READER_VERSION,
            albums = albums,
        )
    }

    // ------------------------------------------------------------------
    // 校验
    // ------------------------------------------------------------------

    private fun normalizeAlbum(album: ArchiveAlbum, position: Int): ArchiveAlbum {
        val source = album.source.trim().ifBlank { SOURCE_BILIBILI }
        if (source != SOURCE_BILIBILI) {
            throw ArchiveFormatException("第 $position 张专辑的来源不支持：$source")
        }
        val rawBvid = album.bvid?.trim().orEmpty()
        val rawUrl = album.url?.trim().orEmpty()
        val bvid = rawBvid.takeIf { it.isNotEmpty() }
        if (bvid != null && !BVID_REGEX.matches(bvid)) {
            throw ArchiveFormatException("第 $position 张专辑的 BV 号格式不正确：$bvid")
        }
        if (bvid == null && rawUrl.isEmpty()) {
            throw ArchiveFormatException("第 $position 张专辑缺少 bvid 或 url")
        }
        if (rawUrl.isNotEmpty() && !rawUrl.startsWith("http://") && !rawUrl.startsWith("https://")) {
            throw ArchiveFormatException("第 $position 张专辑的视频链接必须是 http/https")
        }
        val page = album.page
        if (page < 1) throw ArchiveFormatException("第 $position 张专辑的 page 必须 >= 1")
        val pageCount = album.pageCount?.also {
            if (it < page) throw ArchiveFormatException("第 $position 张专辑的分 P 数小于 page")
        }
        val durationMs = album.durationMs?.takeIf { it > 0 }
        val playCount = album.playCount?.takeIf { it >= 0 }
        val cidHint = album.cidHint?.takeIf { it > 0 }
        val albumKey = bvid?.let { "$source:$it#$page" }
        album.albumKey?.let { provided ->
            if (albumKey != null && provided != albumKey) {
                throw ArchiveFormatException("第 $position 张专辑的 albumKey 与 bvid/page 不一致")
            }
        }
        val title = album.title.cleanText(MAX_TITLE_LENGTH, "第 $position 张专辑的专辑名")
        val artist = album.artist.cleanText(MAX_TITLE_LENGTH, "第 $position 张专辑的艺术家")
        val cover = album.cover?.let { normalizeCover(it, position) }

        if (album.segments != null && album.segments.size > MAX_SEGMENTS) {
            throw ArchiveFormatException("第 $position 张专辑的分曲超过上限（最多 $MAX_SEGMENTS 首）")
        }
        val segments = album.segments?.let { list ->
            var lastStart = -1L
            var previousEnd: Long? = null
            val ids = HashSet<String>()
            list.mapIndexed { segIndex, segment ->
                val startMs = segment.startMs
                if (startMs < 0) throw ArchiveFormatException("第 $position 张专辑第 ${segIndex + 1} 个分曲时间点不能为负")
                if (startMs <= lastStart) {
                    throw ArchiveFormatException("第 $position 张专辑的分曲时间点必须严格递增（第 ${segIndex + 1} 个分曲 $startMs ms）")
                }
                if (durationMs != null && startMs >= durationMs) {
                    throw ArchiveFormatException("第 $position 张专辑第 ${segIndex + 1} 个分曲超出专辑时长（${startMs}ms ≥ ${durationMs}ms）")
                }
                previousEnd?.let { prevEnd ->
                    if (startMs < prevEnd) {
                        throw ArchiveFormatException("第 $position 张专辑第 ${segIndex + 1} 个分曲与上一首重叠")
                    }
                }
                val endMs = segment.endMs?.also { end ->
                    if (end <= startMs) {
                        throw ArchiveFormatException("第 $position 张专辑第 ${segIndex + 1} 个分曲 endMs 必须大于 startMs")
                    }
                    if (durationMs != null && end > durationMs) {
                        throw ArchiveFormatException("第 $position 张专辑第 ${segIndex + 1} 个分曲 endMs 超出专辑时长")
                    }
                }
                val id = segment.id?.trim()?.takeIf { it.isNotEmpty() }?.also {
                    if (it.length > MAX_TRACK_ID_LENGTH) throw ArchiveFormatException("分曲 id 过长")
                    if (!ids.add(it)) throw ArchiveFormatException("第 $position 张专辑存在重复的分曲 id：$it")
                }
                val segTitle = segment.title.cleanText(MAX_TITLE_LENGTH, "第 $position 张专辑第 ${segIndex + 1} 个分曲名")
                val sourceCid = segment.sourceCid?.takeIf { it > 0 }
                val sourcePage = segment.sourcePage?.takeIf { it > 0 }
                val sourceOffset = segment.sourceOffsetMs?.takeIf { it >= 0 }
                if (sourceCid != null && sourcePage == null) {
                    throw ArchiveFormatException("第 $position 张专辑第 ${segIndex + 1} 个分曲缺少分 P 序号")
                }
                previousEnd = endMs
                lastStart = startMs
                ArchiveSegment(
                    id = id,
                    startMs = startMs,
                    endMs = endMs,
                    title = segTitle,
                    sourceCid = sourceCid,
                    sourcePage = sourcePage,
                    sourceOffsetMs = sourceOffset,
                )
            }
        }

        return album.copy(
            albumKey = albumKey ?: album.albumKey?.trim()?.takeIf { it.isNotEmpty() },
            source = source,
            bvid = bvid,
            url = if (bvid == null) rawUrl else null,
            page = page,
            pageCount = pageCount,
            cidHint = cidHint,
            title = title,
            artist = artist,
            durationMs = durationMs,
            updatedAt = album.updatedAt?.takeIf { it > 0 },
            createdAt = album.createdAt?.takeIf { it > 0 },
            lastPlayedAt = album.lastPlayedAt?.takeIf { it > 0 },
            playCount = playCount,
            cover = cover,
            segments = segments,
        )
    }

    private fun normalizeCover(cover: ArchiveCover, position: Int): ArchiveCover {
        if (cover.mime !in ALLOWED_COVER_MIMES) {
            throw ArchiveFormatException("第 $position 张专辑的封面类型不支持：${cover.mime}")
        }
        if (!SHA256_REGEX.matches(cover.hash)) {
            throw ArchiveFormatException("第 $position 张专辑的封面 hash 格式不正确")
        }
        if (cover.byteLength !in 1..MAX_COVER_BYTES) {
            throw ArchiveFormatException("第 $position 张专辑的封面字节数超出范围")
        }
        val base64 = cover.dataBase64?.takeIf { it.isNotBlank() }
        if (base64 != null) {
            val bytes = runCatching { base64.filterNot { it.isWhitespace() }.decodeBase64() }.getOrNull()
                ?: throw ArchiveFormatException("第 $position 张专辑的封面 base64 无效")
            if (bytes.size != cover.byteLength) {
                throw ArchiveFormatException("第 $position 张专辑的封面 byteLength 与实际不一致")
            }
            val actual = bytes.sha256().hex()
            if (!actual.equals(cover.hash, ignoreCase = true)) {
                throw ArchiveFormatException("第 $position 张专辑的封面 hash 校验失败")
            }
        }
        return cover.copy(hash = cover.hash.lowercase())
    }

    private fun String?.cleanText(maxLength: Int, fieldName: String): String? {
        val trimmed = this?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        if (trimmed.length > maxLength) throw ArchiveFormatException("${fieldName}过长（最多 $maxLength 个字符）")
        return trimmed
    }

    private fun legacyToArchive(items: List<BatchImportItem>): LibraryArchive {
        val albums = items.map { item ->
            val raw = item.bvidOrUrl.trim()
            val bvid = when (val ref = BiliUrlParser.parse(raw)) {
                is BiliUrlParser.VideoRef.Bvid -> ref.bvid
                else -> null
            }
            ArchiveAlbum(
                source = SOURCE_BILIBILI,
                bvid = bvid,
                url = if (bvid == null && raw.isNotBlank()) raw else null,
                page = 1,
                title = item.albumTitle,
                artist = item.artist,
                segments = item.segments.map { ArchiveSegment(startMs = it.startMs, title = it.title) },
            )
        }
        return LibraryArchive.create(kind = ArchiveKind.LIBRARY, albums = albums)
    }

    companion object {
        private const val SOURCE_BILIBILI = "bilibili"
        private const val MAX_TEXT_LENGTH = 64 * 1024 * 1024
        private const val MAX_COVER_BYTES = 2 * 1024 * 1024
        private const val MAX_ALBUMS = 2000
        private const val MAX_SEGMENTS = 500
        private const val MAX_TITLE_LENGTH = 200
        private const val MAX_TRACK_ID_LENGTH = 64
        private val BVID_REGEX = Regex("^BV[0-9A-Za-z]{10}$")
        private val SHA256_REGEX = Regex("^[0-9a-fA-F]{64}$")
        private val ALLOWED_COVER_MIMES = setOf("image/jpeg", "image/png", "image/webp")

        val defaultJson: Json = Json {
            prettyPrint = true
            prettyPrintIndent = "  "
            encodeDefaults = true
            explicitNulls = false
            ignoreUnknownKeys = true
        }

        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
