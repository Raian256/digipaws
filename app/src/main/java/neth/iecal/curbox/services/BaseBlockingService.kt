package neth.iecal.curbox.services

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import neth.iecal.curbox.utils.DataStoreManager
import neth.iecal.curbox.anti_stimulants.MindfulMessageTracker
import kotlin.lazy

open class BaseBlockingService : AccessibilityService() {

    companion object {
        // Gap between the Back and the Home press in [pressBackThenHome]. Long
        // enough for the Back to finish() the blocked activity (pop it off the
        // host task's stack) before Home backgrounds the task.
        private const val BACK_THEN_HOME_DELAY_MS = 120L
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
        return super.onUnbind(intent)
    }
}
