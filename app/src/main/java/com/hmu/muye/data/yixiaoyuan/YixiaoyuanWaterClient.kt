package com.hmu.muye.data.yixiaoyuan

import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 浴室动态用水码（`application.xiaofubao.com` 的 H5 接口）。
 *
 * 该接口**不需要签名**，只认 WebView 里由官方 App 写入的 `shiroJID` cookie，
 * 因此与 [YixiaoyuanClient] 的 token/uuToken 体系相互独立。
 */
class YixiaoyuanWaterClient(
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build(),
) {

    /** 获取当前可用用水码；返回 `null` 表示本次没有有效码（如次数用尽或需先申请）。 */
    fun queryCode(shiroJid: String): YixiaoyuanResult<WaterCode> {
        if (shiroJid.isBlank()) return YixiaoyuanResult.Error("未配置易校园会话（shiroJID）")
        val req = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/app/hzsun/cloudwater/queryRanCode")
            .header("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
            .header("Cookie", "shiroJID=$shiroJid")
            .post(FormBody.Builder().add("platform", "YUNMA_APP").build())
            .build()
        return runCatching {
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                val json = runCatching { JSONObject(text) }.getOrNull()
                    ?: return YixiaoyuanResult.Error("响应不是合法 JSON（HTTP ${resp.code}）")
                when (json.optInt("statusCode", -1)) {
                    0 -> {
                        val data = json.optJSONObject("data")
                        val code = data?.optString("ranCode").orEmpty()
                        if (code.isBlank()) {
                            YixiaoyuanResult.Error("当前没有可用用水码，请在官方 App 内重新获取")
                        } else {
                            YixiaoyuanResult.Success(
                                WaterCode(
                                    code = code,
                                    validSeconds = data.optInt("ranValidTime", 0),
                                    remainTimes = data.optInt("ranValidTimes", 0),
                                    intervalSeconds = data.optInt("ranIntervalTime", 0),
                                    usedTimes = data.optInt("ranGetTimes", 0),
                                    longTerm = data.optString("longTerm") == "1",
                                    fetchedAt = System.currentTimeMillis(),
                                ),
                            )
                        }
                    }
                    204 -> YixiaoyuanResult.SessionExpired(json.optString("message", "请重新登录"))
                    else -> YixiaoyuanResult.Error(json.optString("message").ifBlank { "请求失败" }, json.optInt("statusCode", -1))
                }
            }
        }.getOrElse { YixiaoyuanResult.Error(it.message ?: "网络请求失败") }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://application.xiaofubao.com"
    }
}

/**
 * 浴室用水码。
 *
 * 实测为 **8 位**数字；响应里的 `numLength` 是"设备编号长度"，与码长无关，勿混用。
 * [longTerm] 为 true 表示长期有效，否则按 [validSeconds] 倒计时。
 */
data class WaterCode(
    val code: String,
    val validSeconds: Int,
    val remainTimes: Int,
    val intervalSeconds: Int,
    val usedTimes: Int,
    val longTerm: Boolean,
    val fetchedAt: Long,
) {
    /** 剩余有效秒数（基于获取时刻推算，仅用于界面倒计时展示）。 */
    fun remainingSeconds(nowMillis: Long = System.currentTimeMillis()): Int =
        if (longTerm) Int.MAX_VALUE
        else (validSeconds - (nowMillis - fetchedAt) / 1000).toInt().coerceAtLeast(0)
}
