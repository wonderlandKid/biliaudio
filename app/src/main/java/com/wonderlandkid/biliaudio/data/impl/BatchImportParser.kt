package com.wonderlandkid.biliaudio.data.impl

import com.wonderlandkid.biliaudio.data.model.ImportMode
import com.wonderlandkid.biliaudio.data.model.SegmentSpec
import com.wonderlandkid.biliaudio.data.net.BiliJson
import com.wonderlandkid.biliaudio.data.net.BiliUrlParser
import com.wonderlandkid.biliaudio.data.net.arrayOrNull
import com.wonderlandkid.biliaudio.data.net.jsonObjectOrNull
import com.wonderlandkid.biliaudio.data.net.objectOrNull
import com.wonderlandkid.biliaudio.data.net.stringOrNull
import com.wonderlandkid.biliaudio.util.TimeUtils
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.math.roundToLong

/** 批量导入解析出的单张专辑。 */
data class BatchImportItem(
    /** 原始输入（失败回显 / 日志用）。 */
    val source: String,
    /** BV 号、av 号或视频链接。 */
    val bvidOrUrl: String,
    val albumTitle: String? = null,
    val artist: String? = null,
    val mode: ImportMode = ImportMode.SINGLE_VIDEO,
    val segments: List<SegmentSpec> = emptyList(),
)

/** 解析失败项（对应导入报告里的 [com.wonderlandkid.biliaudio.data.model.ImportResult.Failure]）。 */
data class BatchParseError(
    /** 出错的原始片段，便于 UI 定位。 */
    val source: String,
    val message: String,
)

data class BatchParseResult(
    val items: List<BatchImportItem> = emptyList(),
    val errors: List<BatchParseError> = emptyList(),
)

/**
 * 批量导入文本解析器。
 *
 * **格式规范以 docs/IMPORT_FORMATS.md 为唯一依据，本类严格实现该文档**，支持三种格式：
 *
 * ### A. JSON 数组
 * ```json
 * [
 *   { "bvid": "BV1GJ411x7h7", "title": "专辑名", "artist": "艺术家",
 *     "mode": "single", "segments": [ { "start": "00:00", "name": "曲目一" } ] }
 * ]
 * ```
 * - `bvid` 也可写成 `url`（B 站视频链接）；
 * - `title` / `artist` / `mode` / `segments` 均可省略；`mode` 取 `single`（默认）或 `multipage`；
 * - `start` 支持秒数、`mm:ss`、`mm:ss.SSS`、`hh:mm:ss`；
 * - 标题/歌名为空时自动命名，分曲名规则为 `曲目 N`。
 *
 * ### B. CSV（5 列，每行一首歌，同一 BV 自动合并为一张专辑）
 * ```text
 * BV号,专辑名,艺术家,时间点,歌名
 * BV1GJ411x7h7,专辑名,艺术家,00:00,曲目一
 * ```
 * - 专辑名 / 艺术家可留空（写成 `,,`），时间点必填，歌名可空（自动 `曲目 N`）；
 * - 支持带表头（首行含 bvid / BV号 / 专辑+时间 等关键字时跳过）。
 *
 * ### C. 文本块（贴评论区分割点）
 * ```text
 * BV1GJ411x7h7 专辑名
 *   00:00 曲目一
 *   03:21 曲目二
 *
 * BV1uv411q7Mv 另一张专辑
 *   00:00 第一首
 * ```
 * - 以 BV/av/链接开头的非缩进行开始一张新专辑，行内剩余文本为专辑名（可空）；
 * - 其后的缩进行（行首空白）为分曲：`时间点 歌名`；
 * - 专辑块之间用空行分隔。
 *
 * 自动识别顺序：JSON → CSV → 文本块；全部失败时逐行按「无分曲的单专辑」兜底再试一次。
 * 单个条目解析失败不影响其他条目，错误以 [BatchParseError] 返回（中文，可直接展示）。
 */
class BatchImportParser(private val json: Json = BiliJson.instance) {

