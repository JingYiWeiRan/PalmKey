package com.jywr.pcbuapk.utils

import android.util.Log
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 加密工具类
 * 与服务端 CryptUtils 保持一致
 * 使用 AES-256-GCM 加密算法
 */
object CryptoUtils {
    
    private const val TAG = "CryptoUtils"
    
    // 加密参数（与服务端一致）
    private const val AES_KEY_SIZE = 256
    private const val IV_SIZE = 16
    private const val SALT_SIZE = 16
    private const val GCM_TAG_SIZE = 16
    private const val ITERATIONS = 65535
    private const val ALGORITHM = "AES/GCM/NoPadding"
    private const val KEY_ALGORITHM = "PBKDF2WithHmacSHA256"
    
    /**
     * SHA3-256 哈希（与PC端 CryptUtils::Sha256 保持一致，实际使用SHA3-256）
     * Android 10+ (API 29+) 原生支持 SHA3-256
     */
    fun sha256(text: String): String {
        return try {
            // 尝试使用 SHA3-256（Android 10+）
            val digest = MessageDigest.getInstance("SHA3-256")
            val hash = digest.digest(text.toByteArray(Charsets.UTF_8))
            hash.joinToString("") { "%02x".format(it) }
        } catch (e: java.security.NoSuchAlgorithmException) {
            // 如果不支持 SHA3-256（Android 9），回退到 SHA-256
            Log.w(TAG, "SHA3-256 不支持，回退到 SHA-256（可能导致与PC端不兼容）", e)
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                val hash = digest.digest(text.toByteArray(Charsets.UTF_8))
                hash.joinToString("") { "%02x".format(it) }
            } catch (e2: Exception) {
                Log.e(TAG, "哈希计算失败", e2)
                ""
            }
        } catch (e: Exception) {
            Log.e(TAG, "SHA3-256 计算失败", e)
            ""
        }
    }
    
    /**
     * AES-GCM 加密（带时间戳）
     * @param data 原始数据
     * @param password 密码（用于生成密钥）
     * @return 加密后的数据（IV + Salt + Ciphertext + Tag）
     */
    fun encryptAES(data: ByteArray, password: String): ByteArray? {
        return try {
            // 在数据前添加 8 字节时间戳（大端序）- 与服务端 EncryptAESPacket 一致
            val timestamp = System.currentTimeMillis()
            val dataWithTimestamp = ByteArray(data.size + 8)
            // 将时间戳转换为大端序字节数组
            for (i in 7 downTo 0) {
                dataWithTimestamp[7 - i] = (timestamp shr (i * 8)).toByte()
            }
            System.arraycopy(data, 0, dataWithTimestamp, 8, data.size)
            
            // 生成随机 IV 和 Salt
            val iv = ByteArray(IV_SIZE)
            val salt = ByteArray(SALT_SIZE)
            java.security.SecureRandom().nextBytes(iv)
            java.security.SecureRandom().nextBytes(salt)
            
            // 生成密钥
            val key = generateKey(password, salt)
            
            // 初始化 Cipher
            val cipher = Cipher.getInstance(ALGORITHM)
            val gcmParameterSpec = GCMParameterSpec(GCM_TAG_SIZE * 8, iv)
            cipher.init(Cipher.ENCRYPT_MODE, key, gcmParameterSpec)
            
            // 加密（包含时间戳）
            val ciphertext = cipher.doFinal(dataWithTimestamp)
            
            // 组合：IV + Salt + Ciphertext（包含GCM Tag）
            // PC端期望的格式：IV(16) + Salt(16) + Ciphertext + GCM_Tag(16)
            // cipher.doFinal() 返回的就是包含Tag的完整数据
            val result = ByteArray(IV_SIZE + SALT_SIZE + ciphertext.size)
            System.arraycopy(iv, 0, result, 0, IV_SIZE)
            System.arraycopy(salt, 0, result, IV_SIZE, SALT_SIZE)
            System.arraycopy(ciphertext, 0, result, IV_SIZE + SALT_SIZE, ciphertext.size)
            
            result
        } catch (e: Exception) {
            Log.e(TAG, "AES 加密失败", e)
            null
        }
    }
    
    /**
     * AES-GCM 解密（带时间戳验证）
     * @param encryptedData 加密数据（IV + Salt + Ciphertext + Tag）
     * @param password 密码（用于生成密钥）
     * @return 解密后的原始数据（不包含时间戳）
     */
    fun decryptAES(encryptedData: ByteArray, password: String): ByteArray? {
        return try {
            if (encryptedData.size < IV_SIZE + SALT_SIZE + GCM_TAG_SIZE) {
                Log.e(TAG, "加密数据长度不足")
                return null
            }
            
            // 提取 IV 和 Salt
            val iv = ByteArray(IV_SIZE)
            val salt = ByteArray(SALT_SIZE)
            System.arraycopy(encryptedData, 0, iv, 0, IV_SIZE)
            System.arraycopy(encryptedData, IV_SIZE, salt, 0, SALT_SIZE)
            
            // 提取 Ciphertext（包含 Tag）
            val ciphertext = ByteArray(encryptedData.size - IV_SIZE - SALT_SIZE)
            System.arraycopy(encryptedData, IV_SIZE + SALT_SIZE, ciphertext, 0, ciphertext.size)
            
            // 生成密钥
            val key = generateKey(password, salt)
            
            // 初始化 Cipher
            val cipher = Cipher.getInstance(ALGORITHM)
            val gcmParameterSpec = GCMParameterSpec(GCM_TAG_SIZE * 8, iv)
            cipher.init(Cipher.DECRYPT_MODE, key, gcmParameterSpec)
            
            // 解密（包含时间戳）
            val dataWithTimestamp = cipher.doFinal(ciphertext)
            
            // 验证数据长度（至少要有 8 字节时间戳）
            if (dataWithTimestamp.size < 8) {
                Log.e(TAG, "解密后数据长度不足，缺少时间戳")
                return null
            }
            
            // 提取时间戳（大端序）
            var timestamp: Long = 0
            for (i in 0 until 8) {
                timestamp = timestamp or ((dataWithTimestamp[i].toLong() and 0xFF) shl (56 - i * 8))
            }
            
            // 验证时间戳（2分钟超时）
            val currentTime = System.currentTimeMillis()
            val timeDiff = currentTime - timestamp
            if (timeDiff < -120000 || timeDiff > 120000) {
                Log.e(TAG, "时间戳验证失败: 时间差=${timeDiff}ms")
                return null
            }
            
            // 返回去除时间戳后的原始数据
            val data = ByteArray(dataWithTimestamp.size - 8)
            System.arraycopy(dataWithTimestamp, 8, data, 0, data.size)
            
            data
        } catch (e: Exception) {
            Log.e(TAG, "AES 解密失败", e)
            null
        }
    }
    
    /**
     * 从密码生成 AES 密钥（PBKDF2）
     */
    private fun generateKey(password: String, salt: ByteArray): SecretKey {
        val spec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, AES_KEY_SIZE)
        val skf = SecretKeyFactory.getInstance(KEY_ALGORITHM)
        val tmp = skf.generateSecret(spec)
        return SecretKeySpec(tmp.encoded, "AES")
    }
    
    /**
     * 字节数组转十六进制字符串
     */
    fun bytesToHex(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02x".format(it) }
    }
    
    /**
     * 十六进制字符串转字节数组
     */
    fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        for (i in 0 until len step 2) {
            data[i / 2] = ((hex[i].toString().toInt(16) shl 4) + hex[i + 1].toString().toInt(16)).toByte()
        }
        return data
    }
}
