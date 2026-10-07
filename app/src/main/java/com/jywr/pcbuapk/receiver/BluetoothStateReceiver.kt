package com.jywr.pcbuapk.receiver

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.jywr.pcbuapk.service.UnlockListenerService

/**
 * 蓝牙状态广播接收器
 * 当蓝牙开启时自动启动解锁监听服务
 */
class BluetoothStateReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "BluetoothStateReceiver"
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
            val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
            
            when (state) {
                BluetoothAdapter.STATE_ON -> {
                    Log.i(TAG, "蓝牙已开启，检查并启动解锁监听服务")
                    startUnlockServiceIfNeeded(context)
                    // 除了"把服务拉起来"，还要让它**重新评估监听链路**。
                    // 服务通常本来就在运行（蓝牙之前被关闭才会走到这里），此时
                    // startForegroundService 只会再触发一次 onStartCommand；而如果系统
                    // 拒绝后台启动前台服务（Android 12+），连 onStartCommand 都不会来 ——
                    // 那种情况下只有这一行的显式调用能完成重建。
                    // 服务内部的闸门是"成功过一次就永不重试"，所以蓝牙 accept 线程死掉后，
                    // 没有这个调用就再也回不来了。
                    UnlockListenerService.instance?.ensureListenersRunning()
                }
                BluetoothAdapter.STATE_OFF -> {
                    Log.i(TAG, "蓝牙已关闭")
                }
                BluetoothAdapter.STATE_TURNING_ON -> {
                    Log.d(TAG, "蓝牙正在开启...")
                }
                BluetoothAdapter.STATE_TURNING_OFF -> {
                    Log.d(TAG, "蓝牙正在关闭...")
                }
            }
        }
    }
    
    /**
     * 检查服务是否运行，如果未运行则启动
     *
     * ⚠️ 必须捕获异常：Android 12+ 从后台启动前台服务会抛
     * `ForegroundServiceStartNotAllowedException`，而清单注册接收器的 `onReceive`
     * 一旦抛出未捕获异常就是**进程崩溃**。同仓库的 BootReceiver / KeepAliveReceiver
     * 都做了同样的保护，这里漏了。
     */
    private fun startUnlockServiceIfNeeded(context: Context) {
        try {
            val serviceIntent = Intent(context, UnlockListenerService::class.java)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, serviceIntent)
            } else {
                context.startService(serviceIntent)
            }

            Log.i(TAG, "已尝试启动解锁监听服务")
        } catch (e: Exception) {
            // 系统拒绝后台启动前台服务时忽略即可：服务本来就在运行，或稍后会由闹钟链拉起
            Log.w(TAG, "启动解锁监听服务被系统拒绝，忽略", e)
        }
    }
}
