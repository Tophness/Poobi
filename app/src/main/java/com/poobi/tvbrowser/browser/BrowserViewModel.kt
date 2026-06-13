package com.poobi.tvbrowser.browser

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log
import android.view.View
import android.webkit.*
import android.widget.Toast
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.brave.adblock.AdBlockUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class TabMetadata(
    val defaultTitle: String?,
    val streamItemJson: String? = null,
    val season: Int? = null,
    val episode: Int? = null
)

sealed class BrowserDialogState {
    data class Download(val url: String, val fileName: String, val sizeMb: Float) : BrowserDialogState()
    data class PopupBlocked(val resultMsg: Message) : BrowserDialogState()
    data class StreamPicker(val streams: List<String>, val streamInfos: List<String>) : BrowserDialogState()
    data class AdvancedBlockElement(val data: JSONObject) : BrowserDialogState()
    data class SaveBlockRule(val url: String, val selector: String) : BrowserDialogState()
    data class SaveAutoplayProfile(val url: String, val selectors: List<String>) : BrowserDialogState()
    data class Error(val message: String) : BrowserDialogState()
}

class BrowserViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    val prefs: SharedPreferences = appContext.getSharedPreferences("BrowserSettings", Context.MODE_PRIVATE)

    private val _webViews = mutableStateListOf<WebView>()
    private val _webViewHosts = java.util.WeakHashMap<WebView, String>()

    val interceptedMediaUrls = ConcurrentHashMap<String, Map<String, String>>()
    val interceptedSubtitleUrls = ConcurrentHashMap<String, Map<String, String>>()

    val currentAppTab = MutableStateFlow(0) // 0 = Browser, 1 = Streams

    private val _currentTabIndex = MutableStateFlow(-1)
    val currentTabIndex: StateFlow<Int> = _currentTabIndex.asStateFlow()

    private val _isBrowsing = MutableStateFlow(false)
    val isBrowsing: StateFlow<Boolean> = _isBrowsing.asStateFlow()

    private val _topBarVisible = MutableStateFlow(false)
    val topBarVisible: StateFlow<Boolean> = _topBarVisible.asStateFlow()

    private val _currentUrl = MutableStateFlow("")
    val currentUrl: StateFlow<String> = _currentUrl.asStateFlow()

    private val _isRecordingAutoplay = MutableStateFlow(false)
    val isRecordingAutoplay: StateFlow<Boolean> = _isRecordingAutoplay.asStateFlow()

    private val _currentDialog = MutableStateFlow<BrowserDialogState?>(null)
    val currentDialog: StateFlow<BrowserDialogState?> = _currentDialog.asStateFlow()

    private val _customView = MutableStateFlow<View?>(null)
    val customView: StateFlow<View?> = _customView.asStateFlow()

    private var pendingCustomView: View? = null
    var customViewCallback: WebChromeClient.CustomViewCallback? = null
    var onPlayNativeVideo: ((videoUrl: String, title: String?, alternativeUrls: List<String>?, alternativeNames: List<String>?) -> Unit)? = null

    val currentWebView: WebView? get() = if (_currentTabIndex.value in _webViews.indices) _webViews[_currentTabIndex.value] else null

    val isLightTheme = MutableStateFlow(prefs.getBoolean("light_theme", false))
    val silentPopupBlock = MutableStateFlow(prefs.getBoolean("silent_popup_block", true))
    val extractVideoPref = MutableStateFlow(prefs.getInt("extract_video_pref", 0))
    val videoTriggerPref = MutableStateFlow(prefs.getInt("video_trigger_pref", 1))
    val clickjackPref = MutableStateFlow(prefs.getBoolean("clickjack_prevention", true))
    val navigationModePref = MutableStateFlow(prefs.getInt("navigation_mode_pref", 0))
    val scrollTopbarEnabled = MutableStateFlow(prefs.getBoolean("scroll_topbar_enabled", true))

    private val _historyList = MutableStateFlow<JSONArray>(JSONArray())
    val historyList: StateFlow<JSONArray> = _historyList.asStateFlow()

    private val _favoritesList = MutableStateFlow<JSONArray>(JSONArray())
    val favoritesList: StateFlow<JSONArray> = _favoritesList.asStateFlow()

    private val _downloadsList = MutableStateFlow<JSONArray>(JSONArray())
    val downloadsList: StateFlow<JSONArray> = _downloadsList.asStateFlow()

    private val _savedTabsList = MutableStateFlow<JSONArray>(JSONArray())
    val savedTabsList: StateFlow<JSONArray> = _savedTabsList.asStateFlow()

    private var currentHost = ""
    var isExtractionActive = false
    val recordedSelectors = mutableListOf<String>()

    companion object {
        const val DEFAULT_AUTOPLAY_SCRIPT = """
            (function() {
                if (window.autoPlayInjected) return;
                window.autoPlayInjected = true;
                const clickedElements = new Set();
                const clickPlayButtons = () => {
                    const selectors = ['button', 'div', 'i', 'span', 'a', '[class*="play"]', '[id*="play"]', '[class*="Player"]', '[class*="control"]', '.ytp-large-play-button', '.vjs-big-play-button'];
                    const elements = document.querySelectorAll(selectors.join(','));
                    elements.forEach(el => {
                        if (clickedElements.has(el)) return;
                        const cls = (el.className || "").toString().toLowerCase();
                        const id = (el.id || "").toLowerCase();
                        const text = (el.innerText || "").toLowerCase();
                        const isPlay = cls.includes('play') || id.includes('play') || text.trim() === 'play';
                        const isAd = cls.includes('ad') || id.includes('ad') || id.includes('google') || cls.includes('banner');
                        if (isPlay && !isAd) {
                            const rect = el.getBoundingClientRect();
                            if (rect.width > 5 && rect.height > 5) {
                                const style = window.getComputedStyle(el);
                                if (style.display !== 'none' && style.visibility !== 'hidden' && style.opacity !== '0') {
                                    clickedElements.add(el);
                                    ['mousedown', 'mouseup', 'click'].forEach(name => {
                                        el.dispatchEvent(new MouseEvent(name, {bubbles: true, cancelable: true, view: window, buttons: 1}));
                                    });
                                }
                            }
                        }
                    });
                };
                clickPlayButtons();
                const observer = new MutationObserver((mutations) => {
                    for (const mutation of mutations) { if (mutation.addedNodes.length > 0) { clickPlayButtons(); break; } }
                });
                observer.observe(document.body || document.documentElement, { childList: true, subtree: true });
            })();
        """

        fun generateSelectorScript(selectors: JSONArray): String {
            return """
                (function() {
                    if (window.autoPlayInjected) return;
                    window.autoPlayInjected = true;
                    const selectors = $selectors;
                    const clicked = new Set();
                    const run = () => {
                        selectors.forEach(sel => {
                            try {
                                const elements = document.querySelectorAll(sel);
                                elements.forEach(el => {
                                    if (el && !clicked.has(el)) {
                                        const rect = el.getBoundingClientRect();
                                        if (rect.width > 5 && rect.height > 5) {
                                            const style = window.getComputedStyle(el);
                                            if (style.display !== 'none' && style.visibility !== 'hidden' && style.opacity !== '0') {
                                                clicked.add(el);
                                                ['mousedown', 'mouseup', 'click'].forEach(n => {
                                                    el.dispatchEvent(new MouseEvent(n, {bubbles:true, cancelable:true, buttons:1, view:window}));
                                                });
                                            }
                                        }
                                    }
                                });
                            } catch(e) {}
                        });
                    };
                    run();
                    const obs = new MutationObserver(run);
                    obs.observe(document.body || document.documentElement, { childList: true, subtree: true });
                    setTimeout(() => obs.disconnect(), 15000);
                })();
            """.trimIndent()
        }
    }

    init {
        refreshLists()
    }

    fun refreshLists() {
        viewModelScope.launch(Dispatchers.IO) {
            val history = JSONArray(prefs.getString("history", "[]") ?: "[]")
            val favorites = JSONArray(prefs.getString("favorites", "[]") ?: "[]")
            val downloads = JSONArray(prefs.getString("downloads", "[]") ?: "[]")
            val savedTabs = JSONArray(prefs.getString("saved_tabs", "[]") ?: "[]")
            
            withContext(Dispatchers.Main) {
                _historyList.value = history
                _favoritesList.value = favorites
                _downloadsList.value = downloads
                _savedTabsList.value = savedTabs
            }
        }
    }

    fun showTopBar() { _topBarVisible.value = true }
    fun hideTopBar() { _topBarVisible.value = false }

    fun dismissDialog() {
        _currentDialog.value = null
        currentWebView?.apply {
            isFocusable = true
            isFocusableInTouchMode = true
            requestFocus()
        }
    }

    fun getWebViewsList(): List<WebView> = _webViews

    fun createNewTab(context: Context, url: String? = null, switchTo: Boolean = true, title: String? = null): WebView {
        val newWebView = WebView(context).apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
            visibility = View.GONE
            tag = TabMetadata(defaultTitle = title)
        }
        setupWebView(newWebView)
        _webViews.add(newWebView)

        if (url != null) newWebView.loadUrl(url)
        saveTabs()
        if (switchTo) {
            switchTab(_webViews.size - 1)
            if (url.isNullOrBlank()) {
                _topBarVisible.value = true
            }
        }
        return newWebView
    }

    fun switchTab(index: Int) {
        if (index !in _webViews.indices) {
            Log.e("TVBrowser", "switchTab failure: Index $index out of bounds (Size: ${_webViews.size})")
            return
        }
        
        val oldWebView = currentWebView
        oldWebView?.visibility = View.GONE
        _currentTabIndex.value = index
        _isBrowsing.value = true
        _webViews[index].visibility = View.VISIBLE
        interceptedMediaUrls.clear()
        interceptedSubtitleUrls.clear()
        
        val url = _webViews[index].url ?: ""
        _currentUrl.value = url
        _webViews[index].requestFocus()
    }

    fun closeTab(index: Int) {
        if (index !in _webViews.indices) {
            Log.e("TVBrowser", "closeTab failure: Index $index out of bounds (Size: ${_webViews.size})")
            return
        }
        
        val isActiveTab = (index == _currentTabIndex.value)
        val wv = _webViews[index]
        
        (wv.parent as? android.view.ViewGroup)?.removeView(wv)
        
        try {
            wv.stopLoading()
            wv.loadUrl("about:blank")
            wv.clearHistory()
        } catch (e: Exception) {
            Log.e("TVBrowser", "Error clearing WebView state", e)
        }
        
        if (isActiveTab) {
            if (_webViews.size > 1) {
                val newIndex = if (index == _webViews.size - 1) index - 1 else index
                switchTab(newIndex)
            } else {
                showHomeScreen()
            }
        }
        
        _webViews.remove(wv)
        wv.destroy()
        saveTabs()
        
        if (_webViews.isEmpty()) {
            showHomeScreen()
        } else {
            _currentTabIndex.value = _webViews.indexOf(currentWebView).coerceAtLeast(0)
        }
    }

    fun loadUrlAndBrowse(
        context: Context, 
        inputUrl: String, 
        newTab: Boolean = false,
        streamItemJson: String? = null,
        season: Int? = null,
        episode: Int? = null
    ) {
        var url = inputUrl.trim()
        if (url.isNotEmpty()) {
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = if (url.contains(".") && !url.contains(" ")) "https://$url" else "https://www.google.com/search?q=$url"
            }
            if (newTab || _webViews.isEmpty()) {
                val newWv = createNewTab(context, url, switchTo = true)
                newWv.tag = TabMetadata(
                    defaultTitle = newWv.title,
                    streamItemJson = streamItemJson,
                    season = season,
                    episode = episode
                )
            } else {
                if (_currentTabIndex.value == -1) {
                    _currentTabIndex.value = 0
                }
                currentWebView?.tag = TabMetadata(
                    defaultTitle = currentWebView?.title,
                    streamItemJson = streamItemJson,
                    season = season,
                    episode = episode
                )
                currentWebView?.loadUrl(url)
                _isBrowsing.value = true
                currentWebView?.visibility = View.VISIBLE
            }
            _topBarVisible.value = false
        }
    }

    fun restoreAllTabs(context: Context) {
        val savedTabs = prefs.getString("saved_tabs", "[]") ?: "[]"
        try {
            val array = JSONArray(savedTabs)
            if (array.length() == 0) return
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val url = obj.optString("url")
                if (url.isNotEmpty()) {
                    val title = obj.optString("title")
                    createNewTab(context, url, switchTo = false, title = title)
                }
            }
        } catch (e: Exception) {
            Log.e("TVBrowser", "Error restoring tabs: ${e.message}")
        }
        refreshLists()
    }

    fun showHomeScreen() {
        _isBrowsing.value = false
        _topBarVisible.value = false
        _currentUrl.value = ""
        _currentTabIndex.value = -1
    }

    fun isFavorited(url: String): Boolean {
        val favoritesJson = prefs.getString("favorites", "[]") ?: "[]"
        try {
            val array = JSONArray(favoritesJson)
            for (i in 0 until array.length()) {
                if (array.getJSONObject(i).optString("url") == url) {
                    return true
                }
            }
        } catch (e: Exception) {}
        return false
    }

    fun toggleFavoriteOnCurrent() {
        val wv = currentWebView ?: return
        val url = wv.url ?: return
        if (isFavorited(url)) {
            removeFromList("favorites", url)
        } else {
            saveToList("favorites", url, wv.title ?: url, "${url.hashCode()}.png")
        }
    }

    fun triggerContextMenuAtCursor(cursorX: Float, cursorY: Float) {
        val wv = currentWebView ?: return
        val density = appContext.resources.displayMetrics.density
        val x = (cursorX / density).toInt()
        val y = (cursorY / density).toInt()

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
            if (result != null && result != "null") {
                try {
                    val json = JSONObject(result)
                    val link = json.optString("link")

                    wv.apply {
                        isFocusable = false
                        isFocusableInTouchMode = false
                        clearFocus()
                    }

                    _currentDialog.value = BrowserDialogState.SaveBlockRule(link, "context_menu_trigger") 
                } catch (e: Exception) { }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView(wv: WebView) {
        wv.isFocusable = true
        wv.isFocusableInTouchMode = true
        val customUserAgent = prefs.getString("user_agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
        
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            userAgentString = customUserAgent
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                isAlgorithmicDarkeningAllowed = !isLightTheme.value
            }
        }

        wv.addJavascriptInterface(WebAppInterface(), "AndroidAutoplay")

        wv.setDownloadListener { url, _, contentDisposition, mimetype, contentLength ->
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimetype)
            _currentDialog.value = BrowserDialogState.Download(url, fileName, contentLength / (1024f * 1024f))
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean {
                if (silentPopupBlock.value) return true
                if (resultMsg != null) {
                    _currentDialog.value = BrowserDialogState.PopupBlocked(resultMsg)
                }
                return true
            }

            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                customViewCallback = callback
                pendingCustomView = view
                attemptVideoExtraction(view, callback)
            }

            override fun onHideCustomView() {
                hideCustomViewInternal()
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                super.onReceivedTitle(view, title)
                interceptedMediaUrls.clear()
                interceptedSubtitleUrls.clear()
            }
        }

        wv.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                interceptedMediaUrls.clear()
                interceptedSubtitleUrls.clear()
                if (url != null && view != null) {
                    val host = Uri.parse(url).host ?: ""
                    _webViewHosts[view] = host
                    if (view == currentWebView) currentHost = host
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (url == null || url == "about:blank" || view == null) return

                if (view == currentWebView) {
                    currentHost = Uri.parse(url).host ?: ""
                    _currentUrl.value = url
                }

                injectClickjackPrevention(view)
                applyBlockedElements(view)
                saveTabs()

                if (videoTriggerPref.value == 0) {
                    triggerAutoPlayClicks(view)
                }

                if (navigationModePref.value == 1) {
                    initDpadNav()
                }

                view.evaluateJavascript("(function() { return document.documentElement.innerText; })();") { content ->
                    if (content != null && content.contains("#EXTM3U")) {
                        val headers = mutableMapOf<String, String>()
                        CookieManager.getInstance().getCookie(url ?: "")?.let { headers["Cookie"] = it }
                        headers["User-Agent"] = view.settings.userAgentString
                        if (url != null) {
                            interceptedMediaUrls[url] = headers
                            if (extractVideoPref.value != 0) {
                                attemptVideoExtraction(null, null)
                            }
                        }
                    }
                }

                view.postDelayed({ saveSnapshot(url, view.title ?: "Website", view) }, 2500)
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val requestedUrl = request?.url?.toString()
                if (requestedUrl != null) {
                    if (requestedUrl.contains("disable-devtool") || requestedUrl.contains("devtools-detector")) {
                        return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream("".toByteArray()))
                    }

                    val isVideo = requestedUrl.contains(".m3u8") || 
                                 requestedUrl.contains(".mp4") || 
                                 requestedUrl.contains(".mkv") ||
                                 requestedUrl.contains(".mpd") ||
                                 requestedUrl.contains("/m3u8/")

                    if (isVideo) {
                        val wasEmpty = interceptedMediaUrls.isEmpty()
                        val headers = request.requestHeaders.toMutableMap()
                        
                        CookieManager.getInstance().getCookie(requestedUrl)?.let {
                            headers["Cookie"] = it
                        }
                        
                        val userAgent = prefs.getString("user_agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36") ?: ""
                        val viewUrl = _currentUrl.value

                        if (!headers.containsKey("User-Agent")) {
                            headers["User-Agent"] = userAgent
                        }

                        if (!headers.containsKey("Referer")) {
                            headers["Referer"] = viewUrl.ifEmpty { requestedUrl }
                        }
                        if (!headers.containsKey("Origin")) {
                            val originUrl = viewUrl.ifEmpty { requestedUrl }
                            Uri.parse(originUrl).let { uri ->
                                if (uri.scheme != null && uri.host != null) {
                                    headers["Origin"] = "${uri.scheme}://${uri.host}"
                                }
                            }
                        }

                        interceptedMediaUrls[requestedUrl] = headers
                        
                        if (videoTriggerPref.value == 0 && _isBrowsing.value && wasEmpty) {
                            view?.post { attemptVideoExtraction(null, null) }
                        }
                    }
                    if (requestedUrl.contains(".srt") || requestedUrl.contains(".vtt") || requestedUrl.contains(".ass")) {
                        interceptedSubtitleUrls[requestedUrl] = request.requestHeaders
                    }

                    val filterOption = AdBlockUtils.mapRequestToFilterOption(request)
                    val host = _webViewHosts[view] ?: currentHost
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

    private fun injectClickjackPrevention(wv: WebView) {
        if (!clickjackPref.value) return
        val script = """
            (function() {
                if (window.clickjackPrevented) return;
                window.clickjackPrevented = true;
                window.open = function() { return { focus: function(){}, close: function(){}, blur: function(){} }; };
                var neutralize = function() {
                    var all = document.querySelectorAll('div, section, ins, iframe');
                    for (var i = 0; i < all.length; i++) {
                        var el = all[i];
                        var s = window.getComputedStyle(el);
                        if (s.position === 'fixed' || s.position === 'absolute') {
                            var rect = el.getBoundingClientRect();
                            if (rect.width >= window.innerWidth * 0.9 && rect.height >= window.innerHeight * 0.9) {
                                if (s.zIndex > 10 && (s.opacity < 0.1 || s.backgroundColor === 'transparent' || s.backgroundColor === 'rgba(0, 0, 0, 0)')) {
                                    el.style.pointerEvents = 'none'; el.style.display = 'none';
                                }
                            }
                        }
                    }
                };
                neutralize(); setInterval(neutralize, 1500);
                var originalStop = Event.prototype.stopPropagation;
                var originalStopImmediate = Event.prototype.stopImmediatePropagation;
                Event.prototype.stopPropagation = function() { if (['click', 'mousedown', 'mouseup'].indexOf(this.type) !== -1) return; originalStop.apply(this, arguments); };
                Event.prototype.stopImmediatePropagation = function() { if (['click', 'mousedown', 'mouseup'].indexOf(this.type) !== -1) return; originalStopImmediate.apply(this, arguments); };
                var style = document.createElement('style');
                style.innerHTML = 'video, .video-player, [class*="player"] { pointer-events: auto !important; z-index: 2147483647 !important; }';
                document.head.appendChild(style);
            })();
        """.trimIndent()
        wv.evaluateJavascript(script, null)
    }

    private fun triggerAutoPlayClicks(wv: WebView) {
        val url = wv.url ?: return
        val profilesJson = prefs.getString("autoplay_profiles", "[]") ?: "[]"
        val profiles = JSONArray(profilesJson)
        var executedAny = false

		for (i in 0 until profiles.length()) {
			val obj = profiles.getJSONObject(i)
			val patterns = obj.getJSONArray("urlPatterns")
			var matchesPattern = false
			for (j in 0 until patterns.length()) {
				val pattern = patterns.getString(j).replace(".", "\\.").replace("*", ".*").replace("?", ".")
				if (java.util.regex.Pattern.compile(pattern, java.util.regex.Pattern.CASE_INSENSITIVE).matcher(url).find()) {
					matchesPattern = true; break
				}
			}

			if (matchesPattern && obj.optBoolean("enabled", true)) {
				executedAny = true
				val scriptToRun = obj.getString("script")
				wv.evaluateJavascript(scriptToRun, null)
			}
		}

        if (!executedAny) {
            wv.evaluateJavascript(DEFAULT_AUTOPLAY_SCRIPT, null)
        }

        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        var attempts = 0
        val extractionTask = object : Runnable {
            override fun run() {
                if (videoTriggerPref.value != 0 || !_isBrowsing.value || attempts >= 8) return
                attemptVideoExtraction(null, null)
                attempts++
                handler.postDelayed(this, 2000)
            }
        }
        handler.post(extractionTask)
    }

    fun startRecordingAutoplay() {
        val wv = currentWebView ?: return
        _isRecordingAutoplay.value = true
        recordedSelectors.clear()
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

    fun stopRecordingAutoplay() {
        _isRecordingAutoplay.value = false
        val wv = currentWebView
        wv?.evaluateJavascript("document.removeEventListener('click', window.recordedClickCallback, true);", null)
        if (recordedSelectors.isNotEmpty()) {
            _currentDialog.value = BrowserDialogState.SaveAutoplayProfile(wv?.url ?: "", recordedSelectors.toList())
        }
    }

    fun blockElementAtCursor(cursorX: Float, cursorY: Float) {
        val wv = currentWebView ?: return
        val density = appContext.resources.displayMetrics.density
        val x = (cursorX / density).toInt()
        val y = (cursorY / density).toInt()

        val setupScript = """
            (function() {
                if (!window.blockerHelper) {
                    window.blockerHelper = {
                        currentEl: null,
                        candidateEls: [],
                        candidateIndex: 0,
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
                                if (this.candidateEls.length === 0) return {error: 'HTML/Body Limits'};
                                this.candidateIndex = 0;
                                this.currentEl = this.candidateEls[0];
                                return this.getOptions();
                            } catch(e) {
                                return {error: e.message};
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
                    _currentDialog.value = BrowserDialogState.AdvancedBlockElement(json)
                } else {
                    _currentDialog.value = BrowserDialogState.Error(json.optString("error", "Target error"))
                }
            } catch (e: Exception) {
                _currentDialog.value = BrowserDialogState.Error("Parsing error")
            }
        }
    }

    private fun attemptVideoExtraction(view: View?, callback: WebChromeClient.CustomViewCallback?) {
        if (isExtractionActive) return
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
                val pref = extractVideoPref.value
                if (pref == 1 && finalCandidates.size == 1) {
                    playVideoInNativePlayer(finalCandidates[0], wv.title)
                } else if (pref == 2) {
                    _customView.value = view
                    isExtractionActive = false
                } else {
                    parseHlsAndShowPicker(finalCandidates)
                }
            } else {
                isExtractionActive = false
                if (view != null) {
                    _customView.value = view
                }
            }
        }
    }

    private fun parseHlsAndShowPicker(streams: List<String>) {
        val streamInfos = mutableListOf<String>()
        val streamUrls = mutableListOf<String>()

        viewModelScope.launch(Dispatchers.IO) {
            for (url in streams) {
                val headers = interceptedMediaUrls[url] ?: emptyMap()
                if (url.contains(".m3u8") || url.contains("/streamsvr/")) {
                    try {
                        val connection = URL(url).openConnection() as HttpURLConnection
                        headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
                        if (!headers.containsKey("User-Agent")) {
                            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
                        }
                        connection.connectTimeout = 5000
                        connection.readTimeout = 5000
                        
                        val content = connection.inputStream.bufferedReader().use { it.readText() }
                        
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

            withContext(Dispatchers.Main) {
                if (streamUrls.isNotEmpty()) {
                    isExtractionActive = false
                    playVideoInNativePlayer(
                        url = streamUrls[0],
                        title = currentWebView?.title,
                        alternativeUrls = streamUrls,
                        alternativeNames = streamInfos
                    )
                } else {
                    isExtractionActive = false
                }
            }
        }
    }

    fun resumeTimersOnCurrent() {
        currentWebView?.apply {
            onResume()
            resumeTimers()
        }
    }

    fun playVideoInNativePlayer(
        url: String, 
        title: String?, 
        alternativeUrls: List<String>? = null, 
        alternativeNames: List<String>? = null
    ) {
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
        onPlayNativeVideo?.invoke(url, title, alternativeUrls, alternativeNames)
    }

    fun dismissStreamPicker() {
        isExtractionActive = false
        _customView.value = pendingCustomView
        dismissDialog()
    }

    fun hideCustomViewInternal() {
        _customView.value = null
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        isExtractionActive = false
        currentWebView?.apply {
            onResume()
            resumeTimers()
        }
    }

    fun allowPopup(context: Context, resultMsg: Message, rememberDecision: Boolean) {
        if (rememberDecision) {
            prefs.edit().putBoolean("silent_popup_block", true).apply()
            silentPopupBlock.value = true
        }
        val transport = resultMsg.obj as? WebView.WebViewTransport ?: return
        val newWv = createNewTab(context)
        transport.webView = newWv
        resultMsg.sendToTarget()
        dismissDialog()
    }

    fun denyPopup(rememberDecision: Boolean) {
        if (rememberDecision) {
            prefs.edit().putBoolean("silent_popup_block", true).apply()
            silentPopupBlock.value = true
        }
        dismissDialog()
    }

    fun startDownload(context: Context, url: String, fileName: String) {
        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
        try {
            val request = android.app.DownloadManager.Request(Uri.parse(url))
                .setTitle(fileName)
                .setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, fileName)

            downloadManager.enqueue(request)
            Toast.makeText(context, "Starting download: $fileName", Toast.LENGTH_SHORT).show()

            saveToList("downloads", url, fileName, "")
        } catch (e: Exception) {
            Toast.makeText(context, "Download failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    fun highlightElement(selector: String) {
        currentWebView?.evaluateJavascript("window.blockerHelper.setHighlight('$selector')", null)
    }

    fun clearElementHighlight() {
        currentWebView?.evaluateJavascript("window.blockerHelper.clearHighlight()", null)
    }

    fun selectNextElementCandidate(onResult: (JSONObject) -> Unit) {
        currentWebView?.evaluateJavascript("window.blockerHelper.nextCandidate()") { result ->
            if (result != null && result != "null") {
                try {
                    onResult(JSONObject(result))
                } catch (e: Exception) {}
            }
        }
    }

    fun selectParentElementCandidate(onResult: (JSONObject) -> Unit) {
        currentWebView?.evaluateJavascript("window.blockerHelper.selectParent()") { result ->
            if (result != null && result != "null") {
                try {
                    onResult(JSONObject(result))
                } catch (e: Exception) {}
            }
        }
    }

    fun showSaveBlockRuleDialog(selector: String) {
        val url = currentWebView?.url ?: ""
        _currentDialog.value = BrowserDialogState.SaveBlockRule(url, selector)
    }

    fun saveBlockedElementRule(name: String, url: String, selector: String) {
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
                put("id", UUID.randomUUID().toString())
                put("name", name)
                put("enabled", true)
                put("urlPatterns", JSONArray().put("*$host*"))
                put("selectors", JSONArray().put(selector))
            }
            array.put(newRule)
        }

        prefs.edit().putString("blocked_elements", array.toString()).apply()
        refreshLists()
        dismissDialog()
    }

    fun saveAutoplayProfile(name: String, url: String, selectors: List<String>) {
        val host = Uri.parse(url).host ?: "*"
        val selectorsJson = JSONArray(selectors)
        val script = generateSelectorScript(selectorsJson)

        val profilesJson = prefs.getString("autoplay_profiles", "[]") ?: "[]"
        val array = JSONArray(profilesJson)
        array.put(JSONObject().apply {
            put("id", UUID.randomUUID().toString())
            put("name", name)
            put("enabled", true)
            put("urlPatterns", JSONArray().put("*$host*"))
            put("script", script)
            put("use_script", false)
            put("selectors", selectorsJson)
        })
        prefs.edit().putString("autoplay_profiles", array.toString()).apply()
        dismissDialog()
    }

    fun initDpadNav() {
        val script = """
            (function() {
                if (!window.navHelper) {
                    window.navHelper = {
                        focusedEl: null,
                        highlight: function(el) {
                            this.clearHighlight(); if (!el) return; this.focusedEl = el;
                            let style = document.getElementById('poobi-nav-highlight');
                            if (!style) { style = document.createElement('style'); style.id = 'poobi-nav-highlight'; document.head.appendChild(style); }
                            el.classList.add('poobi-focused');
                            style.innerHTML = '.poobi-focused { outline: 6px solid #FF5722 !important; box-shadow: 0 0 15px rgba(255, 87, 34, 0.7) !important; position: relative !important; z-index: 2147483645 !important; }';
                            el.scrollIntoView({block: 'nearest', behavior: 'smooth'});
                        },
                        clearHighlight: function() { if (this.focusedEl) { this.focusedEl.classList.remove('poobi-focused'); this.focusedEl = null; } },
                        getFocusableElements: function() {
                            return Array.from(document.querySelectorAll('a, button, input, select, textarea, [tabindex]:not([tabindex="-1"]), [onclick], [role="button"]')).filter(el => el.getBoundingClientRect().width > 0);
                        },
                        move: function(direction) {
                            const elements = this.getFocusableElements();
                            if (elements.length === 0) return;
                            
                            let currentRect = this.focusedEl ? this.focusedEl.getBoundingClientRect() : { left: 0, top: 0, right: 0, bottom: 0 };
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
                        clickFocused: function() { if (this.focusedEl) this.focusedEl.click(); }
                    };
                }
                if (!window.navHelper.focusedEl) {
                    const elements = window.navHelper.getFocusableElements();
                    if (elements.length > 0) window.navHelper.highlight(elements[0]);
                }
            })();
        """.trimIndent()
        currentWebView?.evaluateJavascript(script, null)
    }

    fun handleDpadNav(direction: String) {
        currentWebView?.evaluateJavascript("if(window.navHelper) window.navHelper.move('$direction');", null)
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
                val pattern = patterns.getString(j).replace(".", "\\.").replace("*", ".*")
                if (java.util.regex.Pattern.compile(pattern, java.util.regex.Pattern.CASE_INSENSITIVE).matcher(url).find()) {
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

    private fun saveSnapshot(url: String, title: String, wv: WebView) {
        if (wv.width == 0 || wv.height == 0) return
        try {
            val bitmap = Bitmap.createBitmap(wv.width, wv.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            wv.draw(canvas)
            val thumb = Bitmap.createScaledBitmap(bitmap, 320, 180, true)
            val filename = "${url.hashCode()}.png"
            val file = File(appContext.filesDir, filename)
            FileOutputStream(file).use { thumb.compress(Bitmap.CompressFormat.PNG, 80, it) }
            bitmap.recycle()
            saveToList("history", url, title, filename)
        } catch (e: Exception) {}
    }

    private fun saveToList(key: String, url: String, title: String, thumbFile: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val jsonString = prefs.getString(key, "[]") ?: "[]"
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
            refreshLists()
        }
    }

    fun removeFromList(key: String, url: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val array = JSONArray(prefs.getString(key, "[]") ?: "[]")
            val newArray = JSONArray()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                if (obj.getString("url") != url) newArray.put(obj)
            }
            prefs.edit().putString(key, newArray.toString()).apply()
            refreshLists()
        }
    }

    private fun saveTabs() {
        val array = JSONArray()
        for (wv in _webViews) {
            val url = wv.url ?: "about:blank"
            if (url == "about:blank") continue
            val metadata = wv.tag as? TabMetadata
            val obj = JSONObject().apply {
                put("url", url)
                put("title", wv.title ?: metadata?.defaultTitle ?: url)
            }
            array.put(obj)
        }
        prefs.edit().putString("saved_tabs", array.toString()).apply()
        refreshLists()
    }

    inner class WebAppInterface {
        @JavascriptInterface
        fun onElementClicked(selector: String) {
            if (_isRecordingAutoplay.value) {
                recordedSelectors.add(selector)
            }
        }
    }
}