package com.jywr.pcbuapk.network.protocol

import android.util.Log
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 数据包编解码器
 * 与服务端 Packets.h 和 BaseConnection.cpp 保持一致的协议格式
 */
object PacketCodec {
    
    private const val TAG = "PacketCodec"
    
    // 包头魔数（64位）- 0xDB065AC7AFDFA4CC 作为有符号 Long
    private const val PACKET_HEADER = -2664342315847408436L
    
    // 包 ID 定义
    val PACKET_ID_PAIR_INIT = 80.toShort()      // 0x50
    val PACKET_ID_PAIR_RESPONSE = 81.toShort()  // 0x51
    val PACKET_ID_DEVICE_ID = (-80).toShort()   // 0xB0 = 176
    val PACKET_ID_UNLOCK_REQUEST = (-79).toShort()  // 0xB1 = 177
    val PACKET_ID_UNLOCK_RESPONSE = (-78).toShort() // 0xB2 = 178
    
    /**
     * 编码数据包
     * @param packetId 包 ID
     * @param data 数据内容
     * @return 完整的二进制数据包
     * 
     * 协议格式（与服务端 BaseConnection.cpp 一致）：
     * | Header (8字节, 大端序) | PacketID (2字节, 网络字节序/大端序) | Length (2字节, 网络字节序) | Data (可变长度) |
     */
    fun encodePacket(packetId: Short, data: ByteArray): ByteArray {
        val outputStream = ByteArrayOutputStream()
        
        // 写入包头（8字节，大端序）
        val headerBytes = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(PACKET_HEADER).array()
        outputStream.write(headerBytes)
        
        // 写入包 ID（2字节，网络字节序/大端序）- 与服务端 htons 一致
        val packetIdBytes = ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN).putShort(packetId).array()
        outputStream.write(packetIdBytes)
        
        // 写入数据长度（2字节，网络字节序/大端序）- 与服务端 ntohs 一致
        val lengthBytes = ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN).putShort(data.size.toShort()).array()
        outputStream.write(lengthBytes)
        
        // 写入数据
        outputStream.write(data)
        
        return outputStream.toByteArray()
    }
    
    /**
     * 解码数据包
     * @param buffer 原始数据缓冲区
     * @return Pair<包ID, 数据内容>，如果解析失败返回 null
     */
    fun decodePacket(buffer: ByteArray): Pair<Short, ByteArray>? {
        return try {
            if (buffer.size < 12) { // 8(header) + 2(packetId) + 2(length)
                Log.e(TAG, "数据包长度不足")
                return null
            }
            
            val byteBuffer = ByteBuffer.wrap(buffer)
            byteBuffer.order(ByteOrder.BIG_ENDIAN)
            
            // 读取并验证包头
            val header = byteBuffer.long
            if (header != PACKET_HEADER) {
                Log.e(TAG, "包头不匹配: ${header.toString(16)}")
                return null
            }
            
            // 读取包 ID（2字节，网络字节序/大端序）
            val packetId = byteBuffer.short
            
            // 读取数据长度（2字节，网络字节序/大端序）
            val dataLength = byteBuffer.short.toInt() and 0xFFFF
            
            // 读取数据
            val data = ByteArray(dataLength)
            byteBuffer.get(data)
            
            Pair(packetId, data)
        } catch (e: Exception) {
            Log.e(TAG, "数据包解析失败", e)
            null
        }
    }
    
    /**
     * 从输入流中读取完整的数据包（处理粘包/拆包）
     * @param inputStream 输入流
     * @return Pair<包ID, 数据内容>，如果读取失败返回 null
     */
    fun readPacketFromStream(inputStream: java.io.InputStream): Pair<Short, ByteArray>? {
        return try {
            // 读取包头（8字节）
            val headerBuffer = ByteArray(8)
            if (inputStream.read(headerBuffer) != 8) {
                return null
            }
            
            val header = ByteBuffer.wrap(headerBuffer).order(ByteOrder.BIG_ENDIAN).long
            if (header != PACKET_HEADER) {
                Log.e(TAG, "包头不匹配")
                return null
            }
            
            // 读取包 ID（2字节，网络字节序/大端序）
            val packetIdBuffer = ByteArray(2)
            if (inputStream.read(packetIdBuffer) != 2) {
                return null
            }
            val packetId = ByteBuffer.wrap(packetIdBuffer).order(ByteOrder.BIG_ENDIAN).short
            
            // 读取数据长度（2字节，网络字节序/大端序）
            val lengthBuffer = ByteArray(2)
            if (inputStream.read(lengthBuffer) != 2) {
                return null
            }
            val dataLength = ByteBuffer.wrap(lengthBuffer).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xFFFF
            
            // 读取数据
            val data = ByteArray(dataLength)
            var totalRead = 0
            while (totalRead < dataLength) {
                val read = inputStream.read(data, totalRead, dataLength - totalRead)
                if (read == -1) {
                    Log.e(TAG, "连接已关闭")
                    return null
                }
                totalRead += read
            }
            
            Pair(packetId, data)
        } catch (e: Exception) {
            Log.e(TAG, "读取数据包失败", e)
            null
        }
    }
}
