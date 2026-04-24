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
import neth.iecal.curbox.databinding.FragmentSetupTimedModeBinding
import neth.iecal.curbox.utils.DataStoreManager
import java.util.Calendar

class SetupTimedModeFragment : Fragment() {

    companion object {
        const val FRAGMENT_ID = "anti_uninstall_setup_timed"
    }

    private var _binding: FragmentSetupTimedModeBinding? = null
    private val binding get() = _binding!!

    private val dataStoreManager by lazy { DataStoreManager(requireContext().applicationContext) }

    private var selectedEndMillis: Long = 0L

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSetupTimedModeBinding.inflate(inflater, container, false)
        binding.toolbar.setNavigationOnClickListener { requireActivity().finish() }
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.calendarView.minDate = System.currentTimeMillis()
        binding.calendarView.setOnDateChangeListener { _, year, month, dayOfMonth ->
            val cal = Calendar.getInstance().apply {
                set(year, month, dayOfMonth, 0, 0, 0)
                set(Calendar.MILLISECOND, 0)
            }
            selectedEndMillis = cal.timeInMillis
        }

        binding.btnTurnOn.setOnClickListener { onTurnOnClicked() }
    }

    private fun onTurnOnClicked() {
        val today = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        if (selectedEndMillis <= today) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.anti_uninstall_confirm_title)
                .setMessage(R.string.anti_uninstall_pick_future_date)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.anti_uninstall_confirm_title)
            .setMessage(R.string.anti_uninstall_confirm_enable_message)
            .setPositiveButton(R.string.anti_uninstall_i_understand) { _, _ -> saveAndExit() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun saveAndExit() {
        val endMillis = selectedEndMillis
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                dataStoreManager.updateAntiUninstallConfig(
                    AntiUninstallConfig(
                        isEnabled = true,
                        mode = Constants.ANTI_UNINSTALL_TIMED_MODE,
                        endTimeInMillis = endMillis
                    )
                )
            }
            requireActivity().finish()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
