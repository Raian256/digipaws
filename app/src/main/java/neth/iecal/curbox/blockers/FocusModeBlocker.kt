package neth.iecal.curbox.blockers

import neth.iecal.curbox.R

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Context.RECEIVER_EXPORTED
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import neth.iecal.curbox.Constants
import neth.iecal.curbox.data.models.AutoFocusGroup
import neth.iecal.curbox.data.models.FocusBlockMode
import neth.iecal.curbox.data.models.ManualFocusGroup
import neth.iecal.curbox.data.models.TimeInterval
import neth.iecal.curbox.services.BaseBlockingService
import neth.iecal.curbox.utils.SystemOverlayDetector
import neth.iecal.curbox.utils.TimerNotification
import neth.iecal.curbox.utils.getEssentialPackages
import java.util.Calendar

class FocusModeBlocker : BaseBlocker() {

    private data class ManualFocusModeData(
        val focusGroupData: ManualFocusGroup,
        val endTimeInMillis: Long
    )

    companion object {
        const val INTENT_ACTION_REFRESH_FOCUS_MODE = "neth.iecal.curbox.refresh.focus_mode"
        const val INTENT_ACTION_EXIT_AUTO_FOCUS = "neth.iecal.curbox.exit.auto_focus"
        const val INTENT_ACTION_CANCEL_EXIT_AUTO_FOCUS = "neth.iecal.curbox.cancel_exit.auto_focus"
        private const val AUTO_FOCUS_NOTIFICATION_ID = 2001
        private const val AUTO_FOCUS_CHANNEL_ID = "AutoFocusChannel"
        private const val BLOCKED_LOG_MAX_ENTRIES = 100
        // typeAllMask + flagRetrieveInteractiveWindows on the service makes
        // TYPE_WINDOWS_CHANGED arrive with packageName=null. Stringifying
        // null gives "null", which isn't in any whitelist or essential set,
        // so unfiltered processing would press Home on every window animation.
        private const val FOREGROUND_EVENT_MASK =
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
    }

    private var focusModeData: ManualFocusModeData? = null
    private var lastPackage = ""
    private lateinit var service: BaseBlockingService
    private lateinit var notificationManager: TimerNotification

    private var autoFocusGroups: List<AutoFocusGroup> = emptyList()
    private val dismissedAutoFocusGroupIds = mutableSetOf<String>()
    private val pendingExitTimes = mutableMapOf<String, Long>()
    private val pendingExitPauseMs = mutableMapOf<String, Long>()
    private val autoFocusResumeAt = mutableMapOf<String, Long>()
    private var autoFocusNotificationShown = false
    private var essentialPackages: Set<String> = emptySet()
    private var currentActiveAutoFocusGroupId: String? = null
    private var didReconcileAutoFocusSessions = false

    private var lastEvaluatedMinute = -1

    /**
     * Advance the exit/pause state machine purely from timestamps:
     *  - a cooldown that has elapsed exits its group and starts the pause,
     *  - a pause that has run its course resumes the group.
     *
     * This is time-driven on purpose. The release alarm is only a best-effort
     * wake-up; the actual transitions happen here, on every accessibility event
     * and minute tick, so a pause still applies even when the alarm never fires
     * (exact-alarm permission denied, Doze, or the service was recreated and
     * lost its in-memory alarm). Returns true if anything changed so callers
     * can re-evaluate suspensions.
     */
    private fun processAutoFocusExitSchedule(): Boolean {
        val now = System.currentTimeMillis()
        var changed = false

        if (pendingExitTimes.isNotEmpty()) {
            val ready = pendingExitTimes.filter { it.value <= now }.keys.toList()
            for (groupId in ready) {
                pendingExitTimes.remove(groupId)
                dismissedAutoFocusGroupIds.add(groupId)
                val pauseMs = pendingExitPauseMs.remove(groupId)
                if (pauseMs != null && pauseMs > 0) {
                    autoFocusResumeAt[groupId] = now + pauseMs
                }
                changed = true
            }
            if (ready.isNotEmpty()) {
                hideAutoFocusNotification(wasForceStopped = true)
            }
        }

        if (autoFocusResumeAt.isNotEmpty()) {
            val expired = autoFocusResumeAt.filter { it.value <= now }.keys.toList()
            for (id in expired) {
                autoFocusResumeAt.remove(id)
                dismissedAutoFocusGroupIds.remove(id)
                changed = true
            }
        }

        return changed
    }

