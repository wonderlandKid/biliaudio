package com.wonderlandkid.biliaudio.ui.player

import androidx.lifecycle.ViewModel
import com.wonderlandkid.biliaudio.player.PlaybackController
import com.wonderlandkid.biliaudio.player.PlaybackState
import kotlinx.coroutines.flow.StateFlow

/**
 * 「正在播放」页 ViewModel：只做状态转发 + 控制指令转发，业务状态全部在 [PlaybackController]。
 *
 * 用 `appViewModel { container -> NowPlayingViewModel(container.playbackController) }` 创建，
 * 页面用 `collectAsStateWithLifecycle()` 订阅 [state]。
 */
class NowPlayingViewModel(
    private val controller: PlaybackController,
) : ViewModel() {

    /** 播放状态（曲目 / 队列 / 进度 / 循环随机 / 错误）。 */
    val state: StateFlow<PlaybackState> = controller.state

    fun togglePlayPause() = controller.togglePlayPause()

    fun play() = controller.play()

    fun pause() = controller.pause()

    fun next() = controller.next()

    fun previous() = controller.previous()

    /** 拖动分曲进度条后调用（曲内进度）。 */
    fun seekToTrackPosition(positionMs: Long) = controller.seekToTrackPosition(positionMs)

    /** 播放列表面板点选某首。 */
    fun jumpToQueueIndex(index: Int) = controller.jumpToQueueIndex(index)

    fun cycleRepeatMode() = controller.cycleRepeatMode()

    fun toggleShuffle() = controller.toggleShuffle()

    fun dismissError() = controller.dismissError()
}
