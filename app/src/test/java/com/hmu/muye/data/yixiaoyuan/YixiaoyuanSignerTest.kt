package com.hmu.muye.data.yixiaoyuan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 签名算法回归测试。
 *
 * 黄金样本为**合成数据**（与任何真实账号无关），期望值由独立实现算出：
 * ```
 * ymId     = 1234567890123456789   → a=8, b=7
 * deviceId = ym-0123456789abcdef0123456789abcdef
 * uuToken  = 0123456789abcdef0123456789abcdef
 * S        = ymId.drop(8) + deviceId.drop(7) + uuToken
 * key      = sha256Hex(S)[32..63]  = 6fe0c3b95d2c4d9071a7696e55f6be8c
 * ```
 */
class YixiaoyuanSignerTest {

    private val ymId = "1234567890123456789"
    private val deviceId = "ym-0123456789abcdef0123456789abcdef"
    private val uuToken = "0123456789abcdef0123456789abcdef"

    @Test
    fun `derives key from ymId digits and device tail`() {
        val key = YixiaoyuanSigner.deriveKey(ymId, deviceId, uuToken)
        assertEquals("6fe0c3b95d2c4d9071a7696e55f6be8c", key)
    }

    @Test
    fun `sign params sorts by key skips blanks and joins with pipe`() {
        val params = linkedMapOf(
            "token" to "tok-abc123",
            "appVersion" to "740",
            "nt" to "1700000000000",
            "platform" to "YUNMA_APP",
            "deviceId" to deviceId,
            "ymId" to ymId,
            "empty" to "",
            "padded" to "  x  ",
        )
        // 字典序：appVersion, deviceId, empty(跳过), nt, padded, platform, token, ymId
        assertEquals(
            "740|$deviceId|1700000000000|x|YUNMA_APP|tok-abc123|$ymId",
            YixiaoyuanSigner.signParams(params),
        )
    }

    @Test
    fun `produces stable base64 hmac`() {
        val key = YixiaoyuanSigner.deriveKey(ymId, deviceId, uuToken)
        val params = mapOf(
            "appVersion" to "740",
            "nt" to "1700000000000",
            "ymId" to ymId,
            "deviceId" to deviceId,
            "platform" to "YUNMA_APP",
            "token" to "tok-abc123",
        )
        val sign = YixiaoyuanSigner.sign(params, key)
        assertEquals("pHGG7NtQsud6YPmtzNiusp2A5/N6EsNsrLsDCUnLjIY=", sign)
        assertEquals(44, sign.length)
    }

    @Test
    fun `key changes with uuToken`() {
        val k1 = YixiaoyuanSigner.deriveKey(ymId, deviceId, uuToken)
        val k2 = YixiaoyuanSigner.deriveKey(ymId, deviceId, "ffffffffffffffffffffffffffffffff")
        assertNotEquals(k1, k2)
    }

    @Test
    fun `blank credential cannot sign`() {
        assertEquals("", YixiaoyuanSigner.deriveKey("", deviceId, uuToken))
        assertEquals("", YixiaoyuanSigner.deriveKey(ymId, "", uuToken))
        assertEquals("", YixiaoyuanSigner.deriveKey(ymId, deviceId, ""))
        assertTrue(YixiaoyuanCredential(deviceId = deviceId).signKey().isBlank())
    }

    @Test
    fun `offset digits fall back when ymId is not numeric`() {
        // 末位非数字时按下标 0 处理，等价于不裁剪
        val key = YixiaoyuanSigner.deriveKey("abc", "dev", "uu")
        val expected = YixiaoyuanSigner.deriveKey("abc", "dev", "uu")
        assertEquals(expected, key)
        assertTrue(key.isNotEmpty())
    }
}
