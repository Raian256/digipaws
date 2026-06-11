package neth.iecal.curbox.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Thin wrapper over the framework [LocationManager] used by the app blocker to
 * evaluate geofenced activation conditions. Deliberately avoids Google Play
 * Services so the F-Droid flavour stays free of proprietary dependencies.
 *
 * It keeps a single best-known [Location] and re-emits via [onUpdate] whenever a
 * fresh fix arrives, so callers can re-evaluate the currently foregrounded app
 * even when no accessibility event fires (e.g. the user crosses a boundary while
 * staring at a static screen).
 */
class LocationProvider(private val context: Context) {

    private val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    /** Most recent fix we know about, or null if we've never had one. */
    @Volatile
    var lastLocation: Location? = null
        private set

    /** Invoked on the main thread whenever [lastLocation] is refreshed. */
    var onUpdate: (() -> Unit)? = null

    private var listener: LocationListener? = null

    fun hasPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    /**
     * Begin listening for updates. No-op if already started, permission is
     * missing, or no location manager is available. Safe to call repeatedly.
     */
    fun start() {
        val lm = locationManager ?: return
        if (listener != null || !hasPermission()) return

        val l = LocationListener { loc ->
            if (lastLocation == null || loc.time >= lastLocation!!.time) {
                lastLocation = loc
                onUpdate?.invoke()
            }
        }
        listener = l

        try {
            // Seed with the freshest cached fix across providers so a geofence
            // can be evaluated immediately, before the first live update lands.
            lm.getProviders(true).forEach { provider ->
                val cached = lm.getLastKnownLocation(provider)
                if (cached != null && (lastLocation == null || cached.time > lastLocation!!.time)) {
                    lastLocation = cached
                }
            }

            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER, MIN_TIME_MS, MIN_DISTANCE_M, l, Looper.getMainLooper()
                )
            }
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER, MIN_TIME_MS, MIN_DISTANCE_M, l, Looper.getMainLooper()
                )
            }
        } catch (e: SecurityException) {
            Log.e("LocationProvider", "Missing location permission: $e")
            listener = null
        } catch (e: Exception) {
            Log.e("LocationProvider", "Failed to start location updates: $e")
        }
    }

    fun stop() {
        val lm = locationManager
        listener?.let { lm?.removeUpdates(it) }
        listener = null
    }

    /**
     * Distance in metres from [lastLocation] to ([lat], [lng]), or null if no
     * fix is available.
     */
    fun distanceTo(lat: Double, lng: Double): Float? {
        val loc = lastLocation ?: return null
        val results = FloatArray(1)
        Location.distanceBetween(loc.latitude, loc.longitude, lat, lng, results)
        return results[0]
    }

    companion object {
        private const val MIN_TIME_MS = 60_000L
        private const val MIN_DISTANCE_M = 25f
    }
}
