package com.hmu.muye.data.campus

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

sealed interface PortalAuthResult {
    data class Authenticated(val message: String) : PortalAuthResult
    data class NeedConfig(val message: String) : PortalAuthResult
    data class Failed(val message: String) : PortalAuthResult
}

/**
 * 龙子湖校区校园网 Portal 探测与一键认证客户端。
 * - 支持通过 http://captive.apple.com 探测当前 Wi-Fi 是否已被认证放行；
 * - 若配置了认证 URL 与学号密码，可执行 POST 认证（具体字段名待 Phase 2 按校网认证页实采对齐）。
 */
class CampusPortalClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .followRedirects(false)
        .build(),
) {

    /** 返回 Pair(是否已放行外网, 如被劫持则返回跳转的认证页 URL) */
    suspend fun probeCaptivePortal(): Pair<Boolean, String?> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url("http://captive.apple.com/hotspot-detect.html")
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.code in 300..399) {
                    val loc = resp.header("Location")
                    return@use false to loc
                }
                val body = resp.body?.string().orEmpty()
                if (resp.isSuccessful && body.contains("Success", ignoreCase = true)) {
                    true to null
                } else {
                    false to null
                }
            }
        }.getOrDefault(false to null)
    }

    suspend fun authenticate(config: PortalConfig, password: String): PortalAuthResult = withContext(Dispatchers.IO) {
        val (online, redirectUrl) = probeCaptivePortal()
        if (online) return@withContext PortalAuthResult.Authenticated("当前网络已放行，无需重复认证")

        val targetUrl = config.authUrl.ifBlank { redirectUrl.orEmpty() }
        if (targetUrl.isBlank() || config.account.isBlank() || password.isBlank()) {
            return@withContext PortalAuthResult.NeedConfig(
                if (redirectUrl != null) "检测到认证页地址，请先补全校网账号与密码"
                else "请先配置龙子湖校园网认证地址及账号密码",
            )
        }

        val fullUser = if (config.ispSuffix.isBlank()) config.account else "${config.account}${config.ispSuffix}"
        runCatching {
            val form = FormBody.Builder()
                .add("userId", fullUser)
                .add("password", password)
                .build()
            val req = Request.Builder().url(targetUrl).post(form).build()
            client.newCall(req).execute().use { resp ->
                val (nowOnline, _) = probeCaptivePortal()
                if (nowOnline) {
                    PortalAuthResult.Authenticated("校园网认证成功")
                } else {
                    PortalAuthResult.Failed("已发送认证请求（HTTP ${resp.code}），若未放行请按 API_CAPTURE_GUIDE 核对表单字段")
                }
            }
        }.getOrElse { e ->
            PortalAuthResult.Failed("认证连接失败：${e.message ?: "请确认已连接校园网 Wi-Fi"}")
        }
    }
}
