package neth.iecal.curbox.ui.fragments.installation.onboarding

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import neth.iecal.curbox.R
import neth.iecal.curbox.databinding.FragmentOnboardingBinding
import neth.iecal.curbox.ui.activity.FragmentActivity
import neth.iecal.curbox.ui.fragments.installation.onboarding.screens.OnboardingInfoFragment
import neth.iecal.curbox.ui.fragments.installation.onboarding.screens.OnboardingPermissionFragment
import neth.iecal.curbox.ui.fragments.main.usage.AllAppsUsageFragment

class OnboardingFragment : Fragment() {

    companion object {
        const val FRAGMENT_ID = "onboarding_fragment"

        // Number of intro pages shown before the per-permission pages.
        private const val INFO_PAGE_COUNT = 3
    }

    private var _binding: FragmentOnboardingBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {

        _binding = FragmentOnboardingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, 0)
            insets
        }
        val pagerAdapter = OnboardingPagerAdapter(this)
        binding.viewPager.adapter = pagerAdapter
        binding.viewPager.isUserInputEnabled = false // disable swipe, MUST click buttons
    }

    fun goToNextPage() {
        val currentItem = binding.viewPager.currentItem
        if (currentItem < (binding.viewPager.adapter?.itemCount ?: 0) - 1) {
            binding.viewPager.currentItem = currentItem + 1
        }
    }

    /**
     * Called from the final permission step once every permission has been
     * granted. Marks onboarding complete and opens the main app.
     */
    fun finishOnboarding() {
        val sharedPreferences =
            requireContext().getSharedPreferences("AppPreferences", Context.MODE_PRIVATE)
        sharedPreferences.edit().putBoolean("isFirstLaunchComplete", true).apply()

        val intent = Intent(requireContext(), FragmentActivity::class.java).apply {
            putExtra("fragment", AllAppsUsageFragment.FRAGMENT_ID)
        }
        startActivity(intent)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private inner class OnboardingPagerAdapter(fragment: Fragment) : FragmentStateAdapter(fragment) {
        // Three intro pages followed by one page per permission.
        private val permissionSteps = OnboardingPermissionFragment.PermissionStep.ORDER

        override fun getItemCount(): Int = INFO_PAGE_COUNT + permissionSteps.size

        override fun createFragment(position: Int): Fragment {
            return when (position) {
                0 -> OnboardingInfoFragment.newInstance(
                    R.string.onboarding_about_title,
                    R.string.onboarding_about_body
                )
                1 -> OnboardingInfoFragment.newInstance(
                    R.string.onboarding_blockers_title,
                    R.string.onboarding_blockers_body
                )
                2 -> OnboardingInfoFragment.newInstance(
                    R.string.onboarding_selfbind_title,
                    R.string.onboarding_selfbind_body
                )
                in INFO_PAGE_COUNT until itemCount ->
                    OnboardingPermissionFragment.newInstance(permissionSteps[position - INFO_PAGE_COUNT])
                else -> throw IllegalArgumentException("Invalid position $position")
            }
        }
    }
}
