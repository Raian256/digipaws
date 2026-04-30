package neth.iecal.curbox.utils.backup.sections

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonElement
import kotlinx.coroutines.flow.first
import neth.iecal.curbox.data.models.Settings
import neth.iecal.curbox.utils.DataStoreManager
import neth.iecal.curbox.utils.backup.BackupSection

/**
 * Backs up the entire user-configurable state, which lives in a single Gson-backed
 * DataStore (`filesDir/datastore/settings.json`) modeled by [Settings].
 *
 * Adding a new feature usually just means adding a field to [Settings]; Gson
 * handles missing fields on read by using the data-class default, and unknown
 * fields are ignored, so this section needs no edits in that case.
 *
 * Bump [version] only on a truly incompatible reshape of [Settings] (rename,
 * type change, semantic change). Then handle older [sourceVersion] payloads
 * inside [import] or throw `BackupIncompatibleException` for the user.
 */
class SettingsSection : BackupSection {
    override val id: String = "settings"
    override val version: Int = 1

    private val gson = Gson()

    override suspend fun export(context: Context): JsonElement {
        val current = DataStoreManager
            .getSettingsDataStore(context.applicationContext, gson)
            .data.first()
        return gson.toJsonTree(current)
    }

    override suspend fun import(context: Context, data: JsonElement, sourceVersion: Int) {
        val restored = gson.fromJson(data, Settings::class.java) ?: Settings()
        DataStoreManager
            .getSettingsDataStore(context.applicationContext, gson)
            .updateData { restored }
    }
}
