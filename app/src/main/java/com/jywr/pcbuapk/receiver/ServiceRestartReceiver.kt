package com.jywr.pcbuapk.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.jywr.pcbuapk.service.UnlockListenerService

/**
 * 服务重启广播接收器
 * 在用户完成解锁时确认解锁监听服务仍然在运行
 *
 * 监听的事件（必须与 AndroidManifest 中注册的 action 一致）：
 * - 用户解锁屏幕（USER_PRESENT）
 *
 * ⚠️ 两个**不能**放在这里的 action：
 * 1. `SCREEN_ON`：属于「只能动态注册接收」的系统广播，平台不会投递给静态注册的
 *    接收器，写在清单里是死配置。而且动态注册也达不到"服务死了把它拉起来"的原意
 *    （动态注册随进程消失）。该职责由 AlarmManager 保活链 + START_STICKY + BootReceiver 承担。
 * 2. `CONNECTIVITY_CHANGE`：targetSdk ≥ 26 起同样不再投递给静态接收器，
 *    网络恢复改由 `UnlockListenerService.registerNetworkCallback` 处理。
 */
class ServiceRestartReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ServiceRestartReceiver"

        const val ACTION_USER_PRESENT = "android.intent.action.USER_PRESENT"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_USER_PRESENT -> {
                Log.i(TAG, "🔓 检测到用户解锁")
                restartServiceIfNeeded(context)
                // 用户解锁是最高频的"人在手机旁"时刻，顺带幂等地确认监听链路还在。
                // 若 UDP 接收循环曾异常退出、状态位被复位，这里就会把它重建起来
                // （服务正常运行时该调用无副作用）。见 UnlockListenerService.ensureListenersRunning。
                UnlockListenerService.instance?.ensureListenersRunning()
            }
        }
    }

    /**
     * 检查服务是否在运行，如果不在则重启
     *
     * 必须整体包在 try/catch 里：Android 12+ 从后台启动前台服务会抛
     * `ForegroundServiceStartNotAllowedException`，而清单注册接收器的 `onReceive`
     * 一旦抛出未捕获异常就是进程崩溃。
     */
    private fun restartServiceIfNeeded(context: Context) {
        try {
            // 检查服务是否正在运行
            if (!isServiceRunning(context, UnlockListenerService::class.java)) {
                Log.i(TAG, "🔄 服务未运行，尝试重启")

                val serviceIntent = Intent(context, UnlockListenerService::class.java)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ContextCompat.startForegroundService(context, serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }

                Log.i(TAG, "✅ 已尝试重启服务")
            } else {
                Log.i(TAG, "ℹ️ 服务已在运行，无需重启")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ 重启服务失败", e)
        }
    }

    /**
     * 检查服务是否正在运行
     *
     * 注意 `ActivityManager.getRunningServices` 自 API 26 起已废弃，
     * 且只返回调用方自己的服务。对本应用自己的前台服务仍可用，
     * 更稳的判据其实是 `UnlockListenerService.instance != null`。
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
}
