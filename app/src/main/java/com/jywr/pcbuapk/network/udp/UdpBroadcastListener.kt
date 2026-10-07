package com.jywr.pcbuapk.network.udp

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketAddress

/**
 * UDP 广播监听器
 * 监听服务端发送的设备发现广播
 *
 * 服务端（PC）每 2 秒向 255.255.255.255:udpPort 广播：
 * {"deviceId":"xxx","pcbuIP":"192.168.1.100","pcbuPort":8080,"isManual":false}
 * 注意 pcbuPort 是数字、isManual 是布尔，不能用「只匹配字符串」的正则解析。
 *
 * 重要：**同一个端口只能有一个 socket**。
 * `DatagramSocket` 开了 SO_REUSEADDR，重复调用会成功绑定出第二个、第三个 socket，
 * 同一份广播会被投递多次；上层虽然做了去重，但这里必须从源头保证单实例，
 * 否则每次收到广播都会连弹好几次验证框。
 */
class UdpBroadcastListener {

    companion object {
        private const val TAG = "UdpBroadcastListener"
        private const val BUFFER_SIZE = 1024
    }

    /** 接收循环自己的作用域：带 SupervisorJob，便于 stopListening 时整体取消 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var socket: DatagramSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var listenerJob: Job? = null

    @Volatile
    private var isListening = false

    fun isRunning(): Boolean = isListening

    /**
     * 开始监听 UDP 广播
     *
     * 绑定在方法内同步完成，因此返回值能真实反映结果：
     * 之前实现是「先启动协程再置标志」，绑定失败时调用方仍以为监听成功，导致永久不再重试。
     *
     * 加 `@Synchronized` + 先释放旧 socket：即使上层并发调用，也只会有唯一一个监听 socket。
     *
     * @return 是否成功进入监听状态
     */
    /**
     * 开始监听 UDP 广播
     *
     * 绑定在方法内同步完成，因此返回值能真实反映结果：
     * 之前实现是「先启动协程再置标志」，绑定失败时调用方仍以为监听成功，导致永久不再重试。
     *
     * 加 `@Synchronized` + 先释放旧 socket：即使上层并发调用，也只会有唯一一个监听 socket。
     *
     * @param onStoppedUnexpectedly 接收循环**非正常**退出时回调。
     *   这是必须的：socket 还绑在端口上、却已经没人在读，是应用最隐蔽的失效形态 ——
     *   报文会在内核队列里静默堆积，日志上一条都没有，用户只看到「完全没反应」。
     *   回调让上层能复位自己的状态位，从而在下次网络变化/服务重启时重建监听。
     * @return 是否成功进入监听状态
     */
    @Synchronized
    fun startListening(
        context: Context,
        port: Int,
        onDeviceFound: (UdpBroadcastData) -> Unit,
        onStoppedUnexpectedly: () -> Unit = {}
    ): Boolean {
        if (isListening) {
            Log.w(TAG, "已经在监听中")
            return true
        }

        // 防御：清掉任何残留 socket，避免 SO_REUSEADDR 让同一端口绑出多个监听者
        releaseSocket()

        return try {
            val udpSocket = DatagramSocket(null as SocketAddress?).apply {
                reuseAddress = true
                broadcast = true
                soTimeout = 0
                bind(InetSocketAddress(port))
            }
            socket = udpSocket

            // 部分机型收不到广播报文是因为缺少 MulticastLock
            multicastLock = runCatching {
                (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                    .createMulticastLock("pcbu_udp")
                    .apply {
                        setReferenceCounted(false)
                        acquire()
                    }
            }.onFailure { Log.w(TAG, "申请 MulticastLock 失败", it) }.getOrNull()

            isListening = true
            listenerJob = scope.launch {
                receiveLoop(udpSocket, onDeviceFound, onStoppedUnexpectedly)
            }

            Log.i(TAG, "UDP 监听已启动，端口: $port")
            true
        } catch (e: Exception) {
            Log.e(TAG, "UDP 监听启动失败（端口 $port 可能被占用）", e)
            releaseSocket()
            false
        }
    }

    private suspend fun receiveLoop(
        udpSocket: DatagramSocket,
        onDeviceFound: (UdpBroadcastData) -> Unit,
        onStoppedUnexpectedly: () -> Unit
    ) {
        val buffer = ByteArray(BUFFER_SIZE)
        while (isListening) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                udpSocket.receive(packet)

                val data = String(packet.data, 0, packet.length, Charsets.UTF_8)
                val sender = packet.address?.hostAddress
                Log.i(TAG, "收到 UDP 广播: $data（来源=$sender）")

                parseBroadcastData(data)?.let { parsed ->
                    // 载荷是明文 JSON 且无签名，这里至少核对「自称的 pcbuIP」与真实来源
                    // 是否一致，挡掉从别处转发、伪造来源的广播（否则任何主机都能让手机亮屏）
                    if (!BroadcastSourcePolicy.isSourceConsistent(parsed.pcbuIP, sender)) {
                        Log.w(TAG, "广播自称 IP=${parsed.pcbuIP} 但实际来源是 $sender，已丢弃")
                        return@let
                    }
                    onDeviceFound(parsed)
                }
            } catch (e: Exception) {
                if (isListening) {
                    Log.e(TAG, "接收 UDP 数据包失败", e)
                }
            }
        }
        // 走到这里说明监听已停止（正常 stopListening）或 socket 被关闭。
        // 显式打日志：否则一旦循环意外退出，socket 仍然存在，内核里会静默堆积报文，
        // 应用看起来「完全收不到消息」而没有任何线索。
        //
        // 关键补充：光打日志不够，必须**主动释放 socket 并复位状态**。
        // 否则会留下最隐蔽的失效形态 —— 端口还占着（`ss -unl` 能看到 *:8888）、
        // 却已经没有线程在读，报文静默丢弃，而调用方的状态位仍显示「正在监听」，
        // 于是它永远不会重建监听。真机验证时确实撞到过这个状态。
        val unexpected = isListening
        releaseSocket()
        isListening = false
        listenerJob = null

