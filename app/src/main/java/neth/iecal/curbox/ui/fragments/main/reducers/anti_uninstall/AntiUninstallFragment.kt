package neth.iecal.curbox.ui.fragments.main.reducers.anti_uninstall

import android.content.Intent
import android.os.Bundle
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import neth.iecal.curbox.Constants
import neth.iecal.curbox.R
import neth.iecal.curbox.data.models.AntiUninstallConfig
import neth.iecal.curbox.databinding.DialogRemoveAntiUninstallBinding
import neth.iecal.curbox.databinding.FragmentAntiUninstallBinding
import neth.iecal.curbox.ui.activity.FragmentActivity
import neth.iecal.curbox.utils.DataStoreManager
import neth.iecal.curbox.utils.HashUtils
import java.util.Calendar

class AntiUninstallFragment : Fragment() {

    companion object {
        const val FRAGMENT_ID = "anti_uninstall_fragment"
    }

    private var _binding: FragmentAntiUninstallBinding? = null
    private val binding get() = _binding!!

    private val dataStoreManager by lazy { DataStoreManager(requireContext().applicationContext) }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAntiUninstallBinding.inflate(inflater, container, false)
        binding.toolbar.setNavigationOnClickListener { requireActivity().finish() }
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnEnable.setOnClickListener {
            startActivity(Intent(requireContext(), FragmentActivity::class.java).apply {
                putExtra("fragment", ChooseAntiUninstallModeFragment.FRAGMENT_ID)
            })
        }

        binding.btnRemove.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val config = dataStoreManager.settings.first().antiUninstallConfig
                handleRemoveRequest(config)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                dataStoreManager.settings.collect { settings ->
                    renderState(settings.antiUninstallConfig)
                }
            }
        }
    }

    private fun renderState(config: AntiUninstallConfig) {
        if (config.isEnabled) {
            binding.disabledContainer.visibility = View.GONE
            binding.enabledContainer.visibility = View.VISIBLE
            binding.statusMode.text = when (config.mode) {
                Constants.ANTI_UNINSTALL_PASSWORD_MODE ->
                    getString(R.string.anti_uninstall_status_password_mode)
                Constants.ANTI_UNINSTALL_TIMED_MODE ->
                    getString(R.string.anti_uninstall_status_timed_mode)
                else -> ""
            }
            binding.statusDetails.text = when (config.mode) {
                Constants.ANTI_UNINSTALL_PASSWORD_MODE ->
                    getString(R.string.anti_uninstall_status_password_details)
                Constants.ANTI_UNINSTALL_TIMED_MODE -> {
                    val formatted = DateFormat.getLongDateFormat(requireContext())
                        .format(config.endTimeInMillis)
                    getString(R.string.anti_uninstall_status_timed_details, formatted)
                }
                else -> ""
            }
        } else {
            binding.disabledContainer.visibility = View.VISIBLE
            binding.enabledContainer.visibility = View.GONE
        }
    }

    private fun handleRemoveRequest(config: AntiUninstallConfig) {
        when (config.mode) {
            Constants.ANTI_UNINSTALL_TIMED_MODE -> {
                val now = System.currentTimeMillis()
                if (config.endTimeInMillis <= now) {
                    disableAntiUninstall()
                } else {
                    val daysRemaining = daysBetween(now, config.endTimeInMillis)
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.anti_uninstall_remove_failed)
                        .setMessage(
                            resources.getQuantityString(
                                R.plurals.anti_uninstall_days_remaining,
                                daysRemaining,
                                daysRemaining
                            )
                        )
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            }
            Constants.ANTI_UNINSTALL_PASSWORD_MODE -> {
                val dialogBinding = DialogRemoveAntiUninstallBinding.inflate(layoutInflater)
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.anti_uninstall_remove)
                    .setView(dialogBinding.root)
                    .setPositiveButton(R.string.remove) { _, _ ->
                        val entered = dialogBinding.password.text?.toString().orEmpty()
                        if (HashUtils.sha256(entered) == config.passwordHash) {
                            disableAntiUninstall()
                        } else {
                            Snackbar.make(
                                binding.root,
                                R.string.anti_uninstall_incorrect_password,
                                Snackbar.LENGTH_LONG
                            ).setAction(R.string.retry) { handleRemoveRequest(config) }.show()
                        }
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        }
    }

    private fun disableAntiUninstall() {
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                dataStoreManager.updateAntiUninstallConfig(AntiUninstallConfig())
            }
            Snackbar.make(binding.root, R.string.anti_uninstall_removed, Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun daysBetween(fromMillis: Long, toMillis: Long): Int {
        val from = Calendar.getInstance().apply { timeInMillis = fromMillis; zeroOutTime() }
        val to = Calendar.getInstance().apply { timeInMillis = toMillis; zeroOutTime() }
        val diff = to.timeInMillis - from.timeInMillis
        return ((diff + 1) / (1000L * 60 * 60 * 24)).toInt().coerceAtLeast(1)
    }

    private fun Calendar.zeroOutTime() {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
