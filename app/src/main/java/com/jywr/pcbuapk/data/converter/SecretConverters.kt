package com.jywr.pcbuapk.data.converter

import androidx.room.TypeConverter
import com.jywr.pcbuapk.utils.SecretCipher

/**
 * 落盘时会被加密的字符串。
 *
 * 内存里始终持有**明文**，只有跨越 Room 的读写边界时才变成密文。
 *
 * 为什么不直接对 `String` 做转换，而要包一层：
 * 1. Room 根本不允许 `String ⇄ String` 的转换器 —— 输入输出同型、两个方向冲突，
 *    KSP 会以 "Multiple methods define the same conversion" 直接编译失败；
 * 2. 更重要的是**编译器强制**：字段类型是 [SecretString]，
 *    每个使用点都必须显式 `.value` 才能拿到明文，因此不可能出现
 *    「某处忘了解密、把密文当密钥用」这种静默错误。
 */
data class SecretString(val value: String)

/**
 * `encryptionKey` 列（非空）的落盘加解密。
 *
 * 数据库列类型仍是 `TEXT NOT NULL`，与改造前完全一致，
 * 所以**不需要新的数据库迁移**：老库升级上来结构不变，
 * 老数据里的明文由 [SecretCipher.decryptOrLegacy] 兼容读取。
 */
class SecretStringConverter {

    @TypeConverter
    fun toStorage(value: SecretString): String = SecretCipher.encrypt(value.value)

    @TypeConverter
    fun fromStorage(value: String): SecretString = SecretString(SecretCipher.decryptOrLegacy(value))
}

/**
 * `passwordKey` 列（可空）的落盘加解密。语义同 [SecretStringConverter]。
 */
class NullableSecretStringConverter {

    @TypeConverter
    fun toStorage(value: SecretString?): String? = value?.let { SecretCipher.encrypt(it.value) }

    @TypeConverter
    fun fromStorage(value: String?): SecretString? =
        value?.let { SecretString(SecretCipher.decryptOrLegacy(it)) }
}
