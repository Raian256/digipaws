package neth.iecal.curbox.ui.activity

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import neth.iecal.curbox.R
import neth.iecal.curbox.databinding.ActivityManageEssentialsBinding
import neth.iecal.curbox.ui.fragments.main.reducers.anti_modifications.AntiModificationsGate
import neth.iecal.curbox.utils.DataStoreManager
import neth.iecal.curbox.utils.getDefaultEssentialPackages

/**
 * Lets the user view the built-in essential packages (launcher, keyboard,
 * system UI, our own app) and add or remove custom ones. Defaults are shown
 * with a lock icon and cannot be deleted.
 */
class ManageEssentialsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityManageEssentialsBinding
    private val dataStoreManager by lazy { DataStoreManager(this) }
    private val adapter = EssentialsAdapter()
    @Volatile private var isLocked: Boolean = false

    private val pickAppsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != RESULT_OK) return@registerForActivityResult
            // Re-check the lock: Anti-Modifications could have been armed for the
            // essentials list between launching the picker and returning.
            if (isLocked) {
                refuseLocked()
                return@registerForActivityResult
            }
            val picked = result.data?.getStringArrayListExtra("SELECTED_APPS").orEmpty()
            // Strip defaults defensively — the picker should already exclude them,
            // but if a default were ever passed through we'd silently shadow it
            // in customs (cosmetic only, since the merge dedupes).
            val defaults = getDefaultEssentialPackages(this)
            val customs = picked.filterNot { defaults.contains(it) }.distinct()
            lifecycleScope.launch {
                dataStoreManager.updateCustomEssentialPackages(customs)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityManageEssentialsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.essentialsList.layoutManager = LinearLayoutManager(this)
        binding.essentialsList.adapter = adapter

        binding.addEssential.setOnClickListener {
            if (isLocked) {
                refuseLocked()
                return@setOnClickListener
            }
            lifecycleScope.launch {
                val customs = dataStoreManager.settings.first().customEssentialPackages
                val intent = Intent(this@ManageEssentialsActivity, SelectAppsActivity::class.java)
                intent.putStringArrayListExtra("PRE_SELECTED_APPS", ArrayList(customs))
                // The picker normally hides custom essentials so they don't pollute
                // app/focus group pickers. Here we WANT the user to see them
                // pre-selected so they can untoggle as needed.
                intent.putExtra(SelectAppsActivity.EXTRA_HIDE_CUSTOM_ESSENTIALS, false)
                pickAppsLauncher.launch(intent)
            }
        }

        // Continuously observe settings: list contents AND the Anti-Modifications
        // lock both come from here, so a single subscription keeps them in sync.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                dataStoreManager.settings.collect { settings ->
                    val nowLocked = AntiModificationsGate.isEssentialAppsListLocked(
                        settings.antiModificationsConfig
                    )
                    isLocked = nowLocked
                    binding.addEssential.alpha = if (nowLocked) 0.5f else 1f
                    val defaults = getDefaultEssentialPackages(this@ManageEssentialsActivity).toList()
                    adapter.submit(buildRows(defaults, settings.customEssentialPackages, nowLocked))
                }
            }
        }
    }

    private fun refuseLocked() {
        Snackbar.make(
            binding.essentialsList,
            R.string.anti_modifications_item_locked,
            Snackbar.LENGTH_LONG
        ).show()
    }

    private fun buildRows(defaults: List<String>, customs: List<String>, locked: Boolean): List<Row> {
        val rows = mutableListOf<Row>()
        if (locked) {
            rows += Row.LockBanner(getString(R.string.essential_apps_locked_banner))
        }
        rows += Row.Header(getString(R.string.essential_apps_section_default), getString(R.string.essential_apps_section_default_subtitle))
        defaults.sorted().forEach { rows += Row.Item(it, removable = false) }
        rows += Row.Header(getString(R.string.essential_apps_section_custom), getString(R.string.essential_apps_section_custom_subtitle))
        if (customs.isEmpty()) {
            rows += Row.Empty(getString(R.string.essential_apps_custom_empty))
        } else {
            // When locked, customs become non-removable in the UI; the delete
            // button is gone and the lock icon takes its place.
            customs.sorted().forEach { rows += Row.Item(it, removable = !locked) }
        }
        return rows
    }

    private fun deleteCustom(packageName: String) {
        if (isLocked) {
            refuseLocked()
            return
        }
        lifecycleScope.launch {
            val current = dataStoreManager.settings.first().customEssentialPackages
            dataStoreManager.updateCustomEssentialPackages(current.filterNot { it == packageName })
        }
    }

    private sealed class Row {
        data class Header(val title: String, val subtitle: String) : Row()
        data class Item(val packageName: String, val removable: Boolean) : Row()
        data class Empty(val text: String) : Row()
        data class LockBanner(val text: String) : Row()
    }

    private inner class EssentialsAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private var rows: List<Row> = emptyList()

        @SuppressLint("NotifyDataSetChanged")
        fun submit(newRows: List<Row>) {
            rows = newRows
            notifyDataSetChanged()
        }

        override fun getItemViewType(position: Int): Int = when (rows[position]) {
            is Row.Header -> TYPE_HEADER
            is Row.Item -> TYPE_ITEM
            is Row.Empty -> TYPE_EMPTY
            is Row.LockBanner -> TYPE_LOCK_BANNER
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return when (viewType) {
                TYPE_HEADER -> HeaderViewHolder(inflater.inflate(R.layout.essential_section_header, parent, false))
                TYPE_EMPTY -> EmptyViewHolder(inflater.inflate(R.layout.essential_section_header, parent, false))
                TYPE_LOCK_BANNER -> LockBannerViewHolder(inflater.inflate(R.layout.essential_lock_banner, parent, false))
                else -> ItemViewHolder(inflater.inflate(R.layout.essential_app_item, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is Row.Header -> (holder as HeaderViewHolder).bind(row)
                is Row.Item -> (holder as ItemViewHolder).bind(row)
                is Row.Empty -> (holder as EmptyViewHolder).bind(row)
                is Row.LockBanner -> (holder as LockBannerViewHolder).bind(row)
            }
        }

        override fun getItemCount(): Int = rows.size
    }

    private inner class HeaderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val title: TextView = itemView.findViewById(R.id.section_title)
        private val subtitle: TextView = itemView.findViewById(R.id.section_subtitle)
        fun bind(row: Row.Header) {
            title.text = row.title
            subtitle.text = row.subtitle
            subtitle.visibility = if (row.subtitle.isBlank()) View.GONE else View.VISIBLE
        }
    }

    private inner class EmptyViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val title: TextView = itemView.findViewById(R.id.section_title)
        private val subtitle: TextView = itemView.findViewById(R.id.section_subtitle)
        fun bind(row: Row.Empty) {
            title.visibility = View.GONE
            subtitle.text = row.text
        }
    }

    private inner class LockBannerViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val text: TextView = itemView.findViewById(R.id.banner_text)
        fun bind(row: Row.LockBanner) {
            text.text = row.text
        }
    }

    private inner class ItemViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val icon: ImageView = itemView.findViewById(R.id.app_icon)
        private val name: TextView = itemView.findViewById(R.id.app_name)
        private val subtitle: TextView = itemView.findViewById(R.id.app_subtitle)
        private val lockIcon: ImageView = itemView.findViewById(R.id.lock_icon)
        private val deleteButton: MaterialButton = itemView.findViewById(R.id.delete_button)

        fun bind(row: Row.Item) {
            val pm = itemView.context.packageManager
            name.text = try {
                pm.getApplicationLabel(pm.getApplicationInfo(row.packageName, 0)).toString()
            } catch (e: Exception) {
                row.packageName
            }
            subtitle.text = row.packageName
            try {
                icon.setImageDrawable(pm.getApplicationIcon(row.packageName))
            } catch (e: Exception) {
                icon.setImageResource(android.R.drawable.sym_def_app_icon)
            }
            if (row.removable) {
                lockIcon.visibility = View.GONE
                deleteButton.visibility = View.VISIBLE
                deleteButton.setOnClickListener {
                    MaterialAlertDialogBuilder(itemView.context)
                        .setTitle(R.string.essential_apps_remove_title)
                        .setMessage(itemView.context.getString(R.string.essential_apps_remove_message, name.text))
                        .setPositiveButton(R.string.remove) { _, _ -> deleteCustom(row.packageName) }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                }
            } else {
                lockIcon.visibility = View.VISIBLE
                deleteButton.visibility = View.GONE
                deleteButton.setOnClickListener(null)
            }
        }
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_ITEM = 1
        private const val TYPE_EMPTY = 2
        private const val TYPE_LOCK_BANNER = 3
    }
}
