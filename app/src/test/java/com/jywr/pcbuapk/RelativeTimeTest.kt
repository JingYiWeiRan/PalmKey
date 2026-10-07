package com.jywr.pcbuapk

import com.jywr.pcbuapk.ui.util.RelativeTime
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 相对时间文案的测试。
 *
 * 从 `MainScreen` 里抽出来时顺手修了一个缺陷并把它变成可测：原先直接读
 * `System.currentTimeMillis()`，无法单测；而且**没有处理"未来"的时间戳** ——
 * 电脑端时钟偏快、或从备份恢复数据后，`now - timestamp` 会是负数，
 * 落到第一个分支被判成「刚刚」，于是一年后的事情也显示「刚刚」。
 * 现在 `now` 可注入，且负差值有明确语义。
 */
class RelativeTimeTest {

    private val now = 1_700_000_000_000L

    @Test
    fun `just now under one minute`() {
        assertEquals("刚刚", RelativeTime.format(now - 30_000L, now))
        assertEquals("刚刚", RelativeTime.format(now, now))
    }

    @Test
    fun `minutes hours and days`() {
        assertEquals("5 分钟前", RelativeTime.format(now - 5 * 60_000L, now))
        assertEquals("3 小时前", RelativeTime.format(now - 3 * 3_600_000L, now))
        assertEquals("2 天前", RelativeTime.format(now - 2 * 86_400_000L, now))
    }

    @Test
    fun `boundaries roll over exactly`() {
        assertEquals("刚刚", RelativeTime.format(now - 59_999L, now))
        assertEquals("1 分钟前", RelativeTime.format(now - 60_000L, now))
        assertEquals("59 分钟前", RelativeTime.format(now - 59 * 60_000L, now))
        assertEquals("1 小时前", RelativeTime.format(now - 3_600_000L, now))
        assertEquals("1 天前", RelativeTime.format(now - 86_400_000L, now))
    }

    @Test
    fun `future timestamps are clamped to just now`() {
        assertEquals(
            "时钟偏快或备份恢复导致的未来时间戳，不能显示成负数的『-5 分钟前』",
            "刚刚",
            RelativeTime.format(now + 5 * 60_000L, now)
        )
        assertEquals("刚刚", RelativeTime.format(now + 400 * 86_400_000L, now))
    }
}
