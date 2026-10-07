package com.jywr.pcbuapk.service

import android.util.Log
import com.jywr.pcbuapk.data.dao.PairedDeviceDao
import com.jywr.pcbuapk.data.converter.SecretString
import com.jywr.pcbuapk.data.entity.PairedDeviceEntity
import com.jywr.pcbuapk.network.protocol.PacketCodec
import com.jywr.pcbuapk.network.tcp.TcpClient
import com.jywr.pcbuapk.utils.CryptoUtils
import dagger.hilt.android.scopes.ActivityRetainedScoped
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID
import javax.inject.Inject

/**
 * 配对服务
 * 负责与电脑端进行配对流程
 */
@ActivityRetainedScoped
class PairingService @Inject constructor(
    private val pairedDeviceDao: PairedDeviceDao
) {
    
    companion object {
        private const val TAG = "PairingService"
        private const val PROTOCOL_VERSION = "3.0.0"
    }
    
    /**
     * 执行 TCP 配对流程
     * @param ipAddress 电脑 IP 地址
     * @param port 配对端口
     * @param encKey 加密密钥（从二维码获取）
     * @param deviceName 手机设备名称
     * @param udpPort UDP 监听端口
     * @return 配对结果消息，成功返回 null
     */
    suspend fun pairViaTcp(
        ipAddress: String,
        port: Int,
        encKey: String,
        deviceName: String,
        udpPort: Int
    ): String? {
        return withContext(Dispatchers.IO) {
            val tcpClient = TcpClient()
            
            try {
                // 1. 连接 TCP
                Log.d(TAG, "步骤1: 连接 TCP $ipAddress:$port")
                if (!tcpClient.connect(ipAddress, port)) {
                    return@withContext "无法连接到电脑，请检查 IP 和端口"
                }
                
                // 2. 构建配对初始化数据包
                Log.d(TAG, "步骤2: 发送配对请求")
                val deviceUUID = UUID.randomUUID().toString()
                val initPacket = JSONObject().apply {
                    put("protoVersion", PROTOCOL_VERSION)
                    put("deviceUUID", deviceUUID)
                    put("deviceName", deviceName)
                    put("ipAddress", "") // 手机端不需要提供 IP
                    put("tcpPort", 0)
                    put("udpPort", udpPort)
                    put("udpManualPort", 0)
                    put("cloudToken", "")
                }
                
                // 3. 加密并发送
                val initDataBytes = initPacket.toString().toByteArray(Charsets.UTF_8)
                val encryptedData = CryptoUtils.encryptAES(initDataBytes, encKey)
                if (encryptedData == null) {
                    return@withContext "加密失败"
                }
                
                Log.d(TAG, "加密后数据长度: ${encryptedData.size}")
                if (!tcpClient.sendPacket(PacketCodec.PACKET_ID_PAIR_INIT, encryptedData)) {
                    return@withContext "发送配对请求失败"
                }
                
                // 4. 接收响应
                Log.d(TAG, "步骤3: 等待配对响应")
                val response = tcpClient.receivePacket()
                if (response == null || response.first != PacketCodec.PACKET_ID_PAIR_RESPONSE) {
                    Log.e(TAG, "响应包 ID 不匹配: 期望=${PacketCodec.PACKET_ID_PAIR_RESPONSE.toInt().toString(16)}, 实际=${response?.first?.toInt()?.toString(16)}")
                    return@withContext "未收到配对响应"
                }
                
                Log.d(TAG, "收到响应数据长度: ${response.second.size}")
                
                // 5. 解密响应（直接使用原始字节）
                val decryptedBytes = CryptoUtils.decryptAES(response.second, encKey)
                if (decryptedBytes == null) {
                    return@withContext "解密响应失败，请检查加密密钥"
                }
                
                Log.d(TAG, "解密后数据长度: ${decryptedBytes.size}")
                val responseJson = JSONObject(String(decryptedBytes, Charsets.UTF_8))
                
                // 6. 检查是否有错误
                val errMsg = responseJson.optString("errMsg")
                if (errMsg.isNotEmpty()) {
                    return@withContext "配对失败: $errMsg"
                }
                
                // 7. 解析配对数据
                val dataJson = responseJson.getJSONObject("data")
                val deviceId = dataJson.getString("deviceId")
                val computerName = dataJson.getString("deviceName")
                val userName = dataJson.getString("userName")
                val passwordKey = dataJson.getString("passwordKey")
                val pairingMethod = dataJson.getString("pairingMethod")
                val unlockPort = dataJson.getInt("port")
                val macAddress = dataJson.optString("macAddress", "")
                
                Log.d(TAG, "步骤4: 配对成功，设备ID: $deviceId")
                
                // 8. 保存配对信息
                val pairedDevice = PairedDeviceEntity(
                    id = deviceId,
                    deviceName = computerName,
                    pairingMethod = pairingMethod,
                    userName = userName,
                    passwordEnc = "", // 暂时为空，后续可能需要
                    // 用 SecretString 包一层：入库时由 Room TypeConverter 加密（AndroidKeyStore）
                    encryptionKey = SecretString(encKey),
                    passwordKey = SecretString(passwordKey),
                    ipAddress = ipAddress,
                    tcpPort = unlockPort,
                    udpPort = udpPort,
                    bluetoothAddress = macAddress
                )
                
                pairedDeviceDao.insertDevice(pairedDevice)
                Log.d(TAG, "配对信息已保存")
                
                null // 成功
            } catch (e: Exception) {
                Log.e(TAG, "配对过程异常", e)
                "配对失败: ${e.message}"
            } finally {
                tcpClient.close()
            }
        }
    }
    
    /**
     * 验证二维码格式
     * @param qrContent 二维码内容
     * @return 解析后的配对数据，如果格式错误返回 null
     */
    fun parseQrCode(qrContent: String): QrCodeData? {
        return try {
            val json = JSONObject(qrContent)
            QrCodeData(
                ip = json.getString("ip"),
                port = json.getInt("port"),
                method = json.getString("method"),
                encKey = json.getString("encKey")
            )
        } catch (e: Exception) {
            Log.e(TAG, "二维码格式错误", e)
            null
        }
    }
}

/**
 * 二维码数据结构
 */
data class QrCodeData(
    val ip: String,
    val port: Int,
    val method: String,
    val encKey: String
)
