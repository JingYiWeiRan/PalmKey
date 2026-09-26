package com.jywr.pcbuapk.network.tcp

import android.util.Log
import com.jywr.pcbuapk.network.protocol.PacketCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * TCP 客户端
 * 用于与服务端进行配对和解锁通信
 */
class TcpClient {
    
    companion object {
        private const val TAG = "TcpClient"
        private const val CONNECT_TIMEOUT = 5000 // 5秒
        private const val SOCKET_TIMEOUT = 10000 // 10秒
    }
    
    private var socket: Socket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    
    /**
     * 连接到服务端
     * @param ipAddress IP 地址
     * @param port 端口号
     */
    suspend fun connect(ipAddress: String, port: Int): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "正在连接到 $ipAddress:$port")
                
                socket = Socket()
                socket?.connect(InetSocketAddress(ipAddress, port), CONNECT_TIMEOUT)
                socket?.soTimeout = SOCKET_TIMEOUT
                
                inputStream = socket?.getInputStream()
                outputStream = socket?.getOutputStream()
                
                Log.d(TAG, "TCP 连接成功")
                true
            } catch (e: Exception) {
                Log.e(TAG, "TCP 连接失败", e)
                close()
                false
            }
        }
    }
    
    /**
     * 发送数据包（原始字节）
     * @param packetId 包 ID
     * @param data 数据内容（原始字节数组）
     * @return 是否发送成功
     */
    suspend fun sendPacket(packetId: Short, data: ByteArray): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                if (outputStream == null) {
                    Log.e(TAG, "输出流未初始化")
                    return@withContext false
                }
                
                val packetBytes = PacketCodec.encodePacket(packetId, data)
                outputStream?.write(packetBytes)
                outputStream?.flush()
                
                Log.d(TAG, "数据包已发送，ID: ${packetId.toInt().toString(16)}, 长度: ${data.size}")
                true
            } catch (e: Exception) {
                Log.e(TAG, "发送数据包失败", e)
                false
            }
        }
    }
    
    /**
     * 发送数据包（JSON 字符串）
     * @param packetId 包 ID
     * @param data 数据内容（JSON 字符串）
     * @return 是否发送成功
     */
    suspend fun sendPacket(packetId: Short, data: String): Boolean {
        return sendPacket(packetId, data.toByteArray(Charsets.UTF_8))
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
                    Log.d(TAG, "收到数据包，ID: ${packet.first.toInt().toString(16)}")
                }
                packet
            } catch (e: Exception) {
                Log.e(TAG, "接收数据包失败", e)
                null
            }
        }
    }
    
    /**
     * 发送并接收响应（适用于请求-响应模式，原始字节）
     * @param requestPacketId 请求包 ID
     * @param requestData 请求数据（原始字节数组）
     * @param responsePacketId 期望的响应包 ID
     * @return 响应数据（原始字节数组），如果失败返回 null
     */
    suspend fun sendAndReceive(
        requestPacketId: Short,
        requestData: ByteArray,
        responsePacketId: Short
    ): ByteArray? {
        // 发送请求
        if (!sendPacket(requestPacketId, requestData)) {
            return null
        }
        
        // 接收响应
        val response = receivePacket()
        if (response == null || response.first != responsePacketId) {
            Log.e(TAG, "响应包 ID 不匹配")
            return null
        }
        
        return response.second
    }
    
    /**
     * 检查是否已连接
     */
    fun isConnected(): Boolean {
        return socket?.isConnected == true && !socket?.isClosed!!
    }
    
    /**
     * 关闭连接
     */
    fun close() {
        try {
            inputStream?.close()
            outputStream?.close()
            socket?.close()
            
            inputStream = null
            outputStream = null
            socket = null
            
            Log.d(TAG, "TCP 连接已关闭")
        } catch (e: Exception) {
            Log.e(TAG, "关闭 TCP 连接失败", e)
        }
    }
}
