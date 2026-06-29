package neth.iecal.curbox.blockers

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Context.RECEIVER_EXPORTED
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.edit
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import neth.iecal.curbox.Constants
import neth.iecal.curbox.data.models.AppBlockerWarningScreenConfig
import neth.iecal.curbox.data.models.AppBlockingType
import neth.iecal.curbox.data.models.AppTimeConfig
import neth.iecal.curbox.data.models.AppUsageConfig
import neth.iecal.curbox.data.models.GeoFenceConfig
import neth.iecal.curbox.data.models.GeoFenceMode
import neth.iecal.curbox.services.BaseBlockingService
import neth.iecal.curbox.services.MediaNotifSilencer
import neth.iecal.curbox.ui.activity.WarningActivity
import neth.iecal.curbox.utils.LocationProvider
import neth.iecal.curbox.utils.SystemOverlayDetector
import neth.iecal.curbox.utils.TimeTools
import neth.iecal.curbox.utils.TimerNotification
import neth.iecal.curbox.utils.UsageStatsHelper
import neth.iecal.curbox.utils.getEssentialPackages
import java.time.LocalDate
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

class AppBlocker() : BaseBlocker() {

    companion object {
        /**
         * Refreshes information about warning screen, cheat hours and blocked app list
         */
        const val INTENT_ACTION_REFRESH_APP_BLOCKER = "neth.iecal.curbox.refresh.appblocker"

        /**
         * Add cooldown to an app.
         * This broadcast should always be sent together with the following keys:
         * selected_time: Int -> Duration of cooldown in millis
         * result_id : String -> Package name of app to be put into cooldown
         */
        const val INTENT_ACTION_REFRESH_APP_BLOCKER_COOLDOWN = "neth.iecal.curbox.refresh.appblocker.cooldown"

        /**
         * Forces the running blocker to fetch a fresh location fix and
         * re-evaluate the foregrounded app, for when the periodic geofence
         * location hasn't updated on its own.
         */
        const val INTENT_ACTION_REFRESH_GEOFENCE_LOCATION = "neth.iecal.curbox.refresh.geofence.location"
        private const val TARGET_EVENTS_MASK = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED

        /** Upper bound of the random delay applied to a manual geofence refresh. */
        private const val GEOFENCE_REFRESH_MAX_DELAY_MS = 3 * 60_000L
    }

    private lateinit var prefs: SharedPreferences
        /**
     * stores what blocked apps have been allowed by the user to be used and until when
     * package-name -> end-time-in-real-time-millis
     */
    private var cooldownAppsList = HashMap<String, Long>()

    /**
     * Per-package list of all active groups that cover the package. Storing a
     * list (not a single config) is what stops the multi-group bypass:
     * creating a second, looser group used to overwrite the stricter one in a
     * package-keyed map. Now every group's rule is evaluated, and the
     * strictest one wins.
     */
    private data class UsageEntry(val config: AppUsageConfig, val warning: AppBlockerWarningScreenConfig, val killBackgroundAudio: Boolean, val countBackgroundAudio: Boolean, val geoFence: GeoFenceConfig)
    private data class TimeEntry(val config: AppTimeConfig, val warning: AppBlockerWarningScreenConfig, val killBackgroundAudio: Boolean, val geoFence: GeoFenceConfig)

    private var blockedAppsList = HashMap<String, MutableList<UsageEntry>>()
    private var timeBlockedAppsList = HashMap<String, MutableList<TimeEntry>>()

    /**
     * Packages we've asked MediaNotifSilencer to silence. Kept so we can send
     * UNSILENCE when the block clears (next allowed window, fresh daily quota,
     * or the user editing the group to drop killBackgroundAudio).
     */
    private val audioSilencedPackages = mutableSetOf<String>()

    /**
     * Background-audio millis accrued today per package, reported by
     * [MediaNotifSilencer] over [INTENT_ACTION_AUDIO_TOTALS_FROM_TRACKER]. Added
     * to UsageStats foreground time for groups that opted into
     * `countBackgroundAudio`. [backgroundAudioDay] guards against consuming a
     * stale snapshot left over from a previous day.
     */
    private val backgroundAudioMillisToday = HashMap<String, Long>()
    private var backgroundAudioDay: Long = LocalDate.now().toEpochDay()

