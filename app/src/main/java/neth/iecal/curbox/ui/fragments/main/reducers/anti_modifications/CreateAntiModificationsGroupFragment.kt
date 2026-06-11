package neth.iecal.curbox.ui.fragments.main.reducers.anti_modifications

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import neth.iecal.curbox.Constants
import neth.iecal.curbox.R
import neth.iecal.curbox.data.models.AntiModificationsGroup
import neth.iecal.curbox.data.models.Settings
import neth.iecal.curbox.databinding.FragmentCreateAntiModificationsGroupBinding
import neth.iecal.curbox.ui.fragments.main.reducers.blockertools.viewBlocker.ViewBlockerLabels
import neth.iecal.curbox.utils.DataStoreManager
import neth.iecal.curbox.utils.HashUtils
import java.util.Calendar

class CreateAntiModificationsGroupFragment : Fragment() {

    companion object {
        const val FRAGMENT_ID = "anti_modifications_create_group"
        const val ARG_GROUP_ID = "group_id"
    }

    private var _binding: FragmentCreateAntiModificationsGroupBinding? = null
    private val binding get() = _binding!!

    private val dataStoreManager by lazy { DataStoreManager(requireContext().applicationContext) }

    private var editingGroupId: String? = null

    private val pickedAppPauseIds = mutableSetOf<String>()
    private val pickedAutoFocusIds = mutableSetOf<String>()
    private val pickedKeywords = mutableSetOf<String>()
    private val pickedViewBlockerIds = mutableSetOf<String>()
    private var pickedLockEssentials: Boolean = false
    private var pickedLockGeofenceFailMode: Boolean = false

    private var selectedEndMillis: Long = 0L

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCreateAntiModificationsGroupBinding.inflate(inflater, container, false)
        binding.toolbar.setNavigationOnClickListener { requireActivity().finish() }
        editingGroupId = arguments?.getString(ARG_GROUP_ID)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.modeGroup.setOnCheckedChangeListener { _, id -> renderModeSections(id) }
        renderModeSections(binding.modeGroup.checkedRadioButtonId)

        binding.calendarView.minDate = System.currentTimeMillis()
        binding.calendarView.setOnDateChangeListener { _, year, month, dayOfMonth ->
            val cal = Calendar.getInstance().apply {
                set(year, month, dayOfMonth, 0, 0, 0)
                set(Calendar.MILLISECOND, 0)
            }
            selectedEndMillis = cal.timeInMillis
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val settings = dataStoreManager.settings.first()
            // If editing an existing group, we're actually used for "Add items to
            // existing group". Seed the picker with current items.
            val existing = editingGroupId?.let { id ->
                settings.antiModificationsConfig.groups.firstOrNull { it.id == id }
            }
            if (existing != null) {
                binding.toolbar.title = getString(R.string.anti_modifications_group_add_items)
                binding.nameLayout.visibility = View.GONE
                binding.modeGroup.visibility = View.GONE
                binding.passwordSection.visibility = View.GONE
                binding.timedSection.visibility = View.GONE
                binding.cooldownLayout.visibility = View.GONE
                pickedAppPauseIds.addAll(existing.lockedAppPauseScheduleIds)
                pickedAutoFocusIds.addAll(existing.lockedAutoFocusScheduleIds)
                pickedKeywords.addAll(existing.lockedKeywords)
                pickedViewBlockerIds.addAll(existing.lockedViewBlockerIds)
                pickedLockEssentials = existing.lockEssentialAppsList
                pickedLockGeofenceFailMode = existing.lockGeofenceFailMode
            }
            populatePickers(settings)
            populateEssentialsSection()
            populateGeofenceFailModeSection()
            updateItemsSummary()
        }

