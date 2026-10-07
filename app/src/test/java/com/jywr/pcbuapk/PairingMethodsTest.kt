package com.jywr.pcbuapk

import com.jywr.pcbuapk.data.entity.PairingMethods
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 配对方式判定的测试。
 *
 * 背景：`pairingMethod` 是从电脑端响应里**原样存下来的字符串**
 * （上游 `PairingMethodUtils::ToString()` 的取值），此前代码里对它有三套互相矛盾的判断：
 *
 * - `DeviceCard` 判定 `!= "TCP"` 就当蓝牙 → 把 `UDP` / `MANUAL_UDP` / `CLOUD_TCP`
 *   全显示成「蓝牙」，可它们实际走的是电脑端的 TCP 解锁服务；
 * - 解锁分发判定 `== "BLUETOOTH"` → 同一台设备在 UI 上标着蓝牙，却被按 TCP 发出去；
 * - `UnlockService` 只认 `TCP` / `BLUETOOTH`，其它一律「不支持的配对方式」。
 *
 * 更关键的是：电脑端**默认**配对方式就是 `UDP`（上游 `PairingForm.cpp`：
 * `pairingMethodType == "AUTO" ? PairingMethod::UDP : MANUAL_UDP`），
 * 而电脑端恰恰只对 `UDP` / `MANUAL_UDP` / `CLOUD_TCP` 启动 `TCPUnlockServer`。
 * 所以这几项必须走 TCP 服务流程，不能当成蓝牙。
 */
class PairingMethodsTest {

    @Test
    fun `methodsthathit the pc tcp unlock server`() {
        // 上游 UnlockHandler：CLOUD_TCP 直接起 TCPUnlockServer；
        // UDP / MANUAL_UDP 经 case 贯穿同样置 hasTCPServer = true
        listOf("TCP", "UDP", "MANUAL_UDP", "CLOUD_TCP").forEach { raw ->
            assertTrue("$raw 应当走电脑端 TCP 解锁服务", PairingMethods.usesTcpServer(raw))
            assertFalse("$raw 不是蓝牙", PairingMethods.isBluetooth(raw))
        }
    }

    @Test
    fun `bluetooth is recognised and is not a tcp server method`() {
        assertTrue(PairingMethods.isBluetooth("BLUETOOTH"))
        assertFalse(PairingMethods.usesTcpServer("BLUETOOTH"))
    }

    @Test
    fun `the default pc pairing method is treated as supported`() {
        // 默认值来自上游 PairingForm.cpp 的 AUTO 分支
        assertTrue("UDP 是电脑端默认配对方式，必须被认为是受支持的", PairingMethods.isSupported("UDP"))
        assertTrue(PairingMethods.isSupported("MANUAL_UDP"))
    }

    @Test
    fun `unknown and empty methods are unsupported and are neither bluetooth nor tcp`() {
        listOf("", "  ", "CLOUD_BT", "SOMETHING_ELSE").forEach { raw ->
            assertFalse("'$raw' 不应被当成蓝牙", PairingMethods.isBluetooth(raw))
            assertFalse("'$raw' 不应被当成 TCP 服务方式", PairingMethods.usesTcpServer(raw))
            assertFalse("'$raw' 应被判为不支持", PairingMethods.isSupported(raw))
        }
    }

    @Test
    fun `matching tolerates surrounding whitespace`() {
        assertTrue(PairingMethods.usesTcpServer("  UDP "))
        assertTrue(PairingMethods.isBluetooth("BLUETOOTH "))
    }
}
