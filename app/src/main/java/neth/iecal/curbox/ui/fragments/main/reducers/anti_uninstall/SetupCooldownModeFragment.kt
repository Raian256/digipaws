package neth.iecal.curbox.ui.fragments.main.reducers.anti_uninstall

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import neth.iecal.curbox.Constants
import neth.iecal.curbox.R
import neth.iecal.curbox.data.models.AntiUninstallConfig
import neth.iecal.curbox.databinding.FragmentSetupCooldownModeBinding
import neth.iecal.curbox.utils.DataStoreManager

class SetupCooldownModeFragment : Fragment() {

    companion object {
        const val FRAGMENT_ID = "anti_uninstall_setup_cooldown"
    }

    private var _binding: FragmentSetupCooldownModeBinding? = null
    private val binding get() = _binding!!

    private val dataStoreManager by lazy { DataStoreManager(requireContext().applicationContext) }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSetupCooldownModeBinding.inflate(inflater, container, false)
        binding.toolbar.setNavigationOnClickListener { requireActivity().finish() }
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.switchBlockConfig.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) showBlockConfigWarning()
        }

        binding.btnTurnOn.setOnClickListener { onTurnOnClicked() }
    }

    private fun onTurnOnClicked() {
        val minutes = binding.minutes.text?.toString()?.toIntOrNull() ?: 0
        binding.minutesLayout.error = null
        if (minutes <= 0) {
            binding.minutesLayout.error = getString(R.string.anti_uninstall_cooldown_minutes_invalid)
            return
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.anti_uninstall_confirm_title)
            .setMessage(R.string.anti_uninstall_confirm_enable_message)
            .setPositiveButton(R.string.anti_uninstall_i_understand) { _, _ -> saveAndExit(minutes) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun saveAndExit(minutes: Int) {
        val blockChanges = binding.switchBlockConfig.isChecked
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                dataStoreManager.updateAntiUninstallConfig(
                    AntiUninstallConfig(
                        isEnabled = true,
                        mode = Constants.ANTI_UNINSTALL_COOLDOWN_MODE,
                        cooldownMinutes = minutes,
                        blockConfigChanges = blockChanges
                    )
                )
            }
            requireActivity().finish()
        }
    }

    private fun showBlockConfigWarning() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.anti_uninstall_confirm_title)
            .setMessage(R.string.anti_uninstall_block_config_warning)
            .setPositiveButton(R.string.anti_uninstall_i_understand, null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
