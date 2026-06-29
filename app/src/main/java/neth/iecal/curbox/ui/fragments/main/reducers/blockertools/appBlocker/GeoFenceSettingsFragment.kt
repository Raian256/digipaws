package neth.iecal.curbox.ui.fragments.main.reducers.blockertools.appBlocker

import android.Manifest
import android.app.Dialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import neth.iecal.curbox.R
import neth.iecal.curbox.data.models.GeoFenceConfig
import neth.iecal.curbox.data.models.GeoFenceMode
import neth.iecal.curbox.data.models.GeoFencePoint
import neth.iecal.curbox.databinding.FragmentAppBlockerGeofenceSettingsBinding
import neth.iecal.curbox.databinding.ItemGeofencePointBinding
import neth.iecal.curbox.utils.DataStoreManager
import neth.iecal.curbox.utils.LocationProvider

/**
 * Bottom sheet for configuring the optional geofence activation condition on an
 * app-block group. Any number of centre points can be added; each is captured
 * from the device's current location or typed in manually — there is no map,
 * since the app holds no network permission and therefore cannot fetch map
 * tiles.
 *
 * With [GeoFenceMode.INSIDE] the group is active while inside any point's
 * radius; with [GeoFenceMode.OUTSIDE] it is active while outside every point.
 */
class GeoFenceSettingsFragment : BottomSheetDialogFragment() {

    companion object {
        const val FRAGMENT_ID = "geofence_settings"
    }

    private var _binding: FragmentAppBlockerGeofenceSettingsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: AppBlockerSettingViewModel by activityViewModels()

    private var locationProvider: LocationProvider? = null

    private val dataStoreManager by lazy { DataStoreManager(requireContext().applicationContext) }

    /**
     * True once the global "lock geofencing" setting is on AND this group didn't
     * already have geofencing enabled. In that state the enable switch is held
     * off: a group that's already geofenced stays fully editable, but a new one
     * can't opt in.
     */
    private var geofencingLocked = false

    /** Live row bindings, one per centre point currently shown. */
    private val pointRows = mutableListOf<ItemGeofencePointBinding>()

