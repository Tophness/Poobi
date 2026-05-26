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
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.brave.adblock.AdBlockUtils
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream

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

    private lateinit var downloadsContainer: LinearLayout
    private lateinit var topDownloadsBtn: ImageButton

    private var embeddedSubsEnabled = true
    private var scrollTopbarEnabled = true
    private val interceptedSubtitleUrls = mutableSetOf<String>()

    private var lastClickedUrl: String? = null
    private var okDownTime = 0L
    private val LONG_PRESS_THRESHOLD = 600L
    private var isLongPressing = false

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

    private val interceptedMediaUrls = mutableSetOf<String>()

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
        webContainer = findViewById(R.id.web_container)
        tabsContainer = findViewById(R.id.tabs_container)
        contextMenu = findViewById(R.id.context_menu)

        cursor = findViewById(R.id.cursor)
        customViewContainer = findViewById(R.id.fullscreen_custom_content)
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
        showHomeScreen()
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
            cursor.visibility = View.VISIBLE
            cursor.removeCallbacks(hideCursorRunnable)
            cursor.postDelayed(hideCursorRunnable, 3500)

            movementHandler.removeCallbacks(hoverCheckRunnable)
            movementHandler.postDelayed(hoverCheckRunnable, 500)
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
                else -> null
            }
            container?.let {
                val nextToFocus = if (focusUIIndex > 0) focusUIIndex - 1 else 0
                if (it.childCount > 0) {
                    it.getChildAt(nextToFocus.coerceAtMost(it.childCount - 1)).requestFocus()
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
        val thumbView = view.findViewById<ImageView>(R.id.card_thumb)

        val url = obj.getString("url")
        val title = obj.getString("title")
        titleView.text = title

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
                    if (!isBrowsing) refreshLists()
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
                    refreshLists()
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

            homeTabBookmarks.alpha = 0.5f
            homeTabTabs.alpha = 0.5f
            homeTabHistory.alpha = 0.5f
            homeTabDownloads.alpha = 0.5f

            activeHomeTab?.isSelected = false
            activeHomeTab = tab
            tab.isSelected = true
            tab.alpha = 1.0f
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
        }
        findViewById<Button>(R.id.ctx_refresh).setOnClickListener {
            currentWebView?.reload()
            contextMenu.visibility = View.GONE
        }
        findViewById<Button>(R.id.ctx_block).setOnClickListener {
            blockElementAtCursor()
            contextMenu.visibility = View.GONE
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
        homeScreenLayout.visibility = View.VISIBLE
        webContainer.visibility = View.GONE
        topBarLayout.visibility = View.GONE
        contextMenu.visibility = View.GONE

        for (wv in webViews) wv.visibility = View.GONE

        refreshLists()
        findViewById<ImageButton>(R.id.home_settings_btn).requestFocus()
    }

    private fun loadUrlAndBrowse(inputUrl: String, newTab: Boolean = false) {
        var url = inputUrl.trim()
        if (url.isNotEmpty()) {
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = if (url.contains(".") && !url.contains(" ")) "https://$url" else "https://www.google.com/search?q=$url"
            }
            isBrowsing = true
            homeScreenLayout.visibility = View.GONE
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
            candidates.addAll(interceptedMediaUrls)

            val finalCandidates = candidates.distinct()

            if (finalCandidates.isNotEmpty()) {
                if (extractVideoPref == 1 && finalCandidates.size == 1) {
                    launchNativeVideoPlayer(finalCandidates[0], callback)
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
                if (url.contains(".m3u8")) {
                    try {
                        val content = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            java.net.URL(url).readText()
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
                launchNativeVideoPlayer(streamUrls[position], callback)
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
    private fun launchNativeVideoPlayer(videoUrl: String, callback: WebChromeClient.CustomViewCallback?) {
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
        cursor.visibility = View.GONE

        exoPlayer?.release()

        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(currentWebView?.settings?.userAgentString ?: "")
            .setAllowCrossProtocolRedirects(true)

        exoPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(dataSourceFactory))
            .build()

        nativeVideoView.player = exoPlayer
        nativeVideoView.setShowNextButton(false)
        nativeVideoView.setShowPreviousButton(false)
        nativeVideoView.setShowSubtitleButton(true)

        exoPlayer?.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                val cause = error.cause?.message ?: "Unknown"
                Toast.makeText(this@MainActivity, "ExoPlayer Error: ${error.errorCodeName}\n${cause}", Toast.LENGTH_LONG).show()
                Log.e("ExoPlayerDebug", "Playback failed: ", error)
            }
        })

        val mediaItemBuilder = MediaItem.Builder().setUri(videoUrl)

        val subtitleConfigs = interceptedSubtitleUrls.map { subUrl ->
            val mimeType = if (subUrl.contains(".vtt")) MimeTypes.TEXT_VTT
            else if (subUrl.contains(".ass")) MimeTypes.TEXT_SSA
            else MimeTypes.APPLICATION_SUBRIP

            val info = getLanguageInfo(subUrl)
            MediaItem.SubtitleConfiguration.Builder(Uri.parse(subUrl))
                .setMimeType(mimeType)
                .setLanguage(info.first)
                .setLabel(info.second)
                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                .setRoleFlags(C.ROLE_FLAG_SUBTITLE)
                .build()
        }
        mediaItemBuilder.setSubtitleConfigurations(subtitleConfigs)

        exoPlayer?.setMediaItem(mediaItemBuilder.build())

        val pageUrl = currentWebView?.url ?: ""
        if (pageUrl.isNotEmpty()) {
            val savedPos = prefs.getLong("resume_$pageUrl", 0L)
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
    }

    private fun showWebsiteFullscreen(view: View?, callback: WebChromeClient.CustomViewCallback?) {
        mCustomView = view
        customViewContainer.addView(mCustomView)
        customViewContainer.visibility = View.VISIBLE
        webContainer.visibility = View.GONE
        customViewCallback = callback
        wakeCursor()
    }

    private fun hideFullscreenVideo() {
        if (nativeVideoView.visibility == View.VISIBLE) {
            val pageUrl = currentWebView?.url ?: ""
            if (pageUrl.isNotEmpty() && exoPlayer != null) {
                val pos = exoPlayer!!.currentPosition
                val dur = exoPlayer!!.duration
                if (dur > 0 && pos < dur - 10000) { // Don't save if near the end
                    prefs.edit().putLong("resume_$pageUrl", pos).apply()
                } else {
                    prefs.edit().remove("resume_$pageUrl").apply()
                }
            }

            nativeVideoView.visibility = View.GONE
            nativeVideoView.keepScreenOn = false
            exoPlayer?.stop()
            exoPlayer?.release()
            exoPlayer = null
            nativeVideoView.player = null
        }

        customViewContainer.visibility = View.GONE
        isExtractionActive = false

        currentWebView?.apply {
            onResume()
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
        wakeCursor()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView(wv: WebView) {
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
                        interceptedMediaUrls.add(requestedUrl)
                        if (videoTriggerPref == 0 && isBrowsing && wasEmpty) {
                            view?.post { attemptVideoExtraction(null, null) }
                        }
                    }
                    if (requestedUrl.contains(".srt") || requestedUrl.contains(".vtt") || requestedUrl.contains(".ass")) {
                        interceptedSubtitleUrls.add(requestedUrl)
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
        player.seekTo(newPos.coerceIn(0, player.duration))
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
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
            if (event.action == KeyEvent.ACTION_DOWN) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> { seekVideo(-1, event.repeatCount); return true }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { seekVideo(1, event.repeatCount); return true }
                    KeyEvent.KEYCODE_BACK -> { hideFullscreenVideo(); return true }
                }
            }
            return super.dispatchKeyEvent(event)
        }

        if (!isBrowsing || topBarLayout.visibility == View.VISIBLE) {
            if (topBarLayout.visibility == View.VISIBLE && event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN && event.action == KeyEvent.ACTION_DOWN) {
                topBarLayout.visibility = View.GONE
                wakeCursor()
                return true
            }
            if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (customViewContainer.visibility == View.VISIBLE) {
                    hideFullscreenVideo()
                    return true
                } else if (topBarLayout.visibility == View.VISIBLE) {
                    topBarLayout.visibility = View.GONE
                    wakeCursor()
                    return true
                }
            }
            return super.dispatchKeyEvent(event)
        }

        if (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (event.repeatCount == 0) {
                    okDownTime = System.currentTimeMillis()
                    isLongPressing = false
                } else if (!isLongPressing && System.currentTimeMillis() - okDownTime > LONG_PRESS_THRESHOLD) {
                    isLongPressing = true
                    showContextMenu()
                    return true
                }
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
        if (!isBrowsing || topBarLayout.visibility == View.VISIBLE || isNativeVideoPlaying()) {
            if (event.action == KeyEvent.ACTION_UP) {
                keyStates[event.keyCode] = false
            }
            return false
        }

        val keyCode = event.keyCode
        val isDown = event.action == KeyEvent.ACTION_DOWN

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
            if (scrollTopbarEnabled && cursorY < 1 && (wv?.scrollY ?: 0) == 0 && dy < 0) {
                topBarLayout.visibility = View.VISIBLE
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
            switchTab(currentTabIndex)
        }
        if (!isBrowsing) refreshLists()
    }

    private fun showContextMenu() {
        val wv = currentWebView ?: return
        val density = resources.displayMetrics.density
        val x = (cursorX / density).toInt()
        val y = (cursorY / density).toInt()

        wv.evaluateJavascript("""
            (function() {
                var el = document.elementFromPoint($x, $y);
                var link = '';
                var temp = el;
                while(temp && temp.tagName !== 'A') temp = temp.parentElement;
                if(temp) link = temp.href;
                return JSON.stringify({link: link});
            })()
        """.trimIndent()) { result ->
            val res = result?.trim('"')?.replace("\\\"", "\"") ?: "{}"
            try {
                val json = JSONObject(res)
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
            wakeCursor()
        } else {
            topBarLayout.visibility = View.VISIBLE
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
                            var candidates = [];
                            function collect(win, rx, ry) {
                                try {
                                    var els = win.document.elementsFromPoint(rx, ry);
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
                            this.candidateIndex = 0;
                            this.currentEl = this.candidateEls[0];
                            return this.getOptions();
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
                            if (!this.currentEl) return JSON.stringify({error: 'No element'});
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
                            
                            return JSON.stringify({
                                tagName: el.tagName,
                                id: el.id,
                                className: el.className,
                                candidatesCount: this.candidateEls.length,
                                candidateIndex: this.candidateIndex,
                                options: options
                            });
                        }
                    };
                }
                return window.blockerHelper.selectAt($x, $y);
            })();
        """.trimIndent()

        wv.evaluateJavascript(setupScript) { result ->
            val res = result?.trim('"')?.replace("\\\"", "\"") ?: "{}"
            try {
                val json = JSONObject(res)
                if (json.has("options")) {
                    showAdvancedBlockDialog(json)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
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
                    val res = result?.trim('"')?.replace("\\\"", "\"") ?: "{}"
                    try {
                        val json = JSONObject(res)
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
                    val res = result?.trim('"')?.replace("\\\"", "\"") ?: "{}"
                    try {
                        val json = JSONObject(res)
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

        currentDialog = AlertDialog.Builder(this)
            .setView(layout)
            .setOnDismissListener {
                wv.evaluateJavascript("window.blockerHelper.clearHighlight()", null)
            }
            .create()

        currentDialog?.show()
    }
    
    private var currentDialog: AlertDialog? = null

    private fun showSaveBlockedElementDialog(selector: String) {
        val url = currentWebView?.url ?: ""
        val host = Uri.parse(url).host ?: "Unknown Site"
        val editText = EditText(this).apply { 
            setText(host)
            ViewUtils.applySmartDpadFocus(this)
        }
        
        AlertDialog.Builder(this)
            .setTitle("Save Blocked Element")
            .setMessage("Rule name for this site:")
            .setView(editText)
            .setPositiveButton("Save") { _, _ ->
                val name = editText.text.toString()
                saveBlockedElement(name, url, selector)
            }
            .setNegativeButton("Just for now", null)
            .show()
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
}
