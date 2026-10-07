package com.jywr.pcbuapk.service

/**
 * 一次解锁请求应当采用的投递方式。
 */
enum class DeliveryMode {
    /** 直接弹出本机生物识别验证界面 */
    PROMPT,

    /** 点亮屏幕、等用户在系统锁屏上完成解锁 */
    WAIT_KEYGUARD
}

/**
 * 解锁授权策略。
 *
 * 为什么单独抽出来：这里定义的是「什么情况下可以不弹本机指纹就放行」，
 * 属于安全相关判断，原先以隐式 if/else 散在 [UnlockListenerService] 内且没有任何测试。
 * 纯函数化之后既能单测，也让信任模型有唯一、可读的出处。
 *
 * ## 信任模型（重要）
 *
 * 1. **触发无需鉴权**：UDP 广播是明文 JSON、无签名，同网段任何主机都能伪造。
 *    因此广播只被当作「提示可能有解锁请求」的信号，来源还会经
 *    [com.jywr.pcbuapk.network.udp.BroadcastSourcePolicy] 核对。
 * 2. **真正的鉴权是共享密钥**：手机回发 `UNLOCK_RESPONSE` 前必须先解开
 *    电脑下发的 `UNLOCK_REQUEST`（`encData` 用 `encryptionKey` 加密）。
 *    拿不到密钥就无法完成握手，**伪造广播解锁不了任何电脑**。
 * 3. **跳过本机指纹的含义**：等价于「电脑的合法请求 + 用户解锁了自己的手机」。
 *    它比「每次都要按一次指纹」弱，但远不是「谁都能解锁」。
 *    这是为避免「锁屏后再按一次指纹」的重复体验而做的产品取舍，见 README/报告说明。
 * 4. **窗口必须有限**：跳过只在这次请求的等待窗口内有效，超时后一律回退到指纹验证。
 */
object UnlockAuthorizationPolicy {

    /**
     * 等待用户完成系统解锁的最长时间。
     * 超过它就不再承认「用户解锁了手机」与本次请求的关联。
     */
    const val KEYGUARD_WAIT_TIMEOUT_MS = 60_000L

    /**
     * 根据屏幕状态决定投递方式。
     *
     * - 锁屏 **且有** 凭据（密码/指纹）→ [DeliveryMode.WAIT_KEYGUARD]。
     *   此时 `BiometricPrompt` 无法完成认证（keyguard 仍挂着会直接报错返回），
     *   只能点亮屏幕让用户走系统解锁；用户解锁本身就是一次强身份验证。
     * - 其余情况（未锁屏 / 滑动解锁无凭据）→ [DeliveryMode.PROMPT]。
     *   滑动解锁没有身份凭据，不能用「用户划开了屏幕」当授权。
     */
    fun decideDeliveryMode(keyguardLocked: Boolean, deviceSecure: Boolean): DeliveryMode =
        if (keyguardLocked && deviceSecure) DeliveryMode.WAIT_KEYGUARD else DeliveryMode.PROMPT

    /**
     * 是否允许跳过本机生物识别直接放行。
     *
     * @param requestAgeMs 从该请求开始等待起经过的毫秒数
     */
    fun canSkipLocalBiometric(requestAgeMs: Long): Boolean =
        requestAgeMs in 0..KEYGUARD_WAIT_TIMEOUT_MS
}
