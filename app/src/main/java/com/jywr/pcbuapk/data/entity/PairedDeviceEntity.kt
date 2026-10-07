package com.jywr.pcbuapk.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import com.jywr.pcbuapk.data.converter.NullableSecretStringConverter
import com.jywr.pcbuapk.data.converter.SecretString
import com.jywr.pcbuapk.data.converter.SecretStringConverter

/**
 * 已配对设备实体类
 * 对应服务端的 PairedDevice 结构
 *
 * 索引说明：蓝牙唤醒时是按 `bluetoothAddress`（失败再按 `deviceName`）查设备的，
 * 这两列原先没有索引，等于每次解锁都在做两次全表扫描，而且这段查询就挤在
 * 3 秒的读包预算里。加索引后保持一致性与延迟。
 */
@Entity(
    tableName = "paired_devices",
    indices = [
        Index(value = ["bluetoothAddress"]),
        Index(value = ["deviceName"])
    ]
)
data class PairedDeviceEntity(
    @PrimaryKey
    val id: String,                          // 设备唯一标识（SHA256）
    val deviceName: String,                  // 设备名称（用户电脑的主机名）
    val pairingMethod: String,               // 配对方式：TCP 或 BLUETOOTH
    val userName: String,                    // 电脑用户名
    val passwordEnc: String,                 // 加密后的密码

    // ⚠️ 下面两列是解锁电脑所需的 AES 口令，绝不可以明文落盘。
    // 字段级 TypeConverter 在 Room 读写边界上自动加解密（密钥由 AndroidKeyStore 持有）：
    // 内存里是 SecretString 明文、数据库里是 `v1:<base64(iv‖ciphertext‖tag)>` 密文。
    // 列类型仍是 TEXT，因此不涉及数据库迁移；改动这两列时请同时跑 SecretCipherTest。
    @field:TypeConverters(SecretStringConverter::class)
    val encryptionKey: SecretString,         // 加密密钥（来自二维码，用于解锁通信）

    @field:TypeConverters(NullableSecretStringConverter::class)
    val passwordKey: SecretString? = null,   // 密码密钥（来自配对响应，用于解锁响应）
    
    // TCP 连接信息
    val ipAddress: String? = null,           // 电脑 IP 地址
    val tcpPort: Int? = null,                // TCP 解锁端口
    val udpPort: Int? = null,                // UDP 广播监听端口（手机端）
    val udpManualPort: Int? = null,          // 手动 UDP 端口
    
    // 蓝牙连接信息
    val bluetoothAddress: String? = null,    // 蓝牙 MAC 地址
    
    // 云端相关（暂不支持）
    val cloudToken: String? = null,
    
    // 元数据
    val createdAt: Long = System.currentTimeMillis(),  // 配对时间
    val lastConnectedAt: Long? = null        // 最后连接时间
)
