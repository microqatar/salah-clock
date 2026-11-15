package com.microqatar.salahclock

import java.util.Calendar
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.acos
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

/** Calculation authority: Fajr angle and Isha angle (or fixed minutes after Maghrib). */
data class CalcMethod(
    val id: String,
    val name: String,
    val fajrAngle: Double,
    val ishaAngle: Double? = null,
    val ishaMinutes: Int? = null
)

/**
 * The four Sunni madhabs. Only Asr differs:
 *  - Hanafi: shadow = 2 x object length (later Asr)
 *  - Maliki, Shafi'i, Hanbali: shadow = 1 x object length
 */
enum class Madhab(val key: String, val label: String, val labelAr: String, val asrFactor: Double) {
    HANAFI("hanafi", "Hanafi", "حنفي", 2.0),
    MALIKI("maliki", "Maliki", "مالكي", 1.0),
    SHAFII("shafii", "Shafi'i", "شافعي", 1.0),
    HANBALI("hanbali", "Hanbali", "حنبلي", 1.0);

    companion object {
        fun from(key: String?): Madhab = entries.firstOrNull { it.key == key } ?: SHAFII
    }
}

data class PrayerTime(val key: String, val time: Long)

data class PrayerDay(val prayers: List<PrayerTime>, val method: CalcMethod, val madhab: Madhab) {
    fun get(key: String): PrayerTime? = prayers.firstOrNull { it.key == key }
}

/** Astronomical prayer time calculation (PrayTimes.org algorithm, angle-based high-latitude rule). */
object PrayerCalculator {

    val METHODS = listOf(
        CalcMethod("mwl", "Muslim World League", 18.0, ishaAngle = 17.0),
        CalcMethod("isna", "ISNA (North America)", 15.0, ishaAngle = 15.0),
        CalcMethod("egypt", "Egyptian General Authority", 19.5, ishaAngle = 17.5),
        CalcMethod("makkah", "Umm al-Qura, Makkah", 18.5, ishaMinutes = 90),
        CalcMethod("karachi", "Univ. of Islamic Sciences, Karachi", 18.0, ishaAngle = 18.0),
        CalcMethod("dubai", "UAE (IACAD Dubai)", 18.2, ishaAngle = 18.2),
        CalcMethod("qatar", "Qatar (Awqaf)", 18.0, ishaMinutes = 90),
        CalcMethod("kuwait", "Kuwait", 18.0, ishaAngle = 17.5),
        CalcMethod("singapore", "Singapore / Malaysia", 20.0, ishaAngle = 18.0),
        CalcMethod("turkey", "Diyanet, Turkey", 18.0, ishaAngle = 17.0),
        CalcMethod("morocco", "Morocco", 19.0, ishaAngle = 17.0),
        CalcMethod("france", "UOIF, France", 12.0, ishaAngle = 12.0),
        CalcMethod("russia", "Russia", 16.0, ishaAngle = 15.0),
    )

    fun resolveMethod(id: String, tzId: String): CalcMethod {
        val key = if (id == "auto") autoMethodFor(tzId) else id
        return METHODS.firstOrNull { it.id == key } ?: METHODS[0]
    }

    private fun autoMethodFor(tz: String): String = when {
        tz == "Asia/Qatar" -> "qatar"
        tz == "Asia/Dubai" || tz == "Asia/Muscat" -> "dubai"
        tz == "Asia/Riyadh" || tz == "Asia/Aden" -> "makkah"
        tz == "Asia/Kuwait" || tz == "Asia/Bahrain" -> "kuwait"
        tz in setOf("Asia/Karachi", "Asia/Kolkata", "Asia/Calcutta", "Asia/Dhaka", "Asia/Kabul") -> "karachi"
        tz == "Africa/Cairo" -> "egypt"
        tz == "Europe/Istanbul" -> "turkey"
        tz == "Africa/Casablanca" -> "morocco"
        tz in setOf("Asia/Singapore", "Asia/Kuala_Lumpur", "Asia/Jakarta", "Asia/Brunei") -> "singapore"
        tz == "Europe/Paris" -> "france"
        tz == "Europe/Moscow" -> "russia"
        tz.startsWith("America/") -> "isna"
        else -> "mwl"
    }

    /** Returns local clock hours for: fajr, sunrise, dhuhr, asr, maghrib, isha. */
    fun compute(
        year: Int, month: Int, day: Int,
        lat: Double, lng: Double, tzHours: Double,
        method: CalcMethod, asrFactor: Double
    ): DoubleArray {
        val jDate = julian(year, month, day) - lng / (15.0 * 24.0)
        val riseSet = 0.833

        var fajr = sunAngleTime(jDate, lat, method.fajrAngle, 5.0 / 24, true)
        val sunrise0 = sunAngleTime(jDate, lat, riseSet, 6.0 / 24, true)
        val dhuhr0 = midDay(jDate, 12.0 / 24)
        val asr0 = asrTime(jDate, lat, asrFactor, 13.0 / 24)
        val sunset0 = sunAngleTime(jDate, lat, riseSet, 18.0 / 24, false)
        var isha = method.ishaAngle?.let { sunAngleTime(jDate, lat, it, 18.0 / 24, false) } ?: Double.NaN

        val shift = tzHours - lng / 15.0
        fajr += shift
        val sunrise = sunrise0 + shift
        val dhuhr = dhuhr0 + shift
        val asr = asr0 + shift
        val sunset = sunset0 + shift
        isha += shift

        // High-latitude safety (angle-based rule)
        val night = timeDiff(sunset, sunrise)
        val fajrPortion = method.fajrAngle / 60.0 * night
        if (fajr.isNaN() || timeDiff(fajr, sunrise) > fajrPortion) fajr = sunrise - fajrPortion
        val ishaAngle = method.ishaAngle
        if (ishaAngle != null) {
            val portion = ishaAngle / 60.0 * night
            if (isha.isNaN() || timeDiff(sunset, isha) > portion) isha = sunset + portion
        }

        val maghrib = sunset
        val ishaMinutes = method.ishaMinutes
        if (ishaMinutes != null) isha = maghrib + ishaMinutes / 60.0

        return doubleArrayOf(fajr, sunrise, dhuhr, asr, maghrib, isha)
    }

