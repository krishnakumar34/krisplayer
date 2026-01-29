package com.iptv.player

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
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
    // Core
    private lateinit var player: ExoPlayer
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var epgContainer: LinearLayout
    private lateinit var repo: Repository
    
    // UI Lists
    private lateinit var rvGroups: RecyclerView
    private lateinit var rvChannels: RecyclerView
    
    // Search UI
    private lateinit var searchContainer: LinearLayout
    private lateinit var etSearch: EditText
    private lateinit var rvSearchResults: RecyclerView
    private lateinit var tvSearchCount: TextView
    
    // Data
    private var allData = mapOf<String, List<Channel>>()
    private var allChannelsFlat = listOf<Channel>()
    private var numBuffer = ""
    private val handler = Handler(Looper.getMainLooper())
    private val PICK_FILE = 101

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        repo = Repository(this)

        player = ExoPlayer.Builder(this).build()
        findViewById<PlayerView>(R.id.playerView).player = player
        
        drawerLayout = findViewById(R.id.drawerLayout)
        epgContainer = findViewById(R.id.epgContainer)
        rvGroups = findViewById(R.id.rvGroups)
        rvGroups.layoutManager = LinearLayoutManager(this)
        rvChannels = findViewById(R.id.rvChannels)
        rvChannels.layoutManager = LinearLayoutManager(this)

        setupSearchUI()
        updateSettingsDrawer()

        val active = repo.getActivePlaylist()
        if (active != null) {
            loadData(active)
        } else {
            showWelcomeDialog()
        }
    }

    // --- SEARCH FEATURE ---
    private fun setupSearchUI() {
        searchContainer = findViewById(R.id.searchContainer)
        etSearch = findViewById(R.id.etSearch)
        rvSearchResults = findViewById(R.id.rvSearchResults)
        tvSearchCount = findViewById(R.id.tvSearchResultsCount)
        findViewById<Button>(R.id.btnCloseSearch).setOnClickListener { closeSearch() }

        rvSearchResults.layoutManager = LinearLayoutManager(this)
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { performSearch(s.toString()) }
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        })
    }

    private fun performSearch(query: String) {
        if (query.isEmpty()) { 
            rvSearchResults.adapter = null
            tvSearchCount.text = "0 Results"
            return 
        }
        val results = allChannelsFlat.filter { it.name.contains(query, ignoreCase = true) }
        tvSearchCount.text = "${results.size} Results"
        rvSearchResults.adapter = ChannelAdapter(results, { c -> 
            play(c)
            closeSearch() 
        }, { 
            toggleFav(c) 
        })
    }

    private fun openSearch() { 
        searchContainer.visibility = View.VISIBLE
        epgContainer.visibility = View.GONE
        etSearch.requestFocus() 
    }
    
    private fun closeSearch() { 
        searchContainer.visibility = View.GONE
        etSearch.text.clear() 
    }

    // --- DATA LOADING ---
    private fun loadData(p: Playlist) {
        lifecycleScope.launch {
            Toast.makeText(this@MainActivity, "Loading...", Toast.LENGTH_SHORT).show()
            allData = M3uParser.parse(this@MainActivity, p, repo.getFavIds())
            allChannelsFlat = allData.values.flatten().distinctBy { it.id }
            
            rvGroups.adapter = GroupAdapter(allData.keys.toList()) { group ->
                (rvChannels.adapter as? ChannelAdapter)?.update(allData[group] ?: emptyList())
            }
            if (allData.isNotEmpty()) {
                val first = allData.keys.first()
                rvChannels.adapter = ChannelAdapter(allData[first] ?: emptyList(), { play(it) }, { toggleFav(it) })
            }
        }
    }

    // --- PLAYBACK ---
    private fun play(c: Channel) {
        repo.addRecent(c)
        val builder = MediaItem.Builder().setUri(c.url)
        if (c.drmLicense != null) {
            builder.setDrmConfiguration(DrmConfiguration.Builder(androidx.media3.common.C.WIDEVINE_UUID).setLicenseUri(c.drmLicense).build())
        }
        player.setMediaItem(builder.build())
        player.prepare()
        player.play()
        epgContainer.visibility = View.GONE
    }

    private fun toggleFav(c: Channel) {
        repo.toggleFav(c.id)
        c.isFavorite = !c.isFavorite
        (rvChannels.adapter as? ChannelAdapter)?.notifyDataSetChanged()
        (rvSearchResults.adapter as? ChannelAdapter)?.notifyDataSetChanged()
    }

    // --- SETTINGS ---
    private fun updateSettingsDrawer() {
        val root = findViewById<LinearLayout>(R.id.settingsDrawer)
        root.removeAllViews()
        
        fun btn(t: String, a: ()->Unit) {
            val b = Button(this)
            b.text = t
            b.setOnClickListener { a() }
            root.addView(b)
        }
        
        val title = TextView(this)
        title.text = "Settings"
        title.textSize = 24f
        title.setTextColor(-1)
        root.addView(title)

        btn("Search") { 
            openSearch()
            drawerLayout.closeDrawers() 
        }
        btn("Add URL") { showAddUrlDialog() }
        btn("Add Local") { openFilePicker() }
        
        val sub = TextView(this)
        sub.text = "Playlists"
        sub.textSize = 18f
        sub.setTextColor(-1)
        sub.setPadding(0,40,0,10)
        root.addView(sub)

        repo.getPlaylists().forEach { p ->
            btn(p.name) { 
                repo.setActivePlaylist(p.id)
                loadData(p)
                drawerLayout.closeDrawers() 
            }
        }
    }

    // --- DIALOGS ---
    private fun showAddUrlDialog() {
        val input = EditText(this)
        input.hint = "http://..."
        AlertDialog.Builder(this)
            .setTitle("Add URL")
            .setView(input)
            .setPositiveButton("OK") { _,_ ->
                if(input.text.isNotEmpty()) {
                    val p = Playlist(name="Playlist ${repo.getPlaylists().size+1}", source=input.text.toString(), type=PlaylistType.REMOTE)
                    repo.savePlaylist(p)
                    updateSettingsDrawer()
                    loadData(p)
                }
            }.show()
    }
    
    private fun openFilePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.type = "*/*"
        startActivityForResult(intent, PICK_FILE)
    }
    
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == PICK_FILE && res == Activity.RESULT_OK) {
            data?.data?.let { 
                contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val p = Playlist(name="Local Playlist", source=it.toString(), type=PlaylistType.LOCAL)
                repo.savePlaylist(p)
                updateSettingsDrawer()
                loadData(p)
            }
        }
    }
    
    private fun showWelcomeDialog() {
        AlertDialog.Builder(this)
            .setTitle("Welcome")
            .setMessage("Add Playlist")
            .setPositiveButton("URL") { _,_ -> showAddUrlDialog() }
            .show()
    }

    // --- HELPERS ---
    fun focusGroupList() { rvGroups.requestFocus() }
    fun focusChannelList() { rvChannels.requestFocus() }

    // --- REMOTE INPUT ---
    override fun onKeyDown(k: Int, e: KeyEvent?): Boolean {
        if (k == KeyEvent.KEYCODE_SEARCH) { 
            openSearch()
            return true 
        }
        
        // Numbers
        if (k in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
            numBuffer += (k - KeyEvent.KEYCODE_0)
            val tv = findViewById<TextView>(R.id.tvOverlayNum)
            tv.text = numBuffer
            tv.visibility = View.VISIBLE
            handler.removeCallbacksAndMessages(null)
            handler.postDelayed({
                allChannelsFlat.find { it.number == numBuffer.toInt() }?.let { play(it) }
                numBuffer = ""
                tv.visibility = View.GONE
            }, 3000)
            return true
        }

        // Navigation
        if (epgContainer.visibility == View.GONE && searchContainer.visibility == View.GONE) {
            when(k) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MENU -> {
                    epgContainer.visibility = View.VISIBLE
                    focusGroupList()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { 
                    drawerLayout.openDrawer(Gravity.END)
                    return true 
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> { 
                    findViewById<View>(R.id.recentContainer).visibility = View.VISIBLE
                    return true 
                }
            }
        } else if (k == KeyEvent.KEYCODE_BACK) {
            if (searchContainer.visibility == View.VISIBLE) {
                closeSearch()
            } else {
                epgContainer.visibility = View.GONE
            }
            return true
        }
        return super.onKeyDown(k, e)
    }

    override fun onDestroy() { 
        super.onDestroy()
        player.release() 
    }
}
