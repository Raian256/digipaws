package neth.iecal.curbox.utils.backup.sections

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonElement
import kotlinx.coroutines.flow.first
import neth.iecal.curbox.data.models.AntiModificationsConfig
import neth.iecal.curbox.data.models.AntiUninstallConfig
import neth.iecal.curbox.data.models.Settings
import neth.iecal.curbox.utils.DataStoreManager
import neth.iecal.curbox.utils.backup.BackupIncompatibleException
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
 *
 * --- Lock & active-state fields ---
 * A handful of [Settings] fields are deliberately excluded from the round-trip:
 *  - Lock state (Anti-Uninstall, Anti-Modifications): exporting password
 *    hashes to a user-pickable file is a brute-force surface; importing
 *    lock state from a file would silently bypass active locks and would
 *    desync from the device-admin enrollment that lives outside Curbox.
 *  - Active runtime state (currently-running focus session): expiry
 *    timestamps don't translate across devices.
 * On export these fields are zeroed; on import the device's current values
 * are preserved. Add a new field here if you ship another lock-style or
 * runtime-state feature.
 */
class SettingsSection : BackupSection {
    override val id: String = "settings"
    override val version: Int = 1

    private val gson = Gson()

    override suspend fun export(context: Context): JsonElement {
        val current = DataStoreManager
            .getSettingsDataStore(context.applicationContext, gson)
            .data.first()
        return gson.toJsonTree(current.redactedForBackup())
    }

    override suspend fun import(context: Context, data: JsonElement, sourceVersion: Int) {
        val restored = gson.fromJson(data, Settings::class.java) ?: Settings()
        DataStoreManager
            .getSettingsDataStore(context.applicationContext, gson)
            .updateData { existing -> restored.preservingDeviceState(existing) }
    }

    override suspend fun precheckImport(context: Context): Result<Unit> {
        val current = DataStoreManager
            .getSettingsDataStore(context.applicationContext, gson)
            .data.first()
        if (current.antiUninstallConfig.isEnabled) {
            return Result.failure(
                BackupIncompatibleException(
                    "Anti-Uninstall is currently active. Remove it (via your password, " +
                        "timer, or cooldown) before restoring a backup."
                )
            )
        }
        if (current.antiModificationsConfig.groups.isNotEmpty()) {
            return Result.failure(
                BackupIncompatibleException(
                    "Anti-Modifications groups are currently active. Delete every group " +
                        "before restoring a backup."
                )
            )
        }
        return Result.success(Unit)
    }

    private fun Settings.redactedForBackup(): Settings = copy(
        antiUninstallConfig = AntiUninstallConfig(),
        antiModificationsConfig = AntiModificationsConfig(),
        activeManualFocusGroupId = Pair(null, 0)
    )

    private fun Settings.preservingDeviceState(existing: Settings): Settings = copy(
        antiUninstallConfig = existing.antiUninstallConfig,
        antiModificationsConfig = existing.antiModificationsConfig,
        activeManualFocusGroupId = existing.activeManualFocusGroupId
    )
}
