package neth.iecal.curbox.ui.fragments.main.reducers.anti_modifications

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch
import neth.iecal.curbox.R
import neth.iecal.curbox.data.models.AntiModificationsGroup
import neth.iecal.curbox.data.models.Settings
import neth.iecal.curbox.databinding.FragmentAntiModificationsGroupDetailsBinding
import neth.iecal.curbox.ui.activity.FragmentActivity
import neth.iecal.curbox.ui.fragments.main.reducers.blockertools.viewBlocker.ViewBlockerLabels
import neth.iecal.curbox.utils.DataStoreManager

class AntiModificationsGroupDetailsFragment : Fragment() {

    companion object {
        const val FRAGMENT_ID = "anti_modifications_group_details"
        const val ARG_GROUP_ID = "group_id"
        private const val ESSENTIALS_PSEUDO_ID = "__essentials__"
        private const val GEOFENCE_FAILMODE_PSEUDO_ID = "__geofence_failmode__"
        private const val RESTRICT_GEOFENCING_PSEUDO_ID = "__restrict_geofencing__"
        private const val DELAYED_UNLOCK_WEIGHT_PSEUDO_ID = "__delayed_unlock_weight__"
    }

    private var _binding: FragmentAntiModificationsGroupDetailsBinding? = null
    private val binding get() = _binding!!

    private val dataStoreManager by lazy { DataStoreManager(requireContext().applicationContext) }

    private var groupId: String = ""
    private var lastSettings: Settings = Settings()
    private var lastGroup: AntiModificationsGroup? = null

    private val tickHandler = Handler(Looper.getMainLooper())
    private var tickRunnable: Runnable? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAntiModificationsGroupDetailsBinding.inflate(inflater, container, false)
        binding.toolbar.setNavigationOnClickListener { requireActivity().finish() }
        groupId = arguments?.getString(ARG_GROUP_ID).orEmpty()
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnAddItems.setOnClickListener {
            startActivity(Intent(requireContext(), FragmentActivity::class.java).apply {
                putExtra("fragment", CreateAntiModificationsGroupFragment.FRAGMENT_ID)
                putExtra(CreateAntiModificationsGroupFragment.ARG_GROUP_ID, groupId)
            })
        }

