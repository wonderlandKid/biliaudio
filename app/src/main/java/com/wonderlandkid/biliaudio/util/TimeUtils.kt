package com.wonderlandkid.biliaudio.util

import java.util.Locale
import kotlin.math.roundToLong

object TimeUtils {
    /** 毫秒 → "mm:ss" 或 "h:mm:ss"。 */
    fun formatDuration(ms: Long): String {
        val totalSeconds = (ms / 1000.0).roundToLong()
        val seconds = totalSeconds % 60
        val minutes = (totalSeconds / 60) % 60
        val hours = totalSeconds / 3600
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }

    /** 毫秒 → "mm:ss.SSS"（分割点编辑用，精确到毫秒；秒数向下取整，保证秒与毫秒一致）。 */
    fun formatDurationPrecise(ms: Long): String {
        val safeMs = ms.coerceAtLeast(0)
        val totalSeconds = safeMs / 1000
        val millis = safeMs % 1000
        val seconds = totalSeconds % 60
        val minutes = (totalSeconds / 60) % 60
        val hours = totalSeconds / 3600
        val base = if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
        return base + String.format(Locale.US, ".%03d", millis)
    }

    /** 毫秒 → "1 小时 23 分钟" 这类口语化描述。 */
    fun formatHumanDuration(ms: Long): String {
        if (ms in 0 until 60_000) return "不到 1 分钟"
        val totalMinutes = (ms / 60000.0).roundToLong()
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return when {
            hours > 0 && minutes > 0 -> "${hours} 小时 ${minutes} 分钟"
            hours > 0 -> "${hours} 小时"
            totalMinutes > 0 -> "${minutes} 分钟"
            else -> "不到 1 分钟"
        }
    }

    /**
     * 解析分割点时间。支持：
     * - "83"            → 83 秒
     * - "1:23"          → 1 分 23 秒
     * - "01:23.500"     → 1 分 23.5 秒
     * - "1:02:03"       → 1 小时 2 分 3 秒
     */
    fun parseTimeToMs(text: String): Long? {
        val raw = text.trim()
        if (raw.isEmpty()) return null
        if (raw.matches(Regex("^\\d+$"))) return raw.toLong() * 1000
        val parts = raw.split(':')
        if (parts.size !in 2..3) return null
        return try {
            val seconds = parts.last().replace(',', '.').toDouble()
            val minutes = parts[parts.size - 2].toLong()
            val hours = if (parts.size == 3) parts[0].toLong() else 0L
            if (seconds < 0 || minutes < 0 || hours < 0) return null
            hours * 3_600_000 + minutes * 60_000 + (seconds * 1000).roundToLong()
        } catch (_: NumberFormatException) {
            null
        }
    }

    /** 解析 "3:55" 这类 B 站时长文本。 */
    fun parseBiliDurationText(text: String): Long {
        // B 站搜索接口返回形如 "3:55" / "1:02:03"
        return parseTimeToMs(text) ?: 0L
    }
}
