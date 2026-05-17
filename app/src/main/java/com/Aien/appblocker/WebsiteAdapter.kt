package com.Aien.appblocker

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class WebsiteAdapter(
    private var websites: List<BlockedWebsite>,
    private val onDelete: (BlockedWebsite) -> Unit
) : RecyclerView.Adapter<WebsiteAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvUrl: TextView = view.findViewById(R.id.tvWebsiteUrl)
        val btnDelete: ImageButton = view.findViewById(R.id.btnDeleteWebsite)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_website, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = websites[position]
        holder.tvUrl.text = item.url
        holder.btnDelete.setOnClickListener { onDelete(item) }
    }

    override fun getItemCount() = websites.size

    fun updateData(newWebsites: List<BlockedWebsite>) {
        websites = newWebsites
        notifyDataSetChanged()
    }
}
