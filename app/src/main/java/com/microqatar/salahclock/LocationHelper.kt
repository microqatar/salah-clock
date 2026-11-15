package com.microqatar.salahclock

import android.annotation.SuppressLint
import android.content.Context
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import java.util.Locale

/** Device location without Google Play Services, so it also works on Android TV boxes. */
object LocationHelper {

    @SuppressLint("MissingPermission")
    fun fetch(ctx: Context, callback: (Location?) -> Unit) {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (lm == null) { callback(null); return }
        val providers = try { lm.getProviders(true) } catch (e: Exception) { emptyList<String>() }

        val lastKnown = providers.mapNotNull {
            try { lm.getLastKnownLocation(it) } catch (e: Exception) { null }
        }.maxByOrNull { it.time }

        if (lastKnown != null && System.currentTimeMillis() - lastKnown.time < 3 * 3_600_000L) {
            callback(lastKnown); return
        }

        val provider = when {
            LocationManager.NETWORK_PROVIDER in providers -> LocationManager.NETWORK_PROVIDER
            LocationManager.GPS_PROVIDER in providers -> LocationManager.GPS_PROVIDER
            else -> null
        }
        if (provider == null) { callback(lastKnown); return }

        val handler = Handler(Looper.getMainLooper())
        var done = false
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (done) return
                done = true
                lm.removeUpdates(this)
                callback(location)
            }
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }
        try {
            lm.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
        } catch (e: Exception) {
            callback(lastKnown); return
        }
        handler.postDelayed({
            if (!done) {
                done = true
                lm.removeUpdates(listener)
                callback(lastKnown)
            }
        }, 20_000L)
    }

    /** Blocking: call from a background thread. */
    fun reverseGeocode(ctx: Context, lat: Double, lon: Double): String? = try {
        if (!Geocoder.isPresent()) null
        else {
            @Suppress("DEPRECATION")
            val a = Geocoder(ctx, Locale.getDefault()).getFromLocation(lat, lon, 1)?.firstOrNull()
            a?.let {
                listOfNotNull(it.locality ?: it.subAdminArea ?: it.adminArea, it.countryName)
                    .joinToString(", ")
            }?.takeIf { it.isNotBlank() }
        }
    } catch (e: Exception) {
        null
    }
}
