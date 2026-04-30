package neth.iecal.curbox.utils.backup

import android.content.Context
import com.google.gson.JsonElement

/**
 * One independently-versioned slice of the backup (settings, stats, etc).
 *
 * To add a new persisted area to the backup:
 *   1. Implement this interface in a new class under `utils/backup/sections/`.
 *   2. Add it to the registry list in [BackupManager].
 *
 * Versioning rules:
 *  - Bump [version] only when the JSON shape produced by [export] changes in a way
 *    that an older app cannot safely consume. Adding optional fields backed by
 *    Kotlin defaults (e.g. new fields on `Settings`) is NOT a breaking change —
 *    Gson fills in defaults and old fields are ignored, so the version stays the same.
 *  - When the current app sees a section payload with a [version] greater than
 *    [version], the manager refuses that section with a clear error rather than
 *    silently dropping data.
 */
interface BackupSection {
    val id: String
    val version: Int

    suspend fun export(context: Context): JsonElement

    /**
     * @param sourceVersion the version the payload was written with.
     * Implementations may migrate older payloads or throw [BackupIncompatibleException]
     * if they cannot be converted.
     */
    suspend fun import(context: Context, data: JsonElement, sourceVersion: Int)
}

class BackupIncompatibleException(message: String) : Exception(message)
