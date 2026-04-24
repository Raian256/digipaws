package neth.iecal.curbox.ui.fragments.main.reducers.anti_modifications

import android.content.Context
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
import neth.iecal.curbox.data.models.AntiModificationsConfig
import neth.iecal.curbox.databinding.DialogRemoveAntiUninstallBinding
import neth.iecal.curbox.utils.DataStoreManager
import neth.iecal.curbox.utils.HashUtils

/**
 * Shared gate that runs an unlock challenge before executing an "unlock"
 * action against [AntiModificationsConfig]. The action is anything that loosens
 * a lock: flipping "lock all" off, removing a specific item lock, disabling
 * the whole system, etc. Locking things tighter is always free and does not
 * go through this gate.
 *
 * Behaviour per mode:
 * - Password: prompts for the password inline; invokes [onUnlocked] on match.
 * - Timed: allows only once [AntiModificationsConfig.endTimeInMillis] has passed.
 * - Cooldown: first tap starts the cooldown timer; a second tap after it elapses
 *   runs [onUnlocked] and resets the timer. Only one cooldown runs at a time.
 */
object AntiModificationsUnlock {

    fun attempt(
        fragment: Fragment,
        config: AntiModificationsConfig,
        onUnlocked: suspend (AntiModificationsConfig) -> AntiModificationsConfig
    ) {
        val ctx = fragment.requireContext()
        when {
            !config.isEnabled -> {
                runAndPersist(fragment, onUnlocked)
            }
            config.isPasswordMode() -> promptPassword(fragment, config, onUnlocked)
            config.isTimedMode() -> promptTimed(ctx, config, fragment, onUnlocked)
            config.isCooldownMode() -> promptCooldown(fragment, config, onUnlocked)
            else -> runAndPersist(fragment, onUnlocked)
        }
    }

    private fun promptPassword(
        fragment: Fragment,
        config: AntiModificationsConfig,
        onUnlocked: suspend (AntiModificationsConfig) -> AntiModificationsConfig
    ) {
        val ctx = fragment.requireContext()
        val dialogBinding = DialogRemoveAntiUninstallBinding.inflate(LayoutInflater.from(ctx))
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.anti_modifications_title)
            .setMessage(R.string.anti_modifications_password_prompt)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.remove) { _, _ ->
                val entered = dialogBinding.password.text?.toString().orEmpty()
                if (HashUtils.sha256(entered) == config.passwordHash) {
                    runAndPersist(fragment, onUnlocked)
                } else {
                    MaterialAlertDialogBuilder(ctx)
                        .setTitle(R.string.anti_modifications_title)
                        .setMessage(R.string.anti_modifications_incorrect_password)
                        .setPositiveButton(android.R.string.ok) { _, _ ->
                            promptPassword(fragment, config, onUnlocked)
                        }
                        .show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun promptTimed(
        ctx: Context,
        config: AntiModificationsConfig,
        fragment: Fragment,
        onUnlocked: suspend (AntiModificationsConfig) -> AntiModificationsConfig
    ) {
        val now = System.currentTimeMillis()
        if (now >= config.endTimeInMillis) {
            runAndPersist(fragment, onUnlocked)
            return
        }
        val formatted = DateFormat.getLongDateFormat(ctx).format(config.endTimeInMillis)
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.anti_modifications_title)
            .setMessage(ctx.getString(R.string.anti_modifications_cannot_unlock_timed, formatted))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun promptCooldown(
        fragment: Fragment,
        config: AntiModificationsConfig,
        onUnlocked: suspend (AntiModificationsConfig) -> AntiModificationsConfig
    ) {
        val ctx = fragment.requireContext()
        val now = System.currentTimeMillis()
        val unlockAt = config.removalRequestedAt + config.cooldownMinutes * 60_000L
        when {
            config.removalRequestedAt == 0L -> {
                MaterialAlertDialogBuilder(ctx)
                    .setTitle(R.string.anti_uninstall_confirm_title)
                    .setMessage(ctx.getString(
                        R.string.anti_modifications_cooldown_start_prompt,
                        config.cooldownMinutes
                    ))
                    .setPositiveButton(R.string.anti_uninstall_i_understand) { _, _ ->
                        persist(fragment) { it.copy(removalRequestedAt = System.currentTimeMillis()) }
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
            now >= unlockAt -> {
                // Cooldown elapsed: perform the unlock and reset the timer.
                val wrapped: suspend (AntiModificationsConfig) -> AntiModificationsConfig = { cfg ->
                    onUnlocked(cfg).copy(removalRequestedAt = 0L)
                }
                runAndPersist(fragment, wrapped)
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

    private fun runAndPersist(
        fragment: Fragment,
        onUnlocked: suspend (AntiModificationsConfig) -> AntiModificationsConfig
    ) {
        persist(fragment, onUnlocked)
    }

    private fun persist(
        fragment: Fragment,
        transform: suspend (AntiModificationsConfig) -> AntiModificationsConfig
    ) {
        val manager = DataStoreManager(fragment.requireContext().applicationContext)
        fragment.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val current = manager.settings.first().antiModificationsConfig
                val updated = transform(current)
                manager.updateAntiModificationsConfig(updated)
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
