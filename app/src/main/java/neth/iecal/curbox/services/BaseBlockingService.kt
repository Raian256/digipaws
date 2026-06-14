package neth.iecal.curbox.services

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import neth.iecal.curbox.data.db.AccessibilityDisableLogEntity
import neth.iecal.curbox.data.db.AppDatabase
import neth.iecal.curbox.utils.DataStoreManager
import neth.iecal.curbox.utils.PermissionUtils
import neth.iecal.curbox.anti_stimulants.MindfulMessageTracker
import kotlin.lazy

open class BaseBlockingService : AccessibilityService() {

    companion object {
        // Gap between the Back and the Home press in [pressBackThenHome]. Long
        // enough for the Back to finish() the blocked activity (pop it off the
        // host task's stack) before Home backgrounds the task.
        private const val BACK_THEN_HOME_DELAY_MS = 120L

        // How long accessibility-disable records are kept on-device (~1 year).
        private const val DISABLE_LOG_RETENTION_MS = 365L * 24 * 60 * 60 * 1000
    }

    val dataStoreManager  by lazy {
        DataStoreManager(this)
    }

    private val actionHandler = Handler(Looper.getMainLooper())


    var lastBackPressTimeStamp: Long =
        SystemClock.uptimeMillis() // prevents repetitive global actions

    override fun onServiceConnected() {
        super.onServiceConnected()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    override fun onInterrupt() {
    }


    fun isDelayOver(lastTimestamp: Long, delay: Int): Boolean {
        val currentTime = SystemClock.uptimeMillis().toFloat()
        return currentTime - lastTimestamp > delay
    }

    fun pressHome() {
        performGlobalAction(GLOBAL_ACTION_HOME)
        lastBackPressTimeStamp = SystemClock.uptimeMillis()

    }

    fun pressBack() {
            performGlobalAction(GLOBAL_ACTION_BACK)
            lastBackPressTimeStamp = SystemClock.uptimeMillis()

    }

    /**
     * Use this (instead of [pressHome]) when a blocked *app* has just been
     * brought to the foreground, because it may have been launched on top of
     * another, unblocked app within the same task (e.g. app A opens app B via
     * an intent/deep link). A plain Home press leaves the blocked activity at
     * the top of that task's back stack, so the next time the user returns to
     * the unblocked app the task resumes the blocked activity and they're stuck
     * behind it. Pressing Back first pops the blocked activity off the stack;
     * Home then sends the user to the launcher.
     *
     * When the blocked app is the root of its own task this is harmless: Back
     * goes home/exits the app and the follow-up Home guarantees we land on the
     * launcher regardless.
     */
    fun pressBackThenHome() {
        performGlobalAction(GLOBAL_ACTION_BACK)
        actionHandler.postDelayed({
            performGlobalAction(GLOBAL_ACTION_HOME)
        }, BACK_THEN_HOME_DELAY_MS)
        lastBackPressTimeStamp = SystemClock.uptimeMillis()
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        actionHandler.removeCallbacksAndMessages(null)
        recordDeliberateDisable()
        return super.onUnbind(intent)
    }

    /**
     * onUnbind fires both when the user turns the service off and when the system
     * tears it down (reboot, app update, low-memory kill). We only want to count
     * the former. The discriminator: when the *user* disables the service via
     * Settings, the component is removed from [Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES]
     * before teardown; on a system teardown the user's preference is left intact
     * and the component is still listed. So if we are no longer in that list, the
     * user did this on purpose — log it.
     *
     * The write is done with runBlocking because the hosting process may be killed
     * shortly after onUnbind returns; a fire-and-forget coroutine could be lost.
     */
    private fun recordDeliberateDisable() {
        try {
            if (PermissionUtils.isAccessibilityServiceEnabled(this, this::class.java)) {
                // Still enabled in the user's preference -> system teardown, not a disable.
                return
            }
            val cutoff = System.currentTimeMillis() - DISABLE_LOG_RETENTION_MS
            runBlocking(Dispatchers.IO) {
                AppDatabase.getInstance(applicationContext)
                    .accessibilityDisableLogDao()
                    .insertAndPrune(
                        AccessibilityDisableLogEntity(
                            timestamp = System.currentTimeMillis(),
                            serviceName = this@BaseBlockingService::class.java.simpleName
                        ),
                        cutoff
                    )
            }
        } catch (t: Throwable) {
            Log.e("AccessibilityDisable", "Failed to record disable", t)
        }
    }
}
