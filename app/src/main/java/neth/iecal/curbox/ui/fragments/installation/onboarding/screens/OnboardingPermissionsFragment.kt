package neth.iecal.curbox.ui.fragments.installation.onboarding.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.fragment.app.activityViewModels
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.gson.Gson
import neth.iecal.curbox.R
import neth.iecal.curbox.databinding.FragmentOnboardingPermissionsBinding
import neth.iecal.curbox.data.models.AppBlockerWarningScreenConfig
import neth.iecal.curbox.data.models.AppBlockingType
import neth.iecal.curbox.data.models.AppGroup
import neth.iecal.curbox.data.models.AppUsageConfig
import neth.iecal.curbox.services.AppBlockerService
import neth.iecal.curbox.services.UsageTrackingService
import neth.iecal.curbox.ui.activity.FragmentActivity
import neth.iecal.curbox.ui.fragments.installation.onboarding.OnboardingViewModel
import neth.iecal.curbox.ui.fragments.main.reducers.blockertools.appBlocker.AppBlockerSettingViewModel
import neth.iecal.curbox.ui.fragments.main.usage.AllAppsUsageFragment
import neth.iecal.curbox.utils.PermissionUtils
import neth.iecal.curbox.utils.backup.BackupManager
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import android.widget.Toast
import java.util.UUID

class OnboardingPermissionsFragment : Fragment() {

    private var _binding: FragmentOnboardingPermissionsBinding? = null
    private val binding get() = _binding!!

