package com.microqatar.salahclock

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class DayForecast(val date: String, val code: Int, val max: Double, val min: Double)

data class WeatherData(
    val temp: Double,
    val feels: Double,
    val humidity: Int,
    val wind: Double,
    val code: Int,
    val isDay: Boolean,
    val daily: List<DayForecast>,
    val fahrenheit: Boolean,
    val fetchedAt: Long
)

data class CityResult(val name: String, val admin: String, val country: String, val lat: Double, val lon: Double) {
    val label: String get() = listOf(name, admin, country).filter { it.isNotBlank() }.distinct().joinToString(", ")
    val shortLabel: String get() = listOf(name, country).filter { it.isNotBlank() }.joinToString(", ")
}

/** Free Open-Meteo API (no API key needed). All calls are blocking: run off the main thread. */
object WeatherClient {

    fun fetch(lat: Double, lon: Double, fahrenheit: Boolean): WeatherData {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,wind_speed_10m,is_day" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=4" +
            if (fahrenheit) "&temperature_unit=fahrenheit" else ""
        val json = JSONObject(get(url))
        val c = json.getJSONObject("current")
        val d = json.getJSONObject("daily")
        val times = d.getJSONArray("time")
        val codes = d.getJSONArray("weather_code")
        val maxs = d.getJSONArray("temperature_2m_max")
        val mins = d.getJSONArray("temperature_2m_min")
        val days = (0 until times.length()).map {
            DayForecast(times.getString(it), codes.optInt(it), maxs.optDouble(it), mins.optDouble(it))
        }
        return WeatherData(
            temp = c.optDouble("temperature_2m"),
            feels = c.optDouble("apparent_temperature"),
            humidity = c.optInt("relative_humidity_2m"),
            wind = c.optDouble("wind_speed_10m"),
            code = c.optInt("weather_code"),
            isDay = c.optInt("is_day", 1) == 1,
            daily = days,
            fahrenheit = fahrenheit,
            fetchedAt = System.currentTimeMillis()
        )
    }

    fun searchCity(query: String): List<CityResult> {
        val url = "https://geocoding-api.open-meteo.com/v1/search?name=" +
            URLEncoder.encode(query, "UTF-8") + "&count=10&language=en&format=json"
        val arr = JSONObject(get(url)).optJSONArray("results") ?: return emptyList()
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            CityResult(
                o.optString("name"), o.optString("admin1"), o.optString("country"),
                o.optDouble("latitude"), o.optDouble("longitude")
            )
        }
    }

    private fun get(u: String): String {
        val conn = URL(u).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("User-Agent", "SalahClock/1.0 (Android)")
        try {
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    fun describe(code: Int): String = when (code) {
        0 -> "Clear sky"
        1 -> "Mainly clear"
        2 -> "Partly cloudy"
        3 -> "Overcast"
        45, 48 -> "Fog"
        51, 53, 55 -> "Drizzle"
        56, 57 -> "Freezing drizzle"
        61, 63, 65 -> "Rain"
        66, 67 -> "Freezing rain"
        71, 73, 75, 77 -> "Snow"
        80, 81, 82 -> "Rain showers"
        85, 86 -> "Snow showers"
        95 -> "Thunderstorm"
        96, 99 -> "Thunderstorm, hail"
        else -> "—"
    }

    fun icon(code: Int, isDay: Boolean = true): String = when (code) {
        0 -> if (isDay) "☀️" else "🌙"
        1 -> if (isDay) "🌤️" else "🌙"
        2 -> "⛅"
        3 -> "☁️"
        45, 48 -> "🌫️"
        51, 53, 55, 56, 57, 80, 81, 82 -> "🌦️"
        61, 63, 65, 66, 67 -> "🌧️"
        71, 73, 75, 77, 85, 86 -> "❄️"
        95, 96, 99 -> "⛈️"
        else -> "🌡️"
    }
}
