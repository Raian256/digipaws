package neth.iecal.curbox.ui.fragments.main.reducers.analytics

import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import neth.iecal.curbox.data.db.AccessibilityDisableLogEntity
import neth.iecal.curbox.databinding.ItemAccessibilityDisableLogBinding

class AccessibilityDisablesLogAdapter(
    private val onDelete: (Int) -> Unit
) : ListAdapter<AccessibilityDisableLogEntity, AccessibilityDisablesLogAdapter.ViewHolder>(DiffCallback) {

    class ViewHolder(val binding: ItemAccessibilityDisableLogBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemAccessibilityDisableLogBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        holder.binding.run {
            serviceName.text = friendlyServiceName(item.serviceName)
            timeText.text = DateUtils.getRelativeTimeSpanString(
                item.timestamp,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS
            )
            btnDelete.setOnClickListener { onDelete(item.id) }
        }
    }

    private fun friendlyServiceName(raw: String): String = when (raw) {
        "AppBlockerService" -> "App Blocker service turned off"
        "UsageTrackingService" -> "Usage Tracking service turned off"
        else -> "$raw turned off"
    }

    object DiffCallback : DiffUtil.ItemCallback<AccessibilityDisableLogEntity>() {
        override fun areItemsTheSame(
            oldItem: AccessibilityDisableLogEntity,
            newItem: AccessibilityDisableLogEntity
        ): Boolean = oldItem.id == newItem.id

        override fun areContentsTheSame(
            oldItem: AccessibilityDisableLogEntity,
            newItem: AccessibilityDisableLogEntity
        ): Boolean = oldItem == newItem
    }
}
