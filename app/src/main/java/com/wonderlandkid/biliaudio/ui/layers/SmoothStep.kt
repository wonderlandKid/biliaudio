package com.wonderlandkid.biliaudio.ui.layers

/** 平滑阶跃：progress 在 [start, end] 内从 0 平滑过渡到 1。 */
internal fun smoothStep(start: Float, end: Float, progress: Float): Float {
    if (end <= start) return if (progress >= end) 1f else 0f
    val t = ((progress - start) / (end - start)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}
