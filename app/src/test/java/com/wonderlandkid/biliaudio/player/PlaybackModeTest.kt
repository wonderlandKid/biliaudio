package com.wonderlandkid.biliaudio.player

import org.junit.Assert.assertEquals
import org.junit.Test

/** v0.33.0 统一播放模式（一个按钮循环切换）的纯逻辑测试。 */
class PlaybackModeTest {

    @Test
    fun `mode cycles in product order`() {
        assertEquals(PlaybackMode.SEQUENTIAL, PlaybackMode.SHUFFLE.next())
        assertEquals(PlaybackMode.LOOP_ALL, PlaybackMode.SEQUENTIAL.next())
        assertEquals(PlaybackMode.LOOP_ONE, PlaybackMode.LOOP_ALL.next())
        assertEquals(PlaybackMode.SHUFFLE, PlaybackMode.LOOP_ONE.next())
    }

    @Test
    fun `state maps shuffle and repeat into unified playback mode`() {
        assertEquals(PlaybackMode.SHUFFLE, PlaybackState(shuffle = true).playbackMode)
        assertEquals(PlaybackMode.SEQUENTIAL, PlaybackState().playbackMode)
        assertEquals(
            PlaybackMode.LOOP_ALL,
            PlaybackState(repeatMode = RepeatMode.ALL).playbackMode,
        )
        assertEquals(
            PlaybackMode.LOOP_ONE,
            PlaybackState(repeatMode = RepeatMode.ONE).playbackMode,
        )
    }
}
