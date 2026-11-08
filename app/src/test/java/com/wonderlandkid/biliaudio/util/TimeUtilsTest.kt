package com.wonderlandkid.biliaudio.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TimeUtilsTest {

    @Test
    fun `formatDuration handles minutes and hours`() {
        assertEquals("00:00", TimeUtils.formatDuration(0))
        assertEquals("00:05", TimeUtils.formatDuration(5_000))
        assertEquals("01:23", TimeUtils.formatDuration(83_000))
        assertEquals("59:59", TimeUtils.formatDuration(3_599_000))
        assertEquals("1:00:00", TimeUtils.formatDuration(3_600_000))
        assertEquals("2:03:04", TimeUtils.formatDuration(7_384_000))
    }

    @Test
    fun `formatDuration rounds to nearest second`() {
        assertEquals("00:02", TimeUtils.formatDuration(1_600))
        assertEquals("00:01", TimeUtils.formatDuration(1_400))
    }

    @Test
    fun `formatDurationPrecise keeps millis`() {
        assertEquals("03:21.500", TimeUtils.formatDurationPrecise(201_500))
        assertEquals("00:00.001", TimeUtils.formatDurationPrecise(1))
    }

    @Test
    fun `formatHumanDuration is readable`() {
        assertEquals("不到 1 分钟", TimeUtils.formatHumanDuration(30_000))
        assertEquals("1 分钟", TimeUtils.formatHumanDuration(60_000))
        assertEquals("1 小时", TimeUtils.formatHumanDuration(3_600_000))
        assertEquals("1 小时 23 分钟", TimeUtils.formatHumanDuration(4_980_000))
    }

    @Test
    fun `parseTimeToMs supports seconds`() {
        assertEquals(83_000L, TimeUtils.parseTimeToMs("83"))
        assertEquals(0L, TimeUtils.parseTimeToMs("0"))
    }

    @Test
    fun `parseTimeToMs supports mm ss`() {
        assertEquals(83_000L, TimeUtils.parseTimeToMs("1:23"))
        assertEquals(83_500L, TimeUtils.parseTimeToMs("01:23.500"))
        assertEquals(83_500L, TimeUtils.parseTimeToMs("1:23,500"))
    }

    @Test
    fun `parseTimeToMs supports hh mm ss`() {
        assertEquals(3_723_000L, TimeUtils.parseTimeToMs("1:02:03"))
        assertEquals(3_723_500L, TimeUtils.parseTimeToMs("01:02:03.5"))
        // 1 小时以上的分割点（如 01:22:23）
        assertEquals(4_943_000L, TimeUtils.parseTimeToMs("01:22:23"))
    }

    @Test
    fun `parseTimeToMs trims spaces`() {
        assertEquals(83_000L, TimeUtils.parseTimeToMs("  1:23  "))
    }

    @Test
    fun `parseTimeToMs rejects invalid input`() {
        assertNull(TimeUtils.parseTimeToMs(""))
        assertNull(TimeUtils.parseTimeToMs("abc"))
        assertNull(TimeUtils.parseTimeToMs("1:2:3:4"))
        assertNull(TimeUtils.parseTimeToMs("1:-2"))
        assertNull(TimeUtils.parseTimeToMs("-3"))
    }

    @Test
    fun `parseBiliDurationText works`() {
        assertEquals(235_000L, TimeUtils.parseBiliDurationText("3:55"))
        assertEquals(0L, TimeUtils.parseBiliDurationText(""))
    }
}
