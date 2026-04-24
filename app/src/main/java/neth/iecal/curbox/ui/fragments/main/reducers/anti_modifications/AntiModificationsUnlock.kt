package neth.iecal.curbox.ui.fragments.main.reducers.anti_modifications

import android.text.format.DateFormat
import android.view.LayoutInflater
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import neth.iecal.curbox.R
import neth.iecal.curbox.data.models.AntiModificationsGroup
import neth.iecal.curbox.databinding.DialogRemoveAntiUninstallBinding
import neth.iecal.curbox.utils.DataStoreManager
import neth.iecal.curbox.utils.HashUtils

/**
 * Runs a single group's unlock challenge and, on success, applies [transform]
 * — a caller-supplied mutation that usually removes an item from the group
 * or deletes the group outright. Tightening actions must not go through
 * this gate.
 *
 * Password: prompt inline, run on match.
 * Timed: allowed only once the group's [AntiModificationsGroup.endTimeInMillis] has passed.
 * Cooldown: first tap starts the cooldown, a second tap after it elapses
 * runs the transform and resets the timer. Each group tracks its own
 * cooldown state.
 */
object AntiModificationsUnlock {

    fun attempt(
        fragment: Fragment,
        group: AntiModificationsGroup,
        transform: (AntiModificationsGroup) -> AntiModificationsGroup
    ) {
        when {
            group.isPasswordMode() -> promptPassword(fragment, group, transform)
            group.isTimedMode() -> promptTimed(fragment, group, transform)
            group.isCooldownMode() -> promptCooldown(fragment, group, transform)
            else -> persist(fragment, group.id, transform)
        }
    }

