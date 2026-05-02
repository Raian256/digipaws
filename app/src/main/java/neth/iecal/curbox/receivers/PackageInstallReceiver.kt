package neth.iecal.curbox.receivers

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import neth.iecal.curbox.R
import neth.iecal.curbox.blockers.AppBlocker
import neth.iecal.curbox.data.models.AppGroup
import neth.iecal.curbox.ui.activity.FragmentActivity
import neth.iecal.curbox.utils.DataStoreManager

class PackageInstallReceiver : BroadcastReceiver() {

    companion object {
        private const val CHANNEL_ID = "auto_add_new_apps_channel"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_PACKAGE_ADDED) return
        if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return
        val pkg = intent.data?.schemeSpecificPart ?: return
        val appContext = context.applicationContext
        if (pkg == appContext.packageName) return

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dsm = DataStoreManager(appContext)
                val current = dsm.settings.first()

                val matchedGroups = mutableListOf<AppGroup>()
                val updated = current.blockedAppGroups.map { group ->
                    if (group.autoAddNewApps && pkg !in group.selectedPackages) {
                        matchedGroups += group
                        group.copy(selectedPackages = group.selectedPackages + pkg)
                    } else group
                }

                if (matchedGroups.isEmpty()) return@launch

                dsm.updateAppGroups(updated)
                appContext.sendBroadcast(Intent(AppBlocker.INTENT_ACTION_REFRESH_APP_BLOCKER))
                notify(appContext, pkg, matchedGroups.map { it.name })
            } finally {
                pending.finish()
            }
        }
    }

    private fun notify(context: Context, pkg: String, groupNames: List<String>) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.auto_add_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.auto_add_channel_description)
            }
        )

        val appLabel = appLabelOf(context, pkg)
        val text = if (groupNames.size == 1) {
            context.getString(R.string.auto_add_notification_text, appLabel, groupNames[0])
        } else {
            context.getString(R.string.auto_add_notification_text_multi, appLabel, groupNames.size)
        }

        val tapIntent = Intent(context, FragmentActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, pkg.hashCode(), tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_app_blocker_aesthetic)
            .setContentTitle(context.getString(R.string.auto_add_notification_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        nm.notify(pkg.hashCode(), notification)
    }

    private fun appLabelOf(context: Context, pkg: String): String {
        val pm = context.packageManager
        return try {
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            pkg
        }
    }
}
