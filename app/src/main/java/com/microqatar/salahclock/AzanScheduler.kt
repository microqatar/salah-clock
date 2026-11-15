package com.microqatar.salahclock

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import java.util.Calendar

/** Keeps exactly one alarm set: the next prayer that has azan enabled. */
object AzanScheduler {
    private const val REQUEST_CODE = 1001

    fun scheduleNext(ctx: Context) {
        val prefs = Prefs(ctx)
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val base = Intent(ctx, AzanReceiver::class.java)

        val next = if (prefs.azanEnabled) findNext(prefs, System.currentTimeMillis()) else null
        if (next == null) {
            am.cancel(PendingIntent.getBroadcast(ctx, REQUEST_CODE, base, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            return
        }
        val pi = PendingIntent.getBroadcast(
            ctx, REQUEST_CODE,
            base.putExtra(AzanService.EXTRA_PRAYER, next.key),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.time, pi)
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.time, pi)
            }
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.time, pi)
        }
    }

    /** Next azan-enabled prayer at least one minute in the future (prevents a double azan). */
    fun findNext(prefs: Prefs, now: Long): PrayerTime? {
        val cal = Calendar.getInstance()
        for (offset in 0..1) {
            val day = cal.clone() as Calendar
            day.add(Calendar.DAY_OF_MONTH, offset)
            PrayerRepository.compute(prefs, day).prayers.firstOrNull {
                it.time > now + 60_000L && prefs.azanFor(it.key)
            }?.let { return it }
        }
        return null
    }
}

class AzanReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prayer = intent.getStringExtra(AzanService.EXTRA_PRAYER)
        if (prayer != null && Prefs(context).azanFor(prayer)) {
            try {
                AzanService.play(context, prayer)
            } catch (e: Exception) {
                Log.w("AzanReceiver", "Could not start azan", e)
            }
        }
        AzanScheduler.scheduleNext(context)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AzanScheduler.scheduleNext(context)
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && Prefs(context).startOnBoot) {
            val canLaunch = Build.VERSION.SDK_INT < 29 || Settings.canDrawOverlays(context)
            if (canLaunch) {
                try {
                    context.startActivity(
                        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (e: Exception) {
                    Log.w("BootReceiver", "Auto-start failed", e)
                }
            }
        }
    }
}
