package neth.iecal.curbox.blockers

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import neth.iecal.curbox.data.models.AntiUninstallConfig
import neth.iecal.curbox.services.BaseBlockingService
import java.util.Locale

class AntiUninstallBlocker : BaseBlocker() {

    companion object {
        private const val TAG = "AntiUninstall"

        private val SETTINGS_PACKAGES = setOf(
            "com.android.settings",
            "com.google.android.packageinstaller",
            "com.android.packageinstaller",
            "com.google.android.permissioncontroller",
            "com.android.permissioncontroller",
            "com.google.android.safetycenter",
            "com.miui.securitycenter",
            "com.samsung.android.app.appsedge"
        )

        private val APP_LABEL_NEEDLES = listOf("curbox")
        private const val POLL_INTERVAL_MS = 1000L
    }

    private lateinit var service: BaseBlockingService
    @Volatile private var config: AntiUninstallConfig = AntiUninstallConfig()
    private var lastBlockTimestamp: Float = 0f
    private var lastLoggedPkg: String? = null
    private var lastLoggedEnabled: Boolean? = null

    private val handler = Handler(Looper.getMainLooper())
    private val pollRunnable = object : Runnable {
        override fun run() {
            try {
                runCheck(source = "poll")
            } catch (t: Throwable) {
                Log.e(TAG, "poll threw", t)
            }
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    fun doAntiUninstallCheck(event: AccessibilityEvent?) {
        runCheck(source = "event")
    }

    private fun runCheck(source: String) {
        if (lastLoggedEnabled != config.isEnabled) {
            Log.d(TAG, "config.isEnabled=${config.isEnabled} mode=${config.mode}")
            lastLoggedEnabled = config.isEnabled
        }
        if (!config.isEnabled) return
        val root = service.rootInActiveWindow
        if (root == null) {
            Log.d(TAG, "[$source] rootInActiveWindow is null")
            return
        }
        try {
            val pkg = root.packageName?.toString()
            if (pkg != lastLoggedPkg) {
                Log.d(TAG, "[$source] active window pkg=$pkg")
                lastLoggedPkg = pkg
            }
            if (pkg != null && !SETTINGS_PACKAGES.contains(pkg)) return
            val matched = nodeTreeMentionsApp(root)
            Log.d(TAG, "[$source] pkg=$pkg matchedCurbox=$matched")
            if (matched) {
                if (isDelayOver(lastBlockTimestamp, 500)) {
                    Log.w(TAG, "[$source] BLOCKING: pressing home")
                    service.pressHome()
                    lastBlockTimestamp = android.os.SystemClock.uptimeMillis().toFloat()
                }
            }
        } finally {
            root.recycle()
        }
    }

    private fun nodeTreeMentionsApp(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        if (nodeMatches(node.text) || nodeMatches(node.contentDescription)) return true
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = try {
                nodeTreeMentionsApp(child)
            } finally {
                child.recycle()
            }
            if (found) return true
        }
        return false
    }

    private fun nodeMatches(value: CharSequence?): Boolean {
        val lowered = value?.toString()?.lowercase(Locale.getDefault()) ?: return false
        return APP_LABEL_NEEDLES.any { lowered.contains(it) }
    }

    fun setupBlocker(service: BaseBlockingService) {
        this.service = service
        Log.d(TAG, "setupBlocker called")
        CoroutineScope(Dispatchers.IO).launch {
            service.dataStoreManager.settings.collectLatest { settings ->
                config = settings.antiUninstallConfig
                Log.d(TAG, "config updated: isEnabled=${config.isEnabled} mode=${config.mode} block=${config.blockConfigChanges}")
            }
        }
        handler.postDelayed(pollRunnable, POLL_INTERVAL_MS)
    }
}
