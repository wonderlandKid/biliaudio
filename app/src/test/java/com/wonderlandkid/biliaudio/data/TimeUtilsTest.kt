package com.wonderlandkid.biliaudio.data

import com.wonderlandkid.biliaudio.util.TimeUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 时间解析/格式化单测（util/TimeUtils 的解析行为，导入与搜索时长解析都依赖它）。 */
class TimeUtilsTest {

    @Test
    fun `格式化时长`() {
        assertEquals("00:00", TimeUtils.formatDuration(0L))
        assertEquals("01:23", TimeUtils.formatDuration(83_000L))
        assertEquals("1:02:03", TimeUtils.formatDuration(3_723_000L))
        // 注意：formatDuration 内部对秒四舍五入（共享工具类现状），毫秒部分需保持一致
        assertEquals("01:23.400", TimeUtils.formatDurationPrecise(83_400L))
    }

    @Test
    fun `解析分割点时间`() {
        assertEquals(83_000L, TimeUtils.parseTimeToMs("83"))
        assertEquals(83_000L, TimeUtils.parseTimeToMs("1:23"))
        assertEquals(83_500L, TimeUtils.parseTimeToMs("01:23.500"))
        assertEquals(83_000L, TimeUtils.parseTimeToMs("1:23"))
        assertEquals(3_723_000L, TimeUtils.parseTimeToMs("1:02:03"))
        // B 站搜索时长可能超过 59 分钟
        assertEquals(39_353_000L, TimeUtils.parseTimeToMs("655:53"))
        // 逗号小数（部分欧洲格式）也兼容
        assertEquals(83_500L, TimeUtils.parseTimeToMs("1:23,500"))
    }

    @Test
    fun `非法时间返回 null`() {
        assertNull(TimeUtils.parseTimeToMs(""))
        assertNull(TimeUtils.parseTimeToMs("abc"))
        assertNull(TimeUtils.parseTimeToMs("1:2:3:4"))
        assertNull(TimeUtils.parseTimeToMs("1:xx"))
    }
}
