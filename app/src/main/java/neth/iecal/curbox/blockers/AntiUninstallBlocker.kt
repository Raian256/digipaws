package neth.iecal.curbox.blockers

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
    }

    private lateinit var service: BaseBlockingService
    @Volatile private var config: AntiUninstallConfig = AntiUninstallConfig()
    private var lastBlockTimestamp: Float = 0f

    fun doAntiUninstallCheck(event: AccessibilityEvent?) {
        if (!config.isEnabled) return
        event ?: return

        val pkg = event.packageName?.toString() ?: return
        if (!SETTINGS_PACKAGES.contains(pkg)) return

        val root = service.rootInActiveWindow ?: return
        try {
            if (nodeTreeMentionsApp(root)) {
                if (isDelayOver(lastBlockTimestamp, 500)) {
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
        val text = node.text?.toString()?.lowercase(Locale.getDefault())
        if (text != null && APP_LABEL_NEEDLES.any { text.contains(it) }) {
            return true
        }
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

    fun setupBlocker(service: BaseBlockingService) {
        this.service = service
        CoroutineScope(Dispatchers.IO).launch {
            service.dataStoreManager.settings.collectLatest { settings ->
                config = settings.antiUninstallConfig
            }
        }
    }
}
