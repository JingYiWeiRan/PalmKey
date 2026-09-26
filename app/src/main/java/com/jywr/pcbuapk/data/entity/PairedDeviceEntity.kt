package com.jywr.pcbuapk.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 已配对设备实体类
 * 对应服务端的 PairedDevice 结构
 */
@Entity(tableName = "paired_devices")
data class PairedDeviceEntity(
    @PrimaryKey
    val id: String,                          // 设备唯一标识（SHA256）
    val deviceName: String,                  // 设备名称（用户电脑的主机名）
    val pairingMethod: String,               // 配对方式：TCP 或 BLUETOOTH
    val userName: String,                    // 电脑用户名
    val passwordEnc: String,                 // 加密后的密码
    val encryptionKey: String,               // 加密密钥（来自二维码，用于解锁通信）
    val passwordKey: String? = null,         // 密码密钥（来自配对响应，用于解锁响应）
    
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
