package com.iptv.player

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.InputStreamReader

object M3uParser {
    suspend fun parse(context: Context, playlist: Playlist, favIds: Set<String>): Map<String, List<Channel>> = withContext(Dispatchers.IO) {
        val channels = mutableListOf<Channel>()
        try {
            val stream = if (playlist.type == PlaylistType.REMOTE) {
                OkHttpClient().newCall(Request.Builder().url(playlist.source).build()).execute().body?.byteStream()
            } else {
                context.contentResolver.openInputStream(Uri.parse(playlist.source))
            }
            
            stream?.let {
                val reader = BufferedReader(InputStreamReader(it))
                var line = reader.readLine()
                var name="Unknown"
                var grp="General"
                var logo:String?=null
                var drm:String?=null
                var count = 1
                
                while (line != null) {
                    line = line.trim()
                    if (line.startsWith("#EXTINF:")) {
                        name = line.substringAfterLast(",").trim()
                        if (line.contains("group-title=\"")) {
                            grp = line.substringAfter("group-title=\"").substringBefore("\"")
                        }
                        if (line.contains("tvg-logo=\"")) {
                            logo = line.substringAfter("tvg-logo=\"").substringBefore("\"")
                        }
                    } 
                    else if (line.startsWith("#KODIPROP:inputstream.adaptive.license_key=")) {
                        drm = line.substringAfter("=")
                    }
                    else if (!line.startsWith("#") && line.isNotEmpty()) {
                        var cleanUrl = line
                        var ua: String? = null
                        if (line.contains("|")) {
                            val parts = line.split("|")
                            cleanUrl = parts[0]
                            if (parts.size > 1 && parts[1].contains("User-Agent=")) {
                                ua = parts[1].substringAfter("User-Agent=")
                            }
                        }

                        val id = "${playlist.id}_$count"
                        channels.add(Channel(
                            id = id,
                            number = count++,
                            name = name,
                            group = grp,
                            url = cleanUrl,
                            userAgent = ua,
                            logo = logo,
                            drmLicense = drm,
                            isFavorite = favIds.contains(id)
                        ))
                        name="Unknown"
                        drm=null 
                    }
                    line = reader.readLine()
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
        
        val map = channels.groupBy { it.group }.toMutableMap()
        if(channels.isNotEmpty()) map["All Channels"] = channels
        
        val favs = channels.filter { it.isFavorite }
        if(favs.isNotEmpty()) map["Favorites"] = favs
        
        return@withContext map
    }
}
