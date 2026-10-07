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
import com.jywr.pcbuapk.data.entity.PairingMethods
import com.jywr.pcbuapk.data.preferences.UserPreferences
import com.jywr.pcbuapk.network.bluetooth.BluetoothServer
import com.jywr.pcbuapk.network.protocol.UnlockProtocol
import com.jywr.pcbuapk.network.udp.BroadcastSourcePolicy
import com.jywr.pcbuapk.network.udp.UdpBroadcastData
import com.jywr.pcbuapk.network.udp.UdpBroadcastListener
import com.jywr.pcbuapk.receiver.KeepAliveReceiver
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
        const val CHANNEL_ID_SERVICE = "pcbu_service"

        /** 解锁请求渠道：必须高优先级，否则锁屏下无声、不震动、不可见 */
        const val CHANNEL_ID_UNLOCK = "pcbu_unlock_request"

        private const val NOTIFICATION_ID_FOREGROUND = 100

        /**
         * 保活周期任务的 tag 与唯一任务名。
         *
         * 二者必须共用同一个常量：清理（`cancelAllWorkByTag`）、去重入队
         * （`enqueueUniquePeriodicWork`）与打标（`addTag`）三处一旦写岔，
         * 清理就会变成空操作 —— 这正是早先 tag 写成 `"keep_alive"` 时发生的事。
         */
        const val KEEP_ALIVE_WORK_TAG = KeepAliveManager.KEEP_ALIVE_WORK_TAG

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

        /**
         * 锁屏场景下等待用户完成系统解锁的最长时间，超时即放弃本次请求。
         *
         * 直接取自 [UnlockAuthorizationPolicy]：这样「等多久」与「多久之内允许跳过本机指纹」
         * 必然是同一个值，不会各自漂移。
         */
        private val KEYGUARD_WAIT_TIMEOUT_MS = UnlockAuthorizationPolicy.KEYGUARD_WAIT_TIMEOUT_MS

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
         *
         * ⚠️ 该窗口**必须大于** PC 的广播周期（约 2 秒），否则跨广播周期根本不会去重：
         * 每 2 秒就会投递一个新的解锁请求并取消正在进行的指纹框，
         * 上面描述的症状实际上并未被修好。早先取 800ms，小于广播周期，正是这个情况。
         */
        private const val DUPLICATE_REQUEST_WINDOW_MS = 3_000L

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

    /**
     * 已武装时采用的保活模式。
     *
     * 有了它，`startKeepAlive()` 的闸门从"武装过就永久跳过"变成"模式没变才跳过"：
     * 用户把模式从「不保活」切回常规模式后，服务若还是直接 return，应用就再也没有
     * 保活链了（而且不会有任何报错，只是再也不自检）—— 那是很难发现的静默失效。
     */
    @Volatile
    private var armedKeepAliveMode: Int = Int.MIN_VALUE

    /**
     * 已被证明「连不上」的设备 ID（端点可疑）。
     *
     * 只有在这个集合里的设备，才允许用 UDP 广播里的端点改写配对记录。
     * 广播无签名、同网段任何主机都能伪造，若无条件采纳，任何人都能把一个**本来可用**
     * 的配对静默改指到别处（响应发给攻击者、真电脑收不到 = 莫名其妙的拒绝服务）。
     * 证据只来自一次真实的连接失败，见 [UnlockService.unlockViaTcp]。
     *
     * 仅存在内存里：服务重启会丢失，但那时下次解锁会再次连接失败并重新置位，
     * 因此没有持久化的必要（也避免为此加一次数据库迁移）。
     */
    private val endpointSuspectDeviceIds: MutableSet<String> =
        java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())

    /**
     * 标记某设备的已存端点"可疑"（连接失败），允许后续广播改写它。
     * 由 [UnlockService] 在 TCP 连接失败时调用。
     */
    fun markEndpointSuspect(deviceId: String) {
        if (endpointSuspectDeviceIds.add(deviceId)) {
            Log.i(TAG, "已存端点连接失败，标记为可疑以允许广播改写: $deviceId")
        }
    }

    /**
     * 等待系统解锁的待处理请求（用引用相等区分每一次请求）。
     *
     * [requestedAt] 用于判定「用户解锁手机」是否仍可算作**本次**请求的授权：
     * 只在 [UnlockAuthorizationPolicy.KEYGUARD_WAIT_TIMEOUT_MS] 窗口内才允许跳过本机指纹。
     */
    private class KeyguardPending(
        val deviceId: String,
        val deviceName: String,
        val requestedAt: Long
    )

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
            // "不要重启我"由返回值表达（START_NOT_STICKY），而不是靠一个标志位 ——
            // 原先这里写 isNormalStop = true，但那个字段全项目只写不读，从未生效。
            Log.i(TAG, "收到正常停止请求")
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

        startUdpListening()
        startBluetoothListening()
        registerNetworkCallback()
        startKeepAlive()

        // 「不保活」模式下不能返回 START_STICKY：那等于告诉系统"进程被杀后请重建我"，
        // 与「清掉就死透」直接冲突。模式从 SharedPreferences 同步读（DataStore 只能异步读）。
        return if (KeepAlivePolicy.shouldBeSticky(KeepAliveManager.getKeepAliveMode(this))) {
            START_STICKY
        } else {
            Log.i(TAG, "不保活模式：返回 START_NOT_STICKY，被系统杀死后不再重建")
            START_NOT_STICKY
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * 用户把应用从最近任务划掉时触发。
     *
     * 服务本身配了 stopWithTask="false"，所以默认不会被一起停掉；
     * 常规模式下会显式确认监听链路仍在跑（国产 ROM 可能顺手回收它们）。
     *
     * 但「不保活」模式下这里必须**主动 stopSelf**：用户划掉应用就是在表达"别跑了"，
     * 而只靠 stopWithTask="false" 会让服务继续活着 —— 那就违背了这个模式的全部意义。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)

        if (!KeepAlivePolicy.shouldSurviveTaskRemoval(KeepAliveManager.getKeepAliveMode(this))) {
            Log.i(TAG, "不保活模式：用户清掉最近任务，彻底停止服务")
            stopSelf()
            return
        }

        Log.i(TAG, "任务被划掉，服务保持运行并确认监听链路")
        ensureListenersRunning()
    }

    /**
     * 幂等地确认两条监听链路都在运行。
     *
     * 两个 start 方法内部都有 CAS 闸门，因此在正常运行时调用它是**无副作用**的；
     * 而当某条链路已经死掉（闸门被复位）时，它会把链路重建起来。
     *
     * 之所以需要这么一个"可以随时调用"的入口：`UdpBroadcastListener` 的接收循环
     * 一旦非正常退出，socket 会被释放、状态位复位，但**没有任何周期性机制**会去重建它。
     * 这里给几个高频时机提供一个廉价的兜底调用点：
     * - 用户划掉最近任务（[onTaskRemoved]）
     * - 用户解锁手机（[ServiceRestartReceiver] 收到 USER_PRESENT）
     * - 网络恢复（[registerNetworkCallback] 的 onAvailable）
     */
    fun ensureListenersRunning() {
        // 先做一次**状态自检**，这是让重建真正可达的关键：
        // 服务以为自己在监听（isUdpListening = true），但监听器自己报告没有在跑 ——
        // 说明接收循环已经停了。此时若直接调 startUdpListening()，会被 CAS 闸门挡回去，
        // 应用就永久失聪：端口还占着（`ss -unl` 能看到 *:8888）、报文静默丢弃、
        // 日志上一条都没有。真机验证时撞到过这种"完全没反应"的状态。
        if (isUdpListening && !udpListener.isRunning()) {
            Log.w(TAG, "UDP 监听状态不一致（服务标记在监听、监听器已停止），复位后重建")
            isUdpListening = false
            udpStarting.set(false)
        }

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
        serviceScope.cancel()
        Companion.instance = null
        // 这句日志会直接决定排查方向，所以必须按模式如实区分：
        // 不保活模式下闹钟已被取消，如果还写"保活闹钟将继续运行"，
        // 下次排查"为什么没自恢复"时就会被它带偏。
        if (KeepAlivePolicy.shouldArmKeepAlive(KeepAliveManager.getKeepAliveMode(this))) {
            Log.i(TAG, "ℹ️ 服务已销毁，保活闹钟将继续运行")
        } else {
            Log.i(TAG, "ℹ️ 服务已销毁（不保活模式：无闹钟续期，不会再被拉起）")
        }
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
                isUdpListening = udpListener.startListening(
                    context = applicationContext,
                    port = udpPort,
                    onDeviceFound = { broadcastData -> handleUdpBroadcast(broadcastData) },
                    onStoppedUnexpectedly = {
                        // 接收循环死了、socket 也已释放：复位闸门与状态位，
                        // 否则 startUdpListening 会被 CAS 挡住，应用永久失聪。
                        // 不在这里立刻重试（避免异常路径变成忙循环），
                        // 交给网络变化回调、保活闹钟重启服务、以及每次手机解锁时的
                        // ensureListenersRunning() 来重建。
                        isUdpListening = false
                        udpStarting.set(false)
                        Log.w(TAG, "UDP 接收循环意外停止，状态已复位，等待重建")
                    }
                )

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
        // 模式来自同步镜像（DataStore 只能异步读，而这里要同步决定"是否重新武装"）
        val currentMode = KeepAliveManager.getKeepAliveMode(this)
        // 模式没变才跳过；模式变了必须重新走一遍，否则：
        //   切到「不保活」→ 旧闹钟留着（靠 UserPreferences 里那次当场撤销兜底）
        //   切回常规模式  → 保活链永远不再建立（无人兜底，静默失效）
        if (keepAliveArmed && armedKeepAliveMode == currentMode) return
        keepAliveArmed = true
        armedKeepAliveMode = currentMode

        serviceScope.launch {
            val prefs = userPreferences.preferences.first()

            // 间隔由模式统一决定（KeepAlivePolicy 是唯一出处，有单测）
            val keepAliveInterval = KeepAlivePolicy.keepAliveIntervalMs(prefs.keepAliveMode)

            Log.i(TAG, "❤️ 保活模式: ${prefs.keepAliveMode}, 间隔: ${keepAliveInterval / 1000}秒")

            // 「不保活」模式：撤销一切主动续命手段，并且不再排新的。
            // 注意顺序 —— 唤醒锁照常持有（它是"运行期间能收到请求"的前提，
            // 与"是否自动复活"是两件事），因此这里先获取唤醒锁，再处理保活链。
            acquireKeepAwakeLock()

            if (!KeepAlivePolicy.shouldArmKeepAlive(prefs.keepAliveMode)) {
                // 仅"不再排新的"不够：闹钟链是自我续期的，已经排上的那一环到点照样会把
                // 服务拉起来。WorkManager 那条周期任务同理。必须就地取消。
                KeepAliveManager.cancelKeepAliveAlarm(this@UnlockListenerService)
                cancelKeepAliveWork()
                Log.i(
                    TAG,
                    "不保活模式：已取消保活闹钟与 WorkManager，清掉最近任务后不会自动恢复"
                )
                return@launch
            }

            // 把用户选择的间隔持久化下来：KeepAliveReceiver / KeepAliveWorker 续期时
            // 必须用同一个值。否则第一跳之后它们各自用写死的 5 分钟续期，
            // 用户在设置里选的「省电模式 30 分钟」会被无声忽略。
            KeepAliveManager.setKeepAliveInterval(this@UnlockListenerService, keepAliveInterval)

            // 常驻唤醒锁：**不是可选的省电开关**，因为它是「息屏时 CPU 仍然运行」的前提。
            // 在正常行为（AOSP 语义）的机型上，没有它，屏幕一熄 CPU 就进入 suspend，
            // UDP 接收线程不再被调度，报文只会堆在内核 socket 队列里 ——
            // 这正是「息屏后怎么都解不开电脑」的常见原因。
            // 原先按保活模式决定是否持有（省电模式就放弃），等于让一个「省电模式」
            // 静默关掉产品唯一的功能：不再做这种联动，无条件持有。
            //
            // ⚠️ 但它**不能**解决国产 ROM 的冻结：实测（本机 vivo / Android 15）
            // 屏幕一熄系统就把进程放进 freezer（`cgroup.freeze=1`），
            // 而 Android 会**强制禁用已冻结进程持有的唤醒锁**
            // （`dumpsys power` 里显示 `'PcbuApk::ServiceKeepAlive' DISABLED ... mIsFrozen`）。
            // 也就是说在那类 ROM 上，唤醒锁是被冻结的结果、而不是它的对手；
            // 必须让用户在系统里把本应用加入「自启动 / 后台运行」白名单才会解冻。
            // 详见 README「息屏无法解锁」一节。
            // （唤醒锁已在上方获取；这里保留注释说明它为何与保活模式无关。）

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
                // WorkManager 周期任务的下限是 15 分钟
                // （PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS）。低于它会被拒绝，
                // 而这里整段在 try 里、异常只记日志，所以「可靠模式」如果填 10 分钟，
                // 结果是该模式**完全没有** WorkManager 兜底却又毫无提示。
                // 更短的间隔由上面的 AlarmManager 链负责。
                val workInterval = when (prefs.keepAliveMode) {
                    0 -> 30L // 省电模式: 30分钟
                    else -> 15L // 平衡/可靠模式: 15分钟（平台下限，更短由闹钟链承担）
                }

                val workManager = WorkManager.getInstance(applicationContext)

                // 清理历史版本用 enqueue() 每次启动都新增所堆出来的重复周期任务
                // （实测堆积了 30+ 个，应用一启动就同时跑，白耗电）
                //
                // ⚠️ tag 必须与下面 addTag 的值一致，否则这句是空操作：
                // 早先这里写 "keep_alive"、而任务打的是 "pcbu_keep_alive"，
                // 匹配不到任何任务，历史堆积的重复任务从未被清理掉。
                workManager.cancelAllWorkByTag(KEEP_ALIVE_WORK_TAG)

                val keepAliveRequest = PeriodicWorkRequestBuilder<KeepAliveWorker>(
                    workInterval, TimeUnit.MINUTES
                )
                    .addTag(KEEP_ALIVE_WORK_TAG)
                    .build()

                // 必须用唯一任务 + KEEP 策略：否则每次服务启动都会再叠加一个
                workManager.enqueueUniquePeriodicWork(
                    KEEP_ALIVE_WORK_TAG,
                    ExistingPeriodicWorkPolicy.KEEP,
                    keepAliveRequest
                )
                Log.i(TAG, "❤️ WorkManager 心跳保活已启动（${workInterval}分钟间隔）")
            } catch (e: Exception) {
                Log.e(TAG, "启动WorkManager心跳保活失败", e)
            }
        }
    }

    /**
     * 取消保活周期任务（切到「不保活」模式时调用）。
     *
     * 与闹钟同理：只"不再入队"不够，已经排上的那条周期任务还会到点把服务拉起来。
     * 用的是同一个唯一任务名，因此不会误伤别的任务。
     */
    private fun cancelKeepAliveWork() {
        try {
            WorkManager.getInstance(applicationContext).cancelUniqueWork(KEEP_ALIVE_WORK_TAG)
        } catch (e: Exception) {
            Log.w(TAG, "取消保活周期任务失败", e)
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

            // 复用与 TCP 完全相同的握手语义：从 PC 下发的请求里取出 **PC 生成** 的 unlockToken
            // （手机不能自己造 token，上游会做等值校验）
            val token = UnlockProtocol.extractPcUnlockToken(requestJson, device.encryptionKey.value)
            if (token == null) {
                Log.e(TAG, "解析/解密 PC 端解锁请求失败（密钥不匹配或两端时间差超过 2 分钟）")
                deferred.complete(null)
                return
            }

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

                // 只有走电脑端 **TCP 解锁服务**的配对才用得到 ipAddress / tcpPort。
                // 蓝牙配对是靠 MAC 连的，端点对它毫无用处：刷新没有意义，而且会因为
                // "本地 IP 与广播不同"每 2 秒刷一条警告日志（上游广播周期正好是 2 秒），
                // 把日志冲得没法看。所以整段刷新逻辑只对 TCP 服务类配对生效。
                if (PairingMethods.usesTcpServer(device.pairingMethod)) {
                    // 用广播里的端点刷新记录 —— 但**只在有证据表明旧端点失效之后**。
                    // 广播无签名、同网段任何主机都能发，若无条件采纳，任何人都能把一个
                    // 本来可用的配对静默改指到别处（响应发到攻击者、真电脑收不到），
                    // 用户只会看到"电脑好好的却解不开了"。证据来自一次真实的连接失败，
                    // 见 UnlockService.unlockViaTcp 里的 markEndpointSuspect。
                    val suspect = endpointSuspectDeviceIds.contains(device.id)
                    if (BroadcastSourcePolicy.shouldRefreshEndpoint(
                            storedIp = device.ipAddress,
                            storedPort = device.tcpPort,
                            broadcastIp = broadcastData.pcbuIP,
                            broadcastPort = broadcastData.pcbuPort,
                            storedEndpointSuspect = suspect
                        )
                    ) {
                        Log.i(
                            TAG,
                            "端点已变化，刷新为 ${broadcastData.pcbuIP}:${broadcastData.pcbuPort}" +
                                    "（原为 ${device.ipAddress}:${device.tcpPort}，旧端点已失效=$suspect）"
                        )
                        pairedDeviceDao.updateEndpoint(
                            device.id,
                            broadcastData.pcbuIP,
                            broadcastData.pcbuPort
                        )
                        // 已经换成新端点了，解除可疑标记；若新端点也不行，下次连接失败会再次置位
                        endpointSuspectDeviceIds.remove(device.id)
                    } else if (!suspect && !device.ipAddress.isNullOrBlank() &&
                        (device.ipAddress != broadcastData.pcbuIP || device.tcpPort != broadcastData.pcbuPort)
                    ) {
                        // 广播声称的端点与本地记录不同，但没被采纳。
                        // 这里刻意不猜具体原因（可能是旧端点尚未被证明失效，也可能是新端点
                        // 是回环地址被策略拒绝）—— 只如实说明"按策略未改写"，避免日志误导。
                        Log.w(
                            TAG,
                            "广播端点 ${broadcastData.pcbuIP}:${broadcastData.pcbuPort} 与本地记录" +
                                    "(${device.ipAddress}:${device.tcpPort}) 不同，按策略暂不改写"
                        )
                    }
                }

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

        // 授权策略集中在 UnlockAuthorizationPolicy 里（纯函数、有单测），
        // 这里只做投递。信任模型见该类的 KDoc。
        if (UnlockAuthorizationPolicy.decideDeliveryMode(keyguardLocked, deviceSecure)
            == DeliveryMode.WAIT_KEYGUARD
        ) {
            // 锁屏 + 有密码/指纹：只点亮屏幕，让用户走系统解锁。
            // 用户解锁本身就是一次强身份验证，无需再弹一次指纹。
            // 注意这里**不**拉起自己的界面：MainActivity 带着 showWhenLocked，
            // 盖上去会把系统锁屏挡住，用户就没法输 PIN 了，只能干等。
            val pending = KeyguardPending(deviceId, deviceName, System.currentTimeMillis())
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
     * 这一次系统解锁就是「用户本人」的证明，因此不再要求第二次指纹 —— 但**仅限**
     * 本次请求的等待窗口内（[UnlockAuthorizationPolicy.canSkipLocalBiometric]）。
     * 超出窗口说明这次「解锁手机」与本次请求已失去关联（例如状态被延迟的事件唤醒），
     * 此时回退到正常的指纹验证，而不是无条件放行。
     */
    private fun onUserPresent() {
        val pending = pendingKeyguardRequest ?: return

        pendingKeyguardRequest = null
        keyguardTimeoutJob?.cancel()
        unregisterUserPresentReceiver()
        releaseScreenWakeLock()
        NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID_UNLOCK_WAITING)

        val requestAgeMs = System.currentTimeMillis() - pending.requestedAt
        val mode = if (UnlockAuthorizationPolicy.canSkipLocalBiometric(requestAgeMs)) {
            Log.i(TAG, "✅ 用户已完成系统解锁（${requestAgeMs}ms），按策略跳过本机指纹: ${pending.deviceName}")
            MODE_SKIP_BIOMETRIC
        } else {
            Log.w(
                TAG,
                "⚠️ 系统解锁事件距请求已 ${requestAgeMs}ms，超出授权窗口，" +
                        "回退为指纹验证: ${pending.deviceName}"
            )
            MODE_PROMPT
        }

        pushUnlockToUi(pending.deviceId, pending.deviceName, mode)
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
     * @param device 配对的设备信息
     * @return null 表示发送成功；否则是失败原因，供界面层如实提示用户
     * @note 必须回显PC端下发的unlockToken，否则PC端验证会失败
     *
     * 为什么要返回结果：早先本方法把**所有**失败都吞在内部只记日志、返回 Unit，
     * 调用方于是无论实际成败都向用户报「解锁成功」——用户看到成功、
     * 电脑却毫无反应，而且没有任何可供自查的线索。
     */
    suspend fun sendBluetoothResponse(device: PairedDeviceEntity): String? {
        return try {
            // 关键：必须使用PC端发送的unlockToken，否则PC端验证会失败
            var tokenToReturn = pendingUnlockToken
            if (tokenToReturn.isNullOrEmpty()) {
                // 用户可能比读包更快完成验证，这里短暂等待读包结果
                tokenToReturn = withTimeoutOrNull(TOKEN_WAIT_TIMEOUT_MS) { tokenDeferred?.await() }
            }
            val finalToken = tokenToReturn
            if (finalToken.isNullOrEmpty()) {
                Log.e(TAG, "PC端unlockToken为空，无法回发有效响应")
                return "未取得电脑端的解锁令牌，请重试"
            }

            Log.i(TAG, "发送蓝牙解锁响应，设备: ${device.deviceName}")

            // 与 TCP 链路复用同一套响应构建逻辑（PacketUnlockResponse）
            val responseJson = UnlockProtocol.buildUnlockResponse(
                pcUnlockToken = finalToken,
                passwordKey = device.passwordKey?.value,
                encryptionKey = device.encryptionKey.value
            ) ?: return "加密解锁响应失败"

            if (bluetoothServer.sendUnlockResponsePacket(responseJson)) {
                Log.i(TAG, "✅ 蓝牙解锁响应Packet已发送")
                null
            } else {
                Log.e(TAG, "❌ 发送蓝牙响应Packet失败")
                "发送解锁响应失败，请确认与电脑的蓝牙连接仍然有效"
            }
        } catch (e: Exception) {
            Log.e(TAG, "发送蓝牙响应异常", e)
            "蓝牙解锁失败: ${e.message}"
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
