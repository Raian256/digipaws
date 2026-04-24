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
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.launch
import neth.iecal.curbox.R
import neth.iecal.curbox.data.models.AntiModificationsConfig
import neth.iecal.curbox.data.models.Settings
import neth.iecal.curbox.databinding.FragmentAntiModificationsBinding
import neth.iecal.curbox.ui.activity.FragmentActivity
import neth.iecal.curbox.ui.fragments.main.reducers.anti_uninstall.ChooseAntiUninstallModeFragment
import neth.iecal.curbox.ui.fragments.main.reducers.anti_uninstall.LockSetupTarget
import neth.iecal.curbox.utils.DataStoreManager

class AntiModificationsFragment : Fragment() {

    companion object {
        const val FRAGMENT_ID = "anti_modifications_fragment"
    }

    private var _binding: FragmentAntiModificationsBinding? = null
    private val binding get() = _binding!!

    private val dataStoreManager by lazy { DataStoreManager(requireContext().applicationContext) }

    private val tickHandler = Handler(Looper.getMainLooper())
    private var tickRunnable: Runnable? = null
    private var lastSettings: Settings = Settings()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAntiModificationsBinding.inflate(inflater, container, false)
        binding.toolbar.setNavigationOnClickListener { requireActivity().finish() }
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnEnable.setOnClickListener {
            startActivity(Intent(requireContext(), FragmentActivity::class.java).apply {
                putExtra("fragment", ChooseAntiUninstallModeFragment.FRAGMENT_ID)
                putExtra(LockSetupTarget.ARG_TARGET, LockSetupTarget.ANTI_MODIFICATIONS.key)
            })
        }

        binding.btnRemove.setOnClickListener {
            AntiModificationsUnlock.attempt(this, lastSettings.antiModificationsConfig) {
                AntiModificationsConfig()
            }
        }

        binding.btnCancelRemoval.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val current = lastSettings.antiModificationsConfig
                dataStoreManager.updateAntiModificationsConfig(current.copy(removalRequestedAt = 0L))
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                dataStoreManager.settings.collect { settings ->
                    lastSettings = settings
                    render(settings)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        startTicker()
    }

    override fun onStop() {
        stopTicker()
        super.onStop()
    }

    private fun startTicker() {
        stopTicker()
        val r = object : Runnable {
            override fun run() {
                if (_binding != null) render(lastSettings)
                tickHandler.postDelayed(this, 1000L)
            }
        }
        tickRunnable = r
        tickHandler.postDelayed(r, 1000L)
    }

    private fun stopTicker() {
        tickRunnable?.let { tickHandler.removeCallbacks(it) }
        tickRunnable = null
    }

    private fun render(settings: Settings) {
        val config = settings.antiModificationsConfig
        if (!config.isEnabled) {
            binding.disabledContainer.visibility = View.VISIBLE
            binding.enabledContainer.visibility = View.GONE
            return
        }

        binding.disabledContainer.visibility = View.GONE
        binding.enabledContainer.visibility = View.VISIBLE
        binding.statusDetails.text = statusText(config)
        binding.btnCancelRemoval.visibility =
            if (config.isCooldownMode() && config.removalRequestedAt != 0L) View.VISIBLE else View.GONE

        renderAppPauseSection(settings, config)
        renderAutoFocusSection(settings, config)
        renderKeywordsSection(settings, config)
        renderViewBlockersSection(settings, config)
    }

    private fun statusText(config: AntiModificationsConfig): String {
        val ctx = requireContext()
        return when {
            config.isPasswordMode() -> getString(R.string.anti_modifications_status_password)
            config.isTimedMode() -> {
                val formatted = DateFormat.getLongDateFormat(ctx).format(config.endTimeInMillis)
                getString(R.string.anti_modifications_status_timed, formatted)
            }
            config.isCooldownMode() -> {
                if (config.removalRequestedAt == 0L) {
                    getString(R.string.anti_modifications_status_cooldown, config.cooldownMinutes)
                } else {
                    val unlockAt = config.removalRequestedAt + config.cooldownMinutes * 60_000L
                    val remaining = unlockAt - System.currentTimeMillis()
                    if (remaining <= 0L) {
                        getString(R.string.anti_modifications_cooldown_ready_to_unlock)
                    } else {
                        getString(
                            R.string.anti_modifications_status_cooldown_waiting,
                            AntiModificationsUnlock.formatRemaining(remaining)
                        )
                    }
                }
            }
            else -> ""
        }
    }

