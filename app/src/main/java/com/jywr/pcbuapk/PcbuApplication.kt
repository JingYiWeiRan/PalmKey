package com.jywr.pcbuapk

import android.app.Application
import android.os.Build
import android.util.Log
import com.jywr.pcbuapk.data.dao.PairedDeviceDao
import com.jywr.pcbuapk.service.UnlockListenerService
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 应用类 - Hilt 入口
 */
@HiltAndroidApp
class PcbuApplication : Application() {

    private companion object {
        const val TAG = "PcbuApplication"

        /**
         * 当前版本真正使用的通知渠道。
         *
         * 任何**不在**这个集合里的已注册渠道都会被当作历史遗留清掉。
         * 用「白名单之外一律删除」而不是「删掉某个写死的 ID」，是因为真机验证发现
         * 老版本留下的渠道不止一个：实测 vivo / Android 15 上残留了
         * `pcbu_channel`（本仓库历史版本创建）和 `pcbu_unlock_listener`
         * （更早版本的代码创建，当前源码里已完全不存在）。
         * 写死单个 ID 只能清掉其中一个，而且以后每换一次渠道命名都要再改一次。
         */
        val ACTIVE_CHANNEL_IDS = setOf(
            UnlockListenerService.CHANNEL_ID_SERVICE,
            UnlockListenerService.CHANNEL_ID_UNLOCK
        )
    }

    @Inject
    lateinit var pairedDeviceDao: PairedDeviceDao

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        runUpgradeCleanup()
    }

    /**
     * 升级后的一次性清理。
     *
     * 把「老版本留在设备上的东西」集中在这里处理。之所以必须在运行时做，
     * 是因为它们都存在**已安装应用**的持久状态里，改代码并不能回收。
     */
    private fun runUpgradeCleanup() {
        deleteLegacyNotificationChannels()
        resealPlaintextSecrets()
    }

    /**
     * 删除历史版本遗留的通知渠道。
     *
     * 背景：所有版本都会在 `MainActivity.onCreate` 里创建一个 `pcbu_channel`（高优先级），
     * 但没有任何代码往它发通知，结果在系统「通知设置」里留下一个永远不会有通知的条目。
     * 代码里的创建调用已经删掉，但**通知渠道一旦创建就会一直存在**（除非卸载应用），
     * 所以对已经装过老版本的设备，必须在升级后显式删除，否则用户永远看得到那个空条目。
     *
     * 采用「白名单之外一律删除」，实测在 vivo / Android 15 上清掉了两个残留渠道
     * （`pcbu_channel` 与更早版本的 `pcbu_unlock_listener`）。
     *
     * 时序上这里跑在 [UnlockListenerService] 创建渠道之前，因此不会误删本次要用的渠道；
     * 白名单判断则保证即便渠道已存在也安全。
     */
    private fun deleteLegacyNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        try {
            val manager = getSystemService(android.app.NotificationManager::class.java) ?: return

            manager.notificationChannels
                .map { it.id }
                .filterNot { it in ACTIVE_CHANNEL_IDS }
                .forEach { legacyId ->
                    manager.deleteNotificationChannel(legacyId)
                    Log.i(TAG, "已删除历史遗留的无用通知渠道: $legacyId")
                }
        } catch (e: Exception) {
            Log.w(TAG, "删除历史通知渠道失败", e)
        }
    }

    /**
     * 把历史版本以明文存放的配对密钥补加密。
     *
     * 为什么需要单独做这一步：字段级 TypeConverter 只在**读写时**生效，
     * 而老用户的密钥早就以明文躺在库里了。不同步重写一遍，
     * 那些行的密钥会一直保持明文 —— 而且 `updateLastConnectedTime` 只更新别的列，
     * 永远不会顺带把它们加密。
     *
     * 幂等：`getDeviceIdsWithPlaintextSecret` 只选没有 `v1:` 前缀的行，
     * 已加密的行不会被选中，因此不存在二次加密的风险。
     * 失败也不影响使用：下次启动会重试，密钥在补加密前依然可用（按明文读取）。
     */
    private fun resealPlaintextSecrets() {
        applicationScope.launch {
            try {
                val pendingIds = pairedDeviceDao.getDeviceIdsWithPlaintextSecret()
                if (pendingIds.isEmpty()) return@launch

                Log.i(TAG, "发现 ${pendingIds.size} 台设备的配对密钥仍为明文，开始补加密")

                var sealed = 0
                pendingIds.forEach { deviceId ->
                    val device = pairedDeviceDao.getDeviceById(deviceId)
                    if (device != null) {
                        // 读出来是明文（TypeConverter 解密/透传），写回去时自动加密
                        pairedDeviceDao.insertDevice(device)
                        sealed++
                    }
                }

                Log.i(TAG, "✅ 已补加密 $sealed 台设备的配对密钥")
            } catch (e: Exception) {
                // 例如处于直接启动（未首次解锁）阶段，Keystore 密钥尚不可用
                Log.e(TAG, "补加密配对密钥失败，将在下次启动重试", e)
            }
        }
    }
}
