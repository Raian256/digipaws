package neth.iecal.curbox.services

import android.annotation.SuppressLint
import android.app.Notification
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.content.edit
import java.time.LocalDate

/**
 * Listener that does two jobs over the device's active media sessions:
 *
 *  1. **Silencing** — on request it pauses a per-package media session and
 *     dismisses its media-style notification, so a blocked app can't keep
 *     playing from the lockscreen or shade. Targets only the named package.
 *
 *  2. **Background-audio time tracking** — for packages the blocker asks us to
 *     track (Usage groups with `countBackgroundAudio`), it measures how long the
 *     app keeps audio *playing while it is NOT the foreground app*, and reports
 *     a running daily total back to the blocker. Foreground playback is excluded
 *     so it isn't double-counted on top of UsageStatsManager's foreground time.
 *
 * AppBlocker (in :app_blocker_service) talks to this via broadcasts; the
 * listener runs in the main process because NotificationListenerService binds
 * to a single process the system picks once granted.
 */
class MediaNotifSilencer : NotificationListenerService() {

    companion object {
        private const val TAG = "MediaNotifSilencer"

        const val ACTION_SILENCE_PKG = "neth.iecal.curbox.media.silence_pkg"
        const val ACTION_UNSILENCE_PKG = "neth.iecal.curbox.media.unsilence_pkg"
        const val EXTRA_PACKAGE = "package"

        /** Blocker -> tracker: replace the set of packages whose background audio we measure. */
        const val ACTION_SET_TRACKED_PKGS = "neth.iecal.curbox.media.set_tracked_pkgs"
        const val EXTRA_PACKAGES = "packages"

        /** Blocker -> tracker: the package currently in the foreground (empty if none/home). */
        const val ACTION_SET_FOREGROUND_PKG = "neth.iecal.curbox.media.set_foreground_pkg"

        /** Blocker -> tracker: re-broadcast the latest totals now (cold-start sync). */
        const val ACTION_REQUEST_AUDIO_TOTALS = "neth.iecal.curbox.media.request_audio_totals"

        /** Tracker -> blocker: today's accumulated background-audio millis per package. */
        const val ACTION_AUDIO_TOTALS = "neth.iecal.curbox.media.audio_totals"
        const val EXTRA_MILLIS = "millis"
        const val EXTRA_DAY = "day" // epoch-day the totals belong to

        private const val PREFS = "media_audio_tracker_prefs"
        private const val KEY_DAY = "day"
        private const val KEY_PKGS = "pkgs"

        /** How often ongoing playback is flushed into the running total. */
        private const val FLUSH_INTERVAL_MS = 30_000L

        fun isEnabled(context: Context): Boolean {
            val componentName = ComponentName(context, MediaNotifSilencer::class.java).flattenToString()
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            ) ?: ""
            return enabled.split(':').any { it == componentName }
        }

        fun sendSilence(context: Context, pkg: String) {
            context.sendBroadcast(
                Intent(ACTION_SILENCE_PKG)
                    .setPackage(context.packageName)
                    .putExtra(EXTRA_PACKAGE, pkg)
            )
        }

        fun sendUnsilence(context: Context, pkg: String) {
            context.sendBroadcast(
                Intent(ACTION_UNSILENCE_PKG)
                    .setPackage(context.packageName)
                    .putExtra(EXTRA_PACKAGE, pkg)
            )
        }

        fun sendSetTrackedPackages(context: Context, packages: Set<String>) {
            context.sendBroadcast(
                Intent(ACTION_SET_TRACKED_PKGS)
                    .setPackage(context.packageName)
                    .putExtra(EXTRA_PACKAGES, packages.toTypedArray())
            )
        }

        fun sendSetForegroundPackage(context: Context, pkg: String) {
            context.sendBroadcast(
                Intent(ACTION_SET_FOREGROUND_PKG)
                    .setPackage(context.packageName)
                    .putExtra(EXTRA_PACKAGE, pkg)
            )
        }

