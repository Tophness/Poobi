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
import androidx.lifecycle.lifecycleScope
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.poobi.tvbrowser.ui.MainApp
import com.poobi.tvbrowser.ui.browser.BrowserViewModel
import com.poobi.tvbrowser.ui.browser.CursorManager
import com.poobi.tvbrowser.ui.player.PlayerEngine
import com.poobi.tvbrowser.ui.streams.StreamsEvent
import com.poobi.tvbrowser.ui.streams.StreamsViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private val browserViewModel: BrowserViewModel by viewModels()
    private val streamsViewModel: StreamsViewModel by viewModels()
    
    private lateinit var cursorManager: CursorManager
    private lateinit var playerEngine: PlayerEngine

    private val longPressHandler = Handler(Looper.getMainLooper())
    private val longPressRunnable = Runnable {
        cursorManager.isLongPressing = true
        browserViewModel.triggerContextMenuAtCursor(cursorManager.cursorX.value, cursorManager.cursorY.value)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AdBlockManager.init(this)

        cursorManager = CursorManager(this, browserViewModel)
        
        playerEngine = PlayerEngine(
            context = this,
            prefs = getSharedPreferences("BrowserSettings", MODE_PRIVATE),
            onPlaybackError = { error, url ->
                playerEngine.stopAndRelease()
                if (streamsViewModel.isPlayingFromSavedLink) {
                    streamsViewModel.isPlayingFromSavedLink = false
                    val item = streamsViewModel.selectedItem.value
                    val season = streamsViewModel.lastScrapedSeason
                    val episode = streamsViewModel.lastScrapedEpisode
                    if (item != null) {
                        streamsViewModel.performScrape(item, season, episode)
                    }
                } else {
                    streamsViewModel.resumeScrape()
                }
            },
            onPlaybackStarted = { url, title, item, season, episode, headers ->
                streamsViewModel.onVideoPlaybackStarted(url, title, item, season, episode, headers)
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
                // Safely clear custom website fullscreen views and reset extraction locks
                browserViewModel.hideCustomViewInternal()
                streamsViewModel.resumeScrape()
            }
        )

        // Web Extraction Play Video callback registered cleanly
        browserViewModel.onPlayNativeVideo = { videoUrl, title ->
            playerEngine.launchVideo(
                videoUrl = videoUrl,
                title = title,
                headers = browserViewModel.interceptedMediaUrls[videoUrl] ?: emptyMap(),
                subtitles = browserViewModel.interceptedSubtitleUrls,
                item = null,
                season = null,
                episode = null
            )
        }

        initPythonAsync()

        lifecycleScope.launchWhenStarted {
            streamsViewModel.itemEpisodes.collect { episodes ->
                if (episodes != null) {
                    val season = streamsViewModel.lastScrapedSeason
                    val episode = streamsViewModel.lastScrapedEpisode
                    if (season != null && episode != null) {
                        for (i in 0 until episodes.length()) {
                            val ep = episodes.getJSONObject(i)
                            if (ep.optInt("episode_number") == episode + 1) {
                                playerEngine.setNextEpisode(ep)
                                break
                            }
                        }
                    }
                }
            }
        }

        lifecycleScope.launchWhenStarted {
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
                                episode = event.episode
                            )
                        } else {
                            browserViewModel.loadUrlAndBrowse(this@MainActivity, event.url) 
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

        setContent {
            MainApp(
                browserViewModel = browserViewModel,
                streamsViewModel = streamsViewModel,
                cursorManager = cursorManager,
                playerEngine = playerEngine
            )
        }

        checkStartupTabs()
    }

    override fun onResume() {
        super.onResume()
        browserViewModel.refreshLists()
        streamsViewModel.refreshFavoritesSet()
        registerPythonDialogListener()
    }

    private fun initPythonAsync() {
        lifecycleScope.launch(Dispatchers.IO) {
            if (!Python.isStarted()) {
                Python.start(AndroidPlatform(this@MainActivity))
            }
            val py = Python.getInstance()
            try {
                py.getModule("modules.control")
                py.getModule("tmdb.tmdb_api")
                py.getModule("main")
            } catch (e: Exception) {}
            withContext(Dispatchers.Main) {
                registerPythonDialogListener()
            }
        }
    }

    private fun registerPythonDialogListener() {
        try {
            val py = Python.getInstance()
            val control = py.getModule("modules.control")
            control.callAttr("set_dialog_listener", object : PythonDialogListener {
                override fun infoDialog(message: String, heading: String, sound: Boolean, icon: String) {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "${heading.cleanKodiText()}: ${message.cleanKodiText()}", Toast.LENGTH_LONG).show()
                    }
                }

                override fun okDialog(message: String, heading: String): Boolean {
                    val future = CompletableDeferred<Boolean>()
                    runOnUiThread {
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle(heading.cleanKodiText().ifEmpty { "Notice" })
                            .setMessage(message.cleanKodiText())
                            .setPositiveButton("OK") { _, _ -> future.complete(true) }
                            .setOnCancelListener { future.complete(true) }
                            .show()
                    }
                    return runBlocking { future.await() }
                }

                override fun yesnoDialog(message: String, heading: String, nolabel: String, yeslabel: String): Boolean {
                    val future = CompletableDeferred<Boolean>()
                    runOnUiThread {
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle(heading.cleanKodiText().ifEmpty { "Confirm" })
                            .setMessage(message.cleanKodiText())
                            .setPositiveButton(if (yeslabel.isEmpty()) "Yes" else yeslabel.cleanKodiText()) { _, _ -> future.complete(true) }
                            .setNegativeButton(if (nolabel.isEmpty()) "No" else nolabel.cleanKodiText()) { _, _ -> future.complete(false) }
                            .setOnCancelListener { future.complete(false) }
                            .show()
                    }
                    return runBlocking { future.await() }
                }
            })
        } catch (e: Exception) {}
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
        browserViewModel.initDpadNav()
        cursorManager.isSelectionMode = true
        Toast.makeText(this, "D-pad Navigation: Select Element and Press OK", Toast.LENGTH_LONG).show()
    }

    private fun checkStartupTabs() {
        val prefs = getSharedPreferences("BrowserSettings", MODE_PRIVATE)
        val pref = prefs.getInt("restore_tabs_pref", 0)
        val savedTabs = prefs.getString("saved_tabs", "[]") ?: "[]"
        if (savedTabs == "[]") return

        when (pref) {
            1 -> browserViewModel.restoreAllTabs(this)
            0 -> {
                AlertDialog.Builder(this)
                    .setTitle("Restore Session?")
                    .setMessage("Do you want to restore your previous tabs?")
                    .setPositiveButton("Restore All") { _, _ -> browserViewModel.restoreAllTabs(this) }
                    .setNegativeButton("New Session") { _, _ -> 
                        prefs.edit().putString("saved_tabs", "[]").apply()
                        browserViewModel.refreshLists()
                    }
                    .show()
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
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER) {
                val pView = playerEngine.playerView
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
                if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                    playerEngine.seekVideo(-1, event.repeatCount)
                    return true
                }
                if (event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    playerEngine.seekVideo(1, event.repeatCount)
                    return true
                }
            }
            return super.dispatchKeyEvent(event)
        }

        val isBrowserMode = browserViewModel.currentAppTab.value == 0 && 
                            browserViewModel.isBrowsing.value && 
                            !browserViewModel.topBarVisible.value &&
                            browserViewModel.currentDialog.value == null
        
        if (isBrowserMode) {
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
                        if (browserViewModel.navigationModePref.value == 1) {
                            browserViewModel.currentWebView?.evaluateJavascript("if(window.navHelper) window.navHelper.clickFocused();", null)
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

    override fun onDestroy() {
        super.onDestroy()
        cursorManager.cleanup()
        playerEngine.stopAndRelease()
    }
}