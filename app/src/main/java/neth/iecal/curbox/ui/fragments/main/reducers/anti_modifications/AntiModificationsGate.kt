package neth.iecal.curbox.ui.fragments.main.reducers.anti_modifications

import android.content.Context
import android.view.View
import com.google.android.material.snackbar.Snackbar
import neth.iecal.curbox.R
import neth.iecal.curbox.data.models.AntiModificationsConfig

/**
 * Thin decider used by the blocker-management screens. Each caller checks the
 * currently loaded [AntiModificationsConfig] (usually from its own Settings
 * flow) and, when a lock applies, bails out with a snackbar or toast.
 *
 * Actually unlocking an item happens on the Anti-Modifications screen — edit
 * screens never run the challenge themselves, they just surface why the edit
 * was refused.
 */
object AntiModificationsGate {

    fun refuseWithSnackbar(anchor: View) {
        Snackbar.make(anchor, R.string.anti_modifications_item_locked, Snackbar.LENGTH_LONG).show()
    }

    fun toast(ctx: Context) {
        android.widget.Toast.makeText(
            ctx,
            ctx.getString(R.string.anti_modifications_item_locked),
            android.widget.Toast.LENGTH_LONG
        ).show()
    }

    fun isAppPauseLocked(config: AntiModificationsConfig, id: String) =
        config.isAppPauseLocked(id)

    fun isAutoFocusLocked(config: AntiModificationsConfig, id: String) =
        config.isAutoFocusLocked(id)

    fun isKeywordLocked(config: AntiModificationsConfig, keyword: String) =
        config.isKeywordLocked(keyword)

    fun isViewBlockerLocked(config: AntiModificationsConfig, id: String) =
        config.isViewBlockerLocked(id)

    fun isAnyAppPauseLocked(config: AntiModificationsConfig, ids: Iterable<String>): Boolean {
        if (!config.isEnabled) return false
        if (config.lockAllAppPauseSchedules) return true
        return ids.any { it in config.lockedAppPauseScheduleIds }
    }

    fun isAnyAutoFocusLocked(config: AntiModificationsConfig, ids: Iterable<String>): Boolean {
        if (!config.isEnabled) return false
        if (config.lockAllAutoFocusSchedules) return true
        return ids.any { it in config.lockedAutoFocusScheduleIds }
    }

    fun isAnyKeywordLocked(config: AntiModificationsConfig, keywords: Iterable<String>): Boolean {
        if (!config.isEnabled) return false
        if (config.lockAllKeywords) return true
        return keywords.any { it in config.lockedKeywords }
    }

    fun isAnyViewBlockerLocked(config: AntiModificationsConfig, ids: Iterable<String>): Boolean {
        if (!config.isEnabled) return false
        if (config.lockAllViewBlockers) return true
        return ids.any { it in config.lockedViewBlockerIds }
    }
}
