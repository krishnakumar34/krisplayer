package com.iptv.player

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.KeyEvent
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

// --- GROUP ADAPTER ---
class GroupAdapter(
    private val groups: List<String>, 
    private val onSelect: (String)->Unit,
    private val onFocusRight: ()->Unit 
) : RecyclerView.Adapter<GroupAdapter.VH>() {
    
    var selectedPos = 0
    
    fun select(index: Int) {
        if (index in groups.indices && index != selectedPos) {
            val old = selectedPos
            selectedPos = index
            notifyItemChanged(old)
            notifyItemChanged(selectedPos)
            onSelect(groups[selectedPos])
        }
    }

    override fun onCreateViewHolder(p: ViewGroup, t: Int): VH {
        val tv = TextView(p.context)
        tv.setTextColor(Color.LTGRAY)
        tv.textSize = 16f
        tv.setPadding(32, 24, 32, 24)
        tv.isFocusable = true
        tv.isClickable = true
        tv.setBackgroundResource(R.drawable.selector_item)
        tv.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        return VH(tv)
    }

    override fun onBindViewHolder(h: VH, pos: Int) {
        val tv = h.itemView as TextView
        tv.text = groups[pos]
        
        if (selectedPos == pos) {
            tv.setBackgroundColor(Color.parseColor("#00BCD4")) 
            tv.setTextColor(Color.WHITE)
        } else {
            tv.setBackgroundColor(Color.TRANSPARENT)
            tv.setTextColor(Color.LTGRAY)
        }
        
        val performSelect = {
            if (selectedPos != pos) {
                val oldPos = selectedPos
                selectedPos = pos
                notifyItemChanged(oldPos) 
                notifyItemChanged(selectedPos) 
                onSelect(groups[pos]) 
            }
        }
        
        tv.setOnClickListener { performSelect() }
        tv.setOnFocusChangeListener { _, hasFocus -> if(hasFocus) performSelect() }
        tv.setOnKeyListener { _, k, e -> 
            if(e.action == KeyEvent.ACTION_DOWN && k == KeyEvent.KEYCODE_DPAD_RIGHT) { onFocusRight(); true } else false
        }
    }
    
    override fun getItemCount() = groups.size
    class VH(v: View) : RecyclerView.ViewHolder(v)
}

// --- CHANNEL ADAPTER ---
class ChannelAdapter(
    private var list: List<Channel>, 
    private val onPlay: (Channel)->Unit, 
    private val onFav: (Channel)->Unit,
    private val onFocusLeft: ()->Unit 
) : RecyclerView.Adapter<ChannelAdapter.VH>() {
    
    fun update(n: List<Channel>, rv: RecyclerView?) { 
        list = n
        notifyDataSetChanged()
        rv?.scrollToPosition(0)
    }

    fun getItems(): List<Channel> = list
    
    override fun onCreateViewHolder(p: ViewGroup, t: Int): VH {
        val v = LayoutInflater.from(p.context).inflate(R.layout.item_channel, p, false)
        return VH(v)
    }
    
    override fun onBindViewHolder(h: VH, pos: Int) {
        val c = list[pos]
        h.num.text = c.number.toString()
        h.name.text = c.name
        h.fav.visibility = if (c.isFavorite) View.VISIBLE else View.GONE
        
        h.itemView.setOnClickListener { onPlay(c) }
        h.itemView.setOnLongClickListener { onFav(c); true }
        h.itemView.setOnKeyListener { _, k, e ->
            if(e.action == KeyEvent.ACTION_DOWN && k == KeyEvent.KEYCODE_DPAD_LEFT) { onFocusLeft(); true } else false
        }
    }
    
    override fun getItemCount() = list.size
    
    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val num: TextView = v.findViewById(R.id.tvNum)
        val name: TextView = v.findViewById(R.id.tvName)
        val fav: ImageView = v.findViewById(R.id.imgFav)
    }
}
