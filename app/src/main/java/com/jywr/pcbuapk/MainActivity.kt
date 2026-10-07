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
import com.jywr.pcbuapk.utils.NotificationManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.receiveAsFlow

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
    val mode: Int = UnlockListenerService.MODE_PROMPT,
    /**
     * 本次请求的唯一标识。
     *
     * 用途是**幂等**：投递到界面后，「弹指纹 → 回发解锁响应」这段逻辑由
     * `viewModelScope` 执行，不受承载它的 `LaunchedEffect` 取消影响。
     * 而实测（vivo / Android 15）从设置页切回主页时，该 effect 会**以同一个 key
     * 重新启动一次**，于是同一个请求被派发两次、给电脑回了两条响应。
     * 用这个 id 把「同一请求只处理一次」的保证放到不随组合重建而丢失的地方。
     */
    val id: String = java.util.UUID.randomUUID().toString()
)

@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    companion object {
        /** 待处理解锁请求的缓冲深度，够覆盖一次连续重试即可 */
        private const val PENDING_UNLOCK_CAPACITY = 8

        /**
         * 待处理的解锁请求队列。
         *
         * 为什么用 Channel 而不是原来的 StateFlow：
         * 验证界面挂在 `main` 目的地里，只有它被组合时才有人在收集。原来用
         * `MutableStateFlow<UnlockRequest?>` 有两个后果：
         * 1. 用户停在「设置 / 配对」页时到达的请求**没有任何人处理**（会被静默丢弃）；
         * 2. 连续两次请求会互相**覆盖**，只剩最后一条。
         * Channel 会缓存并按序**逐个投递、恰好消费一次**，晚到的收集者也能拿到排队中的请求。
         */
        private val _pendingUnlock = Channel<UnlockRequest>(PENDING_UNLOCK_CAPACITY)
        val pendingUnlock: Flow<UnlockRequest> = _pendingUnlock.receiveAsFlow()

        /**
         * 「请把界面切回主页」信号。
         *
         * 解锁请求可能在任何页面到达，而验证界面在主页。不主动切回去的话，
         * 请求只能一直排队等用户自己返回 —— 而电脑端早就超时了。
         */
        private val _navigateHome = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val navigateHome: SharedFlow<Unit> = _navigateHome.asSharedFlow()

        /** 投递一次解锁请求，并把界面带回主页 */
        private fun requestUnlock(request: UnlockRequest) {
            val queued = _pendingUnlock.trySend(request).isSuccess
            if (!queued) {
                Log.w(TAG, "解锁请求队列已满，丢弃本次请求: ${request.deviceName}")
            }
            _navigateHome.tryEmit(Unit)
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

        // 通知渠道由 UnlockListenerService 在 onCreate 里创建
        // （pcbu_service / pcbu_unlock_request）。这里原先还会创建第三个
        // 渠道 pcbu_channel，但它没有任何发送方，只会在系统通知设置里留下无用条目，已移除。

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

        // 这里原先会在每次冷启动时主动跳转「忽略电池优化」授权页。
        // 已移除，两个原因：
        // 1. 那个 action 需要 REQUEST_IGNORE_BATTERY_OPTIMIZATIONS 权限，属 Play 政策风险项；
        // 2. 每次启动都把用户甩到系统设置页本身也很打扰。
        // 电池优化状态改由 KeepAliveGuideDialog（首次启动引导）与设置页的「权限与保活」
        // 分区展示，用户点哪一项才跳哪一页。

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

            requestUnlock(UnlockRequest(deviceId, deviceName, mode))
        }
    }
}
