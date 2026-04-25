package neth.iecal.curbox.utils

import android.content.Context

/**
 * Packages that must never be blocked, suspended, or force-stopped.
 *
 * Blocking these would either brick basic device interaction (launcher, keyboard,
 * system UI) or recursively re-trigger the blocker (the app itself, since blocking
 * presses HOME — which goes to the launcher — and force-stops the offending app).
 *
 * Single source of truth — every blocker, suspender, and picker reads from here.
 * Add new categories (dialer, accessibility services, etc.) in one place.
 */
fun getEssentialPackages(context: Context): Set<String> {
    val essential = mutableSetOf(
        "com.android.systemui",
        context.packageName,
    )
    getDefaultLauncherPackageName(context.packageManager)?.let(essential::add)
    getCurrentKeyboardPackageName(context)?.let(essential::add)
    return essential
}
