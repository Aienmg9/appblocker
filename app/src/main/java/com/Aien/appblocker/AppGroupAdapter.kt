package com.Aien.appblocker

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class AppGroupAdapter(
    private var groups: List<AppGroup>,
    private val onDelete: (AppGroup) -> Unit,
    private val onClick: (AppGroup) -> Unit
) : RecyclerView.Adapter<AppGroupAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tvGroupName)
        val tvLimit: TextView = view.findViewById(R.id.tvGroupLimit)
        val btnDelete: ImageButton = view.findViewById(R.id.btnDeleteGroup)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_group, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val group = groups[position]
        holder.tvName.text = group.name
        holder.tvLimit.text = holder.itemView.context.getString(R.string.group_limit_format, group.limitMinutes)
        holder.btnDelete.setOnClickListener { onDelete(group) }
        holder.itemView.setOnClickListener { onClick(group) }
    }

    override fun getItemCount() = groups.size

    fun updateData(newGroups: List<AppGroup>) {
        groups = newGroups
        notifyDataSetChanged()
    }
}
