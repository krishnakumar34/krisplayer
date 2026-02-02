package com.iptv.player

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
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
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
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
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultAllocator
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import java.net.CookieHandler
import java.net.CookieManager
import java.net.CookiePolicy
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.*
import androidx.media3.exoplayer.source.hls.HlsMediaSource


class MainActivity : AppCompatActivity() {
    private var player: ExoPlayer? = null
    private var drawerLayout: DrawerLayout? = null
    private var epgContainer: LinearLayout? = null
    private var rvGroups: RecyclerView? = null
    private var rvChannels: RecyclerView? = null
    private var searchContainer: LinearLayout? = null
    private var etSearch: EditText? = null
    private var rvSearchResults: RecyclerView? = null
    private var tvSearchCount: TextView? = null
    private var tvChannelInfo: TextView? = null

    private lateinit var repo: Repository
    private var allData = mapOf<String, List<Channel>>()
    private var allChannelsFlat = listOf<Channel>()
    private var currentChannel: Channel? = null
    private var numBuffer = ""
    private val handler = Handler(Looper.getMainLooper())
    private val PICK_FILE = 101

    // --- GLOBAL UNSAFE SSL (MPV STYLE) ---
    // Forces the entire Android OS to trust every certificate for this app.
    private fun applyGlobalTrustAllSSL() {
        try {
            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            })
            val sslContext = SSLContext.getInstance("SSL")
            sslContext.init(null, trustAllCerts, SecureRandom())
            HttpsURLConnection.setDefaultSSLSocketFactory(sslContext.socketFactory)
            HttpsURLConnection.setDefaultHostnameVerifier { _, _ -> true }
        } catch (e: Exception) { e.printStackTrace() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Allow Network on Main Thread (Essential for some IPTV redirects)
        val policy = StrictMode.ThreadPolicy.Builder().permitAll().build()
        StrictMode.setThreadPolicy(policy)

        // --- GLOBAL COOKIE MANAGER (ACCEPT ALL) ---
        // Changed to ACCEPT_ALL. This fixes 301 redirects that drop cookies.
        val cookieManager = CookieManager()
        cookieManager.setCookiePolicy(CookiePolicy.ACCEPT_ALL)
        CookieHandler.setDefault(cookieManager)
        
        applyGlobalTrustAllSSL()
        
        try {
            setContentView(R.layout.activity_main)
            repo = Repository(this)
            setupChannelInfoOverlay()
            initializePlayer()

            drawerLayout = findViewById(R.id.drawerLayout)
            epgContainer = findViewById(R.id.epgContainer)
            rvGroups = findViewById(R.id.rvGroups)
            rvChannels = findViewById(R.id.rvChannels)
            
            rvGroups?.layoutManager = LinearLayoutManager(this)
            rvChannels?.layoutManager = LinearLayoutManager(this)

            setupSearchUI()
            updateSettingsDrawer()

            val active = repo.getActivePlaylist()
            if (active != null) loadData(active) else showWelcomeDialog()
        } catch (e: Exception) { e.printStackTrace(); showError("Init Error", "${e.message}") }
    }

    private fun setupChannelInfoOverlay() {
        val root = findViewById<FrameLayout>(android.R.id.content) ?: return
        tvChannelInfo = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 28f; setTypeface(null, Typeface.BOLD)
            setShadowLayer(6f, 3f, 3f, Color.BLACK); setBackgroundColor(Color.parseColor("#99000000"))
            setPadding(40, 20, 60, 20); visibility = View.GONE
        }
        val params = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM or Gravity.START; setMargins(50, 0, 0, 50)
        }
        addContentView(tvChannelInfo, params)
    }

    private fun showChannelInfo(c: Channel) {
        tvChannelInfo?.apply {
            text = "${c.number}  |  ${c.name}"; visibility = View.VISIBLE
            handler.removeCallbacksAndMessages("HIDE_INFO")
            handler.postAtTime({ visibility = View.GONE }, "HIDE_INFO", android.os.SystemClock.uptimeMillis() + 4000)
        }
    }


        private fun initializePlayer() {
        // 1. MASSIVE BUFFER (50MB) - Stops stuttering
        val loadControl = DefaultLoadControl.Builder()
            .setAllocator(DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE))
            .setBufferDurationsMs(50000, 50000, 2500, 5000)
            .build()

        // 2. HTTP FACTORY (Native Redirects Only)
        // We use ExoPlayer's native redirect handling. 
        // Because we set Global SSL/Cookies in onCreate, this works perfectly.
        val httpFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent("TiviMate/4.7.0") 
            .setConnectTimeoutMs(30000)
            .setReadTimeoutMs(30000)
            .setKeepPostFor302Redirects(true)
            .setDefaultRequestProperties(mapOf(
                "Icy-MetaData" to "1",
                "Connection" to "keep-alive",
                "Accept" to "*/*"
            ))
        
        val dataSourceFactory = DefaultDataSource.Factory(this, httpFactory)
        val hlsFactory = HlsMediaSource.Factory(httpFactory)
    .setAllowChunklessPreparation(true)
    
        
        player = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(hlsFactory)
            .build()
            
        val playerView = findViewById<PlayerView>(R.id.playerView)
        playerView?.player = player
        playerView?.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
    }

    private fun play(c: Channel) {
        // NO COROUTINE NEEDED. We don't resolve manually anymore.
        // We pass the RAW URL to ExoPlayer immediately.
        try {
            currentChannel = c
            repo.addRecent(c)
            showChannelInfo(c)
            
            // Pass the RAW URL. Do NOT resolve it manually.
            val rawUrl = c.url 
            val builder = MediaItem.Builder().setUri(Uri.parse(rawUrl))
            val lowerUrl = rawUrl.lowercase()
            
            // --- AGGRESSIVE HINTING (Tell ExoPlayer what it is) ---
            
            // 1. If it looks like a playlist (M3U8 / PHP), force HLS
            if (lowerUrl.contains(".m3u8") || lowerUrl.contains(".php") || lowerUrl.contains("mode=hls")) {
                builder.setMimeType(MimeTypes.APPLICATION_M3U8)
            } 
            // 2. If it looks like a stream file (TS/MPEG/MKV), force TS
            else if (lowerUrl.contains(".ts") || lowerUrl.contains(".mpeg") || lowerUrl.contains(".mpg") || lowerUrl.contains(".mkv")) {
                builder.setMimeType(MimeTypes.VIDEO_MP2T)
            }
            // 3. If it is a numeric ID (Mystery Link), force TS
            else if (rawUrl.matches(Regex(".*\\/[0-9]+(\\?.*)?$"))) {
                builder.setMimeType(MimeTypes.VIDEO_MP2T)
            }
            // 4. Fallback: If no match, ExoPlayer will probe it (MPV behavior)
            
            if (c.drmLicense != null) {
                builder.setDrmConfiguration(DrmConfiguration.Builder(C.WIDEVINE_UUID).setLicenseUri(c.drmLicense).build())
            }
            
            player?.setMediaItem(builder.build())
            player?.prepare()
            player?.play()
            epgContainer?.visibility = View.GONE
        } catch (e: Exception) { 
            Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show() 
        }
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
                
                (rvChannels?.adapter as? ChannelAdapter)?.update(emptyList(), rvChannels)

                rvGroups?.adapter = GroupAdapter(allData.keys.toList(), { group ->
                    val channels = allData[group] ?: emptyList()
                    (rvChannels?.adapter as? ChannelAdapter)?.update(channels, rvChannels)
                }, { focusChannelList() })
                
                if (allData.isNotEmpty()) {
                    val first = allData.keys.first()
                    rvChannels?.adapter = ChannelAdapter(allData[first] ?: emptyList(), { play(it) }, { toggleFav(it) }, { focusGroupList() })
                    
                    rvChannels?.setOnKeyListener { _, keyCode, event ->
                        if (event.action == KeyEvent.ACTION_DOWN) {
                            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) { rvGroups?.requestFocus(); true }
                            else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) true else false
                        } else false
                    }
                    epgContainer?.visibility = View.VISIBLE
                    rvGroups?.requestFocus()
                } else { Toast.makeText(this@MainActivity, "No channels!", Toast.LENGTH_LONG).show() }
            } catch (e: Exception) { showError("Playlist Error", e.message ?: "Unknown") }
        }
    }

    private fun updateSettingsDrawer() {
        val root = findViewById<LinearLayout>(R.id.settingsDrawer) ?: return
        root.removeAllViews()
        val title = TextView(this); title.text="SETTINGS"; title.textSize=22f; title.setTextColor(Color.CYAN)
        title.isFocusable = false; root.addView(title)
        
        fun btn(t: String, tag: String, a: () -> Unit) {
            val b = Button(this); b.text = t; b.tag = tag; b.setTextColor(Color.WHITE)
            b.setBackgroundResource(android.R.drawable.btn_default)
            b.isFocusable = true; b.isFocusableInTouchMode = true
            b.setOnClickListener { a() }
            root.addView(b)
        }
        btn("Search Channels", "first") { openSearch(); drawerLayout?.closeDrawers() }
        btn("+ Add URL Playlist", "other") { showAddUrlDialog() }
        btn("+ Add Local File", "other") { openFilePicker() }
        
        val sub = TextView(this); sub.text="PLAYLISTS"; sub.textSize=18f; sub.setTextColor(Color.LTGRAY); sub.setPadding(0,20,0,10)
        sub.isFocusable = false; root.addView(sub)
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
        try { val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type="*/*" }; startActivityForResult(intent, PICK_FILE) } catch (e: Exception) {}
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
        etSearch?.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) { rvSearchResults?.requestFocus(); true } else false
        }
        etSearch?.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) { rvSearchResults?.requestFocus(); true } else false
        }
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
        if (drawerLayout?.isDrawerOpen(Gravity.END) == true) {
            if (k == KeyEvent.KEYCODE_BACK) { drawerLayout?.closeDrawers(); return true }
            return super.onKeyDown(k, e) 
        }
        if (searchContainer?.visibility == View.VISIBLE) {
            if (k == KeyEvent.KEYCODE_BACK) { closeSearch(); return true }
            return super.onKeyDown(k, e)
        }
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
        if (epgContainer?.visibility != View.VISIBLE) {
            when(k) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> {
                    currentChannel?.let { curr ->
                        val idx = allChannelsFlat.indexOf(curr)
                        if (idx < allChannelsFlat.size - 1) play(allChannelsFlat[idx + 1])
                    }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                    currentChannel?.let { curr ->
                        val idx = allChannelsFlat.indexOf(curr)
                        if (idx > 0) play(allChannelsFlat[idx - 1])
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
        if (k == KeyEvent.KEYCODE_BACK) { if (epgContainer?.visibility == View.VISIBLE) { epgContainer?.visibility = View.GONE; return true } }
        return super.onKeyDown(k, e)
    }
    
    override fun onDestroy() { super.onDestroy(); player?.release() }
}

    
    
