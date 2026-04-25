package neth.iecal.curbox.data.models

import neth.iecal.curbox.Constants

data class AntiUninstallConfig(
    val isEnabled: Boolean = false,
    val mode: Int = 0,
    val passwordHash: String = "",
    val endTimeInMillis: Long = 0L,
    val cooldownMinutes: Int = 0,
    val removalRequestedAt: Long = 0L
) {
    fun isPasswordMode() = mode == Constants.ANTI_UNINSTALL_PASSWORD_MODE
    fun isTimedMode() = mode == Constants.ANTI_UNINSTALL_TIMED_MODE
    fun isCooldownMode() = mode == Constants.ANTI_UNINSTALL_COOLDOWN_MODE
}
