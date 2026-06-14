package neth.iecal.curbox.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One record of the user deliberately turning off one of Curbox's accessibility
 * services. Logged from [neth.iecal.curbox.services.BaseBlockingService.onUnbind]
 * only when the secure enabled-services list no longer contains the service,
 * which distinguishes a deliberate disable from a reboot / app update / system
 * memory-kill (those leave the user's preference intact).
 */
@Entity(tableName = "accessibility_disable_logs")
data class AccessibilityDisableLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val timestamp: Long,
    // Simple class name of the disabled service, e.g. "AppBlockerService".
    val serviceName: String
)
