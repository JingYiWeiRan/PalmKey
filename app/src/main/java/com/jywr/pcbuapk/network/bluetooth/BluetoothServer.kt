package com.jywr.pcbuapk.network.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*

/**
 * 蓝牙服务端（RFCOMM Server）
 * 持续监听来自PC端的蓝牙连接请求
 * 用于在跨网络情况下接收唤醒信号
 */
class BluetoothServer {
    
    companion object {
        private const val TAG = "BluetoothServer"
        
        // PC Bio Unlock 自定义服务 UUID（必须与PC端SDP服务UUID一致）
        private val SERVICE_UUID = UUID.fromString("62182bf7-97c8-45f9-aa2c-53c5f2008bdf")
        
        private const val SERVER_NAME = "PC Bio Unlock Android"
        
        // Packet协议常量
        private const val PACKET_ID_UNLOCK_RESPONSE = 0xB2

        /**
         * accept 持续失败时的退避时间。
         *
         * 没有它会形成一个 100% 占 CPU 的忙循环并刷日志洪水：`accept()` 抛异常后
         * catch 只是打日志就 continue，下一轮立刻再抛。蓝牙被系统关闭、RFCOMM 服务记录
         * 失效、适配器复位都会走到这里 —— 而这些恰恰是长时间运行后最常见的情况。
         */
        private const val ACCEPT_RETRY_BACKOFF_MS = 500L
    }
    
    private var serverSocket: BluetoothServerSocket? = null
    private var clientSocket: android.bluetooth.BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null

    /**
     * 监听意图 + 线程实际存活状态，两者都由 accept 线程写、由服务读，必须跨线程可见。
     *
     * 关键语义：accept 线程**退出时一定会把它置回 false**（见下面 finally）。
     * 否则服务会以为"还在监听"，而 `startListening` 的 `if (isListening) return`
     * 会让后续所有重建调用变成空操作 —— 也就是"端口看着在、其实没人接"的静默失聪，
     * 与 UDP 那一路已经修过的问题同源。
     */
    @Volatile
    private var isListening = false
    private var listenerThread: Thread? = null
    
    /**
     * 启动蓝牙Server监听
     * @param onClientConnected 客户端连接回调，返回设备名称和MAC地址
     */
    fun startListening(onClientConnected: (String, String) -> Unit) {
        if (isListening) {
            Log.w(TAG, "已经在监听中")
            return
        }
        
        isListening = true
        
        listenerThread = Thread {
            try {
                Log.d(TAG, "========================================")
                Log.d(TAG, "启动蓝牙Server监听")
                Log.d(TAG, "服务名称: $SERVER_NAME")
                Log.d(TAG, "服务UUID: $SERVICE_UUID")
                
                val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
                if (bluetoothAdapter == null) {
                    Log.e(TAG, "❌ 设备不支持蓝牙")
                    isListening = false
                    return@Thread
                }
                Log.d(TAG, "✓ 设备支持蓝牙")
                
                if (!bluetoothAdapter.isEnabled) {
                    Log.e(TAG, "❌ 蓝牙未开启")
                    isListening = false
                    return@Thread
                }
                Log.d(TAG, "✓ 蓝牙已开启")
                
                // 创建RFCOMM Server Socket
                serverSocket = bluetoothAdapter.listenUsingRfcommWithServiceRecord(
                    SERVER_NAME,
                    SERVICE_UUID
                )
                
                Log.d(TAG, "✓ Server Socket创建成功，开始监听...")
                
                while (isListening) {
                    try {
                        // 取出本地引用：stopListening() 会把字段置空，
                        // 若直接写 serverSocket?.accept()，置空后 accept 立刻返回 null，
                        // 循环就会空转（100% CPU）。这里用本地引用并在为空时直接退出。
                        val server = serverSocket
                        if (server == null) {
                            Log.i(TAG, "Server Socket 已释放，监听循环退出")
                            break
                        }

                        // 阻塞等待客户端连接
                        val socket = server.accept()
                        
                        if (socket != null) {
                            val device = socket.remoteDevice
                            val deviceName = device.name ?: "Unknown"
                            val macAddress = device.address
                            
                            Log.d(TAG, "========================================")
                            Log.d(TAG, "✅ 收到蓝牙连接请求！")
                            Log.d(TAG, "设备名称: $deviceName")
                            Log.d(TAG, "MAC地址: $macAddress")
                            Log.d(TAG, "========================================")
                            
                            // 关闭旧连接（如果有）
                            closeClientConnection()
                            
                            // 保存新连接
                            clientSocket = socket
                            inputStream = socket.inputStream
                            outputStream = socket.outputStream
                            
                            // 回调通知
                            onClientConnected(deviceName, macAddress)
                        }
                        
                    } catch (e: Exception) {
                        if (isListening) {
                            Log.e(TAG, "等待客户端连接失败（${ACCEPT_RETRY_BACKOFF_MS}ms 后重试）", e)
                            // 退避，避免持续失败时空转刷日志
                            try {
                                Thread.sleep(ACCEPT_RETRY_BACKOFF_MS)
                            } catch (interrupted: InterruptedException) {
                                Thread.currentThread().interrupt()
                                break
                            }
                        }
                    }
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "启动蓝牙Server失败", e)
            } finally {
                // 线程退出时必须把状态复位：否则服务以为还在监听，而 startListening 的
                // `if (isListening) return` 会让重建调用全部变成空操作 → 静默失聪。
                // 与 UDP 接收循环退出时释放 socket 并复位状态的修复保持对称。
                val unexpected = isListening
                isListening = false
                listenerThread = null
                if (unexpected) {
                    Log.w(TAG, "⚠️ 蓝牙Server监听线程意外退出，状态已复位（等待重建）")
                } else {
                    Log.d(TAG, "蓝牙Server监听线程退出")
                }
            }
        }
        
        listenerThread?.start()
    }
    
