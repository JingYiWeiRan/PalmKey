package com.jywr.pcbuapk.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jywr.pcbuapk.data.dao.PairedDeviceDao
import com.jywr.pcbuapk.data.entity.PairedDeviceEntity
import com.jywr.pcbuapk.service.UnlockService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 主界面 ViewModel
 */
@HiltViewModel
class MainViewModel @Inject constructor(
    private val pairedDeviceDao: PairedDeviceDao,
    private val unlockService: UnlockService
) : ViewModel() {
    
    /**
     * 设备列表（响应式）
     */
    val devices = pairedDeviceDao.getAllDevices()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    
    /**
     * 删除设备
     */
    fun deleteDevice(deviceId: String) {
        viewModelScope.launch {
            pairedDeviceDao.deleteDeviceById(deviceId)
        }
    }

    /**
     * 恢复被误删的设备（配合删除后的「撤销」）。
     *
     * 主键相同且 insertDevice 用的是 OnConflictStrategy.REPLACE，原样写回即可，
     * 加密密钥等字段都还在，不需要重新配对。
     */
    fun restoreDevice(device: PairedDeviceEntity) {
        viewModelScope.launch {
            pairedDeviceDao.insertDevice(device)
        }
    }
    
    /**
     * 更新蓝牙地址
     */
    fun updateBluetoothAddress(deviceId: String, address: String) {
        viewModelScope.launch {
            pairedDeviceDao.updateBluetoothAddress(deviceId, address)
        }
    }
    
    /**
     * 解锁设备（需要生物识别）
     * @param deviceId 设备 ID
     * @param unlockToken 解锁令牌（由生物识别生成）
     * @param callback 解锁结果回调（null 表示成功，否则为错误消息）
     */
    fun unlockWithToken(deviceId: String, unlockToken: String, callback: (String?) -> Unit) {
        viewModelScope.launch {
            val result = unlockService.unlock(deviceId, unlockToken)
            callback(result)
        }
    }
    
    /**
     * 蓝牙解锁（通过已建立的连接发送响应）
     *
     * @param deviceId 设备 ID
     * @param unlockToken 生物识别生成的本地令牌。协议要求回显的是 PC 下发的 token，
     *   故该值不进入报文体；它只表达「人证核验已通过」这一前置条件。
     * @param callback 解锁结果回调（null 表示成功，否则为错误消息）
     */
    fun unlockBluetoothWithToken(deviceId: String, unlockToken: String, callback: (String?) -> Unit) {
        viewModelScope.launch {
            try {
                // 获取设备信息
                val device = pairedDeviceDao.getDeviceById(deviceId)
                if (device == null) {
                    callback("设备不存在")
                    return@launch
                }

                // 通过静态引用访问 UnlockListenerService
                val service = com.jywr.pcbuapk.service.UnlockListenerService.instance
                if (service != null) {
                    // 结果必须如实回传给界面。
                    // sendBluetoothResponse 会等待 PC 的 unlockToken（最多 TOKEN_WAIT_TIMEOUT_MS），
                    // 并返回 null 或失败原因；早先它返回 Unit 且内部吞掉全部失败，
                    // 导致这里无论实际成败都报「解锁成功」。
                    callback(service.sendBluetoothResponse(device))
                } else {
                    callback("解锁服务未运行\n请确保应用正在后台运行")
                }

            } catch (e: Exception) {
                callback("蓝牙解锁失败: ${e.message}")
            }
        }
    }
}
