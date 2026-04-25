package neth.iecal.curbox.ui.fragments.main.reducers.anti_uninstall

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import neth.iecal.curbox.databinding.FragmentChooseAntiUninstallModeBinding
import neth.iecal.curbox.ui.activity.FragmentActivity

class ChooseAntiUninstallModeFragment : Fragment() {

    companion object {
        const val FRAGMENT_ID = "anti_uninstall_choose_mode"
    }

    private var _binding: FragmentChooseAntiUninstallModeBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentChooseAntiUninstallModeBinding.inflate(inflater, container, false)
        binding.toolbar.setNavigationOnClickListener { requireActivity().finish() }
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnNext.setOnClickListener {
            val fragmentId = when (binding.radioGroup.checkedRadioButtonId) {
                binding.modeTimed.id -> SetupTimedModeFragment.FRAGMENT_ID
                binding.modeCooldown.id -> SetupCooldownModeFragment.FRAGMENT_ID
                else -> SetupPasswordModeFragment.FRAGMENT_ID
            }
            startActivity(Intent(requireContext(), FragmentActivity::class.java).apply {
                putExtra("fragment", fragmentId)
            })
            requireActivity().finish()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
