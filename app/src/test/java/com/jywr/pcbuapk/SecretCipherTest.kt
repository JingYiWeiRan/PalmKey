package com.jywr.pcbuapk

import com.jywr.pcbuapk.utils.SecretCipher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * 配对密钥「落盘加密」信封的测试。
 *
 * 背景：`paired_devices` 表里的 `encryptionKey` / `passwordKey` 此前是**明文**存储的，
 * 相当于把解锁电脑所需的 AES 口令直接写在数据库里。这些用例锁定替换方案的信封行为。
 *
 * 注意这里只测信封本身（[SecretCipher.encryptWith] / [SecretCipher.decryptWith]），
 * 它们接受外部传入的密钥，因此可以在纯 JVM 上跑；真正的 AndroidKeyStore
 * 取密钥路径无法在 JVM 单测里覆盖，靠的是「密钥不出 TEE/Keystore」这一平台保证。
 */
class SecretCipherTest {

    private fun jvmKey(): SecretKey =
        KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test
    fun `encrypt then decrypt round trips`() {
        val key = jvmKey()
        val plaintext = "Xq7fZ2mK9pLr4tWv1yB6nC3dE8gH5jS0aQzUoIeR"

        val stored = SecretCipher.encryptWith(key, plaintext)

        assertEquals(
            "解密后必须还原原始密钥",
            plaintext,
            SecretCipher.decryptWith(key, stored)
        )
    }

    @Test
    fun `stored value is marked and does not leak the plaintext`() {
        val key = jvmKey()
        val plaintext = "MySecretEncryptionKey123456"

        val stored = SecretCipher.encryptWith(key, plaintext)

        assertTrue(
            "密文必须带版本前缀，便于识别历史明文并支持将来轮换",
            stored.startsWith(SecretCipher.STORAGE_PREFIX)
        )
        assertFalse(
            "密文里不能出现明文（这是整个修复的意义）",
            stored.contains(plaintext)
        )
    }

    @Test
    fun `same plaintext encrypts differently each time`() {
        val key = jvmKey()
        val plaintext = "same-value"

        val first = SecretCipher.encryptWith(key, plaintext)
        val second = SecretCipher.encryptWith(key, plaintext)

        assertNotEquals(
            "每次必须用新的随机 IV，否则相同密钥会产生相同密文（可被比对识别）",
            first,
            second
        )
        assertEquals(plaintext, SecretCipher.decryptWith(key, first))
        assertEquals(plaintext, SecretCipher.decryptWith(key, second))
    }

    @Test
    fun `decrypt rejects a wrong key`() {
        val stored = SecretCipher.encryptWith(jvmKey(), "top-secret")

        assertNull(
            "换一把密钥必须解不开（GCM tag 校验失败）",
            SecretCipher.decryptWith(jvmKey(), stored)
        )
    }

    @Test
    fun `decrypt rejects tampered ciphertext`() {
        val key = jvmKey()
        val stored = SecretCipher.encryptWith(key, "top-secret")

        // 翻转 base64 载荷中段的一个字符，模拟被改写过的密文
        val body = stored.removePrefix(SecretCipher.STORAGE_PREFIX)
        val index = body.length / 2
        val flipped = if (body[index] == 'A') 'B' else 'A'
        val tampered = SecretCipher.STORAGE_PREFIX +
                body.substring(0, index) + flipped + body.substring(index + 1)

        assertNull(
            "密文被篡改必须被 GCM tag 拒绝，而不是返回垃圾明文",
            SecretCipher.decryptWith(key, tampered)
        )
    }

    @Test
    fun `decrypt rejects malformed input instead of throwing`() {
        val key = jvmKey()

        assertNull(SecretCipher.decryptWith(key, SecretCipher.STORAGE_PREFIX))
        assertNull(SecretCipher.decryptWith(key, SecretCipher.STORAGE_PREFIX + "not-base64!!"))
        // 太短，装不下 IV + tag
        assertNull(SecretCipher.decryptWith(key, SecretCipher.STORAGE_PREFIX + "AAAA"))
    }

    @Test
    fun `empty string round trips`() {
        val key = jvmKey()

        val stored = SecretCipher.encryptWith(key, "")

        assertEquals("", SecretCipher.decryptWith(key, stored))
    }

    /**
     * 升级路径：老版本的库里存的是明文，必须原样读出来，否则所有已配对用户升级后立即失效。
     */
    @Test
    fun `legacy plaintext is passed through so upgrades keep working`() {
        val key = jvmKey()
        val legacyPlaintext = "legacyPlaintextKeyFromOldVersion"

        assertEquals(
            "没有版本前缀的值必须按历史明文原样返回",
            legacyPlaintext,
            SecretCipher.decryptOrLegacyWith(key, legacyPlaintext)
        )
    }

    @Test
    fun `encrypted values still decrypt through the legacy-aware entry point`() {
        val key = jvmKey()
        val plaintext = "newlyEncryptedKey"

        val stored = SecretCipher.encryptWith(key, plaintext)

        assertEquals(
            "带前缀的值必须真的解密，不能原样返回密文",
            plaintext,
            SecretCipher.decryptOrLegacyWith(key, stored)
        )
    }

    /**
     * 解不开时返回空串而不是密文本身：返回密文会让它被当成密钥使用并一路带到协议层，
     * 排查起来毫无线索；返回空串则会在日志里明确报出「密钥解不开，需重新配对」。
     */
    @Test
    fun `undecryptable ciphertext yields empty string rather than the ciphertext`() {
        val stored = SecretCipher.encryptWith(jvmKey(), "value")
        val otherKey = jvmKey()

        val result = SecretCipher.decryptOrLegacyWith(otherKey, stored)

        assertEquals("解不开必须返回空串", "", result)
        assertFalse("绝不能把密文当明文返回", result.contains(SecretCipher.STORAGE_PREFIX))
    }
}
