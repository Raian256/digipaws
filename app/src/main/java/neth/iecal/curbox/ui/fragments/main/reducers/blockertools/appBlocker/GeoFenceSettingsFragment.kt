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
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import neth.iecal.curbox.R
import neth.iecal.curbox.data.models.GeoFenceConfig
import neth.iecal.curbox.data.models.GeoFenceMode
import neth.iecal.curbox.databinding.FragmentAppBlockerGeofenceSettingsBinding
import neth.iecal.curbox.utils.LocationProvider

/**
 * Bottom sheet for configuring the optional geofence activation condition on an
 * app-block group. The centre point is captured from the device's current
 * location or typed in manually — there is no map, since the app holds no
 * network permission and therefore cannot fetch map tiles.
 */
class GeoFenceSettingsFragment : BottomSheetDialogFragment() {

    companion object {
        const val FRAGMENT_ID = "geofence_settings"
    }

    private var _binding: FragmentAppBlockerGeofenceSettingsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: AppBlockerSettingViewModel by activityViewModels()

    private var locationProvider: LocationProvider? = null

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
            if (isChecked) maybeWarnAboutBackgroundLocation()
        }

        binding.btnUseCurrentLocation.setOnClickListener {
            ensurePermissionThenCapture()
        }

        binding.saveSettings.setOnClickListener {
            if (saveConfig()) dismiss()
        }
    }

    private fun loadConfig() {
        val config = viewModel.geoFenceConfig
        binding.switchGeofenceEnabled.isChecked = config.enabled
        binding.geofenceOptionsContainer.visibility = if (config.enabled) View.VISIBLE else View.GONE

        when (config.mode) {
            GeoFenceMode.INSIDE -> binding.rgGeofenceMode.check(R.id.rb_inside)
            GeoFenceMode.OUTSIDE -> binding.rgGeofenceMode.check(R.id.rb_outside)
        }

        // Only prefill coordinates that were actually set, so a brand-new group
        // shows empty fields rather than "0.0, 0.0".
        if (config.latitude != 0.0 || config.longitude != 0.0) {
            binding.etLatitude.setText(config.latitude.toString())
            binding.etLongitude.setText(config.longitude.toString())
        }
        binding.etRadius.setText(config.radiusMeters.toString())
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
        val provider = locationProvider ?: LocationProvider(requireContext()).also { locationProvider = it }
        binding.tvCurrentLocationStatus.text = getString(R.string.location_activation_locating)

        // Refresh the fields as soon as any fix (cached or fresh) is available.
        provider.onUpdate = {
            val loc = provider.lastLocation
            if (loc != null && _binding != null) {
                binding.etLatitude.setText(loc.latitude.toString())
                binding.etLongitude.setText(loc.longitude.toString())
                binding.tvCurrentLocationStatus.text = getString(
                    R.string.location_activation_captured, loc.latitude, loc.longitude
                )
            }
        }
        provider.start()

        // Use whatever cached fix is already available immediately.
        val cached = provider.lastLocation
        if (cached != null) {
            binding.etLatitude.setText(cached.latitude.toString())
            binding.etLongitude.setText(cached.longitude.toString())
            binding.tvCurrentLocationStatus.text = getString(
                R.string.location_activation_captured, cached.latitude, cached.longitude
            )
        }
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
        val enabled = binding.switchGeofenceEnabled.isChecked

        if (!enabled) {
            viewModel.geoFenceConfig = viewModel.geoFenceConfig.copy(enabled = false)
            return true
        }

        val lat = binding.etLatitude.text?.toString()?.trim()?.toDoubleOrNull()
        val lng = binding.etLongitude.text?.toString()?.trim()?.toDoubleOrNull()
        val radius = binding.etRadius.text?.toString()?.trim()?.toFloatOrNull()

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

        val mode = if (binding.rgGeofenceMode.checkedRadioButtonId == R.id.rb_outside) {
            GeoFenceMode.OUTSIDE
        } else {
            GeoFenceMode.INSIDE
        }

        viewModel.geoFenceConfig = GeoFenceConfig(
            enabled = true,
            latitude = lat,
            longitude = lng,
            radiusMeters = radius,
            mode = mode
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
        _binding = null
    }
}
