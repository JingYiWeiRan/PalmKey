package com.jywr.pcbuapk.service

import android.util.Log
import com.jywr.pcbuapk.data.dao.PairedDeviceDao
import com.jywr.pcbuapk.data.entity.PairedDeviceEntity
import com.jywr.pcbuapk.data.entity.PairingMethods
import com.jywr.pcbuapk.network.protocol.PacketCodec
import com.jywr.pcbuapk.network.protocol.UnlockProtocol
import com.jywr.pcbuapk.network.tcp.TcpClient
import dagger.hilt.android.scopes.ActivityRetainedScoped
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * 解锁服务
 * 负责把「本机已通过人证核验」这件事告诉电脑端，并完成协议握手。
 *
 * 日志统一用 Log.i：实测 vivo / OPPO 会丢弃三方应用的 DEBUG 级日志，
 * 而这些握手步骤正是排查「配对成功却解不开」时唯一有用的线索。
 */
@ActivityRetainedScoped
class UnlockService @Inject constructor(
    private val pairedDeviceDao: PairedDeviceDao
) {

    companion object {
        private const val TAG = "UnlockService"
    }

    /**
     * 执行解锁操作。
     *
     * @param deviceId 设备 ID
     * @param unlockToken 由生物识别生成的本地令牌。**不参与协议报文体**：
     *   上游要求回显的是 PC 自己下发的 unlockToken，本地令牌只用于向界面层表达
     *   「人证核验已通过」这一前置条件（调用方只会在验证成功后调用本方法）。
     * @return 解锁结果消息，成功返回 null
     */
    suspend fun unlock(deviceId: String, unlockToken: String): String? {
        return withContext(Dispatchers.IO) {
            // 1. 获取设备信息
            val device = pairedDeviceDao.getDeviceById(deviceId)
            if (device == null) {
                return@withContext "设备不存在"
            }

            Log.i(TAG, "开始解锁设备: ${device.deviceName}（配对方式=${device.pairingMethod}）")

            // 2. 根据配对方式选择连接方式。
            // 判定统一走 PairingMethods（唯一出处，有单测），避免各处各自解释这个字符串
            return@withContext when {
                PairingMethods.usesTcpServer(device.pairingMethod) -> unlockViaTcp(device)

                PairingMethods.isBluetooth(device.pairingMethod) -> unlockViaBluetooth(device)

                else -> "不支持的配对方式: ${device.pairingMethod}"
            }
        }
    }

    /**
     * 通过 TCP 解锁（对接电脑端的 TCPUnlockServer）。
     *
     * 握手顺序必须与上游 `PerformAuthFlow(socket, needsDeviceID = true)` 完全一致：
     * ```
     * ── 手机 → PC：PACKET_ID_DEVICE_ID，载荷是**明文** deviceId
     * ── PC  → 手机：PACKET_ID_UNLOCK_REQUEST，encData 内含 PC 生成的 unlockToken
     * ── 手机 → PC：PACKET_ID_UNLOCK_RESPONSE，原样回显该 token
     * ```
     * 注意：**不是**由手机发解锁请求。上游收到 DEVICE_ID 后才由 PC 下发请求，
     * 而且上游的 `OnPacketReceived` 里根本没有 `PACKET_ID_UNLOCK_REQUEST` 分支，
     * 手机自己发这个包只会被判为 "Invalid response packet"。
     */
    private suspend fun unlockViaTcp(device: PairedDeviceEntity): String? {
        val tcpClient = TcpClient()

        try {
            // 1. 连接到电脑端的解锁服务端口
            val ipAddress = device.ipAddress
            if (ipAddress.isNullOrBlank()) {
                return "设备 IP 地址为空，请重新配对"
            }
            val port = device.tcpPort
            if (port == null || port !in 1..65535) {
                return "设备端口无效，请重新配对"
            }

            Log.i(TAG, "步骤1: 连接 TCP $ipAddress:$port")
            if (!tcpClient.connect(ipAddress, port)) {
                // 连不上：把该设备标记为"端点可疑"，从而允许下一条广播用电脑当前的 IP/端口
                // 覆盖配对时写死的那份。这是 DHCP 漂移的自愈路径 —— 只有这里产生的证据
                // 才允许改写端点，避免任意同网段主机凭空把可用的端点改指到别处。
                UnlockListenerService.instance?.markEndpointSuspect(device.id)
                return "无法连接到电脑，请确保电脑已开机且在同一网络"
            }

            // 2. 发送设备 ID（明文！上游把它直接当设备 ID 字符串查库）
            Log.i(TAG, "步骤2: 发送设备 ID")
            if (!tcpClient.sendPacket(
                    PacketCodec.PACKET_ID_DEVICE_ID,
                    UnlockProtocol.buildDeviceIdPayload(device.id)
                )
            ) {
                return "发送设备 ID 失败"
            }

            // 3. 等待电脑端下发解锁请求
            Log.i(TAG, "步骤3: 等待电脑端下发解锁请求")
            val request = tcpClient.receivePacket()
            if (request == null) {
                return "未收到电脑端的解锁请求（连接超时）"
            }

            val (packetId, requestData) = request
            if (packetId != PacketCodec.PACKET_ID_UNLOCK_REQUEST) {
                // 上游查不到设备 ID 时会直接断开或用错误状态收场，这里给出可诊断的信息
                return "电脑端返回了非预期的数据包 (ID=0x${(packetId.toInt() and 0xFFFF).toString(16).uppercase()})，" +
                        "请确认电脑端已配对同一台手机"
            }

            // 4. 解出 PC 生成的 unlockToken
            val pcUnlockToken = UnlockProtocol.extractPcUnlockToken(
                String(requestData, Charsets.UTF_8),
                device.encryptionKey.value
            )
            if (pcUnlockToken == null) {
                // 明确告知上游出错，避免它一直等到超时
                tcpClient.sendPacket(
                    PacketCodec.PACKET_ID_UNLOCK_RESPONSE,
                    UnlockProtocol.buildErrorResponse("DATA_ERROR")
                )
                return "解密电脑端请求失败，请检查加密密钥或两端时间是否相差超过 2 分钟"
            }

            // 5. 构建并回发解锁响应（必须原样回显 PC 的 token）
            Log.i(TAG, "步骤4: 回发解锁响应")
            val responseJson = UnlockProtocol.buildUnlockResponse(
                pcUnlockToken = pcUnlockToken,
                passwordKey = device.passwordKey?.value,
                encryptionKey = device.encryptionKey.value
            )
            if (responseJson == null) {
                tcpClient.sendPacket(
                    PacketCodec.PACKET_ID_UNLOCK_RESPONSE,
                    UnlockProtocol.buildErrorResponse("APP_ERROR")
                )
                return "加密解锁响应失败"
            }

            if (!tcpClient.sendPacket(PacketCodec.PACKET_ID_UNLOCK_RESPONSE, responseJson)) {
                return "发送解锁响应失败"
            }

            Log.i(TAG, "解锁成功")
            pairedDeviceDao.updateLastConnectedTime(device.id, System.currentTimeMillis())
            return null // 成功
        } catch (e: Exception) {
            Log.e(TAG, "解锁过程异常", e)
            return "解锁失败: ${e.message}"
        } finally {
            tcpClient.close()
        }
    }

    /**
     * 通过蓝牙解锁。
     *
     * 蓝牙链路里 **PC 是客户端、手机是服务端**，手机不主动连接。
     * PC 连上来时由 [UnlockListenerService] 读包、弹验证界面，
     * 用户完成验证后再由它沿同一条连接回发响应。
     *
     * 因此这里（用户手动点击蓝牙设备卡片时）只返回引导信息。
     */
    private suspend fun unlockViaBluetooth(device: PairedDeviceEntity): String? {
        Log.i(TAG, "蓝牙解锁需由电脑端发起连接，设备: ${device.deviceName}")
        return "蓝牙解锁需等待电脑端发起连接\n请确认电脑端已开启解锁监听"
    }
}
