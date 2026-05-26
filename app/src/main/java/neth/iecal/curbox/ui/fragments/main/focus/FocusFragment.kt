package neth.iecal.curbox.ui.fragments.main.focus

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSnapHelper
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import neth.iecal.curbox.R
import neth.iecal.curbox.blockers.FocusModeBlocker
import neth.iecal.curbox.databinding.FragmentFocusBinding
import neth.iecal.curbox.utils.TimeTools

class FocusFragment : Fragment() {

    private var _binding: FragmentFocusBinding? = null
    private val binding get() = _binding!!
    
    private val viewModel: FocusViewModel by activityViewModels()

    private var isProgrammaticScroll = false

    private var lastBoundAutoFocusGroupId: String? = null

    private val cancelExitReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != FocusModeBlocker.INTENT_ACTION_CANCEL_EXIT_AUTO_FOCUS) return
            if (_binding == null) return
            clearAllAutoFocusCooldowns()
            lastBoundAutoFocusGroupId?.let { gid ->
                viewModel.autoFocusGroups.value.find { it.groupId == gid }?.let { bindExitAutoFocusButton(it) }
            }
        }
    }

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

    private fun bindExitAutoFocusButton(group: neth.iecal.curbox.data.models.AutoFocusGroup) {
        lastBoundAutoFocusGroupId = group.groupId
        if (!group.exitable) {
            binding.btnExitAutoFocus.visibility = View.GONE
            binding.tvExitPendingTime.visibility = View.GONE
            return
        }
        binding.btnExitAutoFocus.visibility = View.VISIBLE
        val cooldownEnd = cooldownEndFor(group.groupId)
        val pending = System.currentTimeMillis() < cooldownEnd
        if (pending) {
            binding.btnExitAutoFocus.isEnabled = false
            binding.btnExitAutoFocus.text = getString(R.string.exit_cooldown_pending_button)
            binding.btnExitAutoFocus.setOnClickListener(null)
            val timeFmt = android.text.format.DateFormat.getTimeFormat(requireContext())
            binding.tvExitPendingTime.text =
                getString(R.string.exit_cooldown_eta, timeFmt.format(java.util.Date(cooldownEnd)))
            binding.tvExitPendingTime.visibility = View.VISIBLE
            scheduleAutoFocusButtonRefresh(group, cooldownEnd - System.currentTimeMillis())
        } else {
            if (cooldownEnd != 0L) {
                cooldownPrefs().edit().remove(cooldownKey(group.groupId)).apply()
            }
            binding.tvExitPendingTime.visibility = View.GONE
            binding.btnExitAutoFocus.isEnabled = true
            binding.btnExitAutoFocus.text = getString(R.string.stop_auto_focus)
            binding.btnExitAutoFocus.setOnClickListener {
                val endAt = cooldownEndFor(group.groupId)
                if (System.currentTimeMillis() < endAt) {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.exit_cooldown_already_running),
                        Toast.LENGTH_SHORT
                    ).show()
                    bindExitAutoFocusButton(group)
                    return@setOnClickListener
                }
                showExitAutoFocusDialog(group.groupId, group.groupName, group.exitCooldownMinutes)
            }
        }
    }

    private fun scheduleAutoFocusButtonRefresh(
        group: neth.iecal.curbox.data.models.AutoFocusGroup,
        delayMs: Long
    ) {
        if (_binding == null) return
        binding.btnExitAutoFocus.removeCallbacks(autoFocusButtonRefresher)
        autoFocusButtonRefresher = Runnable {
            if (_binding == null) return@Runnable
            if (lastBoundAutoFocusGroupId == group.groupId) {
                bindExitAutoFocusButton(group)
            }
        }
        binding.btnExitAutoFocus.postDelayed(autoFocusButtonRefresher, delayMs.coerceAtLeast(0L) + 250L)
    }

    private var autoFocusButtonRefresher: Runnable = Runnable {}

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFocusBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.currentRunningFocus.collect { (groupId, endTime) ->
                        if (groupId != null) {
                            binding.activeContainer.visibility = View.VISIBLE
                            binding.setupContainer.visibility = View.GONE

                            val group = viewModel.groups.value.find { it.groupId == groupId }
                            binding.tvActiveGroup.text = group!!.groupName

                            binding.btnStop.visibility = if (group.exitable) View.VISIBLE else View.GONE
                            viewModel.startTimer(endTime)
                        } else {
                            binding.activeContainer.visibility = View.GONE
                            binding.setupContainer.visibility = View.VISIBLE
                        }
                    }
                }

                launch {
                    viewModel.allSessions.collect { sessions ->
                        val runningAuto = sessions.find { it.wasAutoFocus && it.status == 0 }
                        if (runningAuto != null) {
                            val group = viewModel.autoFocusGroups.value.find { it.groupId == runningAuto.groupId }
                            if (group != null) {
                                binding.cvActiveAutoFocus.visibility = View.VISIBLE
                                binding.textHeader.visibility = View.GONE
                                val now = java.util.Calendar.getInstance()
                                val calDay = now.get(java.util.Calendar.DAY_OF_WEEK)
                                val currentDay = if (calDay == java.util.Calendar.SUNDAY) 6 else calDay - 2
                                val currentMinutes = now.get(java.util.Calendar.HOUR_OF_DAY) * 60 + now.get(java.util.Calendar.MINUTE)
                                val intervals = group.dailyIntervals[currentDay] ?: emptyList()
                                val activeInterval = intervals.find { interval ->
                                    val start = interval.startHour * 60 + interval.startMinute
                                    val end = interval.endHour * 60 + interval.endMinute
                                    if (start <= end) currentMinutes in start until end else currentMinutes >= start || currentMinutes < end
                                }
                                if (activeInterval != null) {
                                    val startStr = String.format("%02d:%02d", activeInterval.startHour, activeInterval.startMinute)
                                    val endStr = String.format("%02d:%02d", activeInterval.endHour, activeInterval.endMinute)
                                    binding.tvAutoFocusTimeRange.text = "${group.groupName} (${startStr} - ${endStr})"
                                } else {
                                    binding.tvAutoFocusTimeRange.text = group.groupName
                                }
                                
                                bindExitAutoFocusButton(group)
                            } else {
                                binding.cvActiveAutoFocus.visibility = View.GONE
                                binding.textHeader.visibility = View.VISIBLE
                            }
                        } else {
                            binding.cvActiveAutoFocus.visibility = View.GONE
                            binding.textHeader.visibility = View.VISIBLE
                        }
                    }
                }

                launch {
                    viewModel.currentRunningTimer.collect { time ->
                        binding.tvCountdown.text = TimeTools.formatTimeInHHMM(time)
                    }
                }
            }
        }
        setupRuler()
        setupClicks()
    }
    override fun onResume() {
        super.onResume()
        viewLifecycleOwner.lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val db = neth.iecal.curbox.data.db.AppDatabase.getInstance(requireContext())
            val runningSessions = db.focusStatsDao().getRunningSessions()
            if (runningSessions.isEmpty()) {
                val intent = Intent(FocusModeBlocker.INTENT_ACTION_UNSUSPEND_ALL)
                intent.setPackage(requireContext().packageName)
                requireContext().sendBroadcast(intent)
            }
        }

        val filter = IntentFilter(FocusModeBlocker.INTENT_ACTION_CANCEL_EXIT_AUTO_FOCUS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requireContext().registerReceiver(cancelExitReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            requireContext().registerReceiver(cancelExitReceiver, filter)
        }

        lastBoundAutoFocusGroupId?.let { gid ->
            viewModel.autoFocusGroups.value.find { it.groupId == gid }?.let { bindExitAutoFocusButton(it) }
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            requireContext().unregisterReceiver(cancelExitReceiver)
        } catch (_: IllegalArgumentException) {
        }
    }

    private fun setupClicks() {
        binding.btn15m.setOnClickListener { scrollToMinute(15) }
        binding.btn30m.setOnClickListener { scrollToMinute(30) }
        binding.btn60m.setOnClickListener { scrollToMinute(60) }
        binding.btn120m.setOnClickListener { scrollToMinute(120) }

        binding.btnGoToStats.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.fragment_holder, FocusStatsFragment())
                .addToBackStack(null)
                .commit()
        }

        binding.btnStartConfig.setOnClickListener {
            FocusSetupBottomSheet().show(parentFragmentManager, FocusSetupBottomSheet.FRAGMENT_ID)
        }

        binding.btnStop.setOnClickListener {
            viewModel.forceStopFocus()
        }

    }


    private fun updateTime(pos:Int){
        viewModel.selectedMins = pos + 1
        binding.tvMinutes.text = viewModel.selectedMins.toString()
    }

    private fun setupRuler() {
        val initialSelectedMins = viewModel.selectedMins
        isProgrammaticScroll = true

        val layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
        binding.rvRuler.layoutManager = layoutManager
        binding.rvRuler.adapter = RulerAdapter(240)

        val snapHelper = LinearSnapHelper()
        snapHelper.attachToRecyclerView(binding.rvRuler)

        binding.rvRuler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (isProgrammaticScroll) return
                val centerView = snapHelper.findSnapView(layoutManager) ?: return
                val pos = layoutManager.getPosition(centerView)
                updateTime(pos)
            }
        })

        binding.rvRuler.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (_binding == null || binding.rvRuler.width == 0) return
                binding.rvRuler.viewTreeObserver.removeOnGlobalLayoutListener(this)

                val itemWidthPx = (20 * resources.displayMetrics.density).toInt()
                val padding = (binding.rvRuler.width / 2) - (itemWidthPx / 2)
                binding.rvRuler.setPadding(padding, 0, padding, 0)
                binding.rvRuler.clipToPadding = false
                
                binding.rvRuler.post {
                    scrollToMinute(initialSelectedMins, smooth = false)
                }
            }
        })
    }

    private fun scrollToMinute(minutes: Int, smooth: Boolean = true) {
        val targetPos = (minutes - 1).coerceAtLeast(0)
        isProgrammaticScroll = true

        if (smooth) {
            binding.rvRuler.smoothScrollToPosition(targetPos)
        } else {
            (binding.rvRuler.layoutManager as LinearLayoutManager)
                .scrollToPositionWithOffset(targetPos, 0)
        }

        binding.rvRuler.postDelayed({ isProgrammaticScroll = false }, 300)
        updateTime(minutes - 1)
    }




    private fun showExitAutoFocusDialog(groupId: String, groupName: String, exitCooldownMinutes: Int) {
        val ctx = requireContext()
        val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_exit_auto_focus, null)
        val etIntent = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.et_intent)
        val etMinutes = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.et_minutes)
        val tilIntent = view.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.til_intent)

        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
            .setTitle(getString(R.string.exit_auto_focus_dialog_title))
            .setView(view)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (System.currentTimeMillis() < cooldownEndFor(groupId)) {
                    Toast.makeText(ctx, getString(R.string.exit_cooldown_already_running), Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                    viewModel.autoFocusGroups.value.find { it.groupId == groupId }?.let { bindExitAutoFocusButton(it) }
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
                        packageName = "autofocus:$groupName",
                        intentText = intentText,
                        unlockedDurationMs = pauseMs
                    )
                    neth.iecal.curbox.data.db.AppDatabase.getInstance(ctx.applicationContext)
                        .intentLogDao().insert(log)
                }

                setCooldownEnd(groupId, exitCooldownMinutes)

                val broadcastIntent = Intent(FocusModeBlocker.INTENT_ACTION_EXIT_AUTO_FOCUS)
                broadcastIntent.setPackage(ctx.packageName)
                broadcastIntent.putExtra("group_id", groupId)
                broadcastIntent.putExtra("pause_minutes", minutes)
                broadcastIntent.putExtra("intent_text", intentText)
                ctx.sendBroadcast(broadcastIntent)
                dialog.dismiss()

                viewModel.autoFocusGroups.value.find { it.groupId == groupId }?.let { bindExitAutoFocusButton(it) }
            }
        }
        dialog.show()
    }

    override fun onDestroyView() {
        if (_binding != null) {
            binding.btnExitAutoFocus.removeCallbacks(autoFocusButtonRefresher)
        }
        super.onDestroyView()
        _binding = null
    }
}