package neth.iecal.curbox.ui.fragments.main.reducers.blockertools.appBlocker

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import neth.iecal.curbox.R
import neth.iecal.curbox.data.models.AntiModificationsConfig
import neth.iecal.curbox.data.models.AppBlockingType
import neth.iecal.curbox.data.models.AppGroup
import neth.iecal.curbox.ui.activity.FragmentActivity
import neth.iecal.curbox.ui.fragments.main.reducers.anti_modifications.AntiModificationsGate
import neth.iecal.curbox.utils.DataStoreManager

class AppBlockerGroupsFragment : Fragment() {

    companion object {
        const val FRAGMENT_ID = "app_blocker_groups"
    }

    private lateinit var rvGroups: RecyclerView
    private lateinit var tvEmptyState: TextView
    private lateinit var fabAddGroup: FloatingActionButton
    private lateinit var toolbar: MaterialToolbar

    private val viewModel: AppBlockerSettingViewModel by activityViewModels()
    private val dataStoreManager by lazy { DataStoreManager(requireContext().applicationContext) }
    private var antiMods: AntiModificationsConfig = AntiModificationsConfig()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_app_blocker_groups, container, false)

        rvGroups = view.findViewById(R.id.rv_app_groups)
        tvEmptyState = view.findViewById(R.id.tv_empty_state)
        fabAddGroup = view.findViewById(R.id.fab_add_group)
        toolbar = view.findViewById(R.id.toolbar)

        toolbar.setNavigationOnClickListener {
            requireActivity().finish()
        }

        fabAddGroup.setOnClickListener {
            val intent = Intent(requireContext(), FragmentActivity::class.java).apply {
                putExtra("fragment", CreateAppGroupFragment.FRAGMENT_ID)
            }
            startActivity(intent)
        }

        rvGroups.layoutManager = LinearLayoutManager(requireContext())

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.groups.collectLatest { groups ->
                    if (groups.isEmpty()) {
                        tvEmptyState.visibility = View.VISIBLE
                        rvGroups.visibility = View.GONE
                    } else {
                        tvEmptyState.visibility = View.GONE
                        rvGroups.visibility = View.VISIBLE
                        rvGroups.adapter = AppGroupAdapter(groups)
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                dataStoreManager.settings.collectLatest { settings ->
                    antiMods = settings.antiModificationsConfig
                    rvGroups.adapter?.notifyDataSetChanged()
                }
            }
        }
    }

    inner class AppGroupAdapter(private val groupList: List<AppGroup>) :
        RecyclerView.Adapter<AppGroupAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvName: TextView = view.findViewById(R.id.tv_group_name)
            val tvDetails: TextView = view.findViewById(R.id.tv_group_details)
            val switchActive: SwitchMaterial = view.findViewById(R.id.switch_group_active)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_app_group, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val group = groupList[position]
            holder.tvName.text = group.name

            val typeText = if (group.blockingType == AppBlockingType.Timed) "Time Based" else "Usage Based"
            holder.tvDetails.text = "${group.selectedPackages.size} Apps • $typeText"

            holder.switchActive.setOnCheckedChangeListener(null)
            holder.switchActive.isChecked = group.isActive

            val locked = AntiModificationsGate.isAppPauseLocked(antiMods, group.id)

            val activeListener = object : CompoundButton.OnCheckedChangeListener {
                override fun onCheckedChanged(buttonView: CompoundButton, isChecked: Boolean) {
                    if (locked) {
                        buttonView.setOnCheckedChangeListener(null)
                        buttonView.isChecked = group.isActive
                        AntiModificationsGate.refuseWithSnackbar(holder.itemView)
                        // Re-bind the same locked-aware listener so the next toggle is
                        // also refused — re-binding a lock-less variant defeats the lock.
                        buttonView.setOnCheckedChangeListener(this)
                        return
                    }
                    viewModel.updateGroupActiveState(position, isChecked)
                }
            }
            holder.switchActive.setOnCheckedChangeListener(activeListener)

            holder.itemView.setOnClickListener {
                if (locked) {
                    AntiModificationsGate.refuseWithSnackbar(holder.itemView)
                    return@setOnClickListener
                }
                val intent = Intent(requireContext(), FragmentActivity::class.java).apply {
                    putExtra("fragment", CreateAppGroupFragment.FRAGMENT_ID)
                    putExtra("group_id", group.id)
                }
                startActivity(intent)
            }
        }

        override fun getItemCount() = groupList.size
    }
}
