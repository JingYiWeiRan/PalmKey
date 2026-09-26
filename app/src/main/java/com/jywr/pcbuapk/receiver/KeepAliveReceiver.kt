package com.jywr.pcbuapk.receiver

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.jywr.pcbuapk.service.UnlockListenerService
import com.jywr.pcbuapk.utils.KeepAliveManager

/**
 * 定时心跳保活广播接收器
 * 通过 AlarmManager 定时触发，检查并重启服务
 *
 * 多层防御策略：
 * 1. AlarmManager 唤醒闹钟（5 分钟链式）- 主要保活手段
 * 2. WorkManager 周期性任务（15 分钟）- 备用保活
 * 3. 服务 START_STICKY 自启动 - 服务被杀死时由系统重建
 */
class KeepAliveReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "KeepAliveReceiver"
        const val ACTION_KEEP_ALIVE = "com.jywr.pcbuapk.ACTION_KEEP_ALIVE"

        /**
         * 保活闹钟的 PendingIntent 请求码。
         *
         * 服务、本接收器、KeepAliveWorker 三处必须用**同一个**请求码，
         * 否则会各自持有互不相干的闹钟，同一条保活链被重复排期（多次无谓唤醒）。
         */
        const val REQUEST_CODE_KEEP_ALIVE = 0

        // 快速保活间隔（5分钟）
        private const val FAST_KEEP_ALIVE_INTERVAL = 5 * 60 * 1000L
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_KEEP_ALIVE) {
            Log.d(TAG, "❤️ 收到心跳保活广播")

            // 1. 立即启动服务
            startServiceSafely(context)

            // 2. 设置下一个快速闹钟（形成链式保活）
            setupNextFastAlarm(context)

            Log.d(TAG, "✅ 心跳保活完成")
        }
    }

    /**
     * 安全启动服务
     */
    private fun startServiceSafely(context: Context) {
        try {
            val serviceIntent = Intent(context, UnlockListenerService::class.java)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, serviceIntent)
            } else {
                context.startService(serviceIntent)
            }

            Log.d(TAG, "🔄 已尝试启动解锁监听服务")
        } catch (e: Exception) {
            Log.e(TAG, "❌ 启动服务失败", e)
        }
    }

    /**
     * 设置下一个快速闹钟（5分钟后）
     *
     * 由 KeepAliveManager 统一判断精确闹钟是否可用并降级。
     * 之前直接调用 setExactAndAllowWhileIdle，在 Android 13+ 未授予
     * SCHEDULE_EXACT_ALARM 时会抛 SecurityException，链式心跳就此断掉。
     */
    private fun setupNextFastAlarm(context: Context) {
        try {
            val intent = Intent(context, KeepAliveReceiver::class.java).apply {
                action = ACTION_KEEP_ALIVE
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE_KEEP_ALIVE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val usedExact = KeepAliveManager.scheduleWakeup(
                context,
                System.currentTimeMillis() + FAST_KEEP_ALIVE_INTERVAL,
                pendingIntent
            )

            if (usedExact) {
                Log.d(TAG, "⏰ 下一个快速闹钟已设置（${FAST_KEEP_ALIVE_INTERVAL / 1000}秒后，精确）")
            } else {
                Log.d(TAG, "⏰ 下一个保活闹钟已设置（${FAST_KEEP_ALIVE_INTERVAL / 1000}秒后，已降级）")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ 设置闹钟失败", e)
        }
    }
}
