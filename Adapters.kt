package com.iptv.player

import android.graphics.Color
import android.view.*
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

// --- GROUP ADAPTER ---
class GroupAdapter(private val groups: List<String>, private val onSelect: (String)->Unit) : RecyclerView.Adapter<GroupAdapter.VH>() {
    var selectedPos = 0
    override fun onCreateViewHolder(p: ViewGroup, t: Int) = VH(TextView(p.context).apply {
        setTextColor(Color.LTGRAY); textSize=16f; setPadding(32,24,32,24)
        isFocusable=true; isClickable=true; setBackgroundResource(R.drawable.selector_item)
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    })
    override fun onBindViewHolder(h: VH, pos: Int) {
        val tv = h.itemView as TextView; tv.text = groups[pos]
        tv.setBackgroundColor(if(selectedPos==pos) Color.parseColor("#00BCD4") else Color.TRANSPARENT)
        
        val select = { selectedPos=pos; onSelect(groups[pos]); notifyDataSetChanged() }
        tv.setOnClickListener { select() }
        tv.setOnFocusChangeListener { _, hasFocus -> if(hasFocus) select() }
        
        // KEY: Right -> Jump to Channels
        tv.setOnKeyListener { v, k, e -> 
            if(e.action==KeyEvent.ACTION_DOWN && k==KeyEvent.KEYCODE_DPAD_RIGHT) {
                (v.context as MainActivity).focusChannelList(); true
            } else false
        }
    }
    override fun getItemCount() = groups.size
    class VH(v: View) : RecyclerView.ViewHolder(v)
}

// --- CHANNEL ADAPTER ---
class ChannelAdapter(private var list: List<Channel>, private val onPlay: (Channel)->Unit, private val onFav: (Channel)->Unit) : RecyclerView.Adapter<ChannelAdapter.VH>() {
    fun update(n: List<Channel>) { list=n; notifyDataSetChanged() }
    override fun onCreateViewHolder(p: ViewGroup, t: Int) = VH(LayoutInflater.from(p.context).inflate(R.layout.item_channel, p, false))
    
    override fun onBindViewHolder(h: VH, pos: Int) {
        val c = list[pos]
        h.num.text = c.number.toString(); h.name.text = c.name
        h.fav.visibility = if(c.isFavorite) View.VISIBLE else View.GONE
        h.rvProg.layoutManager = LinearLayoutManager(h.itemView.context, LinearLayoutManager.HORIZONTAL, false)
        h.rvProg.adapter = ProgramAdapter(c.programs)

        // CLICK: Play
        h.header.setOnClickListener { onPlay(c) }
        h.header.setOnLongClickListener { onFav(c); true }

        // KEY: Left -> Jump to Groups
        h.header.setOnKeyListener { v, k, e ->
            if(e.action==KeyEvent.ACTION_DOWN && k==KeyEvent.KEYCODE_DPAD_LEFT) {
                (v.context as MainActivity).focusGroupList(); true
            } else false
        }
    }
    override fun getItemCount() = list.size
    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val header: View = v.findViewById(R.id.btnChannelHeader)
        val num: TextView = v.findViewById(R.id.tvNum); val name: TextView = v.findViewById(R.id.tvName)
        val fav: ImageView = v.findViewById(R.id.imgFav)
        val rvProg: RecyclerView = v.findViewById(R.id.rvPrograms)
    }
}

// --- PROGRAM ADAPTER ---
class ProgramAdapter(private val l: List<Program>) : RecyclerView.Adapter<ProgramAdapter.VH>() {
    override fun onCreateViewHolder(p: ViewGroup, t: Int) = VH(LayoutInflater.from(p.context).inflate(R.layout.item_program, p, false))
    override fun onBindViewHolder(h: VH, i: Int) { h.t.text=l[i].title; h.tm.text=l[i].time }
    override fun getItemCount() = l.size
    class VH(v: View) : RecyclerView.ViewHolder(v) { val t:TextView=v.findViewById(R.id.tvTitle); val tm:TextView=v.findViewById(R.id.tvTime) }
}
