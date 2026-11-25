package com.hmu.muye.data.yixiaoyuan

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher

/**
 * 易校园「校园卡 / 用户」接口客户端（compus.xiaofubao.com）。
 *
 * 只覆盖本项目需要的只读能力：登录换取凭据、查询一卡通余额。
 * 不包含充值、挂失、绑定银行卡等任何写操作；不接入宿舍电费。
 *
 * 签名规则见 [YixiaoyuanSigner]；登录接口本身不校验签名（实测空 sign 亦可通过），
 * 因此未登录时也能提交验证码换取 token / uuToken。
 */
class YixiaoyuanClient(
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build(),
    private val now: () -> Long = System::currentTimeMillis,
) {

    /**
     * 账号密码直连登录：先拉取服务端 RSA 公钥（`/login/getPublicKey`），
     * 用 `RSA/ECB/PKCS1Padding` + Base64 加密密码后提交 `/login/doLoginByPwd`，无需跳转官方 App。
     */
    fun loginByPassword(
        mobile: String,
        password: String,
        deviceId: String,
        schoolCode: String = YixiaoyuanCredential.DEFAULT_SCHOOL_CODE,
    ): YixiaoyuanResult<YixiaoyuanCredential> {
        val cleanMobile = mobile.trim()
        if (cleanMobile.isBlank() || password.isBlank()) {
            return YixiaoyuanResult.Error("请填写手机号与登录密码")
        }
        val keyBody = baseDeviceBody(deviceId, schoolCode)
        val keyReq = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/login/getPublicKey")
            .header("Content-Type", JSON_MEDIA.toString())
            .post(keyBody.toJson().toString().toRequestBody(JSON_MEDIA))
            .build()
        val publicKeyB64 = when (val keyRes = execute(keyReq)) {
            is YixiaoyuanResult.Error -> return keyRes
            is YixiaoyuanResult.SessionExpired -> return keyRes
            is YixiaoyuanResult.Success -> {
                keyRes.data.optJSONObject("data")?.optString("publicKey")?.trim().orEmpty()
            }
        }
        if (publicKeyB64.isBlank()) {
            return YixiaoyuanResult.Error("未获取到易校园登录公钥")
        }
        val cipherPwd = runCatching { encryptPasswordRsa(password, publicKeyB64) }.getOrElse { e ->
            return YixiaoyuanResult.Error("密码加密失败：${e.message ?: "密钥格式异常"}")
        }

        val loginBody = baseDeviceBody(deviceId, schoolCode).apply {
            put("mobilePhone", cleanMobile)
            put("password", cipherPwd)
        }
        val loginReq = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/login/doLoginByPwd")
            .header("Content-Type", JSON_MEDIA.toString())
            .post(loginBody.toJson().toString().toRequestBody(JSON_MEDIA))
            .build()
        return executeLoginRequest(loginReq, cleanMobile, deviceId, schoolCode)
    }

    /**
     * 短信验证码登录，成功后返回带 [YixiaoyuanCredential.uuToken] 的凭据。
     *
     * 注意：**发送**验证码（`/compus/user/sendLoginVerificationCode`）需要官方客户端
     * native 生成的 `securityToken` / `appSecurityToken` 与图形验证码，本客户端尚未实现，
     * 因此短信方式需在官方 App 内获取验证码后填入；建议优先使用上方的密码直连登录。
     */
    fun loginByVerificationCode(
        mobile: String,
        verificationCode: String,
        deviceId: String,
        schoolCode: String = YixiaoyuanCredential.DEFAULT_SCHOOL_CODE,
    ): YixiaoyuanResult<YixiaoyuanCredential> {
        val cleanMobile = mobile.trim()
        val body = baseDeviceBody(deviceId, schoolCode).apply {
            put("mobilePhone", cleanMobile)
            put("verificationCode", verificationCode.trim())
            put("linkUserId", "")
            put("invitationCode", "")
        }
        val req = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/login/doLoginByVerificationCode")
            .header("Content-Type", JSON_MEDIA.toString())
            .post(body.toJson().toString().toRequestBody(JSON_MEDIA))
            .build()
        return executeLoginRequest(req, cleanMobile, deviceId, schoolCode)
    }

    private fun baseDeviceBody(deviceId: String, schoolCode: String): LinkedHashMap<String, Any> = linkedMapOf(
        "appVersion" to YixiaoyuanCredential.APP_VERSION,
        "appAllVersion" to APP_ALL_VERSION,
        "appPlatform" to "Android",
        "osType" to "Android",
        "osVersion" to "15",
        "brand" to "google",
        "mobileType" to "sdk_gphone64_arm64",
        "platform" to PLATFORM,
        "deviceId" to deviceId,
        "schoolCode" to schoolCode,
        "token" to "",
        "nt" to now(),
    )

    private fun executeLoginRequest(
        req: Request,
        mobile: String,
        deviceId: String,
        schoolCode: String,
    ): YixiaoyuanResult<YixiaoyuanCredential> = when (val r = execute(req)) {
        is YixiaoyuanResult.Error -> r
        is YixiaoyuanResult.SessionExpired -> r
        is YixiaoyuanResult.Success -> {
            val data = r.data.optJSONObject("data")
                ?: return YixiaoyuanResult.Error("登录响应缺少 data")
            val credential = YixiaoyuanCredential(
                token = data.optString("token"),
                uuToken = data.optString("uuToken"),
                ymId = data.optString("id"),
                deviceId = deviceId,
                schoolCode = data.optString("schoolCode").ifBlank { schoolCode },
                mobile = mobile,
            )
            if (!credential.signedIn) {
                YixiaoyuanResult.Error("登录响应缺少 token / uuToken / id，可能学校或账号不支持")
            } else {
                YixiaoyuanResult.Success(credential)
            }
        }
    }

    private fun encryptPasswordRsa(plainPassword: String, publicKeyBase64: String): String {
        val cleaned = publicKeyBase64
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replace("\\s+".toRegex(), "")
        val keyBytes = Base64.getDecoder().decode(cleaned)
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(keyBytes))
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, publicKey)
        val encrypted = cipher.doFinal(plainPassword.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(encrypted)
    }

    /** 查询一卡通余额（单位：元）。服务端以字符串返回，例如 `"0"`、`"12.30"`。 */
    fun getCardMoney(credential: YixiaoyuanCredential): YixiaoyuanResult<Double> {
        if (!credential.signedIn) return YixiaoyuanResult.Error("尚未登录易校园")
        val body = commonBody(credential)
        return when (val r = signedPost("/compus/user/getCardMoney", body, credential)) {
            is YixiaoyuanResult.Error -> r
            is YixiaoyuanResult.SessionExpired -> r
            is YixiaoyuanResult.Success -> {
                val raw = r.data.optString("data")
                raw.trim().toDoubleOrNull()
                    ?.let { YixiaoyuanResult.Success(it) }
                    ?: YixiaoyuanResult.Error("余额字段解析失败：$raw")
            }
        }
    }

    /** 组合带签名的 POST：注入 `nt` 与 `token`，按 [YixiaoyuanSigner] 计算 `sign` 头。 */
    private fun signedPost(
        path: String,
        body: LinkedHashMap<String, Any>,
        credential: YixiaoyuanCredential,
    ): YixiaoyuanResult<JSONObject> {
        val payload = LinkedHashMap(body)
        payload["nt"] = now()
        payload["token"] = credential.token
        val key = credential.signKey()
        if (key.isBlank()) return YixiaoyuanResult.Error("签名密钥不可用，请重新登录")
        val params = payload.mapValues { it.value.toString() }
        val req = Request.Builder()
            .url(baseUrl.trimEnd('/') + path)
            .header("Content-Type", JSON_MEDIA.toString())
            .header("sign", YixiaoyuanSigner.sign(params, key))
            .post(payload.toJson().toString().toRequestBody(JSON_MEDIA))
            .build()
        return execute(req)
    }

    private fun commonBody(credential: YixiaoyuanCredential): LinkedHashMap<String, Any> = linkedMapOf(
        "appVersion" to YixiaoyuanCredential.APP_VERSION,
        "platform" to PLATFORM,
        "deviceId" to credential.deviceId,
        "ymId" to credential.ymId,
    )

    private fun execute(req: Request): YixiaoyuanResult<JSONObject> =
        runCatching {
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                val json = runCatching { JSONObject(text) }.getOrNull()
                    ?: return YixiaoyuanResult.Error("响应不是合法 JSON（HTTP ${resp.code}）：${text.take(120)}")
                when (json.optInt("statusCode", -1)) {
                    0 -> YixiaoyuanResult.Success(json)
                    204 -> YixiaoyuanResult.SessionExpired(json.optString("message", "请重新登录"))
                    219 -> YixiaoyuanResult.SessionExpired(json.optString("message", "鉴权失败"))
                    else -> YixiaoyuanResult.Error(
                        json.optString("message").ifBlank { "请求失败" },
                        json.optInt("statusCode", -1),
                    )
                }
            }
        }.getOrElse { YixiaoyuanResult.Error(it.message ?: "网络请求失败") }

    private fun LinkedHashMap<String, Any>.toJson(): JSONObject {
        val o = JSONObject()
        forEach { (k, v) -> o.put(k, v) }
        return o
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://compus.xiaofubao.com"
        private const val APP_ALL_VERSION = "7.9.2"
        private const val PLATFORM = "YUNMA_APP"
        private val JSON_MEDIA = "application/json; charset=UTF-8".toMediaType()

        /** 生成与官方客户端同格式的设备标识：`ym-` + 32 位十六进制 */
        fun newDeviceId(): String {
            val hex = (1..32)
                .map { "0123456789abcdef"[(Math.random() * 16).toInt()] }
                .joinToString("")
            return "ym-$hex"
        }
    }
}