    /**
     * 停止监听
     */
    fun stopListening() {
        Log.d(TAG, "停止蓝牙Server监听")
        isListening = false
        
        // 关闭Server Socket
        try {
            serverSocket?.close()
            serverSocket = null
        } catch (e: Exception) {
            Log.e(TAG, "关闭Server Socket失败", e)
        }
        
        // 关闭客户端连接
        closeClientConnection()
        
        // 等待线程结束
        listenerThread?.join(1000)
        listenerThread = null
        
        Log.d(TAG, "蓝牙Server已停止")
    }
    
    /**
     * 关闭客户端连接
     */
    private fun closeClientConnection() {
        try {
            inputStream?.close()
            outputStream?.close()
            clientSocket?.close()
            
            inputStream = null
            outputStream = null
            clientSocket = null
        } catch (e: Exception) {
            Log.e(TAG, "关闭客户端连接失败", e)
        }
    }
    
    /**
     * 读取Packet协议数据（从PC端接收唤醒信号）
     * Packet格式：
     * - Header: 8字节 (0xDB065AC7AFDFA4CC)
     * - Packet ID: 2字节 (大端序)
     * - Length: 2字节 (数据长度，大端序)
     * - Data: JSON数据
     */
    suspend fun readPacket(): Pair<Short, ByteArray>? {
        return withContext(Dispatchers.IO) {
            try {
                if (inputStream == null) {
                    Log.e(TAG, "输入流未初始化")
                    return@withContext null
                }
                
                // 1. 读取并验证Header (8字节)
                val headerBuffer = ByteArray(8)
                var bytesRead = 0
                while (bytesRead < 8) {
                    val read = inputStream?.read(headerBuffer, bytesRead, 8 - bytesRead) ?: 0
                    if (read <= 0) {
                        Log.w(TAG, "数据流已关闭")
                        return@withContext null
                    }
                    bytesRead += read
                }
                
                // 验证Header
                val expectedHeader = byteArrayOf(
                    0xDB.toByte(), 0x06.toByte(), 0x5A.toByte(), 0xC7.toByte(),
                    0xAF.toByte(), 0xDF.toByte(), 0xA4.toByte(), 0xCC.toByte()
                )
                if (!headerBuffer.contentEquals(expectedHeader)) {
                    Log.e(TAG, "Header验证失败")
                    Log.e(TAG, "期望: ${expectedHeader.joinToString(" ") { "%02X".format(it) }}")
                    Log.e(TAG, "实际: ${headerBuffer.joinToString(" ") { "%02X".format(it) }}")
                    return@withContext null
                }
                
                Log.d(TAG, "✓ Header验证成功")
                
                // 2. 读取Packet ID (2字节，大端序)
                val packetIdBuffer = ByteArray(2)
                bytesRead = 0
                while (bytesRead < 2) {
                    val read = inputStream?.read(packetIdBuffer, bytesRead, 2 - bytesRead) ?: 0
                    if (read <= 0) {
                        Log.w(TAG, "数据流已关闭")
                        return@withContext null
                    }
                    bytesRead += read
                }
                
                val packetId = ((packetIdBuffer[0].toInt() and 0xFF) shl 8) or
                        (packetIdBuffer[1].toInt() and 0xFF)
                
                Log.d(TAG, "Packet ID: 0x${packetId.toString(16).uppercase()}")
                
                // 3. 读取Data Length (2字节，大端序)
                val lengthBuffer = ByteArray(2)
                bytesRead = 0
                while (bytesRead < 2) {
                    val read = inputStream?.read(lengthBuffer, bytesRead, 2 - bytesRead) ?: 0
                    if (read <= 0) {
                        Log.w(TAG, "数据流已关闭")
                        return@withContext null
                    }
                    bytesRead += read
                }
                
                val dataLength = ((lengthBuffer[0].toInt() and 0xFF) shl 8) or
                        (lengthBuffer[1].toInt() and 0xFF)
                
                Log.d(TAG, "Data Length: $dataLength 字节")
                
                if (dataLength <= 0) {
                    Log.e(TAG, "无效的数据长度: $dataLength")
                    return@withContext null
                }
                
                // 4. 读取实际数据
                val data = ByteArray(dataLength)
                bytesRead = 0
                while (bytesRead < dataLength) {
                    val read = inputStream?.read(data, bytesRead, dataLength - bytesRead) ?: 0
                    if (read <= 0) {
                        Log.w(TAG, "数据流已关闭")
                        return@withContext null
                    }
                    bytesRead += read
                }
                
                Log.d(TAG, "✓ 成功读取Packet (ID=0x${packetId.toString(16).uppercase()}, $dataLength 字节)")
                packetId.toShort() to data
                
            } catch (e: Exception) {
                Log.e(TAG, "读取Packet失败", e)
                null
            }
        }
    }
    
