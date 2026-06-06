package com.poobi.tvbrowser

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.*
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.brave.adblock.AdBlockUtils
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection

data class AutoplayProfile(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val urlPatterns: List<String>,
    val script: String,
    val useScript: Boolean = true,
    val selectors: List<String> = emptyList()
)

@SuppressLint("SetTextI18n")
class MainActivity : AppCompatActivity() {

    private lateinit var webContainer: FrameLayout
    private lateinit var tabsContainer: LinearLayout
    private lateinit var contextMenu: LinearLayout
    private lateinit var rootLayout: FrameLayout

    private lateinit var mainTabsLayout: LinearLayout
    private lateinit var btnTabBrowser: Button
    private lateinit var btnTabStreams: Button
    private lateinit var streamsScreenLayout: LinearLayout
    private lateinit var streamsSearchBarLayout: LinearLayout
    private lateinit var streamsSearchInput: EditText
    private lateinit var streamsSearchBtn: ImageButton
    private lateinit var streamsProgress: ProgressBar
    private lateinit var subtitlesProgress: ProgressBar
    private lateinit var subtitlesStatus: TextView
    private lateinit var streamsCountText: TextView
    private lateinit var streamsResultsContainer: LinearLayout
    private lateinit var streamsResultsScroll: ScrollView
    private lateinit var btnStreamsBack: ImageButton
    private lateinit var btnStreamsStop: Button
    private lateinit var btnStreamsSort: Button
    private lateinit var streamsHistoryContainer: LinearLayout
    private lateinit var btnStreamsClearHistory: Button
    private lateinit var streamsHistoryLayout: LinearLayout
    private lateinit var streamsRecentPlayedLayout: LinearLayout
    private lateinit var streamsRecentPlayedContainer: LinearLayout
    private lateinit var btnStreamsClearRecentPlayed: Button

    private lateinit var tvSelectionLayout: LinearLayout
    private lateinit var btnTvBack: ImageButton
    private lateinit var tvShowTitle: TextView
    private lateinit var tvShowPoster: ImageView
    private lateinit var tvShowOverview: TextView
    private lateinit var tvSeasonSpinner: Spinner
    private lateinit var tvEpisodeContainer: LinearLayout

    private var lastSearchResults: JSONArray? = null
    private var lastScrapedItem: JSONObject? = null
    private var lastScrapedSeason: Int? = null
    private var lastScrapedEpisode: Int? = null
    private var currentStreamingTitle: String? = null
    private var lastVideoTitle: String? = null
    private var currentSources: JSONArray? = null
    private var webViews = mutableListOf<WebView>()
    private var webViewHosts = java.util.WeakHashMap<WebView, String>()
    private var currentTabIndex = -1
    private val currentWebView: WebView? get() = if (currentTabIndex in webViews.indices) webViews[currentTabIndex] else null

    private lateinit var cursor: ImageView
    private lateinit var customViewContainer: FrameLayout
    private lateinit var nativeVideoView: PlayerView
    private lateinit var homeScreenLayout: LinearLayout
    private lateinit var topBarLayout: LinearLayout
    private lateinit var historyContainer: LinearLayout
    private lateinit var favContainer: LinearLayout

    private lateinit var homeUrlInput: EditText
    private lateinit var topUrlInput: EditText
    private lateinit var topFavBtn: ImageButton
    private lateinit var topAutoPlayBtn: ImageButton
    private lateinit var topRecordBtn: ImageButton
    private lateinit var topForwardBtn: ImageButton

    private lateinit var openTabsContainer: LinearLayout
    private lateinit var btnRestoreTabs: Button

    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var mCustomView: View? = null
    private var exoPlayer: ExoPlayer? = null

    private lateinit var prefs: SharedPreferences
    private var silentPopupBlock = true
    private var extractVideoPref = 0
    private var videoTriggerPref = 1 // 0: Auto, 1: Fullscreen
    private var historyLimit = 20
    private var isLightTheme = false
    private var currentHost = ""
    private var isBrowsing = false

    private var isExtractionActive = false
    private var lastExtractedUrl = ""

    private var historyIconPref = 0
    private var bookmarkIconPref = 0
    private var navigationModePref = 0 // 0: Cursor, 1: D-pad
    private var autoSubPref = 0 // 0: Ask, 1: Automatic, 2: Never
    private var autoSubCount = 1
    private var autoSubWaitPref = 0 // 0: Stop, 1: Ask, 2: Progressive
    private var subRetentionDays = 3
    private var isDownloadingAutoSubs = false
    private var exoFallbackPref = 0
    private var isInteractingWithSources = false
    private var lastSubtitledItemKey: String? = null
    private var cachedSubtitleResults = mutableMapOf<String, JSONArray>()
    private val cachedTvDetails = mutableMapOf<Int, JSONObject>()
    private val cachedSeasons = mutableMapOf<Int, JSONArray>()
    private val cachedEpisodes = mutableMapOf<String, JSONArray>()
    private var isSelectionMode = false
    private var pendingNextEpisode = false
    private var lastSelectedSource: JSONObject? = null
    private var upNextPopup: View? = null
    private var isUpNextDismissed = false

    private lateinit var downloadsContainer: LinearLayout
    private lateinit var topDownloadsBtn: ImageButton

    private var embeddedSubsEnabled = true
    private var scrollTopbarEnabled = true
    private val interceptedSubtitleUrls = mutableMapOf<String, Map<String, String>>()

    private var lastClickedUrl: String? = null
    private var okDownTime = 0L
    private val LONG_PRESS_THRESHOLD = 600L
    private var isLongPressing = false
    private var isBackHandled = false

    private var lastSeekTime = 0L
    private var seekIncrement = 5000L
    private var lastSeekDirection = 0
    private var seekRunnable: Runnable? = null
    private val SEEK_INTERVAL = 200L

    private var clickjackPref = true

    private var bookmarkPage = 0
    private val ITEMS_PER_PAGE = 10

    private var isRecordingAutoplay = false
    private val recordedSelectors = mutableListOf<String>()

    private val interceptedMediaUrls = mutableMapOf<String, Map<String, String>>()

    private var cursorX = 500f
    private var cursorY = 500f
    private var cursorVelocityX = 0f
    private var cursorVelocityY = 0f
    private var scrollVelocityY = 0f

    private val MIN_VELOCITY = 0.5f
    private val MAX_CURSOR_VELOCITY = 40f
    private val MAX_SCROLL_VELOCITY = 100f
    private val ACCELERATION = 1.15f
    private val SCROLL_ACCELERATION = 1.08f

    private val keyStates = mutableMapOf<Int, Boolean>()
    private val movementHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var isMovementLoopRunning = false

    // Home Tab Elements
    private lateinit var homeTabBookmarks: Button
    private lateinit var homeTabTabs: Button
    private lateinit var homeTabHistory: Button
    private lateinit var homeTabDownloads: Button

    private lateinit var homePanelBookmarks: LinearLayout
    private lateinit var homePanelTabs: LinearLayout
    private lateinit var homePanelHistory: LinearLayout
    private lateinit var homePanelDownloads: LinearLayout
    private var activeHomeTab: Button? = null

    private val hideCursorRunnable = Runnable {
        if (isBrowsing && cursor.visibility == View.VISIBLE) {
            cursor.visibility = View.GONE
        }
    }

    private val movementRunnable = object : Runnable {
        override fun run() {
            if (updateMovement()) {
                movementHandler.postDelayed(this, 16)
            } else {
                isMovementLoopRunning = false
            }
        }
    }