    /**
     * Close auto-focus sessions that the DB still marks as running but which
     * aren't inside an active interval anymore (or whose group is gone). These
     * are left behind when the accessibility service is recreated mid-session:
     * the in-memory [autoFocusNotificationShown] flag resets, so the normal
     * end-of-interval close in [doFocusModeCheck] never fires and the stale
     * session reads as "running" forever. Runs once per service start, after
     * [autoFocusGroups] is populated. Sessions still inside their interval are
     * left untouched so a genuine restart mid-focus keeps the same session.
     */
    private suspend fun reconcileStaleAutoFocusSessions() {
        val statsDao = neth.iecal.curbox.data.db.AppDatabase.getInstance(service).focusStatsDao()
        val cal = Calendar.getInstance()
        val calDay = cal.get(Calendar.DAY_OF_WEEK)
        val currentDay = if (calDay == Calendar.SUNDAY) 6 else calDay - 2
        val currentMinutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val now = System.currentTimeMillis()
        val runningAuto = statsDao.getRunningSessions().filter { it.wasAutoFocus }
        for (session in runningAuto) {
            val group = autoFocusGroups.find { it.groupId == session.groupId }
            val stillActive = group != null &&
                group.dailyIntervals[currentDay]?.any { isWithinInterval(currentMinutes, it) } == true
            if (!stillActive) {
                statsDao.update(session.copy(status = 1, actualEndTimeInMillis = now))
            }
        }
    }

    private fun updateDndState(serviceContext: Context) {
        processAutoFocusExitSchedule()

        var shouldDndBeOn = false
        focusModeData?.focusGroupData?.let { group ->
            if (group.autoTurnOnDnd) shouldDndBeOn = true
        }

        val now = Calendar.getInstance()
        val calDay = now.get(Calendar.DAY_OF_WEEK)
        val currentDay = if (calDay == Calendar.SUNDAY) 6 else calDay - 2
        val currentMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)

        for (group in autoFocusGroups) {
            if (dismissedAutoFocusGroupIds.contains(group.groupId)) continue
            val intervals = group.dailyIntervals[currentDay] ?: continue
            val isInInterval = intervals.any { isWithinInterval(currentMinutes, it) }
            if (isInInterval && group.autoTurnOnDnd) shouldDndBeOn = true
        }

