package com.hmu.muye.data.yixiaoyuan

import kotlinx.serialization.Serializable

/**
 * 易校园凭据：登录后由服务端下发。
 *
 * - [token]    长生命周期会话令牌，用于 `token` 字段与静默登录
 * - [uuToken]  签名密钥原料，**仅**密码 / 短信验证码登录响应携带，静默登录不返回
 * - [ymId]     用户唯一标识（服务端返回的 `id`），签名算法会取其末两位数字当偏移
 * - [deviceId] 设备标识（`ym-` + 32 位十六进制），与账号绑定后不宜变更
 */
@Serializable
data class YixiaoyuanCredential(
    val token: String = "",
    val uuToken: String = "",
    val ymId: String = "",
    val deviceId: String = "",
    val schoolCode: String = DEFAULT_SCHOOL_CODE,
    val mobile: String = "",
) {
    val signedIn: Boolean get() = token.isNotBlank() && uuToken.isNotBlank() && ymId.isNotBlank()

    /** 派生签名密钥；未登录时返回空串。 */
    fun signKey(): String = YixiaoyuanSigner.deriveKey(ymId, deviceId, uuToken)

    companion object {
        /** 河南牧业经济学院（龙子湖校区）在易校园的学校编码 */
        const val DEFAULT_SCHOOL_CODE = "10469"

        /** 官方客户端上报的版本号，服务端会校验，改动前需重新抓包确认 */
        const val APP_VERSION = "740"
    }
}

/** 易校园接口调用结果 */
sealed interface YixiaoyuanResult<out T> {
    data class Success<T>(val data: T) : YixiaoyuanResult<T>

    /** 会话失效（statusCode 204 / 219），需重新登录换取 token + uuToken */
    data class SessionExpired(val message: String) : YixiaoyuanResult<Nothing>

    /** 业务或网络错误 */
    data class Error(val message: String, val statusCode: Int = -1) : YixiaoyuanResult<Nothing>
}