    /** Last foreground package broadcast to the tracker; dedupes the broadcasts. */
    private var lastForegroundPkg = ""

    private lateinit var usageStats : UsageStatsHelper
    private var lastPackage = ""
    private var essentialPackages: Set<String> = emptySet()
    private lateinit var service: BaseBlockingService

    /**
     * Supplies the device location used to evaluate geofenced groups. Updates
     * are only requested while at least one active group opts into a geofence,
     * so location-agnostic setups never touch the GPS stack.
     */
    private lateinit var locationProvider: LocationProvider

    /**
     * Global fail-open vs fail-closed choice for geofenced groups when no
     * location fix is available. Mirrors
     * [neth.iecal.curbox.data.models.Settings.blockGeofencedWhenLocationUnavailable]
     * and is refreshed alongside the group lists.
     */
    private var blockWhenLocationUnavailable = false

    /** True while a manual geofence refresh is pending its randomised delay. */
    private var geofenceRefreshScheduled = false


    // responsible to trigger a recheck for what app user is currently using even when no event is received. Used in putting the usage recheck logic into
    // cooldown for an app and later when the cooldown duration is over, trigger a recheck
    private val handler = Handler(Looper.getMainLooper())

    private val activeRunnables = HashMap<String, Runnable>()

    private lateinit var notificationManager: TimerNotification


    fun doAppBlockerCheck(event: AccessibilityEvent?) {
        if (event == null || (event.eventType and TARGET_EVENTS_MASK) == 0) return

        val packageName = event.packageName?.toString() ?: return

        if (lastPackage == packageName || essentialPackages.contains(packageName)) {
            // Even for essential apps / the launcher (which we never block), the
            // foreground app has changed — tell the audio tracker so a tracked
            // app that just left the foreground starts accruing background time.
            updateForegroundPackage(packageName)
            return
        }

        // Same overlay guard as FocusModeBlocker: a system-signed dialog
        // (permission prompt, installer confirm, Pixel battery-saver "Use
        // anyway?") isn't a real foreground app and shouldn't be home-pressed.
        if (SystemOverlayDetector.isSystemOverlay(service, event)) return

        lastPackage = packageName
        updateForegroundPackage(packageName)

        evaluatePackage(packageName)
    }