    /**
     * Runs the group's unlock challenge and, on success, removes the group
     * from the config entirely. Used for "Delete group".
     */
    fun attemptDelete(fragment: Fragment, group: AntiModificationsGroup) {
        when {
            group.isPasswordMode() -> promptPasswordDelete(fragment, group)
            group.isTimedMode() -> {
                val ctx = fragment.requireContext()
                if (System.currentTimeMillis() >= group.endTimeInMillis) {
                    deleteNow(fragment, group.id)
                } else {
                    val formatted = DateFormat.getLongDateFormat(ctx).format(group.endTimeInMillis)
                    MaterialAlertDialogBuilder(ctx)
                        .setTitle(ctx.getString(R.string.anti_modifications_unlock_group_title, group.name))
                        .setMessage(ctx.getString(R.string.anti_modifications_cannot_unlock_timed, formatted))
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            }
            group.isCooldownMode() -> {
                val now = System.currentTimeMillis()
                val unlockAt = group.removalRequestedAt + group.cooldownMinutes * 60_000L
                val ctx = fragment.requireContext()
                when {
                    group.removalRequestedAt == 0L -> {
                        MaterialAlertDialogBuilder(ctx)
                            .setTitle(ctx.getString(R.string.anti_modifications_unlock_group_title, group.name))
                            .setMessage(ctx.getString(
                                R.string.anti_modifications_cooldown_start_prompt,
                                group.cooldownMinutes
                            ))
                            .setPositiveButton(R.string.anti_uninstall_i_understand) { _, _ ->
                                persist(fragment, group.id) {
                                    it.copy(removalRequestedAt = System.currentTimeMillis())
                                }
                            }
                            .setNegativeButton(R.string.cancel, null)
                            .show()
                    }
                    now >= unlockAt -> deleteNow(fragment, group.id)
                    else -> {
                        MaterialAlertDialogBuilder(ctx)
                            .setTitle(R.string.anti_uninstall_remove_failed)
                            .setMessage(ctx.getString(
                                R.string.anti_modifications_cannot_unlock_waiting,
                                formatRemaining(unlockAt - now)
                            ))
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                    }
                }
            }
            else -> deleteNow(fragment, group.id)
        }
    }

    private fun promptPasswordDelete(fragment: Fragment, group: AntiModificationsGroup) {
        val ctx = fragment.requireContext()
        val dialogBinding = DialogRemoveAntiUninstallBinding.inflate(LayoutInflater.from(ctx))
        MaterialAlertDialogBuilder(ctx)
            .setTitle(ctx.getString(R.string.anti_modifications_unlock_group_title, group.name))
            .setMessage(R.string.anti_modifications_password_prompt)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.remove) { _, _ ->
                val entered = dialogBinding.password.text?.toString().orEmpty()
                if (HashUtils.sha256(entered) == group.passwordHash) {
                    deleteNow(fragment, group.id)
                } else {
                    MaterialAlertDialogBuilder(ctx)
                        .setTitle(R.string.anti_modifications_title)
                        .setMessage(R.string.anti_modifications_incorrect_password)
                        .setPositiveButton(android.R.string.ok) { _, _ ->
                            promptPasswordDelete(fragment, group)
                        }
                        .show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * Apply a tightening-only change (adding items, renaming) without running
     * any challenge. Callers must not use this to loosen protection.
     */
    fun tighten(
        fragment: Fragment,
        groupId: String,
        transform: (AntiModificationsGroup) -> AntiModificationsGroup
    ) = persist(fragment, groupId, transform)

    /**
     * Adds a brand-new group to the config. Always free — it only tightens.
     */
    fun createGroup(fragment: Fragment, group: AntiModificationsGroup) {
        val manager = DataStoreManager(fragment.requireContext().applicationContext)
        fragment.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val cfg = manager.settings.first().antiModificationsConfig
                manager.updateAntiModificationsConfig(cfg.copy(groups = cfg.groups + group))
            }
        }
    }

    private fun deleteNow(fragment: Fragment, groupId: String) {
        val manager = DataStoreManager(fragment.requireContext().applicationContext)
        fragment.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val cfg = manager.settings.first().antiModificationsConfig
                manager.updateAntiModificationsConfig(
                    cfg.copy(groups = cfg.groups.filterNot { it.id == groupId })
                )
            }
        }
    }

    private fun promptPassword(
        fragment: Fragment,
        group: AntiModificationsGroup,
        transform: (AntiModificationsGroup) -> AntiModificationsGroup
    ) {
        val ctx = fragment.requireContext()
        val dialogBinding = DialogRemoveAntiUninstallBinding.inflate(LayoutInflater.from(ctx))
        MaterialAlertDialogBuilder(ctx)
            .setTitle(ctx.getString(R.string.anti_modifications_unlock_group_title, group.name))
            .setMessage(R.string.anti_modifications_password_prompt)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.remove) { _, _ ->
                val entered = dialogBinding.password.text?.toString().orEmpty()
                if (HashUtils.sha256(entered) == group.passwordHash) {
                    persist(fragment, group.id, transform)
                } else {
                    MaterialAlertDialogBuilder(ctx)
                        .setTitle(R.string.anti_modifications_title)
                        .setMessage(R.string.anti_modifications_incorrect_password)
                        .setPositiveButton(android.R.string.ok) { _, _ ->
                            promptPassword(fragment, group, transform)
                        }
                        .show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun promptTimed(
        fragment: Fragment,
        group: AntiModificationsGroup,
        transform: (AntiModificationsGroup) -> AntiModificationsGroup
    ) {
        val ctx = fragment.requireContext()
        val now = System.currentTimeMillis()
        if (now >= group.endTimeInMillis) {
            persist(fragment, group.id, transform)
            return
        }
        val formatted = DateFormat.getLongDateFormat(ctx).format(group.endTimeInMillis)
        MaterialAlertDialogBuilder(ctx)
            .setTitle(ctx.getString(R.string.anti_modifications_unlock_group_title, group.name))
            .setMessage(ctx.getString(R.string.anti_modifications_cannot_unlock_timed, formatted))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun promptCooldown(
        fragment: Fragment,
        group: AntiModificationsGroup,
        transform: (AntiModificationsGroup) -> AntiModificationsGroup
    ) {
        val ctx = fragment.requireContext()
        val now = System.currentTimeMillis()
        val unlockAt = group.removalRequestedAt + group.cooldownMinutes * 60_000L
        when {
            group.removalRequestedAt == 0L -> {
                MaterialAlertDialogBuilder(ctx)
                    .setTitle(ctx.getString(R.string.anti_modifications_unlock_group_title, group.name))
                    .setMessage(ctx.getString(
                        R.string.anti_modifications_cooldown_start_prompt,
                        group.cooldownMinutes
                    ))
                    .setPositiveButton(R.string.anti_uninstall_i_understand) { _, _ ->
                        persist(fragment, group.id) {
                            it.copy(removalRequestedAt = System.currentTimeMillis())
                        }
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
            now >= unlockAt -> {
                persist(fragment, group.id) { cur ->
                    transform(cur).copy(removalRequestedAt = 0L)
                }
            }
            else -> {
                MaterialAlertDialogBuilder(ctx)
                    .setTitle(R.string.anti_uninstall_remove_failed)
                    .setMessage(ctx.getString(
                        R.string.anti_modifications_cannot_unlock_waiting,
                        formatRemaining(unlockAt - now)
                    ))
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }
    }

    /**
     * Applies [transform] to the named group. If the transform returns null,
     * or the updated group has no items left, the group is deleted from the
     * config — the caller uses this pattern to express "delete group".
     */
    fun persist(
        fragment: Fragment,
        groupId: String,
        transform: (AntiModificationsGroup) -> AntiModificationsGroup
    ) {
        val manager = DataStoreManager(fragment.requireContext().applicationContext)
        fragment.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val cfg = manager.settings.first().antiModificationsConfig
                val newGroups = cfg.groups.mapNotNull { g ->
                    if (g.id == groupId) transform(g) else g
                }
                manager.updateAntiModificationsConfig(cfg.copy(groups = newGroups))
            }
        }
    }

    fun formatRemaining(millis: Long): String {
        val total = (millis / 1000L).coerceAtLeast(0L)
        val hours = total / 3600L
        val minutes = (total % 3600L) / 60L
        val seconds = total % 60L
        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%d:%02d", minutes, seconds)
        }
    }
}
