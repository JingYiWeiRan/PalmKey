package com.jywr.pcbuapk.network.protocol

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.InputStream
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

    /** 协议头固定长度：8(header) + 2(packetId) + 2(length) */
    const val HEADER_TOTAL_SIZE = 12

    /**
     * 包 ID 定义。
     *
     * 必须与上游 `Packets.h` 的 `uint16_t` 常量逐位一致：
     * `0x50 / 0x51 / 0xB0 / 0xB1 / 0xB2`。
     *
     * ⚠️ 这里**不能**写成 `(-80).toShort()`。
     * 上游是按 `uint16_t` 收包的（`BaseConnection.cpp` 用 `ntohs` 读入后按无符号比较），
     * 而 `(-80).toShort()` 在 `putShort` 时会发出 `FF B0`，与上游期望的 `00 B0` 不同，
     * 导致 DEVICE_ID / UNLOCK_REQUEST / UNLOCK_RESPONSE 三种包全部落到上游 switch 的
     * `default:` 分支被判为 "Invalid response packet" 丢弃。
     */
    val PACKET_ID_PAIR_INIT = 0x50.toShort()
    val PACKET_ID_PAIR_RESPONSE = 0x51.toShort()
    val PACKET_ID_DEVICE_ID = 0xB0.toShort()
    val PACKET_ID_UNLOCK_REQUEST = 0xB1.toShort()
    val PACKET_ID_UNLOCK_RESPONSE = 0xB2.toShort()

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
            if (buffer.size < HEADER_TOTAL_SIZE) {
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

            // 声明长度必须真的在缓冲区里，否则说明包被截断了
            if (dataLength > byteBuffer.remaining()) {
                Log.e(TAG, "数据长度超出缓冲区: 声明=$dataLength 实际剩余=${byteBuffer.remaining()}")
                return null
            }

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
     *
     * ⚠️ 必须用 [readFully] 循环读取：
     * TCP 是字节流，一次 `read()` 完全可能只返回部分字节；RFCOMM 也一样会分块。
     * 早先的实现对 8/2/2 字节的头部字段各只调用一次 `read()`，
     * 一旦发生短读就直接返回 null，而调用方把 null 当成致命错误，
     * 于是连接会在**流中途**被放弃、剩余字节留在 socket 里，造成永久失步。
     *
     * @param inputStream 输入流
     * @return Pair<包ID, 数据内容>，如果读取失败返回 null
     */
    fun readPacketFromStream(inputStream: InputStream): Pair<Short, ByteArray>? {
        return try {
            // 读取包头（8字节）
            val headerBuffer = ByteArray(8)
            if (!readFully(inputStream, headerBuffer)) {
                return null
            }

            val header = ByteBuffer.wrap(headerBuffer).order(ByteOrder.BIG_ENDIAN).long
            if (header != PACKET_HEADER) {
                Log.e(TAG, "包头不匹配")
                return null
            }

            // 读取包 ID（2字节，网络字节序/大端序）
            val packetIdBuffer = ByteArray(2)
            if (!readFully(inputStream, packetIdBuffer)) {
                return null
            }
            val packetId = ByteBuffer.wrap(packetIdBuffer).order(ByteOrder.BIG_ENDIAN).short

            // 读取数据长度（2字节，网络字节序/大端序）
            val lengthBuffer = ByteArray(2)
            if (!readFully(inputStream, lengthBuffer)) {
                return null
            }
            val dataLength = ByteBuffer.wrap(lengthBuffer).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xFFFF

            // 上游会拒绝长度为 0 的包（BaseConnection.cpp），这里保持一致
            if (dataLength == 0) {
                Log.e(TAG, "收到长度为 0 的包，协议错误")
                return null
            }

            // 读取数据
            val data = ByteArray(dataLength)
            if (!readFully(inputStream, data)) {
                return null
            }

            Pair(packetId, data)
        } catch (e: Exception) {
            Log.e(TAG, "读取数据包失败", e)
            null
        }
    }

    /**
     * 循环读取直到填满 [buffer]。
     *
     * @return true 表示读满；false 表示流已结束或异常（连接不可用）
     */
    private fun readFully(inputStream: InputStream, buffer: ByteArray): Boolean {
        var totalRead = 0
        while (totalRead < buffer.size) {
            val read = inputStream.read(buffer, totalRead, buffer.size - totalRead)
            if (read == -1) {
                Log.e(TAG, "连接已关闭（已读 $totalRead/${buffer.size} 字节）")
                return false
            }
            totalRead += read
        }
        return true
    }
}
