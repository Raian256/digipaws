package neth.iecal.curbox.ui.fragments.main.reducers.anti_uninstall

import android.os.Bundle
import androidx.fragment.app.Fragment

/**
 * Identifies which of the two independent lock systems a setup/choose-mode
 * flow is targeting. The Anti-uninstall and Anti-modifications systems share
 * the same password / timed / cooldown UI but write to different configs.
 */
enum class LockSetupTarget(val key: String) {
    ANTI_UNINSTALL("anti_uninstall"),
    ANTI_MODIFICATIONS("anti_modifications");

    companion object {
        const val ARG_TARGET = "lock_setup_target"

        fun fromArgs(fragment: Fragment): LockSetupTarget {
            val key = fragment.arguments?.getString(ARG_TARGET)
            return entries.firstOrNull { it.key == key } ?: ANTI_UNINSTALL
        }

        fun bundle(target: LockSetupTarget): Bundle = Bundle().apply {
            putString(ARG_TARGET, target.key)
        }
    }
}
