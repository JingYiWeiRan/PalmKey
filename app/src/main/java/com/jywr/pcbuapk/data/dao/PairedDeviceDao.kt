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
}
