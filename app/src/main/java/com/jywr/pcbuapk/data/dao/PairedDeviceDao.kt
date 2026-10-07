package com.jywr.pcbuapk.data.dao

import androidx.room.*
import com.jywr.pcbuapk.data.entity.PairedDeviceEntity
import kotlinx.coroutines.flow.Flow

/**
 * 配对设备数据访问对象
 */
@Dao
interface PairedDeviceDao {
    
    /**
     * 获取所有已配对设备（按创建时间倒序）
     */
    @Query("SELECT * FROM paired_devices ORDER BY createdAt DESC")
    fun getAllDevices(): Flow<List<PairedDeviceEntity>>
    
    /**
     * 根据 ID 获取单个设备
     */
    @Query("SELECT * FROM paired_devices WHERE id = :id")
    suspend fun getDeviceById(id: String): PairedDeviceEntity?
    
    /**
     * 插入新设备
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDevice(device: PairedDeviceEntity)
    
    /**
     * 删除设备
     */
    @Delete
    suspend fun deleteDevice(device: PairedDeviceEntity)
    
    /**
     * 根据 ID 删除设备
     */
    @Query("DELETE FROM paired_devices WHERE id = :id")
    suspend fun deleteDeviceById(id: String)
    
    /**
     * 更新最后连接时间
     */
    @Query("UPDATE paired_devices SET lastConnectedAt = :timestamp WHERE id = :id")
    suspend fun updateLastConnectedTime(id: String, timestamp: Long)
    
    /**
     * 更新蓝牙地址
     */
    @Query("UPDATE paired_devices SET bluetoothAddress = :address WHERE id = :id")
    suspend fun updateBluetoothAddress(id: String, address: String)

    /**
     * 用 UDP 广播里的端点信息刷新记录。
     *
     * 广播里的 `pcbuIP` / `pcbuPort` 是电脑**当前**的 LAN IP 与解锁服务端口
     * （上游 `UDPBroadcaster.cpp`：`pcbuPort = AppSettings::Get().unlockServerPort`，
     * 与配对响应里的 `data.port` 同源）。
     * 配对时写死的值会因 DHCP 换 IP 或电脑端改端口而失效，
     * 此前完全忽略广播里的端点，表现为「电脑在线但手机解不开」。
     */
    @Query("UPDATE paired_devices SET ipAddress = :ip, tcpPort = :port WHERE id = :id")
    suspend fun updateEndpoint(id: String, ip: String, port: Int)
    
    /**
     * 获取设备数量
     */
    @Query("SELECT COUNT(*) FROM paired_devices")
    suspend fun getDeviceCount(): Int
    
    /**
     * 根据蓝牙地址查询设备
     */
    @Query("SELECT * FROM paired_devices WHERE bluetoothAddress = :address LIMIT 1")
    suspend fun getDeviceByBluetoothAddress(address: String): PairedDeviceEntity?
    
    /**
     * 根据设备名称查询设备
     */
    @Query("SELECT * FROM paired_devices WHERE deviceName = :name LIMIT 1")
    suspend fun getDeviceByName(name: String): PairedDeviceEntity?

    /**
     * 找出配对密钥仍以**明文**存放的设备 ID，用于升级时补加密。
     *
     * 两点关键：
     * 1. `WHERE` 作用在**原始存储值**上，不受字段级 TypeConverter 影响，
     *    所以能精确命中老版本留下的明文行。
     * 2. 刻意排除已加密的行（`v1:` 前缀），不会把密文再加密一次。
     *    前缀字面量必须与 `SecretCipher.STORAGE_PREFIX` 保持一致。
     */
    @Query(
        "SELECT id FROM paired_devices " +
                "WHERE encryptionKey NOT LIKE 'v1:%' " +
                "OR (passwordKey IS NOT NULL AND passwordKey NOT LIKE 'v1:%')"
    )
    suspend fun getDeviceIdsWithPlaintextSecret(): List<String>
}
