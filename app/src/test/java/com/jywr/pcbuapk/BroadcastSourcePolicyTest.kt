package com.jywr.pcbuapk

import com.jywr.pcbuapk.network.udp.BroadcastSourcePolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UDP 广播来源可信性策略的测试。
 *
 * 为什么需要这层判断：广播载荷是**明文 JSON**、没有任何签名，同网段任何主机都能
 * 构造一条完全合法的载荷（字段齐全、deviceId 也只需从广播里嗅探即可获知）。
 * 手机端此前对来源不做任何校验，于是任何主机都能让手机亮屏、弹验证界面。
 * 完整协议里真正的鉴权是那把 `encryptionKey`（攻击者拿不到就无法完成握手），
 * 但「随便谁都能打断用户」本身就是应该挡掉的问题。
 */
class BroadcastSourcePolicyTest {

    @Test
    fun `accepts a broadcast whose claimed ip matches the sender`() {
        assertTrue(
            BroadcastSourcePolicy.isSourceConsistent("192.168.1.100", "192.168.1.100")
        )
    }

    @Test
    fun `rejects a broadcast whose claimed ip differs from the sender`() {
        assertFalse(
            "自称 192.168.1.100 却来自 10.0.0.7，必须丢弃",
            BroadcastSourcePolicy.isSourceConsistent("192.168.1.100", "10.0.0.7")
        )
    }

    @Test
    fun `tolerates transport formatting differences`() {
        // DatagramPacket.getAddress().getHostAddress() 在某些栈上会带前导斜杠
        assertTrue(
            BroadcastSourcePolicy.isSourceConsistent("192.168.1.100", "/192.168.1.100")
        )
        // IPv4-mapped IPv6 形式
        assertTrue(
            BroadcastSourcePolicy.isSourceConsistent("192.168.1.100", "::ffff:192.168.1.100")
        )
        // 首尾空白
        assertTrue(
            BroadcastSourcePolicy.isSourceConsistent(" 192.168.1.100 ", "192.168.1.100")
        )
    }

    @Test
    fun `rejects a broadcast with a blank claimed ip`() {
        assertFalse("没有自称 IP 就无法核对来源", BroadcastSourcePolicy.isSourceConsistent("", "192.168.1.100"))
        assertFalse(BroadcastSourcePolicy.isSourceConsistent("   ", "192.168.1.100"))
    }

    /**
     * 取不到发包地址时不阻断：触发信号本身不能解锁任何东西（真正鉴权是共享密钥），
     * 这里 fail-open 可以避免在异常网络栈上把正常功能一起挡掉。
     */
    @Test
    fun `fails open when the sender address is unknown`() {
        assertTrue(BroadcastSourcePolicy.isSourceConsistent("192.168.1.100", null))
        assertTrue(BroadcastSourcePolicy.isSourceConsistent("192.168.1.100", ""))
    }

    // ---------------------------------------------------------------- 端点刷新

    @Test
    fun `refreshes the stored endpoint once it is known to be stale`() {
        assertTrue(
            "电脑换了 IP（DHCP）时，旧端点连不上之后必须跟随更新，否则表现为『电脑在线但解不开』",
            BroadcastSourcePolicy.shouldRefreshEndpoint(
                "192.168.1.100", 43296, "192.168.1.101", 43296, storedEndpointSuspect = true
            )
        )
        assertTrue(
            "电脑改了解锁服务端口时同样要更新",
            BroadcastSourcePolicy.shouldRefreshEndpoint(
                "192.168.1.100", 43296, "192.168.1.100", 50000, storedEndpointSuspect = true
            )
        )
    }

    @Test
    fun `does not rewrite the row when the endpoint is unchanged`() {
        assertFalse(
            "端点没变就不要写库：广播每 2 秒一次，无条件写会造成无谓的磁盘写入",
            BroadcastSourcePolicy.shouldRefreshEndpoint("192.168.1.100", 43296, "192.168.1.100", 43296)
        )
    }

