package com.wonderlandkid.biliaudio.data.impl

import com.wonderlandkid.biliaudio.data.model.ArchiveAlbum
import com.wonderlandkid.biliaudio.data.model.ArchiveCover
import com.wonderlandkid.biliaudio.data.model.ArchiveFormatException
import com.wonderlandkid.biliaudio.data.model.ArchiveKind
import com.wonderlandkid.biliaudio.data.model.ArchiveParseError
import com.wonderlandkid.biliaudio.data.model.ArchiveSegment
import com.wonderlandkid.biliaudio.data.model.ArchiveSettings
import com.wonderlandkid.biliaudio.data.model.AudioQualityPreference
import com.wonderlandkid.biliaudio.data.model.LIBRARY_ARCHIVE_FEATURES
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
 * 音乐库归档 v2 编解码器（docs/ARCHIVE_SCHEMA_V2.md）。
 *
 * - 只支持 v2：旧 v1 归档明确拒绝并提示重新导出；
 * - `kind` 区分整库/单专辑/分割点/目录索引；
 * - `version + minReaderVersion + features` 做前向兼容门禁；
 * - 封面带 MIME/SHA-256/字节数校验；
 * - 输入永远不可信：大小、bvid、URL、分曲边界、封面均校验。
 */
class LibraryArchiveCodec(
    private val json: Json = defaultJson,
    private val legacyParser: BatchImportParser = BatchImportParser(),
) {

    /**
     * 解析文本。无法识别时抛 [ArchiveFormatException]；
     * 旧批量导入格式（JSON 数组/CSV/文本）仍归一化成 v2 归档（这不是 v1 归档兼容）。
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
                val normalized = validateAndNormalize(archive).let { checked ->
                    if (expectedKind != null && checked.kind != expectedKind) {
                        throw ArchiveFormatException(
                            "归档类型不匹配：期望 $expectedKind，实际 ${checked.kind}",
                        )
                    }
                    checked
                }
                return ArchiveParseResult(archive = normalized, recognizedAs = "biliaudio-v2")
            }
        }

        // 旧「快捷导入」文本格式（不是 v1 归档）：归一化成 v2 归档
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

    /** 序列化为归档 JSON。 */
    fun encode(archive: LibraryArchive): String {
        val normalized = validateAndNormalize(
            archive.copy(
                format = LIBRARY_ARCHIVE_FORMAT,
                version = LIBRARY_ARCHIVE_VERSION,
                minReaderVersion = LIBRARY_ARCHIVE_MIN_READER_VERSION,
                features = LIBRARY_ARCHIVE_FEATURES,
            ),
        )
        return json.encodeToString(LibraryArchive.serializer(), normalized)
    }

    /** 生成一份「最小分割点文件」示例，供 UI 展示/复制。 */
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

    /** 目录导入合并后做一次全局校验（供 Repository 调用）。 */
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
        val unknownFeatures = archive.features.filterNot { it in LIBRARY_ARCHIVE_FEATURES }
        if (unknownFeatures.isNotEmpty()) {
            throw ArchiveFormatException("文件包含当前 App 不支持的数据特性：${unknownFeatures.joinToString()}")
        }
        when (archive.kind) {
            ArchiveKind.INDEX -> if (archive.albums.isNotEmpty()) {
                throw ArchiveFormatException("目录索引文件不能包含专辑数据（kind=index）")
            }
            ArchiveKind.ALBUM, ArchiveKind.SEGMENTS -> if (archive.albums.size != 1) {
                throw ArchiveFormatException("${archive.kind} 文件必须且只能包含 1 张专辑")
            }
            ArchiveKind.LIBRARY -> Unit
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
            features = LIBRARY_ARCHIVE_FEATURES,
            settings = archive.settings?.let(::normalizeSettings),
            albums = albums,
        )
    }

    // ------------------------------------------------------------------
    // 校验 + 归一化
    // ------------------------------------------------------------------

    private fun normalizeSettings(settings: ArchiveSettings): ArchiveSettings {
        val quality = settings.audioQuality?.trim()?.takeIf { it.isNotEmpty() }
        if (quality != null && AudioQualityPreference.entries.none { it.name == quality }) {
            throw ArchiveFormatException("未知音质设置：$quality")
        }
        return settings.copy(
            version = settings.version.coerceAtLeast(1),
            audioQuality = quality,
        )
    }

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
                if (startMs < 0) {
                    throw ArchiveFormatException("第 $position 张专辑第 ${segIndex + 1} 个分曲时间点不能为负")
                }
                if (startMs <= lastStart) {
                    throw ArchiveFormatException(
                        "第 $position 张专辑的分曲时间点必须严格递增（第 ${segIndex + 1} 个分曲 $startMs ms）",
                    )
                }
                if (durationMs != null && startMs >= durationMs) {
                    throw ArchiveFormatException(
                        "第 $position 张专辑第 ${segIndex + 1} 个分曲超出专辑时长（${startMs}ms ≥ ${durationMs}ms）",
                    )
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
                    if (it.length > MAX_TRACK_ID_LENGTH) {
                        throw ArchiveFormatException("第 $position 张专辑第 ${segIndex + 1} 个分曲 id 过长")
                    }
                    if (!ids.add(it)) {
                        throw ArchiveFormatException("第 $position 张专辑存在重复的分曲 id：$it")
                    }
                }
                val segTitle = segment.title.cleanText(MAX_TITLE_LENGTH, "第 $position 张专辑第 ${segIndex + 1} 个分曲名")
                previousEnd = endMs
                lastStart = startMs
                ArchiveSegment(id = id, startMs = startMs, endMs = endMs, title = segTitle)
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

    /** 校验封面：MIME、base64、原始字节数 ≤2MB、SHA-256 一致。 */
    private fun normalizeCover(cover: ArchiveCover, position: Int): ArchiveCover {
        if (cover.mime !in ALLOWED_COVER_MIMES) {
            throw ArchiveFormatException("第 $position 张专辑的封面类型不支持：${cover.mime}")
        }
        if (!SHA256_REGEX.matches(cover.sha256)) {
            throw ArchiveFormatException("第 $position 张专辑的封面 sha256 格式不正确")
        }
        val bytes = runCatching {
            cover.dataBase64.filterNot { it.isWhitespace() }.decodeBase64()
                ?: throw ArchiveFormatException("第 $position 张专辑的封面 base64 无效")
        }.getOrElse { e ->
            if (e is ArchiveFormatException) throw e
            throw ArchiveFormatException("第 $position 张专辑的封面 base64 无效")
        }.toByteArray()
        if (bytes.isEmpty()) throw ArchiveFormatException("第 $position 张专辑的封面为空")
        if (bytes.size > MAX_COVER_BYTES) {
            throw ArchiveFormatException("第 $position 张专辑的封面超过 2MB 上限")
        }
        if (bytes.size != cover.byteLength) {
            throw ArchiveFormatException("第 $position 张专辑的封面 byteLength 与实际字节数不一致")
        }
        val actualSha = bytes.toByteString().sha256().hex()
        if (!actualSha.equals(cover.sha256, ignoreCase = true)) {
            throw ArchiveFormatException("第 $position 张专辑的封面 sha256 校验失败")
        }
        return cover
    }

    private fun String?.cleanText(maxLength: Int, fieldName: String): String? {
        val trimmed = this?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        if (trimmed.length > maxLength) {
            throw ArchiveFormatException("${fieldName}过长（最多 $maxLength 个字符）")
        }
        return trimmed
    }

    // ------------------------------------------------------------------
    // 旧「快捷导入」归一化（文本/CSV/JSON 数组 → v2 归档）
    // ------------------------------------------------------------------

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
        return LibraryArchive.create(
            kind = ArchiveKind.LIBRARY,
            albums = albums,
        )
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

        /** 导出用配置：pretty、写默认值（kind/version 等必须落盘）。 */
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
