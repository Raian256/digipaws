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
import neth.iecal.curbox.databinding.FragmentBlockedAppsLogBinding

class BlockedAppsLogFragment : Fragment() {
    private var _binding: FragmentBlockedAppsLogBinding? = null
    private val binding get() = _binding!!

    private val viewModel: BlockedAppsLogViewModel by viewModels {
        BlockedAppsLogViewModelFactory(AppDatabase.getInstance(requireContext()).blockedAppLogDao())
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentBlockedAppsLogBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val adapter = BlockedAppsLogAdapter { logId -> viewModel.deleteLog(logId) }
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter

        binding.btnBack.setNavigationOnClickListener {
            requireActivity().onBackPressed()
        }

        binding.btnBack.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_clear_all) {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Clear all entries?")
                    .setMessage("This will remove all blocked app log entries.")
                    .setPositiveButton("Clear") { _, _ -> viewModel.clearAll() }
                    .setNegativeButton("Cancel", null)
                    .show()
                true
            } else false
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
        const val FRAGMENT_ID = "blocked_apps_log_fragment"
    }
}
