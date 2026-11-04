package com.bggg.watchman.location

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle

/**
 * High-frequency foreground navigation location controller.
 *
 * WatchMan does not deliberately throttle navigation fixes: GPS requests use a
 * 500 ms minimum interval and zero minimum movement. Android/provider hardware
 * remains free to deliver at its own supported cadence.
 */
class LocationController(
    private val activity: Activity,
    private val onLocation: (Location) -> Unit
) : LocationListener {
    private val manager = activity.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var started = false

    fun hasPermission(): Boolean =
        activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            activity.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun start() {
        if (!hasPermission() || started) return
        var registered = false
        if (activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            registered = requestProvider(LocationManager.GPS_PROVIDER, 500L, 0f) || registered
        }
        registered = requestProvider(LocationManager.NETWORK_PROVIDER, 1000L, 0f) || registered
        started = registered
        if (registered) {
            try {
                val last = manager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                    ?: manager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                if (last != null) onLocation(last)
            } catch (_: SecurityException) { }
            catch (_: IllegalArgumentException) { }
        }
    }

    private fun requestProvider(provider: String, intervalMs: Long, movementMeters: Float): Boolean = try {
        manager.requestLocationUpdates(provider, intervalMs, movementMeters, this)
        true
    } catch (_: SecurityException) {
        false
    } catch (_: IllegalArgumentException) {
        false
    }

    fun stop() {
        try { manager.removeUpdates(this) } catch (_: Exception) { }
        started = false
    }

    override fun onLocationChanged(location: Location) = onLocation(location)
    override fun onProviderEnabled(provider: String) { }
    override fun onProviderDisabled(provider: String) { }
    @Deprecated("Deprecated in platform")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) { }
}