    @Test
    fun `refreshes when nothing was stored or the stored value is unusable`() {
        assertTrue(BroadcastSourcePolicy.shouldRefreshEndpoint(null, null, "192.168.1.100", 43296))
        assertTrue(BroadcastSourcePolicy.shouldRefreshEndpoint("", 43296, "192.168.1.100", 43296))
        assertTrue(BroadcastSourcePolicy.shouldRefreshEndpoint("192.168.1.100", null, "192.168.1.100", 43296))
    }

    /**
     * 回环地址绝不能用来改写已存在的端点。
     *
     * 真实的电脑端只会广播自己网卡的 IP（上游 `UDPBroadcaster` 取的是
     * `NetworkHelper::GetSavedNetworkInterface().ipAddress`），不会是 127.0.0.1。
     * 而这条路径是**写库**的：一旦接受回环地址，配对记录就会指向手机自己，
     * 之后所有解锁都会连到本地而失败。
     *
     * 这不是假想——测试时用回环注入广播，就把真机上的 ipAddress 改写成了 127.0.0.1，
     * 直到看界面才发现。触发提示（只读路径）仍允许回环，方便本地联调；
     * 但**写入**必须拒绝。
     */
    @Test
    fun `never rewrites a stored endpoint to a loopback address`() {
        assertFalse(
            BroadcastSourcePolicy.shouldRefreshEndpoint("192.168.26.181", 43296, "127.0.0.1", 43296)
        )
        assertFalse(
            BroadcastSourcePolicy.shouldRefreshEndpoint(null, null, "127.0.0.1", 43296)
        )
        assertFalse(
            BroadcastSourcePolicy.shouldRefreshEndpoint("192.168.26.181", 43296, "localhost", 43296)
        )
    }

    // ------------------------------------------------- 惰性刷新（只在连不上时改写）

    /**
     * 已存端点看起来可用时，**不允许**广播把它改掉。
     *
     * 广播是无签名的，同网段任何主机都能发一条自称自己 IP 的广播（deviceId 也能从
     * 电脑端的明文广播里嗅探到）。若无条件接受，任何主机都能把一个**本来能用**的
     * 配对静默改指到自己那里：响应被发给攻击者（虽然它没有共享密钥、解不开，
     * 也拿不到明文），真正的电脑则永远收不到响应 —— 表现为"莫名其妙就解不开了"。
     * 所以写入必须等到有证据表明旧端点确实失效。
     */
    @Test
    fun `does not overwrite a presumably-working endpoint`() {
        assertFalse(
            "端点没失效时，广播不应改写它",
            BroadcastSourcePolicy.shouldRefreshEndpoint(
                storedIp = "192.168.26.181",
                storedPort = 43296,
                broadcastIp = "192.168.1.77",
                broadcastPort = 43296,
                storedEndpointSuspect = false
            )
        )
    }

    @Test
    fun `refreshes once the stored endpoint proved unreachable`() {
        assertTrue(
            "连不上旧端点后，广播给出的新端点应被采纳（DHCP 漂移的自愈路径）",
            BroadcastSourcePolicy.shouldRefreshEndpoint(
                storedIp = "192.168.26.181",
                storedPort = 43296,
                broadcastIp = "192.168.1.77",
                broadcastPort = 43296,
                storedEndpointSuspect = true
            )
        )
    }

    @Test
    fun `fills in a missing endpoint regardless, since nothing can be lost`() {
        assertTrue(
            BroadcastSourcePolicy.shouldRefreshEndpoint(null, null, "192.168.1.100", 43296, false)
        )
        assertTrue(
            BroadcastSourcePolicy.shouldRefreshEndpoint("", 43296, "192.168.1.100", 43296, false)
        )
        assertTrue(
            BroadcastSourcePolicy.shouldRefreshEndpoint("192.168.1.100", null, "192.168.1.100", 43296, false)
        )
    }
}