    /**
     * Core block decision for [packageName], factored out so it can be driven
     * both by accessibility events and by a fresh location fix (a geofence
     * boundary can be crossed while the user stares at an otherwise static
     * screen, which produces no accessibility event).
     */
    private fun evaluatePackage(packageName: String) {
        // Check Cooldown
        if (cooldownAppsList.containsKey(packageName)) {
            if (cooldownAppsList[packageName]!! < System.currentTimeMillis()) {
                removeCooldownFrom(packageName)
            } else {
                notificationManager.startTimer(totalMillis = cooldownAppsList[packageName]!! - System.currentTimeMillis(), timerId = packageName, title = "Remaining usage before lockdown")
                releaseAudioSilence(packageName) // cooldown grants usage, so unblock audio too
                return // Still in cooldown, let them use it
            }
        }
        // Check Time Blocks — app blocked if currently outside ANY active
        // group's allowed window. Earliest allowed-window end among matching
        // groups drives the next forced refresh.
        val timeEntries = timeBlockedAppsList[packageName]?.filter { isActiveByLocation(it.geoFence) }
        if (!timeEntries.isNullOrEmpty()) {
            var earliestEnd: Long = Long.MAX_VALUE
            for (entry in timeEntries) {
                val endAllowedRealTime = getEndTimeInRealTimeMillis(entry.config)
                if (endAllowedRealTime == null) {
                    notificationManager.stopTimer()
                    showWarningScreen(packageName)
                    return
                }
                if (endAllowedRealTime < earliestEnd) earliestEnd = endAllowedRealTime
            }
            if (earliestEnd != Long.MAX_VALUE) {
                setUpForcedRefreshChecker(packageName, earliestEnd)
            }
        }
        Log.d("checking","checking $packageName")

        // Check Usage Blocks — app blocked if ANY active group's daily limit
        // is exceeded. Notification timer + recheck use the minimum remaining
        // time across all covering groups.
        val usageEntries = blockedAppsList[packageName]?.filter { isActiveByLocation(it.geoFence) }
        if (!usageEntries.isNullOrEmpty()) {
            val foregroundUsage = foregroundMillisToday(packageName)
            val audioUsage = audioMillisToday(packageName)
            var minRemaining = Long.MAX_VALUE
            for (entry in usageEntries) {
                val usageLimitMillis = getUsageLimitForToday(entry.config) * 60_000L
                // Add measured background-audio time only for groups that opted in.
                val currentUsage = foregroundUsage + (if (entry.countBackgroundAudio) audioUsage else 0L)
                val remainingUsage = usageLimitMillis - currentUsage
                if (remainingUsage <= 0) {
                    notificationManager.stopTimer()
                    showWarningScreen(packageName)
                    return
                }
                if (remainingUsage < minRemaining) minRemaining = remainingUsage
            }
            if (minRemaining != Long.MAX_VALUE) {
                notificationManager.startTimer(totalMillis = minRemaining, timerId = packageName, title = "Remaining usage before lockdown")
                setUpForcedRefreshChecker(packageName, System.currentTimeMillis() + minRemaining)
                releaseAudioSilence(packageName) // currently within budget
                return
            }
        }

        notificationManager.stopTimer()
        releaseAudioSilence(packageName)
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    fun setupReceivers() {
        val filter = IntentFilter().apply {
            addAction(INTENT_ACTION_REFRESH_APP_BLOCKER)
            addAction(INTENT_ACTION_REFRESH_APP_BLOCKER_COOLDOWN)
            addAction(INTENT_ACTION_REFRESH_GEOFENCE_LOCATION)
            addAction(MediaNotifSilencer.ACTION_AUDIO_TOTALS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            service.registerReceiver(refreshReceiver, filter, RECEIVER_EXPORTED)
        } else {
            service.registerReceiver(refreshReceiver, filter)
        }
    }

    fun onDestroy() {
        service.unregisterReceiver(refreshReceiver)
        notificationManager.release()
        handler.removeCallbacksAndMessages(null)
        activeRunnables.clear()
        if (::locationProvider.isInitialized) locationProvider.stop()
    }

    fun setupAppBlocker(service: BaseBlockingService) {
        this.service = service
        notificationManager = TimerNotification(service)
        prefs = service.getSharedPreferences("app_blocker_prefs", Context.MODE_PRIVATE)
        loadPersistedData()
        if (!::locationProvider.isInitialized) {
            locationProvider = LocationProvider(service)
            locationProvider.onUpdate = { onLocationChanged() }
        }
        usageStats = UsageStatsHelper(service)
        essentialPackages = getEssentialPackages(service)
        CoroutineScope(Dispatchers.IO).launch {
            service.dataStoreManager.settings.collectLatest { settings ->
                // Refresh essentials so user-added custom essentials take effect immediately.
                essentialPackages = getEssentialPackages(service, settings.customEssentialPackages.toSet())
                blockWhenLocationUnavailable = settings.blockGeofencedWhenLocationUnavailable
                // Rebuild per-package entry lists from scratch on every refresh.
                blockedAppsList.clear()
                timeBlockedAppsList.clear()

                settings.blockedAppGroups.forEach { group ->
                    if (!group.isActive) return@forEach
                    if (group.blockingType == AppBlockingType.Usage) {
                        val appUsageConfig = Gson().fromJson(group.setting, AppUsageConfig::class.java)
                        val entry = UsageEntry(appUsageConfig, group.warningScreenConfig, group.killBackgroundAudio, group.countBackgroundAudio, group.geoFence ?: GeoFenceConfig())
                        group.selectedPackages.forEach { pkg ->
                            blockedAppsList.getOrPut(pkg) { mutableListOf() }.add(entry)
                        }
                    } else {
                        val appTimedConfig = Gson().fromJson(group.setting, AppTimeConfig::class.java)
                        val entry = TimeEntry(appTimedConfig, group.warningScreenConfig, group.killBackgroundAudio, group.geoFence ?: GeoFenceConfig())
                        group.selectedPackages.forEach { pkg ->
                            timeBlockedAppsList.getOrPut(pkg) { mutableListOf() }.add(entry)
                        }
                    }
                }
                Log.d("loaded blocked apps",blockedAppsList.toString())

                // Tell the tracker which packages' background audio to measure,
                // and pull the latest totals so a freshly (re)loaded blocker
                // syncs with whatever has already accrued today.
                val trackedAudioPkgs = blockedAppsList
                    .filterValues { list -> list.any { it.countBackgroundAudio } }
                    .keys.toSet()
                MediaNotifSilencer.sendSetTrackedPackages(service, trackedAudioPkgs)
                MediaNotifSilencer.requestAudioTotals(service)

                // Only spin up location updates while a geofence is actually in
                // use; otherwise keep the GPS stack untouched.
                if (anyGeoFenceActive()) locationProvider.start() else locationProvider.stop()

                // Drop silences for packages whose audio-killing / audio-counting groups went away.
                val stillCovered = audioSilencedPackages.filter { shouldSilenceWhenBlocked(it) }.toSet()
                (audioSilencedPackages - stillCovered).forEach { releaseAudioSilence(it) }
            }
        }

    }

    private fun handlePutCooldownIntentBroadcast(intent: Intent) {
        val coolPackage = intent.getStringExtra("result_id") ?: return

        val durationMillis = intent.getIntExtra(
            "selected_time",
            mergedStrictestWarning(coolPackage)?.timeInterval ?: 10
        )
        Log.d("cooldown for ", durationMillis.toString())
        val realTimeEndMillis = System.currentTimeMillis() + durationMillis

        notificationManager.startTimer(totalMillis = durationMillis.toLong(), timerId = coolPackage, title = "Remaining usage before lockdown")

        putCooldownTo(coolPackage, realTimeEndMillis)
        setUpForcedRefreshChecker(coolPackage, realTimeEndMillis)
        // User earned a cooldown — restore audio (they're using the app on purpose now).
        releaseAudioSilence(coolPackage)
    }

    /**
     * Fresh background-audio totals arrived from the tracker. Replace our cached
     * snapshot (guarding the day stamp), persist it as a cold-start fallback,
     * and immediately enforce any background overage.
     */
    private fun handleAudioTotalsBroadcast(intent: Intent) {
        val day = intent.getLongExtra(MediaNotifSilencer.EXTRA_DAY, LocalDate.now().toEpochDay())
        val pkgs = intent.getStringArrayExtra(MediaNotifSilencer.EXTRA_PACKAGES) ?: emptyArray()
        val millis = intent.getLongArrayExtra(MediaNotifSilencer.EXTRA_MILLIS) ?: LongArray(0)

        backgroundAudioDay = day
        backgroundAudioMillisToday.clear()
        for (i in pkgs.indices) {
            backgroundAudioMillisToday[pkgs[i]] = millis.getOrElse(i) { 0L }
        }
        persistAudioTotals()
        enforceBackgroundAudioLimits()
    }

    /**
     * True if any active group covering [pkg] opted into killBackgroundAudio.
     */
    private fun hasAudioKilling(pkg: String): Boolean {
        val usageHits = blockedAppsList[pkg]?.any { it.killBackgroundAudio } == true
        val timedHits = timeBlockedAppsList[pkg]?.any { it.killBackgroundAudio } == true
        return usageHits || timedHits
    }

    /**
     * Whether [pkg]'s media should be paused when it is blocked. True if any
     * group opts into killBackgroundAudio, OR if any group counts background
     * audio toward its limit — in the latter case stopping playback is how the
     * limit is actually enforced once the budget is spent.
     */
    private fun shouldSilenceWhenBlocked(pkg: String): Boolean {
        return hasAudioKilling(pkg) || blockedAppsList[pkg]?.any { it.countBackgroundAudio } == true
    }

    /** Today's UsageStats foreground time for [pkg], in millis. */
    private fun foregroundMillisToday(pkg: String): Long {
        return usageStats.getForegroundStatsByRelativeDay(0)
            .firstOrNull { it.packageName == pkg }?.totalTime ?: 0L
    }

    /** Today's measured background-audio time for [pkg], or 0 if the snapshot is stale. */
    private fun audioMillisToday(pkg: String): Long {
        if (backgroundAudioDay != LocalDate.now().toEpochDay()) return 0L
        return backgroundAudioMillisToday[pkg] ?: 0L
    }

    private fun updateForegroundPackage(pkg: String) {
        if (pkg == lastForegroundPkg) return
        lastForegroundPkg = pkg
        MediaNotifSilencer.sendSetForegroundPackage(service, pkg)
    }

    /**
     * Driven by fresh totals from the tracker: for every package whose
     * count-enabled limit is now exceeded, pause its (background) playback. This
     * is the screen-off / other-app enforcement path, where no accessibility
     * event would otherwise fire. Releases flow through [evaluatePackage] when
     * the user next interacts within budget, so we only ever tighten here.
     */
    private fun enforceBackgroundAudioLimits() {
        for (pkg in backgroundAudioMillisToday.keys) {
            val entries = blockedAppsList[pkg]?.filter { it.countBackgroundAudio && isActiveByLocation(it.geoFence) }
            if (entries.isNullOrEmpty()) continue
            if (pkg == lastForegroundPkg) continue // foreground use is handled by the live timer
            val used = foregroundMillisToday(pkg) + audioMillisToday(pkg)
            val overBudget = entries.any { used >= getUsageLimitForToday(it.config) * 60_000L }
            if (overBudget) requestAudioSilence(pkg)
        }
    }

    /**
     * Tell the listener to pause+dismiss the package's media. Safe to call
     * repeatedly — the listener treats it as a re-poke (handles apps that
     * re-post media controls after the first dismissal).
     */
    private fun requestAudioSilence(pkg: String) {
        if (!shouldSilenceWhenBlocked(pkg)) return
        audioSilencedPackages.add(pkg)
        MediaNotifSilencer.sendSilence(service, pkg)
    }

    private fun releaseAudioSilence(pkg: String) {
        if (audioSilencedPackages.remove(pkg)) {
            MediaNotifSilencer.sendUnsilence(service, pkg)
        }
    }

    private fun getUsageLimitForToday(config: AppUsageConfig): Long {
        return if (config.isDailyUniform) {
            config.uniformLimit
        } else {
            val calendar = Calendar.getInstance()
            val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK) - 1
            config.dailyLimits[dayOfWeek]
        }
    }

    /**
     * Whether a group with [geo] should currently be treated as active given
     * the latest known location.
     *
     *  - Disabled gate  -> always active (legacy / location-agnostic groups).
     *  - No fix yet      -> governed by the global
     *                       [blockWhenLocationUnavailable] choice: fail open
     *                       (don't block) by default, or fail closed (stay
     *                       active) when the user opts in.
     *  - INSIDE  mode    -> active while within any point's radius.
     *  - OUTSIDE mode    -> active while beyond every point's radius.
     */
    private fun isActiveByLocation(geo: GeoFenceConfig): Boolean {
        if (!geo.enabled) return true
        val points = geo.resolvedPoints
        if (points.isEmpty()) return true
        // distanceTo only returns null when there is no location fix at all, so
        // either all points resolve to a distance or none do.
        val distances = points.mapNotNull { p ->
            locationProvider.distanceTo(p.latitude, p.longitude)?.let { it to p.radiusMeters }
        }
        if (distances.isEmpty()) return blockWhenLocationUnavailable
        val insideAny = distances.any { (distance, radius) -> distance <= radius }
        return when (geo.mode) {
            GeoFenceMode.INSIDE -> insideAny
            GeoFenceMode.OUTSIDE -> !insideAny
        }
    }

    /** True if any active group covering any package opts into a geofence. */
    private fun anyGeoFenceActive(): Boolean {
        return blockedAppsList.values.any { list -> list.any { it.geoFence.enabled } } ||
            timeBlockedAppsList.values.any { list -> list.any { it.geoFence.enabled } }
    }

    /**
     * A new location fix arrived. Re-evaluate the app currently in the
     * foreground so crossing a geofence boundary takes effect immediately,
     * without waiting for the next accessibility event.
     */
    private fun onLocationChanged() {
        val pkg = service.rootInActiveWindow?.packageName?.toString() ?: return
        if (essentialPackages.contains(pkg)) return
        lastPackage = "" // allow re-processing of the same package
        evaluatePackage(pkg)
    }

    private fun loadPersistedData() {
        val cooldownKeys = prefs.getStringSet("cooldown_keys", setOf()) ?: setOf()
        cooldownKeys.forEach { packageName ->
            val endTime = prefs.getLong("cooldown_$packageName", 0L)
            if (endTime > System.currentTimeMillis()) {
                cooldownAppsList[packageName] = endTime
            }
        }
        loadPersistedAudioTotals()
    }

    /**
     * Cold-start fallback for [backgroundAudioMillisToday]: the tracker also
     * re-broadcasts on its own (and we request it on setup), but reading our own
     * last snapshot avoids a window where the budget looks empty before the
     * first broadcast lands. Discarded if it belongs to a previous day.
     */
    private fun loadPersistedAudioTotals() {
        val savedDay = prefs.getLong("audio_day", LocalDate.now().toEpochDay())
        backgroundAudioDay = savedDay
        backgroundAudioMillisToday.clear()
        if (savedDay != LocalDate.now().toEpochDay()) return
        val keys = prefs.getStringSet("audio_keys", setOf()) ?: setOf()
        keys.forEach { pkg ->
            val v = prefs.getLong("audio_$pkg", 0L)
            if (v > 0L) backgroundAudioMillisToday[pkg] = v
        }
    }

    private fun persistAudioTotals() {
        prefs.edit {
            putLong("audio_day", backgroundAudioDay)
            putStringSet("audio_keys", backgroundAudioMillisToday.keys)
            backgroundAudioMillisToday.forEach { (pkg, v) -> putLong("audio_$pkg", v) }
        }
    }

    private fun persistCooldownData() {
        prefs.edit {
            putStringSet("cooldown_keys", cooldownAppsList.keys)
            cooldownAppsList.forEach { (packageName, endTime) ->
                putLong("cooldown_$packageName", endTime)
            }
        }
    }

    private fun putCooldownTo(packageName: String, realTimeEnd: Long) {
        cooldownAppsList[packageName] = realTimeEnd
        persistCooldownData()

    }

    private fun removeCooldownFrom(packageName: String) {
        cooldownAppsList.remove(packageName)
        prefs.edit {
            remove("cooldown_$packageName")
            putStringSet("cooldown_keys", cooldownAppsList.keys)
        }
    }

    /**
     * Synthesizes the strictest warning config across every active group
     * covering [packageName]. The user must satisfy the constraints of EVERY
     * group at once, so each field is aggregated to its most-restrictive
     * value — otherwise adding a looser group would weaken the proceed wait,
     * the typing/QR/intent requirements, or the proceed-limit budget.
     *
     * Rules:
     *  - `timeInterval` (unlock-grant length): min
     *  - `proceedDelayInSecs` (wait before proceed enables): max
     *  - `proceedsTimeWindowMn` (proceed-budget window): max
     *  - `allowedProceeds` (proceeds per window): min (when any group enables the limit)
     *  - Any boolean restriction (`isProceedDisabled`, `isWarningDialogHidden`,
     *    `isQrUnlockRequirementEnabled`, `isTypingRequirementEnabled`,
     *    `isIntentRequirementEnabled`, `proceedLimitEnabled`,
     *    `vibrateAndIncBrightness`): true if ANY group sets it
     *  - `isDynamicIntervalSettingAllowed`: only true if ALL groups allow it
     *    (dynamic = user picks, which is looser)
     *  - `qrKeys`: union — required so any of the user's QR codes still works
     *  - `typingSentence`: longest non-empty (more effort = stricter), picked
     *    only from groups that enable typing
     *  - `message`: first non-default it finds; purely informational
     */
    private fun mergedStrictestWarning(packageName: String): AppBlockerWarningScreenConfig? {
        val warnings = buildList {
            timeBlockedAppsList[packageName]?.forEach { if (isActiveByLocation(it.geoFence)) add(it.warning) }
            blockedAppsList[packageName]?.forEach { if (isActiveByLocation(it.geoFence)) add(it.warning) }
        }
        if (warnings.isEmpty()) return null

        val defaults = AppBlockerWarningScreenConfig()
        val anyTypingEnabled = warnings.any { it.isTypingRequirementEnabled }
        val anyProceedLimit = warnings.any { it.proceedLimitEnabled }
        val mergedQrKeys = warnings.flatMap { it.qrKeys.entries }.associate { it.key to it.value }

        return AppBlockerWarningScreenConfig(
            message = warnings.firstOrNull { it.message != defaults.message }?.message ?: defaults.message,
            timeInterval = warnings.minOf { it.timeInterval },
            isDynamicIntervalSettingAllowed = warnings.all { it.isDynamicIntervalSettingAllowed },
            isProceedDisabled = warnings.any { it.isProceedDisabled },
            isWarningDialogHidden = warnings.any { it.isWarningDialogHidden },
            proceedDelayInSecs = warnings.maxOf { it.proceedDelayInSecs },
            vibrateAndIncBrightness = warnings.any { it.vibrateAndIncBrightness },
            proceedLimitEnabled = anyProceedLimit,
            allowedProceeds = if (anyProceedLimit) {
                warnings.filter { it.proceedLimitEnabled }.minOf { it.allowedProceeds }
            } else defaults.allowedProceeds,
            proceedsTimeWindowMn = if (anyProceedLimit) {
                warnings.filter { it.proceedLimitEnabled }.maxOf { it.proceedsTimeWindowMn }
            } else defaults.proceedsTimeWindowMn,
            isQrUnlockRequirementEnabled = warnings.any { it.isQrUnlockRequirementEnabled },
            qrKeys = mergedQrKeys,
            isTypingRequirementEnabled = anyTypingEnabled,
            typingSentence = if (anyTypingEnabled) {
                warnings.filter { it.isTypingRequirementEnabled && it.typingSentence.isNotEmpty() }
                    .maxByOrNull { it.typingSentence.length }
                    ?.typingSentence ?: ""
            } else "",
            isIntentRequirementEnabled = warnings.any { it.isIntentRequirementEnabled },
        )
    }

    private fun getEndTimeInRealTimeMillis(config: AppTimeConfig): Long? {
        val calendar = Calendar.getInstance()
        val currentMinutes = TimeTools.convertToMinutesFromMidnight(
            calendar.get(Calendar.HOUR_OF_DAY),
            calendar.get(Calendar.MINUTE)
        )
        val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK) - 1

        Log.d("day of week", dayOfWeek.toString())
        val intervals = if (config.isEveryday) config.everydayIntervals else config.dailyIntervals[dayOfWeek] ?: emptyList()

        intervals.forEach { interval ->
            val startMinutes = TimeTools.convertToMinutesFromMidnight(interval.startHour, interval.startMinute)
            val endMinutes = TimeTools.convertToMinutesFromMidnight(interval.endHour, interval.endMinute)

            if (startMinutes <= endMinutes) {
                if (currentMinutes in startMinutes until endMinutes) {
                    val remainingMins = endMinutes - currentMinutes
                    return System.currentTimeMillis() + (remainingMins * 60_000L)
                }
            } else {
                if (currentMinutes >= startMinutes || currentMinutes < endMinutes) {
                    val remainingMins = if (currentMinutes >= startMinutes) {
                        (1440 - currentMinutes) + endMinutes
                    } else {
                        endMinutes - currentMinutes
                    }
                    return System.currentTimeMillis() + (remainingMins * 60_000L)
                }
            }
        }
        return null
    }