        if (unexpected) {
            Log.w(TAG, "UDP 接收循环非正常退出，已释放 socket 并通知上层重建监听")
            onStoppedUnexpectedly()
        }
    }

    /**
     * 停止监听
     */
    fun stopListening() {
        if (!isListening && socket == null) return

        isListening = false
        listenerJob?.cancel()
        listenerJob = null
        releaseSocket()

        Log.i(TAG, "UDP 监听已停止")
    }

    private fun releaseSocket() {
        try {
            socket?.close()
        } catch (e: Exception) {
            Log.w(TAG, "关闭 UDP socket 失败", e)
        }
        socket = null

        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "释放 MulticastLock 失败", e)
        }
        multicastLock = null
    }

    /**
     * 解析广播数据
     */
    private fun parseBroadcastData(jsonString: String): UdpBroadcastData? {
        return try {
            val json = JSONObject(jsonString)
            val deviceId = json.optString("deviceId", "")
            val pcbuIP = json.optString("pcbuIP", "")
            val pcbuPort = json.optInt("pcbuPort", -1)
            val isManual = json.optBoolean("isManual", false)

            if (deviceId.isEmpty() || pcbuIP.isEmpty() || pcbuPort <= 0) {
                Log.w(TAG, "广播字段不完整，已忽略: $jsonString")
                null
            } else {
                UdpBroadcastData(
                    deviceId = deviceId,
                    pcbuIP = pcbuIP,
                    pcbuPort = pcbuPort,
                    isManual = isManual
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "解析广播数据失败: $jsonString", e)
            null
        }
    }
}

/**
 * UDP 广播数据结构
 */
data class UdpBroadcastData(
    val deviceId: String,      // 设备 ID
    val pcbuIP: String,        // 电脑 IP 地址
    val pcbuPort: Int,         // 电脑解锁服务端口
    val isManual: Boolean      // 是否为手动配置
)
