package com.wonderlandkid.biliaudio.data.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * wbi 签名单测。
 *
 * 测试向量用 docs/API_NOTES.md 中的真实密钥（nav 接口实测返回）：
 * img_key = 7cd084941338484aae1ad9425b84077c
 * sub_key = 4932caff0ff746eab6f01bf08b70ac45
 * 已知 mixin_key = ea1db124af3c7062474693fa704f4ff8（B 站社区公开且已联调验证）。
 * 签名结果由同一算法独立实现（Python）计算后固化，离线断言。
 */
class WbiSignerTest {

    private val imgUrl = "https://i0.hdslb.com/bfs/wbi/7cd084941338484aae1ad9425b84077c.png"
    private val subUrl = "https://i0.hdslb.com/bfs/wbi/4932caff0ff746eab6f01bf08b70ac45.png"
    private val imgKey = "7cd084941338484aae1ad9425b84077c"
    private val subKey = "4932caff0ff746eab6f01bf08b70ac45"
    private val expectedMixinKey = "ea1db124af3c7062474693fa704f4ff8"

    @Test
    fun `从 wbi 图片 URL 提取 key`() {
        assertEquals(imgKey, WbiSigner.keyFromUrl(imgUrl))
        assertEquals(subKey, WbiSigner.keyFromUrl(subUrl))
        // 带 query / 无扩展名也要能处理
        assertEquals("abcdef", WbiSigner.keyFromUrl("https://i0.hdslb.com/bfs/wbi/abcdef.png?x=1"))
        assertEquals("abcdef", WbiSigner.keyFromUrl("https://i0.hdslb.com/bfs/wbi/abcdef"))
    }

    @Test
    fun `mixinKey 与已知值一致`() {
        assertEquals(expectedMixinKey, WbiSigner.mixinKey(imgKey, subKey))
    }

    @Test
    fun `key 长度不足会抛异常`() {
        assertThrows(IllegalArgumentException::class.java) {
            WbiSigner.mixinKey("abc", "def")
        }
    }

    @Test
    fun `playurl 参数签名的 query 与 w_rid 正确`() {
        val params = mapOf(
            "bvid" to "BV1GJ411x7h7",
            "cid" to "137649199",
            "fnval" to "16",
            "fnver" to "0",
            "fourk" to "1",
        )
        val signed = WbiSigner.sign(params, imgKey, subKey, timestampSec = 1_700_000_000L)
        assertEquals("2ddab2e23bffac0c51532d2641f11f1a", signed["w_rid"])
        assertEquals("1700000000", signed["wts"])
        // 字典序：bvid < cid < fnval < fnver < fourk < w_rid < wts
        assertEquals(
            "bvid=BV1GJ411x7h7&cid=137649199&fnval=16&fnver=0&fourk=1" +
                "&w_rid=2ddab2e23bffac0c51532d2641f11f1a&wts=1700000000",
            WbiSigner.encodeQuery(signed),
        )
    }

    @Test
    fun `中文与空格参数按 UTF-8 百分号编码签名`() {
        val params = mapOf(
            "search_type" to "video",
            "keyword" to "周杰伦 专辑",
            "page" to "1",
        )
        val signed = WbiSigner.sign(params, imgKey, subKey, timestampSec = 1_700_000_000L)
        assertEquals("e74a5a73fbee4eb5db28c1d1e5b50f8a", signed["w_rid"])
        assertEquals(
            "keyword=%E5%91%A8%E6%9D%B0%E4%BC%A6%20%E4%B8%93%E8%BE%91&page=1" +
                "&search_type=video&wts=1700000000",
            WbiSigner.encodeQuery(signed - "w_rid"),
        )
    }

    @Test
    fun `特殊字符编码规则`() {
        assertEquals("a%20b%26c%3Dd%2F%E4%B8%AD%E6%96%87", WbiSigner.percentEncode("a b&c=d/中文"))
        // unreserved 字符原样保留
        assertEquals("AZaz09-._~", WbiSigner.percentEncode("AZaz09-._~"))
        assertEquals("%25", WbiSigner.percentEncode("%"))
    }

    @Test
    fun `md5 小写十六进制`() {
        assertEquals("900150983cd24fb0d6963f7d28e17f72", WbiSigner.md5Hex("abc"))
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", WbiSigner.md5Hex(""))
    }

    @Test
    fun `参数缺省时 wts 自动补齐`() {
        val signed = WbiSigner.sign(mapOf("foo" to "bar"), imgKey, subKey)
        assertTrue(signed.containsKey("wts"))
        assertEquals(32, signed.getValue("w_rid").length)
        assertNull(signed["not_exist"])
    }
}
