package com.iptv.player

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.StrictMode
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaItem.DrmConfiguration
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private var player: ExoPlayer? = null
    private var drawerLayout: DrawerLayout? = null
    private var epgContainer: LinearLayout? = null
    private var rvGroups: RecyclerView? = null
    private var rvChannels: RecyclerView? = null
    private var rvRecents: RecyclerView? = null // Added Recents Recycler
    private var searchContainer: LinearLayout? = null
    private var etSearch: EditText? = null
    private var rvSearchResults: RecyclerView? = null
    private var tvSearchCount: TextView? = null

    private lateinit var repo: Repository
    private var allData = mapOf<String, List<Channel>>()
    private var allChannelsFlat = listOf<Channel>()
    private var currentChannel: Channel? = null // Track current channel for Next/Prev
    private var numBuffer = ""
    private val handler = Handler(Looper.getMainLooper())
    private val PICK_FILE = 101

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // --- DEFECT FIX 5: FORCE NETWORK PERMISSIONS (NUCLEAR BYPASS) ---
        val policy = StrictMode.ThreadPolicy.Builder().permitAll().build()
        StrictMode.setThreadPolicy(policy)
        
        try {
            setContentView(R.layout.activity_main)
            repo = Repository(this)
            
            initializePlayer()

            drawerLayout = findViewById(R.id.drawerLayout)
            epgContainer = findViewById(R.id.epgContainer)
            rvGroups = findViewById(R.id.rvGroups)
            rvChannels = findViewById(R.id.rvChannels)
            rvRecents = findViewById(R.id.rvRecents) // Bind Recents

            rvGroups?.layoutManager = LinearLayoutManager(this)
            rvChannels?.layoutManager = LinearLayoutManager(this)
            rvRecents?.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)

            setupSearchUI()
            updateSettingsDrawer()

            val active = repo.getActivePlaylist()
            if (active != null) loadData(active) else showWelcomeDialog()

        } catch (e: Exception) {
            e.printStackTrace()
            showError("Init Error", "${e.message}")
        }
    }

    private fun initializePlayer() {
        val httpFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true) // Fix Redirects
            //.setUserAgent("TiviMate/4.7.0")
            .setConnectTimeoutMs(8000)
            .setReadTimeoutMs(8000)
        
        val dataSourceFactory = DefaultDataSource.Factory(this, httpFactory)
        
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(dataSourceFactory))
            .build()
            
        val playerView = findViewById<PlayerView>(R.id.playerView)
        playerView?.player = player
        
        // --- DEFECT FIX 4: FORCE FULLSCREEN VIDEO (NO BLACK BARS) ---
        playerView?.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
    }

    private fun showError(title: String, msg: String) {
        AlertDialog.Builder(this).setTitle(title).setMessage(msg).setPositiveButton("Close") { _, _ -> }.show()
    }

    private fun loadData(p: Playlist) {
        lifecycleScope.launch {
            try {
                Toast.makeText(this@MainActivity, "Loading...", Toast.LENGTH_SHORT).show()
                allData = M3uParser.parse(this@MainActivity, p, repo.getFavIds())
                allChannelsFlat = allData.values.flatten().distinctBy { it.id }
                
                rvGroups?.adapter = GroupAdapter(allData.keys.toList(), { group ->
                    val channels = allData[group] ?: emptyList()
                    (rvChannels?.adapter as? ChannelAdapter)?.update(channels, rvChannels)
                }, { focusChannelList() })
                
                if (allData.isNotEmpty()) {
                    val first = allData.keys.first()
                    rvChannels?.adapter = ChannelAdapter(allData[first] ?: emptyList(), { play(it) }, { toggleFav(it) }, { focusGroupList() })
                    epgContainer?.visibility = View.VISIBLE
                    rvGroups?.requestFocus()
                    updateRecentList() // Init recents
                } else {
                    Toast.makeText(this@MainActivity, "No channels found!", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) { showError("Playlist Error", e.message ?: "Unknown") }
        }
    }

    private fun updateSettingsDrawer() {
        val root = findViewById<LinearLayout>(R.id.settingsDrawer) ?: return
        root.removeAllViews()
        fun btn(t: String, a: () -> Unit) {
            val b = Button(this); b.text = t; b.setTextColor(Color.WHITE)
            b.setBackgroundResource(android.R.drawable.btn_default)
            b.setOnClickListener { a() }
            root.addView(b)
        }
        val t = TextView(this); t.text="SETTINGS"; t.textSize=22f; t.setTextColor(Color.CYAN); root.addView(t)
        btn("Search") { openSearch(); drawerLayout?.closeDrawers() }
        btn("+ URL Playlist") { showAddUrlDialog() }
        btn("+ Local File") { openFilePicker() }
        val s = TextView(this); s.text="PLAYLISTS"; s.textSize=18f; s.setTextColor(Color.LTGRAY); root.addView(s)
        repo.getPlaylists().forEach { p -> btn(p.name) { repo.setActivePlaylist(p.id); loadData(p); drawerLayout?.closeDrawers() } }
    }

    private fun showAddUrlDialog() {
        val input = EditText(this); input.hint = "http://..."; input.setTextColor(Color.WHITE)
        AlertDialog.Builder(this).setTitle("Add URL").setView(input).setPositiveButton("Load") { _,_ ->
            if(input.text.isNotEmpty()) {
                val p = Playlist(name="Playlist ${repo.getPlaylists().size+1}", source=input.text.toString(), type=PlaylistType.REMOTE)
                repo.savePlaylist(p); updateSettingsDrawer(); loadData(p)
            }
        }.show()
    }
    
    private fun openFilePicker() {
        try {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type="*/*" }
            startActivityForResult(intent, PICK_FILE)
        } catch (e: Exception) {}
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
        AlertDialog.Builder(this).setTitle("Welcome").setMessage("Add Playlist").setPositiveButton("URL") { _,_ -> showAddUrlDialog() }.setNegativeButton("File") { _,_ -> openFilePicker() }.show()
    }

    private fun play(c: Channel) {
        try {
            currentChannel = c
            repo.addRecent(c)
            updateRecentList() // --- DEFECT FIX 6: REFRESH RECENTS ON PLAY ---
            
            val builder = MediaItem.Builder().setUri(c.url)
            if (c.drmLicense != null) builder.setDrmConfiguration(DrmConfiguration.Builder(androidx.media3.common.C.WIDEVINE_UUID).setLicenseUri(c.drmLicense).build())
            
            player?.setMediaItem(builder.build())
            player?.prepare()
            player?.play()
            
            epgContainer?.visibility = View.GONE // Hide menu to watch full screen
        } catch (e: Exception) { Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show() }
    }

    // --- DEFECT FIX 6: FUNCTION TO SHOW RECENTS ---
    private fun updateRecentList() {
        val recents = repo.getRecents()
        if (recents.isNotEmpty()) {
            findViewById<View>(R.id.recentContainer)?.visibility = View.GONE // Hide initially, show on Down key
            // We reuse ChannelAdapter but with horizontal layout handling if needed, 
            // or just simple display. For now, simple adapter:
            rvRecents?.adapter = ChannelAdapter(recents, { play(it) }, { toggleFav(it) }, {})
        }
    }

    private fun setupSearchUI() {
        searchContainer = findViewById(R.id.searchContainer)
        etSearch = findViewById(R.id.etSearch)
        rvSearchResults = findViewById(R.id.rvSearchResults)
        tvSearchCount = findViewById(R.id.tvSearchResultsCount)
        findViewById<Button>(R.id.btnCloseSearch)?.setOnClickListener { closeSearch() }
        rvSearchResults?.layoutManager = LinearLayoutManager(this)
        etSearch?.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { performSearch(s.toString()) }
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        })
    }

    private fun performSearch(query: String) {
        if (query.isEmpty()) { rvSearchResults?.adapter = null; tvSearchCount?.text = "0 Results"; return }
        val results = allChannelsFlat.filter { it.name.contains(query, ignoreCase = true) }
        tvSearchCount?.text = "${results.size} Results"
        rvSearchResults?.adapter = ChannelAdapter(results, { c -> play(c); closeSearch() }, { c -> toggleFav(c) }, {})
    }

    private fun openSearch() { searchContainer?.visibility = View.VISIBLE; epgContainer?.visibility = View.GONE; etSearch?.requestFocus(); showKeyboard() }
    private fun closeSearch() { searchContainer?.visibility = View.GONE; etSearch?.text?.clear(); hideKeyboard() }
    private fun showKeyboard() { val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager; etSearch?.let { imm.showSoftInput(it, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT) } }
    private fun hideKeyboard() { val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager; etSearch?.let { imm.hideSoftInputFromWindow(it.windowToken, 0) } }

    private fun toggleFav(c: Channel) { repo.toggleFav(c.id); c.isFavorite = !c.isFavorite; (rvChannels?.adapter as? ChannelAdapter)?.notifyDataSetChanged(); (rvSearchResults?.adapter as? ChannelAdapter)?.notifyDataSetChanged() }
    fun focusGroupList() { rvGroups?.requestFocus() }
    fun focusChannelList() { rvChannels?.requestFocus() }

    // --- CRITICAL DEFECT FIXES: REMOTE CONTROL LOGIC ---
    override fun onKeyDown(k: Int, e: KeyEvent?): Boolean {
        // --- DEFECT FIX 3: NUMBER ZAPPING ---
        if (k in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
            numBuffer += (k - KeyEvent.KEYCODE_0)
            findViewById<TextView>(R.id.tvOverlayNum)?.let { tv ->
                tv.text = numBuffer
                tv.visibility = View.VISIBLE
                handler.removeCallbacksAndMessages(null)
                handler.postDelayed({
                    val target = allChannelsFlat.find { it.number.toString() == numBuffer }
                    if (target != null) {
                        play(target)
                    } else {
                        Toast.makeText(this, "Channel $numBuffer not found", Toast.LENGTH_SHORT).show()
                    }
                    numBuffer = ""
                    tv.visibility = View.GONE
                }, 2000) // 2 second wait
            }
            return true
        }

        // --- DEFECT FIX 1: CHANNEL UP/DOWN ZAPPING ---
        // Only zap if menu is HIDDEN (watching video)
        if (epgContainer?.visibility != View.VISIBLE && searchContainer?.visibility != View.VISIBLE) {
            when(k) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> {
                    currentChannel?.let { curr ->
                        val idx = allChannelsFlat.indexOf(curr)
                        if (idx > 0) play(allChannelsFlat[idx - 1]) // Prev Channel
                    }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                    // Fix: Down usually means Next Channel in lists, but sometimes user wants "Recent" menu.
                    // Let's make "Channel Down" key do zapping, and "D-Pad Down" open recents.
                    if (k == KeyEvent.KEYCODE_CHANNEL_DOWN) {
                        currentChannel?.let { curr ->
                            val idx = allChannelsFlat.indexOf(curr)
                            if (idx < allChannelsFlat.size - 1) play(allChannelsFlat[idx + 1])
                        }
                    } else {
                        // D-Pad Down opens Recents
                        findViewById<View>(R.id.recentContainer)?.visibility = View.VISIBLE
                        rvRecents?.requestFocus()
                    }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { 
                    drawerLayout?.openDrawer(Gravity.END)
                    updateSettingsDrawer()
                    return true 
                }
                // --- DEFECT FIX 2: BACK BUTTON ---
                KeyEvent.KEYCODE_BACK -> {
                    epgContainer?.visibility = View.VISIBLE
                    rvGroups?.requestFocus()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MENU -> {
                    epgContainer?.visibility = View.VISIBLE
                    rvGroups?.requestFocus()
                    return true
                }
            }
        } 
        
        // Navigation inside Menu
        if (k == KeyEvent.KEYCODE_BACK) {
            if (searchContainer?.visibility == View.VISIBLE) {
                closeSearch()
                return true
            }
            if (drawerLayout?.isDrawerOpen(Gravity.END) == true) {
                drawerLayout?.closeDrawers()
                return true
            }
            if (epgContainer?.visibility == View.VISIBLE) {
                // If in menu, Back hides menu to watch video
                epgContainer?.visibility = View.GONE
                return true
            }
        }

        return super.onKeyDown(k, e)
    }
    
    override fun onDestroy() { super.onDestroy(); player?.release() }
}
