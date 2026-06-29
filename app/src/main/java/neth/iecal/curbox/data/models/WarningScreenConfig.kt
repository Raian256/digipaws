package neth.iecal.curbox.data.models


data class AppBlockerWarningScreenConfig(
    val message: String = "",
    val timeInterval: Int = 120000, // default cooldown period
    val isDynamicIntervalSettingAllowed: Boolean = false,
    val isProceedDisabled: Boolean = false,
    val isWarningDialogHidden: Boolean = false, // perform back/home action directly without showing warning screen
    val proceedDelayInSecs: Int = 15,
    val vibrateAndIncBrightness: Boolean = false,
    val proceedLimitEnabled: Boolean = false,
    val allowedProceeds: Int = 3,
    val proceedsTimeWindowMn: Int = 60,
    val isQrUnlockRequirementEnabled: Boolean = false,
    val qrKeys: Map<String,Long> = mapOf(), // qr code content -> Duration of unlock (-1 if dynamic timing)
    val isTypingRequirementEnabled: Boolean = false,
    val typingSentence: String = "",
    val isIntentRequirementEnabled: Boolean = false,
    /**
     * Delayed unlock: instead of waiting on the warning screen, the user picks
     * how long to unlock for and then has to wait off-screen before access opens.
     * The wait is proportional to the chosen unlock length:
     *   waitMillis = max(chosenUnlockMillis * delayedUnlockFactor, delayedUnlockMinWaitMn min)
     * The user can leave the screen; a "stop cooldown" notification can cancel it.
     */
    val isDelayedUnlockEnabled: Boolean = false,
    val delayedUnlockFactor: Float = 0.1f, // k > 0
    val delayedUnlockMinWaitMn: Int = 10,  // N, the floor in minutes
)
