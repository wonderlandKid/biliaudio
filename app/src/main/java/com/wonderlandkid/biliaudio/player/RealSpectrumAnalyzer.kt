package com.wonderlandkid.biliaudio.player

import android.media.audiofx.Visualizer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * 真实音频频谱（用于播放进度条的波浪）。
 *
 * 通过 Android `Visualizer` 绑定 ExoPlayer 的 audioSessionId，周期性读取波形（PCM），
 * 用 Goertzel 算法估算 8 个频段的能量；需要 `RECORD_AUDIO` 权限（由 UI 在用户确认后申请）。
 *
 * - 只在播放页可见时创建，销毁时立即 release；
 * - 任何异常（权限被拒、模拟器不支持音频会话等）都静默失败，UI 自动回退到合成波形；
 * - 回调在 Visualizer 的内部线程，回调里只做纯计算，不持有 Activity/Compose 上下文。
 */
class RealSpectrumAnalyzer(
    private val audioSessionId: Int,
    private val onBands: (FloatArray) -> Unit,
) {
    companion object {
        const val BAND_COUNT = 8
        private val BAND_FREQUENCIES = floatArrayOf(60f, 120f, 250f, 500f, 1_000f, 2_000f, 4_000f, 8_000f)
        private const val CAPTURE_SIZE = 512
        private const val SMOOTH_DECAY = 0.90f
    }

    private var visualizer: Visualizer? = null
    private val smoothed = FloatArray(BAND_COUNT)

    /** @return true = 成功启动（权限/设备支持都 OK）。 */
    fun start(): Boolean {
        if (audioSessionId == 0) return false
        if (visualizer != null) return true
        return runCatching {
            val v = Visualizer(audioSessionId)
            v.captureSize = CAPTURE_SIZE.coerceIn(
                Visualizer.getCaptureSizeRange()[0],
                Visualizer.getCaptureSizeRange()[1],
            )
            v.setDataCaptureListener(
                object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(
                        visualizer: Visualizer?,
                        waveform: ByteArray?,
                        samplingRate: Int,
                    ) {
                        val data = waveform ?: return
                        val bands = computeBands(data, samplingRate)
                        for (i in 0 until BAND_COUNT) {
                            smoothed[i] = max(bands[i], smoothed[i] * SMOOTH_DECAY)
                        }
                        onBands(smoothed.copyOf())
                    }

                    override fun onFftDataCapture(
                        visualizer: Visualizer?,
                        fft: ByteArray?,
                        samplingRate: Int,
                    ) = Unit
                },
                Visualizer.getMaxCaptureRate(),
                true,
                false,
            )
            v.enabled = true
            visualizer = v
            true
        }.getOrDefault(false)
    }

    fun release() {
        runCatching {
            visualizer?.enabled = false
            visualizer?.release()
        }
        visualizer = null
    }

    /** Goertzel：对 8 个目标频段计算能量，转成 0..1 的对数刻度。 */
    private fun computeBands(waveform: ByteArray, sampleRate: Int): FloatArray {
        val n = waveform.size
        if (n <= 1 || sampleRate <= 0) return FloatArray(BAND_COUNT)
        val samples = FloatArray(n) { index ->
            val pcm = (waveform[index].toInt() and 0xFF) - 128
            val window = 0.5f * (1f - cos((2.0 * PI * index / (n - 1)).toFloat()))
            pcm / 128f * window
        }
        val result = FloatArray(BAND_COUNT)
        for (band in 0 until BAND_COUNT) {
            val frequency = BAND_FREQUENCIES[band]
            if (frequency >= sampleRate / 2f) continue
            val coeff = 2f * cos((2.0 * PI * frequency / sampleRate).toFloat())
            var s0 = 0f
            var s1 = 0f
            var s2 = 0f
            for (sample in samples) {
                s0 = sample + coeff * s1 - s2
                s2 = s1
                s1 = s0
            }
            val power = s1 * s1 + s2 * s2 - coeff * s1 * s2
            val magnitude = sqrt(max(power, 0f)) / n
            // 对数压缩，让低电平也可见；再按频段轻微加权（低频通常能量更高）。
            val db = 20f * log10(magnitude + 1e-6f)
            result[band] = ((db + 60f) / 60f).coerceIn(0f, 1f)
        }
        return result
    }
}