    private val hoverCheckRunnable = object : Runnable {
        override fun run() {
            if (isBrowsing && cursor.visibility == View.VISIBLE) {
                checkHover()
                movementHandler.postDelayed(this, 250)
            }
        }
    }

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == android.app.DownloadManager.ACTION_DOWNLOAD_COMPLETE) {
                Toast.makeText(context, "Download finished!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        rootLayout = findViewById(R.id.root_layout)
        mainTabsLayout = findViewById(R.id.main_tabs_layout)
        btnTabBrowser = findViewById(R.id.btn_main_tab_browser)
        btnTabStreams = findViewById(R.id.btn_main_tab_streams)
        streamsScreenLayout = findViewById(R.id.streams_screen_layout)
        streamsSearchBarLayout = findViewById(R.id.streams_search_bar_layout)
        streamsSearchInput = findViewById(R.id.streams_search_input)
        streamsSearchBtn = findViewById(R.id.streams_search_btn)
        streamsProgress = findViewById(R.id.streams_progress)
        subtitlesProgress = findViewById(R.id.subtitles_progress)
        subtitlesStatus = findViewById(R.id.subtitles_status)
        streamsCountText = findViewById(R.id.streams_count_text)
        streamsResultsContainer = findViewById(R.id.streams_results_container)
        streamsResultsScroll = findViewById(R.id.streams_results_scroll)
        btnStreamsBack = findViewById(R.id.btn_streams_back)
        btnStreamsStop = findViewById(R.id.btn_streams_stop)
        btnStreamsSort = findViewById(R.id.btn_streams_sort)
        streamsHistoryContainer = findViewById(R.id.streams_history_container)
        btnStreamsClearHistory = findViewById(R.id.btn_streams_clear_history)
        streamsHistoryLayout = findViewById(R.id.streams_history_layout)
        streamsRecentPlayedLayout = findViewById(R.id.streams_recent_played_layout)
        streamsRecentPlayedContainer = findViewById(R.id.streams_recent_played_container)
        btnStreamsClearRecentPlayed = findViewById(R.id.btn_streams_clear_recent_played)

        tvSelectionLayout = findViewById(R.id.tv_selection_layout)
        btnTvBack = findViewById(R.id.btn_tv_back)
        tvShowTitle = findViewById(R.id.tv_show_title)
        tvShowPoster = findViewById(R.id.tv_show_poster)
        tvShowOverview = findViewById(R.id.tv_show_overview)
        tvSeasonSpinner = findViewById(R.id.tv_season_spinner)
        tvEpisodeContainer = findViewById(R.id.tv_episode_container)

        webContainer = findViewById(R.id.web_container)
        tabsContainer = findViewById(R.id.tabs_container)
        contextMenu = findViewById(R.id.context_menu)

        cursor = findViewById(R.id.cursor)
        customViewContainer = findViewById(R.id.fullscreen_custom_content)
        customViewContainer.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        nativeVideoView = findViewById(R.id.native_video_view)
        homeScreenLayout = findViewById(R.id.home_screen_layout)
        topBarLayout = findViewById(R.id.top_bar_layout)
        homeUrlInput = findViewById(R.id.home_url_input)
        topUrlInput = findViewById(R.id.top_url_input)
        topFavBtn = findViewById(R.id.top_fav_btn)
        topAutoPlayBtn = findViewById(R.id.top_auto_play_btn)
        topRecordBtn = findViewById(R.id.top_record_btn)
        topForwardBtn = findViewById(R.id.top_forward_btn)

        historyContainer = findViewById(R.id.history_container)
        favContainer = findViewById(R.id.favorites_container)
        downloadsContainer = findViewById(R.id.downloads_container)
        openTabsContainer = findViewById(R.id.open_tabs_container)

        btnRestoreTabs = findViewById(R.id.btn_restore_tabs)
        topDownloadsBtn = findViewById(R.id.top_downloads_btn)

        // Init Home Tabs
        homeTabBookmarks = findViewById(R.id.home_tab_bookmarks)
        homeTabTabs = findViewById(R.id.home_tab_tabs)
        homeTabHistory = findViewById(R.id.home_tab_history)
        homeTabDownloads = findViewById(R.id.home_tab_downloads)

        homePanelBookmarks = findViewById(R.id.home_panel_bookmarks)
        homePanelTabs = findViewById(R.id.home_panel_tabs)
        homePanelHistory = findViewById(R.id.home_panel_history)
        homePanelDownloads = findViewById(R.id.home_panel_downloads)

        prefs = getSharedPreferences("BrowserSettings", Context.MODE_PRIVATE)
        AdBlockManager.init(this)

        setupButtons()
        setupHomeTabs()

        val filter = IntentFilter(android.app.DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(downloadReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(downloadReceiver, filter)
        }

        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) {
            if (checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
            }
        }

        checkStartupTabs()
        setupMainTabs()
        btnTabBrowser.isSelected = true
        initPython()
        refreshStreamsHistory()
        showHomeScreen()
        btnTabBrowser.post { btnTabBrowser.requestFocus() }
    }

    private fun initPython() {
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }
    }

    private fun setupMainTabs() {
        btnTabBrowser.setOnClickListener { switchMainTab(true) }
        btnTabStreams.setOnClickListener { switchMainTab(false) }

        btnTabBrowser.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
            if (hasFocus) switchMainTab(true)
        }
        btnTabStreams.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
            if (hasFocus) switchMainTab(false)
        }
        
        btnStreamsBack.setOnClickListener {
            if (streamsScreenLayout.visibility == View.VISIBLE) {
                // If we are in the middle of a TV scrape or looking at TV sources, go back to TV selection
                if (lastScrapedItem != null && lastScrapedItem!!.optString("media_type") == "tv" && lastScrapedSeason != null) {
                    streamsScreenLayout.visibility = View.GONE
                    showTvSelectionScreen(lastScrapedItem!!)
                    return@setOnClickListener
                }
                
                // If we are looking at search results
                if (lastSearchResults != null && streamsResultsContainer.childCount > 0 && 
                    streamsResultsContainer.getChildAt(0).findViewById<View>(R.id.card_detail) != null) {
                    goBackToHistory()
                } else if (lastSearchResults != null) {
                    displayStreamResults(lastSearchResults!!)
                    streamsSearchBarLayout.visibility = View.VISIBLE
                    btnStreamsBack.visibility = View.VISIBLE
                } else {
                    goBackToHistory()
                }
            } else {
                goBackToHistory()
            }
        }

        btnStreamsSort.setOnClickListener {
            showSortPriorityDialog()
        }

        streamsSearchBtn.setOnClickListener { performStreamSearch() }
        btnStreamsClearHistory.setOnClickListener {
            prefs.edit().putString("streams_search_history", "[]").apply()
            refreshStreamsHistory()
        }
        btnStreamsClearRecentPlayed.setOnClickListener {
            prefs.edit().putString("streams_recently_played", "[]").apply()
            refreshStreamsHistory()
            streamsSearchInput.requestFocus()
        }

        btnTvBack.setOnClickListener {
            tvSelectionLayout.visibility = View.GONE
            streamsScreenLayout.visibility = View.VISIBLE
            if (lastSearchResults != null) {
                displayStreamResults(lastSearchResults!!)
                streamsSearchBarLayout.visibility = View.VISIBLE
                streamsResultsScroll.visibility = View.VISIBLE
                btnStreamsBack.visibility = View.VISIBLE
            } else {
                goBackToHistory()
            }
        }
        streamsSearchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                performStreamSearch()
                true
            } else false
        }
    }

    private fun switchMainTab(isBrowser: Boolean) {
        btnTabBrowser.isSelected = isBrowser
        btnTabStreams.isSelected = !isBrowser
        
        if (isBrowser) {
            if (!isBrowsing) {
                homeScreenLayout.visibility = View.VISIBLE
                streamsScreenLayout.visibility = View.GONE
            }
        } else {
            homeScreenLayout.visibility = View.GONE
            streamsScreenLayout.visibility = View.VISIBLE
            refreshStreamsHistory()
            webContainer.visibility = View.GONE
            topBarLayout.visibility = View.GONE
            isBrowsing = false
            cursor.visibility = View.GONE

            // Resume scraping when returning to streams tab
            isInteractingWithSources = false
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val py = Python.getInstance()
                    val scraper = py.getModule("main")
                    scraper.callAttr("resume_scrape")
                } catch (e: Exception) {}
            }
        }
    }

    private fun goBackToHistory() {
        streamsResultsContainer.removeAllViews()
        streamsResultsScroll.visibility = View.GONE
        btnStreamsBack.visibility = View.GONE
        streamsSearchBarLayout.visibility = View.VISIBLE
        streamsCountText.visibility = View.GONE
        streamsCountText.text = ""
        refreshStreamsHistory()
        streamsSearchInput.post { streamsSearchInput.requestFocus() }
    }

    private fun performStreamSearch() {
        val query = streamsSearchInput.text.toString().trim()
        if (query.isEmpty()) return

        addToStreamsHistory(query)
        streamsProgress.visibility = View.VISIBLE
        streamsResultsContainer.removeAllViews()
        streamsCountText.visibility = View.GONE
        streamsCountText.text = ""
        streamsResultsScroll.visibility = View.VISIBLE
        btnStreamsBack.visibility = View.GONE
        streamsHistoryLayout.visibility = View.GONE
        
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(streamsSearchInput.windowToken, 0)

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val py = Python.getInstance()
                val scraper = py.getModule("main")
                val resultsJson = scraper.callAttr("search", query).toString()
                val results = JSONArray(resultsJson)
                lastSearchResults = results

                withContext(Dispatchers.Main) {
                    streamsProgress.visibility = View.GONE
                    btnStreamsBack.visibility = View.VISIBLE
                    displayStreamResults(results)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    streamsProgress.visibility = View.GONE
                    Toast.makeText(this@MainActivity, "Search error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun addToStreamsHistory(query: String) {
        val historyJson = prefs.getString("streams_search_history", "[]") ?: "[]"
        val array = JSONArray(historyJson)
        val newList = mutableListOf<String>()
        for (i in 0 until array.length()) newList.add(array.getString(i))
        
        newList.remove(query) // Remove if already exists to move to top
        newList.add(0, query)
        if (newList.size > 20) newList.removeAt(newList.size - 1)
        
        val newArray = JSONArray()
        newList.forEach { newArray.put(it) }
        prefs.edit().putString("streams_search_history", newArray.toString()).apply()
        refreshStreamsHistory()
    }

    private fun setupHistoryLongPress(view: View, progress: ProgressBar, query: String, isRecentPlayed: Boolean = false) {
        val progressHandler = android.os.Handler(android.os.Looper.getMainLooper())
        var startTime = 0L
        val updateProgress = object : Runnable {
            override fun run() {
                if (startTime == 0L) return
                val elapsed = System.currentTimeMillis() - startTime
                val progressValue = (elapsed / 10f).toInt()

                progress.progress = progressValue
                if (progressValue >= 100) {
                    val parent = view.parent as? ViewGroup
                    val index = parent?.indexOfChild(view) ?: -1
                    
                    if (isRecentPlayed) {
                        removeFromRecentlyPlayedStreams(query)
                    } else {
                        removeFromStreamsHistory(query)
                    }
                    
                    // Focus reassignment
                    val newParent = if (isRecentPlayed) streamsRecentPlayedContainer else streamsHistoryContainer
                    if (newParent.childCount > 0) {
                        val nextToFocus = if (index >= newParent.childCount) newParent.childCount - 1 else index
                        if (nextToFocus >= 0) newParent.getChildAt(nextToFocus).requestFocus()
                        else streamsSearchInput.requestFocus()
                    } else {
                        streamsSearchInput.requestFocus()
                    }

                    startTime = 0L
                    progress.visibility = View.GONE
                } else {
                    progressHandler.postDelayed(this, 16)
                }
            }
        }

        view.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    if (event.repeatCount == 0) {
                        startTime = System.currentTimeMillis()
                        progress.progress = 0
                        progress.visibility = View.VISIBLE
                        progressHandler.post(updateProgress)
                    }
                    return@setOnKeyListener true
                } else if (event.action == KeyEvent.ACTION_UP) {
                    val duration = System.currentTimeMillis() - startTime
                    startTime = 0L
                    progressHandler.removeCallbacks(updateProgress)
                    progress.visibility = View.GONE
                    
                    if (duration < 500) {
                        view.performClick()
                    }
                    return@setOnKeyListener true
                }
            }
            false
        }
    }

    private fun refreshStreamsHistory() {
        streamsHistoryContainer.removeAllViews()
        val historyJson = prefs.getString("streams_search_history", "[]") ?: "[]"
        val array = JSONArray(historyJson)
        
        if (array.length() == 0 && (prefs.getString("streams_recently_played", "[]") ?: "[]") == "[]") {
            streamsHistoryLayout.visibility = View.GONE
            return
        }
        streamsHistoryLayout.visibility = View.VISIBLE
        
        val inflater = LayoutInflater.from(this)
        for (i in 0 until array.length()) {
            val query = array.getString(i)
            val view = inflater.inflate(R.layout.item_search_history, streamsHistoryContainer, false)
            val text = view.findViewById<TextView>(R.id.history_text)
            val deleteProgress = view.findViewById<ProgressBar>(R.id.history_delete_progress)
            
            text.text = query
            view.setOnClickListener {
                streamsSearchInput.setText(query)
                performStreamSearch()
            }
            
            setupHistoryLongPress(view, deleteProgress, query)
            
            streamsHistoryContainer.addView(view)
        }
        refreshRecentlyPlayedStreams()
    }

    private fun refreshRecentlyPlayedStreams() {
        streamsRecentPlayedContainer.removeAllViews()
        val recentJson = prefs.getString("streams_recently_played", "[]") ?: "[]"
        val array = JSONArray(recentJson)

        if (array.length() == 0) {
            streamsRecentPlayedLayout.visibility = View.GONE
            return
        }
        streamsRecentPlayedLayout.visibility = View.VISIBLE

        val inflater = LayoutInflater.from(this)
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val title = obj.getString("display_title")
            val itemData = obj.getJSONObject("item")
            val season = if (obj.has("season")) obj.getInt("season") else null
            val episode = if (obj.has("episode")) obj.getInt("episode") else null

            val view = inflater.inflate(R.layout.item_search_history, streamsRecentPlayedContainer, false)
            val textView = view.findViewById<TextView>(R.id.history_text)
            val deleteProgress = view.findViewById<ProgressBar>(R.id.history_delete_progress)
            textView.text = title

            val iconView = (view as? ViewGroup)?.getChildAt(0) as? ImageView
            iconView?.setImageResource(R.drawable.ic_history)

            view.setOnClickListener {
                val cachedUrl = obj.optString("video_url")
                if (cachedUrl.isNotEmpty()) {
                    val headersObj = obj.optJSONObject("headers")
                    if (headersObj != null) {
                        val headers = mutableMapOf<String, String>()
                        headersObj.keys().forEach { k -> headers[k] = headersObj.getString(k) }
                        interceptedMediaUrls[cachedUrl] = headers
                    }
                    
                    lastScrapedItem = itemData
                    lastScrapedSeason = season
                    lastScrapedEpisode = episode

                    // Restore subtitles
                    interceptedSubtitleUrls.clear()
                    val subsArray = obj.optJSONArray("subtitles")
                    var subsMissing = false
                    if (subsArray != null && subsArray.length() > 0) {
                        for (j in 0 until subsArray.length()) {
                            val subObj = subsArray.getJSONObject(j)
                            val subUrl = subObj.getString("url")
                            val infoObj = subObj.getJSONObject("info")
                            val info = mutableMapOf<String, String>()
                            infoObj.keys().forEach { k -> info[k] = infoObj.getString(k) }
                            
                            if (subUrl.startsWith("file://")) {
                                val file = File(Uri.parse(subUrl).path ?: "")
                                if (file.exists()) {
                                    interceptedSubtitleUrls[subUrl] = info
                                } else {
                                    subsMissing = true
                                }
                            } else {
                                interceptedSubtitleUrls[subUrl] = info
                            }
                        }
                    }

                    if (subsMissing || (subsArray != null && subsArray.length() > 0 && interceptedSubtitleUrls.isEmpty())) {
                        // Some or all previously loaded subs are missing
                        if (autoSubPref == 1) { // Automatic
                            // We will launch the video and then auto-search
                            launchNativeVideoPlayer(cachedUrl, null, title) {
                                performScrape(itemData, season, episode)
                            }
                            // Trigger auto subtitle search
                            performAutoSubtitleSearch(itemData, season, episode)
                        } else {
                            // Ask or Never -> Bring up subtitle picker
                            launchNativeVideoPlayer(cachedUrl, null, title) {
                                performScrape(itemData, season, episode)
                            }
                            showSubtitlePicker(itemData, season, episode)
                        }
                    } else {
                        launchNativeVideoPlayer(cachedUrl, null, title) {
                            // On failure, fallback to scraping
                            performScrape(itemData, season, episode)
                        }
                    }
                } else {
                    performScrape(itemData, season, episode)
                }
            }

            setupHistoryLongPress(view, deleteProgress, title, true)

            // Simple delete for now
            view.findViewById<View>(R.id.btn_delete_history).setOnClickListener {
                removeFromRecentlyPlayedStreams(title)
                if (streamsRecentPlayedContainer.childCount == 0) streamsSearchInput.requestFocus()
            }

            streamsRecentPlayedContainer.addView(view)
        }
    }

    private fun addToRecentlyPlayedStreams(displayTitle: String, item: JSONObject, season: Int?, episode: Int?, videoUrl: String? = null, headers: Map<String, String>? = null) {
        val recentJson = prefs.getString("streams_recently_played", "[]") ?: "[]"
        val array = JSONArray(recentJson)
        val newList = mutableListOf<JSONObject>()
        for (i in 0 until array.length()) newList.add(array.getJSONObject(i))

        val existing = newList.find { it.getString("display_title") == displayTitle }
        newList.removeAll { it.getString("display_title") == displayTitle }

        val newEntry = JSONObject().apply {
            put("display_title", displayTitle)
            put("item", item)
            if (season != null) put("season", season)
            if (episode != null) put("episode", episode)
            
            val finalUrl = videoUrl ?: existing?.optString("video_url")
            if (!finalUrl.isNullOrEmpty()) put("video_url", finalUrl)
            
            val finalHeaders = if (headers != null) JSONObject(headers) else existing?.optJSONObject("headers")
            if (finalHeaders != null) put("headers", finalHeaders)
            
            if (interceptedSubtitleUrls.isNotEmpty()) {
                val subsArray = JSONArray()
                interceptedSubtitleUrls.forEach { (url, info) ->
                    val subObj = JSONObject()
                    subObj.put("url", url)
                    val infoObj = JSONObject()
                    info.forEach { (k, v) -> infoObj.put(k, v) }
                    subObj.put("info", infoObj)
                    subsArray.put(subObj)
                }
                put("subtitles", subsArray)
            } else if (existing?.has("subtitles") == true) {
                put("subtitles", existing.getJSONArray("subtitles"))
            }
        }
        newList.add(0, newEntry)
        if (newList.size > 20) newList.removeAt(newList.size - 1)

        val newArray = JSONArray()
        newList.forEach { newArray.put(it) }
        prefs.edit().putString("streams_recently_played", newArray.toString()).apply()
        refreshRecentlyPlayedStreams()
    }

    private fun removeFromRecentlyPlayedStreams(displayTitle: String) {
        val recentJson = prefs.getString("streams_recently_played", "[]") ?: "[]"
        val array = JSONArray(recentJson)
        val newArray = JSONArray()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            if (obj.getString("display_title") != displayTitle) newArray.put(obj)
        }
        prefs.edit().putString("streams_recently_played", newArray.toString()).apply()
        
        // Clear autoresume state for this title
        prefs.edit().remove("resume_stream_$displayTitle").apply()
        
        refreshRecentlyPlayedStreams()
    }

    private fun removeFromStreamsHistory(query: String) {
        val historyJson = prefs.getString("streams_search_history", "[]") ?: "[]"
        val array = JSONArray(historyJson)
        val newArray = JSONArray()
        for (i in 0 until array.length()) {
            val item = array.getString(i)
            if (item != query) newArray.put(item)
        }
        prefs.edit().putString("streams_search_history", newArray.toString()).apply()
        refreshStreamsHistory()
    }

    private fun displayStreamResults(results: JSONArray) {
        val inflater = LayoutInflater.from(this)
        streamsResultsContainer.removeAllViews()
        streamsCountText.visibility = View.GONE
        streamsCountText.text = ""
        for (i in 0 until results.length()) {
            val item = results.getJSONObject(i)
            val view = inflater.inflate(R.layout.item_stream, streamsResultsContainer, false)
            val titleView = view.findViewById<TextView>(R.id.card_title)
            val detailView = view.findViewById<TextView>(R.id.card_detail)
            val thumbView = view.findViewById<ImageView>(R.id.card_thumb)

            val title = item.optString("title")
            val overview = item.optString("overview")
            val mediaType = item.optString("media_type").uppercase()
            val posterPath = item.optString("poster_path")

            titleView.text = title
            detailView.text = "[$mediaType] ${if (overview.isNotEmpty()) overview else "No description available."}"
            
            if (posterPath.isNotEmpty() && posterPath != "null") {
                val thumbUrl = "https://image.tmdb.org/t/p/w185$posterPath"
                loadStreamThumb(thumbUrl, thumbView)
            } else {
                thumbView.setImageResource(R.drawable.ic_history)
            }
            
            view.setOnClickListener {
                performScrape(item)
            }
            streamsResultsContainer.addView(view)
            if (i == 0) view.post { view.requestFocus() }
        }
    }

    private fun loadStreamThumb(url: String, imageView: ImageView) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val connection = java.net.URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                connection.inputStream.use { input ->
                    val bitmap = BitmapFactory.decodeStream(input)
                    withContext(Dispatchers.Main) {
                        imageView.setImageBitmap(bitmap)
                    }
                }
            } catch (e: Exception) {
                Log.e("TVBrowser", "Failed to load thumb: ${e.message}")
            }
        }
    }

    private fun performScrape(item: JSONObject, season: Int? = null, episode: Int? = null) {
        val mediaType = item.optString("media_type")
        if (mediaType == "tv" && (season == null || episode == null)) {
            showTvSelectionScreen(item)
            return
        }

        val title = item.optString("orig_title") ?: item.optString("title")
        val displayTitle = if (season != null && episode != null) "$title S${season}E$episode" else title
        Toast.makeText(this, "Scraping: $displayTitle", Toast.LENGTH_SHORT).show()

        lastScrapedItem = item
        lastScrapedSeason = season
        lastScrapedEpisode = episode

        tvSelectionLayout.visibility = View.GONE
        streamsScreenLayout.visibility = View.VISIBLE

        interceptedSubtitleUrls.clear()
        interceptedMediaUrls.clear()

        streamsProgress.visibility = View.VISIBLE
        streamsProgress.isIndeterminate = true
        streamsProgress.progress = 0
        streamsResultsContainer.removeAllViews()
        streamsResultsContainer.tag = -1 // Reset tag
        streamsCountText.visibility = View.GONE // Reset count text
        streamsCountText.text = ""
        subtitlesStatus.visibility = View.GONE
        subtitlesStatus.text = ""
        streamsResultsScroll.visibility = View.VISIBLE
        btnStreamsBack.visibility = View.VISIBLE // Allow going back while scraping
        btnStreamsStop.visibility = View.VISIBLE
        btnStreamsStop.requestFocus()
        streamsHistoryLayout.visibility = View.GONE
        streamsSearchBarLayout.visibility = View.GONE
        
        btnStreamsStop.setOnClickListener {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val py = Python.getInstance()
                    val scraper = py.getModule("main")
                    scraper.callAttr("stop_scrape")
                } catch (e: Exception) {
                    Log.e("TVBrowser", "Error stopping scrape: ${e.message}")
                }
            }
            val hadFocus = btnStreamsStop.hasFocus()
            btnStreamsStop.visibility = View.GONE
            if (hadFocus) {
                if (streamsResultsContainer.childCount > 0 && streamsResultsContainer.getChildAt(0).isFocusable) {
                    streamsResultsContainer.getChildAt(0).requestFocus()
                } else {
                    btnStreamsBack.requestFocus()
                }
            }
        }

        currentStreamingTitle = displayTitle
        
        // Polling task for progress and incremental results
        val pollingJob = lifecycleScope.launch(Dispatchers.IO) {
            var lastTagValue = -1
            var lastUIUpdateTime = 0L
            
            while (isActive) {
                try {
                    val py = Python.getInstance()
                    val scraper = py.getModule("main")
                    val statusJson = scraper.callAttr("get_scrape_status").toString()
                    val status = JSONObject(statusJson)
                    
                    val current = status.optInt("current", 0)
                    val total = status.optInt("total", 0)
                    val message = status.optString("message", "")
                    val sources = status.optJSONArray("sources")
                    val isFinished = message == "Finished!" || message == "Stopped!" || message == "Timeout reached!"
                    
                    val currentTime = System.currentTimeMillis()
                    // Throttle UI updates and skip if user is interacting with a source
                    val shouldUpdateList = !isInteractingWithSources && sources != null && (isFinished || (currentTime - lastUIUpdateTime > 1500))

                    withContext(Dispatchers.Main) {
                        if (shouldUpdateList) {
                            val count = sources?.length() ?: 0
                            if (count != lastTagValue) {
                                displaySources(sources!!, isFinished)
                                lastTagValue = count
                                lastUIUpdateTime = currentTime
                            }
                        }

                        val count = sources?.length() ?: 0
                        if (count > 0) {
                            streamsCountText.text = "Total Sources Found: $count"
                            streamsCountText.visibility = View.VISIBLE
                        }

                        if (total > 0) {
                            val statusMsg = if (message.contains("Paused")) "Paused: $displayTitle ($current/$total)" 
                                            else "Scraping: $displayTitle ($current/$total)"
                            subtitlesStatus.text = statusMsg
                            subtitlesStatus.visibility = View.VISIBLE
                            streamsProgress.visibility = View.VISIBLE
                            streamsProgress.isIndeterminate = false
                            streamsProgress.max = total
                            streamsProgress.progress = current
                        } else if (message != "No active scrape") {
                            subtitlesStatus.text = if (message.isNotEmpty()) message else "Scraping: $displayTitle..."
                            subtitlesStatus.visibility = View.VISIBLE
                            streamsProgress.visibility = View.VISIBLE
                            streamsProgress.isIndeterminate = true
                        }

                        if (isFinished) {
                            val hadFocus = btnStreamsStop.hasFocus()
                            btnStreamsStop.visibility = View.GONE
                            subtitlesStatus.visibility = View.GONE
                            streamsProgress.visibility = View.GONE

                            // Ensure final count is shown
                            val finalCount = sources?.length() ?: 0
                            if (finalCount > 0) {
                                streamsCountText.text = "Total Sources Found: $finalCount"
                                streamsCountText.visibility = View.VISIBLE
                            }

                            if (hadFocus) {
                                if (streamsResultsContainer.childCount > 0 && streamsResultsContainer.getChildAt(0).isFocusable) {
                                    streamsResultsContainer.getChildAt(0).requestFocus()
                                } else {
                                    btnStreamsBack.requestFocus()
                                }
                            }
                        } else if (message != "No active scrape") {
                            btnStreamsStop.visibility = View.VISIBLE
                        }
                    }
                } catch (e: Exception) {
                    Log.e("TVBrowser", "Polling error: ${e.message}")
                }
                delay(1000)
            }
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val py = Python.getInstance()
                val scraper = py.getModule("main")
                val sourcesJson = scraper.callAttr("scrape", item.toString(), season, episode).toString()
                val sources = JSONArray(sourcesJson)

                withContext(Dispatchers.Main) {
                    pollingJob.cancel()
                    streamsProgress.visibility = View.GONE
                    subtitlesStatus.visibility = View.GONE
                    btnStreamsBack.visibility = View.VISIBLE
                    btnStreamsStop.visibility = View.GONE
                    displaySources(sources, true)

                    if (autoSubPref == 1) { // 1 is Automatic
                        performAutoSubtitleSearch(item, season, episode)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    pollingJob.cancel()
                    streamsProgress.visibility = View.GONE
                    subtitlesStatus.visibility = View.GONE
                    btnStreamsStop.visibility = View.GONE
                    Toast.makeText(this@MainActivity, "Scrape error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showTvSelectionScreen(item: JSONObject) {
        val tvId = item.optInt("id")
        tvSelectionLayout.visibility = View.VISIBLE
        streamsScreenLayout.visibility = View.GONE
        
        tvShowTitle.text = item.optString("orig_title") ?: item.optString("title")
        tvShowOverview.text = item.optString("overview")
        
        val posterPath = item.optString("poster_path")
        if (posterPath.isNotEmpty() && posterPath != "null") {
            loadStreamThumb("https://image.tmdb.org/t/p/w500$posterPath", tvShowPoster)
        } else {
            tvShowPoster.setImageResource(R.drawable.ic_history)
        }

        // Fetch seasons if not cached
        if (cachedSeasons.containsKey(tvId)) {
            populateSeasons(item, cachedSeasons[tvId]!!)
        } else {
            val dialog = AlertDialog.Builder(this)
                .setTitle("Loading Seasons...")
                .setMessage("Please wait...")
                .setNegativeButton("Cancel", null)
                .create()
            dialog.show()

            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val py = Python.getInstance()
                    val scraper = py.getModule("main")
                    val seasonsJson = scraper.callAttr("get_tv_seasons", tvId).toString()
                    val details = JSONObject(seasonsJson)
                    val seasons = details.optJSONArray("seasons")
                    withContext(Dispatchers.Main) {
                        dialog.dismiss()
                        if (seasons != null) {
                            cachedSeasons[tvId] = seasons
                            populateSeasons(item, seasons)
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        dialog.dismiss()
                        Toast.makeText(this@MainActivity, "Error fetching seasons", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun populateSeasons(item: JSONObject, seasons: JSONArray) {
        val seasonNames = mutableListOf<String>()
        val seasonNumbers = mutableListOf<Int>()
        for (i in 0 until seasons.length()) {
            val s = seasons.getJSONObject(i)
            val num = s.optInt("season_number")
            val name = s.optString("name") ?: "Season $num"
            val epCount = s.optInt("episode_count")
            seasonNames.add("$name ($epCount Episodes)")
            seasonNumbers.add(num)
        }

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, seasonNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        tvSeasonSpinner.adapter = adapter
        
        // If we are returning from an episode and it was near the end, we might want to pre-select something.
        // For now, let's just use the last scraped season if available.
        if (lastScrapedSeason != null) {
            val idx = seasonNumbers.indexOf(lastScrapedSeason!!)
            if (idx != -1) {
                tvSeasonSpinner.setSelection(idx)
            }
        }

        tvSeasonSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                loadEpisodes(item, seasonNumbers[position])
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun loadEpisodes(item: JSONObject, seasonNumber: Int) {
        val tvId = item.optInt("id")
        val cacheKey = "${tvId}_$seasonNumber"
        
        tvEpisodeContainer.removeAllViews()
        
        if (cachedEpisodes.containsKey(cacheKey)) {
            displayEpisodes(item, seasonNumber, cachedEpisodes[cacheKey]!!)
        } else {
            val progress = ProgressBar(this).apply {
                isIndeterminate = true
                layoutParams = LinearLayout.LayoutParams(100, 100).apply {
                    gravity = android.view.Gravity.CENTER
                    setMargins(0, 50, 0, 0)
                }
            }
            tvEpisodeContainer.addView(progress)

            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val py = Python.getInstance()
                    val scraper = py.getModule("main")
                    val episodesJson = scraper.callAttr("get_tv_episodes", tvId, seasonNumber).toString()
                    val episodes = JSONArray(episodesJson)
                    cachedEpisodes[cacheKey] = episodes
                    withContext(Dispatchers.Main) {
                        displayEpisodes(item, seasonNumber, episodes)
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        tvEpisodeContainer.removeAllViews()
                        Toast.makeText(this@MainActivity, "Error fetching episodes", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun displayEpisodes(item: JSONObject, seasonNumber: Int, episodes: JSONArray) {
        tvEpisodeContainer.removeAllViews()
        val inflater = LayoutInflater.from(this)
        
        var nextEpToSelect = -1
        if (pendingNextEpisode && lastScrapedSeason == seasonNumber && lastScrapedEpisode != null) {
            nextEpToSelect = lastScrapedEpisode!! + 1
        } else if (lastScrapedSeason == seasonNumber && lastScrapedEpisode != null) {
            nextEpToSelect = lastScrapedEpisode!!
        }

        var scrollToView: View? = null

        for (i in 0 until episodes.length()) {
            val ep = episodes.getJSONObject(i)
            val view = inflater.inflate(R.layout.item_episode, tvEpisodeContainer, false)
            val title = view.findViewById<TextView>(R.id.episode_title)
            val overview = view.findViewById<TextView>(R.id.episode_overview)
            val thumb = view.findViewById<ImageView>(R.id.episode_thumb)
            
            val num = ep.optInt("episode_number")
            val name = ep.optString("name") ?: "Episode $num"
            title.text = "E$num: $name"
            overview.text = ep.optString("overview")
            
            val stillPath = ep.optString("still_path")
            if (stillPath.isNotEmpty() && stillPath != "null") {
                loadStreamThumb("https://image.tmdb.org/t/p/w300$stillPath", thumb)
            } else {
                thumb.setImageResource(R.drawable.ic_history)
            }
            
            view.isFocusable = true
            view.isClickable = true
            view.setOnClickListener {
                pendingNextEpisode = false
                performScrape(item, seasonNumber, num)
            }

            if (num == nextEpToSelect) {
                view.setBackgroundResource(R.drawable.bg_tab_nav) // Highlight
                scrollToView = view
            }

            tvEpisodeContainer.addView(view)
        }
        
        if (scrollToView != null) {
            scrollToView.post {
                scrollToView.requestFocus()
                // Also ensure it's visible in the ScrollView
                val parent = tvEpisodeContainer.parent as? ScrollView
                parent?.smoothScrollTo(0, scrollToView.top)
            }
            pendingNextEpisode = false // Reset once consumed
        } else if (tvEpisodeContainer.childCount > 0) {
            tvEpisodeContainer.getChildAt(0).requestFocus()
        }
    }

    private fun displaySources(sources: JSONArray, isFinished: Boolean = false, forceRefresh: Boolean = false) {
        currentSources = sources
        val inflater = LayoutInflater.from(this)
        
        val currentTag = streamsResultsContainer.tag as? Int ?: 0
        if (!forceRefresh && sources.length() > 0 && sources.length() == currentTag) return
        streamsResultsContainer.tag = sources.length()

        streamsResultsContainer.removeAllViews()
        
        if (sources.length() == 0) {
            if (isFinished) {
                val emptyView = TextView(this).apply {
                    text = "No sources found for this title."
                    setTextColor(android.graphics.Color.WHITE)
                    textSize = 18f
                    setPadding(40, 40, 40, 40)
                }
                streamsResultsContainer.addView(emptyView)
            }
            btnStreamsSort.visibility = View.GONE
            return
        }

        btnStreamsSort.visibility = View.VISIBLE

        val priorities = getSortPriorities()
        val sortedSources = SourceSorter(priorities).sort(sources)

        // Add a "Find Subtitles" button at the top
        val subBtn = Button(this).apply {
            text = "🔍 Search External Subtitles"
            background = getDrawable(R.drawable.bg_focusable)
            setTextColor(android.graphics.Color.WHITE)
            setPadding(20, 10, 20, 10)
            val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 90)
            params.setMargins(20, 10, 20, 20)
            layoutParams = params
            setOnClickListener {
                lastScrapedItem?.let { item ->
                    showSubtitlePicker(item, lastScrapedSeason, lastScrapedEpisode)
                }
            }
            visibility = if (autoSubPref == 2) View.VISIBLE else View.GONE
        }
        streamsResultsContainer.addView(subBtn)

        for (i in 0 until sortedSources.length()) {
            val item = sortedSources.getJSONObject(i)
            val displayTitle = item.optString("title")
            val sourceData = item.optString("source_data")

            val view = inflater.inflate(R.layout.item_stream, streamsResultsContainer, false)
            val titleView = view.findViewById<TextView>(R.id.card_title)
            val detailView = view.findViewById<TextView>(R.id.card_detail)
            val thumbView = view.findViewById<ImageView>(R.id.card_thumb)

            titleView.text = displayTitle
            detailView.text = "Click to play stream"
            thumbView.setImageResource(R.drawable.ic_go)

            view.setOnClickListener {
                isInteractingWithSources = true
                lastSelectedSource = item
                resolveAndPlay(sourceData)
            }
            streamsResultsContainer.addView(view)
            if (i == 0 && !streamsResultsContainer.hasFocus()) view.post { view.requestFocus() }
        }
    }

    private fun getSortPriorities(): List<SortCriteria> {
        val json = prefs.getString("sort_priorities", null)
        if (json != null) {
            try {
                val array = JSONArray(json)
                val list = mutableListOf<SortCriteria>()
                for (i in 0 until array.length()) {
                    list.add(SortCriteria.valueOf(array.getString(i)))
                }
                return list
            } catch (e: Exception) {}
        }
        return SourceSorter.DEFAULT_PRIORITIES
    }

    private fun saveSortPriorities(priorities: List<SortCriteria>) {
        val array = JSONArray()
        priorities.forEach { array.put(it.name) }
        prefs.edit().putString("sort_priorities", array.toString()).apply()
    }

    private fun showSortPriorityDialog() {
        val priorities = getSortPriorities().toMutableList()
        val inflater = LayoutInflater.from(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        fun refreshList() {
            container.removeAllViews()
            priorities.forEachIndexed { index, criteria ->
                val row = inflater.inflate(R.layout.item_sort_priority, container, false)
                row.findViewById<TextView>(R.id.criteria_name).text = when(criteria) {
                    SortCriteria.NATIVE -> "Native Player (ExoPlayer)"
                    SortCriteria.RESOLUTION -> "Resolution (4K, 1080p, etc.)"
                    SortCriteria.DIRECT -> "Direct Link (vs HLS/Stream)"
                    SortCriteria.SOURCE -> "Source/Host Name"
                }

                val btnUp = row.findViewById<ImageButton>(R.id.btn_up)
                val btnDown = row.findViewById<ImageButton>(R.id.btn_down)

                btnUp.visibility = if (index > 0) View.VISIBLE else View.INVISIBLE
                btnUp.isFocusable = index > 0
                btnDown.visibility = if (index < priorities.size - 1) View.VISIBLE else View.INVISIBLE
                btnDown.isFocusable = index < priorities.size - 1

                btnUp.setOnClickListener {
                    if (index > 0) {
                        val temp = priorities[index]
                        priorities[index] = priorities[index - 1]
                        priorities[index - 1] = temp
                        refreshList()
                        val targetRow = container.getChildAt(index - 1)
                        val targetBtn = targetRow.findViewById<View>(R.id.btn_up)
                        if (targetBtn.visibility == View.VISIBLE) targetBtn.requestFocus()
                        else targetRow.findViewById<View>(R.id.btn_down).requestFocus()
                    }
                }

                btnDown.setOnClickListener {
                    if (index < priorities.size - 1) {
                        val temp = priorities[index]
                        priorities[index] = priorities[index + 1]
                        priorities[index + 1] = temp
                        refreshList()
                        val targetRow = container.getChildAt(index + 1)
                        val targetBtn = targetRow.findViewById<View>(R.id.btn_down)
                        if (targetBtn.visibility == View.VISIBLE) targetBtn.requestFocus()
                        else targetRow.findViewById<View>(R.id.btn_up).requestFocus()
                    }
                }
                container.addView(row)
            }
        }

        refreshList()
        layout.addView(container)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Sort Priorities")
            .setView(layout)
            .setPositiveButton("Apply") { _, _ ->
                saveSortPriorities(priorities)
                currentSources?.let { displaySources(it, isFinished = true, forceRefresh = true) }
            }
            .setNegativeButton("Cancel", null)
            .create()
        dialog.show()
    }

    private fun showSubtitlePicker(item: JSONObject, season: Int?, episode: Int?) {
        val itemKey = "${item.optInt("id")}_${season ?: 0}_${episode ?: 0}"
        val cached = cachedSubtitleResults[itemKey]
        if (cached != null) {
            displaySubtitlePicker(cached)
            return
        }

        subtitlesStatus.text = "Searching external subtitles..."
        subtitlesStatus.visibility = View.VISIBLE
        subtitlesProgress.visibility = View.VISIBLE
        subtitlesProgress.isIndeterminate = true

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val py = Python.getInstance()
                val scraper = py.getModule("main")
                val subsJson = scraper.callAttr("search_subtitles", item.toString(), season, episode).toString()
                val subs = JSONArray(subsJson)
                cachedSubtitleResults[itemKey] = subs
                
                withContext(Dispatchers.Main) {
                    subtitlesStatus.visibility = View.GONE
                    subtitlesProgress.visibility = View.GONE
                    displaySubtitlePicker(subs)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    subtitlesStatus.visibility = View.GONE
                    subtitlesProgress.visibility = View.GONE
                    Toast.makeText(this@MainActivity, "Subtitle search failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun displaySubtitlePicker(subs: JSONArray) {
        if (subs.length() == 0) {
            Toast.makeText(this@MainActivity, "No external subtitles found", Toast.LENGTH_SHORT).show()
            return
        }
        
        val subNames = mutableListOf<String>()
        for (i in 0 until subs.length()) {
            val sub = subs.getJSONObject(i)
            subNames.add("[${sub.optString("service")}] ${sub.optString("lang")}: ${sub.optString("name")}")
        }
        
        val inflater = LayoutInflater.from(this@MainActivity)
        val layout = inflater.inflate(R.layout.dialog_subtitle_picker, null)
        val listView = layout.findViewById<ListView>(R.id.sub_list)
        val btnDownload = layout.findViewById<Button>(R.id.btn_download)
        val btnCancel = layout.findViewById<Button>(R.id.btn_cancel)
        
        val adapter = ArrayAdapter<String>(this@MainActivity, R.layout.item_subtitle_choice, subNames)
        listView.adapter = adapter
        
        val dialog = AlertDialog.Builder(this@MainActivity)
            .setView(layout)
            .create()
            
        btnCancel.setOnClickListener { dialog.dismiss() }
        btnDownload.setOnClickListener {
            val checkedPositions = listView.checkedItemPositions
            val selectedIndices = mutableListOf<Int>()
            for (i in 0 until adapter.count) {
                if (checkedPositions.get(i)) selectedIndices.add(i)
            }
            
            if (selectedIndices.isEmpty()) {
                Toast.makeText(this@MainActivity, "No subtitles selected", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            
            dialog.dismiss()

            val selectedCount = selectedIndices.size
            subtitlesStatus.text = "Downloading 0 / $selectedCount subtitles..."
            subtitlesStatus.visibility = View.VISIBLE
            subtitlesProgress.visibility = View.VISIBLE
            subtitlesProgress.isIndeterminate = false
            subtitlesProgress.max = selectedCount
            subtitlesProgress.progress = 0

            var downloadedCount = 0
            fun downloadNext(idx: Int) {
                if (idx >= selectedIndices.size) {
                    subtitlesStatus.visibility = View.GONE
                    subtitlesProgress.visibility = View.GONE
                    Toast.makeText(this@MainActivity, "Finished downloading $downloadedCount subtitles", Toast.LENGTH_SHORT).show()
                    return
                }
                
                subtitlesStatus.text = "Downloading ${idx + 1} / $selectedCount subtitles..."
                downloadSubtitle(subs.getJSONObject(selectedIndices[idx]), silent = true) { path ->
                    if (path != null) downloadedCount++
                    subtitlesProgress.progress = idx + 1
                    downloadNext(idx + 1)
                }
            }
            downloadNext(0)
        }
        dialog.show()
    }

    private fun downloadSubtitle(subItem: JSONObject, silent: Boolean = false, onComplete: ((String?) -> Unit)? = null) {
        if (!silent) {
            subtitlesStatus.text = "Downloading subtitle..."
            subtitlesStatus.visibility = View.VISIBLE
            subtitlesProgress.visibility = View.VISIBLE
            subtitlesProgress.isIndeterminate = true
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val py = Python.getInstance()
                val scraper = py.getModule("main")
                val serviceName = subItem.getString("service")
                val actionArgs = subItem.getString("action_args")
                val filePath = scraper.callAttr("get_subtitle_file", serviceName, actionArgs).toString()

                withContext(Dispatchers.Main) {
                    if (!silent) {
                        subtitlesStatus.visibility = View.GONE
                        subtitlesProgress.visibility = View.GONE
                    }
                    if (filePath.isNotEmpty()) {
                        val subUri = Uri.fromFile(File(filePath)).toString()
                        val source = subItem.optString("service", "Unknown")
                        val name = subItem.optString("name", "Subtitle")
                        val label = "[$source] $name"
                        val lang = subItem.optString("lang", "und")

                        Log.d("TVBrowserSubs", "Setting label: $label, lang: $lang, uri: $subUri")

                        interceptedSubtitleUrls[subUri] = mapOf(
                            "label" to label,
                            "lang" to lang
                        )

                        if (silent) {
                            exoPlayer?.let { player ->
                                val mimeType = if (subUri.contains(".vtt")) MimeTypes.TEXT_VTT
                                else if (subUri.contains(".ass")) MimeTypes.TEXT_SSA
                                else MimeTypes.APPLICATION_SUBRIP

                                val resolvedLang = if (!label.isNullOrEmpty()) null else lang

                                val subConfig = MediaItem.SubtitleConfiguration.Builder(Uri.parse(subUri))
                                    .setMimeType(mimeType)
                                    .setLanguage(resolvedLang)
                                    .setLabel(label)
                                    .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                                    .setRoleFlags(C.ROLE_FLAG_SUBTITLE)
                                    .build()

                                val currentMediaItem = player.currentMediaItem
                                if (currentMediaItem != null) {
                                    val newMediaItem = currentMediaItem.buildUpon()
                                        .setSubtitleConfigurations(currentMediaItem.localConfiguration?.subtitleConfigurations.orEmpty() + subConfig)
                                        .build()
                                    val currentPos = player.currentPosition
                                    player.setMediaItem(newMediaItem, false)
                                    player.seekTo(currentPos)
                                    player.prepare()
                                }
                            }
                        } else {
                            Toast.makeText(this@MainActivity, "Subtitle added! Start video to use.", Toast.LENGTH_SHORT).show()
                        }
                        onComplete?.invoke(filePath)
                    } else {
                        if (!silent) Toast.makeText(this@MainActivity, "Subtitle download failed", Toast.LENGTH_SHORT).show()
                        onComplete?.invoke(null)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (!silent) {
                        subtitlesStatus.visibility = View.GONE
                        subtitlesProgress.visibility = View.GONE
                        Toast.makeText(this@MainActivity, "Subtitle download error: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                    onComplete?.invoke(null)
                }
            }
        }
    }

    private fun performAutoSubtitleSearch(item: JSONObject, season: Int?, episode: Int?) {
        val itemKey = "${item.optInt("id")}_${season ?: 0}_${episode ?: 0}"
        if (isDownloadingAutoSubs || lastSubtitledItemKey == itemKey) return
        isDownloadingAutoSubs = true
        lastSubtitledItemKey = itemKey
        
        subtitlesStatus.text = "Searching automatic subtitles..."
        subtitlesStatus.visibility = View.VISIBLE
        subtitlesProgress.visibility = View.VISIBLE
        subtitlesProgress.isIndeterminate = true
        
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val py = Python.getInstance()
                val scraper = py.getModule("main")
                val subsJson = scraper.callAttr("search_subtitles", item.toString(), season, episode).toString()
                val subs = JSONArray(subsJson)
                
                if (subs.length() > 0) {
                    val countToGet = if (autoSubCount == 0) subs.length() else autoSubCount.coerceAtMost(subs.length())
                    
                    val prioritizedSubs = mutableListOf<JSONObject>()
                    for (i in 0 until subs.length()) {
                        val sub = subs.getJSONObject(i)
                        if (sub.optString("sync") == "true") prioritizedSubs.add(sub)
                    }
                    for (i in 0 until subs.length()) {
                        val sub = subs.getJSONObject(i)
                        if (sub.optString("sync") != "true") prioritizedSubs.add(sub)
                    }

                    withContext(Dispatchers.Main) {
                        subtitlesStatus.text = "Downloading subtitles: 0 / $countToGet"
                        subtitlesProgress.isIndeterminate = false
                        subtitlesProgress.max = countToGet
                        subtitlesProgress.progress = 0

                        var downloadedCount = 0
                        var index = 0
                        fun next() {
                            if (isDownloadingAutoSubs && downloadedCount < countToGet && index < prioritizedSubs.size) {
                                val subToGet = prioritizedSubs[index++]
                                downloadSubtitle(subToGet, silent = true) { path ->
                                    if (path != null) {
                                        downloadedCount++
                                        subtitlesProgress.progress = downloadedCount
                                        subtitlesStatus.text = "Downloading subtitles: $downloadedCount / $countToGet"
                                    }
                                    next()
                                }
                            } else {
                                isDownloadingAutoSubs = false
                                subtitlesStatus.visibility = View.GONE
                                subtitlesProgress.visibility = View.GONE
                            }
                        }
                        next()
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        isDownloadingAutoSubs = false
                        subtitlesStatus.visibility = View.GONE
                        subtitlesProgress.visibility = View.GONE
                    }
                }
            } catch (e: Exception) {
                Log.e("TVBrowser", "Auto subtitle search failed: ${e.message}")
                withContext(Dispatchers.Main) {
                    isDownloadingAutoSubs = false
                    subtitlesStatus.visibility = View.GONE
                    subtitlesProgress.visibility = View.GONE
                }
            }
        }
    }

    private fun resolveAndPlay(sourceDataJson: String) {
        if (isDownloadingAutoSubs) {
            when (autoSubWaitPref) {
                0 -> { // Stop adding
                    isDownloadingAutoSubs = false
                    resolveAndPlayInternal(sourceDataJson)
                    return
                }
                1 -> { // Ask to wait
                    val downloadDialog = AlertDialog.Builder(this)
                        .setTitle("Downloading Subtitles")
                        .setMessage("Automatic subtitles are being fetched. Would you like to wait for them to finish?")
                        .setPositiveButton("Skip & Play") { _, _ ->
                            resolveAndPlayInternal(sourceDataJson)
                        }
                        .setNegativeButton("Cancel", null)
                        .create()

                    val pBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                        setPadding(60, 40, 60, 0)
                    }
                    downloadDialog.setView(pBar)
                    downloadDialog.show()
                    downloadDialog.getButton(AlertDialog.BUTTON_POSITIVE).requestFocus()

                    lifecycleScope.launch {
                        while (isDownloadingAutoSubs) {
                            pBar.isIndeterminate = subtitlesProgress.isIndeterminate
                            pBar.max = subtitlesProgress.max
                            pBar.progress = subtitlesProgress.progress
                            delay(500)
                        }
                        if (downloadDialog.isShowing) {
                            downloadDialog.dismiss()
                            resolveAndPlayInternal(sourceDataJson)
                        }
                    }
                    return
                }
                2 -> { // Progressive
                    resolveAndPlayInternal(sourceDataJson)
                    return
                }
            }
        }
        resolveAndPlayInternal(sourceDataJson)
    }

    private fun resolveAndPlayInternal(sourceDataJson: String) {
        streamsProgress.visibility = View.VISIBLE
        streamsProgress.isIndeterminate = true

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val py = Python.getInstance()
                val scraper = py.getModule("main")
                
                // Pause scraping while resolving/playing
                try { scraper.callAttr("pause_scrape") } catch (e: Exception) {}

                val resolveResult = scraper.callAttr("resolve", sourceDataJson).toString()
                
                withContext(Dispatchers.Main) {
                    streamsProgress.visibility = View.GONE
                    try {
                        val json = JSONObject(resolveResult)
                        if (json.has("error")) {
                            // Resume if error
                            lifecycleScope.launch(Dispatchers.IO) { 
                                try { scraper.callAttr("resume_scrape") } catch (e: Exception) {}
                            }
                            Toast.makeText(this@MainActivity, "Resolve error: ${json.getString("error")}", Toast.LENGTH_LONG).show()
                            return@withContext
                        }

                        val streamUrl = json.optString("url")
                        val isVideo = json.optBoolean("is_video", false)

                        if (streamUrl.isNotEmpty() && streamUrl.startsWith("http")) {
                            if (isVideo) {
                                val title = currentStreamingTitle
                                launchNativeVideoPlayer(streamUrl, null, title)
                                if (autoSubPref == 0) {
                                    lastScrapedItem?.let { item ->
                                        showSubtitlePicker(item, lastScrapedSeason, lastScrapedEpisode)
                                    }
                                }
                            } else {
                                // Resolved to what looks like a webpage, open in browser instead
                                loadUrlAndBrowse(streamUrl, true)
                                Toast.makeText(this@MainActivity, "Opening as webpage...", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            // Resume if empty
                            lifecycleScope.launch(Dispatchers.IO) { 
                                try { scraper.callAttr("resume_scrape") } catch (e: Exception) {}
                            }
                            Toast.makeText(this@MainActivity, "Could not resolve stream URL", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        // Fallback if not JSON (legacy or unexpected error)
                        if (resolveResult.isNotEmpty() && resolveResult.startsWith("http")) {
                            val title = currentStreamingTitle
                            launchNativeVideoPlayer(resolveResult, null, title)
                            if (autoSubPref == 0) {
                                lastScrapedItem?.let { item ->
                                    showSubtitlePicker(item, lastScrapedSeason, lastScrapedEpisode)
                                }
                            }
                        } else {
                            // Resume if error
                            isInteractingWithSources = false
                            lifecycleScope.launch(Dispatchers.IO) { 
                                try { scraper.callAttr("resume_scrape") } catch (e: Exception) {}
                            }
                            Toast.makeText(this@MainActivity, "Resolve error: $resolveResult", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    // Resume if error
                    isInteractingWithSources = false
                    lifecycleScope.launch(Dispatchers.IO) { 
                        try { Python.getInstance().getModule("main").callAttr("resume_scrape") } catch (e: Exception) {}
                    }
                    streamsProgress.visibility = View.GONE
                    Toast.makeText(this@MainActivity, "Resolve error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        silentPopupBlock = prefs.getBoolean("silent_popup_block", true)
        extractVideoPref = prefs.getInt("extract_video_pref", 0)
        videoTriggerPref = prefs.getInt("video_trigger_pref", 1)
        historyLimit = prefs.getInt("history_limit", 20)
        isLightTheme = prefs.getBoolean("light_theme", false)
        embeddedSubsEnabled = prefs.getBoolean("embedded_subs_enabled", true)
        scrollTopbarEnabled = prefs.getBoolean("scroll_topbar_enabled", true)
        historyIconPref = prefs.getInt("history_icon_pref", 0)
        bookmarkIconPref = prefs.getInt("bookmark_icon_pref", 0)
        clickjackPref = prefs.getBoolean("clickjack_prevention", true)
        navigationModePref = prefs.getInt("navigation_mode_pref", 0)
        autoSubPref = prefs.getInt("auto_sub_pref", 0)
        autoSubCount = prefs.getInt("auto_sub_count", 1)
        autoSubWaitPref = prefs.getInt("auto_sub_wait_pref", 0)
        subRetentionDays = prefs.getInt("sub_retention_days", 3)
        exoFallbackPref = prefs.getInt("exo_fallback_pref", 0)

        updateAutoPlayIcon()
        applyTheme()
        if (!isBrowsing) refreshLists()
    }

    override fun onPause() {
        super.onPause()
        exoPlayer?.pause()
        keyStates.clear()
        isMovementLoopRunning = false
        movementHandler.removeCallbacks(movementRunnable)
        movementHandler.removeCallbacks(hoverCheckRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(downloadReceiver)
        exoPlayer?.release()
        exoPlayer = null
    }

    private fun applyTheme() {
        val color = if (isLightTheme) {
            android.graphics.Color.parseColor("#444444")
        } else {
            android.graphics.Color.parseColor("#40C4FF")
        }
        cursor.setColorFilter(color, android.graphics.PorterDuff.Mode.MULTIPLY)

        webViews.forEach { wv ->
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                wv.settings.isAlgorithmicDarkeningAllowed = !isLightTheme
            }
        }
    }

    private fun isNativeVideoPlaying() = customViewContainer.visibility == View.VISIBLE && nativeVideoView.visibility == View.VISIBLE

    private fun wakeCursor() {
        if (isBrowsing && topBarLayout.visibility == View.GONE && !isNativeVideoPlaying()) {
            if (navigationModePref == 1 || isSelectionMode) {
                cursor.visibility = View.GONE
                // Ensure navHelper is ready
                val wv = currentWebView ?: return
                wv.evaluateJavascript("if(typeof window.navHelper === 'undefined') { /* trigger init script */ }", null)
                // We actually need the full script here if it's missing, but it's easier to just call startDpadSelectionMode logic without the toast
                initDpadNav()
            } else {
                cursor.visibility = View.VISIBLE
                cursor.removeCallbacks(hideCursorRunnable)
                cursor.postDelayed(hideCursorRunnable, 3500)
            }

            movementHandler.removeCallbacks(hoverCheckRunnable)
            if (navigationModePref == 0 && !isSelectionMode) {
                movementHandler.postDelayed(hoverCheckRunnable, 500)
            }
        }
    }

    private fun injectClickjackPrevention(wv: WebView) {
        if (!clickjackPref) return
        val script = """
            (function() {
                if (window.clickjackPrevented) return;
                window.clickjackPrevented = true;
                
                // Block window.open popups at the JS level
                window.open = function() { 
                    return { focus: function(){}, close: function(){}, blur: function(){} }; 
                };

                // Aggressively neutralize overlays that cover the screen
                var neutralize = function() {
                    var all = document.querySelectorAll('div, section, ins, iframe');
                    for (var i = 0; i < all.length; i++) {
                        var el = all[i];
                        var s = window.getComputedStyle(el);
                        if (s.position === 'fixed' || s.position === 'absolute') {
                            var rect = el.getBoundingClientRect();
                            if (rect.width >= window.innerWidth * 0.9 && rect.height >= window.innerHeight * 0.9) {
                                if (s.zIndex > 10 && (s.opacity < 0.1 || s.backgroundColor === 'transparent' || s.backgroundColor === 'rgba(0, 0, 0, 0)')) {
                                    el.style.pointerEvents = 'none';
                                    el.style.display = 'none'; // Some sites check for pointer-events, display:none is safer
                                }
                            }
                        }
                    }
                };

                // Run immediately and then periodically
                neutralize();
                setInterval(neutralize, 1500);

                // Prevent event hijacking by sites that try to capture all inputs
                var originalStop = Event.prototype.stopPropagation;
                var originalStopImmediate = Event.prototype.stopImmediatePropagation;

                Event.prototype.stopPropagation = function() {
                    if (['click', 'mousedown', 'mouseup'].indexOf(this.type) !== -1) return; 
                    originalStop.apply(this, arguments);
                };
                Event.prototype.stopImmediatePropagation = function() {
                    if (['click', 'mousedown', 'mouseup'].indexOf(this.type) !== -1) return; 
                    originalStopImmediate.apply(this, arguments);
                };

                // Ensure video elements and players are always interactable
                var style = document.createElement('style');
                style.innerHTML = 'video, .video-player, [class*="player"] { pointer-events: auto !important; z-index: 2147483647 !important; }';
                document.head.appendChild(style);
            })();
        """.trimIndent()
        wv.evaluateJavascript(script, null)
    }

    private fun saveSnapshot(url: String, title: String) {
        val wv = currentWebView ?: return
        if (wv.width == 0 || wv.height == 0 || !isBrowsing) return
        try {
            val bitmap = Bitmap.createBitmap(wv.width, wv.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            wv.draw(canvas)
            val thumb = Bitmap.createScaledBitmap(bitmap, 320, 180, true)
            val filename = "${url.hashCode()}.png"
            val file = File(filesDir, filename)
            FileOutputStream(file).use { thumb.compress(Bitmap.CompressFormat.PNG, 80, it) }
            bitmap.recycle()
            saveToList("history", url, title, filename)
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun saveToList(key: String, url: String, title: String, thumbFile: String) {
        val jsonString = prefs.getString(key, "[]")
        val array = JSONArray(jsonString)
        var existingIndex = -1
        for (i in 0 until array.length()) {
            if (array.getJSONObject(i).getString("url") == url) {
                existingIndex = i; break
            }
        }
        if (existingIndex != -1) array.remove(existingIndex)

        val obj = JSONObject().apply {
            put("url", url)
            put("title", title.ifEmpty { url })
            put("thumb", thumbFile)
        }
        array.put(obj)
        prefs.edit().putString(key, array.toString()).apply()
        updateFavIcon()
    }

    private fun removeFromList(key: String, url: String, focusUIIndex: Int = -1) {
        val array = JSONArray(prefs.getString(key, "[]"))
        val newArray = JSONArray()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            if (obj.getString("url") != url) newArray.put(obj)
        }
        prefs.edit().putString(key, newArray.toString()).apply()
        if (!isBrowsing) refreshLists(key, focusUIIndex)
        updateFavIcon()
    }

    private fun isFavorited(url: String): Boolean {
        val array = JSONArray(prefs.getString("favorites", "[]"))
        for (i in 0 until array.length()) {
            if (array.getJSONObject(i).getString("url") == url) return true
        }
        return false
    }

    private fun updateFavIcon() {
        val url = currentWebView?.url ?: return
        if (isFavorited(url)) topFavBtn.setImageResource(R.drawable.ic_heart_filled)
        else topFavBtn.setImageResource(R.drawable.ic_heart_empty)
    }

    private fun updateAutoPlayIcon() {
        if (videoTriggerPref == 0) {
            topAutoPlayBtn.imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#00BCD4"))
            topRecordBtn.visibility = View.VISIBLE
        } else {
            val color = if (isLightTheme) android.graphics.Color.BLACK else android.graphics.Color.WHITE
            topAutoPlayBtn.imageTintList = android.content.res.ColorStateList.valueOf(color)
            topRecordBtn.visibility = View.GONE
            if (isRecordingAutoplay) stopRecording()
        }
    }

    private fun refreshLists(focusKey: String? = null, focusUIIndex: Int = -1) {
        historyContainer.removeAllViews()
        favContainer.removeAllViews()
        downloadsContainer.removeAllViews()
        openTabsContainer.removeAllViews()
        val inflater = LayoutInflater.from(this)

        var endIdx = 0
        var favCount = 0
        try {
            val downloadsArray = JSONArray(prefs.getString("downloads", "[]"))
            for (i in (downloadsArray.length() - 1) downTo 0) {
                val obj = downloadsArray.optJSONObject(i) ?: continue
                val view = createCard(inflater, obj, "downloads")
                downloadsContainer.addView(view)
            }

            btnRestoreTabs.visibility = View.GONE
            if (webViews.isNotEmpty()) {
                for (i in webViews.indices) {
                    val wv = webViews[i]
                    val card = createTabCard(inflater, wv, i)
                    openTabsContainer.addView(card)
                }
            } else {
                val savedTabs = prefs.getString("saved_tabs", "[]") ?: "[]"
                if (savedTabs != "[]") {
                    btnRestoreTabs.visibility = View.VISIBLE
                    btnRestoreTabs.setOnClickListener { restoreAllTabs() }
                    val array = JSONArray(savedTabs)
                    for (i in 0 until array.length()) {
                        val obj = array.optJSONObject(i) ?: continue
                        val card = createSavedTabCard(inflater, obj, i)
                        openTabsContainer.addView(card)
                    }
                }
            }

            val historyArray = JSONArray(prefs.getString("history", "[]"))
            val histLimit = if (historyLimit > 0 && historyArray.length() > historyLimit) historyArray.length() - historyLimit else 0
            for (i in (historyArray.length() - 1) downTo histLimit) {
                val obj = historyArray.optJSONObject(i) ?: continue
                val view = createCard(inflater, obj, "history")
                historyContainer.addView(view)
            }

            val favArray = JSONArray(prefs.getString("favorites", "[]"))
            favCount = favArray.length()
            val startIdx = bookmarkPage * ITEMS_PER_PAGE
            endIdx = startIdx + ITEMS_PER_PAGE
            if (endIdx > favCount) endIdx = favCount

            for (i in (favCount - 1 - startIdx) downTo (favCount - endIdx)) {
                if (i < 0) break
                val obj = favArray.optJSONObject(i) ?: continue
                val view = createCard(inflater, obj, "favorites")
                favContainer.addView(view)
            }
        } catch (e: Exception) {
            Log.e("TVBrowser", "Error refreshing lists: ${e.message}")
        }

        findViewById<Button>(R.id.btn_bookmarks_prev).apply {
            visibility = if (bookmarkPage > 0) View.VISIBLE else View.GONE
            setOnClickListener { bookmarkPage--; refreshLists() }
        }
        findViewById<Button>(R.id.btn_bookmarks_next).apply {
            visibility = if (endIdx < favCount) View.VISIBLE else View.GONE
            setOnClickListener { bookmarkPage++; refreshLists() }
        }

        if (focusKey != null && focusUIIndex != -1) {
            val container = when (focusKey) {
                "history" -> historyContainer
                "favorites" -> favContainer
                "downloads" -> downloadsContainer
                "tabs" -> openTabsContainer
                else -> null
            }
            container?.post {
                if (container.childCount > 0) {
                    val target = container.getChildAt(focusUIIndex.coerceAtMost(container.childCount - 1))
                    target?.requestFocus()
                } else {
                    when (focusKey) {
                        "history" -> homeTabHistory.requestFocus()
                        "favorites" -> homeTabBookmarks.requestFocus()
                        "downloads" -> homeTabDownloads.requestFocus()
                        "tabs" -> homeTabTabs.requestFocus()
                    }
                }
            }
        }
    }

    private fun fetchFavicon(url: String, file: File, onDone: () -> Unit) {
        val host = Uri.parse(url).host ?: return
        val faviconUrl = "https://www.google.com/s2/favicons?sz=64&domain_url=$host"

        @Suppress("OPT_IN_USAGE")
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val connection = java.net.URL(faviconUrl).openConnection()
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                connection.getInputStream().use { input ->
                    val bitmap = BitmapFactory.decodeStream(input)
                    if (bitmap != null) {
                        FileOutputStream(file).use { out ->
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                        }
                        withContext(Dispatchers.Main) { onDone() }
                    }
                }
            } catch (e: Exception) {
                Log.e("TVBrowser", "Favicon fetch failed: ${e.message}")
            }
        }
    }

    private fun createCard(inflater: LayoutInflater, obj: JSONObject, listKey: String): View {
        val view = inflater.inflate(R.layout.item_card, null)
        val titleView = view.findViewById<TextView>(R.id.card_title)
        val urlView = view.findViewById<TextView>(R.id.card_url)
        val thumbView = view.findViewById<ImageView>(R.id.card_thumb)

        val url = obj.getString("url")
        val title = obj.getString("title")
        titleView.text = title
        urlView.text = url

        val iconPref = if (listKey == "history") historyIconPref else bookmarkIconPref

        if (iconPref == 0) {
            val file = File(filesDir, obj.getString("thumb"))
            if (file.exists()) thumbView.setImageBitmap(BitmapFactory.decodeFile(file.absolutePath))
            else thumbView.setImageResource(if (listKey == "history") R.drawable.ic_history else R.drawable.ic_heart_empty)
        } else {
            val host = Uri.parse(url).host ?: ""
            val faviconFile = File(filesDir, "fav_${host.hashCode()}.png")
            if (faviconFile.exists()) {
                thumbView.setImageBitmap(BitmapFactory.decodeFile(faviconFile.absolutePath))
            } else {
                thumbView.setImageResource(if (listKey == "history") R.drawable.ic_history else R.drawable.ic_heart_empty)
                fetchFavicon(url, faviconFile) {
                    // Update only this view if it's still visible
                    val bitmap = BitmapFactory.decodeFile(faviconFile.absolutePath)
                    if (bitmap != null) thumbView.setImageBitmap(bitmap)
                }
            }
            thumbView.setPadding(30, 30, 30, 30)
        }

        view.setOnClickListener {
            if (listKey == "downloads") {
                openDownloadedFile(title)
            } else {
                loadUrlAndBrowse(url, true)
            }
        }

        view.setOnLongClickListener {
            val parent = view.parent as? ViewGroup
            val index = parent?.indexOfChild(view) ?: -1
            AlertDialog.Builder(this)
                .setTitle("Remove?")
                .setMessage("Remove this from $listKey?")
                .setPositiveButton("Remove") { _, _ -> removeFromList(listKey, url, index) }
                .setNegativeButton("Cancel", null).show()
            true
        }
        return view
    }

    private fun openDownloadedFile(fileName: String) {
        try {
            val file = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), fileName)
            if (!file.exists()) {
                Toast.makeText(this, "File not found. It may have been deleted.", Toast.LENGTH_SHORT).show()
                return
            }

            val uri = FileProvider.getUriForFile(this, "${packageName}.provider", file)
            val extension = file.extension.lowercase()
            val mimeType = when (extension) {
                "apk" -> "application/vnd.android.package-archive"
                else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "*/*"
            }

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Cannot open this file type.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun createTabCard(inflater: LayoutInflater, wv: WebView, index: Int): View {
        val view = inflater.inflate(R.layout.tab_item, null).apply {
            layoutParams = LinearLayout.LayoutParams(400, ViewGroup.LayoutParams.MATCH_PARENT).apply { marginEnd = 20 }
        }
        val titleTxt = view.findViewById<TextView>(R.id.tab_title)
        val progress = view.findViewById<ProgressBar>(R.id.tab_close_progress)

        val title = wv.title?.takeIf { it.isNotEmpty() && it != "about:blank" }
            ?: wv.tag as? String
            ?: wv.url?.takeIf { it.isNotEmpty() && it != "about:blank" }
            ?: "New Tab"

        titleTxt.text = title
        view.isSelected = (index == currentTabIndex)

        view.setOnClickListener { switchTab(index) }
        setupTabLongPress(view, progress, index)
        return view
    }

    private fun createSavedTabCard(inflater: LayoutInflater, obj: JSONObject, index: Int): View {
        val view = inflater.inflate(R.layout.tab_item, null).apply {
            layoutParams = LinearLayout.LayoutParams(400, ViewGroup.LayoutParams.MATCH_PARENT).apply { marginEnd = 20 }
            alpha = 0.6f
        }
        val titleTxt = view.findViewById<TextView>(R.id.tab_title)
        val progress = view.findViewById<ProgressBar>(R.id.tab_close_progress)

        titleTxt.text = obj.optString("title", "Saved Tab")

        view.setOnClickListener { restoreAllTabs(); switchTab(index) }
        setupSavedTabLongPress(view, progress, index)
        return view
    }

    private fun setupTabLongPress(view: View, progress: ProgressBar, index: Int) {
        val progressHandler = android.os.Handler(android.os.Looper.getMainLooper())
        var startTime = 0L
        val updateProgress = object : Runnable {
            override fun run() {
                if (startTime == 0L) return
                val elapsed = System.currentTimeMillis() - startTime
                val progressValue = (elapsed / 10f).toInt()

                progress.progress = progressValue
                if (progressValue >= 100) {
                    closeTab(index)
                    if (!isBrowsing) refreshLists("tabs", index)
                } else {
                    progressHandler.postDelayed(this, 16)
                }
            }
        }

        view.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    if (event.repeatCount == 0) {
                        startTime = System.currentTimeMillis()
                        progress.progress = 0
                        progress.visibility = View.VISIBLE
                        progressHandler.post(updateProgress)
                    }
                } else if (event.action == KeyEvent.ACTION_UP) {
                    startTime = 0L
                    progressHandler.removeCallbacks(updateProgress)
                    progress.visibility = View.GONE
                }
            }
            false
        }
    }

    private fun setupSavedTabLongPress(view: View, progress: ProgressBar, index: Int) {
        val progressHandler = android.os.Handler(android.os.Looper.getMainLooper())
        var startTime = 0L
        val updateProgress = object : Runnable {
            override fun run() {
                if (startTime == 0L) return
                val elapsed = System.currentTimeMillis() - startTime
                val progressValue = (elapsed / 10f).toInt()

                progress.progress = progressValue
                if (progressValue >= 100) {
                    removeSavedTab(index)
                    refreshLists("tabs", index)
                } else {
                    progressHandler.postDelayed(this, 16)
                }
            }
        }

        view.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    if (event.repeatCount == 0) {
                        startTime = System.currentTimeMillis()
                        progress.progress = 0
                        progress.visibility = View.VISIBLE
                        progressHandler.post(updateProgress)
                    }
                } else if (event.action == KeyEvent.ACTION_UP) {
                    startTime = 0L
                    progressHandler.removeCallbacks(updateProgress)
                    progress.visibility = View.GONE
                }
            }
            false
        }
    }

    private fun removeSavedTab(index: Int) {
        val savedTabs = prefs.getString("saved_tabs", "[]") ?: "[]"
        val array = JSONArray(savedTabs)
        if (index in 0 until array.length()) {
            array.remove(index)
            prefs.edit().putString("saved_tabs", array.toString()).apply()
        }
    }

    private fun setupHomeTabs() {
        fun showHomePanel(panel: View, tab: Button) {
            homePanelBookmarks.visibility = View.GONE
            homePanelTabs.visibility = View.GONE
            homePanelHistory.visibility = View.GONE
            homePanelDownloads.visibility = View.GONE
            panel.visibility = View.VISIBLE

            activeHomeTab?.isSelected = false
            activeHomeTab = tab
            tab.isSelected = true
        }

        val tabListener = View.OnClickListener { v ->
            when(v.id) {
                R.id.home_tab_bookmarks -> showHomePanel(homePanelBookmarks, homeTabBookmarks)
                R.id.home_tab_tabs -> showHomePanel(homePanelTabs, homeTabTabs)
                R.id.home_tab_history -> showHomePanel(homePanelHistory, homeTabHistory)
                R.id.home_tab_downloads -> showHomePanel(homePanelDownloads, homeTabDownloads)
            }
        }

        val focusListener = View.OnFocusChangeListener { v, hasFocus ->
            if (hasFocus && v is Button) {
                when(v.id) {
                    R.id.home_tab_bookmarks -> showHomePanel(homePanelBookmarks, homeTabBookmarks)
                    R.id.home_tab_tabs -> showHomePanel(homePanelTabs, homeTabTabs)
                    R.id.home_tab_history -> showHomePanel(homePanelHistory, homeTabHistory)
                    R.id.home_tab_downloads -> showHomePanel(homePanelDownloads, homeTabDownloads)
                }
            }
        }

        homeTabBookmarks.setOnClickListener(tabListener)
        homeTabTabs.setOnClickListener(tabListener)
        homeTabHistory.setOnClickListener(tabListener)
        homeTabDownloads.setOnClickListener(tabListener)

        homeTabBookmarks.onFocusChangeListener = focusListener
        homeTabTabs.onFocusChangeListener = focusListener
        homeTabHistory.onFocusChangeListener = focusListener
        homeTabDownloads.onFocusChangeListener = focusListener

        showHomePanel(homePanelTabs, homeTabTabs)
    }

    private fun setupButtons() {
        val launchSettings = View.OnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        findViewById<ImageButton>(R.id.home_settings_btn).setOnClickListener(launchSettings)
        findViewById<ImageButton>(R.id.top_settings_btn).setOnClickListener(launchSettings)

        findViewById<ImageButton>(R.id.home_go_btn).setOnClickListener { loadUrlAndBrowse(homeUrlInput.text.toString()) }
        findViewById<Button>(R.id.btn_clear_history).setOnClickListener {
            prefs.edit().putString("history", "[]").apply()
            refreshLists()
        }

        findViewById<Button>(R.id.btn_view_all_downloads).setOnClickListener {
            startActivity(Intent(this, DownloadsActivity::class.java))
        }

        findViewById<ImageButton>(R.id.top_home_btn).setOnClickListener { showHomeScreen() }
        findViewById<ImageButton>(R.id.top_back_btn).setOnClickListener {
            val wv = currentWebView ?: return@setOnClickListener
            if (wv.canGoBack()) {
                wv.goBack()
            } else {
                if (webViews.size > 1) {
                    closeTab(currentTabIndex)
                } else {
                    showHomeScreen()
                }
            }
        }
        topForwardBtn.setOnClickListener { currentWebView?.goForward() }
        findViewById<ImageButton>(R.id.top_go_btn).setOnClickListener { loadUrlAndBrowse(topUrlInput.text.toString()) }
        findViewById<ImageButton>(R.id.top_refresh_btn).setOnClickListener { currentWebView?.reload() }
        findViewById<ImageButton>(R.id.btn_new_tab).setOnClickListener { createNewTab() }

        topAutoPlayBtn.setOnClickListener {
            videoTriggerPref = if (videoTriggerPref == 0) 1 else 0
            prefs.edit().putInt("video_trigger_pref", videoTriggerPref).apply()
            updateAutoPlayIcon()
            val status = if (videoTriggerPref == 0) "Enabled" else "Disabled"
            Toast.makeText(this, "Auto-play Video: $status", Toast.LENGTH_SHORT).show()
        }

        topRecordBtn.setOnClickListener {
            if (isRecordingAutoplay) stopRecording() else startRecording()
        }

        topFavBtn.setOnClickListener {
            val wv = currentWebView ?: return@setOnClickListener
            val url = wv.url ?: return@setOnClickListener
            if (isFavorited(url)) {
                removeFromList("favorites", url)
            } else {
                saveToList("favorites", url, wv.title ?: url, "${url.hashCode()}.png")
            }
        }

        topDownloadsBtn.setOnClickListener {
            startActivity(Intent(this, DownloadsActivity::class.java))
        }

        findViewById<Button>(R.id.ctx_new_tab).setOnClickListener {
            lastClickedUrl?.let { loadUrlAndBrowse(it, true) }
            contextMenu.visibility = View.GONE
            rootLayout.requestFocus()
        }
        findViewById<Button>(R.id.ctx_refresh).setOnClickListener {
            currentWebView?.reload()
            contextMenu.visibility = View.GONE
            rootLayout.requestFocus()
        }
        findViewById<Button>(R.id.ctx_block).setOnClickListener {
            blockElementAtCursor()
            contextMenu.visibility = View.GONE
            rootLayout.requestFocus()
        }

        val editorListener = TextView.OnEditorActionListener { v, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)) {
                loadUrlAndBrowse(v.text.toString())
                true
            } else false
        }
        homeUrlInput.setOnEditorActionListener(editorListener)
        topUrlInput.setOnEditorActionListener(editorListener)

        ViewUtils.applySmartDpadFocus(homeUrlInput)
        ViewUtils.applySmartDpadFocus(topUrlInput)
    }

    private fun startDownload(url: String, fileName: String) {
        val downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
        val request = android.app.DownloadManager.Request(Uri.parse(url))
            .setTitle(fileName)
            .setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, fileName)

        downloadManager.enqueue(request)
        Toast.makeText(this, "Starting download: $fileName", Toast.LENGTH_SHORT).show()

        saveToList("downloads", url, fileName, "")
        if (!isBrowsing) refreshLists()
    }

    private fun showHomeScreen() {
        isBrowsing = false
        cursor.visibility = View.GONE
        
        mainTabsLayout.visibility = View.VISIBLE
        // Default to browser tab if not already in streams
        if (streamsScreenLayout.visibility != View.VISIBLE) {
            homeScreenLayout.visibility = View.VISIBLE
            streamsScreenLayout.visibility = View.GONE
            btnTabBrowser.alpha = 1.0f
            btnTabStreams.alpha = 0.5f
        } else {
            homeScreenLayout.visibility = View.GONE
        }
        
        webContainer.visibility = View.GONE
        topBarLayout.visibility = View.GONE
        contextMenu.visibility = View.GONE

        for (wv in webViews) wv.visibility = View.GONE

        refreshLists()
        if (homeScreenLayout.visibility == View.VISIBLE) {
            findViewById<ImageButton>(R.id.home_settings_btn).requestFocus()
        } else {
            streamsSearchInput.requestFocus()
        }
    }

    private fun loadUrlAndBrowse(inputUrl: String, newTab: Boolean = false) {
        var url = inputUrl.trim()
        if (url.isNotEmpty()) {
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = if (url.contains(".") && !url.contains(" ")) "https://$url" else "https://www.google.com/search?q=$url"
            }
            isBrowsing = true
            homeScreenLayout.visibility = View.GONE
            streamsScreenLayout.visibility = View.GONE
            mainTabsLayout.visibility = View.GONE
            topBarLayout.visibility = View.GONE
            contextMenu.visibility = View.GONE
            webContainer.visibility = View.VISIBLE

            if (newTab || webViews.isEmpty()) {
                createNewTab(url)
            } else {
                currentWebView?.loadUrl(url)
                currentWebView?.visibility = View.VISIBLE
            }

            wakeCursor()

            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(homeUrlInput.windowToken, 0)
        }
    }

    private fun attemptVideoExtraction(view: View?, callback: WebChromeClient.CustomViewCallback?) {
        if (isExtractionActive || nativeVideoView.visibility == View.VISIBLE) return
        val wv = currentWebView ?: return

        isExtractionActive = true
        wv.evaluateJavascript("(function() { var v = document.querySelector('video'); return v ? (v.currentSrc || v.src) : ''; })();") { result ->
            val jsUrl = result?.replace("\"", "") ?: ""
            val candidates = mutableListOf<String>()

            if (jsUrl.startsWith("http") && !jsUrl.contains("blob:")) {
                candidates.add(jsUrl)
            }
            candidates.addAll(interceptedMediaUrls.keys)

            val finalCandidates = candidates.distinct()

            if (finalCandidates.isNotEmpty()) {
                if (extractVideoPref == 1 && finalCandidates.size == 1) {
                    launchNativeVideoPlayer(finalCandidates[0], callback, currentWebView?.title)
                    // keep isExtractionActive true until player is hidden or stream changes
                } else if (extractVideoPref == 2) {
                    showWebsiteFullscreen(view, callback)
                    isExtractionActive = false
                } else {
                    showStreamPicker(finalCandidates, view, callback)
                }
            } else {
                isExtractionActive = false
                if (view != null) {
                    Toast.makeText(this, "Playing via Website Player", Toast.LENGTH_SHORT).show()
                    showWebsiteFullscreen(view, callback)
                }
            }
        }
    }

    private fun showStreamPicker(streams: List<String>, view: View?, callback: WebChromeClient.CustomViewCallback?) {
        val streamInfos = mutableListOf<String>()
        val streamUrls = mutableListOf<String>()

        @Suppress("OPT_IN_USAGE")
        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.Main) {
            for (url in streams) {
                val headers = interceptedMediaUrls[url] ?: emptyMap()
                if (url.contains(".m3u8")) {
                    try {
                        val content = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            val connection = java.net.URL(url).openConnection() as HttpURLConnection
                            headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
                            connection.inputStream.bufferedReader().use { it.readText() }
                        }
                        if (content.contains("#EXT-X-STREAM-INF")) {
                            if (!streamUrls.contains(url)) {
                                streamInfos.add("Auto (Adaptive Quality)")
                                streamUrls.add(url)
                            }

                            val lines = content.lines()
                            for (i in lines.indices) {
                                val line = lines[i]
                                if (line.startsWith("#EXT-X-STREAM-INF")) {
                                    var info = "HLS Stream"
                                    val resMatch = Regex("RESOLUTION=(\\d+x\\d+)").find(line)
                                    if (resMatch != null) info += " (${resMatch.groupValues[1]})"
                                    val nameMatch = Regex("NAME=\"([^\"]+)\"").find(line)
                                    if (nameMatch != null) info = "${nameMatch.groupValues[1]} ($info)"

                                    for (j in i + 1 until lines.size) {
                                        val nextLine = lines[j].trim()
                                        if (nextLine.isNotEmpty() && !nextLine.startsWith("#")) {
                                            var fullUrl = nextLine
                                            if (!fullUrl.startsWith("http")) {
                                                try {
                                                    fullUrl = java.net.URL(java.net.URL(url), nextLine).toString()
                                                } catch (e: Exception) {
                                                    Log.e("TVBrowser", "URL Resolution failed", e)
                                                }
                                            }

                                            if (!streamUrls.contains(fullUrl)) {
                                                streamInfos.add(info)
                                                streamUrls.add(fullUrl)
                                                if (!interceptedMediaUrls.containsKey(fullUrl)) {
                                                    interceptedMediaUrls[fullUrl] = headers
                                                }
                                            }
                                            break
                                        }
                                    }
                                }
                            }
                        } else {
                            if (!streamUrls.contains(url)) {
                                streamInfos.add("HLS Playlist (Single)")
                                streamUrls.add(url)
                            }
                        }
                    } catch (e: Exception) {
                        if (!streamUrls.contains(url)) {
                            streamInfos.add("HLS Playlist (Unreachable)")
                            streamUrls.add(url)
                        }
                    }
                } else {
                    if (!streamUrls.contains(url)) {
                        streamInfos.add(if (url.endsWith(".mp4")) "MP4 Video" else "Stream")
                        streamUrls.add(url)
                    }
                }
            }

            if (streamInfos.isEmpty()) {
                isExtractionActive = false
                Toast.makeText(this@MainActivity, "No playable streams found", Toast.LENGTH_SHORT).show()
                showWebsiteFullscreen(view, callback)
                return@launch
            }

            val layout = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
            val checkBox = CheckBox(this@MainActivity).apply {
                text = "Always use Native Player"
                setPadding(50, 30, 50, 30)
                textSize = 16f
            }
            if (extractVideoPref == 0) layout.addView(checkBox)

            val listView = ListView(this@MainActivity)
            val adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_list_item_1, streamInfos)
            listView.adapter = adapter
            layout.addView(listView)

            val dialog = AlertDialog.Builder(this@MainActivity)
                .setTitle("Select Video Stream")
                .setView(layout)
                .setNegativeButton("Use Website Player") { d, _ -> d.cancel() }
                .setOnCancelListener {
                    isExtractionActive = false
                    showWebsiteFullscreen(view, callback)
                }
                .create()

            listView.setOnItemClickListener { _, _, position, _ ->
                if (checkBox.isChecked) {
                    extractVideoPref = 1
                    prefs.edit().putInt("extract_video_pref", 1).apply()
                }
                dialog.dismiss()
                // isExtractionActive stays true while player is open to prevent re-triggering
                launchNativeVideoPlayer(streamUrls[position], callback, currentWebView?.title)
            }
            dialog.show()
        }
    }

    private fun getLanguageInfo(url: String): Pair<String, String> {
        val uri = Uri.parse(url)
        val fileName = uri.lastPathSegment?.lowercase() ?: ""
        val langMap = mapOf(
            "en" to "English", "es" to "Spanish", "fr" to "French", "de" to "German",
            "it" to "Italian", "pt" to "Portuguese", "ru" to "Russian", "zh" to "Chinese",
            "ja" to "Japanese", "ko" to "Korean", "ar" to "Arabic", "hi" to "Hindi",
            "tr" to "Turkish", "vi" to "Vietnamese", "th" to "Thai", "id" to "Indonesian"
        )

        var code = "und"
        var label = "Unknown"

        // 1. Try to find full language names first
        for ((c, name) in langMap) {
            if (fileName.contains(name.lowercase())) {
                code = c
                label = name
                if (fileName.contains("sdh")) label += " (SDH)"
                if (fileName.contains("forced")) label += " (Forced)"
                return Pair(code, label)
            }
        }

        // 2. Try language codes with delimiters
        for ((c, name) in langMap) {
            if (fileName.contains("-$c.") || fileName.contains("_$c.") || fileName.contains(".$c.") || fileName.startsWith("$c.")) {
                code = c
                label = name
                if (fileName.contains("sdh")) label += " (SDH)"
                if (fileName.contains("forced")) label += " (Forced)"
                return Pair(code, label)
            }
        }

        // 3. Fallback to filename if it looks descriptive
        val cleanName = fileName.substringBeforeLast(".").replace(Regex("[-_]"), " ").trim()
        if (cleanName.length > 2 && !cleanName.all { it.isDigit() || it == ' ' }) {
            return Pair("und", cleanName.split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } })
        }

        return Pair(code, label)
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun launchNativeVideoPlayer(videoUrl: String, callback: WebChromeClient.CustomViewCallback?, title: String? = null, onFailure: (() -> Unit)? = null) {
        // Halt the WebView entirely
        currentWebView?.apply {
            onPause()
            pauseTimers()
            evaluateJavascript("""
                (function() {
                    var videos = document.getElementsByTagName('video');
                    for (var i = 0; i < videos.length; i++) {
                        videos[i].pause();
                        videos[i].src = "";
                        videos[i].load();
                    }
                })();
            """.trimIndent(), null)
        }

        customViewCallback = callback
        customViewContainer.visibility = View.VISIBLE
        nativeVideoView.visibility = View.VISIBLE
        nativeVideoView.keepScreenOn = true
        webContainer.visibility = View.GONE
        mainTabsLayout.visibility = View.GONE
        cursor.visibility = View.GONE
        lastVideoTitle = title
        isUpNextDismissed = false

        exoPlayer?.release()

        val headers = interceptedMediaUrls[videoUrl] ?: emptyMap()
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(currentWebView?.settings?.userAgentString ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(headers)

        val dataSourceFactory = DefaultDataSource.Factory(this, httpDataSourceFactory)

        exoPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(dataSourceFactory))
            .build()

        nativeVideoView.player = exoPlayer
        nativeVideoView.setShowNextButton(false)
        nativeVideoView.setShowPreviousButton(false)
        nativeVideoView.setShowSubtitleButton(true)

        exoPlayer?.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    val title = lastVideoTitle
                    if (title != null && lastScrapedItem != null) {
                        val headers = interceptedMediaUrls[videoUrl] ?: emptyMap()
                        addToRecentlyPlayedStreams(title, lastScrapedItem!!, lastScrapedSeason, lastScrapedEpisode, videoUrl, headers)
                    }
                    movementHandler.post(checkUpNextRunnable)
                } else if (state == Player.STATE_ENDED) {
                    movementHandler.removeCallbacks(checkUpNextRunnable)
                    if (getPythonConfig().optString("up_next_popup_pref", "Ask") == "Always") {
                        handleNextEpisodeAutoPlay()
                    }
                } else {
                    movementHandler.removeCallbacks(checkUpNextRunnable)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (onFailure != null) {
                    hideFullscreenVideo()
                    onFailure.invoke()
                } else {
                    handleExoPlayerError(error, videoUrl)
                }
            }
        })

        val mediaItemBuilder = MediaItem.Builder().setUri(videoUrl)

        val subtitleConfigs = interceptedSubtitleUrls.map { (subUrl, infoMap) ->
            val mimeType = if (subUrl.contains(".vtt")) MimeTypes.TEXT_VTT
            else if (subUrl.contains(".ass")) MimeTypes.TEXT_SSA
            else MimeTypes.APPLICATION_SUBRIP

            val label = infoMap["label"] ?: getLanguageInfo(subUrl).second
            val lang = if (!label.isNullOrEmpty()) null else (infoMap["lang"] ?: getLanguageInfo(subUrl).first)

            MediaItem.SubtitleConfiguration.Builder(Uri.parse(subUrl))
                .setMimeType(mimeType)
                .setLanguage(lang)
                .setLabel(label)
                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                .setRoleFlags(C.ROLE_FLAG_SUBTITLE)
                .build()
        }
        mediaItemBuilder.setSubtitleConfigurations(subtitleConfigs)

        exoPlayer?.setMediaItem(mediaItemBuilder.build())

        val resumeKey = if (title != null) "resume_stream_$title" else {
            val pageUrl = currentWebView?.url ?: ""
            if (pageUrl.isNotEmpty()) "resume_$pageUrl" else null
        }

        if (resumeKey != null) {
            val savedPos = prefs.getLong(resumeKey, 0L)
            if (savedPos > 5000L) { // Only resume if more than 5 seconds in
                exoPlayer?.seekTo(savedPos)
            }
        }

        if (!embeddedSubsEnabled) {
            exoPlayer?.trackSelectionParameters = exoPlayer?.trackSelectionParameters?.buildUpon()
                ?.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                ?.build() ?: exoPlayer!!.trackSelectionParameters
        }

        exoPlayer?.prepare()
        exoPlayer?.playWhenReady = true
        nativeVideoView.requestFocus()
        nativeVideoView.post {
            nativeVideoView.findViewById<View>(androidx.media3.ui.R.id.exo_play_pause)?.requestFocus()
        }
    }

    private fun handleExoPlayerError(error: PlaybackException, videoUrl: String) {
        val cause = error.cause?.message ?: "Unknown"
        Log.e("ExoPlayerDebug", "Playback failed: ", error)

        val fallback = prefs.getInt("exo_fallback_pref", 0)
        if (fallback == 1) { // Always
            hideFullscreenVideo()
            loadUrlAndBrowse(videoUrl, true)
            return
        } else if (fallback == 2) { // Never
            hideFullscreenVideo()
            Toast.makeText(this, "ExoPlayer Error: ${error.errorCodeName}\n${cause}", Toast.LENGTH_LONG).show()
            return
        }

        // Ask
        val checkBox = CheckBox(this).apply {
            text = "Never ask again (Remember choice)"
            setPadding(50, 20, 50, 20)
        }

        AlertDialog.Builder(this)
            .setTitle("Playback Error")
            .setMessage("ExoPlayer failed to play this stream. Would you like to try opening it in the browser instead?\n\nError: ${error.errorCodeName}")
            .setView(checkBox)
            .setPositiveButton("Open in Browser") { _, _ ->
                if (checkBox.isChecked) {
                    prefs.edit().putInt("exo_fallback_pref", 1).apply()
                }
                hideFullscreenVideo()
                loadUrlAndBrowse(videoUrl, true)
            }
            .setNegativeButton("Cancel") { _, _ ->
                if (checkBox.isChecked) {
                    prefs.edit().putInt("exo_fallback_pref", 2).apply()
                }
                hideFullscreenVideo()
            }
            .show()
    }

    private fun showWebsiteFullscreen(view: View?, callback: WebChromeClient.CustomViewCallback?) {
        mCustomView = view
        customViewContainer.addView(mCustomView)
        customViewContainer.visibility = View.VISIBLE
        webContainer.visibility = View.GONE
        mainTabsLayout.visibility = View.GONE
        customViewCallback = callback
        wakeCursor()
    }

    private fun hideFullscreenVideo() {
        if (nativeVideoView.visibility == View.VISIBLE) {
            val title = lastVideoTitle
            val resumeKey = if (title != null) "resume_stream_$title" else {
                val pageUrl = currentWebView?.url ?: ""
                if (pageUrl.isNotEmpty()) "resume_$pageUrl" else null
            }

            if (resumeKey != null && exoPlayer != null && exoPlayer!!.playerError == null) {
                val pos = exoPlayer!!.currentPosition
                val dur = exoPlayer!!.duration
                if (dur > 0) {
                    if (pos > 5000L && pos < dur - 10000L) { // Save if between 5s and 10s from end
                        prefs.edit().putLong(resumeKey, pos).apply()
                        if (pos >= dur - 300000L) { // Within 5 minutes of end
                            pendingNextEpisode = true
                        }
                    } else if (pos >= dur - 10000L) { // If near end, clear it
                        prefs.edit().remove(resumeKey).apply()
                        pendingNextEpisode = true
                    }
                    // If pos <= 5000L, we do nothing to avoid overwriting a valid saved position with 0 on start/error
                }
            }

            nativeVideoView.visibility = View.GONE
            customViewContainer.visibility = View.GONE
            nativeVideoView.keepScreenOn = false
            exoPlayer?.stop()
            exoPlayer?.release()
            exoPlayer = null
            nativeVideoView.player = null

            if (upNextPopup != null) {
                customViewContainer.removeView(upNextPopup)
                upNextPopup = null
            }
            movementHandler.removeCallbacks(checkUpNextRunnable)

            // Resume scraping when player is closed
            isInteractingWithSources = false
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val py = Python.getInstance()
                    py.getModule("main").callAttr("resume_scrape")
                } catch (e: Exception) {}
            }
        }

        customViewContainer.visibility = View.GONE
        isExtractionActive = false

        currentWebView?.apply {
            onResume()
            resumeTimers()
            resumeTimers()
        }

        if (mCustomView != null) {
            mCustomView?.visibility = View.GONE
            customViewContainer.removeView(mCustomView)
            mCustomView = null
        }

        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        webContainer.visibility = View.VISIBLE
        if (!isBrowsing) {
            mainTabsLayout.visibility = View.VISIBLE
            if (streamsScreenLayout.visibility == View.VISIBLE) {
                if (streamsResultsContainer.childCount > 0) {
                    streamsResultsContainer.getChildAt(0).requestFocus()
                } else {
                    streamsSearchInput.requestFocus()
                }
            }
        }
        wakeCursor()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView(wv: WebView) {
        wv.isFocusable = true
        wv.isFocusableInTouchMode = true
        wv.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS

        wv.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false; setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                isAlgorithmicDarkeningAllowed = !isLightTheme
            }
        }

        wv.addJavascriptInterface(WebAppInterface(), "AndroidAutoplay")

        wv.setDownloadListener { url, userAgent, contentDisposition, mimetype, contentLength ->
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimetype)
            AlertDialog.Builder(this)
                .setTitle("Download File?")
                .setMessage("Do you want to download $fileName?\nSize: ${contentLength / 1024} KB")
                .setPositiveButton("Download") { _, _ -> startDownload(url, fileName) }
                .setNegativeButton("Cancel", null).show()
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean {
                if (silentPopupBlock) {
                    return true
                }
                val checkBox = CheckBox(this@MainActivity).apply { text = "Never ask again (Silent Block)"; setPadding(50, 0, 50, 0) }
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Popup Blocked")
                    .setMessage("Do you want to allow a popup from this site?")
                    .setView(checkBox)
                    .setPositiveButton("Allow") { _, _ ->
                        if (checkBox.isChecked) { silentPopupBlock = true; prefs.edit().putBoolean("silent_popup_block", true).apply() }
                        val transport = resultMsg?.obj as? WebView.WebViewTransport
                        transport?.webView = createNewTab()
                        resultMsg?.sendToTarget()
                    }
                    .setNegativeButton("Block") { _, _ ->
                        if (checkBox.isChecked) { silentPopupBlock = true; prefs.edit().putBoolean("silent_popup_block", true).apply() }
                    }.show()
                return true
            }

            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                attemptVideoExtraction(view, callback)
            }

            override fun onHideCustomView() {
                hideFullscreenVideo()
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                super.onReceivedTitle(view, title)
                // Clear intercepted URLs on title change, often happens on SPA navigation
                interceptedMediaUrls.clear()
                interceptedSubtitleUrls.clear()
            }

            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                // Silencing console messages stops them from hitting Logcat and causing lag
                return true
            }
        }

        wv.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                interceptedMediaUrls.clear()
                interceptedSubtitleUrls.clear()
                if (url != null && view != null) {
                    val host = Uri.parse(url).host ?: ""
                    webViewHosts[view] = host
                    if (view == currentWebView) currentHost = host
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (url == null || url == "about:blank" || view == null) return

                val index = webViews.indexOf(view)
                if (index != -1) {
                    val tabView = tabsContainer.getChildAt(index)
                    tabView?.findViewById<TextView>(R.id.tab_title)?.text = view.title ?: url
                }

                if (view == currentWebView) {
                    currentHost = Uri.parse(url).host ?: ""
                    topUrlInput.setText(url)
                    updateFavIcon()
                }

                injectClickjackPrevention(view)
                applyBlockedElements(view)
                saveTabs()
                if (videoTriggerPref == 0) {
                    triggerAutoPlayClicks(view)
                }
                if (navigationModePref == 1 || isSelectionMode) {
                    initDpadNav()
                }
                view.postDelayed({ saveSnapshot(url, view.title ?: "Website") }, 2500)
            }

            private fun triggerAutoPlayClicks(wv: WebView) {
                val url = wv.url ?: return
                val profilesJson = prefs.getString("autoplay_profiles", "[]") ?: "[]"
                val profiles = JSONArray(profilesJson)
                
                var matchedAny = false
                var executedAny = false
                // Apply all matching and enabled profiles
                for (i in 0 until profiles.length()) {
                    val obj = profiles.getJSONObject(i)
                    val patterns = obj.getJSONArray("urlPatterns")
                    var matchesPattern = false
                    for (j in 0 until patterns.length()) {
                        if (matchUrlPattern(patterns.getString(j), url)) {
                            matchesPattern = true
                            break
                        }
                    }
                    
                    if (matchesPattern) {
                        matchedAny = true
                        if (obj.optBoolean("enabled", true)) {
                            executedAny = true
                            val scriptToRun = if (obj.optBoolean("use_script", true)) {
                                obj.getString("script")
                            } else {
                                val selectors = obj.optJSONArray("selectors")
                                if (selectors != null && selectors.length() > 0) {
                                    SettingsActivity.generateSelectorScript(selectors)
                                } else {
                                    obj.getString("script")
                                }
                            }
                            wv.evaluateJavascript(scriptToRun, null)
                        }
                    }
                }

                if (!executedAny && !matchedAny) {
                    // Fallback for fresh installs where profiles list is empty
                    // OR when no user-defined profile (including the 'Default' one in the list) matches the URL.
                    wv.evaluateJavascript(SettingsActivity.DEFAULT_AUTOPLAY_SCRIPT.trimIndent(), null)
                }

                // Android-side: Periodically attempt extraction for the next 15 seconds
                val handler = android.os.Handler(android.os.Looper.getMainLooper())
                var attempts = 0
                val maxAttempts = 8 // 8 * 2s = 16s total monitoring
                
                val extractionTask = object : Runnable {
                    override fun run() {
                        if (videoTriggerPref != 0 || !isBrowsing || nativeVideoView.visibility == View.VISIBLE || attempts >= maxAttempts) {
                            return
                        }
                        attemptVideoExtraction(null, null)
                        attempts++
                        handler.postDelayed(this, 2000)
                    }
                }
                handler.post(extractionTask)
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val requestedUrl = request?.url?.toString()
                if (requestedUrl != null) {
                    // Block aggressive anti-debug/spam scripts that lag the browser
                    if (requestedUrl.contains("disable-devtool") || requestedUrl.contains("devtools-detector")) {
                        return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream("".toByteArray()))
                    }

                    if (requestedUrl.contains(".m3u8") || requestedUrl.contains(".mp4") || requestedUrl.contains(".mkv")) {
                        val wasEmpty = interceptedMediaUrls.isEmpty()
                        interceptedMediaUrls[requestedUrl] = request.requestHeaders
                        if (videoTriggerPref == 0 && isBrowsing && wasEmpty) {
                            view?.post { attemptVideoExtraction(null, null) }
                        }
                    }
                    if (requestedUrl.contains(".srt") || requestedUrl.contains(".vtt") || requestedUrl.contains(".ass")) {
                        interceptedSubtitleUrls[requestedUrl] = request.requestHeaders
                    }

                    val filterOption = AdBlockUtils.mapRequestToFilterOption(request)
                    val host = webViewHosts[view] ?: currentHost
                    if (AdBlockManager.shouldBlock(requestedUrl, filterOption, host)) {
                        return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream("".toByteArray()))
                    }
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                if (url.startsWith("intent://") || url.startsWith("market://")) return true
                view?.loadUrl(url)
                return true
            }
        }
    }

    private fun seekVideo(direction: Int, repeatCount: Int) {
        val player = exoPlayer ?: return
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastSeekTime < 150) return // Throttle seeking

        lastSeekTime = currentTime
        // Starts at 5s, grows quadratically to allow skipping long durations
        seekIncrement = (5000L + (repeatCount * repeatCount) * 100L).coerceAtMost(300000L)

        val newPos = player.currentPosition + (direction * seekIncrement)
        val duration = if (player.duration > 0) player.duration else 0L
        player.seekTo(newPos.coerceIn(0, duration))
    }

    @SuppressLint("UnsafeOptInUsageError")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val isImeVisible = rootLayout.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true
            if (isImeVisible) {
                if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_DOWN) {
                    return super.dispatchKeyEvent(event)
                }
                if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP || event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
                    event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ||
                    event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER) {
                    return super.dispatchKeyEvent(event)
                }
            }
        }

        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_MENU) {
            if (contextMenu.visibility == View.VISIBLE) {
                contextMenu.visibility = View.GONE
                return true
            }
            toggleTopBar()
            return true
        }

        if (contextMenu.visibility == View.VISIBLE) {
            if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_BACK) {
                contextMenu.visibility = View.GONE
                return true
            }
            return super.dispatchKeyEvent(event)
        }

        if (handleMovementKey(event)) return true

        if (isNativeVideoPlaying()) {
            if (upNextPopup == null && !nativeVideoView.hasFocus()) {
                nativeVideoView.requestFocus()
            }
            if (event.action == KeyEvent.ACTION_DOWN) {
                // If Up/Down pressed and popup is visible, force focus to it
                if (upNextPopup != null && (event.keyCode == KeyEvent.KEYCODE_DPAD_UP || event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN)) {
                    if (!upNextPopup!!.isFocused) {
                        upNextPopup!!.requestFocus()
                        return true
                    }
                }

                if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                    val isControllerVisible = nativeVideoView.isControllerFullyVisible
                    
                    // If the popup is specifically FOCUSED, dismiss it
                    if (upNextPopup != null && upNextPopup!!.isFocused) {
                        customViewContainer.removeView(upNextPopup)
                        upNextPopup = null
                        isUpNextDismissed = true
                        nativeVideoView.requestFocus()
                        return true
                    }
                    
                    // If controller is visible, hide it
                    if (isControllerVisible) {
                        nativeVideoView.hideController()
                        return true
                    }
                    
                    // Otherwise (popup focused or hidden, controller hidden), exit video player
                    hideFullscreenVideo()
                    return true
                }

                val isControllerVisible = nativeVideoView.isControllerFullyVisible
                if (!isControllerVisible) {
                    when (event.keyCode) {
                        KeyEvent.KEYCODE_DPAD_LEFT -> { seekVideo(-1, event.repeatCount); return true }
                        KeyEvent.KEYCODE_DPAD_RIGHT -> { seekVideo(1, event.repeatCount); return true }
                    }
                } else {
                    val focusedView = nativeVideoView.findFocus() ?: currentFocus
                    val idName = try { focusedView?.id?.let { resources.getResourceEntryName(it) } ?: "" } catch (e: Exception) { "" }
                    val isCenterButton = idName.contains("play") || idName.contains("pause") || 
                                       idName.contains("rew") || idName.contains("ffwd") ||
                                       idName.contains("prev") || idName.contains("next")
                    val isButton = focusedView is ImageButton || focusedView is Button

                    if (!isButton || isCenterButton) {
                        when (event.keyCode) {
                            KeyEvent.KEYCODE_DPAD_LEFT -> { seekVideo(-1, event.repeatCount); return true }
                            KeyEvent.KEYCODE_DPAD_RIGHT -> { seekVideo(1, event.repeatCount); return true }
                            KeyEvent.KEYCODE_DPAD_UP -> {
                                if (!isCenterButton) {
                                    val playPauseId = resources.getIdentifier("exo_play_pause", "id", "androidx.media3.ui")
                                    if (playPauseId != 0) {
                                        val playPause = nativeVideoView.findViewById<View>(playPauseId)
                                        if (playPause != null && playPause.visibility == View.VISIBLE) {
                                            playPause.requestFocus()
                                            return true
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            return super.dispatchKeyEvent(event)
        }

        if (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER) {
            if (isSelectionMode) {
                if (event.action == KeyEvent.ACTION_UP) selectDpadElement()
                return true
            }
            if (navigationModePref == 1 && isBrowsing && topBarLayout.visibility == View.GONE && contextMenu.visibility == View.GONE && !isLongPressing) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    if (event.repeatCount == 0) {
                        okDownTime = System.currentTimeMillis()
                    } else if (!isLongPressing && okDownTime > 0 && System.currentTimeMillis() - okDownTime > LONG_PRESS_THRESHOLD) {
                        isLongPressing = true
                        showContextMenuAtFocused()
                    }
                } else if (event.action == KeyEvent.ACTION_UP) {
                    if (!isLongPressing) {
                        currentWebView?.evaluateJavascript("if(window.navHelper) window.navHelper.clickFocused();", null)
                    }
                    okDownTime = 0
                    isLongPressing = false
                }
                return true
            }
        }

        if (!isBrowsing || topBarLayout.visibility == View.VISIBLE) {
            if (topBarLayout.visibility == View.VISIBLE && event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN && event.action == KeyEvent.ACTION_DOWN) {
                topBarLayout.visibility = View.GONE
                wakeCursor()
                return true
            }
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    isBackHandled = false
                    if (customViewContainer.visibility == View.VISIBLE) {
                        hideFullscreenVideo()
                        isBackHandled = true
                        return true
                    } else if (topBarLayout.visibility == View.VISIBLE) {
                        topBarLayout.visibility = View.GONE
                        wakeCursor()
                        isBackHandled = true
                        return true
                    } else if (!isBrowsing && tvSelectionLayout.visibility == View.VISIBLE) {
                        btnTvBack.performClick()
                        isBackHandled = true
                        return true
                    } else if (!isBrowsing && streamsScreenLayout.visibility == View.VISIBLE) {
                        if (btnStreamsBack.visibility == View.VISIBLE) {
                            btnStreamsBack.performClick()
                            isBackHandled = true
                            return true
                        }
                    }
                } else if (event.action == KeyEvent.ACTION_UP) {
                    if (isBackHandled) {
                        isBackHandled = false
                        return true
                    }
                }
            }
            return super.dispatchKeyEvent(event)
        }

        if (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (event.repeatCount == 0) {
                    okDownTime = System.currentTimeMillis()
                    isLongPressing = false
                } else if (!isLongPressing && okDownTime > 0 && System.currentTimeMillis() - okDownTime > LONG_PRESS_THRESHOLD) {
                    isLongPressing = true
                    showContextMenu()
                }
                return true
            } else if (event.action == KeyEvent.ACTION_UP) {
                if (!isLongPressing) {
                    simulateClick()
                }
                okDownTime = 0
                isLongPressing = false
                return true
            }
            if (isLongPressing) return true
        }

        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (isSelectionMode) {
                isSelectionMode = false
                wakeCursor()
                // If we want to return to the dialog, we'd need to store the previous data.
                // For now, let's just exit the mode.
                return true
            }
            if (customViewContainer.visibility == View.VISIBLE) hideFullscreenVideo()
            else if (currentWebView?.canGoBack() == true) currentWebView?.goBack()
            else {
                if (webViews.size > 1) closeTab(currentTabIndex)
                else showHomeScreen()
            }
            return true
        }

        return super.dispatchKeyEvent(event)
    }

    private fun handleMovementKey(event: KeyEvent): Boolean {
        if (!isBrowsing || topBarLayout.visibility == View.VISIBLE || isNativeVideoPlaying() || contextMenu.visibility == View.VISIBLE || isLongPressing || currentDialog?.isShowing == true) {
            if (event.action == KeyEvent.ACTION_UP) {
                keyStates[event.keyCode] = false
            }
            return false
        }

        val keyCode = event.keyCode
        val isDown = event.action == KeyEvent.ACTION_DOWN

        if (navigationModePref == 1 || isSelectionMode) {
            if (isDown) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> handleDpadNav("up")
                    KeyEvent.KEYCODE_DPAD_DOWN -> handleDpadNav("down")
                    KeyEvent.KEYCODE_DPAD_LEFT -> handleDpadNav("left")
                    KeyEvent.KEYCODE_DPAD_RIGHT -> handleDpadNav("right")
                    else -> return false
                }
            }
            return true
        }

        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN -> {
                keyStates[keyCode] = isDown
                if (isDown) {
                    wakeCursor()
                    if (!isMovementLoopRunning) {
                        isMovementLoopRunning = true
                        movementHandler.post(movementRunnable)
                    }
                }
                return true
            }
        }
        return false
    }

    private var lastMovementTime = 0L
    private var isHoverCheckPending = false

    private fun updateMovement(): Boolean {
        if (!isBrowsing || topBarLayout.visibility == View.VISIBLE || contextMenu.visibility == View.VISIBLE || isLongPressing || currentDialog?.isShowing == true) {
            cursorVelocityX = 0f
            cursorVelocityY = 0f
            scrollVelocityY = 0f
            lastMovementTime = 0L
            return false
        }
        val currentTime = System.currentTimeMillis()
        if (lastMovementTime == 0L) {
            lastMovementTime = currentTime
            return true
        }
        val dt = (currentTime - lastMovementTime) / 16.6f
        lastMovementTime = currentTime

        var moved = false
        val accel = Math.pow(ACCELERATION.toDouble(), dt.toDouble()).toFloat()

        if (keyStates[KeyEvent.KEYCODE_DPAD_LEFT] == true) {
            cursorVelocityX = if (cursorVelocityX >= 0) -MIN_VELOCITY else (cursorVelocityX * accel).coerceIn(-MAX_CURSOR_VELOCITY, -MIN_VELOCITY)
            moved = true
        } else if (keyStates[KeyEvent.KEYCODE_DPAD_RIGHT] == true) {
            cursorVelocityX = if (cursorVelocityX <= 0) MIN_VELOCITY else (cursorVelocityX * accel).coerceIn(MIN_VELOCITY, MAX_CURSOR_VELOCITY)
            moved = true
        } else {
            cursorVelocityX = 0f
        }

        if (keyStates[KeyEvent.KEYCODE_DPAD_UP] == true) {
            cursorVelocityY = if (cursorVelocityY >= 0) -MIN_VELOCITY else (cursorVelocityY * accel).coerceIn(-MAX_CURSOR_VELOCITY, -MIN_VELOCITY)
            moved = true
        } else if (keyStates[KeyEvent.KEYCODE_DPAD_DOWN] == true) {
            cursorVelocityY = if (cursorVelocityY <= 0) MIN_VELOCITY else (cursorVelocityY * accel).coerceIn(MIN_VELOCITY, MAX_CURSOR_VELOCITY)
            moved = true
        } else {
            cursorVelocityY = 0f
        }

        if (keyStates[KeyEvent.KEYCODE_PAGE_UP] == true) {
            scrollVelocityY = if (scrollVelocityY >= 0) -MIN_VELOCITY * 5f else (scrollVelocityY * 1.1f).coerceIn(-MAX_SCROLL_VELOCITY, -MIN_VELOCITY)
            moved = true
        } else if (keyStates[KeyEvent.KEYCODE_PAGE_DOWN] == true) {
            scrollVelocityY = if (scrollVelocityY <= 0) MIN_VELOCITY * 5f else (scrollVelocityY * 1.1f).coerceIn(MIN_VELOCITY, MAX_SCROLL_VELOCITY)
            moved = true
        } else {
            scrollVelocityY = 0f
        }

        if (cursorVelocityX != 0f || cursorVelocityY != 0f) {
            moveCursor(cursorVelocityX * dt, cursorVelocityY * dt)
        }

        if (scrollVelocityY != 0f) {
            simulateScroll(scrollVelocityY * dt)
        }

        if (!moved) lastMovementTime = 0L
        return moved
    }

    private fun simulateScroll(dy: Float) {
        val wv = currentWebView ?: return
        val downTime = SystemClock.uptimeMillis()
        val eventTime = SystemClock.uptimeMillis()

        // Coordinate conversion is crucial for dispatchGenericMotionEvent to work correctly on elements
        val location = IntArray(2)
        wv.getLocationOnScreen(location)
        val relativeX = cursorX - location[0]
        val relativeY = cursorY - location[1]

        val properties = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { 
            x = relativeX; y = relativeY; pressure = 1f; size = 1f 
            setAxisValue(MotionEvent.AXIS_VSCROLL, -dy / 20f) 
        })

        val event = MotionEvent.obtain(downTime, eventTime, MotionEvent.ACTION_SCROLL, 1, properties, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0)
        wv.dispatchGenericMotionEvent(event)
        event.recycle()
    }

    private fun checkHover() {
        if (isHoverCheckPending || !isBrowsing || cursor.visibility != View.VISIBLE) return

        val wv = currentWebView ?: return
        val density = resources.displayMetrics.density
        val x = (cursorX / density).toInt()
        val y = (cursorY / density).toInt()

        isHoverCheckPending = true
        wv.evaluateJavascript("""
            (function() {
                var el = document.elementFromPoint($x, $y);
                var isLink = false;
                var temp = el;
                while(temp) {
                    if (temp.tagName === 'A' || temp.tagName === 'BUTTON' || (temp.onclick) || temp.getAttribute('role') === 'button') {
                        isLink = true; break;
                    }
                    temp = temp.parentElement;
                }
                return isLink;
            })()
        """.trimIndent()) { result ->
            isHoverCheckPending = false
            val isLink = result?.toBoolean() ?: false
            if (isLink) {
                cursor.setImageResource(R.drawable.ic_hand)
            } else {
                cursor.setImageResource(R.drawable.ic_cursor)
            }
        }
    }

    private fun moveCursor(dx: Float, dy: Float) {
        val maxX = rootLayout.width.toFloat().takeIf { it > 0 } ?: 1920f
        val maxY = rootLayout.height.toFloat().takeIf { it > 0 } ?: 1080f

        cursorX = (cursorX + dx).coerceIn(0f, maxX)
        cursorY = (cursorY + dy).coerceIn(0f, maxY)
        
        // Tip of the arrow in ic_cursor.xml is at (4,4) in a 24x24 viewport.
        // The ImageView is 32dp x 32dp. Tip offset is (4/24) * 32 = 5.33dp.
        val density = resources.displayMetrics.density
        val tipOffset = (4f / 24f) * 32f * density
        cursor.x = cursorX - tipOffset
        cursor.y = cursorY - tipOffset

        if (isBrowsing && customViewContainer.visibility != View.VISIBLE) {
            val wv = currentWebView
            if (scrollTopbarEnabled && cursorY <= 0 && (wv?.scrollY ?: 0) == 0 && dy < 0 && !isLongPressing && contextMenu.visibility == View.GONE && currentDialog?.isShowing != true) {
                topBarLayout.visibility = View.VISIBLE
                mainTabsLayout.visibility = View.GONE // Ensure main tabs stay hidden during browsing
                cursor.visibility = View.GONE
                topUrlInput.requestFocus()
            }
            
            // Only scroll if pushing against the extreme edge AND moving in that direction
            if (cursorY >= maxY - 1f && dy > 0) {
                wv?.scrollBy(0, 15)
            } else if (cursorY <= 0f && (wv?.scrollY ?: 0) > 0 && dy < 0) {
                wv?.scrollBy(0, -15)
            }
        }
    }

    private fun createNewTab(url: String? = null, switchTo: Boolean = true, title: String? = null): WebView {
        val newWebView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            visibility = View.GONE
            tag = title
        }
        setupWebView(newWebView)
        webContainer.addView(newWebView)
        webViews.add(newWebView)

        val tabView = layoutInflater.inflate(R.layout.tab_item, tabsContainer, false)
        val titleTxt = tabView.findViewById<TextView>(R.id.tab_title)
        val progress = tabView.findViewById<ProgressBar>(R.id.tab_close_progress)

        titleTxt.text = title ?: url ?: "New Tab"

        tabView.setOnClickListener { switchTab(webViews.indexOf(newWebView)) }
        setupTabLongPress(tabView, progress, webViews.indexOf(newWebView))

        tabsContainer.addView(tabView)

        if (url != null) newWebView.loadUrl(url)

        saveTabs()
        if (switchTo) switchTab(webViews.size - 1)
        return newWebView
    }

    private fun saveTabs() {
        val array = JSONArray()
        for (wv in webViews) {
            val url = wv.url ?: "about:blank"
            if (url == "about:blank") continue
            val obj = JSONObject().apply {
                put("url", url)
                put("title", wv.title ?: wv.tag as? String ?: url)
            }
            array.put(obj)
        }
        prefs.edit().putString("saved_tabs", array.toString()).apply()
    }

    private fun restoreAllTabs() {
        val savedTabs = prefs.getString("saved_tabs", "[]") ?: "[]"
        try {
            val array = JSONArray(savedTabs)
            if (array.length() == 0) return

            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val url = obj.optString("url")
                if (url.isNotEmpty()) {
                    val title = obj.optString("title")
                    createNewTab(url, switchTo = false, title = title)
                }
            }
        } catch (e: Exception) {
            Log.e("TVBrowser", "Error restoring tabs: ${e.message}")
        }
        if (!isBrowsing) refreshLists()
    }

    private fun checkStartupTabs() {
        val pref = prefs.getInt("restore_tabs_pref", 0)
        val savedTabs = prefs.getString("saved_tabs", "[]") ?: "[]"
        if (savedTabs == "[]") return

        when (pref) {
            1 -> restoreAllTabs()
            0 -> {
                AlertDialog.Builder(this)
                    .setTitle("Restore Session?")
                    .setMessage("Do you want to restore your previous tabs?")
                    .setPositiveButton("Restore All") { _, _ -> restoreAllTabs() }
                    .setNegativeButton("New Session") { _, _ -> prefs.edit().putString("saved_tabs", "[]").apply(); refreshLists() }
                    .show()
            }
        }
    }

    private fun switchTab(index: Int) {
        if (index !in webViews.indices) return

        currentWebView?.visibility = View.GONE
        currentTabIndex = index

        if (!isBrowsing) {
            isBrowsing = true
            homeScreenLayout.visibility = View.GONE
            mainTabsLayout.visibility = View.GONE
            webContainer.visibility = View.VISIBLE
            wakeCursor()
        }

        currentWebView?.visibility = View.VISIBLE

        for (i in 0 until tabsContainer.childCount) {
            val child = tabsContainer.getChildAt(i)
            child.isSelected = (i == index)
            child.alpha = if (i == index) 1.0f else 0.5f
        }

        currentWebView?.let {
            val url = it.url?.takeIf { u -> u != "about:blank" } ?: ""
            topUrlInput.setText(url)
            updateFavIcon()
            it.requestFocus()
        }
    }

    private fun closeTab(index: Int) {
        if (index !in webViews.indices) return

        val wv = webViews.removeAt(index)
        webContainer.removeView(wv)
        wv.destroy()
        tabsContainer.removeViewAt(index)

        saveTabs()
        if (webViews.isEmpty()) {
            showHomeScreen()
        } else {
            if (currentTabIndex >= webViews.size) {
                currentTabIndex = webViews.size - 1
            }
            if (isBrowsing) {
                switchTab(currentTabIndex)
            } else {
                for (i in 0 until tabsContainer.childCount) {
                    val child = tabsContainer.getChildAt(i)
                    child.isSelected = (i == currentTabIndex)
                    child.alpha = if (i == currentTabIndex) 1.0f else 0.5f
                }
            }
        }
        if (!isBrowsing) refreshLists("tabs", index)
    }

    private fun showContextMenu() {
        val wv = currentWebView ?: return
        val density = resources.displayMetrics.density
        val x = (cursorX / density).toInt()
        val y = (cursorY / density).toInt()

        // Stop any ongoing movement when the menu is requested
        keyStates.clear()
        isMovementLoopRunning = false
        cursorVelocityX = 0f
        cursorVelocityY = 0f
        scrollVelocityY = 0f
        isLongPressing = false

        wv.evaluateJavascript("""
            (function() {
                var el = document.elementFromPoint($x, $y);
                var link = '';
                var temp = el;
                while(temp && temp.tagName !== 'A') temp = temp.parentElement;
                if(temp) link = temp.href;
                return {link: link};
            })()
        """.trimIndent()) { result ->
            if (result == null || result == "null") return@evaluateJavascript
            try {
                val json = JSONObject(result)
                val link = json.optString("link")
                lastClickedUrl = link
                findViewById<Button>(R.id.ctx_new_tab).visibility = if (link.isNotEmpty()) View.VISIBLE else View.GONE
            } catch (e: Exception) { lastClickedUrl = "" }

            contextMenu.visibility = View.VISIBLE
            contextMenu.x = cursorX.coerceAtMost((rootLayout.width - contextMenu.width).toFloat())
            contextMenu.y = cursorY.coerceAtMost((rootLayout.height - contextMenu.height).toFloat())
            contextMenu.requestFocus()
        }
    }

    private fun toggleTopBar() {
        if (topBarLayout.visibility == View.VISIBLE) {
            topBarLayout.visibility = View.GONE
            if (!isBrowsing) mainTabsLayout.visibility = View.VISIBLE
            wakeCursor()
        } else {
            topBarLayout.visibility = View.VISIBLE
            if (!isBrowsing) mainTabsLayout.visibility = View.VISIBLE
            else mainTabsLayout.visibility = View.GONE // Hide mode switch tabs when browsing
            cursor.visibility = View.GONE
            topUrlInput.requestFocus()
        }
    }

    private fun blockElementAtCursor() {
        val wv = currentWebView ?: return
        val density = resources.displayMetrics.density
        val x = (cursorX / density).toInt()
        val y = (cursorY / density).toInt()

        val setupScript = """
            (function() {
                if (!window.blockerHelper) {
                    window.blockerHelper = {
                        currentEl: null,
                        candidateEls: [],
                        candidateIndex: 0,
                        highlightStyle: null,
                        init: function() {
                            // Handled in setHighlight
                        },
                        setHighlight: function(selector) {
                            this.clearHighlight();
                            const styleText = selector + ' { outline: 4px solid #00BCD4 !important; outline-offset: -4px !important; box-shadow: 0 0 20px rgba(0, 188, 212, 0.8) !important; position: relative !important; z-index: 2147483646 !important; }';
                            const apply = (win) => {
                                try {
                                    let style = win.document.getElementById('poobi-blocker-highlight');
                                    if (!style) {
                                        style = win.document.createElement('style');
                                        style.id = 'poobi-blocker-highlight';
                                        win.document.head.appendChild(style);
                                    }
                                    style.innerHTML = styleText;
                                    Array.from(win.frames).forEach(f => { try { apply(f); } catch(e) {} });
                                } catch(e) {}
                            };
                            apply(window);
                        },
                        clearHighlight: function() {
                            const clear = (win) => {
                                try {
                                    let style = win.document.getElementById('poobi-blocker-highlight');
                                    if (style) style.innerHTML = '';
                                    Array.from(win.frames).forEach(f => { try { clear(f); } catch(e) {} });
                                } catch(e) {}
                            };
                            clear(window);
                        },
                        selectAt: function(x, y) {
                            try {
                                var candidates = [];
                                function collect(win, rx, ry) {
                                    try {
                                        var els = win.document.elementsFromPoint(rx, ry);
                                        if (!els || els.length === 0) return;
                                        for (var el of Array.from(els)) {
                                            if (el.tagName === 'HTML' || el.tagName === 'BODY') continue;
                                            if (el.tagName === 'IFRAME') {
                                                try {
                                                    var rect = el.getBoundingClientRect();
                                                    collect(el.contentWindow, rx - rect.left, ry - rect.top);
                                                } catch(e) {}
                                            }
                                            candidates.push(el);
                                        }
                                    } catch(e) {}
                                }
                                collect(window, x, y);
                                this.candidateEls = candidates.filter((el, idx) => candidates.indexOf(el) === idx);
                                if (this.candidateEls.length === 0) {
                                    return {error: 'No valid elements found at this position (only Body/HTML)'};
                                }
                                this.candidateIndex = 0;
                                this.currentEl = this.candidateEls[0];
                                return this.getOptions();
                            } catch(e) {
                                return {error: 'Script Error: ' + e.message};
                            }
                        },
                        nextCandidate: function() {
                            if (this.candidateEls.length > 1) {
                                this.candidateIndex = (this.candidateIndex + 1) % this.candidateEls.length;
                                this.currentEl = this.candidateEls[this.candidateIndex];
                            }
                            return this.getOptions();
                        },
                        selectParent: function() {
                            if (this.currentEl && this.currentEl.parentElement && this.currentEl.parentElement !== document.documentElement) {
                                this.currentEl = this.currentEl.parentElement;
                            }
                            return this.getOptions();
                        },
                        getOptions: function() {
                            if (!this.currentEl) return {error: 'No element'};
                            var options = [];
                            var el = this.currentEl;
                            var tag = el.tagName.toLowerCase();
                            
                            if (el.id) options.push({type: 'ID', value: '#' + el.id});
                            
                            var classes = Array.from(el.classList);
                            classes.forEach(c => options.push({type: 'Class', value: '.' + c}));
                            
                            options.push({type: 'Tag', value: tag});

                            if (classes.length > 0) {
                                options.push({type: 'Tag+Class', value: tag + '.' + classes.join('.')});
                            }

                            var parent = el.parentElement;
                            if (parent) {
                                var idx = Array.from(parent.children).indexOf(el) + 1;
                                options.push({type: 'Position', value: tag + ':nth-child(' + idx + ')'});
                            }

                            var path = [];
                            var curr = el;
                            while (curr && curr.tagName !== 'HTML' && curr.tagName !== 'BODY') {
                                var segment = curr.tagName.toLowerCase();
                                if (curr.id) {
                                    segment += '#' + curr.id;
                                    path.unshift(segment);
                                    break;
                                }
                                var p = curr.parentElement;
                                if (p) {
                                    var i = Array.from(p.children).indexOf(curr) + 1;
                                    segment += ':nth-child(' + i + ')';
                                }
                                path.unshift(segment);
                                curr = curr.parentElement;
                            }
                            if (path.length > 1) {
                                options.push({type: 'Path', value: path.join(' > ')});
                            }

                            if (tag === 'iframe' && el.src) {
                                try {
                                    var url = new URL(el.src);
                                    options.push({type: 'Source', value: 'iframe[src*="' + url.host + '"]'});
                                } catch(e) {}
                            }
                            
                            return {
                                tagName: el.tagName,
                                id: el.id,
                                className: el.className,
                                candidatesCount: this.candidateEls.length,
                                candidateIndex: this.candidateIndex,
                                options: options
                            };
                        }
                    };
                }
                return window.blockerHelper.selectAt($x, $y);
            })();
        """.trimIndent()

        wv.evaluateJavascript(setupScript) { result ->
            if (result == null || result == "null") return@evaluateJavascript
            try {
                val json = JSONObject(result)
                if (json.has("options")) {
                    showAdvancedBlockDialog(json)
                } else {
                    val error = json.optString("error", "No element detected at this position.")
                    showBlockErrorDialog(error)
                }
            } catch (e: Exception) {
                showBlockErrorDialog("Failed to detect element: ${e.message}")
            }
        }
    }

    private fun initDpadNav(showToast: Boolean = false) {
        val wv = currentWebView ?: return
        if (showToast) Toast.makeText(this, "D-pad Navigation: Select Element and Press OK", Toast.LENGTH_LONG).show()
        
        val setupScript = """
            (function() {
                if (!window.navHelper) {
                    window.navHelper = {
                        focusedEl: null,
                        highlight: function(el) {
                            this.clearHighlight();
                            if (!el) return;
                            this.focusedEl = el;
                            const styleId = 'poobi-nav-highlight';
                            let style = document.getElementById(styleId);
                            if (!style) {
                                style = document.createElement('style');
                                style.id = styleId;
                                document.head.appendChild(style);
                            }
                            el.classList.add('poobi-focused');
                            style.innerHTML = '.poobi-focused { outline: 6px solid #FF5722 !important; outline-offset: -4px !important; box-shadow: 0 0 15px rgba(255, 87, 34, 0.7) !important; position: relative !important; z-index: 2147483645 !important; }';
                            el.scrollIntoView({block: 'nearest', behavior: 'smooth'});
                        },
                        clearHighlight: function() {
                            if (this.focusedEl) {
                                this.focusedEl.classList.remove('poobi-focused');
                                this.focusedEl = null;
                            }
                        },
                        getFocusableElements: function() {
                            const selectors = 'a, button, input, select, textarea, [tabindex]:not([tabindex="-1"]), [onclick], [role="button"]';
                            return Array.from(document.querySelectorAll(selectors)).filter(el => {
                                const rect = el.getBoundingClientRect();
                                return rect.width > 0 && rect.height > 0 && window.getComputedStyle(el).visibility !== 'hidden' && window.getComputedStyle(el).display !== 'none';
                            });
                        },
                        move: function(direction) {
                            const elements = this.getFocusableElements();
                            if (elements.length === 0) return;
                            
                            let currentRect;
                            if (this.focusedEl) {
                                currentRect = this.focusedEl.getBoundingClientRect();
                            } else {
                                currentRect = { left: 0, top: 0, right: 0, bottom: 0, width: 0, height: 0 };
                            }
                            
                            let bestCandidate = null;
                            let minDistance = Infinity;
                            
                            const curX = (currentRect.left + currentRect.right) / 2;
                            const curY = (currentRect.top + currentRect.bottom) / 2;

                            elements.forEach(el => {
                                if (el === this.focusedEl) return;
                                const rect = el.getBoundingClientRect();
                                const tgtX = (rect.left + rect.right) / 2;
                                const tgtY = (rect.top + rect.bottom) / 2;
                                
                                let isCandidate = false;
                                if (direction === 'up' && rect.bottom <= currentRect.top + 5) isCandidate = true;
                                else if (direction === 'down' && rect.top >= currentRect.bottom - 5) isCandidate = true;
                                else if (direction === 'left' && rect.right <= currentRect.left + 5) isCandidate = true;
                                else if (direction === 'right' && rect.left >= currentRect.right - 5) isCandidate = true;
                                
                                if (isCandidate) {
                                    const distance = Math.sqrt(Math.pow(curX - tgtX, 2) + Math.pow(curY - tgtY, 2));
                                    if (distance < minDistance) {
                                        minDistance = distance;
                                        bestCandidate = el;
                                    }
                                }
                            });
                            
                            if (bestCandidate) this.highlight(bestCandidate);
                            else if (!this.focusedEl && elements.length > 0) this.highlight(elements[0]);
                        },
                        getSelector: function(el) {
                            if (!el) return null;
                            if (el.id) return '#' + el.id;
                            let path = [];
                            let curr = el;
                            while (curr && curr.nodeType === Node.ELEMENT_NODE && curr.tagName !== 'BODY' && curr.tagName !== 'HTML') {
                                let selector = curr.nodeName.toLowerCase();
                                if (curr.id) {
                                    selector += '#' + curr.id;
                                    path.unshift(selector);
                                    break;
                                } else {
                                    let sib = curr, nth = 1;
                                    while (sib = sib.previousElementSibling) {
                                        if (sib.nodeName.toLowerCase() == selector) nth++;
                                    }
                                    if (nth != 1) selector += ":nth-of-type(" + nth + ")";
                                }
                                path.unshift(selector);
                                curr = curr.parentNode;
                            }
                            return path.join(" > ");
                        },
                        getSelectedSelector: function() {
                            return this.getSelector(this.focusedEl);
                        },
                        getFocusedCoords: function() {
                            if (!this.focusedEl) return null;
                            const rect = this.focusedEl.getBoundingClientRect();
                            return { x: (rect.left + rect.right) / 2, y: (rect.top + rect.bottom) / 2 };
                        },
                        clickFocused: function() {
                            if (this.focusedEl) this.focusedEl.click();
                        }
                    };
                }
                if (!window.navHelper.focusedEl) {
                    const elements = window.navHelper.getFocusableElements();
                    if (elements.length > 0) window.navHelper.highlight(elements[0]);
                }
            })();
        """.trimIndent()
        wv.evaluateJavascript(setupScript, null)
    }

    private fun showContextMenuAtFocused() {
        currentWebView?.evaluateJavascript("if(window.navHelper) window.navHelper.getFocusedCoords();") { result ->
            if (result != null && result != "null") {
                try {
                    val obj = JSONObject(result)
                    val density = resources.displayMetrics.density
                    cursorX = obj.getDouble("x").toFloat() * density
                    cursorY = obj.getDouble("y").toFloat() * density
                    showContextMenu()
                } catch (e: Exception) {
                    showContextMenu() // Fallback
                }
            } else {
                showContextMenu() // Fallback
            }
        }
    }

    private fun startDpadSelectionMode() {
        isSelectionMode = true
        cursor.visibility = View.GONE
        currentWebView?.evaluateJavascript("if(window.blockerHelper) window.blockerHelper.clearHighlight();", null)
        initDpadNav(true)
    }

    private fun handleDpadNav(direction: String) {
        currentWebView?.evaluateJavascript("if(window.navHelper) window.navHelper.move('$direction');", null)
    }

    private fun selectDpadElement() {
        currentWebView?.evaluateJavascript("if(window.navHelper) window.navHelper.getSelectedSelector();") { result ->
            val selector = result?.replace("\"", "")
            if (selector != null && selector != "null" && selector.isNotEmpty()) {
                isSelectionMode = false
                currentWebView?.evaluateJavascript("if(window.navHelper) window.navHelper.clearHighlight();", null)

                val script = "window.blockerHelper.currentEl = document.querySelector('$selector'); window.blockerHelper.getOptions();"
                currentWebView?.evaluateJavascript(script) { blockData ->
                    if (blockData != null && blockData != "null") {
                        try {
                            val jsonStr = if (blockData.startsWith("\"") && blockData.endsWith("\"")) {
                                blockData.substring(1, blockData.length - 1).replace("\\\"", "\"")
                            } else {
                                blockData
                            }
                            showAdvancedBlockDialog(JSONObject(jsonStr))
                        } catch (e: Exception) {
                            Log.e("TVBrowser", "Error parsing block options: ${e.message}")
                        }
                    }
                }
            } else {
                Toast.makeText(this, "No element selected", Toast.LENGTH_SHORT).show()
                isSelectionMode = false
                wakeCursor()
            }
        }
    }

    private fun showBlockErrorDialog(message: String) {
        val dialog = AlertDialog.Builder(this)
            .setTitle("Block Element")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .setOnDismissListener {
                if (currentDialog == it) currentDialog = null
                rootLayout.requestFocus()
            }
            .create()
        currentDialog = dialog
        dialog.show()
    }

    private fun showAdvancedBlockDialog(data: JSONObject) {
        val wv = currentWebView ?: return
        val tagName = data.optString("tagName", "unknown")
        val candCount = data.optInt("candidatesCount", 1)
        val candIndex = data.optInt("candidateIndex", 0)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 20)
        }

        val headerText = TextView(this).apply {
            text = "Block Element ($tagName) [${candIndex + 1}/$candCount]"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 18f
            setPadding(0, 0, 0, 20)
        }
        layout.addView(headerText)

        val optionsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        fun populateOptions(json: JSONObject) {
            val opts = json.getJSONArray("options")
            val tName = json.getString("tagName")
            val cIdx = json.optInt("candidateIndex", 0)
            val cCnt = json.optInt("candidatesCount", 1)
            
            headerText.text = "Block Element ($tName) [${cIdx + 1}/$cCnt]"
            optionsContainer.removeAllViews()
            
            for (i in 0 until opts.length()) {
                val opt = opts.getJSONObject(i)
                val type = opt.getString("type")
                val value = opt.getString("value")
                
                val btn = Button(this).apply {
                    text = "$type: $value"
                    isAllCaps = false
                    background = getDrawable(R.drawable.bg_focusable)
                    setTextColor(android.graphics.Color.WHITE)
                    gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
                    setPadding(30, 0, 30, 0)
                    val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 90)
                    params.setMargins(0, 0, 0, 10)
                    layoutParams = params
                    
                    onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
                        if (hasFocus) {
                            wv.evaluateJavascript("window.blockerHelper.setHighlight('$value')", null)
                        }
                    }
                    
                    setOnClickListener {
                        wv.evaluateJavascript("window.blockerHelper.clearHighlight()", null)
                        currentDialog?.dismiss()
                        showSaveBlockedElementDialog(value)
                    }
                }
                optionsContainer.addView(btn)
            }
        }

        populateOptions(data)
        layout.addView(optionsContainer)

        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            params.setMargins(0, 10, 0, 0)
            layoutParams = params
        }

        val nextBtn = Button(this).apply {
            text = "↓ Select Underneath"
            background = getDrawable(R.drawable.bg_focusable)
            setTextColor(android.graphics.Color.YELLOW)
            setPadding(20, 0, 20, 0)
            val params = LinearLayout.LayoutParams(0, 90, 1f)
            params.marginEnd = 10
            layoutParams = params
            visibility = if (candCount > 1) View.VISIBLE else View.GONE
            
            setOnClickListener {
                wv.evaluateJavascript("window.blockerHelper.nextCandidate()") { result ->
                    if (result == null || result == "null") return@evaluateJavascript
                    try {
                        val json = JSONObject(result)
                        if (json.has("options")) {
                            populateOptions(json)
                            if (optionsContainer.childCount > 0) optionsContainer.getChildAt(0).requestFocus()
                        }
                    } catch (e: Exception) { e.printStackTrace() }
                }
            }
        }

        val parentBtn = Button(this).apply {
            text = "↑ Select Parent"
            background = getDrawable(R.drawable.bg_focusable)
            setTextColor(android.graphics.Color.CYAN)
            setPadding(20, 0, 20, 0)
            val params = LinearLayout.LayoutParams(0, 90, 1f)
            layoutParams = params
            
            setOnClickListener {
                wv.evaluateJavascript("window.blockerHelper.selectParent()") { result ->
                    if (result == null || result == "null") return@evaluateJavascript
                    try {
                        val json = JSONObject(result)
                        if (json.has("options")) {
                            populateOptions(json)
                            if (optionsContainer.childCount > 0) optionsContainer.getChildAt(0).requestFocus()
                        }
                    } catch (e: Exception) { e.printStackTrace() }
                }
            }
        }

        actionRow.addView(nextBtn)
        actionRow.addView(parentBtn)
        layout.addView(actionRow)

        val dpadSelectBtn = Button(this).apply {
            text = "Navigate & Select Element"
            background = getDrawable(R.drawable.bg_focusable)
            setTextColor(android.graphics.Color.WHITE)
            setPadding(20, 0, 20, 0)
            val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 90)
            params.topMargin = 10
            layoutParams = params
        }
        layout.addView(dpadSelectBtn)

        val dialog = AlertDialog.Builder(this)
            .setView(layout)
            .create()

        dpadSelectBtn.setOnClickListener {
            dialog.dismiss()
            startDpadSelectionMode()
        }

        dialog.setOnDismissListener {
            wv.evaluateJavascript("window.blockerHelper.clearHighlight()", null)
            if (currentDialog == dialog) currentDialog = null
            rootLayout.requestFocus()
        }
        
        currentDialog = dialog
        dialog.show()
    }
    
    private var currentDialog: AlertDialog? = null

    private fun showSaveBlockedElementDialog(selector: String) {
        val url = currentWebView?.url ?: ""
        val host = Uri.parse(url).host ?: "Unknown Site"
        val editText = EditText(this).apply { 
            setText(host)
            ViewUtils.applySmartDpadFocus(this)
        }
        
        val dialog = AlertDialog.Builder(this)
            .setTitle("Save Blocked Element")
            .setMessage("Rule name for this site:")
            .setView(editText)
            .setPositiveButton("Save") { _, _ ->
                val name = editText.text.toString()
                saveBlockedElement(name, url, selector)
            }
            .setNegativeButton("Just for now", null)
            .setOnDismissListener {
                if (currentDialog == it) currentDialog = null
                rootLayout.requestFocus()
            }
            .create()
        currentDialog = dialog
        dialog.show()
    }

    private fun saveBlockedElement(name: String, url: String, selector: String) {
        val host = Uri.parse(url).host ?: "*"
        val blockedJson = prefs.getString("blocked_elements", "[]") ?: "[]"
        val array = JSONArray(blockedJson)
        
        var existingIndex = -1
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            if (obj.getString("name") == name) {
                existingIndex = i; break
            }
        }

        if (existingIndex != -1) {
            val obj = array.getJSONObject(existingIndex)
            val selectors = obj.getJSONArray("selectors")
            var found = false
            for (j in 0 until selectors.length()) {
                if (selectors.getString(j) == selector) { found = true; break }
            }
            if (!found) selectors.put(selector)
        } else {
            val newRule = JSONObject().apply {
                put("id", java.util.UUID.randomUUID().toString())
                put("name", name)
                put("enabled", true)
                put("urlPatterns", JSONArray().put("*$host*"))
                put("selectors", JSONArray().put(selector))
            }
            array.put(newRule)
        }
        
        prefs.edit().putString("blocked_elements", array.toString()).apply()
        Toast.makeText(this, "Element rule saved!", Toast.LENGTH_SHORT).show()
    }

    private fun applyBlockedElements(wv: WebView) {
        val url = wv.url ?: return
        val blockedJson = prefs.getString("blocked_elements", "[]") ?: "[]"
        val array = JSONArray(blockedJson)
        
        val allSelectors = mutableListOf<String>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            if (!obj.optBoolean("enabled", true)) continue
            
            val patterns = obj.getJSONArray("urlPatterns")
            var matches = false
            for (j in 0 until patterns.length()) {
                if (matchUrlPattern(patterns.getString(j), url)) {
                    matches = true; break
                }
            }
            
            if (matches) {
                val selectors = obj.getJSONArray("selectors")
                for (j in 0 until selectors.length()) {
                    allSelectors.add(selectors.getString(j))
                }
            }
        }

        if (allSelectors.isNotEmpty()) {
            val selectorsJson = JSONArray(allSelectors).toString()
            val script = """
                (function() {
                    const selectors = $selectorsJson;
                    const styleText = selectors.join(', ') + ' { display: none !important; }';
                    const inject = (win) => {
                        try {
                            let style = win.document.getElementById('poobi-blocker-style');
                            if (!style) {
                                style = win.document.createElement('style');
                                style.id = 'poobi-blocker-style';
                                win.document.head.appendChild(style);
                            }
                            style.innerHTML = styleText;
                            Array.from(win.frames).forEach(f => { try { inject(f); } catch(e) {} });
                        } catch(e) {}
                    };
                    inject(window);
                })();
            """.trimIndent()
            wv.evaluateJavascript(script, null)
        }
    }

    private fun simulateClick() {
        val downTime = SystemClock.uptimeMillis()
        val eventTime = SystemClock.uptimeMillis()

        // Ensure the WebView has focus so the IME can be triggered
        currentWebView?.requestFocus()

        @Suppress("SpellCheckingInspection")
        val properties = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }
        val coordinates = MotionEvent.PointerCoords().apply { x = cursorX; y = cursorY; pressure = 1.0f; size = 1.0f }

        val downEvent = MotionEvent.obtain(downTime, eventTime, MotionEvent.ACTION_DOWN, 1, arrayOf(properties), arrayOf(coordinates), 0, 0, 1.0f, 1.0f, 0, 0, 0, 0)
        val upEvent = MotionEvent.obtain(downTime, eventTime + 100, MotionEvent.ACTION_UP, 1, arrayOf(properties), arrayOf(coordinates), 0, 0, 1.0f, 1.0f, 0, 0, 0, 0)

        window.superDispatchTouchEvent(downEvent)
        window.superDispatchTouchEvent(upEvent)

        downEvent.recycle()
        upEvent.recycle()
    }

    private fun startRecording() {
        val wv = currentWebView ?: return
        isRecordingAutoplay = true
        recordedSelectors.clear()
        topRecordBtn.setImageResource(R.drawable.ic_stop)
        topRecordBtn.imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.RED)
        Toast.makeText(this, "Recording Autoplay Sequence...", Toast.LENGTH_SHORT).show()

        val script = """
            (function() {
                function getSelector(el) {
                    if (el.id) return '#' + el.id;
                    let names = [];
                    while (el.parentElement) {
                        if (el.id) {
                            names.unshift('#' + el.id);
                            break;
                        } else {
                            if (el == el.ownerDocument.documentElement) names.unshift(el.tagName);
                            else {
                                for (var c = 1, e = el; e.previousElementSibling; e = e.previousElementSibling, c++);
                                names.unshift(el.tagName + ":nth-child(" + c + ")");
                            }
                            el = el.parentElement;
                        }
                    }
                    return names.join(" > ");
                }
                window.recordedClickCallback = function(e) {
                    const selector = getSelector(e.target);
                    AndroidAutoplay.onElementClicked(selector);
                };
                document.addEventListener('click', window.recordedClickCallback, true);
            })();
        """.trimIndent()
        wv.evaluateJavascript(script, null)
    }

    private fun stopRecording() {
        isRecordingAutoplay = false
        topRecordBtn.setImageResource(R.drawable.ic_record)
        topRecordBtn.imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
        
        val wv = currentWebView
        wv?.evaluateJavascript("document.removeEventListener('click', window.recordedClickCallback, true);", null)

        if (recordedSelectors.isNotEmpty()) {
            showSaveProfileDialog()
        } else {
            Toast.makeText(this, "No clicks recorded", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showSaveProfileDialog() {
        val url = currentWebView?.url ?: ""
        val host = Uri.parse(url).host ?: "Unknown Site"
        val editText = EditText(this).apply { 
            setText(host)
            ViewUtils.applySmartDpadFocus(this)
        }
        
        AlertDialog.Builder(this)
            .setTitle("Save Autoplay Profile")
            .setMessage("Enter a name for this profile:")
            .setView(editText)
            .setPositiveButton("Save") { _, _ ->
                val name = editText.text.toString()
                saveAutoplayProfile(name, url, recordedSelectors.toList())
            }
            .setNegativeButton("Discard", null)
            .show()
    }

    private fun saveAutoplayProfile(name: String, url: String, selectors: List<String>) {
        val host = Uri.parse(url).host ?: "*"
        val selectorsJson = JSONArray(selectors)
        val script = SettingsActivity.generateSelectorScript(selectorsJson)
        
        val profile = AutoplayProfile(
            name = name, 
            urlPatterns = listOf("*$host*"), 
            script = script,
            useScript = false,
            selectors = selectors
        )
        
        val profilesJson = prefs.getString("autoplay_profiles", "[]") ?: "[]"
        val array = JSONArray(profilesJson)
        array.put(JSONObject().apply {
            put("id", profile.id)
            put("name", profile.name)
            put("enabled", true)
            put("urlPatterns", JSONArray(profile.urlPatterns))
            put("script", profile.script)
            put("use_script", profile.useScript)
            put("selectors", JSONArray(profile.selectors))
        })
        prefs.edit().putString("autoplay_profiles", array.toString()).apply()
        Toast.makeText(this, "Profile '$name' saved!", Toast.LENGTH_SHORT).show()
    }

    private fun matchUrlPattern(pattern: String, url: String): Boolean {
        if (pattern == "*") return true
        val regex = pattern.replace(".", "\\.")
                           .replace("*", ".*")
                           .replace("?", ".")
        return try {
            java.util.regex.Pattern.compile(regex, java.util.regex.Pattern.CASE_INSENSITIVE).matcher(url).find()
        } catch (e: Exception) {
            false
        }
    }

    inner class WebAppInterface {
        @JavascriptInterface
        fun onElementClicked(selector: String) {
            if (isRecordingAutoplay) {
                recordedSelectors.add(selector)
            }
        }
    }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()

    private fun getPythonConfig(): JSONObject {
        try {
            val py = Python.getInstance()
            val main = py.getModule("main")
            return JSONObject(main.get("GLOBAL_CONFIG").toString())
        } catch (e: Exception) {
            return JSONObject()
        }
    }

    private val checkUpNextRunnable = object : Runnable {
        override fun run() {
            val player = exoPlayer ?: return
            if (player.playbackState == Player.STATE_READY && player.playWhenReady) {
                val pos = player.currentPosition
                val dur = player.duration
                if (dur > 0 && lastScrapedSeason != null && upNextPopup == null && !isUpNextDismissed) {
                    val cfg = getPythonConfig()
                    val threshold = cfg.optInt("up_next_time_pref", 20) * 1000L
                    if (dur - pos <= threshold) {
                        showUpNextPopup()
                    }
                }
            }
            movementHandler.postDelayed(this, 1000)
        }
    }

    private fun showUpNextPopup() {
        val cfg = getPythonConfig()
        val popupPref = cfg.optString("up_next_popup_pref", "Ask")
        if (popupPref == "Never") return

        val item = lastScrapedItem ?: return
        val season = lastScrapedSeason ?: return
        val episode = lastScrapedEpisode ?: return
        
        val nextEp = episode + 1
        val cacheKey = "${item.optInt("id")}_$season"
        val episodes = cachedEpisodes[cacheKey] ?: return
        var nextEpData: JSONObject? = null
        for (i in 0 until episodes.length()) {
            if (episodes.getJSONObject(i).optInt("episode_number") == nextEp) {
                nextEpData = episodes.getJSONObject(i)
                break
            }
        }
        
        if (nextEpData == null) return

        val inflater = LayoutInflater.from(this)
        val popup = inflater.inflate(R.layout.dialog_up_next, customViewContainer, false)
        popup.id = View.generateViewId()
        val title = popup.findViewById<TextView>(R.id.up_next_title)
        val thumb = popup.findViewById<ImageView>(R.id.up_next_thumb)
        
        title.text = "E$nextEp: ${nextEpData.optString("name")}"
        val stillPath = nextEpData.optString("still_path")
        if (stillPath.isNotEmpty() && stillPath != "null") {
            loadStreamThumb("https://image.tmdb.org/t/p/w300$stillPath", thumb)
        }
        
        val params = FrameLayout.LayoutParams(300.dp(), FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = android.view.Gravity.BOTTOM or android.view.Gravity.END
            setMargins(0, 0, 40.dp(), 100.dp())
        }
        popup.layoutParams = params
        popup.elevation = 50.dp().toFloat()
        popup.translationZ = 50.dp().toFloat()
        
        popup.setOnClickListener {
            customViewContainer.removeView(popup)
            upNextPopup = null
            isUpNextDismissed = true
            handleNextEpisodeAutoPlay()
        }
        
        customViewContainer.addView(popup)
        upNextPopup = popup
        popup.bringToFront()
        
        if (navigationModePref == 1) {
            popup.isFocusable = true
            popup.isFocusableInTouchMode = true
            
            popup.post {
                popup.requestFocus()
            }
        }
        
        popup.postDelayed({
            if (upNextPopup == popup) {
                customViewContainer.removeView(popup)
                upNextPopup = null
            }
        }, 15000)
    }

    private fun handleNextEpisodeAutoPlay() {
        val item = lastScrapedItem
        val season = lastScrapedSeason
        val episode = lastScrapedEpisode
        
        if (item == null || season == null || episode == null) {
            Toast.makeText(this, "Missing episode info for auto-play", Toast.LENGTH_SHORT).show()
            hideFullscreenVideo()
            return
        }
        
        val nextEp = episode + 1
        Toast.makeText(this, "Playing next: E$nextEp", Toast.LENGTH_SHORT).show()
        
        val cfg = getPythonConfig()
        val autoplayMode = cfg.optString("autoplay_next_pref", "Closest Source")
        
        if (autoplayMode == "Ask") {
            hideFullscreenVideo()
            performScrape(item, season, nextEp)
            return
        }

        performAutoPlayScrape(item, season, nextEp, autoplayMode)
    }

    private fun performAutoPlayScrape(item: JSONObject, season: Int, episode: Int, mode: String) {
        hideFullscreenVideo()
        
        val progressDialog = AlertDialog.Builder(this)
            .setTitle("Auto-playing Next Episode...")
            .setMessage("Finding best source for E$episode...")
            .setNegativeButton("Cancel") { _, _ -> 
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        Python.getInstance().getModule("main").callAttr("stop_scrape")
                    } catch (e: Exception) {}
                }
            }
            .create()
        progressDialog.show()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val py = Python.getInstance()
                val scraper = py.getModule("main")
                scraper.callAttr("stop_scrape")
                
                // Start scrape in a separate job
                launch {
                    try {
                        Log.d("AutoPlay", "Starting scrape for E$episode")
                        scraper.callAttr("scrape", item.toString(), season, episode)
                    } catch (e: Exception) {
                        Log.e("AutoPlay", "Scrape failed: ${e.message}")
                    }
                }
                
                var foundSource: JSONObject? = null
                val startTime = System.currentTimeMillis()
                val timeout = 45000L // 45s timeout for auto-play search
                
                delay(2000) // Give it a moment to start
                
                while (foundSource == null && System.currentTimeMillis() - startTime < timeout) {
                    val statusJson = scraper.callAttr("get_scrape_status").toString()
                    val status = JSONObject(statusJson)
                    val sources = status.optJSONArray("sources")
                    val message = status.optString("message", "")
                    val isFinished = message == "Finished!" || message == "Stopped!" || message == "Timeout reached!"
                    
                    if (sources != null && sources.length() > 0) {
                        val rawSources = mutableListOf<JSONObject>()
                        for (i in 0 until sources.length()) {
                            rawSources.add(JSONObject(sources.getJSONObject(i).getString("source_data")))
                        }
                        
                        if (mode == "Closest Source" && lastSelectedSource != null) {
                            val targetSource = lastSelectedSource!!.optString("source")
                            val targetProvider = lastSelectedSource!!.optString("provider")
                            val targetUrl = lastSelectedSource!!.optString("url")
                            val targetHost = if (targetUrl.contains("//")) targetUrl.split("//")[1].split("/")[0] else ""

                            foundSource = rawSources.find { 
                                val u = it.optString("url")
                                val h = if (u.contains("//")) u.split("//")[1].split("/")[0] else ""
                                h == targetHost && it.optString("provider") == targetProvider && it.optString("source") == targetSource
                            }
                            
                            if (foundSource == null) {
                                foundSource = rawSources.find { 
                                    it.optString("provider") == targetProvider && it.optString("source") == targetSource
                                }
                            }
                            
                            if (foundSource == null) {
                                foundSource = rawSources.find { 
                                    it.optString("source") == targetSource
                                }
                            }
                        }
                        
                        if (foundSource == null && (isFinished || mode == "Best Source")) {
                            val priorities = getSortPriorities()
                            val sorted = SourceSorter(priorities).sort(sources)
                            if (sorted.length() > 0) {
                                foundSource = JSONObject(sorted.getJSONObject(0).getString("source_data"))
                            }
                        }
                    }
                    
                    if (isFinished && foundSource == null) break
                    if (foundSource == null) delay(1000)
                }
                
                if (foundSource == null) {
                    // Try one last time with whatever we have
                    val statusJson = scraper.callAttr("get_scrape_status").toString()
                    val status = JSONObject(statusJson)
                    val sources = status.optJSONArray("sources")
                    if (sources != null && sources.length() > 0) {
                        val priorities = getSortPriorities()
                        val sorted = SourceSorter(priorities).sort(sources)
                        foundSource = JSONObject(sorted.getJSONObject(0).getString("source_data"))
                    }
                }

                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    if (foundSource != null) {
                        lastSelectedSource = foundSource
                        lastScrapedItem = item
                        lastScrapedSeason = season
                        lastScrapedEpisode = episode
                        resolveAndPlay(foundSource.toString())
                    } else {
                        Toast.makeText(this@MainActivity, "Could not find a suitable source automatically", Toast.LENGTH_SHORT).show()
                        performScrape(item, season, episode)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    Toast.makeText(this@MainActivity, "Error during auto-play: ${e.message}", Toast.LENGTH_SHORT).show()
                    performScrape(item, season, episode)
                }
            }
        }
    }
}
