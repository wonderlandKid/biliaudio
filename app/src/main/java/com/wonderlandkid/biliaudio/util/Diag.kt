package com.wonderlandkid.biliaudio.util

import android.content.Context
import android.os.Build
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 诊断日志：内存环形缓冲 + Logcat（tag `BiliDiag`），支持复制/清空/导出（设置 → 诊断日志）。
 * 只记录技术链路，禁止写入 Cookie/账号等敏感信息；tag 规范见 docs/DEBUGGING.md。
 */
object Diag {

    const val TAG = "BiliDiag"

    /** 内存中保留的最大日志行数。 */
    private const val MAX_LINES = 3000

    private val lock = Any()
    private val buffer = ArrayDeque<String>(MAX_LINES)
    // SimpleDateFormat 非线程安全：主线程/IO/播放线程都会写日志，用 ThreadLocal 隔离。
    private val wallClock = ThreadLocal.withInitial {
        SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    }
    private val fileClock = ThreadLocal.withInitial {
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
    }

    /** 进程启动时间（用于日志里的相对耗时）。 */
    private val processStartMs = System.currentTimeMillis()

    @Volatile
    var enabled: Boolean = true

    // ------------------------------------------------------------------
    // 记录
    // ------------------------------------------------------------------

    fun log(tag: String, message: String, error: Throwable? = null) {
        if (!enabled) return
        // 日志设施本身不允许抛异常：格式化/写环形缓冲/Logcat 全部兜底，
        // 否则日志量增大（多线程）时可能把异常抛回播放/UI 调用方。
        runCatching {
            val line = buildLine(tag, message, error)
            synchronized(lock) {
                buffer.addLast(line)
                while (buffer.size > MAX_LINES) buffer.removeFirst()
            }
            Log.i(TAG, line)
        }
    }

    /** 业务事件（建议 tag 用域名：Api/Play/Service/Import/Archive…）。 */
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
            append(wallClock.get().format(Date(now)))
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
