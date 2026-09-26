package com.jywr.pcbuapk.service

import android.util.Log
import com.jywr.pcbuapk.data.dao.PairedDeviceDao
import com.jywr.pcbuapk.network.bluetooth.BluetoothClient
import com.jywr.pcbuapk.network.protocol.PacketCodec
import com.jywr.pcbuapk.network.tcp.TcpClient
import com.jywr.pcbuapk.utils.CryptoUtils
import dagger.hilt.android.scopes.ActivityRetainedScoped
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import javax.inject.Inject

/**
 * 解锁服务
 * 负责发送解锁请求到电脑端
 */
@ActivityRetainedScoped
class UnlockService @Inject constructor(
    private val pairedDeviceDao: PairedDeviceDao
) {
    
    companion object {
        private const val TAG = "UnlockService"
        private const val PROTOCOL_VERSION = "3.0.0"
    }
    
    /**
     * 执行解锁操作
     * @param deviceId 设备 ID
     * @param unlockToken 解锁令牌（由生物识别生成）
     * @return 解锁结果消息，成功返回 null
     */
    suspend fun unlock(deviceId: String, unlockToken: String): String? {
        return withContext(Dispatchers.IO) {
            // 1. 获取设备信息
            val device = pairedDeviceDao.getDeviceById(deviceId)
            if (device == null) {
                return@withContext "设备不存在"
            }
            
            Log.d(TAG, "开始解锁设备: ${device.deviceName}")
            
            // 2. 根据配对方式选择连接方式
            return@withContext when (device.pairingMethod) {
                "TCP" -> unlockViaTcp(device, unlockToken)
                "BLUETOOTH" -> unlockViaBluetooth(device, unlockToken)
                else -> "不支持的配对方式: ${device.pairingMethod}"
            }
        }
    }
    
    /**
     * 通过 TCP 解锁
     */
    private suspend fun unlockViaTcp(device: com.jywr.pcbuapk.data.entity.PairedDeviceEntity, unlockToken: String): String? {
        val tcpClient = TcpClient()
        
        try {
            // 1. 连接到解锁端口
            val ipAddress = device.ipAddress ?: return "设备 IP 地址为空"
            val port = device.tcpPort ?: return "设备端口为空"
            
            Log.d(TAG, "步骤1: 连接 TCP $ipAddress:$port")
            if (!tcpClient.connect(ipAddress, port)) {
                return "无法连接到电脑，请确保电脑已开机且在同一网络"
            }
            
            // 2. 发送设备 ID
            Log.d(TAG, "步骤2: 发送设备 ID")
            val deviceIdPacket = JSONObject().apply {
                put("deviceId", device.id)
            }
            
            val encryptedDeviceId = CryptoUtils.encryptAES(
                deviceIdPacket.toString().toByteArray(Charsets.UTF_8),
                device.encryptionKey
            ) ?: return "加密失败"
            
            if (!tcpClient.sendPacket(
                    PacketCodec.PACKET_ID_DEVICE_ID,
                    CryptoUtils.bytesToHex(encryptedDeviceId)
                )
            ) {
                return "发送设备 ID 失败"
            }
            
            // 3. 构建解锁请求
            Log.d(TAG, "步骤3: 发送解锁请求")
            val unlockRequestData = JSONObject().apply {
                put("user", device.userName)
                put("program", "") // 空表示通用解锁
                put("unlockToken", unlockToken)
            }
            
            val unlockRequest = JSONObject().apply {
                put("protoVersion", PROTOCOL_VERSION)
                put("deviceId", device.id)
                put("encData", CryptoUtils.bytesToHex(
                    CryptoUtils.encryptAES(
                        unlockRequestData.toString().toByteArray(Charsets.UTF_8),
                        device.encryptionKey
                    ) ?: return "加密解锁请求失败"
                ))
            }
            
            if (!tcpClient.sendPacket(
                    PacketCodec.PACKET_ID_UNLOCK_REQUEST,
                    unlockRequest.toString()
                )
            ) {
                return "发送解锁请求失败"
            }
            
            // 4. 接收响应
            Log.d(TAG, "步骤4: 等待解锁响应")
            val response = tcpClient.receivePacket()
            if (response == null || response.first != PacketCodec.PACKET_ID_UNLOCK_RESPONSE) {
                return "未收到解锁响应"
            }
            
            // 5. 解析响应
            val responseJson = JSONObject(String(response.second, Charsets.UTF_8))
            val error = responseJson.optString("error")
            if (error.isNotEmpty()) {
                return "解锁失败: $error"
            }
            
            val encData = responseJson.optString("encData")
            if (encData.isEmpty()) {
                return "响应数据为空"
            }
            
            // 6. 解密响应
            val decryptedBytes = CryptoUtils.decryptAES(
                CryptoUtils.hexToBytes(encData),
                device.encryptionKey
            ) ?: return "解密响应失败"
            
            val responseData = JSONObject(String(decryptedBytes, Charsets.UTF_8))
            val returnedToken = responseData.optString("unlockToken")
            
            // 7. 验证令牌
            if (returnedToken != unlockToken) {
                return "令牌验证失败"
            }
            
            Log.d(TAG, "解锁成功")
            
            // 8. 更新最后连接时间
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
     * 通过蓝牙解锁
     * 注意：蓝牙场景下，PC端是客户端，Android端是服务端
     * 所以这里不需要主动连接，而是等待PC的连接
     */
    private suspend fun unlockViaBluetooth(device: com.jywr.pcbuapk.data.entity.PairedDeviceEntity, unlockToken: String): String? {
        Log.d(TAG, "========== 蓝牙解锁 ==========")
        Log.d(TAG, "设备ID: ${device.id}")
        Log.d(TAG, "设备名称: ${device.deviceName}")
        Log.d(TAG, "配对方式: ${device.pairingMethod}")
        
        // 蓝牙解锁由 UnlockListenerService 处理
        // 当PC端连接时，BluetoothServer会接收连接并显示通知
        // 用户生物识别后，应该通过已建立的连接发送数据
        
        return "蓝牙解锁需要通过通知触发\n请等待电脑端发起连接请求"
    }

}
