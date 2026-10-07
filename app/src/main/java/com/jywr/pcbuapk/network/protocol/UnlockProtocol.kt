package com.jywr.pcbuapk.network.protocol

import android.util.Log
import com.jywr.pcbuapk.utils.CryptoUtils
import org.json.JSONObject

/**
 * 解锁握手协议的载荷编解码。
 *
 * 与上游 `BaseUnlockConnection` + `Packets.h` 严格对应，TCP 与蓝牙两条链路共用同一套语义。
 *
 * 正确的握手顺序（上游 `PerformAuthFlow(socket, needsDeviceID=true)`）：
 * ```
 * 手机 → PC : PACKET_ID_DEVICE_ID    载荷 = 明文 deviceId（**不是**加密数据，见下）
 * PC  → 手机: PACKET_ID_UNLOCK_REQUEST  载荷 = {protoVersion, deviceId, encData}
 *                                        encData = hex(AES(json{user, program, unlockToken}))
 *                                        其中 unlockToken 由 **PC** 生成
 * 手机 → PC : PACKET_ID_UNLOCK_RESPONSE 载荷 = {error:"", encData}
 *                                        encData = hex(AES(json{unlockToken, passwordKey}))
 *                                        必须原样回显 PC 下发的 unlockToken
 * ```
 *
 * 两个最容易写错的点：
 * 1. `DEVICE_ID` 的载荷是**明文字符串**。上游直接把它当设备 ID 去查库
 *    （`BaseUnlockConnection.cpp`: `std::string(reinterpret_cast<const char*>(packet.data.data()), ...)`），
 *    发加密数据会让查库必然失败并返回 "Invalid device ID"。
 * 2. `unlockToken` 是 **PC 生成、手机回显**，手机自己造一个 token 上游校验不过。
 */
object UnlockProtocol {

    private const val TAG = "UnlockProtocol"

    /** 与上游 `AppInfo::GetUnlockProtocolVersion()` / 配对时使用的版本一致 */
    const val PROTOCOL_VERSION = "3.0.0"

    /**
     * 构建 `PACKET_ID_DEVICE_ID` 的载荷：设备 ID 的原始 UTF-8 字节。
     */
    fun buildDeviceIdPayload(deviceId: String): ByteArray =
        deviceId.toByteArray(Charsets.UTF_8)

    /**
     * 解析 PC 下发的 `PACKET_ID_UNLOCK_REQUEST`，取出 PC 生成的 `unlockToken`。
     *
     * @param requestJson 请求包的 JSON 文本
     * @param encryptionKey 该设备的加密密钥
     * @return PC 生成的 unlockToken；任何一步失败返回 null
     */
    fun extractPcUnlockToken(requestJson: String, encryptionKey: String): String? {
        return try {
            val request = JSONObject(requestJson)
            val encDataHex = request.optString("encData", "")
            if (encDataHex.isEmpty()) {
                Log.e(TAG, "解锁请求缺少 encData")
                return null
            }

            val decrypted = CryptoUtils.decryptAES(
                CryptoUtils.hexToBytes(encDataHex),
                encryptionKey
            )
            if (decrypted == null) {
                Log.e(TAG, "解密 PC 端解锁请求失败（密钥不匹配或两端时间差超过 2 分钟）")
                return null
            }

            val token = JSONObject(String(decrypted, Charsets.UTF_8)).optString("unlockToken", "")
            if (token.isEmpty()) {
                Log.e(TAG, "解锁请求解密后缺少 unlockToken")
                return null
            }
            token
        } catch (e: Exception) {
            Log.e(TAG, "解析解锁请求失败", e)
            null
        }
    }

    /**
     * 构建 `PACKET_ID_UNLOCK_RESPONSE` 的载荷。
     *
     * @param pcUnlockToken 必须原样回显 PC 下发的 token
     * @param passwordKey 该设备的 passwordKey
     * @return 响应 JSON 文本；加密失败返回 null
     */
    fun buildUnlockResponse(
        pcUnlockToken: String,
        passwordKey: String?,
        encryptionKey: String
    ): String? {
        return try {
            // passwordKey 必须存在：上游 PacketUnlockResponseData::FromJson 用
            // json["passwordKey"] 取值，缺失会抛异常并被判为 "Error parsing response data"
            val responseData = JSONObject().apply {
                put("unlockToken", pcUnlockToken)
                put("passwordKey", passwordKey ?: "")
            }

            val encrypted = CryptoUtils.encryptAES(
                responseData.toString().toByteArray(Charsets.UTF_8),
                encryptionKey
            )
            if (encrypted == null) {
                Log.e(TAG, "加密解锁响应失败")
                return null
            }

            JSONObject().apply {
                put("error", "") // 空字符串表示成功
                put("encData", CryptoUtils.bytesToHex(encrypted))
            }.toString()
        } catch (e: Exception) {
            Log.e(TAG, "构建解锁响应失败", e)
            null
        }
    }

    /**
     * 构建失败响应（供界面层取消等场景使用）。
     * 上游识别 CANCEL / NOT_PAIRED / APP_ERROR / TIME_ERROR / DATA_ERROR / PROTOCOL_ERROR。
     */
    fun buildErrorResponse(errorCode: String): String =
        JSONObject().apply { put("error", errorCode) }.toString()
}
