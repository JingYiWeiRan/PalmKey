package com.jywr.pcbuapk.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.jywr.pcbuapk.service.KeepAlivePolicy
import com.jywr.pcbuapk.utils.KeepAliveManager
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
     * @param mode 0=省电, 1=平衡, 2=可靠, 3=不保活（不做任何主动续命，清掉即彻底停止）
     */
    suspend fun setKeepAliveMode(mode: Int) {
        // 上界是 3：新增了「不保活」模式。若仍按旧的 0..2 收敛，用户选它会被悄悄改成 2。
        val safeMode = mode.coerceIn(0, 3)
        context.dataStore.edit { prefs ->
            prefs[KEEP_ALIVE_MODE] = safeMode
        }

        // 同步写一份镜像到 SharedPreferences。
        // 为什么需要镜像：服务的 onStartCommand（决定 START_STICKY）与 onTaskRemoved
        // （决定划掉任务后是否停服）、以及 BootReceiver 都必须**同步**读到模式，
        // 而 DataStore 只能异步读。这里是"模式"唯一会被用户改动的地方，所以在此同步。
        // 详见 KeepAliveManager.getKeepAliveMode。
        KeepAliveManager.setKeepAliveMode(context, safeMode)

        // 模式变化必须**立刻**作用到保活链，不能等到"下次服务启动"：
        //   - 切到「不保活」：当场撤销已排的闹钟与 WorkManager。否则那条会自我续期的
        //     闹钟仍会把服务拉起来，与「清掉就死透」直接冲突（真机复现过）。
        //   - 切回常规模式：叫服务重新评估，否则保活链永远不再建立 ——
        //     真机实测切回「平衡」后待触发闹钟为空，且没有任何报错，属于静默失效。
        if (KeepAlivePolicy.shouldArmKeepAlive(safeMode)) {
            KeepAliveManager.requestKeepAliveReconcile(context)
        } else {
            KeepAliveManager.stopKeepAlive(context)
        }
    }
}

/**
 * 用户偏好设置数据类
 */
data class UserPreferencesData(
    val notificationModeA: Boolean,
    val udpListenPort: Int,
    val keepAliveMode: Int // 0=省电, 1=平衡, 2=可靠, 3=不保活
)
