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
import kotlinx.coroutines.launch
import neth.iecal.curbox.R
import neth.iecal.curbox.data.models.AntiModificationsConfig
import neth.iecal.curbox.data.models.AntiModificationsGroup
import neth.iecal.curbox.databinding.FragmentAntiModificationsBinding
import neth.iecal.curbox.ui.activity.FragmentActivity
import neth.iecal.curbox.utils.DataStoreManager

class AntiModificationsFragment : Fragment() {

    companion object {
        const val FRAGMENT_ID = "anti_modifications_fragment"
    }

    private var _binding: FragmentAntiModificationsBinding? = null
    private val binding get() = _binding!!

    private val dataStoreManager by lazy { DataStoreManager(requireContext().applicationContext) }

    private var lastConfig: AntiModificationsConfig = AntiModificationsConfig()
    private val tickHandler = Handler(Looper.getMainLooper())
    private var tickRunnable: Runnable? = null

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

        binding.fabNewGroup.setOnClickListener {
            startActivity(Intent(requireContext(), FragmentActivity::class.java).apply {
                putExtra("fragment", CreateAntiModificationsGroupFragment.FRAGMENT_ID)
            })
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                dataStoreManager.settings.collect { settings ->
                    lastConfig = settings.antiModificationsConfig
                    render(lastConfig)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val r = object : Runnable {
            override fun run() {
                if (_binding != null) render(lastConfig)
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

    private fun render(config: AntiModificationsConfig) {
        binding.groupsContainer.removeAllViews()
        if (config.groups.isEmpty()) {
            binding.emptyState.visibility = View.VISIBLE
            return
        }
        binding.emptyState.visibility = View.GONE
        for (group in config.groups) {
            binding.groupsContainer.addView(buildGroupCard(group))
        }
    }

    private fun buildGroupCard(group: AntiModificationsGroup): View {
        val ctx = requireContext()
        val card = MaterialCardView(ctx).apply {
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = (12 * resources.displayMetrics.density).toInt()
            layoutParams = lp
            val tv = android.util.TypedValue()
            ctx.theme.resolveAttribute(
                com.google.android.material.R.attr.colorSurfaceContainer, tv, true
            )
            setCardBackgroundColor(tv.data)
            radius = 16 * resources.displayMetrics.density
            cardElevation = 0f
            strokeWidth = 0
            isClickable = true
            isFocusable = true
        }

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }

        val title = TextView(ctx).apply {
            text = group.name.ifBlank { getString(R.string.anti_modifications_new_group) }
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            val tv = android.util.TypedValue()
            ctx.theme.resolveAttribute(
                com.google.android.material.R.attr.colorOnSurface, tv, true
            )
            setTextColor(tv.data)
        }

        val mode = TextView(ctx).apply {
            text = modeSummary(group)
            textSize = 13f
            val tv = android.util.TypedValue()
            ctx.theme.resolveAttribute(
                com.google.android.material.R.attr.colorOnSurfaceVariant, tv, true
            )
            setTextColor(tv.data)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = (4 * resources.displayMetrics.density).toInt()
            layoutParams = lp
        }

        val count = TextView(ctx).apply {
            text = getString(R.string.anti_modifications_group_items_count, group.totalItemCount())
            textSize = 13f
            val tv = android.util.TypedValue()
            ctx.theme.resolveAttribute(
                com.google.android.material.R.attr.colorOnSurfaceVariant, tv, true
            )
            setTextColor(tv.data)
        }

        root.addView(title)
        root.addView(mode)
        root.addView(count)
        card.addView(root)
        card.setOnClickListener {
            startActivity(Intent(requireContext(), FragmentActivity::class.java).apply {
                putExtra("fragment", AntiModificationsGroupDetailsFragment.FRAGMENT_ID)
                putExtra(AntiModificationsGroupDetailsFragment.ARG_GROUP_ID, group.id)
            })
        }
        return card
    }

    private fun modeSummary(group: AntiModificationsGroup): String = when {
        group.isPasswordMode() -> getString(R.string.anti_modifications_group_mode_password)
        group.isTimedMode() -> {
            val formatted = DateFormat.getLongDateFormat(requireContext()).format(group.endTimeInMillis)
            getString(R.string.anti_modifications_group_mode_timed, formatted)
        }
        group.isCooldownMode() -> {
            if (group.removalRequestedAt == 0L) {
                getString(R.string.anti_modifications_group_mode_cooldown, group.cooldownMinutes)
            } else {
                val unlockAt = group.removalRequestedAt + group.cooldownMinutes * 60_000L
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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
