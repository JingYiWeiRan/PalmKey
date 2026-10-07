package com.jywr.pcbuapk

import com.jywr.pcbuapk.utils.PairingInputValidator
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 测试手动输入配对功能的输入验证逻辑。
 *
 * ⚠️ 这些用例原先测的是本文件内自己声明的一份 `private fun isValidIpAddress/...` 副本，
 * 生产代码里根本没有同名实现 —— 于是测试全绿却对发布代码零保护，
 * 界面实际只判 `isNotBlank()`，连 `"99999"` 端口都会照发。
 *
 * 现在断言的是 [PairingInputValidator]，也就是 PairingScreen 真正引用的那一份。
 * 任何生产校验被放宽/删除，这里都会立刻变红。
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
            assertTrue("IP '$ip' should be valid", PairingInputValidator.isValidIpAddress(ip))
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
            assertFalse("IP '$ip' should be invalid", PairingInputValidator.isValidIpAddress(ip))
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
            assertTrue("Port '$port' should be valid", PairingInputValidator.isValidPort(port))
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
            assertFalse("Port '$port' should be invalid", PairingInputValidator.isValidPort(port))
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
            assertTrue("Key '$key' should be valid", PairingInputValidator.isValidEncryptionKey(key))
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
            assertFalse("Key '$key' should be invalid", PairingInputValidator.isValidEncryptionKey(key))
        }
    }

    @Test
    fun `complete form validation - all fields valid`() {
        assertTrue(
            "Complete form with valid data should pass validation",
            PairingInputValidator.isFormValid("192.168.1.100", "8080", "mySecretKey123")
        )
    }

    @Test
    fun `complete form validation - missing IP`() {
        assertFalse(
            "Form with missing IP should fail validation",
            PairingInputValidator.isFormValid("", "8080", "mySecretKey123")
        )
    }

    @Test
    fun `complete form validation - invalid port`() {
        assertFalse(
            "Form with invalid port should fail validation",
            PairingInputValidator.isFormValid("192.168.1.100", "99999", "mySecretKey123")
        )
    }

    @Test
    fun `complete form validation - empty encryption key`() {
        assertFalse(
            "Form with empty encryption key should fail validation",
            PairingInputValidator.isFormValid("192.168.1.100", "8080", "")
        )
    }

    /** 端口下界与上界必须真的被拦住（原先生产只判 toIntOrNull，0 会被放行） */
    @Test
    fun `port boundaries are enforced`() {
        assertFalse(PairingInputValidator.isValidPort("0"))
        assertTrue(PairingInputValidator.isValidPort("1"))
        assertTrue(PairingInputValidator.isValidPort("65535"))
        assertFalse(PairingInputValidator.isValidPort("65536"))
    }
}
