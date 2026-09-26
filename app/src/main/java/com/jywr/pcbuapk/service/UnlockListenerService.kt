package com.jywr.pcbuapk.service

import android.app.ActivityOptions
import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.jywr.pcbuapk.MainActivity
import com.jywr.pcbuapk.R
import com.jywr.pcbuapk.data.dao.PairedDeviceDao
import com.jywr.pcbuapk.data.entity.PairedDeviceEntity
import com.jywr.pcbuapk.data.preferences.UserPreferences
import com.jywr.pcbuapk.network.bluetooth.BluetoothServer
import com.jywr.pcbuapk.network.udp.UdpBroadcastData
import com.jywr.pcbuapk.network.udp.UdpBroadcastListener
import com.jywr.pcbuapk.receiver.KeepAliveReceiver
import com.jywr.pcbuapk.utils.CryptoUtils
import com.jywr.pcbuapk.utils.KeepAliveManager
import com.jywr.pcbuapk.utils.NotificationManager
import com.jywr.pcbuapk.worker.KeepAliveWorker
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

/**
 * 解锁监听服务（前台服务）
 *
 * 持续监听 UDP 广播与蓝牙连接，当电脑请求解锁时按「屏幕状态」分流：
 *
 * 1. **屏幕锁着（安全锁屏）**：不弹我们自己的指纹，而是点亮屏幕、等用户在系统锁屏上
 *    完成解锁（ACTION_USER_PRESENT）。用户解锁即已通过身份验证，直接放行、不再二次指纹。
 * 2. **屏幕已解锁 / 无安全锁屏**：把验证界面拉到最前，由用户完成指纹识别。
 *
 * 之所以要分流：`BiometricPrompt` 在 keyguard 仍在时无法完成认证，会直接报错返回，
 * 而此前实现拿到 null 就结束了、用户在解锁后不会再有第二次机会 —— 表现为「亮屏后无任何反应」。
 *
 * 保活要点（国产 ROM 尤其重要）：
 * - 无论有没有通知权限都必须 startForeground()，否则 Android 8+ 会直接杀掉服务
 * - 解锁提醒必须走独立的高优先级渠道，写 PRIORITY_* 在 Android 8+ 会被渠道级别覆盖
 * - 保活心跳的精确闹钟在 Android 12+ 默认不可用，必须降级而不是抛异常
 * - 周期任务必须用 enqueueUniquePeriodicWork，否则每次服务启动都会叠加一个 Worker
 * - 所有诊断日志用 Log.i：vivo/OPPO 等 ROM 会丢弃三方应用的 DEBUG 级日志
 */
@AndroidEntryPoint
class UnlockListenerService : Service() {

    companion object {
        private const val TAG = "UnlockListenerService"

        /** 常驻服务通知渠道：低优先级、静默、锁屏不可见 */
        private const val CHANNEL_ID_SERVICE = "pcbu_service"

        /** 解锁请求渠道：必须高优先级，否则锁屏下无声、不震动、不可见 */
        private const val CHANNEL_ID_UNLOCK = "pcbu_unlock_request"

        private const val NOTIFICATION_ID_FOREGROUND = 100

        /** 解锁请求通知 ID；MainActivity 接住请求后需要撤销它，所以对外公开 */
        const val NOTIFICATION_ID_UNLOCK_REQUEST = 101

        /** 「请在锁屏上解锁」提示通知 ID（与指纹提醒分开，避免互相覆盖） */
        private const val NOTIFICATION_ID_UNLOCK_WAITING = 102

        /** 发送解锁 PendingIntent 时使用的请求码（与通知的内容 Intent 共用同一份） */
        private const val REQUEST_CODE_UNLOCK = 0

        /** 直接拉起界面后，等待界面真正到达前台的校验时间 */
        private const val LAUNCH_VERIFY_DELAY_MS = 1200L

        /** 降级发通知后的二次回读时间：应用冷启动较慢时避免留下多余横幅 */
        private const val FALLBACK_RECHECK_DELAY_MS = 1500L

        /** 锁屏场景下等待用户完成系统解锁的最长时间，超时即放弃本次请求 */
        private const val KEYGUARD_WAIT_TIMEOUT_MS = 60_000L

        /** 锁屏时仅用于「点亮屏幕」的唤醒锁时长（用户不接管即自动释放） */
        private const val SCREEN_WAKE_TIMEOUT_MS = 10_000L

        /** 点亮屏幕后的回读延迟：用来判断唤醒锁在当前 ROM 上是否真的生效 */
        private const val WAKE_SCREEN_VERIFY_DELAY_MS = 800L

        /**
         * 等待系统解锁时的轮询间隔。
         *
         * 除了 ACTION_USER_PRESENT 广播，还会按这个间隔轮询 isKeyguardLocked 兜底：
         * 实测 vivo/Android 15 上运行时接收器收不到 USER_PRESENT（广播根本没投递到我们），
         * 只靠广播会让用户在解锁后「还是没反应」。轮询期间我们持有唤醒锁，成本可忽略。
         */
        private const val KEYGUARD_POLL_INTERVAL_MS = 1000L

        /**
         * 重复解锁请求的去重窗口。
         *
         * PC 端是周期广播（约 2 秒一次），窗口内同一台设备的重复请求一定来自重复投递，
         * 而不是两次真实意图。不去重时第二个验证框会把第一个顶掉，
         * 用户看到的是「指纹框闪一下就消失 / 完全没有反应」。
         */
        private const val DUPLICATE_REQUEST_WINDOW_MS = 800L

        /** 投递模式：正常弹出指纹识别 */
        const val MODE_PROMPT = 0

        /** 投递模式：设备已锁屏且为安全锁屏 → 亮屏并等待用户用系统方式解锁 */
        const val MODE_WAIT_KEYGUARD = 1

        /** 投递模式：用户刚完成系统解锁 → 直接放行，不再二次指纹 */
        const val MODE_SKIP_BIOMETRIC = 2

        const val ACTION_STOP = "com.jywr.pcbuapk.ACTION_STOP"
        const val ACTION_UNLOCK = "com.jywr.pcbuapk.ACTION_UNLOCK"
        const val EXTRA_DEVICE_ID = "extra_device_id"
        const val EXTRA_DEVICE_NAME = "extra_device_name"
        const val EXTRA_UNLOCK_MODE = "extra_unlock_mode"

        /** 读取蓝牙解锁请求的超时；超时只放弃读取，不影响通知弹出 */
        private const val BLUETOOTH_READ_TIMEOUT_MS = 3000L

        /** 等待 PC 端 unlockToken 的时间（用户可能比读包更快完成验证） */
        private const val TOKEN_WAIT_TIMEOUT_MS = 3000L

        /** 静态引用，用于从 Activity/ViewModel 访问 */
        @Volatile
        var instance: UnlockListenerService? = null
            private set

        /** 标记是否正在正常停止（避免在 onDestroy 中重启） */
        @Volatile
        private var isNormalStop = false
    }

