package neth.iecal.curbox.services

import android.annotation.SuppressLint
import android.app.Notification
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.os.Build
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * Listener that silences a per-package media session and dismisses its
 * media-style notification on request. Targets only the named package, so
 * other apps' audio (legit podcasts, calls, etc.) is untouched.
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
    }

    private val silenced: MutableSet<String> = mutableSetOf()
    private var receiverRegistered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val pkg = intent?.getStringExtra(EXTRA_PACKAGE) ?: return
            when (intent.action) {
                ACTION_SILENCE_PKG -> {
                    silenced.add(pkg)
                    pauseAndDismiss(pkg)
                }
                ACTION_UNSILENCE_PKG -> silenced.remove(pkg)
            }
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onListenerConnected() {
        super.onListenerConnected()
        if (!receiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(ACTION_SILENCE_PKG)
                addAction(ACTION_UNSILENCE_PKG)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(receiver, filter)
            }
            receiverRegistered = true
        }
    }

    override fun onListenerDisconnected() {
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
}
