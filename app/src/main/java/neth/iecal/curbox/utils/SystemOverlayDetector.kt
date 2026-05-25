package neth.iecal.curbox.utils

import android.accessibilityservice.AccessibilityService
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Detects accessibility events that represent a transient system dialog or
 * overlay sitting on top of another app — e.g. Pixel's Extreme Battery Saver
 * "Use this app anyway?" prompt (com.google.android.flipendo), runtime
 * permission dialogs, the package installer's install/uninstall confirmation,
 * OEM AppOps prompts.
 *
 * The blockers call pressHome() whenever a non-whitelisted package comes to
 * the foreground. That breaks these flows: the user taps an app, Android
 * shows a confirmation dialog from a system package, the blocker sees an
 * unknown foreground and dismisses it before the user can answer. Treat
 * such overlays as transient and skip the block.
 *
 * Signals (any one matches):
 *   1. The event's window is a system-signed app AND another TYPE_APPLICATION
 *      window is still visible underneath. A real app switch tears down the
 *      previous app's window; an overlay leaves it on screen.
 *   2. The event's className is a Dialog subclass (AlertDialog, Dialog, the
 *      AlertController internals). Catches dialogs hosted by user apps too,
 *      but they're transient by definition — pressHome would dismiss them
 *      whether the user is in focus mode or not.
 *
 * Signal 1 requires `flagRetrieveInteractiveWindows` on the accessibility
 * service; without it [AccessibilityService.getWindows] is empty and signal
 * 1 never fires, leaving signal 2 as the only safety net.
 */
object SystemOverlayDetector {

    private val systemAppCache = HashMap<String, Boolean>()

    fun isSystemOverlay(service: AccessibilityService, event: AccessibilityEvent): Boolean {
        val packageName = event.packageName?.toString() ?: return false

        if (isDialogClassName(event.className?.toString())) return true

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return false
        if (!isSystemApp(service.packageManager, packageName)) return false

        val windows = try {
            service.windows
        } catch (_: Throwable) {
            null
        } ?: return false

        val eventWindowId = event.windowId
        return windows.any {
            it.id != eventWindowId && it.type == AccessibilityWindowInfo.TYPE_APPLICATION
        }
    }

    private fun isDialogClassName(className: String?): Boolean {
        if (className.isNullOrEmpty()) return false
        return className.contains("Dialog") || className.contains("AlertController")
    }

    private fun isSystemApp(pm: PackageManager, packageName: String): Boolean {
        systemAppCache[packageName]?.let { return it }
        val result = try {
            val info = pm.getApplicationInfo(packageName, 0)
            (info.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
        } catch (_: Exception) {
            false
        }
        systemAppCache[packageName] = result
        return result
    }
}
