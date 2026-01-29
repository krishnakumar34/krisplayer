package com.iptv.player

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import java.util.UUID

class MainActivity : AppCompatActivity() {
    // --- UI COMPONENTS ---
    private var player: ExoPlayer? = null
    private var drawerLayout: DrawerLayout? = null
    private var epgContainer: LinearLayout? = null
    private var rvGroups: RecyclerView? = null
    private var rvChannels: RecyclerView? = null
    private var searchContainer: LinearLayout? = null
    private var etSearch: EditText? = null
    private var rvSearchResults: RecyclerView? = null
    private var tvSearchCount: TextView? = null

    // --- DATA ---
    private lateinit var repo: Repository
    private var allData = mapOf<String, List<Channel>>()
    private var allChannelsFlat = listOf<Channel>()
    private var numBuffer = ""
    private val handler = Handler(Looper.getMainLooper())
    private val PICK_FILE = 101

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 1. SAFE LAYOUT LOADING
        try {
            setContentView(R.layout.activity_main)
        } catch (e: Exception) {
            showError("Layout Error", "XML Error: ${e.message}")
            return
        }

        // 2. INITIALIZATION
        try {
            repo = Repository(this)
            
            player = ExoPlayer.Builder(this).build()
            findViewById<PlayerView>(R.id.playerView)?.player = player

            drawerLayout = findViewById(R.id.drawerLayout)
            epgContainer = findViewById(R.id.epgContainer)
            rvGroups = findViewById(R.id.rvGroups)
            rvChannels = findViewById(R.id.rvChannels)

            rvGroups?.layoutManager = LinearLayoutManager(this)
            rvChannels?.layoutManager = LinearLayoutManager(this)

            setupSearchUI()
            updateSettingsDrawer() // <--- Checks if Local Option exists

            // Load Active Playlist or Show Welcome
            val active = repo.getActivePlaylist()
            if (active != null) {
                loadData(active)
            } else {
                showWelcomeDialog()
            }

        } catch (e: Exception) {
            e.printStackTrace()
            showError("Init Error", "${e.message}")
        }
    }

    private fun showError(title: String, msg: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(msg)
            .setPositiveButton("Close") { _, _ -> }
            .show()
    }

    // --- 1. DATA LOADING (FIXED: Shows Menu automatically) ---
    private fun loadData(p: Playlist) {
        lifecycleScope.launch {
            try {
                Toast.makeText(this@MainActivity, "Loading Playlist...", Toast.LENGTH_SHORT).show()
                
                // Parse in background
                allData = M3uParser.parse(this@MainActivity, p, repo.getFavIds())
                allChannelsFlat = allData.values.flatten().distinctBy { it.id }
                
                // Setup Adapters
                rvGroups?.adapter = GroupAdapter(allData.keys.toList()) { group ->
                    (rvChannels?.adapter as? ChannelAdapter)?.update(allData[group] ?: emptyList())
                }
                
                if (allData.isNotEmpty()) {
                    val first = allData.keys.first()
                    rvChannels?.adapter = ChannelAdapter(allData[first] ?: emptyList(), { play(it) }, { toggleFav(it) })
                    
                    // *** FIX: FORCE MENU TO SHOW ***
                    epgContainer?.visibility = View.VISIBLE
                    rvGroups?.requestFocus()
                    
                    Toast.makeText(this@MainActivity, "Loaded ${allChannelsFlat.size} Channels", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, "No channels found in this URL!", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                showError("Playlist Error", "Failed to parse: ${e.message}")
            }
        }
    }

    // --- 2. SETTINGS DRAWER (FIXED: Explicit Buttons) ---
    private fun updateSettingsDrawer() {
        val root = findViewById<LinearLayout>(R.id.settingsDrawer) ?: return
        root.removeAllViews()
        
        // Helper to create visible buttons
        fun addButton(text: String, onClick: ()->Unit) {
            val btn = Button(this)
            btn.text = text
            btn.setTextColor(Color.WHITE)
            btn.setBackgroundResource(android.R.drawable.btn_default)
            btn.setOnClickListener { onClick() }
            // Add spacing
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.setMargins(0, 10, 0, 10)
            btn.layoutParams = params
            root.addView(btn)
        }
        
        val title = TextView(this)
        title.text = "SETTINGS"
        title.textSize = 22f
        title.setTextColor(Color.CYAN)
        title.setPadding(0, 0, 0, 20)
        root.addView(title)

        // *** THESE ARE THE BUTTONS YOU WERE MISSING ***
        addButton("Search Channels") { 
            openSearch()
            drawerLayout?.closeDrawers() 
        }
        addButton("+ Add URL Playlist") { showAddUrlDialog() }
        addButton("+ Add Local M3U File") { openFilePicker() }
        
        val sub = TextView(this)
        sub.text = "MY PLAYLISTS"
        sub.textSize = 18f
        sub.setTextColor(Color.LTGRAY)
        sub.setPadding(0, 40, 0, 10)
        root.addView(sub)

        repo.getPlaylists().forEach { p ->
            addButton(p.name) { 
                repo.setActivePlaylist(p.id)
                loadData(p)
                drawerLayout?.closeDrawers() 
            }
        }
    }

    // --- 3. DIALOGS ---
    private fun showAddUrlDialog() {
        val input = EditText(this)
        input.hint = "http://example.com/playlist.m3u"
        input.setTextColor(Color.WHITE)
        
        AlertDialog.Builder(this)
            .setTitle("Add Remote Playlist")
            .setView(input)
            .setPositiveButton("Load") { _,_ ->
                val url = input.text.toString()
                if(url.isNotEmpty()) {
                    val p = Playlist(name="Playlist ${repo.getPlaylists().size+1}", source=url, type=PlaylistType.REMOTE)
                    repo.savePlaylist(p)
                    updateSettingsDrawer()
                    loadData(p) // Loads and opens menu
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun openFilePicker() {
        try {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            intent.addCategory(Intent.CATEGORY_OPENABLE)
            intent.type = "*/*" // Allow all files, user selects .m3u
            startActivityForResult(intent, PICK_FILE)
        } catch (e: Exception) {
            Toast.makeText(this, "No File Manager found on this device", Toast.LENGTH_LONG).show()
        }
    }
    
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == PICK_FILE && res == Activity.RESULT_OK) {
            data?.data?.let { uri ->
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val p = Playlist(name="Local File ${repo.getPlaylists().size+1}", source=uri.toString(), type=PlaylistType.LOCAL)
                repo.savePlaylist(p)
                updateSettingsDrawer()
                loadData(p)
            }
        }
    }
    
    private fun showWelcomeDialog() {
        AlertDialog.Builder(this)
            .setTitle("Welcome to TiviClone")
            .setMessage("No playlist found. How do you want to add channels?")
            .setPositiveButton("From URL") { _,_ -> showAddUrlDialog() }
            .setNegativeButton("From File") { _,_ -> openFilePicker() }
            .setCancelable(false)
            .show()
    }

    // --- 4. PLAYER & SEARCH LOGIC ---
    private fun play(c: Channel) {
        try {
            repo.addRecent(c)
            val builder = MediaItem.Builder().setUri(c.url)
            if (c.drmLicense != null) {
                builder.setDrmConfiguration(DrmConfiguration.Builder(androidx.media3.common.C.WIDEVINE_UUID).setLicenseUri(c.drmLicense).build())
            }
            player?.setMediaItem(builder.build())
            player?.prepare()
            player?.play()
            epgContainer?.visibility = View.GONE // Hide menu when playing
        } catch (e: Exception) {
            Toast.makeText(this, "Playback Error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupSearchUI() {
        searchContainer = findViewById(R.id.searchContainer)
        etSearch = findViewById(R.id.etSearch)
        rvSearchResults = findViewById(R.id.rvSearchResults)
        tvSearchCount = findViewById(R.id.tvSearchResultsCount)
        val btnClose = findViewById<Button>(R.id.btnCloseSearch)

        btnClose?.setOnClickListener { closeSearch() }
        rvSearchResults?.layoutManager = LinearLayoutManager(this)
        
        etSearch?.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { performSearch(s.toString()) }
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        })
    }

    private fun performSearch(query: String) {
        if (query.isEmpty()) { 
            rvSearchResults?.adapter = null
            tvSearchCount?.text = "0 Results"
            return 
        }
        val results = allChannelsFlat.filter { it.name.contains(query, ignoreCase = true) }
        tvSearchCount?.text = "${results.size} Results"
        
        rvSearchResults?.adapter = ChannelAdapter(results, { c -> 
            play(c)
            closeSearch() 
        }, { c -> 
            toggleFav(c) 
        })
    }

    private fun openSearch() { 
        searchContainer?.visibility = View.VISIBLE
        epgContainer?.visibility = View.GONE
        etSearch?.requestFocus() 
        showKeyboard()
    }
    
    private fun closeSearch() { 
        searchContainer?.visibility = View.GONE
        etSearch?.text?.clear() 
        hideKeyboard()
    }
    
    private fun showKeyboard() {
        // Basic keyboard trigger
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        etSearch?.let { imm.showSoftInput(it, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT) }
    }
    
    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        etSearch?.let { imm.hideSoftInputFromWindow(it.windowToken, 0) }
    }

    private fun toggleFav(c: Channel) {
        repo.toggleFav(c.id)
        c.isFavorite = !c.isFavorite
        (rvChannels?.adapter as? ChannelAdapter)?.notifyDataSetChanged()
        (rvSearchResults?.adapter as? ChannelAdapter)?.notifyDataSetChanged()
    }

    // --- 5. REMOTE CONTROL ---
    override fun onKeyDown(k: Int, e: KeyEvent?): Boolean {
        // Toggle Menu on OK/Center if not already visible
        if ((k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_MENU) && epgContainer?.visibility != View.VISIBLE) {
            epgContainer?.visibility = View.VISIBLE
            rvGroups?.requestFocus()
            return true
        }

        if (k == KeyEvent.KEYCODE_SEARCH) { openSearch(); return true }
        
        if (k in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
            numBuffer += (k - KeyEvent.KEYCODE_0)
            val tv = findViewById<TextView>(R.id.tvOverlayNum)
            if (tv != null) {
                tv.text = numBuffer
                tv.visibility = View.VISIBLE
                handler.removeCallbacksAndMessages(null)
                handler.postDelayed({
                    allChannelsFlat.find { it.number == numBuffer.toInt() }?.let { play(it) }
                    numBuffer = ""
                    tv.visibility = View.GONE
                }, 3000)
            }
            return true
        }

        if (epgContainer?.visibility == View.GONE && searchContainer?.visibility == View.GONE) {
            when(k) {
                KeyEvent.KEYCODE_DPAD_RIGHT -> { 
                    drawerLayout?.openDrawer(Gravity.END)
                    updateSettingsDrawer() // Ensure drawer is fresh
                    return true 
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> { 
                    findViewById<View>(R.id.recentContainer)?.visibility = View.VISIBLE
                    return true 
                }
            }
        } else if (k == KeyEvent.KEYCODE_BACK) {
            if (searchContainer?.visibility == View.VISIBLE) closeSearch()
            else if (drawerLayout?.isDrawerOpen(Gravity.END) == true) drawerLayout?.closeDrawers()
            else epgContainer?.visibility = View.GONE
            return true
        }
        return super.onKeyDown(k, e)
    }

    override fun onDestroy() { 
        super.onDestroy()
        player?.release() 
    }
}
