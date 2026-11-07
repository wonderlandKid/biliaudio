package com.wonderlandkid.biliaudio.player

import androidx.media3.common.PlaybackException
import com.wonderlandkid.biliaudio.data.model.BiliApiException

/**
 * 播放错误 → 用户可读中文文案（纯逻辑，便于单元测试）。
 *
 * 播放器不会把异常直接抛给 UI：引擎把 [PlaybackException] / 准备阶段异常统一转换成这里的文案，
 * 放进 [PlaybackState.error] 由页面用 Snackbar / 错误卡片展示。
 */
internal object PlayerErrorMessages {

    /** 音频流不可用（解析失败 / 地址过期）。 */
    const val AUDIO_STREAM_UNAVAILABLE = "音频流获取失败，请重试"

    /** 歌词页视频流不可用。 */
    const val VIDEO_STREAM_UNAVAILABLE = "视频流获取失败，请重试"

    /** 通用网络错误。 */
    const val NETWORK = "网络连接失败，请检查网络后重试"

    /** 流格式 / 解析问题。 */
    const val STREAM_PARSE = "音频流解析失败，请稍后重试"

    /** 播放异常错误码 → 文案。 */
    fun forPlaybackErrorCode(errorCode: Int): String = when (errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> NETWORK

        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
        PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE -> "$AUDIO_STREAM_UNAVAILABLE（链接可能已过期）"

        PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
        PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED -> "音频流被拒绝访问，请重新登录后重试"

        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED -> STREAM_PARSE

        else -> "播放失败，请重试（错误码 $errorCode）"
    }

    /**
     * 准备播放阶段（解析 / 查库 / 构建队列）异常 → 文案。
     * 优先使用异常自带的用户可读信息（如「该专辑还没有分曲」）。
     */
    fun forException(error: Throwable): String = when (error) {
        is BiliApiException -> error.message ?: AUDIO_STREAM_UNAVAILABLE
        is IllegalStateException -> error.message ?: AUDIO_STREAM_UNAVAILABLE
        is IllegalArgumentException -> error.message ?: AUDIO_STREAM_UNAVAILABLE
        else -> AUDIO_STREAM_UNAVAILABLE
    }
}
