package com.wonderlandkid.biliaudio.ui.theme

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.drawable.toBitmap
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import coil3.SingletonImageLoader
import coil3.asDrawable
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import com.wonderlandkid.biliaudio.util.Diag

/**
 * 从当前歌曲封面取一个主题种子色（播放页局部主题用，不改变全局品牌色）。
 *
 * - 用 Coil 加载一张 96×96 缩略图，取像素做加权平均：高饱和、中间明度的像素权重更高；
 * - 灰度封面会得到中性灰种子（Material 3 TonalSpot 会生成灰阶主题）；
 * - 加载失败/无封面时回退到当前主题的 primary；
 * - 只在 [cover] 变化时重新计算，结果缓存在 Coil 的 bitmap 缓存里。
 */
@Composable
internal fun rememberCoverAccentColor(cover: Any?, fallback: Color): Color {
    val context = LocalContext.current
    // 保留上一首的封面色，直到新封面色算出来——避免切歌瞬间闪回品牌粉。
    var accent by remember { mutableStateOf(fallback) }
    LaunchedEffect(cover) {
        if (cover == null) {
            accent = fallback
            return@LaunchedEffect
        }
        val extracted = withContext(Dispatchers.IO) {
            runCatching { extractAccent(context, cover) }
                .onFailure {
                    // 只记封面类型，不记完整 URL（可能带签名）。
                    Diag.log(
                        "Theme",
                        "cover accent extract FAILED cover=${cover.javaClass.simpleName}, " +
                            "keep previous seed",
                        it,
                    )
                }
                .getOrNull()
        }
        accent = extracted ?: accent
    }
    return accent
}

private suspend fun extractAccent(
    context: android.content.Context,
    cover: Any,
): Color? {
    val request = ImageRequest.Builder(context)
        .data(cover)
        .size(96)
        .allowHardware(false)
        .build()
    val result = SingletonImageLoader.get(context).execute(request)
    val image = (result as? SuccessResult)?.image ?: return null
    val bitmap = image.asDrawable(context.resources).toBitmap(96, 96)
    return bitmapAccent(bitmap)
}

private fun bitmapAccent(bitmap: Bitmap): Color? {
    val width = bitmap.width
    val height = bitmap.height
    if (width <= 0 || height <= 0) return null
    val pixels = IntArray(width * height)
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

    var sumR = 0.0
    var sumG = 0.0
    var sumB = 0.0
    var weightSum = 0.0
    val hsv = FloatArray(3)
    for (pixel in pixels) {
        val alpha = AndroidColor.alpha(pixel)
        if (alpha < 200) continue
        AndroidColor.colorToHSV(pixel, hsv)
        val (h, s, v) = hsv
        // 忽略纯白、纯黑和过曝区域；高饱和、中间明度权重更高。
        if (v < 0.06f || v > 0.97f) continue
        if (s < 0.04f) {
            // 灰度封面：保留灰度信息，低权重参与平均。
            val w = 0.25
            sumR += AndroidColor.red(pixel) * w
            sumG += AndroidColor.green(pixel) * w
            sumB += AndroidColor.blue(pixel) * w
            weightSum += w
            continue
        }
        val weight = (0.35 + s * 0.65) * (0.35 + v * 0.65)
        sumR += AndroidColor.red(pixel) * weight
        sumG += AndroidColor.green(pixel) * weight
        sumB += AndroidColor.blue(pixel) * weight
        weightSum += weight
    }
    if (weightSum <= 0.0) return null
    val avg = AndroidColor.rgb(
        (sumR / weightSum).toInt().coerceIn(0, 255),
        (sumG / weightSum).toInt().coerceIn(0, 255),
        (sumB / weightSum).toInt().coerceIn(0, 255),
    )
    AndroidColor.colorToHSV(avg, hsv)
    var (h, s, v) = hsv
    // 避免取到几乎无色的灰白种子：有颜色时保证可辨识，灰度时保持中性。
    if (s > 0.08f) {
        s = s.coerceIn(0.45f, 0.85f)
        v = v.coerceIn(0.42f, 0.78f)
    } else {
        v = v.coerceIn(0.35f, 0.72f)
    }
    // 极端色相（接近纯红/纯绿）稍微拉回一点，避免 Material 主题过于刺眼。
    if (abs(h - 0f) < 8f || abs(h - 360f) < 8f) {
        h = (h + 12f) % 360f
    }
    return Color(AndroidColor.HSVToColor(floatArrayOf(h, s, v)))
}
