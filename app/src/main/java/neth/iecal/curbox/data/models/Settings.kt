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
    val usageTrackerIgnoredApps: List<String> = listOf(),
    val mindfulMessageConfig: MindfulMessageConfig = MindfulMessageConfig(),
    val viewBlockerConfig: ViewBlockerConfig = ViewBlockerConfig(),
    val antiUninstallConfig: AntiUninstallConfig = AntiUninstallConfig(),
    val antiModificationsConfig: AntiModificationsConfig = AntiModificationsConfig(),
    /**
     * User-added packages that should never be blocked, in addition to the
     * built-in defaults (launcher, keyboard, system UI, our own app).
     */
    val customEssentialPackages: List<String> = listOf(),
    /**
     * Global fallback for geofenced app-block groups when the device location
     * is unknown (no fix yet, location off, or permission missing).
     *  - false (default): fail open — geofenced groups don't block until the
     *    inside/outside condition can be confirmed.
     *  - true: fail closed — geofenced groups stay active while location is
     *    unavailable, so a block can't be dodged by denying location.
     * Lockable via an Anti-Modifications group.
     */
    val blockGeofencedWhenLocationUnavailable: Boolean = false,
    /**
     * When true, geofencing may no longer be turned on for any group that
     * doesn't already have it enabled. Groups whose geofence is already enabled
     * keep working and stay editable — this only blocks newly opting in.
     *
     * Geofencing requires periodic location fixes, each of which briefly lights
     * the system location indicator; tapping it lets the user force-stop the app
     * and slip past every geofenced block. This switch lets a user who relies on
     * the protection close that door against their future self by refusing to
     * expand the geofenced surface any further.
     */
    val restrictNewGeofencing: Boolean = false
)
