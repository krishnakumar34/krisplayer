package com.iptv.player

import java.util.UUID

data class Playlist(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val source: String, 
    val type: PlaylistType
)

enum class PlaylistType { REMOTE, LOCAL }

data class Channel(
    val id: String,
    val number: Int,
    val name: String,
    val group: String,
    val url: String,
    val userAgent: String? = null,
    val logo: String? = null,
    val drmLicense: String? = null,
    var isFavorite: Boolean = false,
    val programs: List<Program> = generateDummyPrograms()
)

data class Program(val title: String, val time: String)

fun generateDummyPrograms(): List<Program> {
    val shows = listOf("News", "Sports", "Movie", "Kids", "Doc", "Weather")
    return (0..10).map { 
        Program(shows.random(), "${8+it}:00") 
    }
}
