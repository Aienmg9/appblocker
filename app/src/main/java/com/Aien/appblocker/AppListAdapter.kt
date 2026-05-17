package com.Aien.appblocker

import android.content.Intent
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class AppListAdapter(
    private val apps: List<AppItem>,
    private val db: AppDatabase,
    private val onAppClick: (AppItem) -> Unit,
    private val onAppLongClick: (AppItem) -> Unit
) : RecyclerView.Adapter<AppListAdapter.ViewHolder>() {

    private var filtered = mutableListOf<AppItem>()
    private var currentQuery = ""
    private var isEditingEnabled = true

    init {
        refresh()
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivIcon: ImageView = view.findViewById(R.id.ivAppIcon)
        val tvName: TextView = view.findViewById(R.id.tvAppName)
        val tvUsage: TextView = view.findViewById(R.id.tvUsageStats)
        val tvLifeTime: TextView = view.findViewById(R.id.tvLifeTimeStats)
        val checkbox: CheckBox = view.findViewById(R.id.cbBlocked)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val app = filtered[position]
        holder.tvName.text = app.appName
        holder.tvUsage.text = app.usageTime
        holder.tvLifeTime.text = app.lifeTimeTime
        holder.ivIcon.setImageDrawable(app.icon)
        
        holder.checkbox.setOnCheckedChangeListener(null)
        holder.checkbox.isChecked = app.isBlocked
        
        val isSelf = app.packageName == holder.itemView.context.packageName
        holder.checkbox.isEnabled = isEditingEnabled && !isSelf
        holder.itemView.alpha = if (isEditingEnabled) (if (isSelf) 0.8f else 1.0f) else 0.5f

        val isWhitelisted = db.dao().isInFocusWhitelist(app.packageName)
        holder.itemView.setBackgroundColor(if (isWhitelisted) Color.parseColor("#1A237E") else Color.TRANSPARENT)

        holder.checkbox.setOnCheckedChangeListener { _, isChecked ->
            app.isBlocked = isChecked
            if (isChecked) {
                val existing = db.dao().getAllBlockedApps().find { it.packageName == app.packageName }
                db.dao().insertBlockedApp(BlockedApp(app.packageName, app.appName, existing?.groupId))
            } else {
                db.dao().deleteBlockedApp(BlockedApp(app.packageName, app.appName))
            }
            holder.itemView.context.sendBroadcast(Intent("com.Aien.appblocker.REFRESH_CACHE"))
        }

        holder.itemView.setOnClickListener { onAppClick(app) }
        holder.itemView.setOnLongClickListener { onAppLongClick(app); true }
    }

    override fun getItemCount() = filtered.size

    fun setEditingEnabled(enabled: Boolean) {
        if (isEditingEnabled != enabled) {
            isEditingEnabled = enabled
            notifyDataSetChanged()
        }
    }

    private fun refresh() {
        filtered = apps.filter {
            it.appName.contains(currentQuery, ignoreCase = true) ||
                    it.packageName.contains(currentQuery, ignoreCase = true)
        }.sortedWith(
            compareByDescending<AppItem> { it.isBlocked }
            .thenByDescending { it.rawTimeToday }
            .thenBy { it.appName }
        ).toMutableList()
        notifyDataSetChanged()
    }

    fun filter(query: String) {
        currentQuery = query
        refresh()
    }
}