    private fun renderAppPauseSection(settings: Settings, config: AntiModificationsConfig) {
        val section = binding.sectionAppPause
        section.removeAllViews()

        addLockAllRow(
            section,
            checked = config.lockAllAppPauseSchedules,
            onRequestLock = { setLocked ->
                lockAll(
                    setLocked,
                    isSetLocked = { it.lockAllAppPauseSchedules },
                    updateLocked = { cfg, locked -> cfg.copy(lockAllAppPauseSchedules = locked) }
                )
            }
        )

        val items = settings.blockedAppGroups
        if (items.isEmpty()) {
            addEmptyRow(section)
            return
        }
        for (group in items) {
            val locked = group.id in config.lockedAppPauseScheduleIds
            addItemRow(
                section,
                title = group.name,
                checked = locked || config.lockAllAppPauseSchedules,
                enabled = !config.lockAllAppPauseSchedules,
                onRequestLock = { setLocked ->
                    toggleIdLock(
                        id = group.id,
                        setLocked = setLocked,
                        getSet = { it.lockedAppPauseScheduleIds },
                        setSet = { cfg, ids -> cfg.copy(lockedAppPauseScheduleIds = ids) }
                    )
                }
            )
        }
    }

    private fun renderAutoFocusSection(settings: Settings, config: AntiModificationsConfig) {
        val section = binding.sectionAutofocus
        section.removeAllViews()

        addLockAllRow(
            section,
            checked = config.lockAllAutoFocusSchedules,
            onRequestLock = { setLocked ->
                lockAll(
                    setLocked,
                    isSetLocked = { it.lockAllAutoFocusSchedules },
                    updateLocked = { cfg, locked -> cfg.copy(lockAllAutoFocusSchedules = locked) }
                )
            }
        )

        val items = settings.autoFocusGroups
        if (items.isEmpty()) {
            addEmptyRow(section)
            return
        }
        for (group in items) {
            val locked = group.groupId in config.lockedAutoFocusScheduleIds
            addItemRow(
                section,
                title = group.groupName,
                checked = locked || config.lockAllAutoFocusSchedules,
                enabled = !config.lockAllAutoFocusSchedules,
                onRequestLock = { setLocked ->
                    toggleIdLock(
                        id = group.groupId,
                        setLocked = setLocked,
                        getSet = { it.lockedAutoFocusScheduleIds },
                        setSet = { cfg, ids -> cfg.copy(lockedAutoFocusScheduleIds = ids) }
                    )
                }
            )
        }
    }

    private fun renderKeywordsSection(settings: Settings, config: AntiModificationsConfig) {
        val section = binding.sectionKeywords
        section.removeAllViews()

        addLockAllRow(
            section,
            checked = config.lockAllKeywords,
            onRequestLock = { setLocked ->
                lockAll(
                    setLocked,
                    isSetLocked = { it.lockAllKeywords },
                    updateLocked = { cfg, locked -> cfg.copy(lockAllKeywords = locked) }
                )
            }
        )

        val keywords = settings.keywordBlockerConfig.blockedKeywords
        if (keywords.isEmpty()) {
            addEmptyRow(section)
            return
        }
        for (keyword in keywords) {
            val locked = keyword in config.lockedKeywords
            addItemRow(
                section,
                title = keyword,
                checked = locked || config.lockAllKeywords,
                enabled = !config.lockAllKeywords,
                onRequestLock = { setLocked ->
                    toggleIdLock(
                        id = keyword,
                        setLocked = setLocked,
                        getSet = { it.lockedKeywords },
                        setSet = { cfg, ids -> cfg.copy(lockedKeywords = ids) }
                    )
                }
            )
        }
    }

    private fun renderViewBlockersSection(settings: Settings, config: AntiModificationsConfig) {
        val section = binding.sectionViewBlockers
        section.removeAllViews()

        addLockAllRow(
            section,
            checked = config.lockAllViewBlockers,
            onRequestLock = { setLocked ->
                lockAll(
                    setLocked,
                    isSetLocked = { it.lockAllViewBlockers },
                    updateLocked = { cfg, locked -> cfg.copy(lockAllViewBlockers = locked) }
                )
            }
        )

        val rules = settings.viewBlockerConfig.rules
        if (rules.isEmpty()) {
            addEmptyRow(section)
            return
        }
        for (rule in rules) {
            val locked = rule.id in config.lockedViewBlockerIds
            addItemRow(
                section,
                title = rule.label,
                checked = locked || config.lockAllViewBlockers,
                enabled = !config.lockAllViewBlockers,
                onRequestLock = { setLocked ->
                    toggleIdLock(
                        id = rule.id,
                        setLocked = setLocked,
                        getSet = { it.lockedViewBlockerIds },
                        setSet = { cfg, ids -> cfg.copy(lockedViewBlockerIds = ids) }
                    )
                }
            )
        }
    }

