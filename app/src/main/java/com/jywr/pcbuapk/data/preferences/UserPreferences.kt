package com.jywr.pcbuapk.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 用户偏好设置
 */
class UserPreferences(private val context: Context) {
    
    companion object {
        private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "user_preferences")
        
        // 通知验证模式：true=模式A（进入App），false=模式B（通知栏直接验证）
        val NOTIFICATION_VERIFY_MODE_A = booleanPreferencesKey("notification_verify_mode_a")
        
        // UDP 监听端口
        val UDP_LISTEN_PORT = intPreferencesKey("udp_listen_port")
        
        // 保活模式：0=省电, 1=平衡(默认), 2=可靠
        val KEEP_ALIVE_MODE = intPreferencesKey("keep_alive_mode")
        
        // 默认值
        const val DEFAULT_UDP_PORT = 8888
        const val DEFAULT_NOTIFICATION_MODE_A = true
        const val DEFAULT_KEEP_ALIVE_MODE = 1 // 平衡模式
    }
    
    /**
     * 获取所有偏好设置
     */
    val preferences: Flow<UserPreferencesData> = context.dataStore.data.map { prefs ->
        UserPreferencesData(
            notificationModeA = prefs[NOTIFICATION_VERIFY_MODE_A] ?: DEFAULT_NOTIFICATION_MODE_A,
            udpListenPort = prefs[UDP_LISTEN_PORT] ?: DEFAULT_UDP_PORT,
            keepAliveMode = prefs[KEEP_ALIVE_MODE] ?: DEFAULT_KEEP_ALIVE_MODE
        )
    }
    
    /**
     * 更新通知验证模式
     */
    suspend fun setNotificationModeA(modeA: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[NOTIFICATION_VERIFY_MODE_A] = modeA
        }
    }
    
    /**
     * 更新 UDP 监听端口
     */
    suspend fun setUdpListenPort(port: Int) {
        context.dataStore.edit { prefs ->
            prefs[UDP_LISTEN_PORT] = port
        }
    }
    
    /**
     * 更新保活模式
     * @param mode 0=省电, 1=平衡, 2=可靠
     */
    suspend fun setKeepAliveMode(mode: Int) {
        context.dataStore.edit { prefs ->
            prefs[KEEP_ALIVE_MODE] = mode.coerceIn(0, 2)
        }
    }
}

/**
 * 用户偏好设置数据类
 */
data class UserPreferencesData(
    val notificationModeA: Boolean,
    val udpListenPort: Int,
    val keepAliveMode: Int // 0=省电, 1=平衡, 2=可靠
)
