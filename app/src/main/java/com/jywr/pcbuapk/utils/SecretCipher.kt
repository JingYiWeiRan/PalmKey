package com.jywr.pcbuapk.utils

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 配对密钥的落盘加密。
 *
 * 背景：`paired_devices` 表里的 `encryptionKey` / `passwordKey` 是解锁电脑所需的
 * AES 口令，此前直接**明文**存在 SQLite 里。任何能读到
 * `/data/data/com.jywr.pcbuapk/databases/pcbu_database` 的人（root、取证镜像、
 * debuggable 构建上的 `adb run-as`）都能拿走它，进而冒充当手机与电脑端通信。
 *
 * 方案：密钥保存在 **AndroidKeyStore** 里，私钥材料由系统/TEE 持有、进程无法导出；
 * 落盘的是 AES-256-GCM 密文（`v1:<base64(iv‖ciphertext‖tag)>`）。
 * 攻击者拿到数据库文件也只能得到密文，除非能作为本应用在本机执行代码。
 *
 * 有意为之的取舍：
 * - **不**设置 `setUserAuthenticationRequired(true)`。解锁监听服务需要在息屏、
 *   后台随时读取密钥来完成握手，绑定用户认证会让后台读取直接抛异常。
 *   也就是说：这防的是「数据离开设备/被离线读取」，不是「拿到已解锁手机的人」。
 * - 信封带 `v1:` 前缀，既能识别历史明文、支持将来轮换算法，
 *   也让「解不开」和「本来就是空」可区分。
 */
object SecretCipher {

    private const val TAG = "SecretCipher"

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "pcbu_secret_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_SIZE = 12
    private const val GCM_TAG_BITS = 128

    /** 密文版本前缀。没有该前缀的值一律按历史明文处理。 */
    const val STORAGE_PREFIX = "v1:"

    @Volatile
    private var cachedKey: SecretKey? = null

    // ------------------------------------------------------------------ 对外 API

    /** 加密一个密钥值，返回可直接入库的字符串。失败时抛出，绝不静默写明文。 */
    fun encrypt(plaintext: String): String = encryptWith(keystoreKey(), plaintext)

    /**
     * 解密一个入库的密钥值。
     * - 历史明文（无前缀）→ 原样返回，保证升级后已配对设备继续可用
     * - 密文解不开 → 返回空串并记日志（该设备需要重新配对）
     */
    fun decryptOrLegacy(stored: String): String = decryptOrLegacyWith(keystoreKey(), stored)

    // ------------------------------------------------------- 信封实现（可单测）

    /**
     * 用指定密钥加密。抽出来是为了让信封逻辑能在纯 JVM 单测里验证 ——
     * AndroidKeyStore 本身在 JVM 上不存在，无法在单测中覆盖。
     */
    internal fun encryptWith(key: SecretKey, plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        // 不传 IV：由 JCE/Keystore 生成随机 IV，每次加密都不同
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

        val payload = ByteArray(iv.size + ciphertext.size)
        System.arraycopy(iv, 0, payload, 0, iv.size)
        System.arraycopy(ciphertext, 0, payload, iv.size, ciphertext.size)

        return STORAGE_PREFIX + Base64.getEncoder().encodeToString(payload)
    }

    /**
     * 用指定密钥解密。
     * @return 明文；输入不是本方案的密文、或校验/解密失败时返回 null
     */
    internal fun decryptWith(key: SecretKey, stored: String): String? {
        if (!stored.startsWith(STORAGE_PREFIX)) return null

        return try {
            val payload = Base64.getDecoder().decode(stored.removePrefix(STORAGE_PREFIX))
            // 至少要装得下 IV + GCM tag，否则直接判为损坏
            if (payload.size <= IV_SIZE) return null

            val iv = payload.copyOfRange(0, IV_SIZE)
            val ciphertext = payload.copyOfRange(IV_SIZE, payload.size)

            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))

            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (e: Exception) {
            // 密钥不匹配（例如换机恢复）或数据被篡改，GCM tag 校验会在这里失败
            Log.w(TAG, "密钥解密失败，该设备需要重新配对", e)
            null
        }
    }

    /** 带历史明文兼容的解密入口，见 [decryptOrLegacy] 的语义说明。 */
    internal fun decryptOrLegacyWith(key: SecretKey, stored: String): String {
        // 没有版本前缀 → 老版本留在库里的明文，原样返回
        if (!stored.startsWith(STORAGE_PREFIX)) return stored

        // 解不开时返回空串而不是密文本体：把密文当明文返回会一路带到协议层，
        // 表现为「密钥看起来有值但怎么都解不开」，毫无排查线索。
        return decryptWith(key, stored) ?: ""
    }

    // ------------------------------------------------------------------ 密钥管理

    /**
     * 取（必要时创建）AndroidKeyStore 中的 AES-256 密钥。
     * 密钥材料由 Keystore 持有，进程只能拿到一个不可导出的句柄。
     */
    private fun keystoreKey(): SecretKey {
        cachedKey?.let { return it }

        synchronized(this) {
            cachedKey?.let { return it }

            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val existing = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
            val key = existing?.secretKey ?: generateKey()

            cachedKey = key
            return key
        }
    }

    private fun generateKey(): SecretKey {
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )

        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // 必须为 false：解锁监听服务要在息屏/后台读取密钥完成握手，
                // 绑定用户认证会让后台读取抛异常，直接导致息屏解锁失效。
                .setUserAuthenticationRequired(false)
                .build()
        )

        Log.i(TAG, "已在 AndroidKeyStore 中创建配对密钥加密密钥")
        return generator.generateKey()
    }
}