    private fun lockAll(
        setLocked: Boolean,
        isSetLocked: (AntiModificationsConfig) -> Boolean,
        updateLocked: (AntiModificationsConfig, Boolean) -> AntiModificationsConfig
    ) {
        val cfg = lastSettings.antiModificationsConfig
        if (setLocked) {
            // Tightening is free — apply immediately.
            viewLifecycleOwner.lifecycleScope.launch {
                dataStoreManager.updateAntiModificationsConfig(updateLocked(cfg, true))
            }
        } else {
            // Loosening goes through the unlock gate.
            AntiModificationsUnlock.attempt(this, cfg) { current ->
                if (isSetLocked(current)) updateLocked(current, false) else current
            }
        }
    }

    private fun toggleIdLock(
        id: String,
        setLocked: Boolean,
        getSet: (AntiModificationsConfig) -> Set<String>,
        setSet: (AntiModificationsConfig, Set<String>) -> AntiModificationsConfig
    ) {
        val cfg = lastSettings.antiModificationsConfig
        if (setLocked) {
            viewLifecycleOwner.lifecycleScope.launch {
                val newSet = getSet(cfg) + id
                dataStoreManager.updateAntiModificationsConfig(setSet(cfg, newSet))
            }
        } else {
            AntiModificationsUnlock.attempt(this, cfg) { current ->
                setSet(current, getSet(current) - id)
            }
        }
    }

    private fun addLockAllRow(
        parent: LinearLayout,
        checked: Boolean,
        onRequestLock: (Boolean) -> Unit
    ) {
        val row = buildRow(
            title = getString(R.string.anti_modifications_lock_all),
            checked = checked,
            enabled = true,
            bold = true,
            onRequestLock = onRequestLock
        )
        parent.addView(row)
    }

    private fun addItemRow(
        parent: LinearLayout,
        title: String,
        checked: Boolean,
        enabled: Boolean,
        onRequestLock: (Boolean) -> Unit
    ) {
        parent.addView(buildRow(title, checked, enabled, bold = false, onRequestLock = onRequestLock))
    }

    private fun addEmptyRow(parent: LinearLayout) {
        val tv = TextView(requireContext()).apply {
            text = getString(R.string.anti_modifications_section_empty)
            setTextColor(
                requireContext().getColor(android.R.color.darker_gray)
            )
            textSize = 13f
            val pad = (12 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        parent.addView(tv)
    }

    private fun buildRow(
        title: String,
        checked: Boolean,
        enabled: Boolean,
        bold: Boolean,
        onRequestLock: (Boolean) -> Unit
    ): View {
        val card = MaterialCardView(requireContext()).apply {
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = (8 * resources.displayMetrics.density).toInt()
            layoutParams = lp

            val typedValue = android.util.TypedValue()
            context.theme.resolveAttribute(
                com.google.android.material.R.attr.colorSurfaceContainerHigh, typedValue, true
            )
            setCardBackgroundColor(typedValue.data)
            radius = 16 * resources.displayMetrics.density
            cardElevation = 0f
            strokeWidth = 0
        }

        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            gravity = android.view.Gravity.CENTER_VERTICAL
        }

        val tv = TextView(requireContext()).apply {
            text = title
            textSize = if (bold) 15f else 14f
            val typedValue = android.util.TypedValue()
            context.theme.resolveAttribute(
                com.google.android.material.R.attr.colorOnSurface, typedValue, true
            )
            setTextColor(typedValue.data)
            if (bold) setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ).apply {
                marginEnd = (16 * resources.displayMetrics.density).toInt()
            }
        }

        val sw = MaterialSwitch(requireContext()).apply {
            isChecked = checked
            isEnabled = enabled
        }
        sw.setOnCheckedChangeListener { view, requested ->
            // Revert the visual to the confirmed state; the DataStore flow will
            // repaint once the request (if any) lands. This keeps the switch in
            // sync when the unlock prompt is cancelled or rejected.
            view.setOnCheckedChangeListener(null)
            view.isChecked = checked
            onRequestLock(requested)
        }

        container.addView(tv)
        container.addView(sw)
        card.addView(container)
        return card
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
