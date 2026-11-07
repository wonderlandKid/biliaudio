package com.wonderlandkid.biliaudio.data.net

import com.wonderlandkid.biliaudio.util.TimeUtils

/**
 * B 站返回文本 / 图片地址的清洗工具（纯函数，方便单测）。
 *
 * 已知坑（docs/API_NOTES.md §6）：
 * - 搜索结果的标题带 `<em class="keyword">` 高亮标签和 HTML 实体；
 * - 搜索结果封面是 `//i1.hdslb.com/...` 或 `http://...`，必须转 https，否则 Android 禁明文。
 */
object BiliTextUtils {

    private val HTML_TAG = Regex("<[^>]*>")
    private val WHITESPACE = Regex("\\s+")

    /** 去 HTML 标签、解实体、压缩空白。 */
    fun cleanHtml(raw: String?): String {
        if (raw.isNullOrEmpty()) return ""
        val withoutTags = HTML_TAG.replace(raw, "")
        return WHITESPACE.replace(decodeEntities(withoutTags), " ").trim()
    }

    /** 补全图片地址协议：`//` → `https://`，`http://` → `https://`。 */
    fun normalizeImageUrl(raw: String?): String {
        val url = raw?.trim().orEmpty()
        if (url.isEmpty()) return ""
        return when {
            url.startsWith("//") -> "https:$url"
            url.startsWith("http://", ignoreCase = true) -> "https://" + url.substring("http://".length)
            url.startsWith("https://", ignoreCase = true) -> url
            else -> url
        }
    }

    /** 解析 JSON 里的数字/数字字符串（如播放量、分 P 数），失败返回 0。 */
    fun parseIntOrZero(raw: String?): Int = raw?.trim()?.toIntOrNull() ?: 0

    /**
     * 解 HTML 实体（`&amp;`、`&lt;`、`&#39;`、`&nbsp;`、十进制 `&#119815;`、十六进制 `&#x1D407;` 等）。
     *
     * B 站搜索结果存在**二次转义**的情况（`&amp;#119815;` / `&amp;amp;`），
     * 只解一次会残留 `&#119815;`、`&amp;` 直接显示在 UI 上，因此最多迭代解 3 轮直到稳定。
     */
    fun decodeEntities(text: String): String {
        if (!text.contains('&')) return text
        var current = text
        repeat(MAX_ENTITY_DECODE_PASSES) {
            val next = decodeEntitiesOnce(current)
            if (next == current) return current
            current = next
        }
        return current
    }

    private fun decodeEntitiesOnce(text: String): String {
        val sb = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            val char = text[index]
            if (char != '&') {
                sb.append(char)
                index++
                continue
            }
            val end = text.indexOf(';', startIndex = index + 1)
            // 实体长度一般不超过 8，找不到分号或距离过远就按普通字符处理
            if (end < 0 || end - index > 10) {
                sb.append(char)
                index++
                continue
            }
            val entity = text.substring(index + 1, end)
            val decoded = decodeEntity(entity)
            if (decoded == null) {
                sb.append(char)
                index++
            } else {
                sb.append(decoded)
                index = end + 1
            }
        }
        return sb.toString()
    }

    private fun decodeEntity(entity: String): String? {
        if (entity.isEmpty()) return null
        if (entity[0] == '#') {
            val codePoint = if (entity.length > 1 && (entity[1] == 'x' || entity[1] == 'X')) {
                entity.substring(2).toIntOrNull(16)
            } else {
                entity.substring(1).toIntOrNull()
            }
            // 0 与非法码点不解码；代理区码点保留（连续代理对可拼回 emoji）
            return codePoint?.takeIf { it in 1..0x10FFFF }?.let { String(Character.toChars(it)) }
        }
        return when (entity.lowercase()) {
            "amp" -> "&"
            "lt" -> "<"
            "gt" -> ">"
            "quot" -> "\""
            "apos" -> "'"
            "nbsp" -> " "
            "ensp", "emsp" -> " "
            else -> null
        }
    }

    /**
     * 归一时长文本：B 站搜索结果可能是非补零格式（`4:9` 表示 4 分 09 秒），
     * 解析为毫秒后重新格式化成 `04:09`；无法解析的值原样保留（如 `"--:--"`）。
     */
    fun normalizeDurationText(raw: String?): String {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return ""
        val ms = TimeUtils.parseTimeToMs(text) ?: return text
        return TimeUtils.formatDuration(ms)
    }

    /** HTML 实体最多迭代解码轮数（覆盖 B 站二次转义的情况）。 */
    private const val MAX_ENTITY_DECODE_PASSES = 3
}
