package io.github.sentinel6systems.fieldmap.geo

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import org.maplibre.android.location.engine.LocationEngine
import org.maplibre.android.location.engine.LocationEngineCallback
import org.maplibre.android.location.engine.LocationEngineRequest
import org.maplibre.android.location.engine.LocationEngineResult

/**
 * The phone's position for the map, straight from the satellite receiver. MapLibre's own
 * choice asks the system for its "best" source, which on many phones is a battery-saving mix
 * that leaves the receiver off; away from towns and networks that gives no position at all.
 * The network's rough position is used only until the receiver has a fix. No Google services.
 */
@SuppressLint("MissingPermission")          // the activity asks before it switches the position on
class GpsEngine(context: Context) : LocationEngine {
    private val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val listeners = HashMap<LocationEngineCallback<LocationEngineResult>, LocationListener>()
    private var lastGpsFix = 0L                 // elapsed-time clock, ms

    private fun has(provider: String) = try { manager.allProviders.contains(provider) } catch (e: Exception) { false }

    override fun getLastLocation(callback: LocationEngineCallback<LocationEngineResult>) {
        val last = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter(::has)
            .mapNotNull { manager.getLastKnownLocation(it) }.maxByOrNull { it.elapsedRealtimeNanos }
        if (last != null) callback.onSuccess(LocationEngineResult.create(last)) else callback.onFailure(Exception("No location yet"))
    }

    override fun requestLocationUpdates(request: LocationEngineRequest, callback: LocationEngineCallback<LocationEngineResult>, looper: Looper?) {
        removeLocationUpdates(callback)
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                val now = SystemClock.elapsedRealtime()
                if (location.provider == LocationManager.GPS_PROVIDER) lastGpsFix = now
                else if (now - lastGpsFix < GPS_FRESH_MS) return
                callback.onSuccess(LocationEngineResult.create(location))
            }
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
        }
        listeners[callback] = listener
        val on = looper ?: Looper.getMainLooper()
        if (has(LocationManager.GPS_PROVIDER)) manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, request.interval, 0f, listener, on)
        if (has(LocationManager.NETWORK_PROVIDER)) manager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, maxOf(request.interval, 5000L), 0f, listener, on)
    }

    override fun removeLocationUpdates(callback: LocationEngineCallback<LocationEngineResult>) {
        listeners.remove(callback)?.let(manager::removeUpdates)
    }

    // The map asks with a callback only.
    override fun requestLocationUpdates(request: LocationEngineRequest, pendingIntent: PendingIntent?) {}
    override fun removeLocationUpdates(pendingIntent: PendingIntent?) {}

    private companion object {
        /** A network position is ignored for this long after a satellite fix. */
        const val GPS_FRESH_MS = 15_000L
    }
}
