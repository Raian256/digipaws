package neth.iecal.curbox.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ReelStatsEntity::class, ScrollPatternEntity::class, FocusStatsEntity::class, WebsiteStatsEntity::class, IntentLogEntity::class, BlockedAppLogEntity::class, AccessibilityDisableLogEntity::class],
    version = 7,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun reelStatsDao(): ReelStatsDao
    abstract fun scrollPatternDao(): ScrollPatternDao
    abstract fun focusStatsDao(): FocusStatsDao
    abstract fun websiteStatsDao(): WebsiteStatsDao
    abstract fun intentLogDao(): IntentLogDao
    abstract fun blockedAppLogDao(): BlockedAppLogDao
    abstract fun accessibilityDisableLogDao(): AccessibilityDisableLogDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        // Adds the accessibility-disable log table. An explicit migration (rather
        // than relying on destructive fallback) preserves the user's existing
        // analytics history across this upgrade.
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `accessibility_disable_logs` (" +
                        "`id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, " +
                        "`timestamp` INTEGER NOT NULL, " +
                        "`serviceName` TEXT NOT NULL)"
                )
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "curbox_db"
                ).enableMultiInstanceInvalidation()
                    .addMigrations(MIGRATION_6_7)
                    .fallbackToDestructiveMigration().build().also { INSTANCE = it }
            }
        }
    }
}
