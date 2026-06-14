package neth.iecal.curbox.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface AccessibilityDisableLogDao {
    @Insert
    suspend fun insert(log: AccessibilityDisableLogEntity)

    @Query("SELECT * FROM accessibility_disable_logs ORDER BY timestamp DESC")
    fun getAll(): Flow<List<AccessibilityDisableLogEntity>>

    /** Count of disables since [since] (epoch millis), used for the weekly tally. */
    @Query("SELECT COUNT(*) FROM accessibility_disable_logs WHERE timestamp >= :since")
    fun countSince(since: Long): Flow<Int>

    @Query("DELETE FROM accessibility_disable_logs WHERE id = :logId")
    suspend fun delete(logId: Int)

    @Query("DELETE FROM accessibility_disable_logs")
    suspend fun clear()

    /** Drops records older than [cutoff] (epoch millis) to bound retention. */
    @Query("DELETE FROM accessibility_disable_logs WHERE timestamp < :cutoff")
    suspend fun pruneOlderThan(cutoff: Long)

    @Transaction
    suspend fun insertAndPrune(log: AccessibilityDisableLogEntity, cutoff: Long) {
        insert(log)
        pruneOlderThan(cutoff)
    }
}