        fun requestAudioTotals(context: Context) {
            context.sendBroadcast(
                Intent(ACTION_REQUEST_AUDIO_TOTALS).setPackage(context.packageName)
            )
        }
    }

    private val silenced: MutableSet<String> = mutableSetOf()
    private var receiverRegistered = false

    // --- background-audio tracking state (all touched on the main thread) ---
    private lateinit var prefs: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())
    private var sessionManager: MediaSessionManager? = null

    /** Packages whose background audio time we measure (from the blocker). */
    private val trackedPkgs: MutableSet<String> = mutableSetOf()
    /** Current foreground package; its playback is excluded from accrual. */
    private var foregroundPkg: String = ""
    /** pkg -> elapsedRealtime when its current background-playing interval began. */
    private val playStartElapsed: MutableMap<String, Long> = mutableMapOf()
    /** pkg -> accumulated background-audio millis for [currentDay]. */
    private val dailyTotals: MutableMap<String, Long> = mutableMapOf()
    private var currentDay: Long = LocalDate.now().toEpochDay()

    private val controllerCallbacks = mutableListOf<Pair<MediaController, MediaController.Callback>>()

    private val sessionsChangedListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            rebindControllerCallbacks(controllers ?: emptyList())
            reconcileAccrual()
        }

    private val flushRunnable = object : Runnable {
        override fun run() {
            flush()
            // Keep flushing only while something is actively accruing.
            if (playStartElapsed.isNotEmpty()) handler.postDelayed(this, FLUSH_INTERVAL_MS)
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_SILENCE_PKG -> {
                    val pkg = intent.getStringExtra(EXTRA_PACKAGE) ?: return
                    silenced.add(pkg)
                    pauseAndDismiss(pkg)
                }
                ACTION_UNSILENCE_PKG -> {
                    val pkg = intent.getStringExtra(EXTRA_PACKAGE) ?: return
                    silenced.remove(pkg)
                }
                ACTION_SET_TRACKED_PKGS -> {
                    val pkgs = intent.getStringArrayExtra(EXTRA_PACKAGES) ?: emptyArray()
                    trackedPkgs.clear()
                    trackedPkgs.addAll(pkgs)
                    // Drop accrual for packages we no longer track.
                    playStartElapsed.keys.filter { it !in trackedPkgs }.forEach { stopAccrual(it) }
                    reconcileAccrual()
                }
                ACTION_SET_FOREGROUND_PKG -> {
                    foregroundPkg = intent.getStringExtra(EXTRA_PACKAGE) ?: ""
                    reconcileAccrual()
                }
                ACTION_REQUEST_AUDIO_TOTALS -> {
                    flush()
                    broadcastTotals()
                }
            }
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onListenerConnected() {
        super.onListenerConnected()
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        loadPersistedTotals()

        if (!receiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(ACTION_SILENCE_PKG)
                addAction(ACTION_UNSILENCE_PKG)
                addAction(ACTION_SET_TRACKED_PKGS)
                addAction(ACTION_SET_FOREGROUND_PKG)
                addAction(ACTION_REQUEST_AUDIO_TOTALS)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(receiver, filter)
            }
            receiverRegistered = true
        }

        val msm = getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
        sessionManager = msm
        val component = ComponentName(this, MediaNotifSilencer::class.java)
        try {
            msm?.addOnActiveSessionsChangedListener(sessionsChangedListener, component)
            rebindControllerCallbacks(msm?.getActiveSessions(component) ?: emptyList())
            reconcileAccrual()
        } catch (e: SecurityException) {
            Log.w(TAG, "session tracking unavailable (listener not granted?): ${e.message}")
        }

        // Re-sync the blocker after a (re)bind so it picks up whatever we already accrued today.
        broadcastTotals()
    }

    override fun onListenerDisconnected() {
        // Persist whatever is in flight before we lose the session.
        flush()
        sessionManager?.removeOnActiveSessionsChangedListener(sessionsChangedListener)
        clearControllerCallbacks()
        handler.removeCallbacks(flushRunnable)
        if (receiverRegistered) {
            try { unregisterReceiver(receiver) } catch (_: Exception) {}
            receiverRegistered = false
        }
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        val pkg = sbn.packageName ?: return
        if (pkg !in silenced) return
        if (!isMediaNotification(sbn)) return

        // App re-posted media controls (user tapped play, or service auto-resume).
        // Pause the session first so audio actually stops, then drop the notif.
        pauseSessionsFor(pkg)
        try { cancelNotification(sbn.key) } catch (e: Exception) {
            Log.w(TAG, "cancelNotification failed for $pkg: ${e.message}")
        }
    }

    private fun pauseAndDismiss(pkg: String) {
        pauseSessionsFor(pkg)
        try {
            activeNotifications?.filter { it.packageName == pkg && isMediaNotification(it) }
                ?.forEach { cancelNotification(it.key) }
        } catch (e: Exception) {
            Log.w(TAG, "pauseAndDismiss failed for $pkg: ${e.message}")
        }
    }

    private fun pauseSessionsFor(pkg: String) {
        val msm = getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager ?: return
        val component = ComponentName(this, MediaNotifSilencer::class.java)
        try {
            msm.getActiveSessions(component)
                .filter { it.packageName == pkg }
                .forEach { ctl: MediaController -> ctl.transportControls.pause() }
        } catch (e: SecurityException) {
            Log.w(TAG, "getActiveSessions denied (listener not granted?): ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "pauseSessionsFor error: ${e.message}")
        }
    }

    private fun isMediaNotification(sbn: StatusBarNotification): Boolean {
        val n = sbn.notification ?: return false
        if (n.category == Notification.CATEGORY_TRANSPORT) return true
        val extras = n.extras ?: return false
        if (extras.containsKey(Notification.EXTRA_MEDIA_SESSION)) return true
        val template = extras.getString(Notification.EXTRA_TEMPLATE)
        return template?.contains("MediaStyle", ignoreCase = true) == true
    }

    // ---------------------------------------------------------------------
    // Background-audio time tracking
    // ---------------------------------------------------------------------

    /**
     * Re-attach a playback-state callback to each currently active controller.
     * Active sessions come and go (apps spawn/kill their media service), so we
     * rebuild the callback set on every change rather than tracking diffs.
     */
    private fun rebindControllerCallbacks(controllers: List<MediaController>) {
        clearControllerCallbacks()
        controllers.forEach { controller ->
            val cb = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) {
                    reconcileAccrual()
                }
                override fun onSessionDestroyed() {
                    reconcileAccrual()
                }
            }
            try {
                controller.registerCallback(cb, handler)
                controllerCallbacks.add(controller to cb)
            } catch (e: Exception) {
                Log.w(TAG, "registerCallback failed for ${controller.packageName}: ${e.message}")
            }
        }
    }

    private fun clearControllerCallbacks() {
        controllerCallbacks.forEach { (controller, cb) ->
            try { controller.unregisterCallback(cb) } catch (_: Exception) {}
        }
        controllerCallbacks.clear()
    }

    /** Packages with at least one active session currently in the PLAYING state. */
    private fun currentlyPlayingPackages(): Set<String> {
        return controllerCallbacks
            .map { it.first }
            .filter { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            .map { it.packageName }
            .toSet()
    }

    /**
     * Bring accrual markers in line with the current world: a tracked package
     * accrues iff it is playing AND it is not the foreground app. Starts/stops
     * markers as needed; existing intervals are flushed when they stop.
     */
    private fun reconcileAccrual() {
        rollDayIfNeeded()
        val playing = currentlyPlayingPackages()
        // Stop accrual for anything no longer eligible.
        playStartElapsed.keys.toList().forEach { pkg ->
            val eligible = pkg in trackedPkgs && pkg in playing && pkg != foregroundPkg
            if (!eligible) stopAccrual(pkg)
        }
        // Start accrual for newly eligible packages.
        playing.forEach { pkg ->
            if (pkg in trackedPkgs && pkg != foregroundPkg && pkg !in playStartElapsed) {
                playStartElapsed[pkg] = SystemClock.elapsedRealtime()
            }
        }
        // Keep the periodic flush alive while anything is accruing.
        handler.removeCallbacks(flushRunnable)
        if (playStartElapsed.isNotEmpty()) handler.postDelayed(flushRunnable, FLUSH_INTERVAL_MS)
    }

    /** Flush a single package's in-flight interval into the daily total and clear its marker. */
    private fun stopAccrual(pkg: String) {
        val start = playStartElapsed.remove(pkg) ?: return
        addElapsed(pkg, SystemClock.elapsedRealtime() - start)
        persistTotals()
        broadcastTotals()
    }

    /**
     * Fold every in-flight interval into the running total without ending it
     * (advance the start marker to now). Lets the blocker see fresh numbers and
     * caps how much accrued time we'd lose if the process were killed mid-play.
     */
    private fun flush() {
        if (playStartElapsed.isEmpty()) return
        rollDayIfNeeded()
        val now = SystemClock.elapsedRealtime()
        playStartElapsed.keys.toList().forEach { pkg ->
            val start = playStartElapsed[pkg] ?: return@forEach
            addElapsed(pkg, now - start)
            playStartElapsed[pkg] = now
        }
        persistTotals()
        broadcastTotals()
    }

    private fun addElapsed(pkg: String, delta: Long) {
        if (delta <= 0) return
        dailyTotals[pkg] = (dailyTotals[pkg] ?: 0L) + delta
    }

    private fun rollDayIfNeeded() {
        val today = LocalDate.now().toEpochDay()
        if (today != currentDay) {
            currentDay = today
            dailyTotals.clear()
            // In-flight intervals continue, but their accrued time now lands in the new day.
            val now = SystemClock.elapsedRealtime()
            playStartElapsed.keys.toList().forEach { playStartElapsed[it] = now }
            persistTotals()
        }
    }

    private fun broadcastTotals() {
        val pkgs = dailyTotals.keys.toTypedArray()
        val millis = LongArray(pkgs.size) { dailyTotals[pkgs[it]] ?: 0L }
        sendBroadcast(
            Intent(ACTION_AUDIO_TOTALS)
                .setPackage(packageName)
                .putExtra(EXTRA_PACKAGES, pkgs)
                .putExtra(EXTRA_MILLIS, millis)
                .putExtra(EXTRA_DAY, currentDay)
        )
    }

    private fun loadPersistedTotals() {
        val savedDay = prefs.getLong(KEY_DAY, currentDay)
        currentDay = LocalDate.now().toEpochDay()
        if (savedDay != currentDay) {
            // Stale snapshot from a previous day — start clean.
            dailyTotals.clear()
            return
        }
        val pkgs = prefs.getStringSet(KEY_PKGS, emptySet()) ?: emptySet()
        pkgs.forEach { pkg ->
            val v = prefs.getLong("total_$pkg", 0L)
            if (v > 0L) dailyTotals[pkg] = v
        }
    }

    private fun persistTotals() {
        prefs.edit {
            putLong(KEY_DAY, currentDay)
            putStringSet(KEY_PKGS, dailyTotals.keys)
            dailyTotals.forEach { (pkg, v) -> putLong("total_$pkg", v) }
        }
    }
}
