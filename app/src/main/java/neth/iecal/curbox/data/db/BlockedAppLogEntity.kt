package neth.iecal.curbox.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "blocked_app_logs")
data class BlockedAppLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val timestamp: Long,
    val packageName: String,
    val groupId: String,
    val groupName: String
)
