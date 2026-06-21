package neth.iecal.curbox.ui.activity

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import neth.iecal.curbox.R
import neth.iecal.curbox.utils.PermissionUtils
import neth.iecal.curbox.ui.fragments.installation.AccessibilityGuide
import neth.iecal.curbox.ui.fragments.installation.onboarding.OnboardingFragment
import neth.iecal.curbox.ui.fragments.main.focus.FocusFragment
import neth.iecal.curbox.ui.fragments.main.reducers.ReducersFragment
import neth.iecal.curbox.ui.fragments.main.reducers.analytics.BlockedAppsLogFragment
import neth.iecal.curbox.ui.fragments.main.reducers.analytics.IntentsLogFragment
import neth.iecal.curbox.ui.fragments.main.reducers.anti_modifications.AntiModificationsFragment
import neth.iecal.curbox.ui.fragments.main.reducers.anti_modifications.AntiModificationsGroupDetailsFragment
import neth.iecal.curbox.ui.fragments.main.reducers.anti_modifications.CreateAntiModificationsGroupFragment
import neth.iecal.curbox.ui.fragments.main.reducers.anti_uninstall.AntiUninstallFragment
import neth.iecal.curbox.ui.fragments.main.reducers.anti_uninstall.ChooseAntiUninstallModeFragment
import neth.iecal.curbox.ui.fragments.main.reducers.anti_uninstall.SetupCooldownModeFragment
import neth.iecal.curbox.ui.fragments.main.reducers.anti_uninstall.SetupPasswordModeFragment
import neth.iecal.curbox.ui.fragments.main.reducers.anti_uninstall.SetupTimedModeFragment
import neth.iecal.curbox.ui.fragments.main.usage.AllAppsUsageFragment
import neth.iecal.curbox.ui.fragments.main.reducers.blockertools.appBlocker.AppBlockerGroupsFragment
import neth.iecal.curbox.ui.fragments.main.reducers.blockertools.appBlocker.CreateAppGroupFragment
import neth.iecal.curbox.ui.fragments.main.reducers.blockertools.autofocus.AutoFocusFragment
import neth.iecal.curbox.ui.fragments.main.reducers.blockertools.autofocus.CreateAutoFocusGroupFragment
import neth.iecal.curbox.ui.fragments.main.reducers.blockertools.reelBlocker.ReelBlockerFragment
import neth.iecal.curbox.ui.fragments.main.reducers.blockertools.keywordBlocker.KeywordBlockerFragment
import neth.iecal.curbox.ui.fragments.main.reducers.blockertools.viewBlocker.ViewBlockerFragment

class FragmentActivity : AppCompatActivity() {