    @Inject
    lateinit var pairedDeviceDao: PairedDeviceDao

    @Inject
    lateinit var userPreferences: UserPreferences

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val udpListener = UdpBroadcastListener()
    private val bluetoothServer = BluetoothServer()

    @Volatile
    private var isUdpListening = false

    @Volatile
    private var isBtListening = false

    /**
     * 「监听链路是否已在启动中」的闸门。
     *
     * 旧实现是「先判断 isXxxListening，再在协程里启动」，判断与置位之间存在时间差，
     * 而 onStartCommand 与网络回调会在同一瞬间并发调用，实测一次启动就绑定了 **3 个**
     * 8888 端口 socket，同一份广播被投递 3 次（甚至叠加出 7 次解锁投递），
     * 于是第二个生物识别弹窗把第一个直接顶掉 —— 用户侧表现就是「没反应」。
     * 用 CAS 一次性占位，从源头保证监听只被启动一次。
     */
    private val udpStarting = AtomicBoolean(false)
    private val btStarting = AtomicBoolean(false)

    /** WakeLock 用于保持 CPU 活跃以接收蓝牙连接 */
    private var wakeLock: PowerManager.WakeLock? = null

    /** 网络恢复时自动重建监听链路（替代已失效的 CONNECTIVITY_CHANGE 静态注册） */
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /** 存储待处理的蓝牙设备信息（用于生物识别后响应） */
    @Volatile
    private var pendingBluetoothDevice: PairedDeviceEntity? = null

    @Volatile
    private var pendingUnlockRequestData: ByteArray? = null

    /** 存储 PC 端发送的 unlockToken（必须原样返回） */
    @Volatile
    private var pendingUnlockToken: String? = null

    /** 读包与用户验证之间的同步：用户先验证完也不会拿到空 token */
    @Volatile
    private var tokenDeferred: CompletableDeferred<String?>? = null

    /** 锁屏场景下正等待用户完成系统解锁的请求 */
    @Volatile
    private var pendingKeyguardRequest: KeyguardPending? = null

    private var userPresentReceiver: BroadcastReceiver? = null
    private var keyguardTimeoutJob: Job? = null

    /**
     * 常驻 CPU 唤醒锁（PARTIAL_WAKE_LOCK）。
     *
     * 这是「息屏时还能收到解锁请求」的关键。实测（vivo / Android 15）：
     * 没有它时，一旦熄屏，进程虽然还是前台服务状态，线程却完全不再执行 ——
     * PC 发来的 UDP 报文只会在内核 socket 队列里越堆越多，应用一条日志都没有，
     * 屏幕也不会亮，正是用户反馈的「后台息屏发起解锁没有任何反应」。
     * 持有唤醒锁后进程不会被冻结，socket 一有数据就能立刻处理。
     */
    private var keepAwakeLock: PowerManager.WakeLock? = null

    /** 仅用于「锁屏时点亮屏幕」的短时屏幕唤醒锁 */
    private var screenWakeLock: PowerManager.WakeLock? = null

    /** 去重用的「上一次投递」记录（同一台电脑在窗口内的重复请求直接忽略） */
    @Volatile
    private var lastDeliveredDeviceId: String? = null

    @Volatile
    private var lastDeliveredAt = 0L

    /** 保活链是否已武装：避免每个 onStartCommand 都重新排一次心跳闹钟 */
    @Volatile
    private var keepAliveArmed = false

