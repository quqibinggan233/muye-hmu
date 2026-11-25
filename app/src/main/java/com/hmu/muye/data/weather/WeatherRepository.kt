package com.hmu.muye.data.weather

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

data class WeatherInfo(
    val emoji: String,
    val description: String,
    val temperatureC: Int,
    val rainAdvice: String = "",
    val fetchedAt: Long = System.currentTimeMillis(),
) {
    /** Compact display line for schedule header, e.g. "☀️ 12° · 晴 · 郑州" */
    val headerLine: String
        get() = listOf("$emoji ${temperatureC}°", description, "郑州", rainAdvice)
            .filter { it.isNotBlank() }
            .joinToString(" · ")
}

/**
 * 郑州龙子湖校区实时天气（Open-Meteo 免 Key 接口 + 30 分钟本地缓存）。
 * 网络不可用或解析失败时静默返回缓存或 null，不阻塞课表首帧渲染。
 */
class WeatherRepository(
    context: Context,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(5, TimeUnit.SECONDS)
        .build(),
) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun cached(): WeatherInfo? {
        val raw = prefs.getString(KEY_JSON, null) ?: return null
        val ts = prefs.getLong(KEY_TS, 0L)
        return runCatching {
            val obj = JSONObject(raw)
            WeatherInfo(
                emoji = obj.optString("e", "⛅"),
                description = obj.optString("d", "多云"),
                temperatureC = obj.optInt("t", 0),
                rainAdvice = obj.optString("r", ""),
                fetchedAt = ts,
            )
        }.getOrNull()
    }

    suspend fun getWeather(forceRefresh: Boolean = false): WeatherInfo? = withContext(Dispatchers.IO) {
        val cached = cached()
        val now = System.currentTimeMillis()
        if (!forceRefresh && cached != null && now - cached.fetchedAt < CACHE_TTL_MS) {
            return@withContext cached
        }
        val fresh = runCatching {
            client.newCall(Request.Builder().url(LONGZIHU_URL).build()).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                parseOpenMeteoJson(resp.body?.string().orEmpty(), now)
            }
        }.getOrNull()
        if (fresh != null) save(fresh)
        fresh ?: cached
    }

    private fun save(info: WeatherInfo) {
        val payload = JSONObject()
            .put("e", info.emoji)
            .put("d", info.description)
            .put("t", info.temperatureC)
            .put("r", info.rainAdvice)
            .toString()
        prefs.edit().putString(KEY_JSON, payload).putLong(KEY_TS, info.fetchedAt).apply()
    }

    companion object {
        private const val PREFS = "hmu_weather_cache"
        private const val KEY_JSON = "weather_json"
        private const val KEY_TS = "weather_ts"
        private const val CACHE_TTL_MS = 30 * 60 * 1000L

        // 河南牧业经济学院龙子湖校区坐标（郑州郑东新区龙子湖高校园区）
        const val LONGZIHU_LAT = 34.787
        const val LONGZIHU_LON = 113.800
        private const val LONGZIHU_URL =
            "https://api.open-meteo.com/v1/forecast" +
                "?latitude=$LONGZIHU_LAT&longitude=$LONGZIHU_LON" +
                "&current=temperature_2m,weather_code" +
                "&minutely_15=precipitation&forecast_minutely_15=8" +
                "&timezone=Asia%2FShanghai"

        fun describeWeatherCode(code: Int): Pair<String, String> = when (code) {
            0 -> "☀️" to "晴"
            1 -> "🌤️" to "晴间多云"
            2 -> "⛅" to "多云"
            3 -> "☁️" to "阴"
            45, 48 -> "🌫️" to "雾"
            in 51..57 -> "🌦️" to "毛毛雨"
            in 61..67, in 80..82 -> "🌧️" to "雨"
            in 71..77, in 85..86 -> "❄️" to "雪"
            in 95..99 -> "⛈️" to "雷雨"
            else -> "⛅" to "多云"
        }

        fun parseOpenMeteoJson(raw: String, nowMs: Long = System.currentTimeMillis()): WeatherInfo? {
            if (raw.isBlank()) return null
            val root = runCatching { JSONObject(raw) }.getOrNull() ?: return null
            val cur = root.optJSONObject("current") ?: return null
            val temp = cur.optDouble("temperature_2m", Double.NaN)
            if (temp.isNaN()) return null
            val code = cur.optInt("weather_code", 2)
            val (emoji, desc) = describeWeatherCode(code)

            val precip = root.optJSONObject("minutely_15")?.optJSONArray("precipitation")
            var firstRainIdx = -1
            if (precip != null) {
                val count = minOf(8, precip.length())
                for (i in 0 until count) {
                    if (precip.optDouble(i, 0.0) > 0.05) {
                        firstRainIdx = i
                        break
                    }
                }
            }
            val advice = when {
                firstRainIdx == 0 -> "记得带伞"
                firstRainIdx > 0 -> "约${firstRainIdx * 15}分钟后有雨"
                else -> ""
            }
            return WeatherInfo(
                emoji = emoji,
                description = desc,
                temperatureC = temp.roundToInt(),
                rainAdvice = advice,
                fetchedAt = nowMs,
            )
        }
    }
}
