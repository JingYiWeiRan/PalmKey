package com.jywr.pcbuapk

import org.junit.Test
import org.junit.Assert.*

/**
 * 测试手动输入配对功能的输入验证逻辑
 * 这些测试验证用户在手动输入配对时的表单验证规则
 */
class ManualInputValidationTest {

    @Test
    fun `valid IP address should pass validation`() {
        val validIps = listOf(
            "192.168.1.100",
            "10.0.0.1",
            "172.16.0.1",
            "255.255.255.255",
            "127.0.0.1"
        )

        validIps.forEach { ip ->
            assertTrue("IP '$ip' should be valid", isValidIpAddress(ip))
        }
    }

    @Test
    fun `invalid IP address should fail validation`() {
        val invalidIps = listOf(
            "",
            "256.1.1.1",
            "192.168.1",
            "abc.def.ghi.jkl",
            "192.168.1.100.1",
            "  ",
            "192.168.1.100 "
        )

        invalidIps.forEach { ip ->
            assertFalse("IP '$ip' should be invalid", isValidIpAddress(ip))
        }
    }

    @Test
    fun `valid port number should pass validation`() {
        val validPorts = listOf(
            "8080",
            "80",
            "443",
            "65535",
            "1024"
        )

        validPorts.forEach { port ->
            assertTrue("Port '$port' should be valid", isValidPort(port))
        }
    }

    @Test
    fun `invalid port number should fail validation`() {
        val invalidPorts = listOf(
            "",
            "0",
            "65536",
            "-1",
            "abc",
            "8080.0",
            "  ",
            "99999"
        )

        invalidPorts.forEach { port ->
            assertFalse("Port '$port' should be invalid", isValidPort(port))
        }
    }

    @Test
    fun `valid encryption key should pass validation`() {
        val validKeys = listOf(
            "mySecretKey123",
            "aBcDeFgHiJkLmNoPqRsTuVwXyZ012345",
            "key-with-dashes",
            "key_with_underscores",
            "12345678"
        )

        validKeys.forEach { key ->
            assertTrue("Key '$key' should be valid", isValidEncryptionKey(key))
        }
    }

    @Test
    fun `invalid encryption key should fail validation`() {
        val invalidKeys = listOf(
            "",
            "   ",
            "ab", // too short
            "a"
        )

        invalidKeys.forEach { key ->
            assertFalse("Key '$key' should be invalid", isValidEncryptionKey(key))
        }
    }

    @Test
    fun `complete form validation - all fields valid`() {
        val ip = "192.168.1.100"
        val port = "8080"
        val encKey = "mySecretKey123"

        val isFormValid = isValidIpAddress(ip) && isValidPort(port) && isValidEncryptionKey(encKey)

        assertTrue("Complete form with valid data should pass validation", isFormValid)
    }

    @Test
    fun `complete form validation - missing IP`() {
        val ip = ""
        val port = "8080"
        val encKey = "mySecretKey123"

        val isFormValid = isValidIpAddress(ip) && isValidPort(port) && isValidEncryptionKey(encKey)

        assertFalse("Form with missing IP should fail validation", isFormValid)
    }

    @Test
    fun `complete form validation - invalid port`() {
        val ip = "192.168.1.100"
        val port = "99999"
        val encKey = "mySecretKey123"

        val isFormValid = isValidIpAddress(ip) && isValidPort(port) && isValidEncryptionKey(encKey)

        assertFalse("Form with invalid port should fail validation", isFormValid)
    }

    @Test
    fun `complete form validation - empty encryption key`() {
        val ip = "192.168.1.100"
        val port = "8080"
        val encKey = ""

        val isFormValid = isValidIpAddress(ip) && isValidPort(port) && isValidEncryptionKey(encKey)

        assertFalse("Form with empty encryption key should fail validation", isFormValid)
    }

    /**
     * IP地址验证函数
     */
    private fun isValidIpAddress(ip: String): Boolean {
        if (ip.isBlank()) return false
        
        val parts = ip.split(".")
        if (parts.size != 4) return false
        
        return parts.all { part ->
            part.toIntOrNull()?.let { it in 0..255 } ?: false
        }
    }

    /**
     * 端口号验证函数
     */
    private fun isValidPort(port: String): Boolean {
        if (port.isBlank()) return false
        
        val portNum = port.toIntOrNull() ?: return false
        return portNum in 1..65535
    }

    /**
     * 加密密钥验证函数
     */
    private fun isValidEncryptionKey(key: String): Boolean {
        return key.isNotBlank() && key.length >= 3
    }
}
