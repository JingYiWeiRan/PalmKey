package com.jywr.pcbuapk.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.jywr.pcbuapk.service.KeepAlivePolicy
import com.jywr.pcbuapk.service.UnlockListenerService
import com.jywr.pcbuapk.utils.KeepAliveManager

/**
 * 开机广播接收器 + 应用更新广播
 * 设备启动完成后自动启动解锁监听服务
 *
 * 「不保活」模式下这里**必须什么都不做**：否则用户清掉应用、重启手机后它又自己回来了，
 * 那就违背了那个模式的全部意义（用户要的是"清掉就死透"）。
 * 模式从 SharedPreferences 同步读 —— 这是接收器里唯一可行的读法（DataStore 只能异步读）。
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
                val mode = KeepAliveManager.getKeepAliveMode(context)
                if (!KeepAlivePolicy.shouldStartOnBoot(mode)) {
                    Log.i(TAG, "不保活模式（mode=$mode）：开机不自启，等待用户手动打开")
                    return
                }
                Log.i(TAG, "收到广播: ${intent.action}，启动解锁监听服务")
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
            
            Log.i(TAG, "解锁监听服务已启动")
        } catch (e: Exception) {
            Log.e(TAG, "启动服务失败", e)
        }
    }
}
