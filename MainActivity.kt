package com.iptv.player

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
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
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaItem.DrmConfiguration
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.CookieHandler
import java.net.CookieManager
import java.net.CookiePolicy
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {
    private var player: ExoPlayer? = null
    private var drawerLayout: DrawerLayout? = null
    private var epgContainer: LinearLayout? = null
    private var rvGroups: RecyclerView? = null
    private var rvChannels: RecyclerView? = null
    private var rvRecents: RecyclerView? = null
    private var searchContainer: LinearLayout? = null
    private var etSearch: EditText? = null
    private var rvSearchResults: RecyclerView? = null
    private var tvSearchCount: TextView? = null

    private lateinit var repo: Repository
    private var allData = mapOf<String, List<Channel>>()
    private var allChannelsFlat = listOf<Channel>()
    private var currentChannel: Channel? = null
    private var numBuffer = ""
    private val handler = Handler(Looper.getMainLooper())
    private val PICK_FILE = 101

    // --- ROBUST RESOLVER CLIENT ---
    private val resolverClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // NUCLEAR BYPASS
        val policy = StrictMode.ThreadPolicy.Builder().permitAll().build()
        StrictMode.setThreadPolicy(policy)

        // COOKIE MANAGER
        val cookieManager = CookieManager()
        cookieManager.setCookiePolicy(CookiePolicy.ACCEPT_ORIGINAL_SERVER)
        CookieHandler.setDefault(cookieManager)
        
        try {
            setContentView(R.layout.activity_main)
            repo = Repository(this)
            
            initializePlayer()

            drawerLayout = findViewById(R.id.drawerLayout)
            epgContainer = findViewById(R.id.epgContainer)
            rvGroups = findViewById(R.id.rvGroups)
            rvChannels = findViewById(R.id.rvChannels)
            rvRecents = findViewById(R.id.rvRecents)

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
        // --- PLAYER CONFIG ---
        val httpFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent("TiviMate/4.7.0") 
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(15000)
        
        val dataSourceFactory = DefaultDataSource.Factory(this, httpFactory)
        
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(dataSourceFactory))
            .build()
            
        val playerView = findViewById<PlayerView>(R.id.playerView)
        playerView?.player = player
        playerView?.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
    }

    // --- SMART HYBRID RESOLVER (HEAD -> Fallback to GET) ---
    private suspend fun resolveRedirects(url: String): String {
        return withContext(Dispatchers.IO) {
            // Optimization: Skip valid static files
            if ((url.contains(".ts") || url.contains(".m3u8")) && !url.contains(".php") && !url.contains("fake=")) {
                return@withContext url
            }

            // ATTEMPT 1: TRY HEAD (Preferred)
            // It is faster and works for most redirects without downloading the body.
            try {
                val reqHead = Request.Builder()
                    .url(url)
                    .head()
                    .header("User-Agent", "TiviMate/4.7.0")
                    .build()
                
                val resp = resolverClient.newCall(reqHead).execute()
                if (resp.isSuccessful || resp.isRedirect) {
                    val finalUrl = resp.request.url.toString()
                    resp.close()
                    return@withContext finalUrl
                }
                resp.close()
                // If HEAD returns an error code (like 405 Method Not Allowed), fall through to GET
            } catch (e: Exception) {
                // If HEAD fails (network error or crash), just swallow error and try GET
            }

            // ATTEMPT 2: TRY GET (Fallback)
            // Robust method that mimics a browser fully.
            try {
                val reqGet = Request.Builder()
                    .url(url)
                    .get()
                    .header("User-Agent", "TiviMate/4.7.0")
                    .build()
                    
                val resp = resolverClient.newCall(reqGet).execute()
                val finalUrl = resp.request.url.toString()
                resp.close()
                return@withContext finalUrl
            } catch (e: Exception) {
                // If both fail, return original URL
                return@withContext url
            }
        }
    }

    private fun play(c: Channel) {
        lifecycleScope.launch {
            try {
                currentChannel = c
                repo.addRecent(c)
                updateRecentList()
                
                // 1. RESOLVE THE URL (Smart Hybrid: HEAD -> GET)
                val realUrl = resolveRedirects(c.url)
                
                // 2. FORCE HLS MIME TYPE
                val builder = MediaItem.Builder()
                    .setUri(Uri.parse(realUrl))
                    .setMimeType(MimeTypes.APPLICATION_M3U8) 
                
                if (c.drmLicense != null) {
                    builder.setDrmConfiguration(DrmConfiguration.Builder(C.WIDEVINE_UUID).setLicenseUri(c.drmLicense).build())
                }
                
                player?.setMediaItem(builder.build())
                player?.prepare()
                player?.play()
                
                epgContainer?.visibility = View.GONE
            } catch (e: Exception) { 
                Toast.makeText(this@MainActivity, "Playback Error: ${e.message}", Toast.LENGTH_SHORT).show() 
            }
        }
    }
    
    // --- UI HELPERS ---
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
                    updateRecentList()
                } else {
                    Toast.makeText(this@MainActivity, "No channels found!", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) { showError("Playlist Error", e.message ?: "Unknown") }
        }
    }

    private fun updateSettingsDrawer() {
        val root = findViewById<LinearLayout>(R.id.settingsDrawer) ?: return
        root.removeAllViews()
        val title = TextView(this); title.text="SETTINGS"; title.textSize=22f; title.setTextColor(Color.CYAN); root.addView(title)
        
        fun btn(t: String, tag: String, a: () -> Unit) {
            val b = Button(this); b.text = t; b.tag = tag; b.setTextColor(Color.WHITE)
            b.setBackgroundResource(android.R.drawable.btn_default)
            b.isFocusable = true
            b.isFocusableInTouchMode = true
            b.setOnClickListener { a() }
            root.addView(b)
        }
        
        btn("Search Channels", "first") { openSearch(); drawerLayout?.closeDrawers() }
        btn("+ Add URL Playlist", "other") { showAddUrlDialog() }
        btn("+ Add Local File", "other") { openFilePicker() }
        
        val sub = TextView(this); sub.text="PLAYLISTS"; sub.textSize=18f; sub.setTextColor(Color.LTGRAY); sub.setPadding(0,20,0,10); root.addView(sub)
        repo.getPlaylists().forEach { p -> btn(p.name, "list") { repo.setActivePlaylist(p.id); loadData(p); drawerLayout?.closeDrawers() } }
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

    private fun updateRecentList() {
        val recents = repo.getRecents()
        if (recents.isNotEmpty()) {
            findViewById<View>(R.id.recentContainer)?.visibility = View.GONE 
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

    override fun onKeyDown(k: Int, e: KeyEvent?): Boolean {
        if (k in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
            numBuffer += (k - KeyEvent.KEYCODE_0)
            findViewById<TextView>(R.id.tvOverlayNum)?.let { tv ->
                tv.text = numBuffer; tv.visibility = View.VISIBLE
                handler.removeCallbacksAndMessages(null)
                handler.postDelayed({
                    allChannelsFlat.find { it.number.toString() == numBuffer }?.let { play(it) } ?: Toast.makeText(this, "Channel $numBuffer not found", Toast.LENGTH_SHORT).show()
                    numBuffer = ""; tv.visibility = View.GONE
                }, 2000)
            }
            return true
        }

        if (epgContainer?.visibility != View.VISIBLE && searchContainer?.visibility != View.VISIBLE) {
            when(k) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> {
                    currentChannel?.let { curr ->
                        val idx = allChannelsFlat.indexOf(curr)
                        if (idx > 0) play(allChannelsFlat[idx - 1])
                    }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                    if (k == KeyEvent.KEYCODE_CHANNEL_DOWN) {
                        currentChannel?.let { curr ->
                            val idx = allChannelsFlat.indexOf(curr)
                            if (idx < allChannelsFlat.size - 1) play(allChannelsFlat[idx + 1])
                        }
                    } else {
                        findViewById<View>(R.id.recentContainer)?.visibility = View.VISIBLE
                        rvRecents?.requestFocus()
                    }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { 
                    drawerLayout?.openDrawer(Gravity.END)
                    updateSettingsDrawer()
                    val root = findViewById<LinearLayout>(R.id.settingsDrawer)
                    root?.postDelayed({ root.findViewWithTag<View>("first")?.requestFocus() }, 100)
                    return true 
                }
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MENU -> {
                    epgContainer?.visibility = View.VISIBLE
                    rvGroups?.requestFocus()
                    return true
                }
            }
        } 
        
        if (k == KeyEvent.KEYCODE_BACK) {
            if (searchContainer?.visibility == View.VISIBLE) { closeSearch(); return true }
            if (drawerLayout?.isDrawerOpen(Gravity.END) == true) { drawerLayout?.closeDrawers(); return true }
            if (epgContainer?.visibility == View.VISIBLE) { epgContainer?.visibility = View.GONE; return true }
        }

        return super.onKeyDown(k, e)
    }
    
    override fun onDestroy() { super.onDestroy(); player?.release() }
}
