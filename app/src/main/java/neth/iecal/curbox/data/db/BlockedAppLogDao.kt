package neth.iecal.curbox.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface BlockedAppLogDao {
    @Insert
    suspend fun insert(log: BlockedAppLogEntity)

    @Query("SELECT * FROM blocked_app_logs ORDER BY id DESC")
    fun getAll(): Flow<List<BlockedAppLogEntity>>

    @Query("DELETE FROM blocked_app_logs WHERE id = :logId")
    suspend fun delete(logId: Int)

    @Query("DELETE FROM blocked_app_logs")
    suspend fun clear()

    @Query("DELETE FROM blocked_app_logs WHERE id NOT IN (SELECT id FROM blocked_app_logs ORDER BY id DESC LIMIT :keep)")
    suspend fun pruneToLast(keep: Int)

    @Transaction
    suspend fun insertAndPrune(log: BlockedAppLogEntity, keep: Int) {
        insert(log)
        pruneToLast(keep)
    }
}
