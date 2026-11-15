package com.microqatar.salahclock

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Plays the azan as a foreground service so it works even when the screen is off.
 * Sound priority: custom file chosen in admin panel -> res/raw/azan(_fajr).mp3 -> device alarm tone.
 */
class AzanService : Service() {

    companion object {
        const val ACTION_PLAY = "com.microqatar.salahclock.action.PLAY"
        const val ACTION_STOP = "com.microqatar.salahclock.action.STOP"
        const val EXTRA_PRAYER = "prayer"
        const val CHANNEL_ID = "azan"
        private const val NOTIF_ID = 42
        private const val TAG = "AzanService"

        @Volatile var isPlaying = false
            private set
        @Volatile var currentPrayer: String? = null
            private set

        fun play(ctx: Context, prayer: String) {
            val i = Intent(ctx, AzanService::class.java).setAction(ACTION_PLAY).putExtra(EXTRA_PRAYER, prayer)
            ContextCompat.startForegroundService(ctx, i)
        }

        fun stop(ctx: Context) {
            if (!isPlaying) return
            try {
                ctx.startService(Intent(ctx, AzanService::class.java).setAction(ACTION_STOP))
            } catch (e: Exception) {
                Log.w(TAG, "stop failed", e)
            }
        }

        fun rawAzanId(ctx: Context, prayer: String): Int {
            val r = ctx.resources
            val p = ctx.packageName
            if (prayer == "fajr") {
                val f = r.getIdentifier("azan_fajr", "raw", p)
                if (f != 0) return f
            }
            return r.getIdentifier("azan", "raw", p)
        }

        fun ensureChannel(ctx: Context) {
            if (Build.VERSION.SDK_INT >= 26) {
                val nm = ctx.getSystemService(NotificationManager::class.java)
                if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                    val ch = NotificationChannel(CHANNEL_ID, "Azan", NotificationManager.IMPORTANCE_HIGH).apply {
                        description = "Azan playback"
                        setSound(null, null)
                    }
                    nm.createNotificationChannel(ch)
                }
            }
        }
    }

    private var player: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private val safetyStop = Runnable { stopAzan() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopAzan()
            return START_NOT_STICKY
        }
        val prayer = intent?.getStringExtra(EXTRA_PRAYER) ?: "dhuhr"
        startInForeground(prayer)
        startPlayback(prayer)
        return START_NOT_STICKY
    }

    private fun startInForeground(prayer: String) {
        ensureChannel(this)
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val stopPi = PendingIntent.getService(
            this, 1, Intent(this, AzanService::class.java).setAction(ACTION_STOP), flags
        )
        val openPi = PendingIntent.getActivity(
            this, 2,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            flags
        )
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_azan)
            .setContentTitle("Azan · ${PrayerNames.en(prayer)}  ${PrayerNames.ar(prayer)}")
            .setContentText("Tap Stop to silence")
            .setContentIntent(openPi)
            .addAction(0, "Stop", stopPi)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
        ServiceCompat.startForeground(this, NOTIF_ID, n, type)
    }

    private fun startPlayback(prayer: String) {
        releasePlayer()
        val prefs = Prefs(this)
        val mp = MediaPlayer()
        if (!setSource(mp, prefs, prayer)) {
            mp.release()
            stopAzan()
            return
        }
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            val vol = prefs.azanVolume.coerceIn(0, 100) / 100f
            mp.setVolume(vol, vol)
            mp.setOnPreparedListener { it.start() }
            mp.setOnCompletionListener { stopAzan() }
            mp.setOnErrorListener { _, _, _ -> stopAzan(); true }
            player = mp
            isPlaying = true
            currentPrayer = prayer
            mp.prepareAsync()
        } catch (e: Exception) {
            Log.w(TAG, "playback failed", e)
            stopAzan()
            return
        }
        handler.removeCallbacks(safetyStop)
        handler.postDelayed(safetyStop, 7 * 60_000L) // never play longer than 7 minutes
    }

    private fun setSource(mp: MediaPlayer, prefs: Prefs, prayer: String): Boolean {
        prefs.soundUri(prayer)?.let { uri ->
            try { mp.setDataSource(this, uri); return true } catch (e: Exception) { mp.reset() }
        }
        val raw = rawAzanId(this, prayer)
        if (raw != 0) {
            try {
                resources.openRawResourceFd(raw).use { afd ->
                    mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                }
                return true
            } catch (e: Exception) { mp.reset() }
        }
        val def = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        if (def != null) {
            try { mp.setDataSource(this, def); return true } catch (e: Exception) { mp.reset() }
        }
        return false
    }

    private fun releasePlayer() {
        player?.let {
            try { if (it.isPlaying) it.stop() } catch (_: Exception) {}
            it.release()
        }
        player = null
    }

    private fun stopAzan() {
        handler.removeCallbacks(safetyStop)
        releasePlayer()
        isPlaying = false
        currentPrayer = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(safetyStop)
        releasePlayer()
        isPlaying = false
        currentPrayer = null
        super.onDestroy()
    }
}
