package neth.iecal.curbox.ui.fragments.installation.onboarding.screens

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.StringRes
import androidx.core.os.bundleOf
import androidx.core.text.HtmlCompat
import androidx.fragment.app.Fragment
import neth.iecal.curbox.databinding.FragmentOnboardingInfoBinding
import neth.iecal.curbox.ui.fragments.installation.onboarding.OnboardingFragment

/**
 * A read-only onboarding page that shows a title and an HTML body, then advances
 * to the next onboarding page. Used for the goals/scope and feature overview pages.
 */
class OnboardingInfoFragment : Fragment() {

    private var _binding: FragmentOnboardingInfoBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentOnboardingInfoBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val args = requireArguments()
        binding.tvTitle.text = getString(args.getInt(ARG_TITLE))
        binding.tvBody.text = HtmlCompat.fromHtml(
            getString(args.getInt(ARG_BODY)),
            HtmlCompat.FROM_HTML_MODE_COMPACT
        )

        binding.btnAction.setOnClickListener {
            (parentFragment as? OnboardingFragment)?.goToNextPage()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val ARG_TITLE = "title"
        private const val ARG_BODY = "body"

        fun newInstance(@StringRes title: Int, @StringRes body: Int): OnboardingInfoFragment =
            OnboardingInfoFragment().apply {
                arguments = bundleOf(ARG_TITLE to title, ARG_BODY to body)
            }
    }
}