        binding.btnSave.setOnClickListener { onSaveClicked() }
    }

    private fun renderModeSections(radioId: Int) {
        binding.passwordSection.visibility =
            if (radioId == binding.modePassword.id) View.VISIBLE else View.GONE
        binding.timedSection.visibility =
            if (radioId == binding.modeTimed.id) View.VISIBLE else View.GONE
        binding.cooldownLayout.visibility =
            if (radioId == binding.modeCooldown.id) View.VISIBLE else View.GONE
    }

    private fun populatePickers(settings: Settings) {
        populateSection(
            binding.sectionAppPause,
            settings.blockedAppGroups.map { it.id to it.name },
            pickedAppPauseIds
        )
        populateSection(
            binding.sectionAutofocus,
            settings.autoFocusGroups.map { it.groupId to it.groupName },
            pickedAutoFocusIds
        )
        populateSection(
            binding.sectionKeywords,
            settings.keywordBlockerConfig.blockedKeywords.map { it to it },
            pickedKeywords
        )
        val viewBlockerItems = buildList {
            addAll(settings.viewBlockerConfig.rules.map { it.id to it.label })
            addAll(settings.viewBlockerConfig.customRules.map {
                it to ViewBlockerLabels.labelFor(it)
            })
        }
        populateSection(
            binding.sectionViewBlockers,
            viewBlockerItems,
            pickedViewBlockerIds
        )
    }

    private fun populateSection(
        container: LinearLayout,
        items: List<Pair<String, String>>,
        selected: MutableSet<String>
    ) {
        container.removeAllViews()
        if (items.isEmpty()) {
            val tv = TextView(requireContext()).apply {
                text = getString(R.string.anti_modifications_section_nothing_available)
                setTextColor(requireContext().getColor(android.R.color.darker_gray))
                textSize = 13f
            }
            container.addView(tv)
            return
        }
        for ((id, label) in items) {
            val cb = CheckBox(requireContext()).apply {
                text = label
                isChecked = id in selected
                setOnCheckedChangeListener { _, checked ->
                    if (checked) selected.add(id) else selected.remove(id)
                    updateItemsSummary()
                }
            }
            container.addView(cb)
        }
    }

    private fun populateEssentialsSection() {
        binding.sectionEssentials.removeAllViews()
        val cb = CheckBox(requireContext()).apply {
            text = getString(R.string.anti_modifications_essentials_item_label)
            isChecked = pickedLockEssentials
            setOnCheckedChangeListener { _, checked ->
                pickedLockEssentials = checked
                updateItemsSummary()
            }
        }
        binding.sectionEssentials.addView(cb)
    }

    private fun populateGeofenceFailModeSection() {
        binding.sectionGeofenceFailmode.removeAllViews()
        val cb = CheckBox(requireContext()).apply {
            text = getString(R.string.anti_modifications_geofence_failmode_item_label)
            isChecked = pickedLockGeofenceFailMode
            setOnCheckedChangeListener { _, checked ->
                pickedLockGeofenceFailMode = checked
                updateItemsSummary()
            }
        }
        binding.sectionGeofenceFailmode.addView(cb)
    }

    private fun updateItemsSummary() {
        val total = pickedAppPauseIds.size + pickedAutoFocusIds.size +
            pickedKeywords.size + pickedViewBlockerIds.size +
            (if (pickedLockEssentials) 1 else 0) +
            (if (pickedLockGeofenceFailMode) 1 else 0)
        binding.itemsSummary.text = getString(R.string.anti_modifications_items_count_summary, total)
    }

    private fun onSaveClicked() {
        val existingId = editingGroupId
        if (existingId != null) {
            saveAddItemsToExisting(existingId)
            return
        }

        binding.nameLayout.error = null
        binding.passwordLayout.error = null
        binding.confirmPasswordLayout.error = null
        binding.cooldownLayout.error = null

        val name = binding.name.text?.toString()?.trim().orEmpty()
        if (name.isEmpty()) {
            binding.nameLayout.error = getString(R.string.anti_modifications_group_name_empty)
            return
        }

        val totalItems = pickedAppPauseIds.size + pickedAutoFocusIds.size +
            pickedKeywords.size + pickedViewBlockerIds.size +
            (if (pickedLockEssentials) 1 else 0) +
            (if (pickedLockGeofenceFailMode) 1 else 0)
        if (totalItems == 0) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.anti_modifications_title)
                .setMessage(R.string.anti_modifications_group_no_items)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }

        val mode: Int
        val passwordHash: String
        val endTimeInMillis: Long
        val cooldownMinutes: Int

        when (binding.modeGroup.checkedRadioButtonId) {
            binding.modePassword.id -> {
                val pw = binding.password.text?.toString().orEmpty()
                val cf = binding.confirmPassword.text?.toString().orEmpty()
                if (pw.isEmpty()) {
                    binding.passwordLayout.error = getString(R.string.anti_uninstall_password_empty)
                    return
                }
                if (pw != cf) {
                    binding.confirmPasswordLayout.error =
                        getString(R.string.anti_uninstall_passwords_do_not_match)
                    return
                }
                mode = Constants.ANTI_UNINSTALL_PASSWORD_MODE
                passwordHash = HashUtils.sha256(pw)
                endTimeInMillis = 0L
                cooldownMinutes = 0
            }
            binding.modeTimed.id -> {
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
                mode = Constants.ANTI_UNINSTALL_TIMED_MODE
                passwordHash = ""
                endTimeInMillis = selectedEndMillis
                cooldownMinutes = 0
            }
            else -> {
                val minutes = binding.cooldownMinutes.text?.toString()?.toIntOrNull() ?: 0
                if (minutes <= 0) {
                    binding.cooldownLayout.error =
                        getString(R.string.anti_uninstall_cooldown_minutes_invalid)
                    return
                }
                mode = Constants.ANTI_UNINSTALL_COOLDOWN_MODE
                passwordHash = ""
                endTimeInMillis = 0L
                cooldownMinutes = minutes
            }
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.anti_uninstall_confirm_title)
            .setMessage(R.string.anti_uninstall_confirm_enable_message)
            .setPositiveButton(R.string.anti_uninstall_i_understand) { _, _ ->
                val group = AntiModificationsGroup(
                    name = name,
                    mode = mode,
                    passwordHash = passwordHash,
                    endTimeInMillis = endTimeInMillis,
                    cooldownMinutes = cooldownMinutes,
                    lockedAppPauseScheduleIds = pickedAppPauseIds.toSet(),
                    lockedAutoFocusScheduleIds = pickedAutoFocusIds.toSet(),
                    lockedKeywords = pickedKeywords.toSet(),
                    lockedViewBlockerIds = pickedViewBlockerIds.toSet(),
                    lockEssentialAppsList = pickedLockEssentials,
                    lockGeofenceFailMode = pickedLockGeofenceFailMode
                )
                AntiModificationsUnlock.createGroup(this, group)
                requireActivity().finish()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun saveAddItemsToExisting(groupId: String) {
        // Tightening only — add items to the group without any unlock challenge.
        AntiModificationsUnlock.tighten(this, groupId) { g ->
            g.copy(
                lockedAppPauseScheduleIds = g.lockedAppPauseScheduleIds + pickedAppPauseIds,
                lockedAutoFocusScheduleIds = g.lockedAutoFocusScheduleIds + pickedAutoFocusIds,
                lockedKeywords = g.lockedKeywords + pickedKeywords,
                lockedViewBlockerIds = g.lockedViewBlockerIds + pickedViewBlockerIds,
                // Tighten only — never flip the flag off here.
                lockEssentialAppsList = g.lockEssentialAppsList || pickedLockEssentials,
                lockGeofenceFailMode = g.lockGeofenceFailMode || pickedLockGeofenceFailMode
            )
        }
        requireActivity().finish()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
