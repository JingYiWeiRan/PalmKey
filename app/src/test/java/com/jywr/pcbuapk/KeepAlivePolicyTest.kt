package com.jywr.pcbuapk

import com.jywr.pcbuapk.service.KeepAlivePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 保活模式语义的测试。
 *
 * 背景：新增第 4 个模式「不保活」（[KeepAlivePolicy.MODE_NO_KEEP_ALIVE]），
 * 它的职责是**保证 App 被清掉之后就是死的**：任何自动复活路径都不能把它拉起来。
 * 这些判断此前散在 `UnlockListenerService` / `BootReceiver` 里各写各的 if，
 * 没有测试；模式一旦加值，漏改一处就会出现「清了后台过了十分钟又活了」这种
 * 最难排查的现象 —— 所以集中到一处并用测试钉死。
 */
class KeepAlivePolicyTest {

    private val normalModes = listOf(
        KeepAlivePolicy.MODE_BATTERY_SAVER,
        KeepAlivePolicy.MODE_BALANCED,
        KeepAlivePolicy.MODE_RELIABLE
    )

    @Test
    fun `no-keep-alive disables every resurrection path`() {
        val mode = KeepAlivePolicy.MODE_NO_KEEP_ALIVE

        assertFalse("不应排保活闹钟", KeepAlivePolicy.shouldArmKeepAlive(mode))
        assertFalse("不应入队 WorkManager", KeepAlivePolicy.shouldArmKeepAlive(mode))
        assertFalse("开机不应自启", KeepAlivePolicy.shouldStartOnBoot(mode))
        assertFalse("划掉最近任务后不应继续运行", KeepAlivePolicy.shouldSurviveTaskRemoval(mode))
        assertFalse("被系统杀死后不应被重建", KeepAlivePolicy.shouldBeSticky(mode))
    }

    @Test
    fun `the three keep-alive modes keep every resurrection path enabled`() {
        normalModes.forEach { mode ->
            assertTrue("模式 $mode 应排保活闹钟", KeepAlivePolicy.shouldArmKeepAlive(mode))
            assertTrue("模式 $mode 开机应自启", KeepAlivePolicy.shouldStartOnBoot(mode))
            assertTrue("模式 $mode 划掉任务后应继续运行", KeepAlivePolicy.shouldSurviveTaskRemoval(mode))
            assertTrue("模式 $mode 应可由系统重建", KeepAlivePolicy.shouldBeSticky(mode))
        }
    }

    /**
     * 取值异常（历史数据、将来删模式、写入越界）时按**常规保活**处理，而不是按不保活。
     * 理由：把未知值当成"不保活"会让应用在用户毫无察觉的情况下彻底失能，
     * 而当成常规模式只是多耗一点电 —— 失败方向必须偏安全。
     */
    @Test
    fun `unknown mode falls back to normal keep-alive rather than silently disabling the app`() {
        listOf(-1, 4, 99).forEach { mode ->
            assertTrue("未知模式 $mode 不应被当成不保活", KeepAlivePolicy.shouldArmKeepAlive(mode))
            assertTrue(KeepAlivePolicy.shouldStartOnBoot(mode))
            assertTrue(KeepAlivePolicy.shouldSurviveTaskRemoval(mode))
            assertTrue(KeepAlivePolicy.shouldBeSticky(mode))
        }
    }

    @Test
    fun `keep-alive intervals follow the mode`() {
        assertEquals(30 * 60 * 1000L, KeepAlivePolicy.keepAliveIntervalMs(KeepAlivePolicy.MODE_BATTERY_SAVER))
        assertEquals(15 * 60 * 1000L, KeepAlivePolicy.keepAliveIntervalMs(KeepAlivePolicy.MODE_BALANCED))
        assertEquals(5 * 60 * 1000L, KeepAlivePolicy.keepAliveIntervalMs(KeepAlivePolicy.MODE_RELIABLE))
        // 不保活不排闹钟，这里只要求给一个合法值、不要 0 或负数（否则会形成忙循环）
        assertTrue(KeepAlivePolicy.keepAliveIntervalMs(KeepAlivePolicy.MODE_NO_KEEP_ALIVE) > 0)
    }

    @Test
    fun `every advertised mode is offered exactly once`() {
        assertEquals(
            listOf(0, 1, 2, 3),
            KeepAlivePolicy.ALL_MODES.sorted()
        )
    }
}
