package com.jywr.pcbuapk.worker

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.jywr.pcbuapk.receiver.KeepAliveReceiver
import com.jywr.pcbuapk.service.UnlockListenerService
import com.jywr.pcbuapk.utils.KeepAliveManager

/**
 * 心跳保活 Worker
 * 定期启动服务，确保服务不被系统杀死
 *
 * 策略：
 * 1. 直接尝试启动服务
 * 2. 使用 AlarmManager 唤醒闹钟补充 WorkManager 的最小 15 分钟延迟
 * 3. WorkManager 每 15 分钟执行一次作为最后防线
 */
class KeepAliveWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "KeepAliveWorker"

        /**
         * 快速保活检查间隔（5分钟）
         */
        private const val FAST_KEEP_ALIVE_INTERVAL = 5 * 60 * 1000L
    }

    override suspend fun doWork(): Result {
        return try {
            Log.d(TAG, "❤️ [WorkManager] 心跳保活检查：尝试重启服务")

            // 1. 尝试启动服务
            startService()

            // 2. 设置 AlarmManager 唤醒闹钟（补充 WorkManager 的延迟）
            setupFastAlarm()

            Log.d(TAG, "✅ [WorkManager] 心跳保活成功")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "❌ [WorkManager] 心跳保活失败", e)
            // 重试：如果失败，延迟后重试
            Result.retry()
        }
    }

    /**
     * 启动解锁监听服务
     */
    private fun startService() {
        val serviceIntent = Intent(applicationContext, UnlockListenerService::class.java)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(applicationContext, serviceIntent)
        } else {
            applicationContext.startService(serviceIntent)
        }

        Log.d(TAG, "🔄 已尝试启动解锁监听服务")
    }

    /**
     * 设置唤醒闹钟（5分钟间隔）
     *
     * WorkManager 的 PeriodicWorkRequest 最小间隔是 15 分钟，
     * 因此用 AlarmManager 补一个更短的；是否精确由 KeepAliveManager 统一判断并降级。
     */
    private fun setupFastAlarm() {
        try {
            val intent = Intent(applicationContext, KeepAliveReceiver::class.java).apply {
                action = KeepAliveReceiver.ACTION_KEEP_ALIVE
            }
            val pendingIntent = PendingIntent.getBroadcast(
                applicationContext,
                KeepAliveReceiver.REQUEST_CODE_KEEP_ALIVE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            KeepAliveManager.scheduleWakeup(
                applicationContext,
                System.currentTimeMillis() + FAST_KEEP_ALIVE_INTERVAL,
                pendingIntent
            )

            Log.d(TAG, "⏰ 保活闹钟已设置，间隔: ${FAST_KEEP_ALIVE_INTERVAL / 1000}秒")
        } catch (e: Exception) {
            Log.e(TAG, "设置保活闹钟失败", e)
        }
    }
}
