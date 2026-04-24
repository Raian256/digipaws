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
import neth.iecal.curbox.databinding.FragmentSetupPasswordModeBinding
import neth.iecal.curbox.utils.DataStoreManager
import neth.iecal.curbox.utils.HashUtils

class SetupPasswordModeFragment : Fragment() {

    companion object {
        const val FRAGMENT_ID = "anti_uninstall_setup_password"
    }

    private var _binding: FragmentSetupPasswordModeBinding? = null
    private val binding get() = _binding!!

    private val dataStoreManager by lazy { DataStoreManager(requireContext().applicationContext) }
    private val target by lazy { LockSetupTarget.fromArgs(this) }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSetupPasswordModeBinding.inflate(inflater, container, false)
        binding.toolbar.setNavigationOnClickListener { requireActivity().finish() }
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnTurnOn.setOnClickListener { onTurnOnClicked() }
    }

    private fun onTurnOnClicked() {
        val password = binding.password.text?.toString().orEmpty()
        val confirm = binding.confirmPassword.text?.toString().orEmpty()

        binding.passwordLayout.error = null
        binding.confirmPasswordLayout.error = null

        if (password.isEmpty()) {
            binding.passwordLayout.error = getString(R.string.anti_uninstall_password_empty)
            return
        }
        if (confirm.isEmpty()) {
            binding.confirmPasswordLayout.error = getString(R.string.anti_uninstall_confirm_password_empty)
            return
        }
        if (password != confirm) {
            binding.confirmPasswordLayout.error = getString(R.string.anti_uninstall_passwords_do_not_match)
            return
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.anti_uninstall_confirm_title)
            .setMessage(R.string.anti_uninstall_confirm_enable_message)
            .setPositiveButton(R.string.anti_uninstall_i_understand) { _, _ ->
                saveAndExit(password)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun saveAndExit(password: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                when (target) {
                    LockSetupTarget.ANTI_UNINSTALL -> {
                        dataStoreManager.updateAntiUninstallConfig(
                            AntiUninstallConfig(
                                isEnabled = true,
                                mode = Constants.ANTI_UNINSTALL_PASSWORD_MODE,
                                passwordHash = HashUtils.sha256(password)
                            )
                        )
                    }
                    LockSetupTarget.ANTI_MODIFICATIONS -> {
                        val current = dataStoreManager.settings.first().antiModificationsConfig
                        dataStoreManager.updateAntiModificationsConfig(
                            current.copy(
                                isEnabled = true,
                                mode = Constants.ANTI_UNINSTALL_PASSWORD_MODE,
                                passwordHash = HashUtils.sha256(password),
                                endTimeInMillis = 0L,
                                cooldownMinutes = 0,
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
