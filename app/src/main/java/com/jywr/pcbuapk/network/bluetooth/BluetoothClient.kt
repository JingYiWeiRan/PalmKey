package com.jywr.pcbuapk.network.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import com.jywr.pcbuapk.network.protocol.PacketCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.util.*

/**
 * 蓝牙客户端（RFCOMM）
 * 用于通过经典蓝牙与服务端通信
 */
class BluetoothClient {
    
    companion object {
        private const val TAG = "BluetoothClient"
        
        // PC Bio Unlock 自定义服务 UUID（与服务端保持一致）
        private val SERVICE_UUID = UUID.fromString("62182bf7-97c8-45f9-aa2c-53c5f2008bdf")
        
        private const val CONNECT_TIMEOUT = 10000 // 10秒
    }
    
    private var bluetoothSocket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    
    /**
     * 连接到蓝牙设备
     * @param macAddress 蓝牙 MAC 地址
     * @return 是否连接成功
     */
    suspend fun connect(macAddress: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "========================================")
                Log.d(TAG, "开始蓝牙连接流程")
                Log.d(TAG, "目标MAC地址: $macAddress")
                Log.d(TAG, "服务UUID: $SERVICE_UUID")
                
                val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
                if (bluetoothAdapter == null) {
                    Log.e(TAG, "❌ 设备不支持蓝牙")
                    return@withContext false
                }
                Log.d(TAG, "✓ 设备支持蓝牙")
                
                // 检查蓝牙是否开启
                if (!bluetoothAdapter.isEnabled) {
                    Log.e(TAG, "❌ 蓝牙未开启")
                    return@withContext false
                }
                Log.d(TAG, "✓ 蓝牙已开启")
                
                val device = bluetoothAdapter.getRemoteDevice(macAddress)
                Log.d(TAG, "✓ 获取远程设备: ${device.name ?: "未知"}, 地址: ${device.address}")
                
                // 取消发现，提高连接速度
                if (bluetoothAdapter.isDiscovering) {
                    Log.d(TAG, "取消蓝牙发现...")
                    bluetoothAdapter.cancelDiscovery()
                }
                
                // 尝试创建 RFCOMM Socket（使用自定义服务 UUID）
                var connected = false
                var lastException: Exception? = null
                
                // 第一次尝试：标准连接
                try {
                    Log.d(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                    Log.d(TAG, "尝试1: 标准加密 RFCOMM 连接")
                    bluetoothSocket = device.createRfcommSocketToServiceRecord(SERVICE_UUID)
                    Log.d(TAG, "Socket创建成功，开始连接...")
                    bluetoothSocket?.connect()
                    connected = true
                    Log.d(TAG, "✅ 标准连接成功！")
                } catch (e: Exception) {
                    Log.w(TAG, "⚠️ 标准连接失败: ${e.message}", e)
                    lastException = e
                    close()
                    
                    // 第二次尝试：使用反射创建不安全的 RFCOMM 连接
                    try {
                        Log.d(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                        Log.d(TAG, "尝试2: 不安全 RFCOMM 连接（无需配对）")
                        val method = device.javaClass.getMethod(
                            "createInsecureRfcommSocketToServiceRecord",
                            UUID::class.java
                        )
                        bluetoothSocket = method.invoke(device, SERVICE_UUID) as BluetoothSocket
                        Log.d(TAG, "不安全Socket创建成功，开始连接...")
                        bluetoothSocket?.connect()
                        connected = true
                        Log.d(TAG, "✅ 不安全连接成功！")
                    } catch (e2: Exception) {
                        Log.e(TAG, "❌ 不安全连接也失败: ${e2.message}", e2)
                        lastException = e2
                        close()
                    }
                }
                
                if (!connected) {
                    Log.e(TAG, "========================================")
                    Log.e(TAG, "❌ 蓝牙连接最终失败")
                    Log.e(TAG, "最后错误: ${lastException?.javaClass?.simpleName}: ${lastException?.message}")
                    Log.e(TAG, "========================================")
                    return@withContext false
                }
                
                inputStream = bluetoothSocket?.inputStream
                outputStream = bluetoothSocket?.outputStream
                
                Log.d(TAG, "========================================")
                Log.d(TAG, "✅ 蓝牙连接完全成功！")
                Log.d(TAG, "输入流: ${if (inputStream != null) "✓" else "✗"}")
                Log.d(TAG, "输出流: ${if (outputStream != null) "✓" else "✗"}")
                Log.d(TAG, "========================================")
                true
            } catch (e: Exception) {
                Log.e(TAG, "========================================")
                Log.e(TAG, "❌ 蓝牙连接异常: ${e.javaClass.simpleName}")
                Log.e(TAG, "错误信息: ${e.message}")
                e.printStackTrace()
                Log.e(TAG, "========================================")
                close()
                false
            }
        }
    }
    
    /**
     * 发送数据包
     * @param packetId 包 ID
     * @param data 数据内容（JSON 字符串）
     * @return 是否发送成功
     */
    suspend fun sendPacket(packetId: Short, data: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                if (outputStream == null) {
                    Log.e(TAG, "输出流未初始化")
                    return@withContext false
                }
                
                val packetBytes = PacketCodec.encodePacket(packetId, data.toByteArray(Charsets.UTF_8))
                outputStream?.write(packetBytes)
                outputStream?.flush()
                
                Log.d(TAG, "蓝牙数据包已发送，ID: ${packetId.toInt().toString(16)}")
                true
            } catch (e: Exception) {
                Log.e(TAG, "发送蓝牙数据包失败", e)
                false
            }
        }
    }
    
    /**
     * 接收数据包
     * @return Pair<包ID, 数据内容>，如果接收失败返回 null
     */
    suspend fun receivePacket(): Pair<Short, ByteArray>? {
        return withContext(Dispatchers.IO) {
            try {
                if (inputStream == null) {
                    Log.e(TAG, "输入流未初始化")
                    return@withContext null
                }
                
                val packet = PacketCodec.readPacketFromStream(inputStream!!)
                if (packet != null) {
                    Log.d(TAG, "收到蓝牙数据包，ID: ${packet.first.toInt().toString(16)}")
                }
                packet
            } catch (e: Exception) {
                Log.e(TAG, "接收蓝牙数据包失败", e)
                null
            }
        }
    }
    
    /**
     * 发送并接收响应
     */
    suspend fun sendAndReceive(
        requestPacketId: Short,
        requestData: String,
        responsePacketId: Short
    ): String? {
        if (!sendPacket(requestPacketId, requestData)) {
            return null
        }
        
        val response = receivePacket()
        if (response == null || response.first != responsePacketId) {
            Log.e(TAG, "蓝牙响应包 ID 不匹配")
            return null
        }
        
        return String(response.second, Charsets.UTF_8)
    }
    
    /**
     * 检查是否已连接
     */
    fun isConnected(): Boolean {
        return bluetoothSocket?.isConnected == true
    }
    
    /**
     * 关闭连接
     */
    fun close() {
        try {
            inputStream?.close()
            outputStream?.close()
            bluetoothSocket?.close()
            
            inputStream = null
            outputStream = null
            bluetoothSocket = null
            
            Log.d(TAG, "蓝牙连接已关闭")
        } catch (e: Exception) {
            Log.e(TAG, "关闭蓝牙连接失败", e)
        }
    }
}
