package com.wonderlandkid.biliaudio.util

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 维护/测试诊断日志。
 *
 * 用途：方便用户反馈问题时直接复制/导出最近日志，定位播放、视频缓冲、导入、网络等链路问题。
 *
 * 特点：
 * - 内存环形缓冲（最近 [MAX_LINES] 行），不占用存储、不影响性能；
 * - 同时输出到 Logcat（tag = `BiliDiag`），开发期可用 `adb logcat -s BiliDiag` 查看；
 * - 支持复制 / 清空 / 导出到文件（设置 → 诊断日志）；
 * - 仅记录技术链路信息，不记录 Cookie / 账号敏感信息（调用方注意不要传入）。
 */
object Diag {

    const val TAG = "BiliDiag"

    /** 内存中保留的最大日志行数。 */
    private const val MAX_LINES = 3000

    private val lock = Any()
    private val buffer = ArrayDeque<String>(MAX_LINES)
    private val wallClock = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val fileClock = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

    /** 进程启动时间（用于日志里的相对耗时）。 */
    private val processStartMs = System.currentTimeMillis()

    @Volatile
    var enabled: Boolean = true

    // ------------------------------------------------------------------
    // 记录
    // ------------------------------------------------------------------

    fun log(tag: String, message: String, error: Throwable? = null) {
        if (!enabled) return
        val line = buildLine(tag, message, error)
        synchronized(lock) {
            buffer.addLast(line)
            while (buffer.size > MAX_LINES) buffer.removeFirst()
        }
        runCatching { Log.i(TAG, line) }
    }

    /** 业务事件（建议 tag 用域名：Api/Play/Video/Import/Archive…）。 */
    fun event(tag: String, message: String) = log(tag, message)

    /** 便捷记录耗时：`Diag.span("Video") { ... }`。 */
    inline fun <T> span(tag: String, label: String, block: () -> T): T {
        val start = System.currentTimeMillis()
        return try {
            block().also { log(tag, "$label done in ${System.currentTimeMillis() - start}ms") }
        } catch (t: Throwable) {
            log(tag, "$label failed after ${System.currentTimeMillis() - start}ms", t)
            throw t
        }
    }

    // ------------------------------------------------------------------
    // 读取 / 导出
    // ------------------------------------------------------------------

    fun snapshot(): String = synchronized(lock) { buffer.joinToString(separator = "\n") }

    fun lineCount(): Int = synchronized(lock) { buffer.size }

    fun clear() = synchronized(lock) { buffer.clear() }

    /** 导出最近日志到应用私有目录 `files/logs/`，返回文件。 */
    fun exportToFile(context: Context): File {
        val dir = File(context.applicationContext.filesDir, "logs").apply { mkdirs() }
        val file = File(dir, "bili-diag-${fileClock.format(Date())}.log")
        val header = buildString {
            appendLine("BiliAudio 诊断日志")
            appendLine(deviceInfo(context))
            appendLine("导出于: ${wallClock.format(Date())}")
            appendLine("-----------------------------")
        }
        file.writeText(header + snapshot())
        return file
    }

    fun deviceInfo(context: Context): String {
        val versionName = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "?"
        return buildString {
            append("App: $versionName (${context.packageName})")
            append(" | Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            append(" | 设备: ${Build.BRAND} ${Build.MODEL}")
            append(" | ABI: ${Build.SUPPORTED_ABIS.joinToString(",")}")
            append(" | 运行时长: ${(System.currentTimeMillis() - processStartMs) / 1000}s")
        }
    }

    // ------------------------------------------------------------------

    private fun buildLine(tag: String, message: String, error: Throwable?): String {
        val now = System.currentTimeMillis()
        val thread = Thread.currentThread().name
        return buildString {
            append(wallClock.format(Date(now)))
            append(" +").append(now - processStartMs).append("ms")
            append(" [").append(tag).append(']')
            append(' ').append(message)
            append(" (").append(thread).append(')')
            if (error != null) {
                append(" | ").append(error.javaClass.simpleName).append(": ").append(error.message)
            }
        }
    }
}
