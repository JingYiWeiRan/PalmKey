package com.jywr.pcbuapk

import com.jywr.pcbuapk.network.protocol.PacketCodec
import com.jywr.pcbuapk.network.protocol.UnlockProtocol
import com.jywr.pcbuapk.utils.CryptoUtils
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 线上协议格式的回归测试 —— 直接测真实生产代码。
 *
 * 重点是**字节级**断言，因为此前最致命的一类 bug 恰好不会以异常形式暴露，
 * 只会让电脑端把包丢掉：包 ID 写成了 `(-80).toShort()`，实际发出 `FF B0`，
 * 而上游按无符号 `0xB0` 比较，于是三种解锁包全部落到 `default:` 分支被丢弃，
 * 表现为「配对成功但永远解不开」，且两端日志都看不出所以然。
 *
 * 这些用例锁定了上游 `Packets.h` 的取值，任何人再把 ID 写错都会立刻变红。
 */
class ProtocolWireFormatTest {

    /** 上游 BaseConnection.cpp 的包头魔数 0xDB065AC7AFDFA4CC（大端） */
    private val expectedMagic = byteArrayOf(
        0xDB.toByte(), 0x06, 0x5A, 0xC7.toByte(),
        0xAF.toByte(), 0xDF.toByte(), 0xA4.toByte(), 0xCC.toByte()
    )

    private fun packetBytes(packetId: Short, data: ByteArray = byteArrayOf(1)): ByteArray =
        PacketCodec.encodePacket(packetId, data)

    /**
     * 包 ID 必须是上游的无符号 uint16 值，编码后高字节为 0x00。
     * 这正是 `(-80).toShort()` 会写出 `FF B0` 的那个坑。
     */
    @Test
    fun `packet ids match upstream unsigned values`() {
        val expected = mapOf(
            PacketCodec.PACKET_ID_PAIR_INIT to 0x50,
            PacketCodec.PACKET_ID_PAIR_RESPONSE to 0x51,
            PacketCodec.PACKET_ID_DEVICE_ID to 0xB0,
            PacketCodec.PACKET_ID_UNLOCK_REQUEST to 0xB1,
            PacketCodec.PACKET_ID_UNLOCK_RESPONSE to 0xB2
        )

        expected.forEach { (actualId, expectedValue) ->
            // Short → 无符号语义下的数值
            assertEquals(
                "包 ID 数值必须等于上游常量",
                expectedValue,
                actualId.toInt() and 0xFFFF
            )

            // 字节层面：高字节必须是 00，否则上游 uint16 比较必然失配
            val bytes = packetBytes(actualId)
            assertEquals("包 ID 高字节应为 0x00", 0x00, bytes[8].toInt() and 0xFF)
            assertEquals(
                "包 ID 低字节应为 0x${expectedValue.toString(16)}",
                expectedValue,
                bytes[9].toInt() and 0xFF
            )
        }
    }

    @Test
    fun `wire layout is magic then id then length then payload`() {
        val payload = "hello-pcbu".toByteArray(Charsets.UTF_8)
        val bytes = packetBytes(PacketCodec.PACKET_ID_DEVICE_ID, payload)

        // 8 字节包头 + 2 包 ID + 2 长度 + 载荷
        assertEquals(PacketCodec.HEADER_TOTAL_SIZE + payload.size, bytes.size)

        assertArrayEquals(
            "包头魔数必须是 0xDB065AC7AFDFA4CC 的大端字节",
            expectedMagic,
            bytes.copyOfRange(0, 8)
        )

        // 长度字段（大端，2 字节）
        assertEquals(0x00, bytes[10].toInt() and 0xFF)
        assertEquals(payload.size, bytes[11].toInt() and 0xFF)

        assertArrayEquals(payload, bytes.copyOfRange(12, bytes.size))
    }

    @Test
    fun `decodePacket round trips an encoded packet`() {
        val payload = "{\"deviceId\":\"abc\"}".toByteArray(Charsets.UTF_8)
        val encoded = PacketCodec.encodePacket(PacketCodec.PACKET_ID_UNLOCK_REQUEST, payload)

        val decoded = PacketCodec.decodePacket(encoded)

        assertNotNull("应当能解回自己编码的包", decoded)
        assertEquals(
            PacketCodec.PACKET_ID_UNLOCK_REQUEST.toInt(),
            decoded!!.first.toInt()
        )
        assertArrayEquals(payload, decoded.second)
    }