    private var deviceAdminDialog: androidx.appcompat.app.AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_fragment)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, 0)
            insets
        }

        val bottomNav = findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottom_nav)

        val sharedPreferences = getSharedPreferences("AppPreferences", android.content.Context.MODE_PRIVATE)
        val isFirstLaunchComplete = sharedPreferences.getBoolean("isFirstLaunchComplete", false)
        var selectedFragment = intent.getStringExtra("fragment") ?: AllAppsUsageFragment.FRAGMENT_ID
        if (!isFirstLaunchComplete && intent.getStringExtra("fragment") == null) {
            selectedFragment = OnboardingFragment.FRAGMENT_ID
        }
        
        when (selectedFragment) {
            OnboardingFragment.FRAGMENT_ID,
            AccessibilityGuide.FRAGMENT_ID,
            AppBlockerGroupsFragment.FRAGMENT_ID,
            CreateAppGroupFragment.FRAGMENT_ID,
            ReelBlockerFragment.FRAGMENT_ID,
            AutoFocusFragment.FRAGMENT_ID,
            CreateAutoFocusGroupFragment.FRAGMENT_ID,
            neth.iecal.curbox.ui.fragments.main.reducers.anti_stimulants.reel_counter.ReelCounterFragment.FRAGMENT_ID,
                ViewBlockerFragment.FRAGMENT_ID,
                IntentsLogFragment.FRAGMENT_ID,
                BlockedAppsLogFragment.FRAGMENT_ID,
            neth.iecal.curbox.ui.fragments.main.reducers.anti_stimulants.mindful_messages.MindfulMessagesFragment.FRAGMENT_ID,
            AntiUninstallFragment.FRAGMENT_ID,
            ChooseAntiUninstallModeFragment.FRAGMENT_ID,
            SetupPasswordModeFragment.FRAGMENT_ID,
            SetupTimedModeFragment.FRAGMENT_ID,
            SetupCooldownModeFragment.FRAGMENT_ID,
            AntiModificationsFragment.FRAGMENT_ID,
            CreateAntiModificationsGroupFragment.FRAGMENT_ID,
            AntiModificationsGroupDetailsFragment.FRAGMENT_ID,
            KeywordBlockerFragment.FRAGMENT_ID -> {
                // Hide bottom nav for these standalone fragments
                bottomNav.visibility = android.view.View.GONE
                
                val fragment = when (selectedFragment) {
                    OnboardingFragment.FRAGMENT_ID -> OnboardingFragment()
                    AppBlockerGroupsFragment.FRAGMENT_ID -> AppBlockerGroupsFragment()
                    CreateAppGroupFragment.FRAGMENT_ID -> CreateAppGroupFragment()
                    ReelBlockerFragment.FRAGMENT_ID -> ReelBlockerFragment()
                    KeywordBlockerFragment.FRAGMENT_ID -> KeywordBlockerFragment()
                    ViewBlockerFragment.FRAGMENT_ID -> ViewBlockerFragment()
                    AutoFocusFragment.FRAGMENT_ID -> AutoFocusFragment()
                    CreateAutoFocusGroupFragment.FRAGMENT_ID -> CreateAutoFocusGroupFragment()
                    neth.iecal.curbox.ui.fragments.main.reducers.anti_stimulants.reel_counter.ReelCounterFragment.FRAGMENT_ID -> neth.iecal.curbox.ui.fragments.main.reducers.anti_stimulants.reel_counter.ReelCounterFragment()
                    neth.iecal.curbox.ui.fragments.main.reducers.anti_stimulants.mindful_messages.MindfulMessagesFragment.FRAGMENT_ID -> neth.iecal.curbox.ui.fragments.main.reducers.anti_stimulants.mindful_messages.MindfulMessagesFragment()
                    IntentsLogFragment.FRAGMENT_ID -> IntentsLogFragment()
                    BlockedAppsLogFragment.FRAGMENT_ID -> BlockedAppsLogFragment()
                    AntiUninstallFragment.FRAGMENT_ID -> AntiUninstallFragment()
                    ChooseAntiUninstallModeFragment.FRAGMENT_ID -> ChooseAntiUninstallModeFragment()
                    SetupPasswordModeFragment.FRAGMENT_ID -> SetupPasswordModeFragment()
                    SetupTimedModeFragment.FRAGMENT_ID -> SetupTimedModeFragment()
                    SetupCooldownModeFragment.FRAGMENT_ID -> SetupCooldownModeFragment()
                    AntiModificationsFragment.FRAGMENT_ID -> AntiModificationsFragment()
                    CreateAntiModificationsGroupFragment.FRAGMENT_ID -> CreateAntiModificationsGroupFragment()
                    AntiModificationsGroupDetailsFragment.FRAGMENT_ID -> AntiModificationsGroupDetailsFragment()
                    else -> AccessibilityGuide()
                }
                fragment.arguments = intent.extras

                supportFragmentManager.beginTransaction()
                    .replace(R.id.fragment_holder, fragment)
                    .commit()
            }
            else -> {
                // Show bottom nav for main fragments
                bottomNav.visibility = android.view.View.VISIBLE
                
                if (savedInstanceState == null) {
                    bottomNav.selectedItemId = R.id.nav_usage
                    supportFragmentManager.beginTransaction()
                        .replace(R.id.fragment_holder, AllAppsUsageFragment())
                        .commit()
                }
                
                bottomNav.setOnItemSelectedListener { item ->
                    val fragment = when (item.itemId) {
                        R.id.nav_usage -> AllAppsUsageFragment()
                        R.id.nav_focus -> FocusFragment()
                        R.id.nav_reducers -> ReducersFragment()
                        R.id.nav_info -> neth.iecal.curbox.ui.fragments.main.InfoFragment()
                        else -> AllAppsUsageFragment()
                    }
                    
                    supportFragmentManager.beginTransaction()
                        .setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out)
                        .replace(R.id.fragment_holder, fragment)
                        .commit()
                    
                    true
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        maybePromptDeviceAdmin()
    }

    /**
     * Once onboarding is complete, Device Admin is required for anti-uninstall to
     * keep working. If the user has revoked it, reprompt them on every foreground
     * until it is re-enabled.
     */
    private fun maybePromptDeviceAdmin() {
        val sharedPreferences = getSharedPreferences("AppPreferences", android.content.Context.MODE_PRIVATE)
        if (!sharedPreferences.getBoolean("isFirstLaunchComplete", false)) return
        if (PermissionUtils.isDeviceAdminActive(this)) {
            deviceAdminDialog?.dismiss()
            deviceAdminDialog = null
            return
        }
        if (deviceAdminDialog?.isShowing == true) return

        deviceAdminDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.device_admin_required_title)
            .setMessage(R.string.device_admin_required_message)
            .setCancelable(false)
            .setPositiveButton(R.string.enable) { _, _ ->
                startActivity(PermissionUtils.buildDeviceAdminEnableIntent(this))
            }
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        deviceAdminDialog?.dismiss()
        deviceAdminDialog = null
    }
}