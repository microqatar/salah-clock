package com.microqatar.salahclock

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.widget.TextViewCompat
import androidx.preference.PreferenceManager
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    companion object {
        private const val LONG_PRESS_MS = 1500L
        private const val WEATHER_REFRESH_MS = 30 * 60_000L
        private const val WEATHER_RETRY_MS = 2 * 60_000L
    }

    private lateinit var prefs: Prefs
    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    // views
    private lateinit var root: View
    private lateinit var tvCity: TextView
    private lateinit var tvMadhab: TextView
    private lateinit var tvGregorian: TextView
    private lateinit var tvHijri: TextView
    private lateinit var analogClock: AnalogClockView
    private lateinit var digitalArea: View
    private lateinit var tvTime: TextView
    private lateinit var tvSubTime: TextView
    private lateinit var weatherCard: View
    private lateinit var tvWeatherIcon: TextView
    private lateinit var tvTemp: TextView
    private lateinit var tvWeatherDesc: TextView
    private lateinit var tvWeatherDetails: TextView
    private lateinit var forecastContainer: LinearLayout
    private lateinit var nextCard: View
    private lateinit var tvNextLabel: TextView
    private lateinit var tvCountdown: TextView
    private lateinit var prayerContainer: LinearLayout
    private lateinit var tvFooter: TextView
    private lateinit var azanBanner: TextView

    private class PrayerRow(
        val key: String, val view: LinearLayout,
        val name: TextView, val arabic: TextView, val time: TextView, val icon: TextView
    )
    private class ForecastCol(val day: TextView, val icon: TextView, val range: TextView)

    private val rows = mutableListOf<PrayerRow>()
    private val forecastCols = mutableListOf<ForecastCol>()

    // state
    private var today: PrayerDay? = null
    private var tomorrow: PrayerDay? = null
    private var dayKey = ""
    private var highlightedKey: String? = "-"
    private var weather: WeatherData? = null
    private var weatherLat = Double.NaN
    private var weatherLon = Double.NaN
    private var weatherLoading = false
    private var nextWeatherAttempt = 0L
    private var lastLocationFetch = 0L
    private var permissionAsked = false

    private var accent = 0xFFE6B450.toInt()
    private val textColor = 0xFFF2F4F8.toInt()
    private val dimColor = 0xFF9AA4B2.toInt()

    private lateinit var timeFmt: SimpleDateFormat
    private lateinit var prayerFmt: SimpleDateFormat
    private val amPmFmt = SimpleDateFormat("a", Locale.getDefault())
    private val dateFmt = SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault())
    private val dayNameFmt = SimpleDateFormat("EEE", Locale.getDefault())
    private val isoFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    // long-press detection (anywhere on screen)
    private var touchSlop = 0
    private var downX = 0f
    private var downY = 0f
    private var longPressFired = false
    private val longPressRunnable = Runnable {
        longPressFired = true
        openAdmin()
    }

    private val ticker = object : Runnable {
        override fun run() {
            tick()
            val now = System.currentTimeMillis()
            handler.postDelayed(this, 1000L - now % 1000L + 10L)
        }
    }

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result[Manifest.permission.ACCESS_COARSE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_FINE_LOCATION] == true
        ) {
            lastLocationFetch = 0L
            fetchDeviceLocation()
        }
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PreferenceManager.setDefaultValues(this, R.xml.preferences, false)
        prefs = Prefs(this)
        setContentView(R.layout.activity_main)
        bindViews()
        buildPrayerRows()
        buildForecastColumns()
        touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        AzanService.ensureChannel(this)

        if (!prefs.hintShown) {
            Toast.makeText(
                this,
                "Tip: long-press anywhere (or hold OK on the TV remote) to open the admin panel. Default PIN: 1234",
                Toast.LENGTH_LONG
            ).show()
            prefs.hintShown = true
        }
        requestNeededPermissions()
    }

    override fun onResume() {
        super.onResume()
        applyDisplaySettings()
        hideSystemBars()
        recomputePrayers()
        resolveLocation()
        maybeFetchWeather()
        AzanScheduler.scheduleNext(this)
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
        handler.removeCallbacks(longPressRunnable)
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    // ------------------------------------------------------------------ setup

    private fun bindViews() {
        root = findViewById(R.id.root)
        tvCity = findViewById(R.id.tvCity)
        tvMadhab = findViewById(R.id.tvMadhab)
        tvGregorian = findViewById(R.id.tvGregorian)
        tvHijri = findViewById(R.id.tvHijri)
        analogClock = findViewById(R.id.analogClock)
        digitalArea = findViewById(R.id.digitalArea)
        tvTime = findViewById(R.id.tvTime)
        tvSubTime = findViewById(R.id.tvSubTime)
        weatherCard = findViewById(R.id.weatherCard)
        tvWeatherIcon = findViewById(R.id.tvWeatherIcon)
        tvTemp = findViewById(R.id.tvTemp)
        tvWeatherDesc = findViewById(R.id.tvWeatherDesc)
        tvWeatherDetails = findViewById(R.id.tvWeatherDetails)
        forecastContainer = findViewById(R.id.forecastContainer)
        nextCard = findViewById(R.id.nextCard)
        tvNextLabel = findViewById(R.id.tvNextLabel)
        tvCountdown = findViewById(R.id.tvCountdown)
        prayerContainer = findViewById(R.id.prayerContainer)
        tvFooter = findViewById(R.id.tvFooter)
        azanBanner = findViewById(R.id.azanBanner)
        tvWeatherDesc.text = "Loading weather…"
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    private fun autoText(gravity: Int, color: Int = textColor): AppCompatTextView =
        AppCompatTextView(this).apply {
            this.gravity = gravity
            maxLines = 1
            setTextColor(color)
            setPadding(dp(4), dp(2), dp(4), dp(2))
            TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                this, 6, 300, 1, TypedValue.COMPLEX_UNIT_SP
            )
        }

    private fun buildPrayerRows() {
        prayerContainer.removeAllViews()
        rows.clear()
        for (key in PrayerRepository.KEYS) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(1), dp(8), dp(1))
            }
            val name = autoText(Gravity.START or Gravity.CENTER_VERTICAL)
            val ar = autoText(Gravity.CENTER, dimColor)
            val time = autoText(Gravity.END or Gravity.CENTER_VERTICAL).apply { typeface = Typeface.DEFAULT_BOLD }
            val icon = autoText(Gravity.CENTER)
            row.addView(name, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 2.2f))
            row.addView(ar, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.8f))
            row.addView(time, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 2.3f))
            row.addView(icon, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.7f))
            prayerContainer.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            rows += PrayerRow(key, row, name, ar, time, icon)
        }
    }

    private fun buildForecastColumns() {
        forecastContainer.removeAllViews()
        forecastCols.clear()
        repeat(3) {
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val day = autoText(Gravity.CENTER, dimColor)
            val icon = autoText(Gravity.CENTER)
            val range = autoText(Gravity.CENTER)
            col.addView(day, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            col.addView(icon, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.4f))
            col.addView(range, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            forecastContainer.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
            forecastCols += ForecastCol(day, icon, range)
        }
    }

    private fun cardBg(color: Int = 0x1AFFFFFF) = GradientDrawable().apply {
        cornerRadius = dp(14).toFloat()
        setColor(color)
    }

    private fun applyDisplaySettings() {
        val bg = when (prefs.bgTheme) {
            "black" -> 0xFF000000.toInt()
            "emerald" -> 0xFF06261F.toInt()
            "maroon" -> 0xFF240A10.toInt()
            "slate" -> 0xFF1E2430.toInt()
            else -> 0xFF0B1220.toInt()
        }
        accent = when (prefs.accent) {
            "green" -> 0xFF3DDC97.toInt()
            "blue" -> 0xFF5AB0FF.toInt()
            "white" -> 0xFFFFFFFF.toInt()
            "orange" -> 0xFFFF9F43.toInt()
            else -> 0xFFE6B450.toInt()
        }
        root.setBackgroundColor(bg)
        window.decorView.setBackgroundColor(bg)
        weatherCard.background = cardBg()
        nextCard.background = cardBg()
        prayerContainer.background = cardBg()

        analogClock.accentColor = accent
        analogClock.showSeconds = prefs.showSeconds
        tvTime.setTextColor(accent)
        tvHijri.setTextColor(accent)
        tvCountdown.setTextColor(accent)

        when (prefs.clockStyle) {
            "digital" -> { analogClock.visibility = View.GONE; digitalArea.visibility = View.VISIBLE }
            "analog" -> { analogClock.visibility = View.VISIBLE; digitalArea.visibility = View.GONE }
            else -> { analogClock.visibility = View.VISIBLE; digitalArea.visibility = View.VISIBLE }
        }
        weatherCard.visibility = if (prefs.showWeather) View.VISIBLE else View.GONE

        timeFmt = SimpleDateFormat(if (prefs.use24h) "HH:mm" else "h:mm", Locale.getDefault())
        prayerFmt = SimpleDateFormat(if (prefs.use24h) "HH:mm" else "h:mm a", Locale.getDefault())

        if (prefs.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        highlightedKey = "-" // force row re-highlight with new colours
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    // ------------------------------------------------------------------ clock tick

    private fun tick() {
        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        if (keyFor(cal) != dayKey) recomputePrayers()

        tvTime.text = timeFmt.format(Date(now))
        val parts = mutableListOf<String>()
        if (prefs.showSeconds) parts += String.format(Locale.getDefault(), "%02d", cal.get(Calendar.SECOND))
        if (!prefs.use24h) parts += amPmFmt.format(Date(now))
        tvSubTime.text = if (parts.isEmpty()) " " else parts.joinToString("   ")

        updateNextPrayer(now)
        updateAzanBanner()
        maybeFetchWeather()
    }

    private fun keyFor(c: Calendar) = "${c.get(Calendar.YEAR)}-${c.get(Calendar.DAY_OF_YEAR)}"

    private fun recomputePrayers() {
        val cal = Calendar.getInstance()
        dayKey = keyFor(cal)
        val t = PrayerRepository.compute(prefs, cal)
        val tmr = (cal.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, 1) }
        today = t
        tomorrow = PrayerRepository.compute(prefs, tmr)

        val friday = cal.get(Calendar.DAY_OF_WEEK) == Calendar.FRIDAY
        for (r in rows) {
            val p = t.get(r.key)
            r.name.text = PrayerNames.en(r.key, friday)
            r.arabic.text = PrayerNames.ar(r.key, friday)
            r.time.text = if (p != null && p.time > 0) prayerFmt.format(Date(p.time)) else "--:--"
            r.icon.text = when {
                r.key == "sunrise" -> "☀"
                prefs.azanFor(r.key) -> "🔊"
                else -> "🔇"
            }
        }
        val now = System.currentTimeMillis()
        tvGregorian.text = dateFmt.format(Date(now))
        tvHijri.text = HijriHelper.format(now, prefs.hijriOffset)
        tvMadhab.text = "${t.madhab.label} · ${t.method.name}"
        tvCity.text = "📍 ${prefs.cityName}"
        highlightedKey = "-"
        updateNextPrayer(now)
    }

    private fun updateNextPrayer(now: Long) {
        val todayList = today?.prayers.orEmpty().filter { it.key != "sunrise" && it.time > 0 }
        val tomorrowList = tomorrow?.prayers.orEmpty().filter { it.key != "sunrise" && it.time > 0 }
        val nextToday = todayList.firstOrNull { it.time > now }
        val next = nextToday ?: tomorrowList.firstOrNull { it.time > now }

        val hKey = nextToday?.key
        if (hKey != highlightedKey) {
            highlightedKey = hKey
            for (r in rows) {
                val on = r.key == hKey
                r.view.background = if (on) cardBg((accent and 0x00FFFFFF) or 0x33000000) else null
                r.name.setTextColor(if (on) accent else textColor)
                r.time.setTextColor(if (on) accent else textColor)
            }
        }

        if (next == null) {
            tvNextLabel.text = "—"
            tvCountdown.text = ""
            return
        }
        val friday = Calendar.getInstance().apply { timeInMillis = next.time }.get(Calendar.DAY_OF_WEEK) == Calendar.FRIDAY
        val diff = next.time - now
        val h = diff / 3_600_000
        val m = (diff / 60_000) % 60
        val s = (diff / 1000) % 60
        tvNextLabel.text = "Next: ${PrayerNames.en(next.key, friday)}  ${PrayerNames.ar(next.key, friday)}"
        tvCountdown.text = String.format(Locale.getDefault(), "−%02d:%02d:%02d", h, m, s)
    }

    private fun updateAzanBanner() {
        if (AzanService.isPlaying) {
            val p = AzanService.currentPrayer ?: ""
            azanBanner.text = "🔊  Azan · ${PrayerNames.en(p)}  ${PrayerNames.ar(p)}\nTap anywhere to stop"
            azanBanner.visibility = View.VISIBLE
        } else if (azanBanner.visibility == View.VISIBLE) {
            azanBanner.visibility = View.GONE
        }
    }

    // ------------------------------------------------------------------ location & weather

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun requestNeededPermissions() {
        if (permissionAsked) return
        val needed = mutableListOf<String>()
        if (prefs.locationMode == "auto" && !hasLocationPermission()) {
            needed += Manifest.permission.ACCESS_COARSE_LOCATION
            needed += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) needed += Manifest.permission.POST_NOTIFICATIONS
        if (needed.isNotEmpty()) {
            permissionAsked = true
            permLauncher.launch(needed.toTypedArray())
        }
    }

    private fun resolveLocation() {
        tvCity.text = "📍 ${prefs.cityName}"
        if (prefs.locationMode == "auto" && hasLocationPermission()) fetchDeviceLocation()
    }

    private fun fetchDeviceLocation() {
        val now = System.currentTimeMillis()
        if (now - lastLocationFetch < WEATHER_REFRESH_MS) return
        lastLocationFetch = now
        LocationHelper.fetch(this) { loc ->
            if (loc == null || isDestroyed) return@fetch
            val oldLat = prefs.autoLat
            val oldLon = prefs.autoLon
            val moved = oldLat == null || oldLon == null ||
                abs(oldLat - loc.latitude) > 0.01 || abs(oldLon - loc.longitude) > 0.01
            prefs.autoLat = loc.latitude
            prefs.autoLon = loc.longitude
            if (moved || prefs.autoCity.isBlank()) {
                recomputePrayers()
                AzanScheduler.scheduleNext(this)
                maybeFetchWeather()
                val lat = loc.latitude
                val lon = loc.longitude
                io.execute {
                    val name = LocationHelper.reverseGeocode(applicationContext, lat, lon)
                    if (name != null) runOnUiThread {
                        prefs.autoCity = name
                        if (!isDestroyed) tvCity.text = "📍 ${prefs.cityName}"
                    }
                }
            }
        }
    }

    private fun maybeFetchWeather() {
        if (!prefs.showWeather || weatherLoading) return
        val now = System.currentTimeMillis()
        val lat = prefs.lat
        val lon = prefs.lon
        val f = prefs.fahrenheit
        val w = weather
        val stale = w == null || now - w.fetchedAt > WEATHER_REFRESH_MS || w.fahrenheit != f ||
            abs(lat - weatherLat) > 0.01 || abs(lon - weatherLon) > 0.01
        if (!stale || now < nextWeatherAttempt) return
        nextWeatherAttempt = now + 15_000L
        weatherLoading = true
        io.execute {
            val result = try { WeatherClient.fetch(lat, lon, f) } catch (e: Exception) { null }
            runOnUiThread {
                weatherLoading = false
                if (isDestroyed) return@runOnUiThread
                if (result != null) {
                    weather = result
                    weatherLat = lat
                    weatherLon = lon
                    renderWeather(result)
                } else {
                    nextWeatherAttempt = System.currentTimeMillis() + WEATHER_RETRY_MS
                    if (weather == null) tvWeatherDesc.text = "Weather unavailable (offline?)"
                }
            }
        }
    }

    private fun renderWeather(w: WeatherData) {
        val unit = if (w.fahrenheit) "°F" else "°C"
        tvWeatherIcon.text = WeatherClient.icon(w.code, w.isDay)
        tvTemp.text = "${w.temp.roundToInt()}$unit"
        tvWeatherDesc.text = WeatherClient.describe(w.code)
        tvWeatherDetails.text = "Feels ${w.feels.roundToInt()}°  ·  💧${w.humidity}%  ·  💨${w.wind.roundToInt()} km/h"
        forecastCols.forEachIndexed { i, col ->
            val d = w.daily.getOrNull(i + 1)
            if (d == null) {
                col.day.text = ""; col.icon.text = ""; col.range.text = ""
            } else {
                col.day.text = try { dayNameFmt.format(isoFmt.parse(d.date)!!) } catch (e: Exception) { d.date }
                col.icon.text = WeatherClient.icon(d.code)
                col.range.text = "${d.max.roundToInt()}° / ${d.min.roundToInt()}°"
            }
        }
    }

    // ------------------------------------------------------------------ admin access

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x; downY = ev.y
                longPressFired = false
                handler.removeCallbacks(longPressRunnable)
                handler.postDelayed(longPressRunnable, LONG_PRESS_MS)
            }
            MotionEvent.ACTION_MOVE -> {
                if (hypot(ev.x - downX, ev.y - downY) > touchSlop * 2) handler.removeCallbacks(longPressRunnable)
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longPressRunnable)
                if (longPressFired) return true
                if (AzanService.isPlaying) {
                    AzanService.stop(this)
                    azanBanner.visibility = View.GONE
                    return true
                }
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN ->
                handler.removeCallbacks(longPressRunnable)
        }
        return super.dispatchTouchEvent(ev)
    }

    // TV remote: hold OK/centre for admin, MENU/SETTINGS opens directly, OK stops azan.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                event.startTracking()
                return true
            }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS -> {
                openAdmin()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER ||
            keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
        ) {
            openAdmin()
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER ||
            keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
        ) {
            if (event.isTracking && !event.isCanceled && AzanService.isPlaying) AzanService.stop(this)
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private var pinDialog: AlertDialog? = null

    private fun openAdmin() {
        if (pinDialog?.isShowing == true) return
        val pin = prefs.adminPin
        if (pin.isEmpty()) {
            startActivity(Intent(this, AdminActivity::class.java))
            return
        }
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "PIN"
            gravity = Gravity.CENTER
            isSingleLine = true
        }
        val box = FrameLayout(this).apply {
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(input)
        }
        pinDialog = AlertDialog.Builder(this)
            .setTitle("Admin panel")
            .setMessage("Enter admin PIN")
            .setView(box)
            .setPositiveButton("Open") { _, _ ->
                if (input.text.toString() == pin) startActivity(Intent(this, AdminActivity::class.java))
                else Toast.makeText(this, "Wrong PIN", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .create()
        pinDialog?.setOnShowListener { input.requestFocus() }
        pinDialog?.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        pinDialog?.show()
    }
}
