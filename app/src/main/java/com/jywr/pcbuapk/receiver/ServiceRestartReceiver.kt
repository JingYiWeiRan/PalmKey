package com.jywr.pcbuapk.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.jywr.pcbuapk.service.UnlockListenerService

/**
 * 服务重启广播接收器
 * 监听各种系统事件，在适当时机尝试重启服务
 * 
 * 监听的事件：
 * 1. 屏幕点亮 - 用户可能开始使用手机
 * 2. 网络连接变化 - 网络恢复后重启服务
 * 3. 用户解锁屏幕 - 用户活跃时重启服务
 */
class ServiceRestartReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "ServiceRestartReceiver"
        
        const val ACTION_SCREEN_ON = "android.intent.action.SCREEN_ON"
        const val ACTION_USER_PRESENT = "android.intent.action.USER_PRESENT"
        const val ACTION_CONNECTIVITY_CHANGE = "android.net.conn.CONNECTIVITY_CHANGE"
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_SCREEN_ON -> {
                Log.d(TAG, "📱 检测到屏幕点亮")
                restartServiceIfNeeded(context)
            }
            ACTION_USER_PRESENT -> {
                Log.d(TAG, "🔓 检测到用户解锁")
                restartServiceIfNeeded(context)
            }
            ACTION_CONNECTIVITY_CHANGE -> {
                // 检查是否有网络连接
                if (isNetworkAvailable(context)) {
                    Log.d(TAG, "🌐 检测到网络可用")
                    restartServiceIfNeeded(context)
                } else {
                    Log.d(TAG, "🌐 网络不可用，跳过重启")
                }
            }
        }
    }
    
    /**
     * 检查服务是否在运行，如果不在则重启
     */
    private fun restartServiceIfNeeded(context: Context) {
        try {
            // 检查服务是否正在运行
            if (!isServiceRunning(context, UnlockListenerService::class.java)) {
                Log.d(TAG, "🔄 服务未运行，尝试重启")
                
                val serviceIntent = Intent(context, UnlockListenerService::class.java)
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ContextCompat.startForegroundService(context, serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
                
                Log.d(TAG, "✅ 已尝试重启服务")
            } else {
                Log.d(TAG, "ℹ️ 服务已在运行，无需重启")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ 重启服务失败", e)
        }
    }
    
    /**
     * 检查服务是否正在运行
     */
    private fun isServiceRunning(context: Context, serviceClass: Class<*>): Boolean {
        return try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val services = activityManager.getRunningServices(Int.MAX_VALUE)
            
            for (service in services) {
                if (serviceClass.name == service.service.className) {
                    return true
                }
            }
            false
        } catch (e: Exception) {
            Log.e(TAG, "检查服务状态失败", e)
            false
        }
    }
    
    /**
     * 检查网络是否可用
     */
    private fun isNetworkAvailable(context: Context): Boolean {
        return try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val network = connectivityManager.activeNetwork
                val capabilities = connectivityManager.getNetworkCapabilities(network)
                capabilities != null && (
                    capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ||
                    capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) ||
                    capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)
                )
            } else {
                @Suppress("DEPRECATION")
                val networkInfo = connectivityManager.activeNetworkInfo
                networkInfo != null && networkInfo.isConnected
            }
        } catch (e: Exception) {
            Log.e(TAG, "检查网络状态失败", e)
            false
        }
    }
}
