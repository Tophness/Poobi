package com.poobi.tvbrowser

import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.WindowInsets
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import android.util.Log
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.poobi.tvbrowser.browser.AdBlockManager
import com.poobi.tvbrowser.browser.BrowserViewModel
import com.poobi.tvbrowser.browser.CursorManager
import com.poobi.tvbrowser.player.PlayerEngine
import com.poobi.tvbrowser.shared.PythonDialogListener
import com.poobi.tvbrowser.shared.cleanKodiText
import com.poobi.tvbrowser.shared.isFutureDate
import com.poobi.tvbrowser.streams.StreamsEvent
import com.poobi.tvbrowser.streams.StreamsViewModel
import com.poobi.tvbrowser.torrent.TorrentStreamServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalAutofill
import java.util.UUID
import java.util.concurrent.Exchanger

object PythonMessageBridge {
    private val pendingResponses = java.util.concurrent.ConcurrentHashMap<String, Exchanger<Any>>()

    fun postRequest(requestId: String): Exchanger<Any> {
        val exchanger = Exchanger<Any>()
        pendingResponses[requestId] = exchanger
        return exchanger
    }

    fun sendResponse(requestId: String, response: Any) {
        val exchanger = pendingResponses.remove(requestId)
        try {
            exchanger?.exchange(response)
        } catch (e: Exception) {
            Log.e("PythonMessageBridge", "Failed to send response for $requestId", e)
        }
    }
}

class MainActivity : AppCompatActivity() {

    private val browserViewModel: BrowserViewModel by viewModels()
    private val streamsViewModel: StreamsViewModel by viewModels()
    
    private lateinit var cursorManager: CursorManager
    private lateinit var playerEngine: PlayerEngine
    private var torrentServer: TorrentStreamServer? = null

