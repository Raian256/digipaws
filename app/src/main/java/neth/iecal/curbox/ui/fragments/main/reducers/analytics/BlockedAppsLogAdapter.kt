package neth.iecal.curbox.ui.fragments.main.reducers.analytics

import android.content.pm.PackageManager
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import neth.iecal.curbox.R
import neth.iecal.curbox.data.db.BlockedAppLogEntity
import neth.iecal.curbox.databinding.ItemBlockedAppLogBinding

class BlockedAppsLogAdapter(
    private val onDelete: (Int) -> Unit
) : ListAdapter<BlockedAppLogEntity, BlockedAppsLogAdapter.ViewHolder>(DiffCallback) {

    class ViewHolder(val binding: ItemBlockedAppLogBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemBlockedAppLogBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        holder.binding.run {
            packageName.text = item.packageName
            groupName.text = "Auto Focus: ${item.groupName}"
            timeText.text = DateUtils.getRelativeTimeSpanString(
                item.timestamp,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS
            )

            val pm = root.context.packageManager
            try {
                val appInfo = pm.getApplicationInfo(item.packageName, 0)
                appName.text = pm.getApplicationLabel(appInfo)
                appIcon.setImageDrawable(pm.getApplicationIcon(appInfo))
                packageName.visibility = View.VISIBLE
            } catch (e: PackageManager.NameNotFoundException) {
                appName.text = item.packageName
                appIcon.setImageResource(R.drawable.ic_block_aesthetic)
                packageName.visibility = View.GONE
            }

            btnDelete.setOnClickListener { onDelete(item.id) }
        }
    }

    object DiffCallback : DiffUtil.ItemCallback<BlockedAppLogEntity>() {
        override fun areItemsTheSame(oldItem: BlockedAppLogEntity, newItem: BlockedAppLogEntity): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: BlockedAppLogEntity, newItem: BlockedAppLogEntity): Boolean {
            return oldItem == newItem
        }
    }
}
