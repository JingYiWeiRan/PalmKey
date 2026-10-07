package com.jywr.pcbuapk

import com.jywr.pcbuapk.service.DeliveryMode
import com.jywr.pcbuapk.service.UnlockAuthorizationPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 解锁授权策略的测试。
 *
 * 这层策略此前是**隐式**散在 `UnlockListenerService` 里的 if/else，没有任何覆盖，
 * 而它决定了「什么情况下可以不弹本机指纹就放行」—— 属于安全相关逻辑，
 * 必须显式化并有测试锁定。
 *
 * 信任模型（务必理解清楚，避免误判为漏洞，也避免误信为无害）：
 * 手机侧对电脑的鉴权依赖共享密钥 `encryptionKey` —— 触发用的 UDP 广播是无签名的，
 * 但真正回发 `UNLOCK_RESPONSE` 必须能解开电脑下发的 `UNLOCK_REQUEST`。
 * 因此「跳过本机指纹」并不等于「任何人都能解锁电脑」，
 * 它意味着：**电脑的合法请求 + 用户解锁了自己的手机 = 放行**。
 */
class UnlockAuthorizationPolicyTest {

    @Test
    fun `locked with a secure lock screen waits for the system unlock`() {
        assertEquals(
            "锁屏且有密码/指纹时，应点亮屏幕让用户走系统解锁（此刻无法弹 BiometricPrompt）",
            DeliveryMode.WAIT_KEYGUARD,
            UnlockAuthorizationPolicy.decideDeliveryMode(keyguardLocked = true, deviceSecure = true)
        )
    }

    @Test
    fun `unlocked screen asks for a local biometric prompt`() {
        assertEquals(
            DeliveryMode.PROMPT,
            UnlockAuthorizationPolicy.decideDeliveryMode(keyguardLocked = false, deviceSecure = true)
        )
    }

    @Test
    fun `locked but without credentials asks for a local biometric prompt`() {
        assertEquals(
            "滑动解锁没有身份凭据，不能用『用户解锁了手机』当授权，必须弹指纹",
            DeliveryMode.PROMPT,
            UnlockAuthorizationPolicy.decideDeliveryMode(keyguardLocked = true, deviceSecure = false)
        )
    }

    @Test
    fun `skipping the local biometric is allowed inside the wait window`() {
        assertTrue(UnlockAuthorizationPolicy.canSkipLocalBiometric(0))
        assertTrue(UnlockAuthorizationPolicy.canSkipLocalBiometric(1_000))
        assertTrue(
            "刚好在窗口边界上仍算有效",
            UnlockAuthorizationPolicy.canSkipLocalBiometric(
                UnlockAuthorizationPolicy.KEYGUARD_WAIT_TIMEOUT_MS
            )
        )
    }

    @Test
    fun `skipping the local biometric is refused outside the wait window`() {
        assertFalse(
            "超出等待窗口的『用户解锁』事件不该再当作本次请求的授权",
            UnlockAuthorizationPolicy.canSkipLocalBiometric(
                UnlockAuthorizationPolicy.KEYGUARD_WAIT_TIMEOUT_MS + 1
            )
        )
        assertFalse(
            "负的时间差说明状态不一致（例如时钟回拨），此时必须回退到指纹验证",
            UnlockAuthorizationPolicy.canSkipLocalBiometric(-1)
        )
    }
}
