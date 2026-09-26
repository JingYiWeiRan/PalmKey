package com.jywr.pcbuapk.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Build
import android.util.Log
import com.jywr.pcbuapk.service.UnlockListenerService

/**
 * 网络状态变化广播接收器
 * 网络切换时重启服务，保持连接活性
 */
class NetworkStateReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "NetworkStateReceiver"
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ConnectivityManager.CONNECTIVITY_ACTION ||
            intent.action == "android.net.conn.CONNECTIVITY_CHANGE") {
            
            Log.d(TAG, "网络状态发生变化，尝试重启服务")
            
            // 重启服务以保持连接活性
            val serviceIntent = Intent(context, UnlockListenerService::class.java)
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            
            Log.d(TAG, "已触发服务重启")
        }
    }
}
