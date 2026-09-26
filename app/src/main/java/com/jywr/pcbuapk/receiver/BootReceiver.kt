package com.jywr.pcbuapk.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.jywr.pcbuapk.service.UnlockListenerService

/**
 * 开机广播接收器 + 应用更新广播
 * 设备启动完成后自动启动解锁监听服务
 */
class BootReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "BootReceiver"
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Log.d(TAG, "收到广播: ${intent.action}，启动解锁监听服务")
                startServiceSafely(context)
            }
        }
    }
    
    private fun startServiceSafely(context: Context) {
        try {
            val serviceIntent = Intent(context, UnlockListenerService::class.java)
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            
            Log.d(TAG, "解锁监听服务已启动")
        } catch (e: Exception) {
            Log.e(TAG, "启动服务失败", e)
        }
    }
}
