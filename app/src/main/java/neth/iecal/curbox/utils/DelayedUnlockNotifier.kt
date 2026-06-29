package neth.iecal.curbox.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

/**
 * Notifications for the "delayed unlock" flow: a background wait the user starts
 * from the warning screen and can walk away from. While the wait runs we show an
 * ongoing countdown with a "Stop" action that cancels it; once the wait is over
 * and the unlock window is open, a (dismissable) countdown to re-lock is shown.
 *
 * One notification per package (id derived from the package name) so several
 * apps can have independent waits at once, and so it never collides with
 * [TimerNotification]'s shared id.
 */
class DelayedUnlockNotifier(private val context: Context) {

    companion object {
        private const val CHANNEL_ID = "DelayedUnlockChannel"
        private const val BASE_NOTIFICATION_ID = 2000

        /** Stop action broadcast — handled by the app blocker's receiver. */
        const val ACTION_STOP = "neth.iecal.curbox.delayedunlock.stop"

        private fun notificationIdFor(pkg: String): Int =
            BASE_NOTIFICATION_ID + (pkg.hashCode() and 0xFFFF)
    }

    private val notificationManager: NotificationManager by lazy {
        context.applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    init {
        runCatching {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Delayed unlocks",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background waits before a blocked app unlocks"
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    /** Ongoing countdown until [readyAtMillis] (wall-clock), with a Stop action. */
    fun showWaiting(pkg: String, label: String, readyAtMillis: Long) {
        runCatching {
            val stopIntent = Intent(ACTION_STOP).apply {
                setPackage(context.packageName)
                putExtra("result_id", pkg)
            }
            val stopPending = PendingIntent.getBroadcast(
                context,
                pkg.hashCode(),
                stopIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            val notification = NotificationCompat.Builder(context.applicationContext, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentTitle("Waiting to unlock $label")
                .setContentText("Unlocks when the wait is over.")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(true)
                .setUsesChronometer(true)
                .setChronometerCountDown(true)
                .setWhen(readyAtMillis)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop cooldown", stopPending)
                .build()

            notificationManager.notify(notificationIdFor(pkg), notification)
        }
    }

    /** Dismissable countdown until the unlock window closes at [windowEndMillis]. */
    fun showReady(pkg: String, label: String, windowEndMillis: Long) {
        runCatching {
            val notification = NotificationCompat.Builder(context.applicationContext, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setContentTitle("$label is unlocked")
                .setContentText("Open it before the time runs out.")
                .setOnlyAlertOnce(true)
                .setShowWhen(true)
                .setUsesChronometer(true)
                .setChronometerCountDown(true)
                .setWhen(windowEndMillis)
                .build()

            notificationManager.notify(notificationIdFor(pkg), notification)
        }
    }

    fun cancel(pkg: String) {
        runCatching { notificationManager.cancel(notificationIdFor(pkg)) }
    }
}
