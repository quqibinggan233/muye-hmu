package com.hmu.muye.data.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class WeatherRepositoryTest {

    @Test
    fun `maps open meteo weather codes cleanly`() {
        assertEquals("☀️" to "晴", WeatherRepository.describeWeatherCode(0))
        assertEquals("⛅" to "多云", WeatherRepository.describeWeatherCode(2))
        assertEquals("🌧️" to "雨", WeatherRepository.describeWeatherCode(61))
        assertEquals("❄️" to "雪", WeatherRepository.describeWeatherCode(73))
        assertEquals("⛈️" to "雷雨", WeatherRepository.describeWeatherCode(95))
    }

    @Test
    fun `parses open meteo json with upcoming rain advice`() {
        val json = """
            {
              "current": {
                "temperature_2m": 12.4,
                "weather_code": 2
              },
              "minutely_15": {
                "precipitation": [0.0, 0.2, 0.5, 0.0]
              }
            }
        """.trimIndent()
        val info = WeatherRepository.parseOpenMeteoJson(json, nowMs = 1000L)
        assertNotNull(info)
        assertEquals(12, info!!.temperatureC)
        assertEquals("多云", info.description)
        assertEquals("约15分钟后有雨", info.rainAdvice)
        assertEquals("⛅ 12° · 多云 · 郑州 · 约15分钟后有雨", info.headerLine)
    }

    @Test
    fun `returns null on invalid json`() {
        assertNull(WeatherRepository.parseOpenMeteoJson(""))
        assertNull(WeatherRepository.parseOpenMeteoJson("{bad"))
        assertNull(WeatherRepository.parseOpenMeteoJson("{}"))
    }
}
