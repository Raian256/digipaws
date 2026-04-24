package neth.iecal.curbox.ui.fragments.main.reducers.anti_uninstall

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
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
    private val target by lazy { LockSetupTarget.fromArgs(this) }

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
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                when (target) {
                    LockSetupTarget.ANTI_UNINSTALL -> {
                        dataStoreManager.updateAntiUninstallConfig(
                            AntiUninstallConfig(
                                isEnabled = true,
                                mode = Constants.ANTI_UNINSTALL_COOLDOWN_MODE,
                                cooldownMinutes = minutes
                            )
                        )
                    }
                    LockSetupTarget.ANTI_MODIFICATIONS -> {
                        val current = dataStoreManager.settings.first().antiModificationsConfig
                        dataStoreManager.updateAntiModificationsConfig(
                            current.copy(
                                isEnabled = true,
                                mode = Constants.ANTI_UNINSTALL_COOLDOWN_MODE,
                                cooldownMinutes = minutes,
                                passwordHash = "",
                                endTimeInMillis = 0L,
                                removalRequestedAt = 0L
                            )
                        )
                    }
                }
            }
            requireActivity().finish()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
