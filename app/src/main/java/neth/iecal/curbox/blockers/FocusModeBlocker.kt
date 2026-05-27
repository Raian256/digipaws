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
import neth.iecal.curbox.utils.AppSuspendHelper
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
        const val INTENT_ACTION_AUTO_FOCUS_RELEASE = "neth.iecal.curbox.auto_focus.release"
        const val INTENT_ACTION_UNSUSPEND_ALL = "neth.iecal.curbox.unsuspend_all_apps"
        private const val AUTO_FOCUS_NOTIFICATION_ID = 2001
        private const val AUTO_FOCUS_CHANNEL_ID = "AutoFocusChannel"
        private const val RELEASE_ALARM_REQUEST_CODE = 9001
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

    private var currentlySuspendedPackages = setOf<String>()
    private var lastEvaluatedMinute = -1

    private fun pruneExpiredAutoFocusResumes(): Boolean {
        if (autoFocusResumeAt.isEmpty()) return false
        val now = System.currentTimeMillis()
        val expired = autoFocusResumeAt.filter { it.value <= now }.keys.toList()
        if (expired.isEmpty()) return false
        for (id in expired) {
            autoFocusResumeAt.remove(id)
            dismissedAutoFocusGroupIds.remove(id)
        }
        return true
    }

    private fun updateSuspendedPackages(serviceContext: Context) {
        pruneExpiredAutoFocusResumes()
        val newSuspendedPackages = mutableSetOf<String>()

                var shouldDndBeOn = false
        focusModeData?.focusGroupData?.let { group ->
            if (group.autoTurnOnDnd) shouldDndBeOn = true
            newSuspendedPackages.addAll(
                AppSuspendHelper.getPackagesToSuspend(serviceContext, group.blockMode, group.packages, essentialPackages)
            )
        }

        val now = Calendar.getInstance()
        val calDay = now.get(Calendar.DAY_OF_WEEK)
        val currentDay = if (calDay == Calendar.SUNDAY) 6 else calDay - 2
        val currentMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)

        for (group in autoFocusGroups) {
            if (dismissedAutoFocusGroupIds.contains(group.groupId)) continue
            val intervals = group.dailyIntervals[currentDay] ?: continue
            val isInInterval = intervals.any { isWithinInterval(currentMinutes, it) }
                        if (isInInterval) {
                if (group.autoTurnOnDnd) shouldDndBeOn = true
                newSuspendedPackages.addAll(
                    AppSuspendHelper.getPackagesToSuspend(serviceContext, group.blockMode, group.packages, essentialPackages)
                )
            }
        }

        val toSuspend = newSuspendedPackages - currentlySuspendedPackages
        val toUnsuspend = currentlySuspendedPackages - newSuspendedPackages

        if (toSuspend.isNotEmpty()) {
            AppSuspendHelper.suspendApps(toSuspend.toList())
        }
        if (toUnsuspend.isNotEmpty()) {
            AppSuspendHelper.unsuspendApps(toUnsuspend.toList())
        }

        currentlySuspendedPackages = newSuspendedPackages
        
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
        updateSuspendedPackages(service)
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

        if (pruneExpiredAutoFocusResumes()) {
            updateSuspendedPackages(service)
        }

        fun performBlock() {
            service.pressHome()
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
            service.pressHome()
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
            updateSuspendedPackages(service)
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
                cancelReleaseAlarm()
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

    private fun scheduleReleaseAlarm() {
        val nextRelease = pendingExitTimes.values.minOrNull() ?: return
        val am = service.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        val intent = Intent(INTENT_ACTION_AUTO_FOCUS_RELEASE).setPackage(service.packageName)
        val pi = PendingIntent.getBroadcast(
            service, RELEASE_ALARM_REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        am.cancel(pi)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            am.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, nextRelease, pi)
        } else {
            am.setExact(android.app.AlarmManager.RTC_WAKEUP, nextRelease, pi)
        }
    }

    private fun cancelReleaseAlarm() {
        val am = service.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        val intent = Intent(INTENT_ACTION_AUTO_FOCUS_RELEASE).setPackage(service.packageName)
        val pi = PendingIntent.getBroadcast(
            service, RELEASE_ALARM_REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        am.cancel(pi)
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
            addAction(INTENT_ACTION_AUTO_FOCUS_RELEASE)
            addAction(INTENT_ACTION_UNSUSPEND_ALL)
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
                dismissedAutoFocusGroupIds.clear()
                updateSuspendedPackages(service)
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
                        scheduleReleaseAlarm()
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
                        updateSuspendedPackages(service)
                    }
                }
                INTENT_ACTION_CANCEL_EXIT_AUTO_FOCUS -> {
                    if (pendingExitTimes.isNotEmpty()) {
                        pendingExitTimes.clear()
                        pendingExitPauseMs.clear()
                        cancelReleaseAlarm()
                        autoFocusNotificationShown = false
                    }
                    lastPackage = ""
                    updateSuspendedPackages(service)
                }
                INTENT_ACTION_AUTO_FOCUS_RELEASE -> {
                    val now = System.currentTimeMillis()
                    val ready = pendingExitTimes.filter { it.value <= now }.keys.toList()
                    if (ready.isNotEmpty()) {
                        for (groupId in ready) {
                            pendingExitTimes.remove(groupId)
                            dismissedAutoFocusGroupIds.add(groupId)
                            val pauseMs = pendingExitPauseMs.remove(groupId)
                            if (pauseMs != null && pauseMs > 0) {
                                autoFocusResumeAt[groupId] = now + pauseMs
                            }
                        }
                        hideAutoFocusNotification(wasForceStopped = true)
                    }
                    if (pendingExitTimes.isNotEmpty()) {
                        scheduleReleaseAlarm()
                    }
                    lastPackage = ""
                    updateSuspendedPackages(service)
                }
                INTENT_ACTION_UNSUSPEND_ALL -> {
                    AppSuspendHelper.unsuspendAllApps(context ?: service)
                }
            }
        }
    }
}