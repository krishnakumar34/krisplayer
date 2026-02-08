package com.iptv.player

import android.graphics.Color
import android.graphics.Typeface
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

// --- GROUP ADAPTER ---
class GroupAdapter(
    private val groups: List<String>,
    private val onSelect: (String) -> Unit,
    private val onFocusRight: () -> Unit // Callback for navigation
) : RecyclerView.Adapter<GroupAdapter.VH>() {

    private var selectedPos = 0

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        // Using built-in android layout text view
        val tvName: TextView = v.findViewById(android.R.id.text1)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(android.R.layout.simple_list_item_1, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val group = groups[position]
        holder.tvName.text = group
        
        // Highlight logic
        val isSelected = (selectedPos == position)
        holder.tvName.setTextColor(if (isSelected) Color.CYAN else Color.LTGRAY)
        holder.tvName.setTypeface(null, if (isSelected) Typeface.BOLD else Typeface.NORMAL)
        holder.itemView.setBackgroundColor(if (isSelected) Color.parseColor("#33FFFFFF") else Color.TRANSPARENT)

        // Click Handling
        holder.itemView.setOnClickListener {
            updateSelection(holder.bindingAdapterPosition)
            onSelect(group)
        }
        
        // Focus Handling (for Remote)
        holder.itemView.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                updateSelection(holder.bindingAdapterPosition)
                onSelect(group)
            }
        }

        // Right Key Navigation
        holder.itemView.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                onFocusRight()
                true
            } else false
        }
    }

    private fun updateSelection(pos: Int) {
        val prev = selectedPos
        selectedPos = pos
        notifyItemChanged(prev)
        notifyItemChanged(selectedPos)
    }

    override fun getItemCount() = groups.size
}

// --- CHANNEL ADAPTER (FIXED: No EPG to prevent crashes) ---
class ChannelAdapter(
    private var channels: List<Channel>,
    private val onPlay: (Channel) -> Unit,
    private val onFav: (Channel) -> Unit,
    private val onFocusLeft: () -> Unit // Callback for navigation
) : RecyclerView.Adapter<ChannelAdapter.VH>() {

    private var selectedPos = -1

    fun update(newChannels: List<Channel>, rv: RecyclerView?) {
        channels = newChannels
        selectedPos = -1
        notifyDataSetChanged()
        rv?.scrollToPosition(0)
    }

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val root: LinearLayout = v.findViewById(R.id.btnChannelHeader)
        val tvNum: TextView = v.findViewById(R.id.tvNum)
        val tvName: TextView = v.findViewById(R.id.tvName)
        val imgFav: ImageView = v.findViewById(R.id.imgFav)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_channel, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val c = channels[position]
        
        holder.tvNum.text = c.number.toString()
        holder.tvName.text = c.name
        holder.imgFav.visibility = if (c.isFavorite) View.VISIBLE else View.GONE
        
        // Highlight logic
        val isSelected = (selectedPos == position)
        holder.tvName.setTextColor(if (isSelected) Color.CYAN else Color.WHITE)
        holder.root.setBackgroundResource(if (isSelected) R.drawable.selector_item else 0)
        
        // Auto-focus if selected
        if (isSelected) holder.root.requestFocus()

        // Play on Click
        holder.root.setOnClickListener { onPlay(c) }
        
        // Favorite on Long Press
        holder.root.setOnLongClickListener {
            onFav(c)
            true
        }

        // Update selection on focus
        holder.root.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                selectedPos = holder.bindingAdapterPosition
            }
        }

        // Left Key Navigation
        holder.root.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                    onFocusLeft()
                    true
                } else false
            } else false
        }
    }

    override fun getItemCount() = channels.size
}
