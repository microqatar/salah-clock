package com.microqatar.salahclock

import android.icu.util.IslamicCalendar

object HijriHelper {
    private val monthsEn = arrayOf(
        "Muharram", "Safar", "Rabi' al-Awwal", "Rabi' al-Thani", "Jumada al-Ula", "Jumada al-Akhirah",
        "Rajab", "Sha'ban", "Ramadan", "Shawwal", "Dhu al-Qi'dah", "Dhu al-Hijjah"
    )
    private val monthsAr = arrayOf(
        "محرم", "صفر", "ربيع الأول", "ربيع الآخر", "جمادى الأولى", "جمادى الآخرة",
        "رجب", "شعبان", "رمضان", "شوال", "ذو القعدة", "ذو الحجة"
    )

    /** e.g. "24 Jumada al-Ula 1448 AH  ·  ٢٤ جمادى الأولى" (Umm al-Qura calendar + manual offset). */
    fun format(millis: Long, offsetDays: Int): String = try {
        val cal = IslamicCalendar()
        cal.setCalculationType(IslamicCalendar.CalculationType.ISLAMIC_UMALQURA)
        cal.timeInMillis = millis + offsetDays * 86_400_000L
        val d = cal.get(android.icu.util.Calendar.DAY_OF_MONTH)
        val m = cal.get(android.icu.util.Calendar.MONTH).coerceIn(0, 11)
        val y = cal.get(android.icu.util.Calendar.YEAR)
        "$d ${monthsEn[m]} $y AH  ·  ${toArabicDigits(d)} ${monthsAr[m]} ${toArabicDigits(y)}"
    } catch (e: Exception) {
        ""
    }

    private fun toArabicDigits(n: Int): String =
        n.toString().map { if (it in '0'..'9') '٠' + (it - '0') else it }.joinToString("")
}
