package com.fengkao.timetable.data.water

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * 服务器时钟校正：用任意服务端响应头里的 HTTP `Date` 推算时间偏差，
 * 确保 3.1.10 协议下 30 秒时间桶与服务端对齐（adapted from Kou-JunHao/Oasis-App, MIT）。
 */
object WaterServerClock {

    @Volatile
    private var offsetMs: Long = 0L

    fun nowMs(): Long = System.currentTimeMillis() + offsetMs

    fun offsetMs(): Long = offsetMs

    fun syncFromDateHeader(rawDate: String?) {
        val text = rawDate?.trim().orEmpty()
        if (text.isBlank()) return
        runCatching {
            val serverMs = ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME)
                .toInstant()
                .toEpochMilli()
            offsetMs = serverMs - System.currentTimeMillis()
        }
    }

    fun resetForTest() {
        offsetMs = 0L
    }
}

/**
 * 慧生活 798 短信登录签名（3.1.10 keyed BLAKE3 协议，adapted from Kou-JunHao/Oasis-App, MIT）。
 */
object WaterLoginSigner {

    private const val WINDOW_MS = 30_000L
    private const val OCT_KEY_PREFIX = "Ys3yx=j7"
    private const val LOGIN_PATH = "/api/v1/acc/login"

    fun windowValue(nowMs: Long): Long = (nowMs / WINDOW_MS) * 30L

    fun pathToken(path: String = LOGIN_PATH): String {
        val cleaned = path.trim().lowercase().replace(Regex("[^a-z0-9]"), "")
        return cleaned.padStart(8, '0').takeLast(8)
    }

    fun sign(phone: String, smsCode: String, nowMs: Long = WaterServerClock.nowMs()): String {
        val bucket = windowValue(nowMs)
        val bucket8 = bucket.toString().padStart(8, '0').takeLast(8)
        val midStart = ((phone.length - 8) / 2).coerceIn(0, phone.length)
        val midEnd = (midStart + 8).coerceAtMost(phone.length)
        val mid8 = phone.substring(midStart, midEnd)
        val key = "$OCT_KEY_PREFIX$bucket8$mid8${pathToken()}"
        val payload = "authCode=$smsCode&un=$phone"
        return Blake3Keyed.keyedHashHex(
            key.toByteArray(Charsets.UTF_8),
            payload.toByteArray(Charsets.UTF_8),
        ).substring(0, 32)
    }
}
