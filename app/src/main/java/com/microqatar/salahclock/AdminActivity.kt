package com.microqatar.salahclock

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import java.util.Locale
import kotlin.math.roundToInt

/** Admin panel: opened by long-pressing anywhere on the main screen (PIN protected). */
class AdminActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.apply {
            title = getString(R.string.admin_title)
            setDisplayHomeAsUpEnabled(true)
        }
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(android.R.id.content, SettingsFragment())
                .commit()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}

class SettingsFragment : PreferenceFragmentCompat(), SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var prefs: Prefs
    private var pickingFajr = false

    private val pickAudio = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        val ctx = context ?: return@registerForActivityResult
        if (uri == null) return@registerForActivityResult
        try {
            ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
        }
        val key = if (pickingFajr) Prefs.K_FAJR_SOUND else Prefs.K_SOUND
        prefs.sp.edit().putString(key, uri.toString()).apply()
        updateSoundSummaries()
        toast("Azan sound saved")
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)
        prefs = Prefs(requireContext())

        // numeric keyboards
        for (k in listOf("adj_fajr", "adj_sunrise", "adj_dhuhr", "adj_asr", "adj_maghrib", "adj_isha")) {
            findPreference<EditTextPreference>(k)?.setOnBindEditTextListener {
                it.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
                it.setSelection(it.text.length)
            }
        }
        for (k in listOf("manual_lat", "manual_lon")) {
            findPreference<EditTextPreference>(k)?.setOnBindEditTextListener {
                it.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED or
                    InputType.TYPE_NUMBER_FLAG_DECIMAL
                it.setSelection(it.text.length)
            }
        }
        findPreference<EditTextPreference>("admin_pin")?.apply {
            setOnBindEditTextListener {
                it.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            }
            summaryProvider = Preference.SummaryProvider<EditTextPreference> { p ->
                val t = p.text.orEmpty()
                if (t.isEmpty()) "No PIN – admin panel opens without asking"
                else "•".repeat(t.length) + "   (tap to change)"
            }
        }

        click("city_search") { showCitySearch() }
        click("use_device_location") {
            findPreference<ListPreference>("location_mode")?.value = "auto"
            toast("Using device location – it updates when you return to the clock")
        }
        click("pick_azan_sound") { pickSound(false) }
        click("pick_fajr_sound") { pickSound(true) }
        click("reset_azan_sound") {
            prefs.sp.edit().remove(Prefs.K_SOUND).remove(Prefs.K_FAJR_SOUND).apply()
            updateSoundSummaries()
            toast("Reset to default azan")
        }
        click("test_azan") { toggleTest("dhuhr") }
        click("test_fajr_azan") { toggleTest("fajr") }
        click("exact_alarm") {
            if (Build.VERSION.SDK_INT >= 31) {
                tryStart(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${requireContext().packageName}")))
            }
        }
        click("exit_app") {
            AzanService.stop(requireContext())
            requireActivity().finishAffinity()
        }

        findPreference<SwitchPreferenceCompat>("start_on_boot")?.setOnPreferenceChangeListener { _, v ->
            val ctx = requireContext()
            if (v == true && Build.VERSION.SDK_INT >= 29 && !Settings.canDrawOverlays(ctx)) {
                toast("Allow 'Display over other apps' so the clock can start after reboot")
                tryStart(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")))
            }
            true
        }

        findPreference<Preference>("about")?.summary =
            "Salah Clock v${BuildConfig.VERSION_NAME} · Weather by Open-Meteo.com"

        updateCitySummary()
        updateSoundSummaries()
        updateExactAlarmPref()
    }

    override fun onResume() {
        super.onResume()
        preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(this)
        updateExactAlarmPref()
        updateTestSummaries()
    }

    override fun onPause() {
        preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)
        super.onPause()
    }

    override fun onSharedPreferenceChanged(sp: SharedPreferences?, key: String?) {
        val ctx = context ?: return
        AzanScheduler.scheduleNext(ctx)
        if (key in setOf("location_mode", "manual_city", "manual_lat", "manual_lon")) updateCitySummary()
    }

    // ---------------------------------------------------------------- helpers

    private fun click(key: String, action: () -> Unit) {
        findPreference<Preference>(key)?.setOnPreferenceClickListener { action(); true }
    }

    private fun toast(msg: String) {
        context?.let { Toast.makeText(it, msg, Toast.LENGTH_SHORT).show() }
    }

    private fun tryStart(intent: Intent) {
        try { startActivity(intent) } catch (e: Exception) { toast("Not available on this device") }
    }

    private fun pickSound(fajr: Boolean) {
        pickingFajr = fajr
        try {
            pickAudio.launch(arrayOf("audio/*"))
        } catch (e: Exception) {
            toast("No file picker on this device. Put azan.mp3 in app/src/main/res/raw instead.")
        }
    }

    private fun toggleTest(prayer: String) {
        val ctx = requireContext()
        if (AzanService.isPlaying) {
            AzanService.stop(ctx)
        } else {
            AzanService.play(ctx, prayer)
        }
        view?.postDelayed({ updateTestSummaries() }, 400)
    }

    private fun updateTestSummaries() {
        val playing = AzanService.isPlaying
        findPreference<Preference>("test_azan")?.summary = if (playing) "Playing… tap to stop" else "Play the normal azan now"
        findPreference<Preference>("test_fajr_azan")?.summary = if (playing) "Playing… tap to stop" else "Play the Fajr azan now"
    }

    private fun updateCitySummary() {
        val p = Prefs(requireContext())
        val mode = if (p.locationMode == "manual") "Manual" else "Device location"
        findPreference<Preference>("city_search")?.summary =
            String.format(Locale.US, "%s · %s (%.4f, %.4f)", mode, p.cityName, p.lat, p.lon)
    }

    private fun updateSoundSummaries() {
        val ctx = requireContext()
        val defaultDesc = when {
            AzanService.rawAzanId(ctx, "dhuhr") != 0 -> "Built-in azan (res/raw/azan)"
            else -> "Device alarm tone – add azan.mp3 to res/raw or pick a file"
        }
        val fajrDefault = when {
            AzanService.rawAzanId(ctx, "fajr") != AzanService.rawAzanId(ctx, "dhuhr") -> "Built-in Fajr azan (res/raw/azan_fajr)"
            else -> "Same as normal azan"
        }
        findPreference<Preference>("pick_azan_sound")?.summary =
            prefs.sp.getString(Prefs.K_SOUND, null)?.let { "Custom: " + displayName(ctx, Uri.parse(it)) } ?: defaultDesc
        findPreference<Preference>("pick_fajr_sound")?.summary =
            prefs.sp.getString(Prefs.K_FAJR_SOUND, null)?.let { "Custom: " + displayName(ctx, Uri.parse(it)) } ?: fajrDefault
    }

    private fun displayName(ctx: Context, uri: Uri): String = try {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment ?: uri.toString()
    } catch (e: Exception) {
        uri.lastPathSegment ?: uri.toString()
    }

    private fun updateExactAlarmPref() {
        val pref = findPreference<Preference>("exact_alarm") ?: return
        val ctx = context ?: return
        pref.isVisible = Build.VERSION.SDK_INT >= 31 &&
            !(ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()
    }

    // ---------------------------------------------------------------- city search

    private fun showCitySearch() {
        val ctx = requireContext()
        val input = EditText(ctx).apply {
            hint = "City name, e.g. Doha"
            isSingleLine = true
        }
        val pad = (20 * resources.displayMetrics.density).roundToInt()
        val box = FrameLayout(ctx).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        AlertDialog.Builder(ctx)
            .setTitle("Search city")
            .setView(box)
            .setPositiveButton("Search") { _, _ -> searchCity(input.text.toString().trim()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun searchCity(q: String) {
        if (q.isBlank()) return
        toast("Searching…")
        Thread {
            val results = try { WeatherClient.searchCity(q) } catch (e: Exception) { null }
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                when {
                    results == null -> toast("Search failed – check the internet connection")
                    results.isEmpty() -> toast("No city found for \"$q\"")
                    else -> AlertDialog.Builder(requireContext())
                        .setTitle("Choose location")
                        .setItems(results.map { it.label }.toTypedArray()) { _, i -> saveCity(results[i]) }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
        }.start()
    }

    private fun saveCity(c: CityResult) {
        findPreference<EditTextPreference>("manual_lat")?.text = String.format(Locale.US, "%.5f", c.lat)
        findPreference<EditTextPreference>("manual_lon")?.text = String.format(Locale.US, "%.5f", c.lon)
        findPreference<EditTextPreference>("manual_city")?.text = c.shortLabel
        findPreference<ListPreference>("location_mode")?.value = "manual"
        updateCitySummary()
        toast("Location set to ${c.shortLabel}")
    }
}
