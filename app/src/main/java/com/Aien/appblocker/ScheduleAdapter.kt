package com.Aien.appblocker

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class ScheduleAdapter(
    private var schedules: List<BlockedSchedule>,
    private val db: AppDatabase,
    private val onDelete: (BlockedSchedule) -> Unit
) : RecyclerView.Adapter<ScheduleAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvTime: TextView = view.findViewById(R.id.tvTime)
        val tvDetails: TextView = view.findViewById(R.id.tvDetails)
        val btnDelete: ImageButton = view.findViewById(R.id.btnDelete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_schedule, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val context = holder.itemView.context
        val item = schedules[position]
        val action = if (item.actionType == 0) context.getString(R.string.schedule_action_start) else context.getString(R.string.schedule_action_stop)
        
        val target = if (item.targetGroupId != null) {
            val group = db.dao().getAllGroups().find { it.id == item.targetGroupId }
            group?.name ?: context.getString(R.string.unknown_group)
        } else context.getString(R.string.all_apps)

        val dayNames = mapOf(
            "1" to context.getString(R.string.day_sun),
            "2" to context.getString(R.string.day_mon),
            "3" to context.getString(R.string.day_tue),
            "4" to context.getString(R.string.day_wed),
            "5" to context.getString(R.string.day_thu),
            "6" to context.getString(R.string.day_fri),
            "7" to context.getString(R.string.day_sat)
        )
        val daysStr = item.daysOfWeek.split(",").mapNotNull { dayNames[it] }.joinToString(",")

        holder.tvTime.text = String.format("%02d:%02d (%s)", item.hour, item.minute, action)
        holder.tvDetails.text = context.getString(R.string.schedule_details_format, target, item.cooldownMinutes, daysStr)
        holder.btnDelete.setOnClickListener { onDelete(item) }
    }

    override fun getItemCount() = schedules.size

    fun updateData(newSchedules: List<BlockedSchedule>) {
        schedules = newSchedules
        notifyDataSetChanged()
    }
}
