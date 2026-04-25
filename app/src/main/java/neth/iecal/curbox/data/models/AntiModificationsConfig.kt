package neth.iecal.curbox.data.models

import neth.iecal.curbox.Constants
import java.util.UUID

/**
 * Independent lock system that prevents edits to specific blockers while
 * active. Shares the three unlock modes (password / timed / cooldown) with
 * [AntiUninstallConfig] but keeps its own state — toggling one system does
 * not affect the other.
 *
 * Modelled as a list of independent groups. Each [AntiModificationsGroup]
 * carries its own unlock mode + state and its own set of protected items,
 * mixed freely across all four domains. Groups can be created, edited, and
 * deleted independently — one can use password while another uses cooldown.
 *
 * An item is considered locked iff any group protects it; unlocking it
 * means either removing it from every group that contains it (each via its
 * own unlock challenge), or deleting those groups outright.
 */
data class AntiModificationsConfig(
    val groups: List<AntiModificationsGroup> = emptyList()
) {
    fun isAppPauseLocked(id: String) = groups.any { id in it.lockedAppPauseScheduleIds }
    fun isAutoFocusLocked(id: String) = groups.any { id in it.lockedAutoFocusScheduleIds }
    fun isKeywordLocked(keyword: String) = groups.any { keyword in it.lockedKeywords }
    fun isViewBlockerLocked(id: String) = groups.any { id in it.lockedViewBlockerIds }
}

/**
 * A single Anti-Modifications group. Created pre-armed: its unlock state is
 * configured at creation time and cannot be weakened without passing the
 * challenge below.
 *
 * Tightening (adding items, adding new groups) is always free. Loosening
 * (removing items from a group, deleting a group) requires [mode] to be
 * satisfied: entering the password, waiting past [endTimeInMillis], or
 * completing the [cooldownMinutes] wait triggered by [removalRequestedAt].
 *
 * Renaming the group is considered free — it does not weaken protection.
 */
data class AntiModificationsGroup(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",

    val mode: Int = 0,
    val passwordHash: String = "",
    val endTimeInMillis: Long = 0L,
    val cooldownMinutes: Int = 0,
    val removalRequestedAt: Long = 0L,

    val lockedAppPauseScheduleIds: Set<String> = emptySet(),
    val lockedAutoFocusScheduleIds: Set<String> = emptySet(),
    val lockedKeywords: Set<String> = emptySet(),
    val lockedViewBlockerIds: Set<String> = emptySet()
) {
    fun isPasswordMode() = mode == Constants.ANTI_UNINSTALL_PASSWORD_MODE
    fun isTimedMode() = mode == Constants.ANTI_UNINSTALL_TIMED_MODE
    fun isCooldownMode() = mode == Constants.ANTI_UNINSTALL_COOLDOWN_MODE

    fun totalItemCount(): Int =
        lockedAppPauseScheduleIds.size +
            lockedAutoFocusScheduleIds.size +
            lockedKeywords.size +
            lockedViewBlockerIds.size
}
