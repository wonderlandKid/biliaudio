package com.wonderlandkid.biliaudio.ui.layers

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex

/**
 * 应用内持久 chrome 的图层深度。
 *
 * 只用于「同一父容器内、真正会互相覆盖」的常驻层：
 * 页面内容（根 Tab 底栏属于页面自身） < 播放器 surface < 应用内弹层。
 *
 * 页面之间的切换（Tab、详情 push/pop、封面/歌词 Pager）仍由 Content 层内部的 NavHost/Pager 负责，
 * 不要给每个页面单独分配 z 值——否则会退化成全局 zIndex 面条。
 */
object AppLayer {
    const val Content = 0f
    const val MiniPlayer = 200f
    const val Player = 300f
    const val Modal = 400f
}

/** 所有 chrome overlay 必须挂在同一个 AppLayerHost 下，zIndex 才会生效。 */
@Composable
fun AppLayerHost(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier = modifier.fillMaxSize(), content = content)
}

fun Modifier.layer(depth: Float): Modifier = zIndex(depth)