    // ---- astronomy ----
    private fun julian(y: Int, m: Int, d: Int): Double {
        var year = y
        var month = m
        if (month <= 2) { year -= 1; month += 12 }
        val a = floor(year / 100.0)
        val b = 2 - a + floor(a / 4.0)
        return floor(365.25 * (year + 4716)) + floor(30.6001 * (month + 1)) + d + b - 1524.5
    }

    /** Returns (declination, equation of time). */
    private fun sunPosition(jd: Double): Pair<Double, Double> {
        val d = jd - 2451545.0
        val g = fixAngle(357.529 + 0.98560028 * d)
        val q = fixAngle(280.459 + 0.98564736 * d)
        val l = fixAngle(q + 1.915 * dsin(g) + 0.020 * dsin(2 * g))
        val e = 23.439 - 0.00000036 * d
        val ra = darctan2(dcos(e) * dsin(l), dcos(l)) / 15.0
        val eqt = q / 15.0 - fixHour(ra)
        val decl = darcsin(dsin(e) * dsin(l))
        return decl to eqt
    }

    private fun midDay(jDate: Double, t: Double): Double = fixHour(12 - sunPosition(jDate + t).second)

    private fun sunAngleTime(jDate: Double, lat: Double, angle: Double, t: Double, ccw: Boolean): Double {
        val decl = sunPosition(jDate + t).first
        val noon = midDay(jDate, t)
        val v = darccos((-dsin(angle) - dsin(decl) * dsin(lat)) / (dcos(decl) * dcos(lat))) / 15.0
        return noon + if (ccw) -v else v
    }

    private fun asrTime(jDate: Double, lat: Double, factor: Double, t: Double): Double {
        val decl = sunPosition(jDate + t).first
        val angle = -darccot(factor + dtan(abs(lat - decl)))
        return sunAngleTime(jDate, lat, angle, t, false)
    }

    private fun dsin(d: Double) = sin(Math.toRadians(d))
    private fun dcos(d: Double) = cos(Math.toRadians(d))
    private fun dtan(d: Double) = tan(Math.toRadians(d))
    private fun darcsin(x: Double) = Math.toDegrees(asin(x))
    private fun darccos(x: Double) = Math.toDegrees(acos(x))
    private fun darctan2(y: Double, x: Double) = Math.toDegrees(atan2(y, x))
    private fun darccot(x: Double) = Math.toDegrees(atan(1.0 / x))
    private fun fix(a: Double, b: Double): Double { val r = a - b * floor(a / b); return if (r < 0) r + b else r }
    private fun fixAngle(a: Double) = fix(a, 360.0)
    private fun fixHour(a: Double) = fix(a, 24.0)
    private fun timeDiff(t1: Double, t2: Double) = fixHour(t2 - t1)
}

/** Builds a PrayerDay for a date using the saved settings. */
object PrayerRepository {
    val KEYS = listOf("fajr", "sunrise", "dhuhr", "asr", "maghrib", "isha")

    fun compute(prefs: Prefs, day: Calendar): PrayerDay {
        val tz = TimeZone.getDefault()
        val y = day.get(Calendar.YEAR)
        val m = day.get(Calendar.MONTH) + 1
        val d = day.get(Calendar.DAY_OF_MONTH)

        val midnight = Calendar.getInstance(tz).apply { clear(); set(y, m - 1, d, 0, 0, 0) }.timeInMillis
        val tzHours = tz.getOffset(midnight + 12 * 3_600_000L) / 3_600_000.0

        val method = PrayerCalculator.resolveMethod(prefs.calcMethod, tz.id)
        val madhab = Madhab.from(prefs.madhab)
        val hours = PrayerCalculator.compute(y, m, d, prefs.lat, prefs.lon, tzHours, method, madhab.asrFactor)

        val prayers = KEYS.mapIndexed { i, key ->
            val h = hours[i]
            if (h.isNaN()) PrayerTime(key, -1L)
            else {
                val minutes = floor(h * 60.0 + 0.5).toLong() + prefs.adjust(key)
                PrayerTime(key, midnight + minutes * 60_000L)
            }
        }
        return PrayerDay(prayers, method, madhab)
    }
}

object PrayerNames {
    fun en(key: String, friday: Boolean = false) = when (key) {
        "fajr" -> "Fajr"
        "sunrise" -> "Sunrise"
        "dhuhr" -> if (friday) "Jumu'ah" else "Dhuhr"
        "asr" -> "Asr"
        "maghrib" -> "Maghrib"
        "isha" -> "Isha"
        else -> key
    }

    fun ar(key: String, friday: Boolean = false) = when (key) {
        "fajr" -> "الفجر"
        "sunrise" -> "الشروق"
        "dhuhr" -> if (friday) "الجمعة" else "الظهر"
        "asr" -> "العصر"
        "maghrib" -> "المغرب"
        "isha" -> "العشاء"
        else -> key
    }
}
