package com.jywr.pcbuapk.utils

import android.app.KeyguardManager
import android.content.Context
import android.util.Log
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 生物识别工具类
 * 支持指纹、面部识别等
 */
object BiometricUtils {

    private const val TAG = "BiometricUtils"

    /**
     * 当前正在显示的认证弹窗。
     *
     * 全局只允许存在一个：系统里第二个 `BiometricPrompt.authenticate()` 会把前一个直接取消掉，
     * 而重复请求（同一份 UDP 广播被多次投递）恰恰会在几十毫秒内连弹多次，
     * 表现就是「指纹框闪一下就消失」。所以每次弹出前先主动撤掉上一个。
     */
    @Volatile
    private var activePrompt: BiometricPrompt? = null

    /**
     * 生成一次性的解锁令牌。
     *
     * 令牌只是「本地已通过人证核验」的标记：蓝牙链路会用 PC 端下发的 unlockToken
     * 原样回传，TCP 链路则校验 PC 端是否把同一个值回显，所以内容本身不重要，
     * 但必须每次不同，避免被当成重放。
     */
    fun newToken(): String =
        "token_${System.currentTimeMillis()}_${(Math.random() * 10000).toInt()}"

    /**
     * 检查设备是否支持生物识别
     */
    fun isBiometricAvailable(context: Context): Boolean {
        val biometricManager = BiometricManager.from(context)
        return when (biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)) {
            BiometricManager.BIOMETRIC_SUCCESS -> true
            else -> false
        }
    }

    /**
     * 撤掉当前可能还挂着的认证弹窗（幂等）。
     */
    private fun cancelActivePrompt() {
        val prompt = activePrompt ?: return
        activePrompt = null
        try {
            prompt.cancelAuthentication()
        } catch (e: Exception) {
            Log.w(TAG, "取消生物识别失败", e)
        }
    }

    /**
     * 显示生物识别对话框
     *
     * 允许的认证方式按设备实际情况选择：
     * - 设备设有 PIN/图案/密码 → BIOMETRIC_STRONG or DEVICE_CREDENTIAL（生物识别失败可退到锁屏凭据）
     * - 设备没有任何凭据（滑动解锁）→ 只允许 BIOMETRIC_STRONG
     *   否则 `canAuthenticate(BIO or DEVICE_CREDENTIAL)` 会返回 NONE_ENROLLED，弹窗永远起不来
     *
     * @param activity FragmentActivity（必须是 FragmentActivity 或其子类）
     * @param title 标题
     * @param subtitle 副标题
     * @return 解锁令牌（成功时），null（失败时）
     */
    suspend fun authenticate(
        activity: FragmentActivity,
        title: String = "生物识别验证",
        subtitle: String = "验证您的身份以解锁电脑"
    ): String? {
        // keyguard 还挂着时 BiometricPrompt 无法完成认证，会直接报错返回。
        // 正常路径下调用方已经处理了锁屏场景，这里只是兜底，避免静默失败。
        val keyguardManager = activity.getSystemService(KeyguardManager::class.java)
        if (keyguardManager?.isKeyguardLocked == true) {
            Log.w(TAG, "设备仍处于锁屏状态，放弃弹出生物识别")
            return null
        }

        val deviceSecure = keyguardManager?.isDeviceSecure == true
        val authenticators = if (deviceSecure) {
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
        } else {
            BiometricManager.Authenticators.BIOMETRIC_STRONG
        }

        return suspendCancellableCoroutine { continuation ->
            val executor = ContextCompat.getMainExecutor(activity)

            // 弹出前先把上一个撤掉，保证系统里同时只有一个认证框
            cancelActivePrompt()

            val biometricPrompt = BiometricPrompt(
                activity,
                executor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        super.onAuthenticationSucceeded(result)
                        Log.i(TAG, "生物识别成功")
                        activePrompt = null
                        if (!continuation.isCompleted) {
                            continuation.resume(newToken())
                        }
                    }

                    override fun onAuthenticationFailed() {
                        super.onAuthenticationFailed()
                        // 单次比对失败（指纹不匹配），用户可以重试，不结束流程
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        super.onAuthenticationError(errorCode, errString)
                        Log.w(TAG, "生物识别出错: code=$errorCode msg=$errString")
                        activePrompt = null
                        if (!continuation.isCompleted) {
                            continuation.resume(null)
                        }
                    }
                }
            )

            val promptInfo = BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setAllowedAuthenticators(authenticators)
                .build()

            // 取消回调必须先注册：这次调用有可能在弹窗期间被取消（用户连续触发两次解锁），
            // 届时必须把系统弹窗一起撤掉，否则会留下一个没人回收的验证框。
            continuation.invokeOnCancellation { cancelActivePrompt() }

            activePrompt = biometricPrompt
            try {
                biometricPrompt.authenticate(promptInfo)
            } catch (e: Exception) {
                Log.e(TAG, "弹出生物识别失败", e)
                activePrompt = null
                if (!continuation.isCompleted) {
                    continuation.resume(null)
                }
            }
        }
    }
}
