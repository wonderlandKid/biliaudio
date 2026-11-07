package com.wonderlandkid.biliaudio.data.net

/**
 * B 站接口通用常量。注意：B 站 CDN 对请求头校验严格，
 * 媒体流（音频/视频/图片）必须带 Referer 和桌面 UA，否则 403。
 */
object BiliConstants {
    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    const val REFERER = "https://www.bilibili.com"
    const val ORIGIN = "https://www.bilibili.com"

    const val API_BASE = "https://api.bilibili.com"
    const val PASSPORT_BASE = "https://passport.bilibili.com"

    /** 播放流地址里的 Referer 一定要是视频页或主站 */
    const val MEDIA_REFERER = "https://www.bilibili.com"

    /** wbi 签名用的固定重排表 */
    val MIXIN_KEY_ENC_TAB = intArrayOf(
        46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
        27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13,
        37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4,
        22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36, 20, 34, 44, 52,
    )
}
