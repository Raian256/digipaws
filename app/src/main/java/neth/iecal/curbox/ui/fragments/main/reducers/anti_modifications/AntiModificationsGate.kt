package neth.iecal.curbox.ui.fragments.main.reducers.anti_modifications

import android.view.View
import com.google.android.material.snackbar.Snackbar
import neth.iecal.curbox.R
import neth.iecal.curbox.data.models.AntiModificationsConfig

/**
 * Thin decider used by the blocker-management screens. Each caller checks
 * the current [AntiModificationsConfig] and, when any group protects the
 * target item, refuses the edit with a snackbar. Actually unlocking the
 * item lives on the Anti-Modifications screens (per-group) — edit paths
 * only surface why the edit was refused.
 */
object AntiModificationsGate {

    fun refuseWithSnackbar(anchor: View) {
        Snackbar.make(anchor, R.string.anti_modifications_item_locked, Snackbar.LENGTH_LONG).show()
    }

    fun isAppPauseLocked(config: AntiModificationsConfig, id: String) =
        config.isAppPauseLocked(id)

    fun isAutoFocusLocked(config: AntiModificationsConfig, id: String) =
        config.isAutoFocusLocked(id)

    fun isKeywordLocked(config: AntiModificationsConfig, keyword: String) =
        config.isKeywordLocked(keyword)

    fun isViewBlockerLocked(config: AntiModificationsConfig, id: String) =
        config.isViewBlockerLocked(id)
}