    /**
     * 发送响应（可选）
     */
    suspend fun sendResponse(data: ByteArray): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                if (outputStream == null) {
                    Log.e(TAG, "输出流未初始化")
                    return@withContext false
                }
                
                // 先发送数据长度
                val length = data.size
                val lengthBytes = byteArrayOf(
                    ((length shr 24) and 0xFF).toByte(),
                    ((length shr 16) and 0xFF).toByte(),
                    ((length shr 8) and 0xFF).toByte(),
                    (length and 0xFF).toByte()
                )
                
                outputStream?.write(lengthBytes)
                outputStream?.write(data)
                outputStream?.flush()
                
                Log.d(TAG, "✓ 响应已发送 (${data.size} 字节)")
                true
                
            } catch (e: Exception) {
                Log.e(TAG, "发送响应失败", e)
                false
            }
        }
    }
    
    /**
     * 发送解锁响应Packet（PC端期望的格式）
     * Packet格式：
     * - Header: 8字节 (0xDB065AC7AFDFA4CC)
     * - Packet ID: 2字节 (0xB2)
     * - Length: 2字节 (数据长度，大端序)
     * - Data: JSON数据
     */
    suspend fun sendUnlockResponsePacket(responseJson: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                if (outputStream == null) {
                    Log.e(TAG, "输出流未初始化")
                    return@withContext false
                }
                
                val responseData = responseJson.toByteArray(Charsets.UTF_8)
                val dataSize = responseData.size
                
                Log.d(TAG, "========================================")
                Log.d(TAG, "发送解锁响应Packet")
                Log.d(TAG, "Data Size: $dataSize 字节")
                Log.d(TAG, "Data: $responseJson")
                Log.d(TAG, "========================================")
                
                // 使用ByteBuffer构建Packet（大端序）
                val packetSize = 8 + 2 + 2 + dataSize // Header + PacketID + Length + Data
                val buffer = ByteBuffer.allocate(packetSize)
                buffer.order(ByteOrder.BIG_ENDIAN)
                
                // 1. 写入 Header (8字节) - 0xDB065AC7AFDFA4CC
                buffer.put(0xDB.toByte())
                buffer.put(0x06.toByte())
                buffer.put(0x5A.toByte())
                buffer.put(0xC7.toByte())
                buffer.put(0xAF.toByte())
                buffer.put(0xDF.toByte())
                buffer.put(0xA4.toByte())
                buffer.put(0xCC.toByte())
                
                // 2. 写入 Packet ID (2字节)
                buffer.putShort(PACKET_ID_UNLOCK_RESPONSE.toShort())
                
                // 3. 写入 Data Length (2字节)
                buffer.putShort(dataSize.toShort())
                
                // 4. 写入 Data
                buffer.put(responseData)
                
                // 发送整个Packet
                outputStream?.write(buffer.array())
                outputStream?.flush()
                
                Log.d(TAG, "✅ 解锁响应Packet发送成功 (总 $packetSize 字节)")
                true
                
            } catch (e: Exception) {
                Log.e(TAG, "发送解锁响应Packet失败", e)
                false
            }
        }
    }
    
    /**
     * 主动断开当前客户端连接。
     *
     * 用途：readPacket() 是阻塞读且 Android 未暴露 socket 读超时，
     * 读取超时后调用本方法关闭流，把被阻塞的读线程放出来，避免线程泄漏。
     */
    fun closeConnection() {
        Log.d(TAG, "主动关闭蓝牙客户端连接")
        closeClientConnection()
    }

    /**
     * 检查是否有客户端连接
     */
    fun isConnected(): Boolean {
        return clientSocket?.isConnected == true
    }
    
    /**
     * 检查是否正在监听
     */
    fun isRunning(): Boolean {
        return isListening
    }
}
