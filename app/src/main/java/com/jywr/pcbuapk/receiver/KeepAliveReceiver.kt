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
 * 1. AlarmManager 唤醒闹钟（链式，间隔跟随用户在设置里选择的保活模式）- 主要保活手段
 * 2. WorkManager 周期性任务（15 分钟，平台下限）- 备用保活
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
     * 设置下一个唤醒闹钟，形成链式保活。
     *
     * 间隔从 [KeepAliveManager.getKeepAliveInterval] 读取（服务武装时写入的用户选择），
     * 不再写死：否则用户选的「省电模式 30 分钟」在第一跳之后就会被无声降级成 5 分钟。
     *
     * 由 KeepAliveManager 统一判断精确闹钟是否可用并降级。
     * 之前直接调用 setExactAndAllowWhileIdle，在 Android 13+ 未授予
     * SCHEDULE_EXACT_ALARM 时会抛 SecurityException，链式心跳就此断掉。
     */
    private fun setupNextFastAlarm(context: Context) {
        try {
            val interval = KeepAliveManager.getKeepAliveInterval(context)

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
                System.currentTimeMillis() + interval,
                pendingIntent
            )

            // 用 Log.i：项目约定 vivo/OPPO 会丢弃三方应用的 DEBUG 级日志，
            // 而这是保活链路上最关键的一条追踪，降级成 Log.d 就等于在目标机型上看不见。
            if (usedExact) {
                Log.i(TAG, "⏰ 下一个保活闹钟已设置（${interval / 1000}秒后，精确）")
            } else {
                Log.i(TAG, "⏰ 下一个保活闹钟已设置（${interval / 1000}秒后，已降级为非精确）")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ 设置闹钟失败", e)
        }
    }
}
