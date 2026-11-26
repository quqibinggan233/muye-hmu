package com.hmu.muye.data.ujing

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 美的「U 净」校园平台公开协议客户端（独立 Kotlin 实现）。
 *
 * 仅提供短信验证码登录（JWT）、附近门店/设备空闲看板及扫码查机等只读能力；
 * 禁用自动重试（retryOnConnectionFailure = false），不自动发起下单或支付。
 */
class UjingClient(
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build(),
    private val log: (String) -> Unit = {},
) {

    fun sendSmsCaptcha(mobile: String): UjingResult<Unit> {
        val cleanPhone = mobile.trim()
        val req = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/captcha?mobile=${encode(cleanPhone)}")
            .header("x-app-code", "ZI")
            .header("x-app-version", "2.4.3")
            .header("User-Agent", MOBILE_UA)
            .get()
            .build()
        return when (val r = executeJson(req)) {
            is UjingResult.Success -> UjingResult.Success(Unit)
            is UjingResult.SessionExpired -> r
            is UjingResult.Error -> r
        }
    }

    fun loginWithSms(mobile: String, captcha: String): UjingResult<String> {
        val req = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/login")
            .header("x-app-code", "ZI")
            .header("x-app-version", "2.4.3")
            .header("Content-Type", "application/json")
            .post(
                JSONObject()
                    .put("mobile", mobile.trim())
                    .put("captcha", captcha.trim())
                    .toString()
                    .toRequestBody(JSON_MEDIA),
            )
            .build()
        return when (val r = executeJson(req)) {
            is UjingResult.Success -> {
                val token = r.data.optJSONObject("data")?.let { d ->
                    d.optString("token").ifBlank { d.optString("jwt") }
                }.orEmpty()
                if (token.isBlank() || token == "null") {
                    UjingResult.Error("U 净登录未返回有效 JWT")
                } else {
                    UjingResult.Success(cleanJwt(token))
                }
            }
            is UjingResult.SessionExpired -> r
            is UjingResult.Error -> r
        }
    }

    fun nearbyStores(
        jwt: String,
        keyword: String = "",
        lat: Double = 34.787,
        lon: Double = 113.800,
        scopeMeters: Int = 2000,
    ): UjingResult<List<UjingStoreSummary>> {
        val token = cleanJwt(jwt)
        if (token.isBlank()) return UjingResult.SessionExpired()
        // U 净服务端经度参数名为 lont（非 lng）
        val path = buildString {
            append("/stores/near?lat=").append(lat)
            append("&lont=").append(lon)
            append("&scope=").append(scopeMeters)
            append("&page=1&size=50&mode=BA")
            if (keyword.isNotBlank()) append("&keyword=").append(encode(keyword.trim()))
        }
        val req = Request.Builder()
            .url(baseUrl.trimEnd('/') + path)
            .header("x-app-code", "ZA")
            .header("x-app-version", "2.4.18")
            .header("Authorization", "Bearer $token")
            .build()
        return when (val r = executeJson(req)) {
            is UjingResult.Success -> UjingResult.Success(parseStores(r.data))
            is UjingResult.SessionExpired -> r
            is UjingResult.Error -> r
        }
    }

    fun storeDevices(jwt: String, storeId: String): UjingResult<List<UjingDeviceInfo>> {
        val token = cleanJwt(jwt)
        if (token.isBlank()) return UjingResult.SessionExpired()
        val req = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/devices/reserve?storeId=${encode(storeId)}")
            .header("x-app-code", "ZA")
            .header("x-app-version", "2.4.18")
            .header("Authorization", "Bearer $token")
            .build()
        return when (val r = executeJson(req, allowedCodes = setOf(1614))) {
            is UjingResult.Success -> {
                val code = r.data.optInt("code", 0)
                if (code == 1614) UjingResult.Success(emptyList())
                else UjingResult.Success(parseStoreDevices(r.data))
            }
            is UjingResult.SessionExpired -> r
            is UjingResult.Error -> r
        }
    }

    fun scanDeviceQr(jwt: String, qrCode: String): UjingResult<UjingDeviceInfo> {
        val token = cleanJwt(jwt)
        if (token.isBlank()) return UjingResult.SessionExpired()
        val req = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/devices/scanWasherCode")
            .header("x-app-code", "BI")
            .header("x-app-version", "2.4.2")
            .header("weex-version", "1.1.30")
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $token")
            .post(JSONObject().put("qrCode", qrCode.trim()).toString().toRequestBody(JSON_MEDIA))
            .build()
        return when (val r = executeJson(req)) {
            is UjingResult.Success -> {
                val dataObj = r.data.optJSONObject("data")?.let { d ->
                    d.optJSONObject("device") ?: d
                }
                if (dataObj == null) {
                    UjingResult.Error("未解析到设备详情")
                } else {
                    UjingResult.Success(parseDeviceObject(dataObj))
                }
            }
            is UjingResult.SessionExpired -> r
            is UjingResult.Error -> r
        }
    }

    private fun executeJson(
        req: Request,
        allowedCodes: Set<Int> = emptySet(),
    ): UjingResult<JSONObject> = runCatching {
        client.newCall(req).execute().use { resp ->
            log("${req.method} ${req.url.encodedPath} http=${resp.code}")
            if (resp.code == 401) return@use UjingResult.SessionExpired()
            val body = resp.body?.string().orEmpty()
            val obj = runCatching { JSONObject(body) }.getOrNull()
            if (!resp.isSuccessful) {
                val isWafOrHtml = resp.code == 405 ||
                    body.contains("errors.aliyun.com", ignoreCase = true) ||
                    body.contains("block_message", ignoreCase = true) ||
                    body.trimStart().startsWith("<")
                if (isWafOrHtml) {
                    val tip = if (req.url.encodedPath.endsWith("/captcha")) {
                        "U 净服务端安全校验拦截了第三方直接发短信（HTTP ${resp.code}），请先在微信「U 净」小程序或官方 App 获取短信验证码，再回到此处填入验证码登录（或粘贴 JWT）"
                    } else {
                        "U 净服务端拦截了当前请求（HTTP ${resp.code}），请稍后重试或改用微信「U 净」小程序"
                    }
                    return@use UjingResult.Error(tip)
                }
                val msg = obj?.optString("message").orEmpty().ifBlank { obj?.optString("msg").orEmpty() }
                if (msg.contains("JWT", ignoreCase = true) || msg.contains("登录")) {
                    return@use UjingResult.SessionExpired(msg)
                }
                return@use UjingResult.Error(msg.ifBlank { "U 净服务异常（HTTP ${resp.code}）" })
            }
            if (obj == null) return@use UjingResult.Error("U 净返回数据格式异常")
            val code = obj.optInt("code", Int.MIN_VALUE)
            val msg = obj.optString("message").ifBlank { obj.optString("msg") }
            if (code == 401 || code == -99 || msg.contains("JWT", ignoreCase = true) || msg.contains("登录失效")) {
                return@use UjingResult.SessionExpired(msg.ifBlank { "U 净登录已过期，请重新验证" })
            }
            if (code != 0 && code !in allowedCodes) {
                return@use UjingResult.Error(msg.ifBlank { "请求失败（code=$code）" })
            }
            UjingResult.Success(obj)
        }
    }.getOrElse { e ->
        UjingResult.Error("连接 U 净失败：${e.message ?: "网络异常"}")
    }

    private fun parseStores(root: JSONObject): List<UjingStoreSummary> {
        val rawData = root.opt("data")
        val arr = when (rawData) {
            is JSONArray -> rawData
            is JSONObject -> rawData.optJSONArray("list")
                ?: rawData.optJSONArray("stores")
                ?: rawData.optJSONArray("rows")
                ?: JSONArray()
            else -> JSONArray()
        }
        return (0 until arr.length()).mapNotNull { i ->
            val obj = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = obj.opt("id")?.toString().orEmpty().ifBlank { obj.optString("storeId") }
            if (id.isBlank()) return@mapNotNull null
            val infoArr = obj.optJSONArray("storeInfo") ?: JSONArray()
            val categories = (0 until infoArr.length()).mapNotNull { j ->
                val catObj = infoArr.optJSONObject(j) ?: return@mapNotNull null
                val cat = catObj.optInt("category", 0)
                UjingCategoryCount(
                    category = cat,
                    label = categoryLabel(cat),
                    available = catObj.optInt("access", 0),
                    total = catObj.optInt("num", 0),
                )
            }
            UjingStoreSummary(
                id = id,
                name = obj.optString("name").ifBlank { obj.optString("storeName") }.ifBlank { "U 净门店 #$id" },
                address = obj.optString("address").ifBlank { obj.optString("addr") },
                distanceMeters = obj.optInt("distance", obj.optInt("dist", 0)),
                categories = categories,
            )
        }
    }

    private fun parseStoreDevices(root: JSONObject): List<UjingDeviceInfo> {
        val rawData = root.opt("data")
        val arr = when (rawData) {
            is JSONArray -> rawData
            is JSONObject -> rawData.optJSONArray("list")
                ?: rawData.optJSONArray("devices")
                ?: rawData.optJSONArray("rows")
                ?: JSONArray()
            else -> JSONArray()
        }
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let(::parseDeviceObject)
        }
    }

    private fun parseDeviceObject(obj: JSONObject): UjingDeviceInfo {
        val id = obj.opt("deviceId")?.toString().orEmpty()
            .ifBlank { obj.opt("id")?.toString().orEmpty() }
        val name = obj.optString("deviceName")
            .ifBlank { obj.optString("name") }
            .ifBlank { "设备 ${id.takeLast(6)}" }
        val typeName = obj.optString("deviceTypeName")
            .ifBlank { obj.optString("typeName") }
            .ifBlank {
                val cat = obj.optInt("category", obj.optInt("deviceTypeId", 0))
                if (cat > 0) categoryLabel(cat) else ""
            }
        val remain = obj.optInt("remainTime", obj.optInt("remainSeconds", 0)).coerceAtLeast(0)
        val statusCode = obj.optInt("status", -1)
        val isIdle = obj.optBoolean("isIdle", false) ||
            obj.optInt("access", -1) == 1 ||
            statusCode == 1 ||
            statusCode == 0 && remain == 0 && !obj.optBoolean("isFault", false)
        val statusText = obj.optString("statusName")
            .ifBlank { obj.optString("statusDesc") }
            .ifBlank {
                when {
                    remain > 0 -> "运行中（余 ${(remain + 59) / 60} 分钟）"
                    isIdle -> "空闲"
                    else -> "使用中或离线"
                }
            }
        return UjingDeviceInfo(
            id = id,
            name = name,
            typeName = typeName,
            statusText = statusText,
            available = isIdle && remain == 0,
            remainSeconds = remain,
        )
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    companion object {
        const val DEFAULT_BASE_URL = "https://phoenix.ujing.online/api/v1"
        private val JSON_MEDIA = "application/json".toMediaType()
        private const val MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

        fun cleanJwt(raw: String): String =
            raw.trim().removePrefix("Bearer ").removePrefix("bearer ").trim()

        fun categoryLabel(category: Int): String = when (category) {
            1 -> "洗衣机"
            2 -> "烘干机"
            3 -> "洗鞋机"
            4 -> "吹风机"
            else -> "设备#$category"
        }
    }
}
