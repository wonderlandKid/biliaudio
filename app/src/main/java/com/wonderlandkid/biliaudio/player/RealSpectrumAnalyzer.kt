package com.wonderlandkid.biliaudio.player

import android.media.audiofx.Visualizer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 真实音频频谱（用于播放进度条）。
 *
 * 通过 Android `Visualizer` 绑定 ExoPlayer 的 audioSessionId，周期读取 512~1024 点 PCM 波形，
 * 自己做 Hann 窗 + 基 2 FFT，得到 48 个对数频段（约 32Hz~16kHz）的能量；需要 `RECORD_AUDIO` 权限
 * （由 UI 首次打开播放页时说明并申请）。
 *
 * - 只在播放页可见时创建，销毁时立即 release；
 * - 回调在 Visualizer 内部线程，只做纯计算，不持有 Activity/Compose 上下文；
 * - 任何异常（权限被拒、模拟器不支持音频会话等）都静默失败，UI 会显示低电平占位（不再用假波形）。
 */
class RealSpectrumAnalyzer(
    private val audioSessionId: Int,
    private val onBands: (FloatArray) -> Unit,
) {
    companion object {
        const val BAND_COUNT = 48
        private const val MIN_FREQ_HZ = 32f
        private const val MAX_FREQ_HZ = 16_000f
        private const val TARGET_CAPTURE = 1024
        private const val SMOOTH_UP = 0.55f
        private const val SMOOTH_DOWN = 0.18f
    }

    private var visualizer: Visualizer? = null

    private var fftSize = 0
    private var real = FloatArray(0)
    private var imag = FloatArray(0)
    private var window = FloatArray(0)
    private val bands = FloatArray(BAND_COUNT)
    private val smoothed = FloatArray(BAND_COUNT)

    /** @return true = 成功启动（权限/设备支持都 OK）。 */
    fun start(): Boolean {
        if (audioSessionId == 0) return false
        if (visualizer != null) return true
        return runCatching {
            val v = Visualizer(audioSessionId)
            val range = Visualizer.getCaptureSizeRange()
            val captureSize = largestPowerOfTwoWithin(TARGET_CAPTURE, range[0], range[1])
            v.captureSize = captureSize
            v.setDataCaptureListener(
                object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(
                        visualizer: Visualizer?,
                        waveform: ByteArray?,
                        samplingRate: Int,
                    ) {
                        val data = waveform ?: return
                        val result = computeBands(data, samplingRate)
                        onBands(result)
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

    private fun computeBands(waveform: ByteArray, visualizerSampleRate: Int): FloatArray {
        val sampleCount = waveform.size
        if (sampleCount < 32 || visualizerSampleRate <= 0) return FloatArray(BAND_COUNT)
        // Visualizer 的 samplingRate 单位是 milliHz（例如 48000000 = 48kHz）。
        val sampleRate = if (visualizerSampleRate > 100_000) visualizerSampleRate / 1000 else visualizerSampleRate
        val size = powerOfTwoAtMost(sampleCount)
        ensureBuffers(size)
        // PCM 8-bit unsigned → Hann 窗
        for (i in 0 until size) {
            val pcm = ((waveform[i].toInt() and 0xFF) - 128) / 128f
            real[i] = pcm * window[i]
            imag[i] = 0f
        }
        fft(real, imag)

        val nyquist = sampleRate / 2f
        // 第一遍：算各频段真实能量（含高频等响度补偿）
        for (band in 0 until BAND_COUNT) {
            val low = MIN_FREQ_HZ * (MAX_FREQ_HZ / MIN_FREQ_HZ).pow(band / BAND_COUNT.toFloat())
            val high = MIN_FREQ_HZ * (MAX_FREQ_HZ / MIN_FREQ_HZ).pow((band + 1) / BAND_COUNT.toFloat())
            val lowHz = low.coerceAtMost(nyquist)
            val highHz = high.coerceAtMost(nyquist)
            val startBin = ((lowHz / sampleRate) * size).toInt().coerceIn(1, size / 2 - 1)
            val endBin = ((highHz / sampleRate) * size).toInt().coerceIn(startBin + 1, size / 2)
            var power = 0.0
            for (bin in startBin until endBin) {
                val r = real[bin]
                val im = imag[bin]
                power += (r * r + im * im).toDouble()
            }
            val magnitude = sqrt(power / (endBin - startBin).coerceAtLeast(1)) / size
            val db = 20.0 * log10(magnitude + 1e-7)
            val raw = ((db + 80.0) / 80.0).toFloat().coerceIn(0f, 1f)
            val tilt = 1f + 0.55f * (band / BAND_COUNT.toFloat())
            bands[band] = (raw * tilt).coerceIn(0f, 1f)
        }
        // 第二遍：当前帧做相对归一化（保留真实频谱形状，但让小信号也有明显高度差）。
        var frameMax = 0.05f
        for (value in bands) if (value > frameMax) frameMax = value
        val scale = 1f / frameMax
        for (band in 0 until BAND_COUNT) {
            val normalized = (bands[band] * scale).coerceIn(0f, 1f)
            val previous = smoothed[band]
            smoothed[band] = if (normalized > previous) {
                previous + (normalized - previous) * SMOOTH_UP
            } else {
                previous + (normalized - previous) * SMOOTH_DOWN
            }
            bands[band] = smoothed[band]
        }
        return bands.copyOf()
    }

    private fun ensureBuffers(size: Int) {
        if (size == fftSize) return
        fftSize = size
        real = FloatArray(size)
        imag = FloatArray(size)
        window = FloatArray(size) { index ->
            (0.5 * (1.0 - cos(2.0 * PI * index / (size - 1)))).toFloat()
        }
        // 重置平滑值，避免切歌/尺寸变化时残留旧频谱。
        smoothed.fill(0f)
        bands.fill(0f)
    }

    private fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var length = 2
        while (length <= n) {
            val angle = -2.0 * PI / length
            val wLenCos = cos(angle).toFloat()
            val wLenSin = sin(angle).toFloat()
            var start = 0
            while (start < n) {
                var wReal = 1f
                var wImag = 0f
                for (k in 0 until length / 2) {
                    val evenIndex = start + k
                    val oddIndex = evenIndex + length / 2
                    val oddReal = re[oddIndex] * wReal - im[oddIndex] * wImag
                    val oddImag = re[oddIndex] * wImag + im[oddIndex] * wReal
                    re[oddIndex] = re[evenIndex] - oddReal
                    im[oddIndex] = im[evenIndex] - oddImag
                    re[evenIndex] += oddReal
                    im[evenIndex] += oddImag
                    val nextReal = wReal * wLenCos - wImag * wLenSin
                    wImag = wReal * wLenSin + wImag * wLenCos
                    wReal = nextReal
                }
                start += length
            }
            length = length shl 1
        }
    }
}

private fun powerOfTwoAtMost(value: Int): Int {
    var result = 1
    while (result * 2 <= value) result *= 2
    return result
}

private fun largestPowerOfTwoWithin(target: Int, min: Int, max: Int): Int {
    // 取目标 1024 与设备上限的较小值（必须是 2 的幂），不能一路缩到 min（否则频率分辨率太差）。
    return powerOfTwoAtMost(target).coerceIn(min, max)
}