    private val longPressHandler = Handler(Looper.getMainLooper())
    private val longPressRunnable = Runnable {
        cursorManager.isLongPressing = true
        browserViewModel.triggerContextMenuAtCursor(cursorManager.cursorX.value, cursorManager.cursorY.value)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AdBlockManager.init(this)
        startTorrentServer()

        cursorManager = CursorManager(this, browserViewModel)
        
        playerEngine = PlayerEngine(
            context = this,
            prefs = getSharedPreferences("BrowserSettings", MODE_PRIVATE),
            onPlaybackError = { error, rawUrl ->
                playerEngine.stopAndRelease()

                if (streamsViewModel.isTryingAll.value) {
                    streamsViewModel.onPlaybackError()
                } else {
                    val fromStreams = rawUrl.endsWith("|from_streams")
                    val url = if (fromStreams) rawUrl.removeSuffix("|from_streams") else rawUrl

                    val prefs = getSharedPreferences("BrowserSettings", MODE_PRIVATE)
                    val fallbackPref = prefs.getInt("exo_fallback_pref", 0)

                    if (streamsViewModel.isPlayingFromSavedLink) {
                        streamsViewModel.isPlayingFromSavedLink = false
                        val item = streamsViewModel.selectedItem.value
                        val season = streamsViewModel.lastScrapedSeason
                        val episode = streamsViewModel.lastScrapedEpisode
                        if (item != null) {
                            streamsViewModel.performScrape(item, season, episode)
                        }
                    } else if (fromStreams) {
                        when (fallbackPref) {
                            1 -> { // Always
                                val item = streamsViewModel.selectedItem.value
                                browserViewModel.loadUrlAndBrowse(
                                    context = this, 
                                    inputUrl = url, 
                                    newTab = true,
                                    streamItemJson = item?.toString(),
                                    season = streamsViewModel.lastScrapedSeason,
                                    episode = streamsViewModel.lastScrapedEpisode
                                )
                                browserViewModel.currentAppTab.value = 0
                            }
                            2 -> { // Never
                                Toast.makeText(this, "ExoPlayer Error: ${error.errorCodeName}", Toast.LENGTH_LONG).show()
                                streamsViewModel.resumeScrape()
                            }
                            else -> { // Ask (0)
                                showExoFallbackDialog(url, error.errorCodeName)
                            }
                        }
                    } else {
                        // Coming from browser originally, just return to browser or show toast
                        Toast.makeText(this, "ExoPlayer Error: ${error.errorCodeName}", Toast.LENGTH_SHORT).show()
                        browserViewModel.resumeTimersOnCurrent()
                    }
                }
            },
            onPlaybackStarted = { url, title, item, season, episode, headers, subtitles ->
                streamsViewModel.stopTryAll(resume = false)
                streamsViewModel.onVideoPlaybackStarted(url, title, item, season, episode, headers, subtitles)
            },
            onUpNextTriggered = { },
            onVideoEnded = {
                streamsViewModel.selectedItem.value?.let { item ->
                    val season = streamsViewModel.lastScrapedSeason
                    val episode = streamsViewModel.lastScrapedEpisode
                    if (season != null && episode != null) {
                        streamsViewModel.handleNextEpisodeAutoPlay(item, season, episode)
                    }
                }
            },
            onPlayerReleased = {
                browserViewModel.hideCustomViewInternal()
                streamsViewModel.resumeScrape()
                try {
                    val server = TorrentStreamServer.getInstance(applicationContext)
                    server.stopActiveStreams()
                    val prefs = getSharedPreferences("BrowserSettings", MODE_PRIVATE)
                    val cleanMode = prefs.getInt("torrent_cache_clean_mode", 0)
                    if (cleanMode == 2) {
                        server.clearAllCache()
                    }
                } catch (e: Exception) {}
            },
            onVideoWatched = { season, episode ->
                streamsViewModel.markEpisodeAsWatchedLocal(season, episode)
            }
        )

        browserViewModel.onPlayNativeVideo = { videoUrl, title, altUrls, altNames ->
            val metadata = browserViewModel.currentWebView?.tag as? com.poobi.tvbrowser.browser.TabMetadata
            
            val isBrowserTab = browserViewModel.currentAppTab.value == 0
            val item = if (isBrowserTab) {
                metadata?.streamItemJson?.let { org.json.JSONObject(it) }
            } else {
                streamsViewModel.selectedItem.value
            }
            val season = if (isBrowserTab) metadata?.season else streamsViewModel.lastScrapedSeason
            val episode = if (isBrowserTab) metadata?.episode else streamsViewModel.lastScrapedEpisode

            val isFromStreams = item != null
            val cleanTitle = item?.optString("title") ?: item?.optString("name")
            val fullTitle = if (isFromStreams && cleanTitle != null && season != null && episode != null) {
                "$cleanTitle S${season}E$episode"
            } else {
                title ?: cleanTitle
            }

            val combinedSubtitles = mutableMapOf<String, Map<String, String>>()
            combinedSubtitles.putAll(browserViewModel.interceptedSubtitleUrls)

            val isMatchingStreamsItem = isFromStreams && 
                (item.optString("id") == streamsViewModel.selectedItem.value?.optString("id") ||
                 (item.optString("imdb").isNotEmpty() && item.optString("imdb") == streamsViewModel.selectedItem.value?.optString("imdb")))
            
            if (isMatchingStreamsItem) {
                combinedSubtitles.putAll(streamsViewModel.interceptedSubtitleUrls)
            }

            playerEngine.launchVideo(
                videoUrl = videoUrl,
                title = fullTitle,
                headers = browserViewModel.interceptedMediaUrls[videoUrl] ?: emptyMap(),
                subtitles = combinedSubtitles,
                item = if (isFromStreams) item else null,
                season = if (isFromStreams) season else null,
                episode = if (isFromStreams) episode else null,
                fromStreams = false,
                alternativeUrls = altUrls ?: emptyList(),
                alternativeNames = altNames ?: emptyList()
            )
        }

        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                streamsViewModel.itemEpisodes.collect { episodes ->
                    if (episodes != null) {
                        val season = streamsViewModel.lastScrapedSeason
                        val episode = streamsViewModel.lastScrapedEpisode
                        if (season != null && episode != null) {
                            for (i in 0 until episodes.length()) {
                                val ep = episodes.getJSONObject(i)
                                if (ep.optInt("episode_number") == episode + 1) {
                                    val airDate = ep.optString("air_date", "")
                                    if (airDate.isNotEmpty() && !isFutureDate(airDate)) {
                                        playerEngine.setNextEpisode(ep)
                                    } else {
                                        playerEngine.setNextEpisode(null)
                                    }
                                    break
                                }
                            }
                        }
                    }
                }
            }
        }

        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                streamsViewModel.events.collect { event ->
                    when (event) {
                        is StreamsEvent.PlayVideo -> {
                            if (!event.isWebpage) {
                                playerEngine.setNextEpisode(event.nextEpisode)
                                playerEngine.launchVideo(
                                    videoUrl = event.url,
                                    title = event.title,
                                    headers = event.headers,
                                    subtitles = event.subtitles,
                                    item = event.item,
                                    season = event.season,
                                    episode = event.episode,
                                    fromStreams = true
                                )
                            } else {
                                playerEngine.stopAndRelease()
                                browserViewModel.loadUrlAndBrowse(
                                    context = this@MainActivity, 
                                    inputUrl = event.url, 
                                    newTab = true,
                                    streamItemJson = event.item.toString(),
                                    season = event.season,
                                    episode = event.episode
                                ) 
                                browserViewModel.currentAppTab.value = 0 
                            }
                            streamsViewModel.consumeEvent()
                        }
                        is StreamsEvent.ShowToast -> {
                            Toast.makeText(this@MainActivity, event.message, Toast.LENGTH_SHORT).show()
                            streamsViewModel.consumeEvent()
                        }
                        is StreamsEvent.ShowSubtitlePicker -> {
                            displaySubtitlePickerDialog(event.subs)
                            streamsViewModel.consumeEvent()
                        }
                        is StreamsEvent.AskSubtitleWait -> {
                            displaySubtitleWaitDialog(event.sourceDataJson)
                            streamsViewModel.consumeEvent()
                        }
                        is StreamsEvent.AddSubtitlesBatch -> {
                            if (playerEngine.isPlayerActive.value) {
                                playerEngine.addSubtitlesBatch(event.subtitles)
                            }
                            streamsViewModel.consumeEvent()
                        }
                        null -> {}
                    }
                }
            }
        }

        setContent {
            CompositionLocalProvider(
                LocalAutofill provides null
            ) {
                MainApp(
                    browserViewModel = browserViewModel,
                    streamsViewModel = streamsViewModel,
                    cursorManager = cursorManager,
                    playerEngine = playerEngine
                )
            }
        }
        checkStartupTabs()
        initPythonAsync()
    }

    override fun onResume() {
        super.onResume()
		browserViewModel.reloadPreferences()
        browserViewModel.refreshLists()
        streamsViewModel.refreshFavoritesSet()
        registerPythonDialogListenerAsync()
    }

    private fun initPythonAsync() {
        lifecycleScope.launch(Dispatchers.IO) {
            // Stagger Python engine start by 1.5 seconds to ensure Compose initializes and draws without I/O blocking
            delay(1500)
            if (!Python.isStarted()) {
                Python.start(AndroidPlatform(this@MainActivity))
            }
            try {
                val py = Python.getInstance()
                py.getModule("modules.control")
                py.getModule("tmdb.tmdb_utils")
                py.getModule("main")
            } catch (e: Exception) {
                Log.e("TVBrowser", "Error warming up Python modules", e)
            }
            registerPythonDialogListenerAsync()
        }
    }

    private fun showExoFallbackDialog(url: String, errorName: String) {
        val prefs = getSharedPreferences("BrowserSettings", MODE_PRIVATE)
        val checkBox = android.widget.CheckBox(this).apply {
            text = "Never ask again (Remember choice)"
            setPadding(50, 20, 50, 20)
        }

        AlertDialog.Builder(this)
            .setTitle("Playback Error")
            .setMessage("ExoPlayer failed to play this stream. Would you like to try opening it in the browser instead?\n\nError: $errorName")
            .setView(checkBox)
            .setPositiveButton("Open in Browser") { _, _ ->
                if (checkBox.isChecked) {
                    prefs.edit().putInt("exo_fallback_pref", 1).apply()
                }
                val item = streamsViewModel.selectedItem.value
                browserViewModel.loadUrlAndBrowse(
                    context = this, 
                    inputUrl = url, 
                    newTab = true,
                    streamItemJson = item?.toString(),
                    season = streamsViewModel.lastScrapedSeason,
                    episode = streamsViewModel.lastScrapedEpisode
                )
                browserViewModel.currentAppTab.value = 0
            }
            .setNegativeButton("Cancel") { _, _ ->
                if (checkBox.isChecked) {
                    prefs.edit().putInt("exo_fallback_pref", 2).apply()
                }
                streamsViewModel.resumeScrape()
            }
            .setOnCancelListener {
                streamsViewModel.resumeScrape()
            }
            .show()
    }

    private fun registerPythonDialogListenerAsync() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                if (!Python.isStarted()) return@launch
                val py = Python.getInstance()
                val control = py.getModule("modules.control")
                control.callAttr("set_dialog_listener", object : PythonDialogListener {
                    override fun infoDialog(message: String, heading: String, sound: Boolean, icon: String) {
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, "${heading.cleanKodiText()}: ${message.cleanKodiText()}", Toast.LENGTH_LONG).show()
                        }
                    }

                    override fun okDialog(message: String, heading: String): Boolean {
                        val taskId = UUID.randomUUID().toString()
                        val exchanger = PythonMessageBridge.postRequest(taskId)
                        runOnUiThread {
                            AlertDialog.Builder(this@MainActivity)
                                .setTitle(heading.cleanKodiText().ifEmpty { "Notice" })
                                .setMessage(message.cleanKodiText())
                                .setPositiveButton("OK") { _, _ -> 
                                    PythonMessageBridge.sendResponse(taskId, true) 
                                }
                                .setOnCancelListener { 
                                    PythonMessageBridge.sendResponse(taskId, true) 
                                }
                                .show()
                        }
                        return try {
                            exchanger.exchange(true) as Boolean
                        } catch (e: Exception) {
                            true
                        }
                    }

                    override fun yesnoDialog(message: String, heading: String, nolabel: String, yeslabel: String): Boolean {
                        val taskId = UUID.randomUUID().toString()
                        val exchanger = PythonMessageBridge.postRequest(taskId)
                        runOnUiThread {
                            AlertDialog.Builder(this@MainActivity)
                                .setTitle(heading.cleanKodiText().ifEmpty { "Confirm" })
                                .setMessage(message.cleanKodiText())
                                .setPositiveButton(if (yeslabel.isEmpty()) "Yes" else yeslabel.cleanKodiText()) { _, _ -> 
                                    PythonMessageBridge.sendResponse(taskId, true) 
                                }
                                .setNegativeButton(if (nolabel.isEmpty()) "No" else nolabel.cleanKodiText()) { _, _ -> 
                                    PythonMessageBridge.sendResponse(taskId, false) 
                                }
                                .setOnCancelListener { 
                                    PythonMessageBridge.sendResponse(taskId, false) 
                                }
                                .show()
                        }
                        return try {
                            exchanger.exchange(false) as Boolean
                        } catch (e: Exception) {
                            false
                        }
                    }
                })
            } catch (e: Exception) {
                Log.e("TVBrowser", "Error registering background Python dialog listener", e)
            }
        }
    }

    private fun displaySubtitlePickerDialog(subs: JSONArray) {
        val names = mutableListOf<String>()
        val items = mutableListOf<JSONObject>()
        for (i in 0 until subs.length()) {
            val s = subs.getJSONObject(i)
            items.add(s)
            names.add("[${s.optString("service")}] ${s.optString("lang")}: ${s.optString("name")}")
        }

        val checkedItems = BooleanArray(subs.length()) { false }

        AlertDialog.Builder(this)
            .setTitle("Select Subtitles")
            .setMultiChoiceItems(names.toTypedArray(), checkedItems) { _, index, isChecked ->
                checkedItems[index] = isChecked
            }
            .setPositiveButton("Download") { _, _ ->
                val selectedSubs = mutableListOf<JSONObject>()
                for (i in checkedItems.indices) {
                    if (checkedItems[i]) {
                        selectedSubs.add(items[i])
                    }
                }
                if (selectedSubs.isNotEmpty()) {
                    streamsViewModel.downloadSubtitles(selectedSubs)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun displaySubtitleWaitDialog(sourceDataJson: String) {
        AlertDialog.Builder(this)
            .setTitle("Auto-Downloading Subtitles")
            .setMessage("Subtitles are currently being fetched. Do you want to wait for them to finish before playing?")
            .setPositiveButton("Skip & Play") { _, _ ->
                streamsViewModel.cancelSubtitleDownloads()
                streamsViewModel.resolveAndPlayInternal(sourceDataJson)
            }
            .setNegativeButton("Wait") { _, _ ->
                streamsViewModel.pendingPlayVideoSourceData = sourceDataJson
                Toast.makeText(this@MainActivity, "Waiting for subtitles to finish downloading...", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    fun startDpadSelectionMode() {
        cursorManager.isSelectionMode = true
        browserViewModel.currentWebView?.requestFocus()
        Toast.makeText(this, "Navigate with D-pad & press Select on the element to block", Toast.LENGTH_LONG).show()
    }

    private fun checkStartupTabs() {
        lifecycleScope.launch {
            delay(1000)
            val prefs = getSharedPreferences("BrowserSettings", MODE_PRIVATE)
            val pref = prefs.getInt("restore_tabs_pref", 0)
            val savedTabs = prefs.getString("saved_tabs", "[]") ?: "[]"
            if (savedTabs == "[]") return@launch

            when (pref) {
                1 -> browserViewModel.restoreAllTabs(this@MainActivity)
                0 -> {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Restore Session?")
                        .setMessage("Do you want to restore your previous tabs?")
                        .setPositiveButton("Restore All") { _, _ -> browserViewModel.restoreAllTabs(this@MainActivity) }
                        .setNegativeButton("New Session") { _, _ -> 
                            prefs.edit().putString("saved_tabs", "[]").apply()
                            browserViewModel.refreshLists()
                        }
                        .show()
                }
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val isImeVisible = window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true
            if (isImeVisible && event.keyCode != KeyEvent.KEYCODE_BACK) {
                return super.dispatchKeyEvent(event)
            }
        }

        if (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER) {
            if (event.action == KeyEvent.ACTION_UP) {
                longPressHandler.removeCallbacks(longPressRunnable)
                if (cursorManager.isLongPressing) {
                    cursorManager.isLongPressing = false
                    return true
                }
            }
        }

        val isNativeVideoActive = playerEngine.isPlayerActive.value
        val isCustomViewActive = browserViewModel.customView.value != null

        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_MENU) {
            if (!isNativeVideoActive && !isCustomViewActive) {
                if (browserViewModel.topBarVisible.value) {
                    browserViewModel.hideTopBar()
                } else {
                    browserViewModel.showTopBar()
                }
                return true
            }
        }

        if (playerEngine.isPlayerActive.value) {
            val pView = playerEngine.playerView

            if (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER) {
                if (pView != null) {
                    return pView.dispatchKeyEvent(event)
                }
                return true
            }

            if (event.action == KeyEvent.ACTION_DOWN) {
                if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                    playerEngine.stopAndRelease()
                    return true
                }

                if (event.keyCode == KeyEvent.KEYCODE_MENU) {
                    playerEngine.showResolutionSelector()
                    return true
                }

                if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    val isControllerVisible = pView?.isControllerFullyVisible() == true
                    val focusedView = pView?.findFocus()
                    val idName = try {
                        focusedView?.id?.let { id -> pView.resources.getResourceEntryName(id) }
                    } catch (e: Exception) {
                        null
                    }
                    val shouldSeek = !isControllerVisible || 
                                     focusedView == null || 
                                     idName == "exo_play_pause" || 
                                     idName == "exo_play" || 
                                     idName == "exo_pause" || 
                                     idName == "exo_progress" || 
                                     idName == "exo_timebar" || 
                                     idName == "exo_time_bar"

                    if (shouldSeek) {
                        val direction = if (event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1
                        playerEngine.seekVideo(direction, event.repeatCount)
                        return true
                    }
                }
            }

            if (pView != null) {
                return pView.dispatchKeyEvent(event)
            }
            return super.dispatchKeyEvent(event)
        }

        val isBrowserMode = browserViewModel.currentAppTab.value == 0 && 
                            browserViewModel.isBrowsing.value && 
                            !browserViewModel.topBarVisible.value &&
                            browserViewModel.currentDialog.value == null
        
        if (isBrowserMode) {
            val isNativeDpadMode = browserViewModel.navigationModePref.value == 1 && !cursorManager.isSelectionMode

            if (isNativeDpadMode) {
                val key = event.keyCode
                if (key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN ||
                    key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT ||
                    key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER) {
                    
                    if (browserViewModel.currentWebView?.hasFocus() == false) {
                        browserViewModel.currentWebView?.requestFocus()
                    }
                    if (browserViewModel.currentWebView?.dispatchKeyEvent(event) == true) {
                        return true
                    }
                }
            }

            if (cursorManager.isSelectionMode) {
                val key = event.keyCode
                if (key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN ||
                    key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    
                    if (browserViewModel.currentWebView?.hasFocus() == false) {
                        browserViewModel.currentWebView?.requestFocus()
                    }
                    if (browserViewModel.currentWebView?.dispatchKeyEvent(event) == true) {
                        return true
                    }
                }
            }

            if (cursorManager.handleMovementKey(event)) {
                return true
            }

            if (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    if (event.repeatCount == 0) {
                        cursorManager.okDownTime = System.currentTimeMillis()
                        cursorManager.isLongPressing = false
                        longPressHandler.postDelayed(longPressRunnable, 600L)
                    }
                } else if (event.action == KeyEvent.ACTION_UP) {
                    longPressHandler.removeCallbacks(longPressRunnable)
                    if (!cursorManager.isLongPressing) {
                        if (cursorManager.isSelectionMode) {
                            cursorManager.isSelectionMode = false
                            browserViewModel.inspectActiveElement()
                        } else {
                            cursorManager.simulateClick()
                        }
                    }
                    cursorManager.isLongPressing = false
                }
                return true
            }
        }

        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_DOWN) {
            if (cursorManager.isSelectionMode) {
                cursorManager.isSelectionMode = false
                Toast.makeText(this, "Selection mode canceled", Toast.LENGTH_SHORT).show()
                return true
            }
            if (browserViewModel.currentDialog.value != null) {
                browserViewModel.dismissDialog()
                return true
            }
            if (browserViewModel.customView.value != null) {
                browserViewModel.hideCustomViewInternal()
                return true
            }
            if (browserViewModel.currentAppTab.value == 0 && browserViewModel.isBrowsing.value) {
                if (browserViewModel.topBarVisible.value) {
                    browserViewModel.hideTopBar()
                    return true
                }
                if (browserViewModel.currentWebView?.canGoBack() == true) {
                    browserViewModel.currentWebView?.goBack()
                } else {
                    browserViewModel.closeTab(browserViewModel.currentTabIndex.value)
                }
                return true
            }
            if (browserViewModel.currentAppTab.value == 1) {
                if (streamsViewModel.scrapedSources.value != null || streamsViewModel.isScraping.value) {
                    streamsViewModel.clearScrapedSources()
                    return true
                } else if (streamsViewModel.selectedItem.value != null) {
                    streamsViewModel.clearSelectedMedia()
                    return true
                } else if (streamsViewModel.searchResults.value != null) {
                    streamsViewModel.clearSearchResults()
                    return true
                }
            }
        }

        return super.dispatchKeyEvent(event)
    }

    private fun startTorrentServer() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val server = TorrentStreamServer.getInstance(applicationContext)
                server.start()
                Log.i("TVBrowser", "Local Torrent Engine running on port 11470")
                server.checkAndCleanPeriodicCache(applicationContext)
            } catch (e: Exception) {
                Log.e("TVBrowser", "Failed to start local Torrent Server", e)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        longPressHandler.removeCallbacks(longPressRunnable)
        cursorManager.cleanup()
        playerEngine.stopAndRelease()
		TorrentStreamServer.stopInstance()
    }
}