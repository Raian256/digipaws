package neth.iecal.curbox.ui.fragments.installation.onboarding

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
import neth.iecal.curbox.ui.fragments.installation.onboarding.screens.OnboardingInfoFragment
import neth.iecal.curbox.ui.fragments.installation.onboarding.screens.OnboardingPermissionsFragment

class OnboardingFragment : Fragment() {

    companion object {
        const val FRAGMENT_ID = "onboarding_fragment"
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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private inner class OnboardingPagerAdapter(fragment: Fragment) : FragmentStateAdapter(fragment) {
        override fun getItemCount(): Int = 4

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
                3 -> OnboardingPermissionsFragment()
                else -> throw IllegalArgumentException("Invalid position $position")
            }
        }
    }
}