    /** Row awaiting a location fix from a "use current location" tap. */
    private var pendingCaptureRow: ItemGeofencePointBinding? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result.values.any { it }
        if (granted) {
            captureCurrentLocation()
        } else {
            Toast.makeText(
                requireContext(),
                getString(R.string.location_activation_permission_denied),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAppBlockerGeofenceSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        loadConfig()

        binding.switchGeofenceEnabled.setOnCheckedChangeListener { _, isChecked ->
            binding.geofenceOptionsContainer.visibility = if (isChecked) View.VISIBLE else View.GONE
            if (isChecked) {
                if (pointRows.isEmpty()) addPointRow(null)
                maybeWarnAboutBackgroundLocation()
            }
        }

        // A group that's already geofenced stays editable; only opting a new one
        // in is blocked while the global lock is on. Read it async, then hold the
        // switch off if it applies.
        viewLifecycleOwner.lifecycleScope.launch {
            val restricted = dataStoreManager.settings.first().restrictNewGeofencing
            geofencingLocked = restricted && !viewModel.geoFenceConfig.enabled
            if (_binding != null) applyGeofenceLock()
        }

        binding.btnAddLocation.setOnClickListener {
            addPointRow(null)
        }

        binding.saveSettings.setOnClickListener {
            if (saveConfig()) dismiss()
        }
    }

    /**
     * Hold the enable switch off and surface the reason. No-op when the group is
     * already geofenced or the global lock is off, so existing geofences keep
     * working and stay editable.
     */
    private fun applyGeofenceLock() {
        if (!geofencingLocked) return
        binding.switchGeofenceEnabled.isChecked = false
        binding.switchGeofenceEnabled.isEnabled = false
        binding.switchGeofenceEnabled.alpha = 0.5f
        binding.geofenceOptionsContainer.visibility = View.GONE
        binding.tvGeofenceLockedHint.visibility = View.VISIBLE
    }

    private fun loadConfig() {
        val config = viewModel.geoFenceConfig
        binding.switchGeofenceEnabled.isChecked = config.enabled
        binding.geofenceOptionsContainer.visibility = if (config.enabled) View.VISIBLE else View.GONE

        when (config.mode) {
            GeoFenceMode.INSIDE -> binding.rgGeofenceMode.check(R.id.rb_inside)
            GeoFenceMode.OUTSIDE -> binding.rgGeofenceMode.check(R.id.rb_outside)
        }

        val points = config.resolvedPoints
        if (points.isEmpty()) {
            // Always show one empty row to fill in, even for a brand-new group.
            addPointRow(null)
        } else {
            points.forEach { addPointRow(it) }
        }
    }

    /**
     * Inflate a point row, optionally prefilled from [point], wire its buttons
     * and append it to the container.
     */
    private fun addPointRow(point: GeoFencePoint?) {
        val rowBinding = ItemGeofencePointBinding.inflate(
            layoutInflater, binding.pointsContainer, false
        )

        if (point != null && (point.latitude != 0.0 || point.longitude != 0.0)) {
            rowBinding.etLatitude.setText(point.latitude.toString())
            rowBinding.etLongitude.setText(point.longitude.toString())
        }
        rowBinding.etRadius.setText((point?.radiusMeters ?: GeoFencePoint().radiusMeters).toString())

        rowBinding.btnUseCurrentLocation.setOnClickListener {
            pendingCaptureRow = rowBinding
            ensurePermissionThenCapture()
        }

        rowBinding.btnRemovePoint.setOnClickListener {
            binding.pointsContainer.removeView(rowBinding.root)
            pointRows.remove(rowBinding)
            if (pendingCaptureRow === rowBinding) pendingCaptureRow = null
        }

        pointRows.add(rowBinding)
        binding.pointsContainer.addView(rowBinding.root)
        updateRowTitles()
    }

    /** Number the rows ("Location 1", "Location 2", …) for clarity. */
    private fun updateRowTitles() {
        pointRows.forEachIndexed { index, row ->
            row.tvPointTitle.text = if (pointRows.size > 1) {
                getString(R.string.location_activation_center_point) + " " + (index + 1)
            } else {
                getString(R.string.location_activation_center_point)
            }
        }
    }

    private fun ensurePermissionThenCapture() {
        val provider = locationProvider ?: LocationProvider(requireContext()).also { locationProvider = it }
        if (provider.hasPermission()) {
            captureCurrentLocation()
        } else {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    private fun captureCurrentLocation() {
        val row = pendingCaptureRow ?: return
        val provider = locationProvider ?: LocationProvider(requireContext()).also { locationProvider = it }
        row.tvCurrentLocationStatus.visibility = View.VISIBLE
        row.tvCurrentLocationStatus.text = getString(R.string.location_activation_locating)

        // Refresh the target row as soon as any fix (cached or fresh) is available.
        provider.onUpdate = {
            val loc = provider.lastLocation
            if (loc != null && _binding != null && pointRows.contains(row)) {
                fillRowFromLocation(row, loc.latitude, loc.longitude)
            }
        }
        provider.start()

        // Use whatever cached fix is already available immediately.
        val cached = provider.lastLocation
        if (cached != null) {
            fillRowFromLocation(row, cached.latitude, cached.longitude)
        }
    }

    private fun fillRowFromLocation(row: ItemGeofencePointBinding, lat: Double, lng: Double) {
        row.etLatitude.setText(lat.toString())
        row.etLongitude.setText(lng.toString())
        row.tvCurrentLocationStatus.visibility = View.VISIBLE
        row.tvCurrentLocationStatus.text = getString(R.string.location_activation_captured, lat, lng)
    }

    /**
     * On Android 10+ the accessibility service needs "Allow all the time"
     * location access to evaluate the geofence while running in the background.
     * Nudge the user toward it; the foreground capture above still works without it.
     */
    private fun maybeWarnAboutBackgroundLocation() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val provider = locationProvider ?: LocationProvider(requireContext()).also { locationProvider = it }
        if (!provider.hasPermission()) return
        val bgGranted = requireContext().checkSelfPermission(
            Manifest.permission.ACCESS_BACKGROUND_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (bgGranted) return

        Toast.makeText(
            requireContext(),
            getString(R.string.location_activation_background_hint),
            Toast.LENGTH_LONG
        ).show()
        try {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", requireContext().packageName, null)
                )
            )
        } catch (_: Exception) {
            // best effort; the foreground capture still works
        }
    }

    /** @return true if the config was valid and saved. */
    private fun saveConfig(): Boolean {
        // Authoritative backstop for the lock, in case the switch was toggled in
        // the brief window before the async lock state landed.
        if (geofencingLocked) {
            viewModel.geoFenceConfig = viewModel.geoFenceConfig.copy(enabled = false)
            return true
        }

        val enabled = binding.switchGeofenceEnabled.isChecked

        if (!enabled) {
            viewModel.geoFenceConfig = viewModel.geoFenceConfig.copy(enabled = false)
            return true
        }

        if (pointRows.isEmpty()) {
            Toast.makeText(
                requireContext(),
                getString(R.string.location_activation_no_points),
                Toast.LENGTH_SHORT
            ).show()
            return false
        }

        val points = mutableListOf<GeoFencePoint>()
        for (row in pointRows) {
            val lat = row.etLatitude.text?.toString()?.trim()?.toDoubleOrNull()
            val lng = row.etLongitude.text?.toString()?.trim()?.toDoubleOrNull()
            val radius = row.etRadius.text?.toString()?.trim()?.toFloatOrNull()

            if (lat == null || lng == null || lat !in -90.0..90.0 || lng !in -180.0..180.0) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.location_activation_invalid_coords),
                    Toast.LENGTH_SHORT
                ).show()
                return false
            }
            if (radius == null || radius <= 0f) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.location_activation_invalid_radius),
                    Toast.LENGTH_SHORT
                ).show()
                return false
            }

            points.add(GeoFencePoint(latitude = lat, longitude = lng, radiusMeters = radius))
        }

        val mode = if (binding.rgGeofenceMode.checkedRadioButtonId == R.id.rb_outside) {
            GeoFenceMode.OUTSIDE
        } else {
            GeoFenceMode.INSIDE
        }

        viewModel.geoFenceConfig = GeoFenceConfig(
            enabled = true,
            mode = mode,
            points = points
        )
        return true
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        return dialog
    }

    override fun onDestroyView() {
        super.onDestroyView()
        locationProvider?.stop()
        locationProvider = null
        pointRows.clear()
        pendingCaptureRow = null
        _binding = null
    }
}