    private val onboardingViewModel: OnboardingViewModel by activityViewModels()
    private val appBlockerViewModel: AppBlockerSettingViewModel by activityViewModels()

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            updatePermissionsState()
        }

    private val shizukuPermissionListener = rikka.shizuku.Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == 1001 && grantResult == PackageManager.PERMISSION_GRANTED) {
            activity?.runOnUiThread {
                runShizukuGrantAllCommand()
            }
        }
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (neth.iecal.curbox.utils.PermissionUtils.isShizukuAvailable()) {
            try {
                rikka.shizuku.Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
            } catch (e: Exception) { e.printStackTrace() }
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
            // Persist onboarding app block group
            val targetApp = onboardingViewModel.targetAppPackage.value
            val limit = onboardingViewModel.dailyLimitMinutes.value ?: 30L
            
            val packageMap = mapOf(
                "Instagram" to "com.instagram.android",
                "TikTok" to "com.zhiliaoapp.musically",
                "YouTube" to "com.google.android.youtube",
                "Reddit" to "com.reddit.frontpage"
            )
            
            val pkg = packageMap[targetApp]
            if (pkg != null) {
                val usageConfig = AppUsageConfig(
                    isDailyUniform = true,
                    uniformLimit = limit,
                    dailyLimits = LongArray(7) { limit }
                )
                val newGroup = AppGroup(
                    id = UUID.randomUUID().toString(),
                    name = "$targetApp Limits",
                    selectedPackages = listOf(pkg),
                    blockingType = AppBlockingType.Usage,
                    isActive = true,
                    setting = Gson().toJson(usageConfig),
                    warningScreenConfig = AppBlockerWarningScreenConfig()
                )
                appBlockerViewModel.addGroup(newGroup)
            }

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
                rationale = "To break your scrolling habit, we need permission to show a 'pause' screen over distracting apps when you open them.",
                openSourceExplanation = "\uD83D\uDEE1\uFE0F Open Source: Think of our app like a restaurant with an 'open kitchen'. Our entire codebase is public. Anyone can look through it to verify we aren't doing anything sneaky. There are no closed doors here."
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
                rationale = "Curbox needs to know which app you are currently using so we can intervene exactly when you open a distracting app.",
                openSourceExplanation = "\uD83D\uDEE1\uFE0F Verified by the Community: Because our 'kitchen' is open, independent developers and privacy advocates can inspect our work. If we ever tried to track you, the community would find out immediately."
            ) {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }
        }

        binding.notifPermRoot.setOnClickListener {
            if (neth.iecal.curbox.utils.PermissionUtils.isNotificationPermissionGiven(requireContext())) return@setOnClickListener
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                showExplanationDialog(
                    title = "Notifications",
                    rationale = "We need this to keep the application running reliably in the background and to gently remind you of your goals.",
                    openSourceExplanation = "\uD83D\uDEE1\uFE0F Not a Data Broker: Most apps hide their code because their true business is harvesting your data. Since our code is 100% public, you can verify yourself that there is no hidden code sending your personal information away."
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
                rationale = "Curbox needs permission to control Do Not Disturb to automatically hide distractions when you are focusing.",
                openSourceExplanation = "\uD83D\uDEE1\uFE0F We respect your peace: Curbox uses this permission to mute distractions exactly when you want."
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
                rationale = "Curbox uses the Android AccessibilityService API to detect when you launch a target app and draw the blocker screen. This is crucial for the core app blocking to function.",
                openSourceExplanation = "\uD83D\uDEE1\uFE0F Transparency for Deep Access: This is a powerful permission, which is why being open source is so critical. You don't have to just trust our word that we only block apps—the global community has reviewed our public code to guarantee it."
            ) {
                PermissionUtils.openAccessibilityServiceScreen(requireContext(),AppBlockerService::class.java)
            }
        }

        binding.trackerAccPermRoot.setOnClickListener {
            if (neth.iecal.curbox.utils.PermissionUtils.isAccessibilityServiceEnabled(requireContext(), UsageTrackingService::class.java)) return@setOnClickListener
            showExplanationDialog(
                title = "Usage Tracker (Accessibility API)",
                rationale = "Curbox uses the Android AccessibilityService API to accurately measure your screen time and reel scrolling so we can provide you with honest reality-check statistics.",
                openSourceExplanation = "\uD83D\uDEE1\uFE0F Built for You, Not Advertisers: Curbox is a community-driven project built to help people, not to sell data. Our open source nature proves that our only goal is giving you your time back. Your data is yours alone."
            ) {
                PermissionUtils.openAccessibilityServiceScreen(requireContext(),
                    UsageTrackingService::class.java)
            }
        }

        binding.shizukuPermRoot.setOnClickListener {
            if (neth.iecal.curbox.utils.PermissionUtils.hasShizukuPermission()) return@setOnClickListener
            showExplanationDialog(
                title = "Shizuku Permission",
                rationale = "This permission is optional. It allows Curbox to perform more complex tasks and operations efficiently.",
                openSourceExplanation = "\uD83D\uDEE1\uFE0F Optional Power: While basic features work without this, granting Shizuku access enables deeper system-level integrations transparently."
            ) {
                if (neth.iecal.curbox.utils.PermissionUtils.isShizukuAvailable()) {
                    try {
                        rikka.shizuku.Shizuku.requestPermission(1001)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                } else {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/"))
                    startActivity(intent)
                }
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

        binding.btnShizukuGrantAll.setOnClickListener {
            if (!neth.iecal.curbox.utils.PermissionUtils.hasShizukuPermission()) {
                try {
                    rikka.shizuku.Shizuku.requestPermission(1001)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            } else {
                runShizukuGrantAllCommand()
            }
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

    override fun onDestroy() {
        super.onDestroy()
        if (neth.iecal.curbox.utils.PermissionUtils.isShizukuAvailable()) {
            try {
                rikka.shizuku.Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    private fun showExplanationDialog(title: String, rationale: String, openSourceExplanation: String, onProceed: () -> Unit) {
        val privacy = "\n\n\uD83D\uDD12 100% Private: We do not collect, send, or store any of your data on our servers. All processing stays strictly on your phone.\n\n"
        
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setMessage(rationale + privacy + openSourceExplanation)
            .setPositiveButton("Proceed") { _, _ -> onProceed() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun runShizukuGrantAllCommand() {
        binding.btnShizukuGrantAll.isEnabled = false
        binding.btnShizukuGrantAll.text = "Granting Permissions..."

        val pkg = requireContext().packageName
        val svc1 = "$pkg/${AppBlockerService::class.java.name}"
        val svc2 = "$pkg/${UsageTrackingService::class.java.name}"

        val command = """
            appops set $pkg SYSTEM_ALERT_WINDOW allow
            appops set $pkg GET_USAGE_STATS allow
            pm grant $pkg android.permission.POST_NOTIFICATIONS
            cmd notification allow_dnd $pkg
            
            CURRENT_ACC_SVCS=${'$'}(settings get secure enabled_accessibility_services)
            if [ "${'$'}CURRENT_ACC_SVCS" = "null" ] || [ -z "${'$'}CURRENT_ACC_SVCS" ]; then
                settings put secure enabled_accessibility_services "$svc1:$svc2"
            else
                NEW_SVCS="${'$'}CURRENT_ACC_SVCS"
                case "${'$'}CURRENT_ACC_SVCS" in
                    *"$svc1"*) ;;
                    *) NEW_SVCS="${'$'}NEW_SVCS:$svc1" ;;
                esac
                case "${'$'}CURRENT_ACC_SVCS" in
                    *"$svc2"*) ;;
                    *) NEW_SVCS="${'$'}NEW_SVCS:$svc2" ;;
                esac
                settings put secure enabled_accessibility_services "${'$'}NEW_SVCS"
            fi
            settings put secure accessibility_enabled 1
        """.trimIndent()

        neth.iecal.curbox.utils.ShizukuRunner.executeCommand(command, object : neth.iecal.curbox.utils.ShizukuRunner.CommandResultListener {
            override fun onCommandResult(output: String, done: Boolean) {
                if (done) {
                    activity?.runOnUiThread {
                        binding.btnShizukuGrantAll.text = "Permissions Granted!"
                        binding.btnShizukuGrantAll.isEnabled = true
                        updatePermissionsState()
                    }
                }
            }

            override fun onCommandError(error: String) {
                activity?.runOnUiThread {
                    binding.btnShizukuGrantAll.isEnabled = true
                    binding.btnShizukuGrantAll.text = "Error, Tap to Retry"
                    updatePermissionsState()
                }
            }
        })
    }

    private fun updatePermissionsState() {
        val hasOverlay = Settings.canDrawOverlays(requireContext())
        val hasUsageStats = neth.iecal.curbox.utils.PermissionUtils.hasUsageStatsPermission(requireContext())
        val hasNotif = neth.iecal.curbox.utils.PermissionUtils.isNotificationPermissionGiven(requireContext())
        val hasDnd = (requireContext().getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager).isNotificationPolicyAccessGranted
        val hasBlocker = neth.iecal.curbox.utils.PermissionUtils.isAccessibilityServiceEnabled(requireContext(), AppBlockerService::class.java)
        val hasTracker = neth.iecal.curbox.utils.PermissionUtils.isAccessibilityServiceEnabled(requireContext(), UsageTrackingService::class.java)
        val hasShizuku = neth.iecal.curbox.utils.PermissionUtils.hasShizukuPermission()
        
        if (neth.iecal.curbox.utils.PermissionUtils.isShizukuAvailable()) {
            binding.btnShizukuGrantAll.visibility = View.VISIBLE
        } else {
            binding.btnShizukuGrantAll.visibility = View.GONE
        }

        setPermissionIcon(hasOverlay, binding.overlayPermIcon)
        setPermissionIcon(hasUsageStats, binding.usageStatsPermIcon)
        setPermissionIcon(hasNotif, binding.notifPermIcon)
        setPermissionIcon(hasDnd, binding.dndPermIcon)
        setPermissionIcon(hasBlocker, binding.blockerAccPermIcon)
        setPermissionIcon(hasTracker, binding.trackerAccPermIcon)
        setPermissionIcon(hasShizuku, binding.shizukuPermIcon)

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

        val canDoShizuku = canDoTracker && hasTracker
        binding.shizukuPermRoot.isEnabled = canDoShizuku && !hasShizuku
        binding.shizukuPermRoot.alpha = if (canDoShizuku) (if (hasShizuku) 0.5f else 1.0f) else 0.3f

        val allGranted = hasOverlay && hasUsageStats && hasNotif && hasDnd && hasBlocker && hasTracker
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
