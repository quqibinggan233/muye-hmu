package com.hmu.muye.data.ujing

data class UjingCategoryCount(
    val category: Int,
    val label: String,
    val available: Int,
    val total: Int,
)

data class UjingStoreSummary(
    val id: String,
    val name: String,
    val address: String = "",
    val distanceMeters: Int = 0,
    val categories: List<UjingCategoryCount> = emptyList(),
)

data class UjingDeviceInfo(
    val id: String,
    val name: String,
    val typeName: String = "",
    val statusText: String = "",
    val available: Boolean = false,
    val remainSeconds: Int = 0,
)

sealed interface UjingResult<out T> {
    data class Success<T>(val data: T) : UjingResult<T>
    data class SessionExpired(val reason: String = "U 净登录已过期，请重新验证") : UjingResult<Nothing>
    data class Error(val message: String) : UjingResult<Nothing>
}
