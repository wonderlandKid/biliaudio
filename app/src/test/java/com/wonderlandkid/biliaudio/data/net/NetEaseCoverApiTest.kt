package com.wonderlandkid.biliaudio.data.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 网易云专辑搜索响应解析（离线，不依赖网络）。 */
class NetEaseCoverApiTest {

    @Test
    fun `解析专辑搜索结果并生成缩略图`() {
        val body = """
            {
              "result": {
                "albums": [
                  {
                    "id": 372081313,
                    "name": "叶惠美",
                    "picUrl": "http://p2.music.126.net/vwUBxTBt3BazxX8QllCxlg==/109951173094981144.jpg",
                    "artists": [{"name": "周杰伦"}]
                  },
                  {
                    "id": 437968,
                    "name": "Abbey Road (Remastered)",
                    "picUrl": "https://p1.music.126.net/A_V9_72alwfoLwEKftkRsg==/109951163180920481.jpg",
                    "artists": [{"name": "The Beatles"}]
                  }
                ]
              }
            }
        """.trimIndent()
        val list = NetEaseCoverApi.parseAlbums(body)
        assertEquals(2, list.size)
        assertEquals("叶惠美", list[0].title)
        assertEquals("周杰伦", list[0].artist)
        // http → https
        assertTrue(list[0].fullUrl.startsWith("https://"))
        assertTrue(list[0].thumbnailUrl.contains("param=300y300"))
        assertEquals("Abbey Road (Remastered)", list[1].title)
    }

    @Test
    fun `空或异常响应返回空列表`() {
        assertTrue(NetEaseCoverApi.parseAlbums("").isEmpty())
        assertTrue(NetEaseCoverApi.parseAlbums("not json").isEmpty())
        assertTrue(NetEaseCoverApi.parseAlbums("""{"code":400}""").isEmpty())
        assertTrue(NetEaseCoverApi.parseAlbums("""{"result":{"albums":null}}""").isEmpty())
    }

    @Test
    fun `缺字段的专辑条目被跳过`() {
        val body = """
            {"result":{"albums":[
              {"id":1,"name":"没有封面"},
              {"id":2,"name":"正常","picUrl":"http://p.music.126.net/a.jpg","artists":[{"name":"A"},{"name":"B"}]}
            ]}}
        """.trimIndent()
        val list = NetEaseCoverApi.parseAlbums(body)
        assertEquals(1, list.size)
        assertEquals("A / B", list[0].artist)
    }

    @Test
    fun `缩略图 URL 已带参数时不重复拼接`() {
        assertEquals(
            "https://p.music.126.net/a.jpg?param=300y300",
            NetEaseCoverApi.thumbnailUrl("https://p.music.126.net/a.jpg?param=300y300"),
        )
        assertEquals("https://p.music.126.net/a.jpg?param=300y300", NetEaseCoverApi.thumbnailUrl("http://p.music.126.net/a.jpg"))
    }
}