    @Test
    fun `decodePacket rejects a bad header and a truncated payload`() {
        val payload = byteArrayOf(1, 2, 3)
        val encoded = PacketCodec.encodePacket(PacketCodec.PACKET_ID_DEVICE_ID, payload)

        // 篡改包头
        val badHeader = encoded.copyOf()
        badHeader[0] = 0x00
        assertNull("包头不匹配必须拒绝", PacketCodec.decodePacket(badHeader))

        // 截断载荷：声明长度 3，实际只剩 1 字节
        assertNull("声明长度大于实际数据必须拒绝", PacketCodec.decodePacket(encoded.copyOf(13)))
    }

    /**
     * DEVICE_ID 的载荷必须是**明文 UTF-8 设备 ID**。
     * 上游把这段原始字节直接当设备 ID 字符串查库，
     * 一旦改成加密数据或 hex，电脑端必然返回 "Invalid device ID"。
     */
    @Test
    fun `device id payload is plaintext utf8 not encrypted or hex`() {
        val deviceId = "5f2c1a90-8e4b-4d1f-9a77-1c2b3d4e5f60"

        assertArrayEquals(
            "设备 ID 载荷必须就是设备 ID 本身的 UTF-8 字节",
            deviceId.toByteArray(Charsets.UTF_8),
            UnlockProtocol.buildDeviceIdPayload(deviceId)
        )
    }

    /** AES-GCM 信封往返（IV‖salt‖密文‖tag），与上游 CryptUtils 的布局一致。 */
    @Test
    fun `aes gcm envelope round trips`() {
        val key = "mySecretKey1234567890"
        val plaintext = """{"user":"jywr","program":"","unlockToken":"tok-abc"}"""
            .toByteArray(Charsets.UTF_8)

        val encrypted = CryptoUtils.encryptAES(plaintext, key)
        assertNotNull("加密不应失败", encrypted)

        // IV(16) + Salt(16) + 密文 + GCM tag(16)：至少要覆盖 IV+salt+tag 的固定开销
        assertTrue(
            "信封至少要覆盖 IV+salt+tag 的开销，实际 ${encrypted!!.size} 字节",
            encrypted.size >= 16 + 16 + 16
        )
        // 精确尺寸：IV(16) + salt(16) + 明文(8 字节时间戳 + 载荷) + GCM tag(16)
        assertEquals(16 + 16 + 8 + plaintext.size + 16, encrypted.size)

        val decrypted = CryptoUtils.decryptAES(encrypted, key)
        assertNotNull("正确密钥应当能解开", decrypted)
        assertArrayEquals(
            "解密后应当是去掉 8 字节时间戳的原始载荷",
            plaintext,
            decrypted
        )
    }

    @Test
    fun `aes gcm rejects a wrong key and tampered ciphertext`() {
        val plaintext = "secret".toByteArray(Charsets.UTF_8)
        val encrypted = CryptoUtils.encryptAES(plaintext, "correct-key-123")!!

        assertNull("错误密钥必须解密失败", CryptoUtils.decryptAES(encrypted, "wrong-key-456"))

        // 翻转密文里的一位（跳过前 32 字节的 IV+salt）
        val tampered = encrypted.copyOf()
        tampered[40] = (tampered[40].toInt() xor 0x01).toByte()
        assertNull("密文被篡改必须被 GCM tag 校验拒绝", CryptoUtils.decryptAES(tampered, "correct-key-123"))
    }

    @Test
    fun `hex helpers round trip and stay lowercase`() {
        val bytes = byteArrayOf(0x00, 0x1F, 0x80.toByte(), 0xFF.toByte())

        val hex = CryptoUtils.bytesToHex(bytes)
        assertEquals("001f80ff", hex)

        assertArrayEquals(bytes, CryptoUtils.hexToBytes(hex))
    }
}
