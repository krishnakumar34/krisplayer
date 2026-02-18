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
import android.util.Base64
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
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.LocalMediaDrmCallback
import androidx.media3.exoplayer.drm.HttpMediaDrmCallback
import androidx.media3.exoplayer.hls.DefaultHlsExtractorFactory
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.upstream.DefaultAllocator
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.CookieHandler
import java.net.CookieManager
import java.net.CookiePolicy
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.net.ssl.*

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

    // --- SHARED COOKIES ---
    private class BridgeCookieJar : CookieJar {
        private val manager = java.net.CookieManager.getDefault() as java.net.CookieManager
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val cookieStore = manager.cookieStore
            cookies.forEach { 
                val javaCookie = java.net.HttpCookie(it.name, it.value)
                javaCookie.domain = it.domain
                javaCookie.path = it.path
                cookieStore.add(url.toUri(), javaCookie)
            }
        }
        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val cookieStore = manager.cookieStore
            return cookieStore.get(url.toUri()).map { 
                Cookie.Builder().name(it.name).value(it.value).domain(url.host).path(url.encodedPath).build() 
            }
        }
    }

    private fun getUnsafeOkHttpClient(): OkHttpClient {
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })
        val sslContext = SSLContext.getInstance("SSL")
        sslContext.init(null, trustAllCerts, SecureRandom())
        return OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            .cookieJar(BridgeCookieJar())
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
    private val resolverClient by lazy { getUnsafeOkHttpClient() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().permitAll().build())
        val cookieManager = CookieManager()
        cookieManager.setCookiePolicy(CookiePolicy.ACCEPT_ALL)
        CookieHandler.setDefault(cookieManager)
        
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
        val renderersFactory = DefaultRenderersFactory(this).setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        val loadControl = DefaultLoadControl.Builder()
            .setAllocator(DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE))
            .setBufferDurationsMs(50000, 50000, 1500, 3000)
            .build()

        player = ExoPlayer.Builder(this, renderersFactory)
            .setLoadControl(loadControl)
            .build()
        
        player?.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Toast.makeText(this@MainActivity, "Player Error: ${error.message}", Toast.LENGTH_LONG).show()
                error.printStackTrace()
            }
        })

        val playerView = findViewById<PlayerView>(R.id.playerView)
        playerView?.player = player
        playerView?.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
    }

        private suspend fun resolveUrl(url: String): Pair<String, String?> {
        return withContext(Dispatchers.IO) {
            try {
                val req = Request.Builder().url(url).head().build()
                var resp = resolverClient.newCall(req).execute()
                if (!resp.isSuccessful || resp.code == 405) {
                    resp.close()
                    val getReq = Request.Builder().url(url).get().build()
                    resp = resolverClient.newCall(getReq).execute()
                }
                val finalUrl = resp.request.url.toString()
                val contentType = resp.header("Content-Type", "")?.lowercase() ?: ""
                resp.close()
                
                val mime = when {
                     finalUrl.contains(".mpd") || contentType.contains("dash+xml") -> MimeTypes.APPLICATION_MPD
                     finalUrl.contains(".m3u8") || contentType.contains("mpegurl") -> MimeTypes.APPLICATION_M3U8
                     finalUrl.contains(".ts") ||  finalUrl.matches(Regex(".*\\/[0-9]+(\\?.*)?$")) || contentType.contains("mp2t") -> MimeTypes.VIDEO_MP2T
                     else -> null
                }
                return@withContext Pair(finalUrl, mime)
            } catch (e: Exception) {
                return@withContext Pair(url, null)
            }
        }
    }

    private fun play(c: Channel) {
        repo.saveLastPlayed(c.id)
        
        lifecycleScope.launch {
            try {
                currentChannel = c
                repo.addRecent(c)
                showChannelInfo(c)

                val (finalUrl, detectedMime) = resolveUrl(c.url)
                val mimeType = if (detectedMime != null) detectedMime 
                               else if (finalUrl.matches(Regex(".*\\/[0-9]+(\\?.*)?$"))) MimeTypes.VIDEO_MP2T 
                               else MimeTypes.APPLICATION_M3U8
                
                val okHttpFactory = OkHttpDataSource.Factory(getUnsafeOkHttpClient())
                val dataSourceFactory = DefaultDataSource.Factory(this@MainActivity, okHttpFactory)

                if (mimeType == MimeTypes.APPLICATION_MPD) {
                    var drmSessionManager: DefaultDrmSessionManager? = null

                    if (c.drmLicense != null) {
                        if (c.drmLicense.contains(":") && !c.drmLicense.startsWith("http") && c.drmLicense.length > 20) {
                            try {
                                val parts = c.drmLicense.split(":")
                                if (parts.size == 2) {
                                    val kidHex = parts[0]
                                    val keyHex = parts[1]
                                    val kidB64 = base64UrlEncode(hexStringToByteArray(kidHex))
                                    val keyB64 = base64UrlEncode(hexStringToByteArray(keyHex))
                                    val drmJson = """{"keys":[{"kty":"oct","k":"$keyB64","kid":"$kidB64"}],"type":"temporary"}"""
                                    val drmCallback = LocalMediaDrmCallback(drmJson.toByteArray())
                                    drmSessionManager = DefaultDrmSessionManager.Builder()
                                        .setUuidAndExoMediaDrmProvider(C.CLEARKEY_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
                                        .setMultiSession(false)
                                        .build(drmCallback)
                                }
                            } catch (e: Exception) { e.printStackTrace() }
                        } 
                        else {
                            val mediaDrmCallback = HttpMediaDrmCallback(c.drmLicense, okHttpFactory)
                            drmSessionManager = DefaultDrmSessionManager.Builder()
                                .setUuidAndExoMediaDrmProvider(C.WIDEVINE_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
                                .build(mediaDrmCallback)
                        }
                    }

                    val mediaItem = MediaItem.Builder().setUri(Uri.parse(finalUrl)).setMimeType(MimeTypes.APPLICATION_MPD).build()
                    val sourceFactory = DashMediaSource.Factory(dataSourceFactory)
                    if (drmSessionManager != null) {
                        sourceFactory.setDrmSessionManagerProvider { drmSessionManager }
                    }
                    player?.setMediaSource(sourceFactory.createMediaSource(mediaItem))

                } else if (mimeType == MimeTypes.APPLICATION_M3U8) {
                    val hlsExtractorFactory = DefaultHlsExtractorFactory(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES, true)
                    val builder = MediaItem.Builder().setUri(Uri.parse(finalUrl)).setMimeType(MimeTypes.APPLICATION_M3U8)
                    if (c.drmLicense != null && c.drmLicense.startsWith("http")) {
                        builder.setDrmConfiguration(MediaItem.DrmConfiguration.Builder(C.WIDEVINE_UUID).setLicenseUri(c.drmLicense).build())
                    }
                    val mediaSource = HlsMediaSource.Factory(dataSourceFactory)
                        .setExtractorFactory(hlsExtractorFactory)
                        .setAllowChunklessPreparation(false)
                        .createMediaSource(builder.build())
                    player?.setMediaSource(mediaSource)
                } else {
                    val builder = MediaItem.Builder().setUri(Uri.parse(finalUrl)).setMimeType(mimeType)
                    player?.setMediaItem(builder.build())
                }
                
                player?.prepare()
                player?.play()
            } catch (e: Exception) { Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show() }
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
                    val firstGroup = allData.keys.first()
                    rvChannels?.adapter = ChannelAdapter(allData[firstGroup] ?: emptyList(), 
                        { 
                            play(it)
                            epgContainer?.visibility = View.GONE 
                        }, 
                        { toggleFav(it) }, 
                        { focusGroupList() }
                    )
                    
                    rvChannels?.setOnKeyListener { _, keyCode, event ->
                        if (event.action == KeyEvent.ACTION_DOWN) {
                            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) { rvGroups?.requestFocus(); true }
                            else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) true else false
                        } else false
                    }

                    val lastId = repo.getLastPlayed()
                    val lastChannel = if (lastId != null) allChannelsFlat.find { it.id == lastId } else null
                    
                    if (lastChannel != null) {
                        play(lastChannel)
                        epgContainer?.visibility = View.GONE
                    } else {
                        epgContainer?.visibility = View.VISIBLE
                        rvGroups?.requestFocus()
                    }
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
        rvSearchResults?.adapter = ChannelAdapter(results, { c -> 
            play(c)
            closeSearch()
            epgContainer?.visibility = View.GONE 
        }, { c -> toggleFav(c) }, {})
    }

    private fun openSearch() { searchContainer?.visibility = View.VISIBLE; epgContainer?.visibility = View.GONE; etSearch?.requestFocus(); showKeyboard() }
    private fun closeSearch() { searchContainer?.visibility = View.GONE; etSearch?.text?.clear(); hideKeyboard() }
    private fun showKeyboard() { val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager; etSearch?.let { imm.showSoftInput(it, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT) } }
    private fun hideKeyboard() { val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager; etSearch?.let { imm.hideSoftInputFromWindow(it.windowToken, 0) } }

    private fun toggleFav(c: Channel) { repo.toggleFav(c.id); c.isFavorite = !c.isFavorite; (rvChannels?.adapter as? ChannelAdapter)?.notifyDataSetChanged(); (rvSearchResults?.adapter as? ChannelAdapter)?.notifyDataSetChanged() }
    fun focusGroupList() { rvGroups?.requestFocus() }
    fun focusChannelList() { rvChannels?.requestFocus() }

    private fun syncEpgWithCurrentChannel() {
        epgContainer?.visibility = View.VISIBLE
        val curr = currentChannel ?: return
        
        val targetGroup = if (allData.containsKey("All Channels")) "All Channels" else curr.group
        val groupIndex = allData.keys.indexOf(targetGroup)
        
        if (groupIndex != -1) {
            (rvGroups?.adapter as? GroupAdapter)?.select(groupIndex)
            rvGroups?.scrollToPosition(groupIndex)
            
            val adapter = rvChannels?.adapter as? ChannelAdapter
            val channels = adapter?.getItems() ?: emptyList()
            val channelIndex = channels.indexOfFirst { it.id == curr.id }
            
            if (channelIndex != -1) {
                (rvChannels?.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(channelIndex, 100)
                rvChannels?.post {
                    val vh = rvChannels?.findViewHolderForAdapterPosition(channelIndex)
                    vh?.itemView?.requestFocus()
                }
            } else {
                rvGroups?.requestFocus()
            }
        } else {
            rvGroups?.requestFocus()
        }
    }

    override fun onKeyDown(k: Int, e: KeyEvent?): Boolean {
        if (drawerLayout?.isDrawerOpen(Gravity.END) == true) {
            if (k == KeyEvent.KEYCODE_BACK) { drawerLayout?.closeDrawers(); return true }
            return super.onKeyDown(k, e) 
        }
        if (searchContainer?.visibility == View.VISIBLE) {
            if (k == KeyEvent.KEYCODE_BACK) { closeSearch(); return true }
            return super.onKeyDown(k, e)
        }
        
        if (k == KeyEvent.KEYCODE_BACK) {
            if (epgContainer?.visibility == View.VISIBLE) {
                finish() 
                return true
            } else {
                syncEpgWithCurrentChannel()
                return true
            }
        }
        
        if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_MENU) {
            if (epgContainer?.visibility != View.VISIBLE) {
                syncEpgWithCurrentChannel()
                return true
            }
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
            }
        } 
        
        return super.onKeyDown(k, e)
    }
    
    private fun hexStringToByteArray(s: String): ByteArray {
        val len = s.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(s[i], 16) shl 4) + Character.digit(s[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }
    
    private fun base64UrlEncode(b: ByteArray): String {
        return Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP).trim()
    }
    
    override fun onDestroy() { super.onDestroy(); player?.release() }
}

    
