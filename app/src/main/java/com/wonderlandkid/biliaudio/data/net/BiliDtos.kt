package com.wonderlandkid.biliaudio.data.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * B 站接口响应 DTO。
 *
 * 约定：所有字段都有默认值，配合 [BiliJson] 的容错配置，接口少字段/多字段都不会崩溃。
 * 真实响应结构见 docs/API_NOTES.md。
 */

// ---------------------------------------------------------------------------
// /x/web-interface/nav（登录态 + wbi 密钥）
// ---------------------------------------------------------------------------

@Serializable
data class NavResponse(
    val code: Int = -1,
    val message: String? = null,
    val data: NavData? = null,
)

@Serializable
data class NavData(
    val isLogin: Boolean = false,
    val uname: String = "",
    val mid: Long = 0L,
    val face: String = "",
    @SerialName("wbi_img") val wbiImg: WbiImg? = null,
)

@Serializable
data class WbiImg(
    @SerialName("img_url") val imgUrl: String = "",
    @SerialName("sub_url") val subUrl: String = "",
)

// ---------------------------------------------------------------------------
// /x/web-interface/view（视频信息）
// ---------------------------------------------------------------------------

@Serializable
data class ViewResponse(
    val code: Int = -1,
    val message: String? = null,
    val data: ViewData? = null,
)

@Serializable
data class ViewData(
    val aid: Long = 0L,
    val bvid: String = "",
    val cid: Long = 0L,
    val title: String = "",
    /** 封面，可能是 http://，使用时统一转 https。 */
    val pic: String = "",
    /** 总时长（秒）。 */
    val duration: Long = 0L,
    /** 分 P 数。 */
    val videos: Int = 1,
    val owner: ViewOwner? = null,
    val pages: List<ViewPage> = emptyList(),
)

@Serializable
data class ViewOwner(
    val mid: Long = 0L,
    val name: String = "",
    val face: String = "",
)

@Serializable
data class ViewPage(
    val cid: Long = 0L,
    val page: Int = 0,
    val part: String = "",
    /** 分 P 时长（秒）。 */
    val duration: Long = 0L,
)

// ---------------------------------------------------------------------------
// /x/player/wbi/playurl（DASH 播放流）
// ---------------------------------------------------------------------------

@Serializable
data class PlayUrlResponse(
    val code: Int = -1,
    val message: String? = null,
    val data: PlayUrlData? = null,
)

@Serializable
data class PlayUrlData(
    val dash: Dash? = null,
    /** 部分老视频只返回 durl（FLV 分段），MVP 不支持。 */
    val durl: List<DashUrlEntry> = emptyList(),
)

@Serializable
data class Dash(
    val duration: Long = 0L,
    val audio: List<DashMedia> = emptyList(),
    val video: List<DashMedia> = emptyList(),
)

/**
 * DASH 音/视频流条目。
 *
 * 注意 B 站同时返回驼峰和蛇形两套字段（baseUrl / base_url、backupUrl / backup_url），
 * 这里都接收，取用第一个非空的。
 */
@Serializable
data class DashMedia(
    val id: Int = 0,
    @SerialName("baseUrl") val baseUrlCamel: String? = null,
    @SerialName("base_url") val baseUrlSnake: String? = null,
    @SerialName("backupUrl") val backupUrlCamel: List<String>? = null,
    @SerialName("backup_url") val backupUrlSnake: List<String>? = null,
    val bandwidth: Long = 0L,
    val codecs: String = "",
    @SerialName("mimeType") val mimeTypeCamel: String? = null,
    @SerialName("mime_type") val mimeTypeSnake: String? = null,
    val width: Int = 0,
    val height: Int = 0,
) {
    /** 主播放地址。 */
    val url: String?
        get() = baseUrlCamel?.takeIf { it.isNotBlank() }
            ?: baseUrlSnake?.takeIf { it.isNotBlank() }

    /** 备份地址（主地址 403/过期时理论上可降级；当前契约只透传一个地址）。 */
    val backupUrls: List<String>
        get() = backupUrlCamel?.filter { it.isNotBlank() }
            ?: backupUrlSnake?.filter { it.isNotBlank() }
            ?: emptyList()

    val mimeType: String?
        get() = mimeTypeCamel ?: mimeTypeSnake
}

@Serializable
data class DashUrlEntry(
    val url: String = "",
    @SerialName("backup_url") val backupUrl: List<String> = emptyList(),
)

// ---------------------------------------------------------------------------
// 扫码登录
// ---------------------------------------------------------------------------

@Serializable
data class QrGenerateResponse(
    val code: Int = -1,
    val message: String? = null,
    val data: QrGenerateData? = null,
)

@Serializable
data class QrGenerateData(
    /** 用于生成二维码图片的 URL。 */
    val url: String = "",
    @SerialName("qrcode_key") val qrcodeKey: String = "",
)

@Serializable
data class QrPollResponse(
    val code: Int = -1,
    val message: String? = null,
    val data: QrPollData? = null,
)

@Serializable
data class QrPollData(
    /** 登录成功时是跨域登录 URL（内含 Cookie 查询参数）。 */
    val url: String = "",
    @SerialName("refresh_token") val refreshToken: String = "",
    val timestamp: Long = 0L,
    /** 业务码：0 成功、86101 未扫码、86090 已扫码待确认、86038 已失效。 */
    val code: Int = -1,
    val message: String? = null,
)

// ---------------------------------------------------------------------------
// /x/frontend/finger/spi（未登录基础 Cookie：buvid3 / buvid4）
// ---------------------------------------------------------------------------

@Serializable
data class FingerResponse(
    val code: Int = -1,
    val message: String? = null,
    val data: FingerData? = null,
)

@Serializable
data class FingerData(
    @SerialName("b_3") val b3: String = "",
    @SerialName("b_4") val b4: String = "",
)