        applyDndState(serviceContext, shouldDndBeOn)
    }

    private var wasDndTurnedOnByUs = false

    private fun applyDndState(context: Context, shouldBeOn: Boolean) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!nm.isNotificationPolicyAccessGranted) return
        
        val currentFilter = nm.currentInterruptionFilter
        if (shouldBeOn) {
            if (currentFilter == NotificationManager.INTERRUPTION_FILTER_ALL) {
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
                wasDndTurnedOnByUs = true
            }
        } else {
            if (wasDndTurnedOnByUs && currentFilter != NotificationManager.INTERRUPTION_FILTER_ALL) {
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
                wasDndTurnedOnByUs = false
            }
        }
    }

    private fun turnOffFocusMode() {
        val groupId = focusModeData?.focusGroupData?.groupId
        focusModeData = null
        CoroutineScope(Dispatchers.IO).launch {
            if (groupId != null) {
                val db = neth.iecal.curbox.data.db.AppDatabase.getInstance(service)
                val statsDao = db.focusStatsDao()
                val runningSessions = statsDao.getRunningSessions().filter { !it.wasAutoFocus && it.groupId == groupId }
                for (session in runningSessions) {
                    statsDao.update(session.copy(status = 1, actualEndTimeInMillis = session.estimatedEndTimeInMillis))
                }
            }
            service.dataStoreManager.setManualFocusStateToInactive()
        }
        notificationManager.stopTimer()
        updateDndState(service)
    }

    fun doFocusModeCheck(event: AccessibilityEvent?) {
        if (event == null || (event.eventType and FOREGROUND_EVENT_MASK) == 0) return
        val packageName = event.packageName?.toString() ?: return
        if (lastPackage == packageName) return
        // System-signed overlays (Pixel Extreme Battery Saver "Use anyway?",
        // permission dialogs, package installer) briefly bring a non-app
        // package to the foreground on top of the real app. Pressing Home
        // would dismiss the dialog before the user can answer. Skip without
        // updating lastPackage so the underlying app's re-show still runs.
        if (SystemOverlayDetector.isSystemOverlay(service, event)) return
        lastPackage = packageName

        if (processAutoFocusExitSchedule()) {
            updateDndState(service)
        }

        fun performBlock() {
            // Back-then-Home so a blocked app opened from another (unblocked)
            // app gets popped off that app's task instead of being left on top
            // of it. See BaseBlockingService.pressBackThenHome.
            service.pressBackThenHome()
            lastPackage = ""
//            Toast.makeText(service, service.getString(R.string.this_app_is_currently_under_focus), Toast.LENGTH_LONG).show()
        }

        fun performAutoFocusBlock(blockedPackage: String, group: AutoFocusGroup) {
            Toast.makeText(
                service,
                service.getString(
                    R.string.auto_focus_blocked_banner,
                    blockedPackage,
                    group.groupName
                ),
                Toast.LENGTH_LONG
            ).show()
            service.pressBackThenHome()
            lastPackage = ""
            logBlockedApp(blockedPackage, group)
        }

        if (focusModeData != null) {
            when (focusModeData!!.focusGroupData.blockMode) {
                FocusBlockMode.BLOCK_SELECTED -> {
                    if (focusModeData!!.focusGroupData.packages.contains(packageName)
                        && !essentialPackages.contains(packageName)) performBlock()
                }
                FocusBlockMode.BLOCK_ALL_EXCEPT_SELECTED -> {
                    if (!focusModeData!!.focusGroupData.packages.contains(packageName)
                        && !essentialPackages.contains(packageName)) performBlock()
                }
            }
            if (focusModeData!!.endTimeInMillis < System.currentTimeMillis()) {
                turnOffFocusMode()
            }
        }

        val now = Calendar.getInstance()
        val calDay = now.get(Calendar.DAY_OF_WEEK)
        // UI saves: 0=Mon,1=Tue,...,6=Sun. Calendar: 1=Sun,2=Mon,...,7=Sat
        val currentDay = if (calDay == Calendar.SUNDAY) 6 else calDay - 2
        val currentMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        
        if (currentMinutes != lastEvaluatedMinute) {
            updateDndState(service)
            lastEvaluatedMinute = currentMinutes
        }
        
        var anyAutoFocusActive = false
        var activeAutoFocusGroupId: String? = null

        for (group in autoFocusGroups) {
            if (dismissedAutoFocusGroupIds.contains(group.groupId)) continue
            val intervals = group.dailyIntervals[currentDay] ?: continue
            val isInInterval = intervals.any { isWithinInterval(currentMinutes, it) }
            if (!isInInterval) continue

            anyAutoFocusActive = true
            activeAutoFocusGroupId = group.groupId
            val blocked = when (group.blockMode) {
                FocusBlockMode.BLOCK_SELECTED ->
                    group.packages.contains(packageName) && !essentialPackages.contains(packageName)
                FocusBlockMode.BLOCK_ALL_EXCEPT_SELECTED ->
                    !group.packages.contains(packageName) && !essentialPackages.contains(packageName)
            }
            if (blocked) {
                performAutoFocusBlock(packageName, group)
                break
            }
        }

        if (anyAutoFocusActive && !autoFocusNotificationShown) {
            showAutoFocusNotification(activeAutoFocusGroupId!!)
        } else if (!anyAutoFocusActive && autoFocusNotificationShown) {
            hideAutoFocusNotification()
            dismissedAutoFocusGroupIds.clear()
            autoFocusResumeAt.clear()
            if (pendingExitTimes.isNotEmpty()) {
                pendingExitTimes.clear()
                pendingExitPauseMs.clear()
            }
        }
    }

    private fun logBlockedApp(blockedPackage: String, group: AutoFocusGroup) {
        val entry = neth.iecal.curbox.data.db.BlockedAppLogEntity(
            timestamp = System.currentTimeMillis(),
            packageName = blockedPackage,
            groupId = group.groupId,
            groupName = group.groupName
        )
        CoroutineScope(Dispatchers.IO).launch {
            val dao = neth.iecal.curbox.data.db.AppDatabase.getInstance(service).blockedAppLogDao()
            dao.insertAndPrune(entry, BLOCKED_LOG_MAX_ENTRIES)
        }
    }

    private fun isWithinInterval(currentMinutes: Int, interval: TimeInterval): Boolean {
        val start = interval.startHour * 60 + interval.startMinute
        val end = interval.endHour * 60 + interval.endMinute
        return if (start <= end) {
            currentMinutes in start until end
        } else {
            currentMinutes >= start || currentMinutes < end
        }
    }

    private fun showAutoFocusNotification(groupId: String) {
        autoFocusNotificationShown = true
        currentActiveAutoFocusGroupId = groupId
        CoroutineScope(Dispatchers.IO).launch {
            val db = neth.iecal.curbox.data.db.AppDatabase.getInstance(service)
            val statsDao = db.focusStatsDao()
            val runningSessions = statsDao.getRunningSessions().filter { it.wasAutoFocus && it.groupId == groupId }
            if (runningSessions.isEmpty()) {
                val session = neth.iecal.curbox.data.db.FocusStatsEntity(
                    groupId = groupId,
                    wasAutoFocus = true,
                    startTimeInMillis = System.currentTimeMillis(),
                    estimatedEndTimeInMillis = System.currentTimeMillis(),
                    actualEndTimeInMillis = 0L,
                    status = 0
                )
                statsDao.insert(session)
            }
        }

        val nm = service.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val earliestPendingRelease = pendingExitTimes.values.minOrNull()

        val builder = NotificationCompat.Builder(service, AUTO_FOCUS_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setOngoing(true)
            .setOnlyAlertOnce(true)

        if (earliestPendingRelease != null) {
            builder.setContentTitle(service.getString(R.string.auto_focus_exit_pending_title))
                .setContentText(service.getString(R.string.auto_focus_exit_pending_text))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setShowWhen(true)
                .setWhen(earliestPendingRelease)
                .setUsesChronometer(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                builder.setChronometerCountDown(true)
            }
            val cancelIntent = Intent(INTENT_ACTION_CANCEL_EXIT_AUTO_FOCUS).setPackage(service.packageName)
            val cancelPi = PendingIntent.getBroadcast(
                service, 1, cancelIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                service.getString(R.string.cancel_exit),
                cancelPi
            )
        } else {
            builder.setContentTitle("Auto Focus is active")
                .setContentText("Scheduled focus mode is running")
                .setPriority(NotificationCompat.PRIORITY_LOW)
        }

        nm.notify(AUTO_FOCUS_NOTIFICATION_ID, builder.build())
    }

    private fun hideAutoFocusNotification(wasForceStopped: Boolean = false, targetGroupId: String? = null) {
        autoFocusNotificationShown = false
        val nm = service.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(AUTO_FOCUS_NOTIFICATION_ID)
        
        CoroutineScope(Dispatchers.IO).launch {
            val db = neth.iecal.curbox.data.db.AppDatabase.getInstance(service)
            val statsDao = db.focusStatsDao()
            
            val runningSessions = if (wasForceStopped && targetGroupId != null) {
                statsDao.getRunningSessions().filter { it.wasAutoFocus && it.groupId == targetGroupId }
            } else if (wasForceStopped) {
                val exitableIds = autoFocusGroups.filter { it.exitable }.map { it.groupId }
                statsDao.getRunningSessions().filter { it.wasAutoFocus && it.groupId in exitableIds }
            } else {
                statsDao.getRunningSessions().filter { it.wasAutoFocus && (currentActiveAutoFocusGroupId == null || it.groupId == currentActiveAutoFocusGroupId) }
            }
            
            val now = System.currentTimeMillis()
            for (session in runningSessions) {
                val newStatus = if (wasForceStopped) 2 else 1
                statsDao.update(session.copy(status = newStatus, actualEndTimeInMillis = now))
            }
            currentActiveAutoFocusGroupId = null
        }
    }

    private fun createAutoFocusNotificationChannel() {
        val nm = service.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            AUTO_FOCUS_CHANNEL_ID, "Auto Focus", NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Auto focus mode notifications"
            setSound(null, null)
            enableVibration(false)
        }
        nm.createNotificationChannel(channel)
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    fun setupReceivers() {
        val filter = IntentFilter().apply {
            addAction(INTENT_ACTION_REFRESH_FOCUS_MODE)
            addAction(INTENT_ACTION_EXIT_AUTO_FOCUS)
            addAction(INTENT_ACTION_CANCEL_EXIT_AUTO_FOCUS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            service.registerReceiver(refreshReceiver, filter, RECEIVER_EXPORTED)
        } else {
            service.registerReceiver(refreshReceiver, filter)
        }
    }

    fun removeReceivers() {
        service.unregisterReceiver(refreshReceiver)
    }

    fun setupFocusMode(service: BaseBlockingService) {
        this.service = service
        notificationManager = TimerNotification(service)
        createAutoFocusNotificationChannel()

        essentialPackages = getEssentialPackages(service)
        Log.d("essential package", essentialPackages.toString())
        CoroutineScope(Dispatchers.IO).launch {
            service.dataStoreManager.settings.collectLatest { settings ->
                essentialPackages = getEssentialPackages(service, settings.customEssentialPackages.toSet())
            }
        }
        CoroutineScope(Dispatchers.IO).launch {
            val db = neth.iecal.curbox.data.db.AppDatabase.getInstance(service)
            val statsDao = db.focusStatsDao()
            val runningSessions = statsDao.getRunningSessions()
            for (session in runningSessions) {
                if (!session.wasAutoFocus && session.estimatedEndTimeInMillis < System.currentTimeMillis()) {
                     statsDao.update(session.copy(status = 1, actualEndTimeInMillis = session.estimatedEndTimeInMillis))
                }
            }
        }

        CoroutineScope(Dispatchers.IO).launch {
            service.dataStoreManager.settings.collectLatest { settings ->

                if (settings.activeManualFocusGroupId.first != null) {
                    val currentFocusingGroup = settings.manualFocusGroups.find { it.groupId == settings.activeManualFocusGroupId.first }
                    if (currentFocusingGroup != null && settings.activeManualFocusGroupId.second > System.currentTimeMillis()) {
                        focusModeData = ManualFocusModeData(currentFocusingGroup, settings.activeManualFocusGroupId.second)
                        withContext(Dispatchers.Main) {
                            notificationManager.startTimer(
                                focusModeData!!.endTimeInMillis - System.currentTimeMillis(),
                                timerId = "focus_mode",
                                title = "Focus Mode is on"
                            )
                        }
                    }
                } else {
                    focusModeData = null
                    withContext(Dispatchers.Main) {
                        notificationManager.stopTimer()
                    }
                }

                autoFocusGroups = settings.autoFocusGroups
                // Drop exit/pause state for groups that no longer exist, but keep
                // pauses that are still counting down so editing an unrelated
                // setting doesn't silently resume a paused schedule.
                val validIds = autoFocusGroups.mapTo(mutableSetOf()) { it.groupId }
                autoFocusResumeAt.keys.retainAll(validIds)
                pendingExitTimes.keys.retainAll(validIds)
                pendingExitPauseMs.keys.retainAll(validIds)
                dismissedAutoFocusGroupIds.retainAll {
                    it in validIds && autoFocusResumeAt.containsKey(it)
                }
                if (!didReconcileAutoFocusSessions) {
                    reconcileStaleAutoFocusSessions()
                    didReconcileAutoFocusSessions = true
                }
                updateDndState(service)
            }
        }
    }

    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            when (intent.action) {
                INTENT_ACTION_REFRESH_FOCUS_MODE -> setupFocusMode(service)
                INTENT_ACTION_EXIT_AUTO_FOCUS -> {
                    val specificGroupId = intent.getStringExtra("group_id")
                    val pauseMinutes = intent.getIntExtra("pause_minutes", 0).coerceAtLeast(0)
                    val pauseMs = pauseMinutes * 60_000L
                    val targets = if (specificGroupId != null) {
                        autoFocusGroups.filter { it.groupId == specificGroupId }
                    } else {
                        autoFocusGroups.filter {
                            it.exitable && !dismissedAutoFocusGroupIds.contains(it.groupId)
                        }
                    }
                    val now = System.currentTimeMillis()
                    var maxScheduledMinutes = 0
                    var anyImmediate = false
                    var anyNewlyScheduled = false
                    for (group in targets) {
                        if (group.exitCooldownMinutes <= 0) {
                            dismissedAutoFocusGroupIds.add(group.groupId)
                            if (pauseMs > 0) autoFocusResumeAt[group.groupId] = now + pauseMs
                            anyImmediate = true
                        } else if (!pendingExitTimes.containsKey(group.groupId)) {
                            pendingExitTimes[group.groupId] = now + group.exitCooldownMinutes * 60_000L
                            if (pauseMs > 0) pendingExitPauseMs[group.groupId] = pauseMs
                            if (group.exitCooldownMinutes > maxScheduledMinutes) {
                                maxScheduledMinutes = group.exitCooldownMinutes
                            }
                            anyNewlyScheduled = true
                        }
                    }
                    if (anyImmediate) {
                        if (specificGroupId != null) {
                            hideAutoFocusNotification(wasForceStopped = true, targetGroupId = specificGroupId)
                        } else {
                            hideAutoFocusNotification(wasForceStopped = true)
                        }
                    }
                    if (anyNewlyScheduled) {
                        autoFocusNotificationShown = false
                        if (maxScheduledMinutes > 0) {
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                Toast.makeText(
                                    service,
                                    service.getString(R.string.exit_cooldown_started_toast, maxScheduledMinutes),
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                    if (anyImmediate || anyNewlyScheduled) {
                        lastPackage = ""
                        updateDndState(service)
                    }
                }
                INTENT_ACTION_CANCEL_EXIT_AUTO_FOCUS -> {
                    if (pendingExitTimes.isNotEmpty()) {
                        pendingExitTimes.clear()
                        pendingExitPauseMs.clear()
                        autoFocusNotificationShown = false
                    }
                    lastPackage = ""
                    updateDndState(service)
                }
            }
        }
    }
}