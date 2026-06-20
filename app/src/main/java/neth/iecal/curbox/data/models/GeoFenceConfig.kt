package neth.iecal.curbox.data.models

/**
 * Whether a geofenced group is active when the device is INSIDE the radius
 * around a saved point, or when it is OUTSIDE that radius.
 *
 * With multiple points the mode applies to the set as a whole: INSIDE means
 * active while inside *any* point's radius, OUTSIDE means active while outside
 * *every* point's radius.
 */
enum class GeoFenceMode { INSIDE, OUTSIDE }

/**
 * A single circular region the group can be fenced against.
 */
data class GeoFencePoint(
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val radiusMeters: Float = 150f
)

/**
 * Optional location-based activation gate for an [AppGroup]. When [enabled],
 * the group only blocks its apps while the device's last-known location
 * satisfies [mode] relative to the circles in [points]. When disabled the group
 * behaves exactly as before (location is never consulted).
 *
 * Defaults are chosen so an unset/legacy group deserializes to a disabled
 * gate — i.e. no behavioural change for existing groups.
 *
 * [latitude], [longitude] and [radiusMeters] are the legacy single-point fields
 * kept only so configs serialised before multi-point support deserialize
 * correctly; new code reads/writes [points] and resolves the legacy form via
 * [resolvedPoints].
 */
data class GeoFenceConfig(
    val enabled: Boolean = false,
    val mode: GeoFenceMode = GeoFenceMode.INSIDE,
    val points: List<GeoFencePoint>? = null,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val radiusMeters: Float = 150f
) {
    /**
     * The points to evaluate, migrating the legacy single-point form on the fly.
     * Prefers [points] when present; otherwise promotes the legacy
     * latitude/longitude/radius into a one-element list if they were ever set.
     */
    val resolvedPoints: List<GeoFencePoint>
        get() = points?.takeIf { it.isNotEmpty() }
            ?: if (latitude != 0.0 || longitude != 0.0) {
                listOf(GeoFencePoint(latitude, longitude, radiusMeters))
            } else {
                emptyList()
            }
}