    private fun setUpForcedRefreshChecker(coolPackage: String, realTimeEndMillis: Long) {
        // Cancel any existing timer for THIS specific package
        activeRunnables[coolPackage]?.let { handler.removeCallbacks(it) }

        val delayMillis = realTimeEndMillis - System.currentTimeMillis()
        if (delayMillis <= 0) return // Time is already up

        val runnable = Runnable {
            try {
                val isForeground = service.rootInActiveWindow?.packageName == coolPackage
                if (isForeground) {
                    removeCooldownFrom(coolPackage)
                    showWarningScreen(coolPackage)
                    lastPackage = ""
                } else if (shouldSilenceWhenBlocked(coolPackage)) {
                    // App's window expired or its usage budget ran out while
                    // it's playing in the background. We can't render a
                    // warning here, but we can drop its media session + notif
                    // so the user can't keep playing from the shade.
                    removeCooldownFrom(coolPackage)
                    requestAudioSilence(coolPackage)
                }
            } catch (e: Exception) {
                Log.e("AppBlocker", "Recheck error: $e")
                // Retry in 1 minute if UI check failed
                setUpForcedRefreshChecker(coolPackage, System.currentTimeMillis() + 60_000L)
            } finally {
                activeRunnables.remove(coolPackage) // Clean up memory
            }
        }

        activeRunnables[coolPackage] = runnable
        handler.postDelayed(runnable, delayMillis)
    }

