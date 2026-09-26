package com.jywr.pcbuapk.receiver

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
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
                    Log.d(TAG, "蓝牙已开启，检查并启动解锁监听服务")
                    startUnlockServiceIfNeeded(context)
                }
                BluetoothAdapter.STATE_OFF -> {
                    Log.d(TAG, "蓝牙已关闭")
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
     */
    private fun startUnlockServiceIfNeeded(context: Context) {
        // 这里可以添加检查服务是否运行的逻辑
        // 为简单起见，直接尝试启动服务（如果已在运行，startService不会重复创建）
        val serviceIntent = Intent(context, UnlockListenerService::class.java)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
        
        Log.d(TAG, "已尝试启动解锁监听服务")
    }
}
