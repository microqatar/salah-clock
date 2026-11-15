package com.microqatar.salahclock

import android.content.Context
import android.net.Uri
import androidx.preference.PreferenceManager
import java.util.Locale

/** Typed access to all admin-panel settings (default SharedPreferences). */
class Prefs(context: Context) {
    val sp = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)!!

    companion object {
        const val DEFAULT_LAT = 25.2854   // Doha
        const val DEFAULT_LON = 51.5310
        const val DEFAULT_CITY = "Doha, Qatar"

        const val K_SOUND = "azan_sound_uri"
        const val K_FAJR_SOUND = "azan_fajr_sound_uri"
    }

    private fun str(key: String, def: String) = sp.getString(key, def) ?: def

    // ---- location ----
    val locationMode get() = str("location_mode", "auto")
    val manualLat get() = sp.getString("manual_lat", null)?.trim()?.toDoubleOrNull()
    val manualLon get() = sp.getString("manual_lon", null)?.trim()?.toDoubleOrNull()
    val manualCity get() = str("manual_city", "")

    var autoLat: Double?
        get() = sp.getString("auto_lat", null)?.toDoubleOrNull()
        set(v) = sp.edit().putString("auto_lat", v?.toString()).apply()
    var autoLon: Double?
        get() = sp.getString("auto_lon", null)?.toDoubleOrNull()
        set(v) = sp.edit().putString("auto_lon", v?.toString()).apply()
    var autoCity: String
        get() = str("auto_city", "")
        set(v) = sp.edit().putString("auto_city", v).apply()

    private val isManual get() = locationMode == "manual" && manualLat != null && manualLon != null

    val lat: Double get() = if (isManual) manualLat!! else autoLat ?: manualLat ?: DEFAULT_LAT
    val lon: Double get() = if (isManual) manualLon!! else autoLon ?: manualLon ?: DEFAULT_LON

    val cityName: String
        get() {
            if (isManual) return manualCity.ifBlank { coords(lat, lon) }
            if (autoLat == null) return if (manualLat != null) manualCity.ifBlank { coords(lat, lon) } else "$DEFAULT_CITY (default)"
            return autoCity.ifBlank { coords(lat, lon) }
        }

    private fun coords(a: Double, b: Double) = String.format(Locale.US, "%.3f, %.3f", a, b)

    // ---- prayer ----
    val madhab get() = str("madhab", "shafii")
    val calcMethod get() = str("calc_method", "auto")
    fun adjust(key: String) = sp.getString("adj_$key", "0")?.trim()?.toIntOrNull() ?: 0
    val hijriOffset get() = sp.getString("hijri_offset", "0")?.toIntOrNull() ?: 0

    // ---- azan ----
    val azanEnabled get() = sp.getBoolean("azan_enabled", true)
    fun azanFor(key: String) = key != "sunrise" && azanEnabled && sp.getBoolean("azan_$key", true)
    val azanVolume get() = sp.getInt("azan_volume", 100)
    fun soundUri(prayer: String): Uri? {
        val s = if (prayer == "fajr") sp.getString(K_FAJR_SOUND, null) ?: sp.getString(K_SOUND, null)
        else sp.getString(K_SOUND, null)
        return s?.let { Uri.parse(it) }
    }

    // ---- display ----
    val clockStyle get() = str("clock_style", "both")
    val use24h get() = sp.getBoolean("use_24h", false)
    val showSeconds get() = sp.getBoolean("show_seconds", true)
    val fahrenheit get() = str("temp_unit", "c") == "f"
    val bgTheme get() = str("bg_theme", "midnight")
    val accent get() = str("accent_color", "gold")
    val keepScreenOn get() = sp.getBoolean("keep_screen_on", true)
    val showWeather get() = sp.getBoolean("show_weather", true)

    // ---- security / system ----
    val adminPin get() = str("admin_pin", "1234")
    val startOnBoot get() = sp.getBoolean("start_on_boot", false)
    var hintShown: Boolean
        get() = sp.getBoolean("hint_shown", false)
        set(v) = sp.edit().putBoolean("hint_shown", v).apply()
}