    fun parse(text: String): BatchParseResult {
        val trimmed = text.trim().trimStart('\uFEFF').trim()
        if (trimmed.isEmpty()) {
            return BatchParseResult(errors = listOf(BatchParseError("", "导入内容为空")))
        }

        // 1) JSON 数组（也容忍单个对象）
        if (trimmed.startsWith("[") || trimmed.startsWith("{")) {
            val result = runCatching { parseJson(trimmed) }.getOrNull()
            if (result != null && (result.items.isNotEmpty() || result.errors.isNotEmpty())) return result
        }

        // 2) CSV（识别为 CSV 但整行格式错误时直接返回错误，避免被误当成文本块）
        val csvResult = runCatching { parseCsv(trimmed) }.getOrNull()
        if (csvResult != null && (csvResult.items.isNotEmpty() || csvResult.errors.isNotEmpty())) return csvResult

        // 3) 文本块
        val textBlockResult = runCatching { parseTextBlock(trimmed) }.getOrNull()
        if (textBlockResult != null && textBlockResult.items.isNotEmpty()) return textBlockResult

        // 4) 兜底：逐行按「无分曲的单专辑」再试一次
        return parseLineByLine(trimmed)
    }

    // -----------------------------------------------------------------------
    // 格式 A：JSON 数组
    // -----------------------------------------------------------------------

    private fun parseJson(text: String): BatchParseResult {
        val root = json.parseToJsonElement(text)
        val array = when (root) {
            is JsonArray -> root
            is JsonObject -> JsonArray(listOf(root))
            else -> return BatchParseResult()
        }
        val items = mutableListOf<BatchImportItem>()
        val errors = mutableListOf<BatchParseError>()
        array.forEachIndexed { index, element ->
            val obj = element.jsonObjectOrNull()
            if (obj == null) {
                errors += BatchParseError("JSON 第 ${index + 1} 项", "不是合法的 JSON 对象")
                return@forEachIndexed
            }
            val rawRef = obj.stringOrNull("bvid")?.trim().orEmpty()
                .ifEmpty { obj.stringOrNull("url")?.trim().orEmpty() }
            if (rawRef.isEmpty()) {
                errors += BatchParseError("JSON 第 ${index + 1} 项", "缺少 bvid 或 url 字段")
                return@forEachIndexed
            }
            if (BiliUrlParser.parse(rawRef) == null) {
                errors += BatchParseError(rawRef, "BV 号或链接格式不正确：$rawRef")
                return@forEachIndexed
            }
            val mode = if (obj.stringOrNull("mode")?.trim()?.equals("multipage", ignoreCase = true) == true) {
                ImportMode.MULTI_PAGE
            } else {
                ImportMode.SINGLE_VIDEO
            }
            val segments = mutableListOf<SegmentSpec>()
            var segmentError: String? = null
            when (val segmentsElement = obj["segments"]) {
                null, is JsonNull -> Unit
                is JsonArray -> {
                    segmentsElement.forEachIndexed { segIndex, segElement ->
                        val segObj = segElement.jsonObjectOrNull()
                        if (segObj == null) {
                            segmentError = "segments 第 ${segIndex + 1} 项格式不正确"
                            return@forEachIndexed
                        }
                        val startMs = parseStartValue(segObj["start"])
                        if (startMs == null) {
                            segmentError = "segments 第 ${segIndex + 1} 项时间点格式不正确（示例：00:00 / 3:21 / 83）"
                            return@forEachIndexed
                        }
                        val name = (segObj.stringOrNull("name") ?: segObj.stringOrNull("title")).orEmpty().trim()
                        segments += SegmentSpec(
                            startMs = startMs,
                            title = name.ifBlank { "曲目 ${segments.size + 1}" },
                        )
                    }
                }
                else -> segmentError = "segments 字段必须是数组"
            }
            if (segmentError != null) {
                errors += BatchParseError(rawRef, segmentError ?: "segments 解析失败")
                return@forEachIndexed
            }
            items += BatchImportItem(
                source = rawRef,
                bvidOrUrl = rawRef,
                albumTitle = obj.stringOrNull("title")?.trim()?.takeIf { it.isNotEmpty() },
                artist = obj.stringOrNull("artist")?.trim()?.takeIf { it.isNotEmpty() },
                mode = mode,
                segments = segments,
            )
        }
        return BatchParseResult(items = items, errors = errors)
    }

    /** JSON segments[].start：数字按秒，字符串按时间格式解析。 */
    private fun parseStartValue(element: JsonElement?): Long? {
        if (element == null || element is JsonNull) return null
        val primitive = element as? JsonPrimitive ?: return null
        val content = primitive.contentOrNull?.trim().orEmpty()
        if (content.isEmpty()) return null
        content.toDoubleOrNull()?.let { seconds ->
            return if (seconds >= 0) (seconds * 1000).roundToLong() else null
        }
        return TimeUtils.parseTimeToMs(content)?.takeIf { it >= 0 }
    }

