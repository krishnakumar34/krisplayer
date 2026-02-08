package com.iptv.player

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.KeyEvent
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

// --- GROUP ADAPTER (Fixed Navigation Logic) ---
class GroupAdapter(
    private val groups: List<String>, 
    private val onSelect: (String)->Unit,
    private val onFocusRight: ()->Unit // Callback for navigation
) : RecyclerView.Adapter<GroupAdapter.VH>() {
    
    var selectedPos = 0
    
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
        
        // Efficient Highlighting
        if (selectedPos == pos) {
            tv.setBackgroundColor(Color.parseColor("#00BCD4")) // Cyan
            tv.setTextColor(Color.WHITE)
        } else {
            tv.setBackgroundColor(Color.TRANSPARENT)
            tv.setTextColor(Color.LTGRAY)
        }
        
        // Logic to update selection without resetting focus
        val performSelect = {
            if (selectedPos != pos) {
                val oldPos = selectedPos
                selectedPos = pos
                notifyItemChanged(oldPos) // Un-highlight old
                notifyItemChanged(selectedPos) // Highlight new
                onSelect(groups[pos]) 
            }
        }
        
        tv.setOnClickListener { performSelect() }
        tv.setOnFocusChangeListener { _, hasFocus -> if(hasFocus) performSelect() }
        
        // Navigation Handler (Calls the callback)
        tv.setOnKeyListener { _, k, e -> 
            if(e.action == KeyEvent.ACTION_DOWN && k == KeyEvent.KEYCODE_DPAD_RIGHT) {
                onFocusRight() 
                true
            } else {
                false
            }
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
    private val onFocusLeft: ()->Unit // Callback for navigation
) : RecyclerView.Adapter<ChannelAdapter.VH>() {
    
    // Updates list and resets scroll to top
    fun update(n: List<Channel>, rv: RecyclerView?) { 
        list = n
        notifyDataSetChanged()
        rv?.scrollToPosition(0)
    }
    
    override fun onCreateViewHolder(p: ViewGroup, t: Int): VH {
        val v = LayoutInflater.from(p.context).inflate(R.layout.item_channel, p, false)
        return VH(v)
    }
    
    override fun onBindViewHolder(h: VH, pos: Int) {
        val c = list[pos]
        h.num.text = c.number.toString()
        h.name.text = c.name
        
        if (c.isFavorite) h.fav.visibility = View.VISIBLE else h.fav.visibility = View.GONE
        
        h.rvProg.layoutManager = LinearLayoutManager(h.itemView.context, LinearLayoutManager.HORIZONTAL, false)
        h.rvProg.adapter = ProgramAdapter(c.programs)

        h.header.setOnClickListener { onPlay(c) }
        h.header.setOnLongClickListener { onFav(c); true }

        // Navigation Handler
        h.header.setOnKeyListener { _, k, e ->
            if(e.action == KeyEvent.ACTION_DOWN && k == KeyEvent.KEYCODE_DPAD_LEFT) {
                onFocusLeft()
                true
            } else {
                false
            }
        }
    }
    
    override fun getItemCount() = list.size
    
    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val header: View = v.findViewById(R.id.btnChannelHeader)
        val num: TextView = v.findViewById(R.id.tvNum)
        val name: TextView = v.findViewById(R.id.tvName)
        val fav: ImageView = v.findViewById(R.id.imgFav)
        val rvProg: RecyclerView = v.findViewById(R.id.rvPrograms)
    }
}

// --- PROGRAM ADAPTER ---
class ProgramAdapter(private val l: List<Program>) : RecyclerView.Adapter<ProgramAdapter.VH>() {
    override fun onCreateViewHolder(p: ViewGroup, t: Int): VH {
        val v = LayoutInflater.from(p.context).inflate(R.layout.item_program, p, false)
        return VH(v)
    }
    override fun onBindViewHolder(h: VH, i: Int) { 
        h.t.text = l[i].title
        h.tm.text = l[i].time 
    }
    override fun getItemCount() = l.size
    class VH(v: View) : RecyclerView.ViewHolder(v) { 
        val t: TextView = v.findViewById(R.id.tvTitle)
        val tm: TextView = v.findViewById(R.id.tvTime) 
    }
}