    /** 等待系统解锁的待处理请求（用引用相等区分每一次请求） */
    private class KeyguardPending(val deviceId: String, val deviceName: String)

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "服务创建")
        createNotificationChannels()
        Companion.instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 任何入口都先进入前台状态：
        // 若通过 startForegroundService 启动后没有及时 startForeground，服务会被系统杀掉
        startForegroundService()

        if (intent?.action == ACTION_STOP) {
            Log.i(TAG, "收到正常停止请求")
            isNormalStop = true
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_UNLOCK) {
            val deviceId = intent.getStringExtra(EXTRA_DEVICE_ID)
            val deviceName = intent.getStringExtra(EXTRA_DEVICE_NAME)
            val mode = intent.getIntExtra(EXTRA_UNLOCK_MODE, MODE_PROMPT)
            if (deviceId != null && deviceName != null) {
                if (mode == MODE_PROMPT) {
                    // 外部（通知/ADB/测试）投递的请求仍按屏幕状态分流
                    deliverUnlockRequest(deviceId, deviceName)
                } else {
                    // 服务自己已经判断过屏幕状态，直接按指定模式投递
                    pushUnlockToUi(deviceId, deviceName, mode)
                }
            }
            return START_NOT_STICKY
        }

        isNormalStop = false

        startUdpListening()
        startBluetoothListening()
        registerNetworkCallback()
        startKeepAlive()

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * 用户把应用从最近任务划掉时触发。
     *
     * 服务本身配了 stopWithTask="false"，所以不会被一起停掉，
     * 但国产 ROM 可能顺手把监听链路回收掉，这里显式确认它们在跑。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.i(TAG, "任务被划掉，服务保持运行并确认监听链路")
        startUdpListening()
        startBluetoothListening()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "⚠️ 服务销毁")
        stopUdpListening()
        stopBluetoothListening()
        unregisterNetworkCallback()
        unregisterUserPresentReceiver()
        keyguardTimeoutJob?.cancel()
        releaseScreenWakeLock()
        releaseKeepAwakeLock()
        // 注意：不要调用停止保活，让闹钟继续运行以重启服务
        serviceScope.cancel()
        Companion.instance = null
        Log.i(TAG, "ℹ️ 服务已销毁，保活闹钟将继续运行")
    }

    // ------------------------------------------------------------- 通知与前台服务

    /**
     * 进入前台服务状态。
     *
     * 之前实现只在已获得通知权限时才调用 startForeground()，
     * Android 13+ 用户拒绝通知权限后会导致「服务未进入前台」而被系统杀掉。
     */
    private fun startForegroundService() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID_SERVICE)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("掌钥正在运行")
            .setContentText("监听电脑解锁请求...")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setShowWhen(false)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()

        try {
            startForeground(NOTIFICATION_ID_FOREGROUND, notification)
        } catch (e: Exception) {
            Log.e(TAG, "进入前台服务失败", e)
        }
    }

    /**
     * 创建通知渠道。
     * Android 8+ 通知的声音/震动/是否可见完全由渠道级别决定，代码里的 PRIORITY_* 会被忽略，
     * 所以解锁提醒必须拥有自己独立的高优先级渠道。
     */
    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = getSystemService(android.app.NotificationManager::class.java) ?: return

        val serviceChannel = NotificationChannel(
            CHANNEL_ID_SERVICE,
            "后台常驻服务",
            android.app.NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "保持解锁监听服务运行"
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
            setSound(null, null)
            lockscreenVisibility = android.app.Notification.VISIBILITY_SECRET
            setBypassDnd(false)
            setAllowBubbles(false)
        }

        val unlockChannel = NotificationChannel(
            CHANNEL_ID_UNLOCK,
            "解锁请求提醒",
            android.app.NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "电脑请求解锁时提醒您验证身份"
            setShowBadge(true)
            enableLights(true)
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 400, 200, 400)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            setBypassDnd(true)
        }

        manager.createNotificationChannel(serviceChannel)
        manager.createNotificationChannel(unlockChannel)
    }

    // ------------------------------------------------------------- UDP 监听

    private fun startUdpListening() {
        // CAS 占位：并发调用（onStartCommand + 网络回调）只会有一次真正走到绑定逻辑，
        // 否则会绑出多个 socket、同一份广播被投递多次
        if (!udpStarting.compareAndSet(false, true)) return

        serviceScope.launch {
            try {
                val udpPort = userPreferences.preferences.first().udpListenPort
                Log.i(TAG, "启动UDP监听，端口: $udpPort")

                // 绑定在方法内同步完成，返回值即真实结果；失败时释放闸门以便后续重试
                isUdpListening = udpListener.startListening(applicationContext, udpPort) { broadcastData ->
                    handleUdpBroadcast(broadcastData)
                }

                if (!isUdpListening) {
                    Log.w(TAG, "UDP 监听未启动成功，将在网络恢复时重试")
                    udpStarting.set(false)
                }
            } catch (e: Exception) {
                isUdpListening = false
                udpStarting.set(false)
                Log.e(TAG, "启动UDP监听失败", e)
            }
        }
    }

    private fun stopUdpListening() {
        udpStarting.set(false)
        if (!isUdpListening && !udpListener.isRunning()) return
        udpListener.stopListening()
        isUdpListening = false
        Log.i(TAG, "UDP监听已停止")
    }

    // ------------------------------------------------------------- 蓝牙监听

    private fun startBluetoothListening() {
        // 与 UDP 同理：并发调用会拉起多个 accept 循环，重复处理同一次 PC 连接
        if (!btStarting.compareAndSet(false, true)) return

        serviceScope.launch {
            try {
                Log.i(TAG, "启动蓝牙Server监听")

                bluetoothServer.startListening { deviceName, macAddress ->
                    // PC端连接成功，处理唤醒信号
                    handleBluetoothWakeUp(deviceName, macAddress)
                }

                isBtListening = true
            } catch (e: Exception) {
                isBtListening = false
                btStarting.set(false)
                Log.e(TAG, "启动蓝牙Server监听失败", e)
            }
        }
    }

    private fun stopBluetoothListening() {
        btStarting.set(false)
        if (!isBtListening) return
        bluetoothServer.stopListening()
        isBtListening = false
        Log.i(TAG, "蓝牙Server已停止")
    }

    // ------------------------------------------------------------- 网络恢复回调

    /**
     * targetSdk >= 26 后，manifest 里静态注册的 CONNECTIVITY_CHANGE 不再收到广播，
     * 因此改用动态注册的网络回调，在网络切换/恢复时重建监听链路。
     */
    private fun registerNetworkCallback() {
        if (networkCallback != null) return

        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                .build()

            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Log.i(TAG, "🌐 网络可用，检查监听链路")
                    // 两个 start 方法自身都有幂等保护
                    startUdpListening()
                    startBluetoothListening()
                }
            }

            cm.registerNetworkCallback(request, callback)
            connectivityManager = cm
            networkCallback = callback
            Log.i(TAG, "网络回调已注册")
        } catch (e: Exception) {
            Log.e(TAG, "注册网络回调失败", e)
        }
    }

    private fun unregisterNetworkCallback() {
        try {
            networkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) }
        } catch (e: Exception) {
            Log.w(TAG, "注销网络回调失败", e)
        }
        networkCallback = null
        connectivityManager = null
    }

    // ------------------------------------------------------------- 保活

    /**
     * 启动心跳保活机制：AlarmManager（主）+ WorkManager（兜底）
     *
     * 只在本次服务实例里武装一次（[keepAliveArmed]）。
     *
     * 旧实现在**每个 onStartCommand** 里都把闹钟重新安排到「5 秒后」，
     * 而闹钟触发又会拉起服务、服务再排一次 5 秒 —— 形成 5 秒一次的永久唤醒链，
     * 既疯狂耗电，又让服务被反复重启。现在改为按配置间隔安排、且只安排一次；
     * 后续由 [KeepAliveReceiver] 自己续期（与这里共用同一个 PendingIntent，只有一条链）。
     */
    private fun startKeepAlive() {
        if (keepAliveArmed) return
        keepAliveArmed = true

        serviceScope.launch {
            val prefs = userPreferences.preferences.first()

            // 根据保活模式设置不同的间隔
            val keepAliveInterval = when (prefs.keepAliveMode) {
                0 -> 30 * 60 * 1000L // 省电模式: 30分钟
                2 -> 5 * 60 * 1000L  // 可靠模式: 5分钟
                else -> 15 * 60 * 1000L // 平衡模式: 15分钟(默认)
            }

            Log.i(TAG, "❤️ 保活模式: ${prefs.keepAliveMode}, 间隔: ${keepAliveInterval / 1000}秒")

            // 常驻唤醒锁：没有它，熄屏后进程会被系统/国产 ROM 冻结，收不到任何解锁请求
            // （详见 keepAwakeLock 字段说明）。省电模式主动放弃它，
            // 把「息屏时是否要随时可被唤醒」的选择权交给用户。
            if (prefs.keepAliveMode == 0) {
                Log.w(TAG, "省电模式：未持有常驻唤醒锁，息屏后可能无法响应解锁请求")
            } else {
                acquireKeepAwakeLock()
            }

            // 1. AlarmManager（主要保活手段）
            try {
                val intent = Intent(this@UnlockListenerService, KeepAliveReceiver::class.java).apply {
                    action = KeepAliveReceiver.ACTION_KEEP_ALIVE
                }
                val pendingIntent = PendingIntent.getBroadcast(
                    this@UnlockListenerService,
                    KeepAliveReceiver.REQUEST_CODE_KEEP_ALIVE,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                val exact = KeepAliveManager.scheduleWakeup(
                    this@UnlockListenerService,
                    System.currentTimeMillis() + keepAliveInterval,
                    pendingIntent
                )
                if (!exact) {
                    Log.w(TAG, "⚠️ 未使用精确闹钟，保活精度下降，建议引导用户授权精确闹钟")
                }
            } catch (e: Exception) {
                Log.e(TAG, "启动AlarmManager心跳保活失败", e)
            }

            // 2. WorkManager（额外保障）
            try {
                val workInterval = when (prefs.keepAliveMode) {
                    0 -> 30L // 省电模式: 30分钟
                    2 -> 10L // 可靠模式: 10分钟
                    else -> 15L // 平衡模式: 15分钟
                }

                val workManager = WorkManager.getInstance(applicationContext)

                // 清理历史版本用 enqueue() 每次启动都新增所堆出来的重复周期任务
                // （实测堆积了 30+ 个，应用一启动就同时跑，白耗电）
                workManager.cancelAllWorkByTag("keep_alive")

                val keepAliveRequest = PeriodicWorkRequestBuilder<KeepAliveWorker>(
                    workInterval, TimeUnit.MINUTES
                )
                    .addTag("pcbu_keep_alive")
                    .build()

                // 必须用唯一任务 + KEEP 策略：否则每次服务启动都会再叠加一个
                workManager.enqueueUniquePeriodicWork(
                    "pcbu_keep_alive",
                    ExistingPeriodicWorkPolicy.KEEP,
                    keepAliveRequest
                )
                Log.i(TAG, "❤️ WorkManager 心跳保活已启动（${workInterval}分钟间隔）")
            } catch (e: Exception) {
                Log.e(TAG, "启动WorkManager心跳保活失败", e)
            }
        }
    }

    // ------------------------------------------------------------- 蓝牙唤醒处理

    /**
     * 处理蓝牙唤醒信号
     */
    private fun handleBluetoothWakeUp(deviceName: String, macAddress: String) {
        Log.i(TAG, "处理蓝牙唤醒信号: name=$deviceName mac=$macAddress")

        // 获取 WakeLock 确保 CPU 不会休眠
        acquireWakeLock()

        serviceScope.launch {
            try {
                // 优先按 MAC 匹配，匹配不到再退回名称匹配
                val device = pairedDeviceDao.getDeviceByBluetoothAddress(macAddress)
                    ?: pairedDeviceDao.getDeviceByName(deviceName)

                if (device == null) {
                    Log.w(TAG, "未找到匹配设备（MAC=$macAddress, 名称=$deviceName）")
                    return@launch
                }

                Log.i(TAG, "匹配到设备: ${device.deviceName}")
                handleBluetoothDeviceWakeup(device)
            } catch (e: Exception) {
                Log.e(TAG, "处理蓝牙唤醒异常", e)
            } finally {
                // 处理完成后释放 WakeLock
                releaseWakeLock()
            }
        }
    }

    /**
     * 处理蓝牙设备唤醒。
     *
     * 顺序很关键：先弹验证提醒，再读数据包。
     * 之前是「读完包才弹通知」，而 Android 没有给 BluetoothSocket 暴露读超时，
     * PC 连上却不发包时协程会永久阻塞，用户永远看不到任何提示。
     */
    private suspend fun handleBluetoothDeviceWakeup(device: PairedDeviceEntity) {
        Log.i(TAG, "处理蓝牙设备唤醒: ${device.deviceName}")

        pendingBluetoothDevice = device
        val deferred = CompletableDeferred<String?>()
        tokenDeferred = deferred

        // 第一步：立刻把验证提醒推给用户
        deliverUnlockRequest(device.id, device.deviceName)

        // 第二步：读取 PC 端的解锁请求，取出必须原样回传的 unlockToken
        try {
            val packetResult = withTimeoutOrNull(BLUETOOTH_READ_TIMEOUT_MS) { bluetoothServer.readPacket() }
            if (packetResult == null) {
                Log.w(TAG, "读取解锁请求超时或失败，主动断开以释放被阻塞的读线程")
                bluetoothServer.closeConnection()
                deferred.complete(null)
                return
            }

            val (packetId, requestData) = packetResult
            Log.i(TAG, "收到解锁请求Packet (ID=0x${packetId.toString(16).uppercase()}, ${requestData.size} 字节)")
            pendingUnlockRequestData = requestData

            val requestJson = String(requestData, Charsets.UTF_8)
            Log.i(TAG, "解锁请求内容: $requestJson")

            val encDataHex = JSONObject(requestJson).optString("encData", "")
            if (encDataHex.isEmpty()) {
                Log.w(TAG, "解锁请求中缺少 encData")
                deferred.complete(null)
                return
            }

            val encDataBytes = CryptoUtils.hexToBytes(encDataHex)
            val decryptedData = CryptoUtils.decryptAES(encDataBytes, device.encryptionKey)
            if (decryptedData == null) {
                Log.e(TAG, "解密 PC 端请求数据失败（密钥不匹配或两端时间差异超过 2 分钟）")
                deferred.complete(null)
                return
            }

            val token = JSONObject(String(decryptedData, Charsets.UTF_8)).optString("unlockToken", "")
            pendingUnlockToken = token
            deferred.complete(token)
            Log.i(TAG, "已取得 PC 端 unlockToken")
        } catch (e: Exception) {
            Log.w(TAG, "读取解锁请求Packet失败", e)
            deferred.complete(null)
        }
    }

    // ------------------------------------------------------------- UDP 广播处理

    /**
     * 处理UDP广播
     */
    private fun handleUdpBroadcast(broadcastData: UdpBroadcastData) {
        Log.i(TAG, "收到UDP广播: deviceId=${broadcastData.deviceId}, ip=${broadcastData.pcbuIP}, port=${broadcastData.pcbuPort}")

        serviceScope.launch {
            try {
                // 查找数据库中是否存在该设备
                val device = pairedDeviceDao.getDeviceById(broadcastData.deviceId)

                if (device == null) {
                    Log.i(TAG, "数据库中未找到设备: ${broadcastData.deviceId}")
                    return@launch
                }

                Log.i(TAG, "找到匹配设备: ${device.deviceName}")

                // 按屏幕状态分流投递
                deliverUnlockRequest(device.id, device.deviceName)
            } catch (e: Exception) {
                Log.e(TAG, "处理UDP广播异常", e)
            }
        }
    }

    // ------------------------------------------------------------- 通知与解锁触发

    /**
     * 显示解锁请求通知（兜底通道）。
     *
     * 只有「直接拉起界面」没成功时才会走到这里：
     * - 挂上全屏 Intent 后，锁屏/熄屏状态下系统会直接把验证界面拉到最前，仍然免点击
     * - 亮屏时全屏 Intent 会退化成一条横幅，此时只能由用户点一下
     *   （要彻底免点击，必须让用户开启悬浮窗权限，见 [KeepAliveManager.openOverlaySettings]）
     */
    private fun showUnlockRequestNotification(deviceId: String, deviceName: String, mode: Int) {
        if (!NotificationManager.hasNotificationPermission(this)) {
            Log.w(TAG, "没有通知权限，无法弹出解锁提醒")
            return
        }

        val unlockIntent = buildUnlockIntent(deviceId, deviceName, mode)
        val unlockPendingIntent = PendingIntent.getActivity(
            this,
            REQUEST_CODE_UNLOCK,
            unlockIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val canFullScreen = KeepAliveManager.canUseFullScreenIntent(this)

        val builder = NotificationCompat.Builder(this, CHANNEL_ID_UNLOCK)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("需要验证身份")
            .setContentText("点击指纹解锁 $deviceName")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            // CATEGORY_ALARM 让系统按闹钟/来电级别处理，是全屏 Intent 生效的前提之一
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(unlockPendingIntent)
            .setAutoCancel(true)

        if (canFullScreen) {
            builder.setFullScreenIntent(unlockPendingIntent, true)
        } else {
            Log.w(TAG, "⚠️ 未获得全屏通知权限，退化为高优先级横幅提醒")
        }

        try {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID_UNLOCK_REQUEST, builder.build())
            Log.i(TAG, "已显示解锁请求通知: $deviceName（全屏=$canFullScreen mode=$mode）")
        } catch (e: SecurityException) {
            Log.e(TAG, "发送解锁通知失败", e)
        }
    }

    private fun buildUnlockIntent(deviceId: String, deviceName: String, mode: Int): Intent {
        return Intent(this, MainActivity::class.java).apply {
            action = ACTION_UNLOCK
            putExtra(EXTRA_DEVICE_ID, deviceId)
            putExtra(EXTRA_DEVICE_NAME, deviceName)
            putExtra(EXTRA_UNLOCK_MODE, mode)
            // SINGLE_TOP 保证已在前台时走 onNewIntent，而不是重建 Activity
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
    }

    /**
     * 投递解锁请求（UDP / 蓝牙 / 服务 Intent 三个入口统一走这里）。
     *
     * 先按屏幕状态分流：
     * - 已锁屏且是**安全锁屏** → 点亮屏幕、等用户在系统锁屏上自行解锁（见 [MODE_WAIT_KEYGUARD]）
     * - 其余情况 → 直接把界面拉到最前，弹指纹
     *
     * 为什么要分流：`BiometricPrompt` 在 keyguard 还挂着时无法完成认证，
     * 会立刻回调 onAuthenticationError 返回 null；旧实现拿到 null 就结束了，
     * 用户解锁进入桌面后不会再有第二次机会 —— 就是「亮屏后无任何反应」的根因。
     *
     * 另外还要做**去重**：同一台电脑在极短时间内的重复请求必须只处理一次。
     */
    private fun deliverUnlockRequest(deviceId: String, deviceName: String) {
        val now = System.currentTimeMillis()
        if (deviceId == lastDeliveredDeviceId && now - lastDeliveredAt < DUPLICATE_REQUEST_WINDOW_MS) {
            Log.i(TAG, "⏭ 忽略 ${DUPLICATE_REQUEST_WINDOW_MS}ms 内的重复解锁请求: $deviceName")
            return
        }
        lastDeliveredDeviceId = deviceId
        lastDeliveredAt = now

        val keyguardManager = getSystemService(KeyguardManager::class.java)
        val keyguardLocked = keyguardManager?.isKeyguardLocked == true
        val deviceSecure = keyguardManager?.isDeviceSecure == true

        Log.i(
            TAG,
            "投递解锁请求: $deviceName ($deviceId) 锁屏中=$keyguardLocked 安全锁屏=$deviceSecure"
        )

        if (keyguardLocked && deviceSecure) {
            // 锁屏 + 有密码/指纹：只点亮屏幕，让用户走系统解锁。
            // 用户解锁本身就是一次强身份验证，无需再弹一次指纹。
            // 注意这里**不**拉起自己的界面：MainActivity 带着 showWhenLocked，
            // 盖上去会把系统锁屏挡住，用户就没法输 PIN 了，只能干等。
            val pending = KeyguardPending(deviceId, deviceName)
            pendingKeyguardRequest = pending
            registerUserPresentReceiver()
            scheduleKeyguardTimeout()
            showWaitingForUnlockNotification(deviceId, deviceName)
            wakeScreenForUserUnlock(pending)
            return
        }

        // 未锁屏，或锁屏但没有凭据（滑动解锁）：仍需我们自己弹出指纹
        pushUnlockToUi(deviceId, deviceName, MODE_PROMPT)
    }

    // ------------------------------------------------------------- WakeLock

    /**
     * 点亮屏幕，把「系统锁屏」交给用户。
     *
     * 为什么不用拉起界面的方式点亮屏幕：MainActivity 是 `setShowWhenLocked(true)` 的，
     * 它会盖在锁屏之上，用户看不到也点不到 PIN 键盘，于是「亮屏了但进不去手机」。
     * 用屏幕唤醒锁只负责点亮，锁屏仍然由系统呈现，用户就能正常解锁。
     *
     * 少数 ROM 上屏幕唤醒锁可能不生效，因此延迟回读一次 `isInteractive`，
     * 只有确实没亮屏时才降级为「直接拉起界面」（会盖住锁屏，但至少可见）。
     */
    private fun wakeScreenForUserUnlock(pending: KeyguardPending) {
        try {
            if (screenWakeLock == null) {
                val powerManager = getSystemService(POWER_SERVICE) as PowerManager
                screenWakeLock = powerManager.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                            PowerManager.ACQUIRE_CAUSES_WAKEUP or
                            PowerManager.ON_AFTER_RELEASE,
                    "PcbuApk::WakeScreenForUnlock"
                ).apply {
                    setReferenceCounted(false)
                }
            }

            if (screenWakeLock?.isHeld != true) {
                screenWakeLock?.acquire(SCREEN_WAKE_TIMEOUT_MS)
            }
            Log.i(TAG, "🔆 锁屏中：已点亮屏幕，等待用户在系统锁屏上完成解锁")
        } catch (e: Exception) {
            Log.e(TAG, "点亮屏幕失败", e)
        }

        serviceScope.launch {
            delay(WAKE_SCREEN_VERIFY_DELAY_MS)
            // 用户可能已经解锁（pending 被清掉），那就不要再做任何兜底动作
            if (pendingKeyguardRequest !== pending) return@launch

            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            if (powerManager.isInteractive) return@launch

            Log.w(TAG, "⚠️ 屏幕未被点亮，降级为直接拉起验证界面（会盖在锁屏之上）")
            pushUnlockToUi(pending.deviceId, pending.deviceName, MODE_WAIT_KEYGUARD)
        }
    }

    private fun releaseScreenWakeLock() {
        try {
            if (screenWakeLock?.isHeld == true) {
                screenWakeLock?.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "释放屏幕唤醒锁失败", e)
        }
    }

    /**
     * 获取常驻 CPU 唤醒锁，保证熄屏后进程仍在运行、socket 有数据就能立刻被处理。
     */
    private fun acquireKeepAwakeLock() {
        try {
            if (keepAwakeLock == null) {
                val powerManager = getSystemService(POWER_SERVICE) as PowerManager
                keepAwakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "PcbuApk::ServiceKeepAlive"
                ).apply {
                    setReferenceCounted(false)
                }
            }

            if (keepAwakeLock?.isHeld != true) {
                // 不设超时：与服务工作周期一致，服务销毁时释放
                keepAwakeLock?.acquire()
                Log.i(TAG, "🔋 已持有常驻唤醒锁（保证息屏时仍能响应解锁请求）")
            }
        } catch (e: Exception) {
            Log.e(TAG, "获取常驻唤醒锁失败", e)
        }
    }

    private fun releaseKeepAwakeLock() {
        try {
            if (keepAwakeLock?.isHeld == true) {
                keepAwakeLock?.release()
                Log.i(TAG, "🔋 常驻唤醒锁已释放")
            }
        } catch (e: Exception) {
            Log.w(TAG, "释放常驻唤醒锁失败", e)
        }
    }

    /**
     * 锁屏场景下提示用户去解锁。
     *
     * 刻意**不带**全屏 Intent：全屏 Intent 会把 MainActivity 推上来盖住锁屏，
     * 反而让用户无法完成系统解锁。这里只要一条锁屏上可见的高优先级通知即可。
     */
    private fun showWaitingForUnlockNotification(deviceId: String, deviceName: String) {
        if (!NotificationManager.hasNotificationPermission(this)) {
            Log.w(TAG, "没有通知权限，无法提示用户解锁")
            return
        }

        val contentIntent = PendingIntent.getActivity(
            this,
            REQUEST_CODE_UNLOCK,
            buildUnlockIntent(deviceId, deviceName, MODE_WAIT_KEYGUARD),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID_UNLOCK)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("电脑请求解锁")
            .setContentText("请解锁手机以解锁 $deviceName")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()

        try {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID_UNLOCK_WAITING, notification)
            Log.i(TAG, "已提示用户解锁手机（不带全屏 Intent，避免遮挡系统锁屏）")
        } catch (e: SecurityException) {
            Log.e(TAG, "发送解锁提示通知失败", e)
        }
    }

    /**
     * 把请求交给界面层。
     *
     * 分层策略：
     * 1. 应用已在前台 → 直接 startActivity 走 onNewIntent，无需任何权限
     * 2. 应用在后台 → 尝试直接 startActivity（依赖悬浮窗权限带来的后台启动豁免）
     * 3. 延迟回读校验：界面没上来说明被系统/ROM 静默拦截，降级为高优先级全屏通知
     *
     * 为什么必须校验而不是先发通知：亮屏且停在桌面时，全屏 Intent 只会退化成一条横幅，
     * 用户仍然得手点一次。只有「直接拉起」才能真正做到免点击，所以先试它、失败再兜底。
     */
    private fun pushUnlockToUi(deviceId: String, deviceName: String, mode: Int) {
        // 真正开始投递验证界面了，先把「请解锁手机」的提示撤掉，避免两个提示同时存在
        NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID_UNLOCK_WAITING)

        val alreadyForeground = MainActivity.isInForeground
        val started = startUnlockActivity(
            deviceId,
            deviceName,
            if (alreadyForeground) "应用已在前台" else "后台直接拉起",
            mode
        )

        if (!started) {
            // startActivity 直接就抛异常了，只能靠通知
            showUnlockRequestNotification(deviceId, deviceName, mode)
            return
        }

        // 已在前台时界面必然能收到，不需要再发通知
        if (alreadyForeground) return

        serviceScope.launch {
            delay(LAUNCH_VERIFY_DELAY_MS)
            if (MainActivity.isInForeground) {
                Log.i(TAG, "✅ 已直接弹出验证界面，无需用户点击")
                return@launch
            }

            Log.w(TAG, "⚠️ 直接拉起未生效（被系统/国产 ROM 拦截），降级为全屏通知")
            showUnlockRequestNotification(deviceId, deviceName, mode)

            // 应用冷启动可能比校验延迟更慢：界面稍后才上来时，把这条多余的通知撤掉，
            // 免得用户同时看到指纹弹窗和一条无用的横幅
            delay(FALLBACK_RECHECK_DELAY_MS)
            if (MainActivity.isInForeground) {
                NotificationManagerCompat.from(this@UnlockListenerService)
                    .cancel(NOTIFICATION_ID_UNLOCK_REQUEST)
                Log.i(TAG, "界面已上来，撤销多余的兜底通知")
            }
        }
    }

    /**
     * 尝试直接把验证界面拉到最前。
     *
     * Android 10 起后台启动 Activity 受限，官方列出的豁免条件之一是
     * 「应用已获得用户授予的 SYSTEM_ALERT_WINDOW（悬浮窗）权限」；
     * 另外国产 ROM 还有单独的「后台弹出界面」开关。
     *
     * Android 14（API 34）起，发送 PendingIntent 的应用必须显式选择启用，
     * 否则本应用自己的后台启动特权不会被授予，所以这里带上 ActivityOptions。
     *
     * @return true 表示调用已发出（不代表系统一定放行，需靠回读 [MainActivity.isInForeground] 校验）
     */
    @Suppress("DEPRECATION")
    private fun startUnlockActivity(deviceId: String, deviceName: String, from: String, mode: Int): Boolean {
        val unlockIntent = buildUnlockIntent(deviceId, deviceName, mode)
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pendingIntent = PendingIntent.getActivity(
                    this,
                    REQUEST_CODE_UNLOCK,
                    unlockIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val options = ActivityOptions.makeBasic().apply {
                    if (Build.VERSION.SDK_INT >= 36) {
                        // ALLOW_IF_VISIBLE 在「应用当前不可见」时会被直接拒绝，这里必须用 ALWAYS
                        setPendingIntentBackgroundActivityStartMode(
                            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS
                        )
                    } else {
                        setPendingIntentBackgroundActivityStartMode(
                            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                        )
                    }
                }
                pendingIntent.send(this, 0, null, null, null, null, options.toBundle())
            } else {
                startActivity(unlockIntent)
            }

            Log.i(
                TAG,
                "已发起拉起验证界面（$from mode=$mode），悬浮窗权限=${KeepAliveManager.canDrawOverlays(this)}"
            )
            true
        } catch (e: Exception) {
            Log.e(TAG, "拉起验证界面失败（$from）", e)
            false
        }
    }

    // ------------------------------------------------------------- 锁屏等待系统解锁

    /**
     * 注册「用户已完成系统解锁」监听。
     *
     * 屏幕关闭时界面层收不到任何事件，只有系统广播能唤醒我们，
     * 所以必须动态注册 ACTION_USER_PRESENT（不要依赖清单静态注册）。
     */
    private fun registerUserPresentReceiver() {
        if (userPresentReceiver != null) return

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_USER_PRESENT) {
                    onUserPresent()
                }
            }
        }

        val filter = IntentFilter(Intent.ACTION_USER_PRESENT)

        try {
            // 只监听系统广播（USER_PRESENT），因此**不能**用 RECEIVER_NOT_EXPORTED：
            // 实测（vivo / Android 15）接收器确实注册进了 AMS，但广播被过滤掉、永远收不到，
            // 表现为用户解锁后应用毫无反应。USER_PRESENT 是受保护广播，只有系统能发送，
            // 用 EXPORTED 语义没有额外安全风险。
            // （真正的主路径其实是 [scheduleKeyguardTimeout] 里的轮询兜底，这里只是快速路径。）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(receiver, filter)
            }
            userPresentReceiver = receiver
            Log.i(TAG, "已开始等待用户完成系统解锁")
        } catch (e: Exception) {
            Log.e(TAG, "注册 ACTION_USER_PRESENT 监听失败", e)
        }
    }

    private fun unregisterUserPresentReceiver() {
        try {
            userPresentReceiver?.let { unregisterReceiver(it) }
        } catch (e: Exception) {
            Log.w(TAG, "注销 ACTION_USER_PRESENT 监听失败", e)
        }
        userPresentReceiver = null
    }

    /**
     * 用户在系统锁屏上完成了身份验证。
     *
     * 这一次系统解锁就是「用户本人」的证明，直接放行，不再要求第二次指纹。
     */
    private fun onUserPresent() {
        val pending = pendingKeyguardRequest ?: return

        pendingKeyguardRequest = null
        keyguardTimeoutJob?.cancel()
        unregisterUserPresentReceiver()
        releaseScreenWakeLock()
        NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID_UNLOCK_WAITING)

        Log.i(TAG, "✅ 用户已完成系统解锁，直接放行: ${pending.deviceName}")
        pushUnlockToUi(pending.deviceId, pending.deviceName, MODE_SKIP_BIOMETRIC)
    }

    /**
     * 等待用户完成系统解锁：ACTION_USER_PRESENT 广播 + isKeyguardLocked 轮询双保险。
     *
     * 为什么必须加轮询：实测 vivo / Android 15 上运行时接收器收不到 USER_PRESENT
     * （接收器在 AMS 里注册成功，广播却不投递），只靠广播会让用户「解锁完了还是没反应」。
     * 这个阶段我们持有唤醒锁，1 秒一次的判断几乎不耗电，可靠性远比这点开销重要。
     * 超时则放弃本次请求，避免残留状态把后续请求搞乱。
     */
    private fun scheduleKeyguardTimeout() {
        keyguardTimeoutJob?.cancel()
        val waiting = pendingKeyguardRequest
        keyguardTimeoutJob = serviceScope.launch {
            val deadline = System.currentTimeMillis() + KEYGUARD_WAIT_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                delay(KEYGUARD_POLL_INTERVAL_MS)
                // 广播先到（pending 已清）或来了新请求（pending 被替换）→ 本次等待结束
                if (pendingKeyguardRequest !== waiting) return@launch

                val keyguardManager = getSystemService(KeyguardManager::class.java)
                if (keyguardManager?.isKeyguardLocked != true) {
                    Log.i(TAG, "✅ 轮询检测到系统锁屏已解除")
                    onUserPresent()
                    return@launch
                }
            }

            val pending = pendingKeyguardRequest ?: return@launch
            Log.w(TAG, "⏱ 等待系统解锁超时（${KEYGUARD_WAIT_TIMEOUT_MS / 1000}秒），放弃请求: ${pending.deviceName}")
            pendingKeyguardRequest = null
            unregisterUserPresentReceiver()
            releaseScreenWakeLock()
            NotificationManagerCompat.from(this@UnlockListenerService)
                .cancel(NOTIFICATION_ID_UNLOCK_WAITING)
        }
    }

    /**
     * 发送蓝牙解锁响应给PC端
     *
     * @param unlockToken 生物识别生成的令牌（不使用）
     * @param device 配对的设备信息
     * @note 必须返回PC端发送的unlockToken，否则PC端验证会失败
     */
    suspend fun sendBluetoothResponse(unlockToken: String, device: PairedDeviceEntity) {
        try {
            // 关键：必须使用PC端发送的unlockToken，否则PC端验证会失败
            var tokenToReturn = pendingUnlockToken
            if (tokenToReturn.isNullOrEmpty()) {
                // 用户可能比读包更快完成验证，这里短暂等待读包结果
                tokenToReturn = withTimeoutOrNull(TOKEN_WAIT_TIMEOUT_MS) { tokenDeferred?.await() }
            }
            val finalToken = tokenToReturn ?: ""
            if (finalToken.isEmpty()) {
                Log.e(TAG, "警告：PC端unlockToken为空，PC端验证将失败")
            }

            Log.i(TAG, "发送蓝牙解锁响应，设备: ${device.deviceName}")

            // 构建响应数据（PacketUnlockResponseData）
            val responseData = JSONObject().apply {
                put("unlockToken", finalToken)
                put("passwordKey", device.passwordKey ?: "")
            }

            // 加密响应数据
            val encryptedData = CryptoUtils.encryptAES(
                responseData.toString().toByteArray(Charsets.UTF_8),
                device.encryptionKey
            ) ?: throw Exception("加密响应数据失败")

            // 构建完整的响应JSON（PacketUnlockResponse）
            val responseJson = JSONObject().apply {
                put("error", "") // 空表示成功
                put("encData", CryptoUtils.bytesToHex(encryptedData))
            }

            val success = bluetoothServer.sendUnlockResponsePacket(responseJson.toString())
            if (success) {
                Log.i(TAG, "✅ 蓝牙解锁响应Packet已发送")
            } else {
                Log.e(TAG, "❌ 发送蓝牙响应Packet失败")
            }
        } catch (e: Exception) {
            Log.e(TAG, "发送蓝牙响应异常", e)
        } finally {
            // 清理待处理状态
            pendingBluetoothDevice = null
            pendingUnlockRequestData = null
            pendingUnlockToken = null
            tokenDeferred = null
        }
    }

    // ------------------------------------------------------------- WakeLock

    /**
     * 获取 WakeLock 保持 CPU 活跃
     */
    private fun acquireWakeLock() {
        try {
            if (wakeLock == null) {
                val powerManager = getSystemService(POWER_SERVICE) as PowerManager
                wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "PcbuApk::UnlockListenerWakeLock"
                ).apply {
                    setReferenceCounted(false)
                }
            }

            if (wakeLock?.isHeld != true) {
                wakeLock?.acquire(60 * 1000L) // 最多持有60秒
                Log.i(TAG, "🔒 WakeLock 已获取")
            }
        } catch (e: Exception) {
            Log.e(TAG, "获取 WakeLock 失败", e)
        }
    }

    /**
     * 释放 WakeLock
     */
    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                Log.i(TAG, "🔓 WakeLock 已释放")
            }
        } catch (e: Exception) {
            Log.e(TAG, "释放 WakeLock 失败", e)
        }
    }
}
