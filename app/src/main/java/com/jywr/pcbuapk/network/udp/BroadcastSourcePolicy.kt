package com.jywr.pcbuapk.network.udp

/**
 * UDP 广播的来源可信性与端点新鲜度策略。
 *
 * 抽成纯函数是为了能单测：这是安全相关判断，此前散落在网络层里、没有任何覆盖。
 *
 * 需要说明清楚这层判断的**边界**：广播载荷是明文 JSON 且无签名，同网段任何主机
 * 都能伪造一条字段齐全的广播（deviceId 也能从广播里嗅探到）。真正的鉴权是那把
 * 共享密钥 `encryptionKey` —— 攻击者拿不到它就无法完成 DEVICE_ID / UNLOCK_RESPONSE
 * 握手，因此伪造广播**无法解锁任何电脑**。
 * 但「随便谁都能让手机亮屏、弹验证界面」本身就是要挡掉的问题，所以这里做来源核对。
 */
object BroadcastSourcePolicy {

    /**
     * 广播自称的 IP 是否与实际发包地址一致。
     *
     * @param claimedIp 载荷里的 `pcbuIP`
     * @param senderAddress `DatagramPacket.getAddress().getHostAddress()`，取不到时传 null
     */
    fun isSourceConsistent(claimedIp: String, senderAddress: String?): Boolean {
        val claimed = normalize(claimedIp)
        if (claimed.isEmpty()) return false

        // 取不到发包地址时不阻断：触发信号本身解不开任何东西（真正鉴权是共享密钥），
        // 这里 fail-open 可避免在异常网络栈上把正常功能一起挡掉。
        val sender = normalize(senderAddress ?: return true)
        if (sender.isEmpty()) return true

        return claimed == sender
    }

    /**
     * 是否应当用广播里的端点信息刷新本地记录。
     *
     * 广播每 2 秒一发，字段 `pcbuIP` / `pcbuPort` 就是电脑**当前**的 LAN IP 与
     * 解锁服务端口（上游 `UDPBroadcaster.cpp`：`pcbuPort = AppSettings::Get().unlockServerPort`，
     * 与配对响应里的 `data.port` 是同一个值）。
     * 配对时写死的那份可能已经过时（DHCP 换 IP、电脑端改端口），
     * 这正是「电脑明明在线、手机就是解不开」的常见原因。
     *
     * ## 为什么要「惰性」刷新（`storedEndpointSuspect`）
     *
     * 广播是没有签名的，同网段任何主机都能发一条自称自己 IP 的广播，而 deviceId
     * 本来就在电脑端的明文广播里、嗅探即可得。如果无条件接受改写，任何主机都能把
     * 一个**本来能用**的配对静默改指到自己那里：响应被发到攻击者（它没有共享密钥，
     * 解不开、也拿不到明文），真正的电脑永远收不到响应 —— 用户看到的是"电脑好好的，
     * 就是莫名其妙解不开了"，且毫无线索。
     *
     * 所以只有在**有证据表明旧端点确实失效**（`storedEndpointSuspect`，由一次
     * 真实连接失败置位）之后，才采纳广播给出的新端点。代价是 IP 变化后第一次解锁
     * 会失败一次并给出明确提示，随后 2 秒内自愈。
     *
     * @param storedEndpointSuspect 旧端点是否已被证明连不上
     */
    fun shouldRefreshEndpoint(
        storedIp: String?,
        storedPort: Int?,
        broadcastIp: String,
        broadcastPort: Int,
        storedEndpointSuspect: Boolean = false
    ): Boolean {
        val ip = normalize(broadcastIp)
        if (ip.isEmpty() || broadcastPort !in 1..65535) return false

        // 回环地址一律不写库：真实电脑端广播的是自己网卡的 IP
        // （上游取 `NetworkHelper::GetSavedNetworkInterface().ipAddress`），不会是 127.0.0.1。
        // 这是一条**写**路径，接受回环会把配对记录改成指向手机自己、之后再也解不开。
        // 真机测试时用回环注入广播，就把 ipAddress 改写成了 127.0.0.1 —— 不是假想。
        // 这里刻意不记日志：本类保持为纯逻辑（无 Android 依赖，便于 JVM 单测）。
        if (ip == "localhost" || ip.startsWith("127.") || ip == "::1") return false

        // 没有可用的旧端点：直接补上，没什么可失去的
        if (storedIp.isNullOrBlank() || storedPort == null) return true

        // 旧端点看起来还能用：不接受无签名广播的改写
        if (!storedEndpointSuspect) return false

        return storedIp.trim() != ip || storedPort != broadcastPort
    }

    /** 统一地址写法：去掉前导斜杠、IPv4-mapped IPv6 前缀与首尾空白。 */
    private fun normalize(address: String): String =
        address.trim()
            .removePrefix("/")
            .removePrefix("::ffff:")
            .trim()
}
