package com.wonderlandkid.biliaudio.data.net

/**
 * 从用户输入中识别 B 站视频引用（纯函数，便于单测）。
 *
 * 支持：
 * - BV 号：`BV1GJ411x7h7`；
 * - av 号：`av80433022`、`/video/av80433022`、`?aid=80433022`；
 * - 完整链接：`https://www.bilibili.com/video/BV...?...`；
 * - 短链：`https://b23.tv/xxxx`（需要联网解析 302，返回 [VideoRef.ShortUrl] 交给上层处理）。
 */
object BiliUrlParser {

    sealed interface VideoRef {
        data class Bvid(val bvid: String) : VideoRef
        data class Aid(val aid: Long) : VideoRef
        data class ShortUrl(val url: String) : VideoRef
    }

    /** 标准 BV 号 = "BV" + 10 位字母数字。 */
    private val BV_REGEX = Regex("(?i)BV[0-9A-Za-z]{10}")
    private val AV_REGEX = Regex("(?i)(?:^|[^0-9A-Za-z])av(\\d+)")
    private val AID_PARAM_REGEX = Regex("(?i)[?&]aid=(\\d+)")
    private val B23_URL_REGEX = Regex("(?i)https?://b23\\.tv/[A-Za-z0-9]+")
    private val B23_BARE_REGEX = Regex("(?i)^b23\\.tv/[A-Za-z0-9]+")

    fun parse(input: String?): VideoRef? {
        val raw = input?.trim().orEmpty()
        if (raw.isEmpty()) return null

        // 1) b23.tv 短链（优先于 BV 匹配，短链本身可能没有 BV 号）
        B23_URL_REGEX.find(raw)?.let { return VideoRef.ShortUrl(it.value) }
        B23_BARE_REGEX.find(raw)?.let { return VideoRef.ShortUrl("https://${it.value}") }

        // 2) BV 号（裸号或链接中的任意位置）
        BV_REGEX.find(raw)?.let { match ->
            // 前缀统一成大写 "BV"，其余字符保持用户输入
            return VideoRef.Bvid("BV" + match.value.substring(2))
        }

        // 3) av 号
        AV_REGEX.find(raw)?.let { match ->
            match.groupValues.getOrNull(1)?.toLongOrNull()?.let { aid ->
                if (aid > 0) return VideoRef.Aid(aid)
            }
        }
        AID_PARAM_REGEX.find(raw)?.let { match ->
            match.groupValues.getOrNull(1)?.toLongOrNull()?.let { aid ->
                if (aid > 0) return VideoRef.Aid(aid)
            }
        }
        return null
    }
}
