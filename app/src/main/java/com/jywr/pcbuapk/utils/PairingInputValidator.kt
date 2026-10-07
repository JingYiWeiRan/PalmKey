package com.jywr.pcbuapk.utils

/**
 * 手动输入配对信息时的输入校验。
 *
 * 存在的意义：这套规则原先**只存在于单元测试里**（测试自己声明了一份 private 副本），
 * 生产代码走的是 `isNotBlank()` / `toIntOrNull()`，比测试断言的宽松得多 ——
 * 于是测试全绿，却测不到任何会发布出去的代码，`"99999"` 这种端口也能发起配对。
 * 现在校验逻辑集中在这里，界面与测试引用的是同一份实现。
 *
 * 与上游的兼容性约束：电脑端用 `inet_pton(AF_INET, ...)` 解析地址，
 * **只支持 IPv4**，因此这里也只接受点分十进制 IPv4（主机名一律拒绝，
 * 免得用户以为填了域名可用）。
 */
object PairingInputValidator {

    /** 端口合法区间 */
    const val MIN_PORT = 1
    const val MAX_PORT = 65535

    /** 加密密钥最短长度（正常由上游随机生成 64 字符，这里是手动输入的下限保护） */
    const val MIN_ENCRYPTION_KEY_LENGTH = 3

    /**
     * 校验点分十进制 IPv4 地址。
     *
     * 刻意拒绝首尾空白：`"192.168.1.100 "` 这种输入通常是复制粘贴带进来的，
     * 放行只会在连接阶段以更难诊断的方式失败。
     */
    fun isValidIpAddress(ip: String): Boolean {
        if (ip.isEmpty() || ip != ip.trim()) return false

        val parts = ip.split(".")
        if (parts.size != 4) return false

        return parts.all { part ->
            part.isNotEmpty() &&
                    part.all { it.isDigit() } &&
                    part.toIntOrNull()?.let { it in 0..255 } == true
        }
    }

    fun isValidPort(port: String): Boolean {
        if (port.isEmpty() || port != port.trim()) return false
        if (!port.all { it.isDigit() }) return false

        val value = port.toIntOrNull() ?: return false
        return value in MIN_PORT..MAX_PORT
    }

    fun isValidEncryptionKey(key: String): Boolean =
        key.isNotBlank() && key.length >= MIN_ENCRYPTION_KEY_LENGTH

    /** 三个字段是否都合法 */
    fun isFormValid(ip: String, port: String, encKey: String): Boolean =
        isValidIpAddress(ip) && isValidPort(port) && isValidEncryptionKey(encKey)
}
