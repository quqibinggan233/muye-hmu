package com.hmu.muye.data.yixiaoyuan

import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 浴室动态用水码（`application.xiaofubao.com` 的 H5 接口）与自动授权换票。
 *
 * H5 用水码接口无需签名，依赖 `shiroJID` cookie；已登录原生账号拿到 `ymId` 后，
 * 可通过 `auth.xiaofubao.com/authoriz/getCodeV2` → `application.xiaofubao.com/app/login/getUser4Authorize`
 * 自动换取 `shiroJID`，无需用户手动抓包填写 Cookie。
 */
class YixiaoyuanWaterClient(
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build(),
    private val authBaseUrl: String = DEFAULT_AUTH_BASE_URL,
    private val log: (String) -> Unit = {},
) {

    /**
     * 使用已登录易校园账号的 `ymId` 与 `schoolCode` 自动换取 H5 会话 `shiroJID`。
     */
    fun exchangeShiroJid(credential: YixiaoyuanCredential): YixiaoyuanResult<String> =
        exchangeShiroJid(credential.ymId, credential.schoolCode)

    /**
     * 两步无感换票：
     * 1. 带 `yunmaapp.NET` UA 请求 `/authoriz/getCodeV2`（传 `unionid=ymId`），从跳转 URL 提取 `ymCode`
     * 2. 提交 `POST /app/login/getUser4Authorize` 换取 `Set-Cookie: shiroJID=...`
     */
    fun exchangeShiroJid(
        ymId: String,
        schoolCode: String = YixiaoyuanCredential.DEFAULT_SCHOOL_CODE,
    ): YixiaoyuanResult<String> {
        val cleanYmId = ymId.trim()
        if (cleanYmId.isBlank()) return YixiaoyuanResult.Error("请先登录易校园账号")
        val cleanAppBase = baseUrl.trimEnd('/')
        val cleanAuthBase = authBaseUrl.trimEnd('/')
        val callbackUrl = "$cleanAppBase/water/zy/ranCode"
        var currentUrl = "$cleanAuthBase/authoriz/getCodeV2" +
            "?state=&authType=2&appid=$DEFAULT_YM_APP_ID&bindSkip=1&source=auth" +
            "&unionid=${urlEncode(cleanYmId)}&callbackUrl=${urlEncode(callbackUrl)}"

        return runCatching {
            val noRedirectClient = client.newBuilder()
                .followRedirects(false)
                .followSslRedirects(false)
                .build()
            val hopCookies = linkedMapOf<String, String>()
            var ymCode = ""

            for (hop in 0 until MAX_AUTH_HOPS) {
                val reqBuilder = Request.Builder()
                    .url(currentUrl)
                    .header("User-Agent", APP_WEBVIEW_UA)
                if (hopCookies.isNotEmpty()) {
                    reqBuilder.header(
                        "Cookie",
                        hopCookies.entries.joinToString("; ") { "${it.key}=${it.value}" },
                    )
                }
                var nextUrl: String? = null
                noRedirectClient.newCall(reqBuilder.build()).execute().use { resp ->
                    collectCookies(resp.headers("Set-Cookie"), hopCookies)
                    val location = resp.header("Location")
                    if (!location.isNullOrBlank()) {
                        val resolved = resp.request.url.resolve(location)?.toString() ?: location
                        val found = extractYmCode(resolved) ?: extractYmCode(location)
                        log("getCodeV2 hop=$hop http=${resp.code} hasLoc=true hasYmCode=${!found.isNullOrBlank()}")
                        if (!found.isNullOrBlank()) {
                            ymCode = found
                            return@use
                        }
                        if (resp.code in 300..399) {
                            nextUrl = resolved
                            return@use
                        }
                    }

                    val body = resp.body?.string().orEmpty()
                    val bodyCode = extractYmCode(body)
                    if (!bodyCode.isNullOrBlank()) {
                        log("getCodeV2 hop=$hop http=${resp.code} bodyHasYmCode=true")
                        ymCode = bodyCode
                        return@use
                    }
                    val jsTarget = extractJsRedirect(body)?.replace("&amp;", "&")
                    if (!jsTarget.isNullOrBlank()) {
                        val resolved = resp.request.url.resolve(jsTarget)?.toString() ?: jsTarget
                        val jsCode = extractYmCode(resolved)
                        log("getCodeV2 hop=$hop http=${resp.code} jsRedirect hasYmCode=${!jsCode.isNullOrBlank()}")
                        if (!jsCode.isNullOrBlank()) {
                            ymCode = jsCode
                            return@use
                        }
                        if (hop + 1 < MAX_AUTH_HOPS) {
                            nextUrl = resolved
                            return@use
                        }
                    }

                    val detail = extractErrorDetail(body, resp.code)
                    log("getCodeV2 hop=$hop failed http=${resp.code} detailLen=${detail.length}")
                    return@runCatching YixiaoyuanResult.Error("自动获取用水码授权失败：$detail")
                }

                if (ymCode.isNotBlank()) break
                if (nextUrl.isNullOrBlank()) break
                currentUrl = nextUrl
            }

            if (ymCode.isBlank()) {
                return@runCatching YixiaoyuanResult.Error("自动获取用水码授权失败：未返回 ymCode")
            }

            // 对齐官方 umi.js 首次授权：不传 schoolCode，只传 code + authType + platform
            val authForm = FormBody.Builder()
                .add("code", ymCode)
                .add("authType", "2")
                .add("platform", "YUNMA_APP")
                .build()
            val authReq = Request.Builder()
                .url("$cleanAppBase/app/login/getUser4Authorize")
                .header("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
                .header("X-Requested-With", "XMLHttpRequest")
                .header("User-Agent", APP_WEBVIEW_UA)
                .header("Origin", cleanAppBase)
                .header("Referer", "$cleanAppBase/water/zy/ranCode")
                .post(authForm)
                .build()

            client.newCall(authReq).execute().use { resp ->
                val rawSetCookies = resp.headers("Set-Cookie")
                val shiroJid = extractCookie(rawSetCookies, "shiroJID")
                val text = resp.body?.string().orEmpty()
                val json = runCatching { JSONObject(text) }.getOrNull()
                val cookieMeta = summarizeCookies(rawSetCookies)
                if (json == null) {
                    log("getUser4Authorize http=${resp.code} invalidJson cookies=$cookieMeta")
                    return@use YixiaoyuanResult.Error("授权响应不是合法 JSON（HTTP ${resp.code}）")
                }
                val statusCode = json.optInt("statusCode", -1)
                val ok = statusCode == 0 || json.optBoolean("success", false)
                log("getUser4Authorize http=${resp.code} status=$statusCode ok=$ok hasShiro=${shiroJid.isNotBlank()} cookies=$cookieMeta")
                if (!ok) {
                    return@use YixiaoyuanResult.Error(
                        json.optString("message").ifBlank { "获取用水码会话失败" },
                        statusCode,
                    )
                }
                if (shiroJid.isBlank()) {
                    return@use YixiaoyuanResult.Error("服务端未下发用水码会话（shiroJID）")
                }
                YixiaoyuanResult.Success(shiroJid)
            }
        }.getOrElse {
            log("exchangeShiroJid exception=${it.javaClass.simpleName}")
            YixiaoyuanResult.Error(it.message ?: "网络请求失败")
        }
    }

    /** 查询当前可用用水码；[autoGenerateIfEmpty] 为 true 时若暂无可用码且仍有配额，自动申请新码。 */
    fun queryCode(
        shiroJid: String,
        autoGenerateIfEmpty: Boolean = false,
    ): YixiaoyuanResult<WaterCode> {
        if (shiroJid.isBlank()) return YixiaoyuanResult.Error("未配置易校园会话（shiroJID）")
        val cleanBase = baseUrl.trimEnd('/')
        val req = Request.Builder()
            .url("$cleanBase/app/hzsun/cloudwater/queryRanCode")
            .header("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
            .header("X-Requested-With", "XMLHttpRequest")
            .header("User-Agent", APP_WEBVIEW_UA)
            .header("Origin", cleanBase)
            .header("Referer", "$cleanBase/water/zy/ranCode")
            .header("Cookie", "shiroJID=$shiroJid")
            .post(FormBody.Builder().add("platform", "YUNMA_APP").build())
            .build()
        return executeWaterCodeCall(req) { data ->
            if (autoGenerateIfEmpty) {
                val remainGet = data?.optInt("ranGetTimes", 1) ?: 1
                if (remainGet <= 0) {
                    YixiaoyuanResult.Error("当日获取次数已用完")
                } else {
                    insertCode(shiroJid)
                }
            } else {
                YixiaoyuanResult.Error("当前没有可用用水码，请点击「获取新码」")
            }
        }
    }

    /** 主动生成/刷新一个新的 8 位浴室用水码（`/app/hzsun/cloudwater/insertRanCode`）。 */
    fun insertCode(
        shiroJid: String,
        unPayName: String = "待支付订单",
    ): YixiaoyuanResult<WaterCode> {
        if (shiroJid.isBlank()) return YixiaoyuanResult.Error("未配置易校园会话（shiroJID）")
        val cleanBase = baseUrl.trimEnd('/')
        val req = Request.Builder()
            .url("$cleanBase/app/hzsun/cloudwater/insertRanCode")
            .header("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
            .header("X-Requested-With", "XMLHttpRequest")
            .header("User-Agent", APP_WEBVIEW_UA)
            .header("Origin", cleanBase)
            .header("Referer", "$cleanBase/water/zy/ranCode")
            .header("Cookie", "shiroJID=$shiroJid")
            .post(
                FormBody.Builder()
                    .add("unPayName", unPayName)
                    .add("platform", "YUNMA_APP")
                    .build(),
            )
            .build()
        return executeWaterCodeCall(req) {
            YixiaoyuanResult.Error("申请用水码未返回有效码，请稍后重试")
        }
    }

    /** 停用当前 8 位浴室用水码（`/app/hzsun/cloudwater/cancelRanCode`）。 */
    fun cancelCode(
        shiroJid: String,
        ranCode: String,
    ): YixiaoyuanResult<Unit> {
        if (shiroJid.isBlank()) return YixiaoyuanResult.Error("未配置易校园会话（shiroJID）")
        val cleanCode = ranCode.trim()
        if (cleanCode.isBlank()) return YixiaoyuanResult.Error("当前没有启用中的用水码")
        val cleanBase = baseUrl.trimEnd('/')
        val req = Request.Builder()
            .url("$cleanBase/app/hzsun/cloudwater/cancelRanCode")
            .header("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
            .header("X-Requested-With", "XMLHttpRequest")
            .header("User-Agent", APP_WEBVIEW_UA)
            .header("Origin", cleanBase)
            .header("Referer", "$cleanBase/water/zy/ranCode")
            .header("Cookie", "shiroJID=$shiroJid")
            .post(
                FormBody.Builder()
                    .add("ranCode", cleanCode)
                    .add("platform", "YUNMA_APP")
                    .build(),
            )
            .build()
        return runCatching {
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                val json = runCatching { JSONObject(text) }.getOrNull()
                    ?: return@use YixiaoyuanResult.Error("响应不是合法 JSON（HTTP ${resp.code}）")
                val status = json.optInt("statusCode", -1)
                log("/app/hzsun/cloudwater/cancelRanCode http=${resp.code} status=$status")
                when (status) {
                    0 -> YixiaoyuanResult.Success(Unit)
                    204 -> YixiaoyuanResult.SessionExpired(json.optString("message", "请重新登录"))
                    else -> YixiaoyuanResult.Error(json.optString("message").ifBlank { "停用用水码失败" }, status)
                }
            }
        }.getOrElse { YixiaoyuanResult.Error(it.message ?: "网络请求失败") }
    }

    private fun executeWaterCodeCall(
        req: Request,
        onEmptyCode: (JSONObject?) -> YixiaoyuanResult<WaterCode>,
    ): YixiaoyuanResult<WaterCode> = runCatching {
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            val json = runCatching { JSONObject(text) }.getOrNull()
                ?: return@use YixiaoyuanResult.Error("响应不是合法 JSON（HTTP ${resp.code}）")
            val status = json.optInt("statusCode", -1)
            val data = json.optJSONObject("data")
            val code = data?.optString("ranCode").orEmpty()
            log("${req.url.encodedPath} http=${resp.code} status=$status hasCode=${code.isNotBlank()}")
            when (status) {
                0 -> {
                    if (data == null || code.isBlank()) {
                        onEmptyCode(data)
                    } else {
                        val longTermFlag = data.optString("longTerm").ifBlank { "-1" }
                        YixiaoyuanResult.Success(
                            WaterCode(
                                code = code,
                                validSeconds = data.optInt("ranValidTime", 0),
                                remainTimes = data.optInt("ranValidTimes", 0),
                                intervalSeconds = data.optInt("ranIntervalTime", 0),
                                usedTimes = data.optInt("ranGetTimes", 0),
                                longTerm = longTermFlag == "1",
                                fetchedAt = System.currentTimeMillis(),
                                longTermFlag = longTermFlag,
                            ),
                        )
                    }
                }
                204 -> YixiaoyuanResult.SessionExpired(json.optString("message", "请重新登录"))
                else -> YixiaoyuanResult.Error(json.optString("message").ifBlank { "请求失败" }, status)
            }
        }
    }.getOrElse { YixiaoyuanResult.Error(it.message ?: "网络请求失败") }

    private fun extractYmCode(input: String?): String? {
        if (input.isNullOrBlank()) return null
        val match = YM_CODE_REGEX.find(input) ?: return null
        val raw = match.groupValues.getOrNull(1)?.trim().orEmpty()
        if (raw.isBlank()) return null
        return runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
    }

    private fun extractJsRedirect(body: String): String? =
        JS_REPLACE_REGEX.find(body)?.groupValues?.getOrNull(1)
            ?.takeIf { it.startsWith("http://") || it.startsWith("https://") || it.startsWith("/") }

    private fun extractErrorDetail(body: String, httpCode: Int): String {
        val jsonMsg = runCatching { JSONObject(body).optString("message").trim() }.getOrNull()
            ?.takeIf { it.isNotBlank() }
        if (jsonMsg != null) return jsonMsg
        val htmlDiv = HTML_DIV_REGEX.findAll(body)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotBlank() }
            .lastOrNull()
        return htmlDiv ?: "HTTP $httpCode"
    }

    private fun collectCookies(headers: List<String>, target: MutableMap<String, String>) {
        headers.forEach { raw ->
            val segments = raw.split(';')
            val first = segments.firstOrNull()?.trim().orEmpty()
            val eq = first.indexOf('=')
            if (eq > 0) {
                val name = first.substring(0, eq).trim()
                val value = first.substring(eq + 1).trim()
                val maxAgeZero = segments.drop(1).any { it.trim().equals("Max-Age=0", ignoreCase = true) }
                if (name.isNotBlank()) {
                    if (value.isBlank() || value.equals("deleteMe", ignoreCase = true) || maxAgeZero) {
                        target.remove(name)
                    } else {
                        target[name] = value
                    }
                }
            }
        }
    }

    private fun extractCookie(headers: List<String>, name: String): String {
        var matched = ""
        for (raw in headers) {
            val segments = raw.split(';')
            val first = segments.firstOrNull()?.trim().orEmpty()
            val eq = first.indexOf('=')
            if (eq <= 0 || first.substring(0, eq).trim() != name) continue
            val value = first.substring(eq + 1).trim()
            val maxAgeZero = segments.drop(1).any { it.trim().equals("Max-Age=0", ignoreCase = true) }
            if (value.isNotBlank() && !value.equals("deleteMe", ignoreCase = true) && !maxAgeZero) {
                matched = value
            }
        }
        return matched
    }

    private fun summarizeCookies(headers: List<String>): String =
        headers.joinToString(",") { raw ->
            val first = raw.substringBefore(';').trim()
            val name = first.substringBefore('=')
            val value = first.substringAfter('=', "")
            "$name(len=${value.length},del=${value.equals("deleteMe", ignoreCase = true)})"
        }

    private fun urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8")

    companion object {
        const val DEFAULT_BASE_URL = "https://application.xiaofubao.com"
        const val DEFAULT_AUTH_BASE_URL = "https://auth.xiaofubao.com"
        const val DEFAULT_YM_APP_ID = "1810181825222034"
        const val APP_WEBVIEW_UA =
            "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36 yunmaapp.NET/7.9.2"
        private const val MAX_AUTH_HOPS = 4
        private val YM_CODE_REGEX = Regex("""(?:^|[?&#])ymCode=([^&#"'\s<>]+)""")
        private val JS_REPLACE_REGEX = Regex("""window\.location\.replace\(\s*['"]([^'"]+)['"]\s*\)""")
        private val HTML_DIV_REGEX = Regex("""<div[^>]*>([^<]+)</div>""")
    }
}

/**
 * 浴室用水码。
 *
 * 实测为 **8 位**数字；响应里的 `numLength` 是"设备编号长度"，与码长无关，勿混用。
 * [longTerm] / [longTermFlag] 为 `"1"` 表示长期有效，`"0"` 时 [validSeconds] 单位为天，其余单位为分钟。
 */
data class WaterCode(
    val code: String,
    val validSeconds: Int,
    val remainTimes: Int,
    val intervalSeconds: Int,
    val usedTimes: Int,
    val longTerm: Boolean,
    val fetchedAt: Long,
    val longTermFlag: String = if (longTerm) "1" else "-1",
) {
    /** 剩余有效秒数（基于获取时刻推算，仅用于兼容既有调用与倒计时测试）。 */
    fun remainingSeconds(nowMillis: Long = System.currentTimeMillis()): Int =
        if (longTerm) Int.MAX_VALUE
        else (validSeconds - (nowMillis - fetchedAt) / 1000).toInt().coerceAtLeast(0)

    /** 官方前端同款有效期文案。 */
    fun validityText(): String = when {
        longTerm || longTermFlag == "1" -> "长期有效"
        longTermFlag == "0" && validSeconds > 0 -> "有效 $validSeconds 天"
        validSeconds > 0 -> "有效 $validSeconds 分钟"
        else -> ""
    }

    /** 组合有效期与可用/可取次数提示。 */
    fun summaryText(): String {
        val countHint = when {
            usedTimes > 0 -> "今日可取 $usedTimes 次"
            remainTimes > 0 -> "可用 $remainTimes 次"
            else -> ""
        }
        return listOf(validityText(), countHint)
            .filter { it.isNotBlank() }
            .joinToString(" · ")
    }
}
