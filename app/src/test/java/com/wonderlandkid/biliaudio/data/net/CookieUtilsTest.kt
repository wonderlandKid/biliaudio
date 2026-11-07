package com.wonderlandkid.biliaudio.data.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cookie 解析单测（扫码登录 Set-Cookie / 跨域登录 URL）。 */
class CookieUtilsTest {

    @Test
    fun `解析普通 Set-Cookie`() {
        val parsed = CookieUtils.parseSetCookie(
            "SESSDATA=abc%2C123%2Cxyz; Path=/; Domain=.bilibili.com; Expires=Thu, 01 Jan 2026 00:00:00 GMT; HttpOnly",
        )
        assertEquals("SESSDATA" to "abc%2C123%2Cxyz", parsed)
    }

    @Test
    fun `Max-Age=0 视为删除`() {
        assertNull(CookieUtils.parseSetCookie("buvid3=xx; Max-Age=0; Path=/")?.second)
        assertNull(CookieUtils.parseSetCookie("foo=; Path=/")?.second)
    }

    @Test
    fun `非法 Set-Cookie 返回 null`() {
        assertNull(CookieUtils.parseSetCookie(null))
        assertNull(CookieUtils.parseSetCookie(""))
        assertNull(CookieUtils.parseSetCookie("; Path=/"))
    }

    @Test
    fun `从跨域登录 URL 提取 Cookie`() {
        val url = "https://passport.biligame.com/crossDomain?DedeUserID=123456" +
            "&DedeUserID__ckMd5=abcdef&SESSDATA=abc%2C123&bili_jct=jjjj&gourl=https%3A%2F%2Fwww.bilibili.com"
        val cookies = CookieUtils.cookiesFromLoginUrl(url)
        assertEquals("123456", cookies["DedeUserID"])
        assertEquals("abcdef", cookies["DedeUserID__ckMd5"])
        assertEquals("abc%2C123", cookies["SESSDATA"])
        assertEquals("jjjj", cookies["bili_jct"])
        // 未知参数不收录
        assertTrue(!cookies.containsKey("gourl"))
    }

    @Test
    fun `空 URL 提取结果为空`() {
        assertTrue(CookieUtils.cookiesFromLoginUrl("").isEmpty())
        assertTrue(CookieUtils.cookiesFromLoginUrl(null).isEmpty())
        assertTrue(CookieUtils.cookiesFromLoginUrl("https://www.bilibili.com").isEmpty())
    }

    @Test
    fun `按名称顺序拼 Cookie 请求头`() {
        val cookies = mapOf("SESSDATA" to "s", "buvid3" to "b3", "bili_jct" to "j")
        assertEquals(
            "SESSDATA=s; bili_jct=j",
            CookieUtils.buildCookieHeader(cookies, CookieUtils.SESSION_COOKIE_NAMES),
        )
        assertEquals(
            "SESSDATA=s; bili_jct=j; buvid3=b3",
            CookieUtils.buildCookieHeader(
                cookies,
                CookieUtils.SESSION_COOKIE_NAMES + CookieUtils.BASE_COOKIE_NAMES,
            ),
        )
        assertEquals("", CookieUtils.buildCookieHeader(emptyMap(), CookieUtils.SESSION_COOKIE_NAMES))
    }
}
