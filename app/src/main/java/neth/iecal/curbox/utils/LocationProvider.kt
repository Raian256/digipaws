package neth.iecal.curbox.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
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
 *
 * Sampling is deliberately **periodic-one-shot** rather than a continuous
 * subscription. A continuous subscription keeps the system location indicator
 * (the status-bar dot) permanently lit; tapping it lets the user "Close app",
 * which force-stops Curbox and silently disables the accessibility services
 * until the app is reopened — a clean bypass of every geofenced block. By
 * instead requesting a single fix every [POLL_INTERVAL_MS] and releasing the
 * providers the moment it lands, the indicator only blinks briefly each cycle,
 * shrinking that attack surface (and cutting battery use). It cannot be removed
 * entirely without Device Owner provisioning — see
 * [PermissionUtils.applyUserControlLock].
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

    private val handler = Handler(Looper.getMainLooper())
    private var polling = false

    /**
     * One-shot listeners currently waiting on an in-flight fix. Tracked so a
     * cycle whose provider never delivers can be torn down at the next tick or
     * by [FIX_TIMEOUT_MS], rather than lingering and keeping the location
     * indicator lit past the sampling window.
     */
    private val pendingFixes = mutableListOf<LocationListener>()

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
     * Begin periodic geofence sampling. No-op if already started, permission is
     * missing, or no location manager is available. Safe to call repeatedly.
     */
    fun start() {
        val lm = locationManager ?: return
        if (polling || !hasPermission()) return
        polling = true
        // Seed with the freshest cached fix across providers so a geofence can
        // be evaluated immediately, before the first live sample lands.
        seedFromCache(lm)
        handler.post(pollRunnable)
    }

    fun stop() {
        polling = false
        handler.removeCallbacks(pollRunnable)
        clearPendingFixes()
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            sampleOnce()
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    private fun seedFromCache(lm: LocationManager) {
        try {
            lm.getProviders(true).forEach { provider ->
                val cached = lm.getLastKnownLocation(provider)
                if (cached != null && (lastLocation == null || cached.time > lastLocation!!.time)) {
                    lastLocation = cached
                }
            }
        } catch (e: SecurityException) {
            Log.e("LocationProvider", "Missing location permission: $e")
        }
    }

    /**
     * Take one fix from each enabled provider, then let the providers go idle.
     * Listeners still pending from a previous cycle are cleared first so we
     * never accumulate live subscriptions (and thus never hold the indicator
     * open between samples).
     */
    private fun sampleOnce() {
        val lm = locationManager ?: return
        if (!hasPermission()) return
        clearPendingFixes()

        try {
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).forEach { provider ->
                if (!lm.isProviderEnabled(provider)) return@forEach
                val l = LocationListener { loc ->
                    if (lastLocation == null || loc.time >= lastLocation!!.time) {
                        lastLocation = loc
                        onUpdate?.invoke()
                    }
                }
                lm.requestSingleUpdate(provider, l, Looper.getMainLooper())
                pendingFixes.add(l)
            }
            // Safety net: drop any provider that never delivers so the indicator
            // can't be held open beyond the sampling window.
            handler.postDelayed(::clearPendingFixes, FIX_TIMEOUT_MS)
        } catch (e: SecurityException) {
            Log.e("LocationProvider", "Missing location permission: $e")
        } catch (e: Exception) {
            Log.e("LocationProvider", "Failed to sample location: $e")
        }
    }

    private fun clearPendingFixes() {
        val lm = locationManager ?: return
        pendingFixes.forEach { lm.removeUpdates(it) }
        pendingFixes.clear()
    }

    /**
     * Force an immediate fix outside the periodic schedule. Useful when the
     * cached fix has gone stale and the user wants geofences re-evaluated
     * against their current position right now (see the in-app "Refresh
     * location" action). No-op if permission is missing or no location manager
     * is available.
     */
    fun requestSingleUpdate() {
        sampleOnce()
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
        /** How often to take a geofence sample while at least one is active. */
        private const val POLL_INTERVAL_MS = 4 * 60_000L

        /** Max time to wait for a provider to deliver before giving up on a sample. */
        private const val FIX_TIMEOUT_MS = 30_000L
    }
}
