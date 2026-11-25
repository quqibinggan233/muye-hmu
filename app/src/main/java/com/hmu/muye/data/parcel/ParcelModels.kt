package com.hmu.muye.data.parcel

import kotlinx.serialization.Serializable

@Serializable
data class ParcelItem(
    val id: String,
    val code: String,
    val station: String,
    val carrier: String = "",
    val rawText: String = "",
    val source: String = "自动识别",
    val receivedAt: Long = System.currentTimeMillis(),
    val pickedUp: Boolean = false,
)

@Serializable
data class CustomParcelRule(
    val id: String,
    val stationLabel: String,
    val pattern: String,
)
