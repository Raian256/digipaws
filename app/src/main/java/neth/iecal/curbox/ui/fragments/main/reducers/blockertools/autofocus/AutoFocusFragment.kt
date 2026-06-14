package neth.iecal.curbox.ui.fragments.main.reducers.blockertools.autofocus

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import neth.iecal.curbox.R
import neth.iecal.curbox.blockers.FocusModeBlocker
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

    private var groups: List<AutoFocusGroup> = emptyList()
    private var runningGroupIds: Set<String> = emptySet()
    private var antiMods: AntiModificationsConfig = AntiModificationsConfig()

    private val adapter = AutoFocusGroupAdapter()

    private val cancelExitReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != FocusModeBlocker.INTENT_ACTION_CANCEL_EXIT_AUTO_FOCUS) return
            if (_binding == null) return
            // A pending exit was cancelled from the notification, so the local
            // cooldown that disabled the Pause button no longer applies.
            clearAllAutoFocusCooldowns()
            adapter.notifyDataSetChanged()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAutofocusBinding.inflate(inflater, container, false)

        binding.toolbar.setNavigationOnClickListener {
            requireActivity().finish()
        }

        binding.fabAddGroup.setOnClickListener {
            val intent = Intent(requireContext(), neth.iecal.curbox.ui.activity.FragmentActivity::class.java).apply {
                putExtra("fragment", CreateAutoFocusGroupFragment.FRAGMENT_ID)
            }
            startActivity(intent)
        }

        binding.rvAutofocusGroups.layoutManager = LinearLayoutManager(requireContext())
        binding.rvAutofocusGroups.adapter = adapter

        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.groups.collect { newGroups ->
                        groups = newGroups
                        renderEmptyState()
                        adapter.submit(newGroups)
                    }
                }
                launch {
                    viewModel.runningGroupIds.collect { ids ->
                        runningGroupIds = ids
                        adapter.notifyDataSetChanged()
                    }
                }
                launch {
                    dataStoreManager.settings.collect { settings ->
                        antiMods = settings.antiModificationsConfig
                        adapter.notifyDataSetChanged()
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(FocusModeBlocker.INTENT_ACTION_CANCEL_EXIT_AUTO_FOCUS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requireContext().registerReceiver(cancelExitReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            requireContext().registerReceiver(cancelExitReceiver, filter)
        }
        if (_binding != null) adapter.notifyDataSetChanged()
    }

    override fun onPause() {
        super.onPause()
        try {
            requireContext().unregisterReceiver(cancelExitReceiver)
        } catch (_: IllegalArgumentException) {
        }
    }

    private fun renderEmptyState() {
        if (_binding == null) return
        val empty = groups.isEmpty()
        binding.tvEmptyState.visibility = if (empty) View.VISIBLE else View.GONE
        binding.rvAutofocusGroups.visibility = if (empty) View.GONE else View.VISIBLE
    }

    // region cooldown bookkeeping (mirrors the group's exitCooldownMinutes locally)

    private fun cooldownPrefs() =
        requireContext().getSharedPreferences("AppPreferences", Context.MODE_PRIVATE)

    private fun cooldownKey(groupId: String) = "autofocus_cooldown_end_$groupId"

    private fun cooldownEndFor(groupId: String): Long =
        cooldownPrefs().getLong(cooldownKey(groupId), 0L)

    private fun setCooldownEnd(groupId: String, cooldownMinutes: Int) {
        if (cooldownMinutes <= 0) return
        cooldownPrefs().edit()
            .putLong(cooldownKey(groupId), System.currentTimeMillis() + cooldownMinutes * 60_000L)
            .apply()
    }

    private fun clearAllAutoFocusCooldowns() {
        val prefs = cooldownPrefs()
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith("autofocus_cooldown_end_") }.forEach { editor.remove(it) }
        editor.apply()
    }

    // endregion

    private fun showExitAutoFocusDialog(group: AutoFocusGroup) {
        val ctx = requireContext()
        val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_exit_auto_focus, null)
        val etIntent = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.et_intent)
        val etMinutes = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.et_minutes)
        val tilIntent = view.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.til_intent)

        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
            .setTitle(getString(R.string.exit_auto_focus_dialog_title))
            .setMessage(group.groupName)
            .setView(view)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (System.currentTimeMillis() < cooldownEndFor(group.groupId)) {
                    Toast.makeText(ctx, getString(R.string.exit_cooldown_already_running), Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                    adapter.notifyDataSetChanged()
                    return@setOnClickListener
                }
                val intentText = etIntent.text?.toString()?.trim().orEmpty()
                val minutes = etMinutes.text?.toString()?.trim()?.toIntOrNull() ?: 0
                if (intentText.isEmpty()) {
                    tilIntent.error = getString(R.string.exit_auto_focus_intent_required)
                    return@setOnClickListener
                }
                if (minutes <= 0) {
                    etMinutes.error = getString(R.string.exit_auto_focus_minutes_required)
                    return@setOnClickListener
                }
                val pauseMs = minutes * 60_000L

                viewLifecycleOwner.lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    val log = neth.iecal.curbox.data.db.IntentLogEntity(
                        timestamp = System.currentTimeMillis(),
                        packageName = "autofocus:${group.groupName}",
                        intentText = intentText,
                        unlockedDurationMs = pauseMs
                    )
                    neth.iecal.curbox.data.db.AppDatabase.getInstance(ctx.applicationContext)
                        .intentLogDao().insert(log)
                }

                setCooldownEnd(group.groupId, group.exitCooldownMinutes)

                val broadcastIntent = Intent(FocusModeBlocker.INTENT_ACTION_EXIT_AUTO_FOCUS)
                broadcastIntent.setPackage(ctx.packageName)
                broadcastIntent.putExtra("group_id", group.groupId)
                broadcastIntent.putExtra("pause_minutes", minutes)
                broadcastIntent.putExtra("intent_text", intentText)
                ctx.sendBroadcast(broadcastIntent)
                dialog.dismiss()

                adapter.notifyDataSetChanged()
            }
        }
        dialog.show()
    }

    inner class AutoFocusGroupAdapter :
        RecyclerView.Adapter<AutoFocusGroupAdapter.ViewHolder>() {

        private val items = mutableListOf<AutoFocusGroup>()

        fun submit(newItems: List<AutoFocusGroup>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        inner class ViewHolder(val itemBinding: ItemAutofocusGroupBinding) : RecyclerView.ViewHolder(itemBinding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val itemBinding = ItemAutofocusGroupBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return ViewHolder(itemBinding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val group = items[position]
            val b = holder.itemBinding
            b.tvGroupName.text = group.groupName

            val typeText = if (group.blockMode == FocusBlockMode.BLOCK_SELECTED) "Included" else "Excluded"
            b.tvGroupDetails.text = "${group.packages.size} Apps • $typeText"

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

            bindPauseControls(b, group)
        }

        override fun getItemCount() = items.size
    }

    /**
     * Whether [group] is inside one of today's intervals right now. Mirrors the
     * blocker's interval check so the UI agrees with what is actually blocking,
     * and so a stale, never-closed session can't make an out-of-window schedule
     * look like it's running.
     */
    private fun isScheduleActiveNow(group: AutoFocusGroup): Boolean {
        val cal = java.util.Calendar.getInstance()
        val calDay = cal.get(java.util.Calendar.DAY_OF_WEEK)
        val currentDay = if (calDay == java.util.Calendar.SUNDAY) 6 else calDay - 2
        val currentMinutes = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
        val intervals = group.dailyIntervals[currentDay] ?: return false
        return intervals.any { interval ->
            val start = interval.startHour * 60 + interval.startMinute
            val end = interval.endHour * 60 + interval.endMinute
            if (start <= end) currentMinutes in start until end
            else currentMinutes >= start || currentMinutes < end
        }
    }

    private fun bindPauseControls(b: ItemAutofocusGroupBinding, group: AutoFocusGroup) {
        val running = group.groupId in runningGroupIds && isScheduleActiveNow(group)
        if (!running || !group.exitable) {
            b.tvRunningStatus.visibility = View.GONE
            b.tvPausePending.visibility = View.GONE
            b.btnPauseAutofocus.visibility = View.GONE
            return
        }

        b.tvRunningStatus.visibility = View.VISIBLE
        b.btnPauseAutofocus.visibility = View.VISIBLE

        val cooldownEnd = cooldownEndFor(group.groupId)
        val pending = System.currentTimeMillis() < cooldownEnd
        if (pending) {
            b.btnPauseAutofocus.isEnabled = false
            b.btnPauseAutofocus.text = getString(R.string.exit_cooldown_pending_button)
            b.btnPauseAutofocus.setOnClickListener(null)
            val timeFmt = android.text.format.DateFormat.getTimeFormat(requireContext())
            b.tvPausePending.text =
                getString(R.string.exit_cooldown_eta, timeFmt.format(java.util.Date(cooldownEnd)))
            b.tvPausePending.visibility = View.VISIBLE
        } else {
            if (cooldownEnd != 0L) {
                cooldownPrefs().edit().remove(cooldownKey(group.groupId)).apply()
            }
            b.tvPausePending.visibility = View.GONE
            b.btnPauseAutofocus.isEnabled = true
            b.btnPauseAutofocus.text = getString(R.string.stop_auto_focus)
            b.btnPauseAutofocus.setOnClickListener {
                if (System.currentTimeMillis() < cooldownEndFor(group.groupId)) {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.exit_cooldown_already_running),
                        Toast.LENGTH_SHORT
                    ).show()
                    adapter.notifyDataSetChanged()
                    return@setOnClickListener
                }
                showExitAutoFocusDialog(group)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
