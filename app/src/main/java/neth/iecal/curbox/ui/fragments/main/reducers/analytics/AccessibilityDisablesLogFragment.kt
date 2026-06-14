package neth.iecal.curbox.ui.fragments.main.reducers.analytics

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import neth.iecal.curbox.R
import neth.iecal.curbox.data.db.AppDatabase
import neth.iecal.curbox.databinding.FragmentAccessibilityDisablesLogBinding

class AccessibilityDisablesLogFragment : Fragment() {
    private var _binding: FragmentAccessibilityDisablesLogBinding? = null
    private val binding get() = _binding!!

    private val viewModel: AccessibilityDisablesLogViewModel by viewModels {
        AccessibilityDisablesLogViewModelFactory(
            AppDatabase.getInstance(requireContext()).accessibilityDisableLogDao()
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAccessibilityDisablesLogBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val adapter = AccessibilityDisablesLogAdapter { logId -> viewModel.deleteLog(logId) }
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter

        binding.btnBack.setNavigationOnClickListener {
            requireActivity().onBackPressed()
        }

        binding.btnBack.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_clear_all) {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Clear all entries?")
                    .setMessage("This will remove all accessibility-disable records.")
                    .setPositiveButton("Clear") { _, _ -> viewModel.clearAll() }
                    .setNegativeButton("Cancel", null)
                    .show()
                true
            } else false
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.weeklyCount.collectLatest { count ->
                binding.weeklyCount.text = count.toString()
                binding.weeklyLabel.text = resources.getQuantityString(
                    R.plurals.accessibility_disabled_this_week, count, count
                )
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.logs.collectLatest { logs ->
                adapter.submitList(logs)
                binding.emptyView.visibility = if (logs.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val FRAGMENT_ID = "accessibility_disables_log_fragment"
    }
}