    private fun showWarningScreen(packageName: String) {
        notificationManager.stopTimer()
        // Back-then-Home: if this blocked app was opened from another (unblocked)
        // app it sits on top of that app's task; a plain Home would leave it
        // there and re-trap the user when they return. Back pops it off first.
        service.pressBackThenHome()
        lastPackage = ""

        // Silence any background audio session up-front; press-home + warning
        // dialog don't stop a media session from playing on their own.
        requestAudioSilence(packageName)

        // Use the strictness-merged config so the warning screen reflects
        // every group's constraints at once — not just whichever entry was
        // first to trigger the block.
        val warning = mergedStrictestWarning(packageName) ?: return
        if (warning.isWarningDialogHidden) return

        handler.postDelayed({
            val dialogIntent = Intent(service, WarningActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                putExtra("mode", Constants.WARNING_SCREEN_MODE_APP_BLOCKER)
                putExtra("result_id", packageName)
                putExtra("warning_config", Gson().toJson(warning))
            }
            service.startActivity(dialogIntent)
        }, 300)
    }

    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            when (intent.action) {
                INTENT_ACTION_REFRESH_APP_BLOCKER -> setupAppBlocker(service)
                INTENT_ACTION_REFRESH_APP_BLOCKER_COOLDOWN -> handlePutCooldownIntentBroadcast(intent)
                INTENT_ACTION_REFRESH_GEOFENCE_LOCATION -> scheduleGeofenceRefresh()
                MediaNotifSilencer.ACTION_AUDIO_TOTALS -> handleAudioTotalsBroadcast(intent)
            }
        }
    }

    /**
     * Friction against summoning the location indicator on demand: instead of
     * firing a fix immediately (which lights the status-bar location dot right
     * away, letting the user tap it to force-stop the app), schedule it at a
     * random moment within the next [GEOFENCE_REFRESH_MAX_DELAY_MS]. Repeat
     * presses while one is pending are ignored, so the timing can't be re-rolled
     * by spamming the button.
     */
    private fun scheduleGeofenceRefresh() {
        if (!::locationProvider.isInitialized || geofenceRefreshScheduled) return
        geofenceRefreshScheduled = true
        val delay = Random.nextLong(GEOFENCE_REFRESH_MAX_DELAY_MS)
        handler.postDelayed({
            geofenceRefreshScheduled = false
            locationProvider.requestSingleUpdate()
        }, delay)
    }
}