        binding.btnDelete.setOnClickListener {
            val g = lastGroup ?: return@setOnClickListener
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.anti_modifications_confirm_delete_title)
                .setMessage(R.string.anti_modifications_confirm_delete_message)
                .setPositiveButton(R.string.anti_uninstall_i_understand) { _, _ ->
                    AntiModificationsUnlock.attemptDelete(this, g)
                    requireActivity().finish()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        binding.btnRename.setOnClickListener {
            val g = lastGroup ?: return@setOnClickListener
            val input = TextInputEditText(requireContext()).apply {
                setText(g.name)
            }
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.anti_modifications_group_rename)
                .setView(input)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val newName = input.text?.toString()?.trim().orEmpty()
                    if (newName.isNotEmpty()) {
                        AntiModificationsUnlock.tighten(this, g.id) { it.copy(name = newName) }
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        binding.btnCancelCooldown.setOnClickListener {
            val g = lastGroup ?: return@setOnClickListener
            AntiModificationsUnlock.tighten(this, g.id) { it.copy(removalRequestedAt = 0L) }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                dataStoreManager.settings.collect { settings ->
                    lastSettings = settings
                    lastGroup = settings.antiModificationsConfig.groups
                        .firstOrNull { it.id == groupId }
                    render()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val r = object : Runnable {
            override fun run() {
                if (_binding != null) render()
                tickHandler.postDelayed(this, 1000L)
            }
        }
        tickRunnable = r
        tickHandler.postDelayed(r, 1000L)
    }

    override fun onStop() {
        tickRunnable?.let { tickHandler.removeCallbacks(it) }
        tickRunnable = null
        super.onStop()
    }

    private fun render() {
        val g = lastGroup
        if (g == null) {
            // Group removed (likely deleted via another flow) — close.
            requireActivity().finish()
            return
        }
        binding.groupName.text = g.name
        binding.groupMode.text = modeSummary(g)
        binding.btnCancelCooldown.visibility =
            if (g.isCooldownMode() && g.removalRequestedAt != 0L) View.VISIBLE else View.GONE

        renderItemSection(
            binding.sectionAppPause,
            lookup = lastSettings.blockedAppGroups.associate { it.id to it.name },
            ids = g.lockedAppPauseScheduleIds
        ) { id ->
            AntiModificationsUnlock.attempt(this, g) { it.copy(
                lockedAppPauseScheduleIds = it.lockedAppPauseScheduleIds - id
            ) }
        }

        renderItemSection(
            binding.sectionAutofocus,
            lookup = lastSettings.autoFocusGroups.associate { it.groupId to it.groupName },
            ids = g.lockedAutoFocusScheduleIds
        ) { id ->
            AntiModificationsUnlock.attempt(this, g) { it.copy(
                lockedAutoFocusScheduleIds = it.lockedAutoFocusScheduleIds - id
            ) }
        }

        renderItemSection(
            binding.sectionKeywords,
            lookup = g.lockedKeywords.associateWith { it },
            ids = g.lockedKeywords
        ) { kw ->
            AntiModificationsUnlock.attempt(this, g) { it.copy(
                lockedKeywords = it.lockedKeywords - kw
            ) }
        }

        val vbLookup = buildMap<String, String> {
            lastSettings.viewBlockerConfig.rules.forEach { put(it.id, it.label) }
            lastSettings.viewBlockerConfig.customRules.forEach {
                put(it, ViewBlockerLabels.labelFor(it))
            }
        }
        renderItemSection(
            binding.sectionViewBlockers,
            lookup = vbLookup,
            ids = g.lockedViewBlockerIds
        ) { id ->
            AntiModificationsUnlock.attempt(this, g) { it.copy(
                lockedViewBlockerIds = it.lockedViewBlockerIds - id
            ) }
        }

        // Essential apps list (singleton): show one row when locked.
        renderItemSection(
            binding.sectionEssentials,
            lookup = mapOf(ESSENTIALS_PSEUDO_ID to getString(R.string.anti_modifications_essentials_item_label)),
            ids = if (g.lockEssentialAppsList) setOf(ESSENTIALS_PSEUDO_ID) else emptySet()
        ) { _ ->
            AntiModificationsUnlock.attempt(this, g) { it.copy(lockEssentialAppsList = false) }
        }

        // Geofence location-unavailable fallback (singleton): one row when locked.
        renderItemSection(
            binding.sectionGeofenceFailmode,
            lookup = mapOf(GEOFENCE_FAILMODE_PSEUDO_ID to getString(R.string.anti_modifications_geofence_failmode_item_label)),
            ids = if (g.lockGeofenceFailMode) setOf(GEOFENCE_FAILMODE_PSEUDO_ID) else emptySet()
        ) { _ ->
            AntiModificationsUnlock.attempt(this, g) { it.copy(lockGeofenceFailMode = false) }
        }

        // Lock-geofencing setting (singleton): one row when locked.
        renderItemSection(
            binding.sectionRestrictGeofencing,
            lookup = mapOf(RESTRICT_GEOFENCING_PSEUDO_ID to getString(R.string.anti_modifications_restrict_geofencing_item_label)),
            ids = if (g.lockRestrictGeofencing) setOf(RESTRICT_GEOFENCING_PSEUDO_ID) else emptySet()
        ) { _ ->
            AntiModificationsUnlock.attempt(this, g) { it.copy(lockRestrictGeofencing = false) }
        }

        // Delayed-unlock on-screen weighting factor (singleton): one row when locked.
        renderItemSection(
            binding.sectionDelayedUnlockWeight,
            lookup = mapOf(DELAYED_UNLOCK_WEIGHT_PSEUDO_ID to getString(R.string.anti_modifications_delayed_unlock_weight_item_label)),
            ids = if (g.lockDelayedUnlockWeight) setOf(DELAYED_UNLOCK_WEIGHT_PSEUDO_ID) else emptySet()
        ) { _ ->
            AntiModificationsUnlock.attempt(this, g) { it.copy(lockDelayedUnlockWeight = false) }
        }
    }

    private fun modeSummary(g: AntiModificationsGroup): String = when {
        g.isPasswordMode() -> getString(R.string.anti_modifications_group_mode_password)
        g.isTimedMode() -> {
            val formatted = DateFormat.getLongDateFormat(requireContext()).format(g.endTimeInMillis)
            getString(R.string.anti_modifications_group_mode_timed, formatted)
        }
        g.isCooldownMode() -> {
            if (g.removalRequestedAt == 0L) {
                getString(R.string.anti_modifications_group_mode_cooldown, g.cooldownMinutes)
            } else {
                val unlockAt = g.removalRequestedAt + g.cooldownMinutes * 60_000L
                val remaining = unlockAt - System.currentTimeMillis()
                if (remaining <= 0L) {
                    getString(R.string.anti_modifications_group_mode_cooldown_ready)
                } else {
                    getString(
                        R.string.anti_modifications_group_mode_cooldown_waiting,
                        AntiModificationsUnlock.formatRemaining(remaining)
                    )
                }
            }
        }
        else -> ""
    }

    private fun renderItemSection(
        parent: LinearLayout,
        lookup: Map<String, String>,
        ids: Set<String>,
        onRequestRemove: (String) -> Unit
    ) {
        parent.removeAllViews()
        if (ids.isEmpty()) {
            val tv = TextView(requireContext()).apply {
                text = getString(R.string.anti_modifications_section_nothing_available)
                setTextColor(requireContext().getColor(android.R.color.darker_gray))
                textSize = 13f
                val pad = (8 * resources.displayMetrics.density).toInt()
                setPadding(pad, pad, pad, pad)
            }
            parent.addView(tv)
            return
        }
        for (id in ids) {
            val label = lookup[id] ?: id
            parent.addView(buildItemRow(label) {
                onRequestRemove(id)
            })
        }
    }

    private fun buildItemRow(label: String, onRequestRemove: () -> Unit): View {
        val ctx = requireContext()
        val card = MaterialCardView(ctx).apply {
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = (8 * resources.displayMetrics.density).toInt()
            layoutParams = lp
            val tv = android.util.TypedValue()
            ctx.theme.resolveAttribute(
                com.google.android.material.R.attr.colorSurfaceContainerHigh, tv, true
            )
            setCardBackgroundColor(tv.data)
            radius = 12 * resources.displayMetrics.density
            cardElevation = 0f
            strokeWidth = 0
        }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            val pad = (14 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val tv = TextView(ctx).apply {
            text = label
            textSize = 14f
            val colorTV = android.util.TypedValue()
            ctx.theme.resolveAttribute(
                com.google.android.material.R.attr.colorOnSurface, colorTV, true
            )
            setTextColor(colorTV.data)
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        }
        // Switch is checked (= protected). Turning it off attempts to remove
        // from the group, which runs the group's unlock challenge.
        val sw = MaterialSwitch(ctx).apply { isChecked = true }
        sw.setOnCheckedChangeListener { view, _ ->
            view.setOnCheckedChangeListener(null)
            view.isChecked = true // revert; flow will repaint on success.
            onRequestRemove()
        }
        row.addView(tv)
        row.addView(sw)
        card.addView(row)
        return card
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
