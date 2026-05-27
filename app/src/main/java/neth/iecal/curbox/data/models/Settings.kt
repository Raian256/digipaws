package neth.iecal.curbox.data.models

data class Settings(
    val blockedAppGroups: List<AppGroup> = listOf(),
    val manualFocusGroups: List<ManualFocusGroup> = listOf(),
    val autoFocusGroups: List<AutoFocusGroup> = listOf(),
    /**
     * Stores info about active manual focus mode.
     * Format Pair<GroupId?, system ms when it ends>.
     * Set group id as null when no active focus mode is running
     */
    val activeManualFocusGroupId: Pair<String?, Long> = Pair(null, 0),

    val reelBlockerConfig: ReelBlocker = ReelBlocker(),
    val keywordBlockerConfig: KeywordBlocker = KeywordBlocker(),
    val isReelCounterOn: Boolean = true,
    val grayscaleGroups: List<GrayscaleGroup> = listOf(),
    val usageTrackerIgnoredApps: List<String> = listOf(),
    val mindfulMessageConfig: MindfulMessageConfig = MindfulMessageConfig(),
    val viewBlockerConfig: ViewBlockerConfig = ViewBlockerConfig(),
    val antiUninstallConfig: AntiUninstallConfig = AntiUninstallConfig(),
    val antiModificationsConfig: AntiModificationsConfig = AntiModificationsConfig(),
    /**
     * User-added packages that should never be blocked, in addition to the
     * built-in defaults (launcher, keyboard, system UI, our own app).
     */
    val customEssentialPackages: List<String> = listOf()
)
