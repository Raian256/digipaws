package neth.iecal.curbox.utils.backup

import android.content.Context
import android.net.Uri
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import neth.iecal.curbox.BuildConfig
import neth.iecal.curbox.utils.backup.sections.SettingsSection
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Reads and writes Curbox backup files (JSON) over Storage Access Framework URIs.
 *
 * --- Adding a new persisted area ---
 * 1. Write a [BackupSection] in `utils/backup/sections/`.
 * 2. Add it to the [sections] list below.
 * That's the whole change.
 *
 * --- Versioning ---
 * Two layers, both produce clear failures on incompatibility rather than silent
 * data loss:
 *   * Envelope [BackupEnvelope.SCHEMA_VERSION] — bumped only on incompatible
 *     reshapes of the envelope itself. A backup with a newer schemaVersion than
 *     the running app supports is refused with [BackupIncompatibleException].
 *   * Per-section [BackupSection.version] — bumped on incompatible reshapes of
 *     a single section. A section with a newer version than the running app
 *     supports is reported as an incompatibility.
 *
 * Sections present in the backup but unknown to the current app are skipped
 * (forward-compat for retired features). Sections known to the current app but
 * absent from the backup are left at their current values (forward-compat for
 * new features added after the backup was taken).
 */
object BackupManager {

    private val sections: List<BackupSection> = listOf(
        SettingsSection()
        // Add new sections here. Example:
        // StatsSection(),
    )

    private val gson = GsonBuilder().setPrettyPrinting().create()

    suspend fun export(context: Context, target: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val payloads = sections.associate { section ->
                section.id to SectionPayload(section.version, section.export(context))
            }
            val envelope = BackupEnvelope(
                schemaVersion = BackupEnvelope.SCHEMA_VERSION,
                appVersionName = BuildConfig.VERSION_NAME,
                appVersionCode = BuildConfig.VERSION_CODE.toLong(),
                exportedAt = System.currentTimeMillis(),
                sections = payloads
            )
            val out = context.contentResolver.openOutputStream(target, "wt")
                ?: error("Could not open backup file for writing")
            out.use { it.write(gson.toJson(envelope).toByteArray(Charsets.UTF_8)) }
        }
    }

    suspend fun import(context: Context, source: Uri): Result<ImportSummary> = withContext(Dispatchers.IO) {
        runCatching {
            val text = context.contentResolver.openInputStream(source)?.use { input ->
                BufferedReader(InputStreamReader(input, Charsets.UTF_8)).readText()
            } ?: error("Could not open backup file for reading")

            val root = gson.fromJson(text, JsonObject::class.java)
                ?: throw BackupIncompatibleException("Backup file is empty or not valid JSON")

            val schemaVersion = root.get("schemaVersion")?.asInt
                ?: throw BackupIncompatibleException("Backup is missing schemaVersion (not a Curbox backup?)")
            if (schemaVersion > BackupEnvelope.SCHEMA_VERSION) {
                throw BackupIncompatibleException(
                    "This backup was created by a newer version of Curbox " +
                        "(schema $schemaVersion). Please update the app to restore it."
                )
            }

            val sectionsJson = root.getAsJsonObject("sections")
                ?: throw BackupIncompatibleException("Backup is missing the 'sections' block")

            val applied = mutableListOf<String>()
            val skippedUnknown = mutableListOf<String>()
            val skippedIncompatible = mutableListOf<String>()

            for (section in sections) {
                val payload = sectionsJson.getAsJsonObject(section.id) ?: continue
                val srcVersion = payload.get("version")?.asInt
                    ?: throw BackupIncompatibleException("Section '${section.id}' is missing a version")
                if (srcVersion > section.version) {
                    skippedIncompatible += section.id
                    continue
                }
                val data = payload.get("data")
                    ?: throw BackupIncompatibleException("Section '${section.id}' is missing data")
                section.import(context, data, srcVersion)
                applied += section.id
            }

            for (key in sectionsJson.keySet()) {
                if (sections.none { it.id == key }) skippedUnknown += key
            }

            ImportSummary(
                schemaVersion = schemaVersion,
                appVersionName = root.get("appVersionName")?.asString,
                exportedAt = root.get("exportedAt")?.asLong,
                applied = applied,
                skippedUnknown = skippedUnknown,
                skippedIncompatible = skippedIncompatible
            )
        }
    }

    data class ImportSummary(
        val schemaVersion: Int,
        val appVersionName: String?,
        val exportedAt: Long?,
        val applied: List<String>,
        /** Sections in the backup the current app doesn't know about. */
        val skippedUnknown: List<String>,
        /** Sections the current app knows about but at an older version than the backup. */
        val skippedIncompatible: List<String>
    )

    fun suggestedFileName(): String {
        val ts = System.currentTimeMillis()
        return "curbox-backup-$ts.json"
    }
}
