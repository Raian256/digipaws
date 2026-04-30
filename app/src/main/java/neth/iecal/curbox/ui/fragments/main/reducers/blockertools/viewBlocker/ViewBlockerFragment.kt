package neth.iecal.curbox.ui.fragments.main.reducers.blockertools.viewBlocker

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import neth.iecal.curbox.R
import neth.iecal.curbox.data.models.AntiModificationsConfig
import neth.iecal.curbox.databinding.FragmentViewBlockerBinding
import neth.iecal.curbox.ui.fragments.main.reducers.anti_modifications.AntiModificationsGate
import neth.iecal.curbox.utils.DataStoreManager


class ViewBlockerFragment : Fragment() {

    private var _binding: FragmentViewBlockerBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ViewBlockerViewModel by activityViewModels()
    private val dataStoreManager by lazy { DataStoreManager(requireContext().applicationContext) }
    private var antiMods: AntiModificationsConfig = AntiModificationsConfig()
    private var isUpdatingUi = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentViewBlockerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupListeners()
        observeViewModel()
    }

    private fun setupListeners() {
        binding.switchEnableViewBlocker.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingUi) return@setOnCheckedChangeListener
            if (!isChecked && AntiModificationsGate.hasAnyLockedViewBlocker(antiMods)) {
                AntiModificationsGate.refuseWithSnackbar(
                    binding.root,
                    R.string.anti_modifications_feature_disable_blocked
                )
                isUpdatingUi = true
                binding.switchEnableViewBlocker.isChecked = true
                isUpdatingUi = false
                return@setOnCheckedChangeListener
            }
            viewModel.setIsActive(isChecked)
        }

        binding.btnAddRule.setOnClickListener {
            val ruleText = binding.editCustomRule.text?.toString()?.trim() ?: ""
            if (ruleText.isEmpty()) {
                Toast.makeText(requireContext(), "Empty Rule", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            viewModel.addCustomRule(ruleText)
            binding.editCustomRule.text?.clear()
        }

        binding.btnPickElement.setOnClickListener {
            val intent = Intent(INTENT_ACTION_SHOW_PICKER_NOTIFICATION)
            intent.setPackage(requireContext().packageName)
            requireContext().sendBroadcast(intent)
            Toast.makeText(requireContext(), "Notification shown. Switch to any app and tap the notification to start picking.", Toast.LENGTH_LONG).show()
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            dataStoreManager.settings.collectLatest { settings ->
                antiMods = settings.antiModificationsConfig
                val current = viewModel.viewBlockerConfig.value
                buildRuleToggles(current.rules)
                buildCustomRuleChips(current.customRules)
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.viewBlockerConfig.collectLatest { config ->
                isUpdatingUi = true

                if (binding.switchEnableViewBlocker.isChecked != config.isActive) {
                    binding.switchEnableViewBlocker.isChecked = config.isActive
                }

                buildRuleToggles(config.rules)
                buildCustomRuleChips(config.customRules)

                isUpdatingUi = false
            }
        }
    }

    private fun getAppNameIfInstalled(context: Context, packageName: String): String? {
        val pm = context.getPackageManager()
        try {
            val ai = pm.getApplicationInfo(packageName, 0)
            return pm.getApplicationLabel(ai).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            return null // not installed
        }
    }

    private fun createRuleCard(title: String ,isChecked: Boolean, onClick: (() -> Unit)? = null, onSwitchChange: (Boolean) -> Unit): View {
        val card = MaterialCardView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (8 * resources.displayMetrics.density).toInt()
            }
            
            val typedValue = android.util.TypedValue()
            context.theme.resolveAttribute(com.google.android.material.R.attr.colorSurfaceContainerHigh, typedValue, true)
            setCardBackgroundColor(typedValue.data)
            radius = 16 * resources.displayMetrics.density
            cardElevation = 0f
            strokeWidth = 0
            isClickable = true
            isFocusable = true
            
            val container = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(
                    (16 * resources.displayMetrics.density).toInt(),
                    (16 * resources.displayMetrics.density).toInt(),
                    (16 * resources.displayMetrics.density).toInt(),
                    (16 * resources.displayMetrics.density).toInt()
                )
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            
            val textContainer = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = (16 * resources.displayMetrics.density).toInt()
                }
            }
            
            val titleView = TextView(requireContext()).apply {
                text = title
                textSize = 15f
                val colorTyped = android.util.TypedValue()
                context.theme.resolveAttribute(com.google.android.material.R.attr.colorOnSurface, colorTyped, true)
                setTextColor(colorTyped.data)
                setTypeface(null, android.graphics.Typeface.BOLD)
            }
            
            textContainer.addView(titleView)

            val toggle = MaterialSwitch(requireContext()).apply {
                this.isChecked = isChecked
                setOnCheckedChangeListener { _, state ->
                    if (!isUpdatingUi) onSwitchChange(state)
                }
            }
            
            container.addView(textContainer)
            container.addView(toggle)
            addView(container)

            if (onClick != null) {
                setOnClickListener { onClick() }
            }
        }
        return card
    }

    private fun buildRuleToggles(rules: List<neth.iecal.curbox.data.models.ViewBlockerRule>) {
        val container = binding.rulesContainer
        container.removeAllViews()

        var currentPackage = ""
        for (rule in rules) {
            if (rule.packageName != currentPackage) {
                val appName = getAppNameIfInstalled(requireContext(),rule.packageName) ?: continue
                currentPackage = rule.packageName
                val header = TextView(requireContext()).apply {
                    text = appName
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall)
                    val typedValue = android.util.TypedValue()
                    context.theme.resolveAttribute(com.google.android.material.R.attr.colorOnSurface, typedValue, true)
                    setTextColor(context.getColor(typedValue.resourceId))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin = (16 * resources.displayMetrics.density).toInt()
                        bottomMargin = (8 * resources.displayMetrics.density).toInt()
                        marginStart = (4 * resources.displayMetrics.density).toInt()
                    }
                }
                container.addView(header)
            }

            val locked = AntiModificationsGate.isViewBlockerLocked(antiMods, rule.id)
            val card = createRuleCard(rule.label, rule.isEnabled, null) { isChecked ->
                if (locked) {
                    AntiModificationsGate.refuseWithSnackbar(binding.root)
                    // Force a rebuild so the switch snaps back to the real value.
                    buildRuleToggles(viewModel.viewBlockerConfig.value.rules)
                } else {
                    viewModel.setRuleEnabled(rule.id, isChecked)
                }
            }
            container.addView(card)
        }
    }

    private fun buildCustomRuleChips(customRules: List<String>) {
        val container = binding.chipGroupCustomRules
        container.removeAllViews()

        for (rule in customRules) {
            val isEnabled = !rule.startsWith("!DISABLED!")
            val cleanRule = rule.removePrefix("!DISABLED!")
            val label = extractLabel(cleanRule)

            val customLocked = AntiModificationsGate.isViewBlockerLocked(antiMods, rule)
            val onClickAction = {
                if (customLocked) {
                    AntiModificationsGate.refuseWithSnackbar(binding.root)
                } else {
                    val editText = android.widget.EditText(requireContext())
                    editText.setText(cleanRule)
                    com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                        .setTitle("Edit Rule")
                        .setView(editText)
                        .setNeutralButton("Delete") { _, _ ->
                            viewModel.removeCustomRule(rule)
                        }
                        .setPositiveButton("Save") { _, _ ->
                            val newStr = editText.text.toString()
                            if (newStr.isNotBlank()) {
                                viewModel.editCustomRule(rule, if (isEnabled) newStr else "!DISABLED!$newStr")
                            } else {
                                viewModel.removeCustomRule(rule)
                            }
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
                Unit
            }

            val card = createRuleCard(label, isEnabled, onClickAction) { checked ->
                if (customLocked) {
                    AntiModificationsGate.refuseWithSnackbar(binding.root)
                    buildCustomRuleChips(viewModel.viewBlockerConfig.value.customRules)
                } else {
                    viewModel.setCustomRuleEnabled(rule, checked)
                }
            }
            container.addView(card)
        }
    }

    /**
     * Extract a human-readable label from a custom rule string.
     *
     * Handles both rule formats:
     * - Legacy `##`-delimited: looks for a `comment=` segment.
     * - Token-based: looks for a `comment:` token (quoted or unquoted).
     *
     * Falls back to the package name short form + first key:value pair when
     * neither marker is present.
     */
    private fun extractLabel(ruleString: String): String {
        // Legacy ##-delimited format: "com.pkg##viewId=foo##comment=My Rule"
        if (ruleString.contains("##")) {
            val parts = ruleString.split("##")
            for (part in parts) {
                if (part.startsWith("comment=")) {
                    return part.removePrefix("comment=")
                }
            }
            // Fallback: package short name + first selector segment
            val pkg = parts.getOrNull(0)?.substringAfterLast(".") ?: "rule"
            val selector = parts.getOrNull(1) ?: ""
            return "$pkg: $selector"
        }

        // Token-based format: "pkg:com.example id:foo comment:My Rule"
        val commentMatch = Regex("""comment:(?:"([^"]*)"|(\S+))""").find(ruleString)
        if (commentMatch != null) {
            return commentMatch.groupValues[1].ifEmpty { commentMatch.groupValues[2] }
        }

        // Fallback: extract pkg: short name + first non-pkg token
        val pkgMatch = Regex("""pkg:(\S+)""").find(ruleString)
        val pkgShort = pkgMatch?.groupValues?.get(1)?.substringAfterLast(".") ?: "rule"
        val firstToken = ruleString.trim().split(" ").firstOrNull { !it.startsWith("pkg:") } ?: ""
        return if (firstToken.isEmpty()) pkgShort else "$pkgShort: $firstToken"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val FRAGMENT_ID = "view_blocker"
        const val INTENT_ACTION_SHOW_PICKER_NOTIFICATION = "neth.iecal.curbox.show.pickernoti"
    }
}
