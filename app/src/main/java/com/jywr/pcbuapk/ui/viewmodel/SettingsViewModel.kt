package com.jywr.pcbuapk.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jywr.pcbuapk.data.preferences.UserPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 设置界面 ViewModel
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val userPreferences: UserPreferences
) : ViewModel() {
    
    /**
     * 用户偏好设置（响应式）
     */
    val preferences = userPreferences.preferences
        .stateIn(viewModelScope, SharingStarted.Lazily, 
            com.jywr.pcbuapk.data.preferences.UserPreferencesData(
                notificationModeA = true,
                udpListenPort = 8888,
                keepAliveMode = 1 // 平衡模式
            )
        )
    
    /**
     * 设置通知验证模式
     */
    fun setNotificationMode(modeA: Boolean) {
        viewModelScope.launch {
            userPreferences.setNotificationModeA(modeA)
        }
    }
    
    /**
     * 设置 UDP 端口
     */
    fun setUdpPort(port: Int) {
        viewModelScope.launch {
            if (port in 1024..65535) {
                userPreferences.setUdpListenPort(port)
            }
        }
    }
    
    /**
     * 设置保活模式
     * @param mode 0=省电, 1=平衡, 2=可靠
     */
    fun setKeepAliveMode(mode: Int) {
        viewModelScope.launch {
            userPreferences.setKeepAliveMode(mode)
        }
    }
}
