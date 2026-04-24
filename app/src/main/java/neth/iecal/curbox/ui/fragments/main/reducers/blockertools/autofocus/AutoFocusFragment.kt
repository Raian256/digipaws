package neth.iecal.curbox.ui.fragments.main.reducers.blockertools.autofocus

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import neth.iecal.curbox.data.models.AntiModificationsConfig
import neth.iecal.curbox.data.models.AutoFocusGroup
import neth.iecal.curbox.data.models.FocusBlockMode
import neth.iecal.curbox.databinding.FragmentAutofocusBinding
import neth.iecal.curbox.databinding.ItemAutofocusGroupBinding
import neth.iecal.curbox.ui.fragments.main.reducers.anti_modifications.AntiModificationsGate
import neth.iecal.curbox.utils.DataStoreManager

class AutoFocusFragment : Fragment() {

    companion object {
        const val FRAGMENT_ID = "autofocus_fragment"
    }

    private var _binding: FragmentAutofocusBinding? = null
    private val binding get() = _binding!!

    private val viewModel: AutoFocusViewModel by activityViewModels()
    private val dataStoreManager by lazy { DataStoreManager(requireContext().applicationContext) }
    private var antiMods: AntiModificationsConfig = AntiModificationsConfig()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAutofocusBinding.inflate(inflater, container, false)

        binding.toolbar.setNavigationOnClickListener {
            requireActivity().finish()
        }

        binding.fabAddGroup.setOnClickListener {
            if (antiMods.isEnabled && antiMods.lockAllAutoFocusSchedules) {
                AntiModificationsGate.refuseWithSnackbar(binding.root)
                return@setOnClickListener
            }
            val intent = Intent(requireContext(), neth.iecal.curbox.ui.activity.FragmentActivity::class.java).apply {
                putExtra("fragment", CreateAutoFocusGroupFragment.FRAGMENT_ID)
            }
            startActivity(intent)
        }

        binding.rvAutofocusGroups.layoutManager = LinearLayoutManager(requireContext())

        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.groups.collectLatest { groups ->
                    if (groups.isEmpty()) {
                        binding.tvEmptyState.visibility = View.VISIBLE
                        binding.rvAutofocusGroups.visibility = View.GONE
                    } else {
                        binding.tvEmptyState.visibility = View.GONE
                        binding.rvAutofocusGroups.visibility = View.VISIBLE
                        binding.rvAutofocusGroups.adapter = AutoFocusGroupAdapter(groups)
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                dataStoreManager.settings.collectLatest { settings ->
                    antiMods = settings.antiModificationsConfig
                    binding.rvAutofocusGroups.adapter?.notifyDataSetChanged()
                }
            }
        }
    }

    inner class AutoFocusGroupAdapter(private val groupList: List<AutoFocusGroup>) :
        RecyclerView.Adapter<AutoFocusGroupAdapter.ViewHolder>() {

        inner class ViewHolder(val itemBinding: ItemAutofocusGroupBinding) : RecyclerView.ViewHolder(itemBinding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val itemBinding = ItemAutofocusGroupBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return ViewHolder(itemBinding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val group = groupList[position]
            holder.itemBinding.tvGroupName.text = group.groupName

            val typeText = if (group.blockMode == FocusBlockMode.BLOCK_SELECTED) "Included" else "Excluded"
            holder.itemBinding.tvGroupDetails.text = "${group.packages.size} Apps • $typeText"

            holder.itemView.setOnClickListener {
                if (AntiModificationsGate.isAutoFocusLocked(antiMods, group.groupId)) {
                    AntiModificationsGate.refuseWithSnackbar(holder.itemView)
                    return@setOnClickListener
                }
                val intent = Intent(requireContext(), neth.iecal.curbox.ui.activity.FragmentActivity::class.java).apply {
                    putExtra("fragment", CreateAutoFocusGroupFragment.FRAGMENT_ID)
                    putExtra("group_id", group.groupId)
                }
                startActivity(intent)
            }
        }

        override fun getItemCount() = groupList.size
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
