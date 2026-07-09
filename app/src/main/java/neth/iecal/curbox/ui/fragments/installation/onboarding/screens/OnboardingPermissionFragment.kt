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
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import neth.iecal.curbox.R
import neth.iecal.curbox.databinding.FragmentOnboardingPermissionBinding
import neth.iecal.curbox.services.AppBlockerService
import neth.iecal.curbox.services.UsageTrackingService
import neth.iecal.curbox.ui.fragments.installation.onboarding.OnboardingFragment
import neth.iecal.curbox.utils.PermissionUtils
import neth.iecal.curbox.utils.backup.BackupManager

/**
 * A single onboarding step that requests exactly one permission. The onboarding
 * pager shows one of these per permission, in the order declared by
 * [PermissionStep.ORDER]. The user cannot advance until the permission is
 * granted, which naturally enforces the intended grant sequence.
 */
class OnboardingPermissionFragment : Fragment() {

    /**
     * The permissions requested during onboarding, in the order they are shown.
     * Usage Tracker (accessibility) is intentionally placed before App Blocker
     * (accessibility).
     */
    enum class PermissionStep(
        @StringRes val title: Int,
        @StringRes val rationale: Int
    ) {
        OVERLAY(R.string.display_over_other_apps, R.string.onboarding_perm_rationale_overlay),
        USAGE_ACCESS(R.string.usage_access, R.string.onboarding_perm_rationale_usage_access),
        NOTIFICATIONS(R.string.send_notifications, R.string.onboarding_perm_rationale_notifications),
        DND(R.string.dnd_access, R.string.onboarding_perm_rationale_dnd),
        USAGE_TRACKER(R.string.usage_tracker_accessibility, R.string.onboarding_perm_rationale_usage_tracker),
        APP_BLOCKER(R.string.app_blocker_accessibility, R.string.onboarding_perm_rationale_app_blocker),
        DEVICE_ADMIN(R.string.device_admin_access, R.string.onboarding_perm_rationale_device_admin);

        companion object {
            val ORDER: List<PermissionStep> = PermissionStep.entries
        }
    }

    private var _binding: FragmentOnboardingPermissionBinding? = null
    private val binding get() = _binding!!

    private lateinit var step: PermissionStep

    // Remembers whether this step's permission was already granted, so we can
    // detect the moment it is freshly granted (used for the accessibility
    // review reminder).
    private var wasGranted: Boolean? = null

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            updateState()
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
        _binding = FragmentOnboardingPermissionBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        step = PermissionStep.valueOf(requireArguments().getString(ARG_STEP)!!)

        val index = PermissionStep.ORDER.indexOf(step)
        binding.tvStep.text = getString(
            R.string.onboarding_permission_step_counter,
            index + 1,
            PermissionStep.ORDER.size
        )
        binding.tvTitle.text = getString(step.title)
        binding.tvRationale.text = getString(step.rationale)

        // Offer backup restore only once, on the very first permission step.
        val showRestore = index == 0
        binding.tvRestorePrompt.visibility = if (showRestore) View.VISIBLE else View.GONE
        binding.btnRestore.visibility = if (showRestore) View.VISIBLE else View.GONE
        binding.btnRestore.setOnClickListener { launchRestorePicker() }

        binding.btnAction.setOnClickListener {
            if (isGranted()) advance() else requestPermission()
        }

