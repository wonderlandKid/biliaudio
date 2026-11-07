package com.wonderlandkid.biliaudio.ui.login

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * 用 zxing core 把 B 站扫码登录 URL 编码成二维码 Bitmap。
 *
 * 不依赖 zxing 的 Android 端库（项目只引了 core），这里直接操作 BitMatrix → 像素数组。
 */
object QrCodeGenerator {

    /**
     * 生成二维码位图。
     *
     * @param content 二维码内容（扫码登录接口返回的 url）
     * @param sizePx 边长（像素）；实际展示时会缩放到 240dp 左右，取大一点保证清晰
     * @return 生成失败（内容过长 / 编码异常）时返回 null
     */
    fun generate(content: String, sizePx: Int = 720): Bitmap? = runCatching {
        if (content.isBlank() || sizePx <= 0) return@runCatching null
        val hints = mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 1,
        )
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val pixels = IntArray(sizePx * sizePx)
        for (y in 0 until sizePx) {
            val rowOffset = y * sizePx
            for (x in 0 until sizePx) {
                pixels[rowOffset + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
            }
        }
        Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, sizePx, 0, 0, sizePx, sizePx)
        }
    }.getOrNull()
}
