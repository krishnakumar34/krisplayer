package com.iptv.player

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class Repository(context: Context) {
    private val prefs = context.getSharedPreferences("tivi_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()

    fun savePlaylist(p: Playlist) {
        val list = getPlaylists().toMutableList()
        list.add(p)
        prefs.edit().putString("playlists", gson.toJson(list)).apply()
        if (list.size == 1) setActivePlaylist(p.id)
    }

    fun getPlaylists(): List<Playlist> {
        val json = prefs.getString("playlists", "[]")
        return gson.fromJson(json, object : TypeToken<List<Playlist>>() {}.type)
    }

    fun setActivePlaylist(id: String) {
        prefs.edit().putString("active_pl", id).apply()
    }

    fun getActivePlaylist(): Playlist? {
        val id = prefs.getString("active_pl", null) ?: return null
        return getPlaylists().find { it.id == id }
    }

    fun toggleFav(id: String) {
        val set = getFavIds().toMutableSet()
        if (set.contains(id)) {
            set.remove(id)
        } else {
            set.add(id)
        }
        prefs.edit().putString("fav_ids", gson.toJson(set)).apply()
    }

    fun getFavIds(): Set<String> {
        val json = prefs.getString("fav_ids", "[]")
        return gson.fromJson(json, object : TypeToken<Set<String>>() {}.type)
    }

    fun addRecent(c: Channel) {
        val list = getRecents().toMutableList()
        list.removeAll { it.id == c.id }
        list.add(0, c)
        if (list.size > 10) list.removeAt(10)
        prefs.edit().putString("recents", gson.toJson(list)).apply()
    }

    fun getRecents(): List<Channel> {
        val json = prefs.getString("recents", "[]")
        return gson.fromJson(json, object : TypeToken<List<Channel>>() {}.type)
    }

    // --- NEW: LAST PLAYED FEATURE ---
    fun saveLastPlayed(channelId: String) {
        prefs.edit().putString("last_played_id", channelId).apply()
    }

    fun getLastPlayed(): String? {
        return prefs.getString("last_played_id", null)
    }
}
