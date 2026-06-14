package neth.iecal.curbox.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [ReelStatsEntity::class, ScrollPatternEntity::class, FocusStatsEntity::class, WebsiteStatsEntity::class, IntentLogEntity::class, BlockedAppLogEntity::class],
    version = 6,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun reelStatsDao(): ReelStatsDao
    abstract fun scrollPatternDao(): ScrollPatternDao
    abstract fun focusStatsDao(): FocusStatsDao
    abstract fun websiteStatsDao(): WebsiteStatsDao
    abstract fun intentLogDao(): IntentLogDao
    abstract fun blockedAppLogDao(): BlockedAppLogDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "curbox_db"
                ).enableMultiInstanceInvalidation().fallbackToDestructiveMigration().build().also { INSTANCE = it }
            }
        }
    }
}
