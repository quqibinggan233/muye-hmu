package com.hmu.muye.data.campus

import kotlinx.serialization.Serializable

/** 校区选项：优先打通龙子湖校区，预留英才/北林扩展 */
@Serializable
enum class HmuCampus(val label: String) {
    LONGZIHU("龙子湖校区"),
    YINGCAI("英才校区"),
    BEILIN("北林校区"),
}

/** 龙子湖校园网 Portal 配置与认证状态（不含明文密码） */
@Serializable
data class PortalConfig(
    val authUrl: String = "",
    val account: String = "",
    val ispSuffix: String = "",
    val autoAuth: Boolean = false,
    val lastAuthAt: Long = 0L,
    val lastSuccess: Boolean = false,
)

/** 易校园一卡通与浴室 8 位动态用水码本地状态（不含宿舍电费） */
@Serializable
data class YixiaoyuanState(
    val hasCredential: Boolean = false,
    val ymId: String = "",
    val cardBalanceYuan: Double? = null,
    val lowBalanceThresholdYuan: Double = 10.0,
    val showerCode: String = "",
    val showerCodeAt: Long = 0L,
    val showBalanceCard: Boolean = true,
) {
    val isLowBalance: Boolean
        get() = cardBalanceYuan != null && cardBalanceYuan < lowBalanceThresholdYuan

    fun formattedShowerCode(): String = when (showerCode.length) {
        8 -> "${showerCode.take(4)} ${showerCode.drop(4)}"
        6 -> "${showerCode.take(3)} ${showerCode.drop(3)}"
        else -> showerCode
    }
}

/** 合肥海鸟浴室吹风机扫码收藏书签 */
@Serializable
data class HairdryerBookmark(
    val id: String,
    val label: String,
    val qrPayload: String,
)

/** 用户自定义校园快捷方式（支持跳转 URL / App 深链） */
@Serializable
data class QuickShortcut(
    val id: String,
    val title: String,
    val subtitle: String,
    val targetUri: String,
    val builtIn: Boolean = false,
)

/** 上课提醒本地偏好 */
@Serializable
data class ClassReminderPrefs(
    val enabled: Boolean = false,
    val beforeMinutes: Int = 10,
    val atClassStart: Boolean = false,
)