    // -----------------------------------------------------------------------
    // 格式 B：CSV（5 列）
    // -----------------------------------------------------------------------

    private class CsvAlbumBuilder(
        val source: String,
        val bvidOrUrl: String,
    ) {
        var albumTitle: String? = null
        var artist: String? = null
        val segments = mutableListOf<SegmentSpec>()
    }

    private fun parseCsv(text: String): BatchParseResult {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return BatchParseResult()
        val headerSkipped = if (isCsvHeader(lines.first())) 1 else 0
        val bodyLines = lines.drop(headerSkipped)
        if (bodyLines.isEmpty()) return BatchParseResult()
        // 认定为 CSV：有表头，或至少一行能拆出 5 列
        val looksLikeCsv = headerSkipped == 1 || bodyLines.any { splitCsvLine(it).size >= CSV_COLUMNS }
        if (!looksLikeCsv) return BatchParseResult()

        val builders = LinkedHashMap<String, CsvAlbumBuilder>()
        val errors = mutableListOf<BatchParseError>()
        for (line in bodyLines) {
            val fields = splitCsvLine(line)
            if (fields.size != CSV_COLUMNS) {
                errors += BatchParseError(
                    line,
                    "CSV 格式不正确：需要 $CSV_COLUMNS 列「BV号,专辑名,艺术家,时间点,歌名」",
                )
                continue
            }
            val rawRef = fields[0].trim()
            val key = identityKey(rawRef)
            if (key == null) {
                errors += BatchParseError(line, "无法识别 BV 号或链接：$rawRef")
                continue
            }
            val timeText = fields[3].trim()
            val startMs = if (timeText.isEmpty()) null else TimeUtils.parseTimeToMs(timeText)
            if (startMs == null) {
                errors += BatchParseError(line, "时间点格式不正确：$timeText（应为 mm:ss 或秒数，且不能为空）")
                continue
            }
            val builder = builders.getOrPut(key) { CsvAlbumBuilder(source = rawRef, bvidOrUrl = rawRef) }
            if (builder.albumTitle.isNullOrBlank()) {
                builder.albumTitle = fields[1].trim().takeIf { it.isNotEmpty() }
            }
            if (builder.artist.isNullOrBlank()) {
                builder.artist = fields[2].trim().takeIf { it.isNotEmpty() }
            }
            val trackTitle = fields[4].trim().ifBlank { "曲目 ${builder.segments.size + 1}" }
            builder.segments += SegmentSpec(startMs = startMs, title = trackTitle)
        }
        return BatchParseResult(items = builders.values.map { it.toItem() }, errors = errors)
    }

    private fun CsvAlbumBuilder.toItem(): BatchImportItem = BatchImportItem(
        source = source,
        bvidOrUrl = bvidOrUrl,
        albumTitle = albumTitle,
        artist = artist,
        mode = ImportMode.SINGLE_VIDEO,
        segments = segments,
    )

    /** 首行是否为 CSV 表头（含表头关键字；首列能解析成视频引用的数据行不算表头）。 */
    private fun isCsvHeader(line: String): Boolean {
        if (!line.contains(',')) return false
        val firstField = splitCsvLine(line).firstOrNull()?.trim().orEmpty()
        if (BiliUrlParser.parse(firstField) != null) return false
        val lower = line.lowercase()
        if (lower.contains("bvid") || lower.contains("bv号")) return true
        val keywordCount = listOf("专辑", "艺术家", "时间", "歌名").count { lower.contains(it) }
        return keywordCount >= 2
    }

    /** 按 CSV 规则拆行（支持双引号包裹、`""` 转义）。 */
    private fun splitCsvLine(line: String): List<String> {
        val fields = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var index = 0
        while (index < line.length) {
            val char = line[index]
            when {
                char == '"' && inQuotes && index + 1 < line.length && line[index + 1] == '"' -> {
                    current.append('"')
                    index++
                }
                char == '"' -> inQuotes = !inQuotes
                char == ',' && !inQuotes -> {
                    fields += current.toString().trim()
                    current.clear()
                }
                else -> current.append(char)
            }
            index++
        }
        fields += current.toString().trim()
        return fields
    }

