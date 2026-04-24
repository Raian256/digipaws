package neth.iecal.curbox.data.models

import neth.iecal.curbox.Constants

/**
 * Independent lock system that prevents edits to specific blockers while active.
 *
 * Shares the three unlock modes (password / timed / cooldown) with
 * [AntiUninstallConfig] but keeps all of its own state — enabling or removing
 * Anti-modifications has no effect on Anti-uninstall and vice versa.
 *
 * Fine-grained: each of the four domains (App Pause schedules, Auto Focus
 * schedules, keyword blocker keywords, view-blocker rules) has a "lock all"
 * flag and a set of specific ids/values to lock individually. Both are OR'd
 * when deciding whether a given item is currently locked.
 */
data class AntiModificationsConfig(
    val isEnabled: Boolean = false,
    val mode: Int = 0,
    val passwordHash: String = "",
    val endTimeInMillis: Long = 0L,
    val cooldownMinutes: Int = 0,
    val removalRequestedAt: Long = 0L,

    val lockAllAppPauseSchedules: Boolean = false,
    val lockedAppPauseScheduleIds: Set<String> = emptySet(),

    val lockAllAutoFocusSchedules: Boolean = false,
    val lockedAutoFocusScheduleIds: Set<String> = emptySet(),

    val lockAllKeywords: Boolean = false,
    val lockedKeywords: Set<String> = emptySet(),

    val lockAllViewBlockers: Boolean = false,
    val lockedViewBlockerIds: Set<String> = emptySet()
) {
    fun isPasswordMode() = mode == Constants.ANTI_UNINSTALL_PASSWORD_MODE
    fun isTimedMode() = mode == Constants.ANTI_UNINSTALL_TIMED_MODE
    fun isCooldownMode() = mode == Constants.ANTI_UNINSTALL_COOLDOWN_MODE

    fun isAppPauseLocked(id: String) =
        isEnabled && (lockAllAppPauseSchedules || id in lockedAppPauseScheduleIds)

    fun isAutoFocusLocked(id: String) =
        isEnabled && (lockAllAutoFocusSchedules || id in lockedAutoFocusScheduleIds)

    fun isKeywordLocked(keyword: String) =
        isEnabled && (lockAllKeywords || keyword in lockedKeywords)

    fun isViewBlockerLocked(id: String) =
        isEnabled && (lockAllViewBlockers || id in lockedViewBlockerIds)
}
