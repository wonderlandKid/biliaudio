package com.wonderlandkid.biliaudio.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade

/**
 * 统一封面加载组件：自动圆角、占位底色、渐显。
 */
@Composable
fun CoverImage(
    /** 任意 Coil 可加载的数据：远程 URL、本地 [File] 路径或 content Uri */
    url: Any?,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 16.dp,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val shape = RoundedCornerShape(cornerRadius)
    AsyncImage(
        model = ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
            .data(url)
            // 共享封面过渡要求 overlay 与真实封面逐帧一致；Coil 自带的 crossfade
            // 会在交接帧先淡出占位色，造成闪烁/发灰，所以封面统一不做交叉淡入。
            .crossfade(false)
            .build(),
        contentDescription = null,
        contentScale = contentScale,
        placeholder = ColorPainter(MaterialTheme.colorScheme.surfaceVariant),
        error = ColorPainter(MaterialTheme.colorScheme.surfaceVariant),
        modifier = modifier.clip(shape),
    )
}

/** 专辑封面的柔光渐变背景（用于发现页头部 / 播放页），不依赖 Palette。 */
@Composable
fun CoverGlowBackground(
    modifier: Modifier = Modifier,
    topColor: Color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
    bottomColor: Color = MaterialTheme.colorScheme.surface,
) {
    Box(
        modifier = modifier.background(
            Brush.verticalGradient(listOf(topColor, bottomColor)),
        ),
    )
}

/** 1:1 封面容器，占满宽度。 */
@Composable
fun SquareCover(
    url: String?,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 20.dp,
) {
    CoverImage(
        url = url,
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f),
        cornerRadius = cornerRadius,
    )
}