        updateState()
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null) {
            updateState()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun isGranted(): Boolean {
        val context = requireContext()
        return when (step) {
            PermissionStep.OVERLAY -> Settings.canDrawOverlays(context)
            PermissionStep.USAGE_ACCESS -> PermissionUtils.hasUsageStatsPermission(context)
            PermissionStep.NOTIFICATIONS -> PermissionUtils.isNotificationPermissionGiven(context)
            PermissionStep.DND -> (context.getSystemService(Context.NOTIFICATION_SERVICE)
                    as android.app.NotificationManager).isNotificationPolicyAccessGranted
            PermissionStep.USAGE_TRACKER ->
                PermissionUtils.isAccessibilityServiceEnabled(context, UsageTrackingService::class.java)
            PermissionStep.APP_BLOCKER ->
                PermissionUtils.isAccessibilityServiceEnabled(context, AppBlockerService::class.java)
            PermissionStep.DEVICE_ADMIN -> PermissionUtils.isDeviceAdminActive(context)
        }
    }

    private fun requestPermission() {
        val context = requireContext()
        when (step) {
            PermissionStep.OVERLAY -> startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                )
            )
            PermissionStep.USAGE_ACCESS ->
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            PermissionStep.NOTIFICATIONS ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            PermissionStep.DND ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                }
            PermissionStep.USAGE_TRACKER ->
                PermissionUtils.openAccessibilityServiceScreen(context, UsageTrackingService::class.java)
            PermissionStep.APP_BLOCKER ->
                PermissionUtils.openAccessibilityServiceScreen(context, AppBlockerService::class.java)
            PermissionStep.DEVICE_ADMIN ->
                startActivity(PermissionUtils.buildDeviceAdminEnableIntent(context))
        }
    }

    private fun advance() {
        val parent = parentFragment as? OnboardingFragment ?: return
        if (PermissionStep.ORDER.last() == step) {
            parent.finishOnboarding()
        } else {
            parent.goToNextPage()
        }
    }

    private fun updateState() {
        val granted = isGranted()

        if (granted) {
            binding.permStatusText.text = getString(R.string.onboarding_permission_granted)
            binding.permStatusIcon.setImageResource(R.drawable.baseline_done_24)
            binding.permStatusIcon.setColorFilter(
                resources.getColor(R.color.md_theme_onSurface, requireContext().theme)
            )
            binding.btnAction.text = getString(
                if (PermissionStep.ORDER.last() == step) R.string.onboarding_finish_setup
                else R.string.onboarding_continue
            )
        } else {
            binding.permStatusText.text = getString(R.string.onboarding_permission_not_granted)
            binding.permStatusIcon.setImageResource(R.drawable.baseline_close_24)
            binding.permStatusIcon.setColorFilter(
                resources.getColor(R.color.error_color, requireContext().theme)
            )
            binding.btnAction.text = getString(R.string.onboarding_grant_permission)
        }

        // When an accessibility service is freshly enabled, remind the user to
        // dismiss the system's accessibility-review pop-up in Security & Privacy.
        val isAccessibilityStep =
            step == PermissionStep.USAGE_TRACKER || step == PermissionStep.APP_BLOCKER
        if (isAccessibilityStep && wasGranted == false && granted) {
            showAccessibilityReviewReminder()
        }
        wasGranted = granted
    }

    private fun launchRestorePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "*/*"))
        }
        restorePicker.launch(intent)
    }

    /**
     * After a user enables an accessibility service, the system posts a Security &
     * Privacy notification/pop-up asking whether they want to turn the newly
     * enabled service back off. If the user opens Security & Privacy while that
     * pop-up is still pending, they can disable our accessibility service from
     * there — even with anti-uninstall active. We can't stop them visiting that
     * screen, but once the pop-up is dismissed it never reappears, so we gently
     * ask the user to go dismiss it now.
     */
    private fun showAccessibilityReviewReminder() {
        if (_binding == null || !isAdded) return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("One quick step")
            .setMessage(
                "Now that accessibility is on, your phone will show a one-time reminder in " +
                "Settings → Security & Privacy asking whether to turn it back off.\n\n" +
                "Please open Security & Privacy and dismiss that reminder. Once it's " +
                "cleared it won't come back, which keeps Curbox protected."
            )
            .setPositiveButton("Open Security Settings") { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
                } catch (e: Exception) {
                    try {
                        startActivity(Intent(Settings.ACTION_SETTINGS))
                    } catch (_: Exception) {
                    }
                }
            }
            .setNegativeButton("I'll do it later", null)
            .show()
    }

    companion object {
        private const val ARG_STEP = "step"

        fun newInstance(step: PermissionStep): OnboardingPermissionFragment =
            OnboardingPermissionFragment().apply {
                arguments = bundleOf(ARG_STEP to step.name)
            }
    }
}
