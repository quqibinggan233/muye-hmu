package com.hmu.muye.data.yixiaoyuan

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 易校园（compus.xiaofubao.com）请求签名。
 *
 * 算法由官方客户端 native（libyischool.so 的 `NativeYischool.getSignData`）还原，
 * 2026-11-25 实机抓包 + 服务器回执双向验证通过：
 *
 * ```
 * a    = ymId 倒数第 2 位数字
 * b    = ymId 倒数第 3 位数字
 * S    = ymId.drop(a) + deviceId.drop(b) + uuToken
 * key  = sha256Hex(S).substring(32)          // 取哈希的【后】32 位十六进制字符
 *
 * data = 请求体各字段按 key 字典序取值 → 跳过空值 → trim → 用 "|" 连接
 * sign = Base64( HMAC-SHA256(key, data) )    // 44 字符，NO_WRAP
 * ```
 *
 * 注意：
 * - `uuToken` 只在密码 / 短信验证码登录响应里下发，静默登录不返回；
 *   登录后必须缓存，`uuToken` 变化则 key 必须重算。
 * - `sign` 只覆盖请求体，不绑定时间戳以外的内容（换 URL / IP 不影响），但 `nt` 变化会改变签名。
 */
object YixiaoyuanSigner {

    /** 依据登录态派生 HMAC 密钥；缺项时返回空串，调用方据此判断未登录。 */
    fun deriveKey(ymId: String, deviceId: String, uuToken: String): String {
        if (ymId.isBlank() || deviceId.isBlank() || uuToken.isBlank()) return ""
        val a = digitAtOrZero(ymId, ymId.length - 2)
        val b = digitAtOrZero(ymId, ymId.length - 3)
        val payload = ymId.drop(a.coerceIn(0, ymId.length)) +
            deviceId.drop(b.coerceIn(0, deviceId.length)) +
            uuToken
        return sha256Hex(payload).substring(32)
    }

    /**
     * 复刻 `Sha256Util.getSignParams`：TreeMap 按 key 字典序 → 取值 → 跳过空 → trim → `|` 连接。
     */
    fun signParams(params: Map<String, String>): String =
        params.toSortedMap()
            .values
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("|")

    /** 对已序列化的请求体参数签名。 */
    fun sign(params: Map<String, String>, key: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val digest = mac.doFinal(signParams(params).toByteArray(Charsets.UTF_8))
        return java.util.Base64.getEncoder().encodeToString(digest)
    }

    private fun digitAtOrZero(text: String, index: Int): Int =
        if (index in text.indices) text[index].digitToIntOrNull() ?: 0 else 0

    private fun sha256Hex(text: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
