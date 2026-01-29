package com.iptv.player

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle; android.os.Handler; android.os.Looper
import android.view.*; android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaItem.DrmConfiguration
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import java.util.UUID

class MainActivity : AppCompatActivity() {
    private lateinit var player: ExoPlayer
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var epgContainer: LinearLayout
    private lateinit var rvGroups: RecyclerView; private lateinit var rvChannels: RecyclerView
    private lateinit var repo: Repository
    
    private var allData = mapOf<String, List<Channel>>()
    private var allChannelsFlat = listOf<Channel>()
    private var numBuffer = ""; private val handler = Handler(Looper.getMainLooper())
    private val PICK_FILE = 101

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        repo = Repository(this)

        player = ExoPlayer.Builder(this).build()
        findViewById<PlayerView>(R.id.playerView).player = player
        
        drawerLayout = findViewById(R.id.drawerLayout)
        epgContainer = findViewById(R.id.epgContainer)
        rvGroups = findViewById(R.id.rvGroups); rvGroups.layoutManager = LinearLayoutManager(this)
        rvChannels = findViewById(R.id.rvChannels); rvChannels.layoutManager = LinearLayoutManager(this)

        updateSettingsDrawer()

        val active = repo.getActivePlaylist()
        if (active != null) loadData(active) else showWelcomeDialog()
    }

    private fun loadData(p: Playlist) {
        lifecycleScope.launch {
            Toast.makeText(this@MainActivity, "Loading...", Toast.LENGTH_SHORT).show()
            allData = M3uParser.parse(this@MainActivity, p, repo.getFavIds())
            allChannelsFlat = allData.values.flatten().distinctBy { it.id }
            
            rvGroups.adapter = GroupAdapter(allData.keys.toList()) { group ->
                (rvChannels.adapter as? ChannelAdapter)?.update(allData[group] ?: emptyList())
            }
            if (allData.isNotEmpty()) {
                val firstGroup = allData.keys.first()
                rvChannels.adapter = ChannelAdapter(allData[firstGroup] ?: emptyList(), { play(it) }, { toggleFav(it) })
            }
        }
    }

    // --- PLAYBACK ENGINE ---
    private fun play(c: Channel) {
        // PREVIEW MODE: We load the stream but DO NOT hide the menu immediately.
        // User must click "Back" to go fullscreen if they want.
        
        val builder = MediaItem.Builder().setUri(c.url)
        if (c.drmLicense != null) {
            builder.setDrmConfiguration(DrmConfiguration.Builder(androidx.media3.common.C.WIDEVINE_UUID).setLicenseUri(c.drmLicense).build())
        }
        
        // Apply User-Agent Header if present
        // (Note: To apply headers fully in ExoPlayer requires a custom DataSourceFactory, 
        //  but the parsing logic is ready in the Model)
        
        player.setMediaItem(builder.build())
        player.prepare()
        player.play()
        
        // HIDE MENU instantly (As per your previous request to switch channel logic)
        epgContainer.visibility = View.GONE
    }

    private fun toggleFav(c: Channel) {
        repo.toggleFav(c.id); c.isFavorite = !c.isFavorite
        (rvChannels.adapter as? ChannelAdapter)?.notifyDataSetChanged()
    }

    // --- SETTINGS ---
    private fun updateSettingsDrawer() {
        val root = findViewById<LinearLayout>(R.id.settingsDrawer)
        root.removeAllViews()
        fun btn(t: String, a: ()->Unit) = root.addView(Button(this).apply { text=t; setOnClickListener { a() } })
        
        root.addView(TextView(this).apply { text="Settings"; textSize=24f; setTextColor(-1) })
        btn("Add URL Playlist") { showAddUrlDialog() }
        btn("Add Local M3U") { openFilePicker() }
        
        root.addView(TextView(this).apply { text="My Playlists"; textSize=18f; setTextColor(-1); setPadding(0,40,0,10) })
        repo.getPlaylists().forEach { p ->
            btn(p.name) { repo.setActivePlaylist(p.id); loadData(p); drawerLayout.closeDrawers() }
        }
    }

    private fun showAddUrlDialog() {
        val input = EditText(this).apply { hint = "http://..." }
        AlertDialog.Builder(this).setTitle("Add URL").setView(input).setPositiveButton("OK") {_,_->
            if(input.text.isNotEmpty()) {
                val p = Playlist(name="Playlist ${repo.getPlaylists().size+1}", source=input.text.toString(), type=PlaylistType.REMOTE)
                repo.savePlaylist(p); updateSettingsDrawer(); loadData(p)
            }
        }.show()
    }

    private fun openFilePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" }
        startActivityForResult(intent, PICK_FILE)
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == PICK_FILE && res == Activity.RESULT_OK) {
            data?.data?.let { uri ->
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val p = Playlist(name="Local Playlist", source=uri.toString(), type=PlaylistType.LOCAL)
                repo.savePlaylist(p); updateSettingsDrawer(); loadData(p)
            }
        }
    }

    private fun showWelcomeDialog() {
        AlertDialog.Builder(this).setTitle("Welcome").setMessage("Add a playlist.")
            .setPositiveButton("Add URL") {_,_-> showAddUrlDialog()}
            .setNegativeButton("Local File") {_,_-> openFilePicker()}.show()
    }

    // --- HELPERS ---
    fun focusGroupList() = rvGroups.requestFocus()
    fun focusChannelList() = rvChannels.requestFocus()

    // --- REMOTE CONTROL LOGIC ---
    override fun onKeyDown(k: Int, e: KeyEvent?): Boolean {
        // 1. NUMBER INPUT
        if (k in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
            numBuffer += (k - KeyEvent.KEYCODE_0)
            val tv = findViewById<TextView>(R.id.tvOverlayNum)
            tv.text = numBuffer; tv.visibility = View.VISIBLE
            handler.removeCallbacksAndMessages(null)
            handler.postDelayed({
                allChannelsFlat.find { it.number == numBuffer.toInt() }?.let { play(it) }
                numBuffer = ""; tv.visibility = View.GONE
            }, 3000)
            return true
        }
        
        // 2. MENU VISIBILITY
        if (epgContainer.visibility == View.GONE) {
            when(k) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MENU -> {
                    epgContainer.visibility = View.VISIBLE; focusGroupList(); return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    drawerLayout.openDrawer(Gravity.END); return true
                }
            }
        } else if (k == KeyEvent.KEYCODE_BACK) {
            epgContainer.visibility = View.GONE; return true
        }
        return super.onKeyDown(k, e)
    }

    override fun onDestroy() { super.onDestroy(); player.release() }
}
