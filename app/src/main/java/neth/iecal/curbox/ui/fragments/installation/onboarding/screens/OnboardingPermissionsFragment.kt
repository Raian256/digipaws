package neth.iecal.curbox.ui.fragments.installation.onboarding.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import neth.iecal.curbox.R
import neth.iecal.curbox.databinding.FragmentOnboardingPermissionsBinding
import neth.iecal.curbox.services.AppBlockerService
import neth.iecal.curbox.services.UsageTrackingService
import neth.iecal.curbox.ui.activity.FragmentActivity
import neth.iecal.curbox.ui.fragments.main.usage.AllAppsUsageFragment
import neth.iecal.curbox.utils.PermissionUtils
import neth.iecal.curbox.utils.backup.BackupManager
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import android.widget.Toast

class OnboardingPermissionsFragment : Fragment() {

    private var _binding: FragmentOnboardingPermissionsBinding? = null
    private val binding get() = _binding!!

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            updatePermissionsState()
        }

    private val restorePicker: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            result.data?.data?.let { uri ->
                lifecycleScope.launch {
                    BackupManager.import(requireContext(), uri)
                        .onSuccess { summary ->
                            val msg = buildString {
                                append("Backup restored")
                                if (summary.skippedIncompatible.isNotEmpty()) {
                                    append(". Skipped (newer-format): ")
                                    append(summary.skippedIncompatible.joinToString())
                                }
                            }
                            Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
                        }
                        .onFailure { e ->
                            Toast.makeText(
                                requireContext(),
                                "Restore failed: ${e.message}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                }
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentOnboardingPermissionsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnAction.setOnClickListener {
            val sharedPreferences =
                requireContext().getSharedPreferences("AppPreferences", Context.MODE_PRIVATE)
            sharedPreferences.edit().putBoolean("isFirstLaunchComplete", true).apply()

            val intent = Intent(requireContext(), FragmentActivity::class.java).apply {
                putExtra("fragment", AllAppsUsageFragment.FRAGMENT_ID)
            }
            startActivity(intent)
        }

        binding.overlayPermRoot.setOnClickListener {
            if (Settings.canDrawOverlays(requireContext())) return@setOnClickListener
            showExplanationDialog(
                title = "Screen Overlay",
                rationale = "Curbox needs permission to draw over other apps so it can show the warning/pause screen over a blocked app when you open it."
            ) {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                    data = Uri.parse("package:${requireContext().packageName}")
                }
                startActivity(intent)
            }
        }

        binding.usageStatsPermRoot.setOnClickListener {
            if (neth.iecal.curbox.utils.PermissionUtils.hasUsageStatsPermission(requireContext())) return@setOnClickListener
            showExplanationDialog(
                title = "Usage Access",
                rationale = "Curbox uses usage access to tell which app is in the foreground so it can act when you open a blocked app."
            ) {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }
        }

        binding.notifPermRoot.setOnClickListener {
            if (neth.iecal.curbox.utils.PermissionUtils.isNotificationPermissionGiven(requireContext())) return@setOnClickListener
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                showExplanationDialog(
                    title = "Notifications",
                    rationale = "Curbox needs notification permission to run reliably in the background and to post its status and reminders."
                ) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }

        binding.dndPermRoot.setOnClickListener {
            val notificationManager = requireContext().getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            if (notificationManager.isNotificationPolicyAccessGranted) return@setOnClickListener
            
            showExplanationDialog(
                title = "Do Not Disturb",
                rationale = "Curbox needs Do Not Disturb access to mute distractions automatically during focus sessions."
            ) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                    startActivity(intent)
                }
            }
        }

        binding.blockerAccPermRoot.setOnClickListener {
            if (neth.iecal.curbox.utils.PermissionUtils.isAccessibilityServiceEnabled(requireContext(), AppBlockerService::class.java)) return@setOnClickListener
            showExplanationDialog(
                title = "App Blocker (Accessibility API)",
                rationale = "Curbox uses the accessibility service to detect when a blocked app is launched and to draw the blocker screen. This is required for app blocking to work."
            ) {
                PermissionUtils.openAccessibilityServiceScreen(requireContext(),AppBlockerService::class.java)
            }
        }

        binding.trackerAccPermRoot.setOnClickListener {
            if (neth.iecal.curbox.utils.PermissionUtils.isAccessibilityServiceEnabled(requireContext(), UsageTrackingService::class.java)) return@setOnClickListener
            showExplanationDialog(
                title = "Usage Tracker (Accessibility API)",
                rationale = "Curbox uses the accessibility service to measure screen time and reel scrolling for the usage statistics."
            ) {
                PermissionUtils.openAccessibilityServiceScreen(requireContext(),
                    UsageTrackingService::class.java)
            }
        }

        binding.deviceAdminPermRoot.setOnClickListener {
            if (neth.iecal.curbox.utils.PermissionUtils.isDeviceAdminActive(requireContext())) return@setOnClickListener
            showExplanationDialog(
                title = "Device Admin (Anti-Uninstall)",
                rationale = "Device Admin lets Curbox resist being uninstalled while anti-uninstall is active. You can revoke it later from within Curbox."
            ) {
                startActivity(PermissionUtils.buildDeviceAdminEnableIntent(requireContext()))
            }
        }

        binding.restoreRoot.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/json"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "*/*"))
            }
            restorePicker.launch(intent)
        }

        updatePermissionsState()
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null) {
            updatePermissionsState()
        }
    }

    override fun onPause() {
        super.onPause()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun showExplanationDialog(title: String, rationale: String, onProceed: () -> Unit) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setMessage(rationale)
            .setPositiveButton("Proceed") { _, _ -> onProceed() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updatePermissionsState() {
        val hasOverlay = Settings.canDrawOverlays(requireContext())
        val hasUsageStats = neth.iecal.curbox.utils.PermissionUtils.hasUsageStatsPermission(requireContext())
        val hasNotif = neth.iecal.curbox.utils.PermissionUtils.isNotificationPermissionGiven(requireContext())
        val hasDnd = (requireContext().getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager).isNotificationPolicyAccessGranted
        val hasBlocker = neth.iecal.curbox.utils.PermissionUtils.isAccessibilityServiceEnabled(requireContext(), AppBlockerService::class.java)
        val hasTracker = neth.iecal.curbox.utils.PermissionUtils.isAccessibilityServiceEnabled(requireContext(), UsageTrackingService::class.java)
        val hasAdmin = neth.iecal.curbox.utils.PermissionUtils.isDeviceAdminActive(requireContext())

        setPermissionIcon(hasOverlay, binding.overlayPermIcon)
        setPermissionIcon(hasUsageStats, binding.usageStatsPermIcon)
        setPermissionIcon(hasNotif, binding.notifPermIcon)
        setPermissionIcon(hasDnd, binding.dndPermIcon)
        setPermissionIcon(hasBlocker, binding.blockerAccPermIcon)
        setPermissionIcon(hasTracker, binding.trackerAccPermIcon)
        setPermissionIcon(hasAdmin, binding.deviceAdminPermIcon)

        // Enforce Sequence
        binding.overlayPermRoot.isEnabled = !hasOverlay
        binding.overlayPermRoot.alpha = if (hasOverlay) 0.5f else 1.0f

        val canDoUsage = hasOverlay
        binding.usageStatsPermRoot.isEnabled = canDoUsage && !hasUsageStats
        binding.usageStatsPermRoot.alpha = if (canDoUsage) (if (hasUsageStats) 0.5f else 1.0f) else 0.3f

        val canDoNotif = canDoUsage && hasUsageStats
        binding.notifPermRoot.isEnabled = canDoNotif && !hasNotif
        binding.notifPermRoot.alpha = if (canDoNotif) (if (hasNotif) 0.5f else 1.0f) else 0.3f

        val canDoDnd = canDoNotif && hasNotif
        binding.dndPermRoot.isEnabled = canDoDnd && !hasDnd
        binding.dndPermRoot.alpha = if (canDoDnd) (if (hasDnd) 0.5f else 1.0f) else 0.3f

        val canDoBlocker = canDoDnd && hasDnd
        binding.blockerAccPermRoot.isEnabled = canDoBlocker && !hasBlocker
        binding.blockerAccPermRoot.alpha = if (canDoBlocker) (if (hasBlocker) 0.5f else 1.0f) else 0.3f

        val canDoTracker = canDoBlocker && hasBlocker
        binding.trackerAccPermRoot.isEnabled = canDoTracker && !hasTracker
        binding.trackerAccPermRoot.alpha = if (canDoTracker) (if (hasTracker) 0.5f else 1.0f) else 0.3f

        val canDoAdmin = canDoTracker && hasTracker
        binding.deviceAdminPermRoot.isEnabled = canDoAdmin && !hasAdmin
        binding.deviceAdminPermRoot.alpha = if (canDoAdmin) (if (hasAdmin) 0.5f else 1.0f) else 0.3f

        val allGranted = hasOverlay && hasUsageStats && hasNotif && hasDnd && hasBlocker && hasTracker && hasAdmin
        binding.btnAction.isEnabled = allGranted
        if (allGranted) {
            binding.btnAction.text = "Finish Onboarding"
        } else {
            binding.btnAction.text = "Enable Permissions"
        }
    }

    private fun setPermissionIcon(isEnabled: Boolean, icon: ImageView) {
        if (isEnabled) {
            icon.setImageResource(R.drawable.baseline_done_24)
            icon.setColorFilter(resources.getColor(R.color.md_theme_onSurface, requireContext().theme))
        } else {
            icon.setImageResource(R.drawable.baseline_close_24)
            icon.setColorFilter(resources.getColor(R.color.error_color, requireContext().theme))
        }
    }
}
