package neth.iecal.curbox.utils.backup

import com.google.gson.JsonElement

/**
 * Top-level on-disk shape of a backup file.
 *
 * The envelope is intentionally small and stable. Most format evolution lives
 * inside individual sections — bump a section's version, not the envelope's.
 *
 * Bump [SCHEMA_VERSION] only on a truly breaking change to the envelope layout
 * itself (e.g. moving from a sections-map to a sections-list). When we do bump,
 * older apps will refuse to read newer backups with a clear "update required"
 * message — that's the explicit "this backup won't survive" signal.
 */
internal data class BackupEnvelope(
    val schemaVersion: Int,
    val appVersionName: String,
    val appVersionCode: Long,
    val exportedAt: Long,
    /** id -> per-section payload */
    val sections: Map<String, SectionPayload>
) {
    companion object {
        const val SCHEMA_VERSION = 1
    }
}

internal data class SectionPayload(
    val version: Int,
    val data: JsonElement
)