    // -----------------------------------------------------------------------
    // 格式 C：文本块
    // -----------------------------------------------------------------------

    private class TextAlbumBuilder(
        val source: String,
        val bvidOrUrl: String,
        var albumTitle: String?,
    ) {
        val segments = mutableListOf<SegmentSpec>()
    }

    private fun parseTextBlock(text: String): BatchParseResult {
        val builders = mutableListOf<TextAlbumBuilder>()
        val errors = mutableListOf<BatchParseError>()
        var current: TextAlbumBuilder? = null
        for (rawLine in text.lines()) {
            if (rawLine.isBlank()) {
                // 空行结束当前专辑块
                current = null
                continue
            }
            val isIndented = rawLine.first().isWhitespace()
            val line = rawLine.trim()
            if (!isIndented) {
                val parts = line.split(WHITESPACE_REGEX, limit = 2)
                val refToken = parts[0]
                val titleText = parts.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
                if (BiliUrlParser.parse(refToken) == null) {
                    errors += BatchParseError(line, "无法识别 BV 号或链接：$line")
                    current = null
                    continue
                }
                current = TextAlbumBuilder(source = line, bvidOrUrl = refToken, albumTitle = titleText)
                builders += current
            } else {
                val builder = current
                if (builder == null) {
                    errors += BatchParseError(line, "分曲行缺少所属专辑（需要先写一行 BV 号）")
                    continue
                }
                // 允许行首的 "-"、"•" 等列表符号
                val content = line.trimStart('-', '—', '–', '•', '·', '*', ' ').trim()
                val match = SEGMENT_LINE_REGEX.matchEntire(content)
                if (match == null) {
                    errors += BatchParseError(line, "分曲行格式不正确：$line（应为「mm:ss 歌名」）")
                    continue
                }
                val startMs = TimeUtils.parseTimeToMs(match.groupValues[1].replace(',', '.'))
                if (startMs == null) {
                    errors += BatchParseError(line, "分曲时间格式不正确：${match.groupValues[1]}")
                    continue
                }
                val name = match.groupValues[2].trim()
                builder.segments += SegmentSpec(
                    startMs = startMs,
                    title = name.ifBlank { "曲目 ${builder.segments.size + 1}" },
                )
            }
        }
        return BatchParseResult(
            items = builders.map { builder ->
                BatchImportItem(
                    source = builder.source,
                    bvidOrUrl = builder.bvidOrUrl,
                    albumTitle = builder.albumTitle,
                    artist = null,
                    mode = ImportMode.SINGLE_VIDEO,
                    segments = builder.segments,
                )
            },
            errors = errors,
        )
    }

    // -----------------------------------------------------------------------
    // 兜底：逐行单专辑
    // -----------------------------------------------------------------------

    private fun parseLineByLine(text: String): BatchParseResult {
        val items = mutableListOf<BatchImportItem>()
        val errors = mutableListOf<BatchParseError>()
        for (rawLine in text.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            val parts = line.split(WHITESPACE_REGEX, limit = 2)
            val refToken = parts[0]
            if (BiliUrlParser.parse(refToken) == null) {
                errors += BatchParseError(line, "无法识别为 BV 号或视频链接：$line")
                continue
            }
            items += BatchImportItem(
                source = line,
                bvidOrUrl = refToken,
                albumTitle = parts.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() },
            )
        }
        return BatchParseResult(items = items, errors = errors)
    }

    /** 用于去重的规范标识（同一 BV 的多种写法归并到一组）。 */
    private fun identityKey(rawRef: String): String? {
        return when (val ref = BiliUrlParser.parse(rawRef)) {
            is BiliUrlParser.VideoRef.Bvid -> "bv:${ref.bvid}"
            is BiliUrlParser.VideoRef.Aid -> "av:${ref.aid}"
            is BiliUrlParser.VideoRef.ShortUrl -> "url:${ref.url}"
            null -> null
        }
    }

    private companion object {
        const val CSV_COLUMNS = 5
        val WHITESPACE_REGEX = Regex("\\s+")
        /** 时间点 + 歌名（歌名可空）：支持 秒 / mm:ss / mm:ss.SSS / hh:mm:ss。 */
        val SEGMENT_LINE_REGEX = Regex("^([0-9]+(?::[0-9]+){0,2}(?:[.,][0-9]{1,3})?)\\s*(.*)$")
    }
}
