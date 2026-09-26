package com.jywr.pcbuapk

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationManagerCompat
import androidx.fragment.app.FragmentActivity
import com.jywr.pcbuapk.navigation.AppNavigation
import com.jywr.pcbuapk.service.UnlockListenerService
import com.jywr.pcbuapk.ui.theme.PcbuTheme
import com.jywr.pcbuapk.utils.KeepAliveManager
import com.jywr.pcbuapk.utils.NotificationManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "MainActivity"

/**
 * 待处理的解锁请求。
 *
 * 刻意使用普通类而不是 data class：StateFlow 会按 equals 去重，
 * 同一台电脑连续两次请求会生成内容相同的对象而被当成同一个值丢掉。
 * 用引用相等可以保证每一次请求都是一次独立的通知。
 *
 * [mode] 见 [UnlockListenerService.MODE_PROMPT] / [UnlockListenerService.MODE_WAIT_KEYGUARD] /
 * [UnlockListenerService.MODE_SKIP_BIOMETRIC]。
 */
class UnlockRequest(
    val deviceId: String,
    val deviceName: String,
    val mode: Int = UnlockListenerService.MODE_PROMPT
)

@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    companion object {
        /**
         * 待处理的解锁请求。
         *
         * 用 StateFlow 而不是普通静态变量：Activity 已在前台时点击通知/全屏 Intent
         * 只会回调 onNewIntent，普通变量赋值不会触发 Compose 重组，
         * 生物识别弹窗就不会出现。
         */
        private val _pendingUnlock = MutableStateFlow<UnlockRequest?>(null)
        val pendingUnlock: StateFlow<UnlockRequest?> = _pendingUnlock.asStateFlow()

        fun consumeUnlock() {
            _pendingUnlock.value = null
        }

        /**
         * 本界面当前是否处于前台。
         *
         * 解锁服务在后台「直接拉起界面」后无法立刻知道是否被系统拦截，
         * 只能延迟一小段时间回读这个标记：如果界面没上来，就降级为全屏通知，
         * 保证用户在任何机型上都至少能收到提醒。
         */
        @Volatile
        var isInForeground = false
            private set

        internal fun updateForegroundState(foreground: Boolean) {
            isInForeground = foreground
        }
    }

    // 蓝牙权限请求器 (Android 12+)
    private val requestBluetoothPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.all { it.value }
        if (allGranted) {
            Log.i(TAG, "蓝牙权限已全部授予")
        } else {
            val denied = permissions.filter { !it.value }.keys.joinToString(", ")
            Log.w(TAG, "蓝牙权限被拒绝: $denied")
        }
        // 无论结果如何，都尝试启动服务
        startUnlockListenerService()
    }

    // 通知权限请求器
    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Log.i(TAG, "通知权限已授予")
        } else {
            Log.w(TAG, "通知权限被拒绝，解锁提醒会退化为横幅，但仍会启动服务")
        }
        // 请求蓝牙权限
        requestBluetoothPermissions()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 创建通知渠道
        NotificationManager.createNotificationChannel(this)

        // 来自解锁请求时需要能盖在锁屏之上并点亮屏幕
        applyLockScreenFlags()

        // 检查并请求通知权限
        if (!NotificationManager.hasNotificationPermission(this)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                // Android 12 以下不需要通知权限，直接请求蓝牙权限
                requestBluetoothPermissions()
            }
        } else {
            // 已有通知权限，请求蓝牙权限
            requestBluetoothPermissions()
        }

        // 检查是否来自解锁通知
        handleUnlockIntent(intent)

        setContent {
            PcbuTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppNavigation()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyLockScreenFlags()
        handleUnlockIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        updateForegroundState(true)
    }

    override fun onPause() {
        super.onPause()
        updateForegroundState(false)
    }

    /**
     * 允许本界面在锁屏之上显示并点亮屏幕。
     *
     * 全屏 Intent 会把 MainActivity 直接拉到最前，若缺少这两个标志，
     * 锁屏状态下仍然看不到验证界面，用户也就无法完成指纹验证。
     */
    private fun applyLockScreenFlags() {
        setShowWhenLocked(true)
        setTurnScreenOn(true)
    }

    /**
     * 请求蓝牙权限（Android 12+）
     */
    private fun requestBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val permissions = arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE
            )
            // 检查哪些权限还没有被授予
            val permissionsToRequest = permissions.filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }

            if (permissionsToRequest.isEmpty()) {
                Log.i(TAG, "蓝牙权限已全部授予")
                startUnlockListenerService()
            } else {
                Log.i(TAG, "请求蓝牙权限: ${permissionsToRequest.joinToString(", ")}")
                requestBluetoothPermissionLauncher.launch(permissionsToRequest.toTypedArray())
            }
        } else {
            // Android 11 及以下，蓝牙权限在 Manifest 中声明即可
            startUnlockListenerService()
        }
    }

    /**
     * 启动解锁监听服务
     */
    private fun startUnlockListenerService() {
        Log.i(TAG, "启动解锁监听服务")

        // 检查并请求电池优化白名单
        KeepAliveManager.requestIgnoreBatteryOptimizations(this)

        val serviceIntent = Intent(this, UnlockListenerService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(this, serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    /**
     * 处理解锁意图，写入待处理的解锁请求
     */
    private fun handleUnlockIntent(intent: Intent?) {
        if (intent?.action != UnlockListenerService.ACTION_UNLOCK) return

        val deviceId = intent.getStringExtra(UnlockListenerService.EXTRA_DEVICE_ID)
        val deviceName = intent.getStringExtra(UnlockListenerService.EXTRA_DEVICE_NAME)
        val mode = intent.getIntExtra(
            UnlockListenerService.EXTRA_UNLOCK_MODE,
            UnlockListenerService.MODE_PROMPT
        )

        if (deviceId != null && deviceName != null) {
            Log.i(TAG, "收到解锁请求: $deviceName ($deviceId) mode=$mode")

            // 界面已经起来了，撤掉可能已经发出的解锁通知，避免「指纹弹窗 + 横幅」同时出现
            try {
                NotificationManagerCompat.from(this)
                    .cancel(UnlockListenerService.NOTIFICATION_ID_UNLOCK_REQUEST)
            } catch (e: Exception) {
                Log.w(TAG, "撤销解锁通知失败", e)
            }

            _pendingUnlock.value = UnlockRequest(deviceId, deviceName, mode)
        }
    }
}
