package com.wonderlandkid.biliaudio.data.net

import java.security.MessageDigest

/**
 * B 站 wbi 请求签名（算法见 docs/API_NOTES.md §1）。
 *
 * 流程：
 * 1. 从 `/x/web-interface/nav` 的 `data.wbi_img.img_url` / `sub_url` 取文件名（去扩展名）作为 imgKey / subKey；
 * 2. 拼成 64 字符串后按 [BiliConstants.MIXIN_KEY_ENC_TAB] 取前 32 位得到 mixinKey；
 * 3. 参数追加 `wts`（秒级时间戳）、按 key 字典序排序、URL 编码后拼 query；
 * 4. `w_rid = md5(query + mixinKey)`，最终 URL 追加 `w_rid`。
 *
 * 编码规则：仅保留 RFC 3986 unreserved 字符（`A-Za-z0-9-._~`），其余按 UTF-8 百分号编码（空格编码为 `%20`）。
 * 该规则已用真实接口验证（含中文/空格关键字）。
 */
object WbiSigner {

    /** 百分号编码用大写 hex（与标准 URL 编码一致，B 站签名校验依赖该规范）。 */
    private const val HEX_UPPER = "0123456789ABCDEF"

    /** MD5 输出用小写 hex。 */
    private const val HEX_LOWER = "0123456789abcdef"

    /** 计算 mixinKey（要求 imgKey + subKey 至少 64 字符）。 */
    fun mixinKey(imgKey: String, subKey: String): String {
        val origin = imgKey + subKey
        require(origin.length >= 64) { "wbi img/sub key 长度不足：${origin.length}" }
        val table = BiliConstants.MIXIN_KEY_ENC_TAB
        return buildString(32) {
            for (index in 0 until 32) append(origin[table[index]])
        }
    }

    /** 从 wbi 图片地址中取 key（最后一段文件名去扩展名）。 */
    fun keyFromUrl(url: String): String {
        val fileName = url.trim().substringBefore('?').substringBefore('#').substringAfterLast('/')
        return fileName.substringBeforeLast('.')
    }

    /**
     * 生成签名参数（原参数 + wts + w_rid）。
     * 调用 [encodeQuery] 即可得到可直接拼在 URL 后面的 query 串。
     */
    fun sign(
        params: Map<String, String>,
        imgKey: String,
        subKey: String,
        timestampSec: Long = System.currentTimeMillis() / 1000,
    ): Map<String, String> {
        val mixin = mixinKey(imgKey, subKey)
        val signed = LinkedHashMap<String, String>(params.size + 2)
        signed.putAll(params)
        signed["wts"] = timestampSec.toString()
        val query = encodeQuery(signed)
        signed["w_rid"] = md5Hex(query + mixin)
        return signed
    }

    /** 按 key 字典序排序并 URL 编码成 query（不含前导 `?`）。 */
    fun encodeQuery(params: Map<String, String>): String =
        params.keys.sorted().joinToString("&") { key ->
            "${percentEncode(key)}=${percentEncode(params[key].orEmpty())}"
        }

    /** UTF-8 百分号编码：仅保留 unreserved 字符（对应 RFC 3986）。 */
    fun percentEncode(value: String): String {
        val bytes = value.toByteArray(Charsets.UTF_8)
        val sb = StringBuilder(bytes.size)
        for (byte in bytes) {
            val code = byte.toInt() and 0xFF
            val char = code.toChar()
            when {
                code in 'A'.code..'Z'.code -> sb.append(char)
                code in 'a'.code..'z'.code -> sb.append(char)
                code in '0'.code..'9'.code -> sb.append(char)
                char == '-' || char == '_' || char == '.' || char == '~' -> sb.append(char)
                else -> {
                    sb.append('%')
                    sb.append(HEX_UPPER[(code shr 4) and 0xF])
                    sb.append(HEX_UPPER[code and 0xF])
                }
            }
        }
        return sb.toString()
    }

    /** MD5 小写十六进制。 */
    fun md5Hex(input: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(digest.size * 2)
        for (byte in digest) {
            val code = byte.toInt() and 0xFF
            sb.append(HEX_LOWER[(code shr 4) and 0xF])
            sb.append(HEX_LOWER[code and 0xF])
        }
        return sb.toString()
    }
}
