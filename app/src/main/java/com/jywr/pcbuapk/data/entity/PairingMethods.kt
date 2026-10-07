package com.jywr.pcbuapk.data.entity

/**
 * 配对方式的唯一判定入口。
 *
 * `PairedDeviceEntity.pairingMethod` 存的是电脑端响应里原样带回的字符串
 * （上游 `PairingMethodUtils::ToString()` 的取值）。此前这个字符串在三处被
 * **互相矛盾**地解释：
 *
 * | 位置 | 判断 | 后果 |
 * |---|---|---|
 * | `DeviceCard` | `!= "TCP"` 即蓝牙 | 把 `UDP`/`MANUAL_UDP`/`CLOUD_TCP` 显示成蓝牙 |
 * | 解锁分发 | `== "BLUETOOTH"` | 同一台设备 UI 标蓝牙、却按 TCP 发送 |
 * | `UnlockService` | 只认 `TCP`/`BLUETOOTH` | 其余一律「不支持的配对方式」 |
 *
 * 而电脑端**默认**就是 `UDP`（上游 `PairingForm.cpp` 的 AUTO 分支），且只对
 * `UDP` / `MANUAL_UDP` / `CLOUD_TCP` 启动 `TCPUnlockServer`（`UnlockHandler.cpp`）。
 * 所以这几项必须走「连电脑端 TCP 解锁服务」的流程，绝不能当蓝牙。
 * 三处判断统一到这里，并有 `PairingMethodsTest` 锁定。
 */
object PairingMethods {

    /** 既不是蓝牙、也不是 TCP 服务方式（含空串与上游的 CLOUD_BT） */
    private const val BLUETOOTH = "BLUETOOTH"

    /**
     * 由电脑端 `TCPUnlockServer` 提供服务的配对方式。
     *
     * - `CLOUD_TCP`：`UnlockHandler.cpp` 直接置 `hasTCPServer = true`
     * - `UDP` / `MANUAL_UDP`：靠该 switch 里 `case UDP` 块末尾漏写 `break`
     *   贯穿到 `CLOUD_TCP`，同样置位（上游这个贯穿是**承重**的）
     * - `TCP`：电脑端反而会去连手机（`TCPUnlockClient`），但手机没有监听端口，
     *   保留在集合里是为了统一走同一套握手并给出可诊断的失败信息，
     *   而不是报「不支持的配对方式」把人绕晕
     */
    private val TCP_SERVER_METHODS = setOf("TCP", "UDP", "MANUAL_UDP", "CLOUD_TCP")

    /** 该配对方式是否由电脑端的 TCP 解锁服务提供 */
    fun usesTcpServer(raw: String): Boolean = normalize(raw) in TCP_SERVER_METHODS

    /** 该配对方式是否为蓝牙（手机作服务端、电脑主动连过来） */
    fun isBluetooth(raw: String): Boolean = normalize(raw) == BLUETOOTH

    /** 该配对方式是否受支持 */
    fun isSupported(raw: String): Boolean = usesTcpServer(raw) || isBluetooth(raw)

    private fun normalize(raw: String): String = raw.trim().uppercase()
}
