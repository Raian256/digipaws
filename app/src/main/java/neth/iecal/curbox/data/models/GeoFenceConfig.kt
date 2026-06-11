package neth.iecal.curbox.data.models

/**
 * Whether a geofenced group is active when the device is INSIDE the radius
 * around the saved point, or when it is OUTSIDE that radius.
 */
enum class GeoFenceMode { INSIDE, OUTSIDE }

/**
 * Optional location-based activation gate for an [AppGroup]. When [enabled],
 * the group only blocks its apps while the device's last-known location
 * satisfies [mode] relative to the circle ([latitude], [longitude],
 * [radiusMeters]). When disabled the group behaves exactly as before
 * (location is never consulted).
 *
 * Defaults are chosen so an unset/legacy group deserializes to a disabled
 * gate — i.e. no behavioural change for existing groups.
 */
data class GeoFenceConfig(
    val enabled: Boolean = false,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val radiusMeters: Float = 150f,
    val mode: GeoFenceMode = GeoFenceMode.INSIDE
)
