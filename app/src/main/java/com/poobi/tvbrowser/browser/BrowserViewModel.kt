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

open class WebAppView(context: Context) : WebView(context) {

    var isCustomInputBridgeActive: Boolean = false
    private val inputBuffer = StringBuilder()

    fun syncInputText(text: String) {
        inputBuffer.setLength(0)
        inputBuffer.append(text)
    }

    override fun onCheckIsTextEditor(): Boolean {
        if (isCustomInputBridgeActive) return true
        return super.onCheckIsTextEditor()
    }

    override fun onCreateInputConnection(outAttrs: android.view.inputmethod.EditorInfo?): android.view.inputmethod.InputConnection? {
        val superIc = super.onCreateInputConnection(outAttrs)

        if (superIc != null && outAttrs != null && outAttrs.inputType != android.view.inputmethod.EditorInfo.TYPE_NULL && !isCustomInputBridgeActive) {
            return superIc
        }

        if (isCustomInputBridgeActive) {
            outAttrs?.let {
                it.inputType = android.text.InputType.TYPE_CLASS_TEXT or 
                               android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                               android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                it.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
                it.initialSelStart = inputBuffer.length
                it.initialSelEnd = inputBuffer.length
            }

            return object : android.view.inputmethod.BaseInputConnection(this, false) {

                private var currentComposingText = ""

                private fun forwardInsert(str: String) {
                    if (str.isEmpty()) return
                    inputBuffer.append(str)
                    val quoted = org.json.JSONObject.quote(str)
                    post { evaluateJavascript("if (window.__poobiInputBridge) window.__poobiInputBridge.insert($quoted);", null) }
                }

                private fun forwardDelete() {
                    if (inputBuffer.isNotEmpty()) {
                        inputBuffer.deleteCharAt(inputBuffer.length - 1)
                    }
                    post { evaluateJavascript("if (window.__poobiInputBridge) window.__poobiInputBridge.delete();", null) }
                }

                private fun forwardEnter() {
                    currentComposingText = ""
                    inputBuffer.append("\n")
                    post { evaluateJavascript("if (window.__poobiInputBridge) window.__poobiInputBridge.enter();", null) }
                }

                override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
                    val newStr = text?.toString() ?: ""
                    val oldStr = currentComposingText

                    if (newStr == oldStr) return true

                    if (newStr.startsWith(oldStr)) {
                        forwardInsert(newStr.substring(oldStr.length))
                    } else if (oldStr.startsWith(newStr)) {
                        val deleteCount = oldStr.length - newStr.length
                        for (i in 0 until deleteCount) forwardDelete()
                    } else {
                        for (i in 0 until oldStr.length) forwardDelete()
                        forwardInsert(newStr)
                    }

                    currentComposingText = newStr
                    return true
                }

                override fun finishComposingText(): Boolean {
                    currentComposingText = ""
                    return true
                }

                override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                    val str = text?.toString() ?: ""
                    val oldStr = currentComposingText

                    if (str == oldStr) {
                        currentComposingText = ""
                        return true
                    }

                    if (str.startsWith(oldStr) && oldStr.isNotEmpty()) {
                        forwardInsert(str.substring(oldStr.length))
                    } else {
                        for (i in 0 until oldStr.length) forwardDelete()
                        forwardInsert(str)
                    }

                    currentComposingText = ""
                    return true
                }

                override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                    currentComposingText = ""
                    if (beforeLength > 0) {
                        for (i in 0 until beforeLength) forwardDelete()
                    }
                    return true
                }

                override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
                    return deleteSurroundingText(beforeLength, afterLength)
                }

                override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence {
                    val len = inputBuffer.length
                    if (len == 0) return ""
                    val start = (len - n).coerceAtLeast(0)
                    return inputBuffer.substring(start, len)
                }

                override fun getTextAfterCursor(n: Int, flags: Int): CharSequence = ""

                override fun getSelectedText(flags: Int): CharSequence = ""

                override fun sendKeyEvent(event: android.view.KeyEvent?): Boolean {
                    if (event != null && event.action == android.view.KeyEvent.ACTION_DOWN) {
                        if (event.keyCode == android.view.KeyEvent.KEYCODE_DEL) {
                            currentComposingText = ""
                            forwardDelete()
                            return true
                        } else if (event.keyCode == android.view.KeyEvent.KEYCODE_ENTER || event.keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER) {
                            forwardEnter()
                            return true
                        } else {
                            val unicode = event.getUnicodeChar(event.metaState)
                            if (unicode != 0 && !Character.isISOControl(unicode)) {
                                currentComposingText = ""
                                forwardInsert(unicode.toChar().toString())
                                return true
                            }
                        }
                    }
                    return super.sendKeyEvent(event)
                }
            }
        }

        return superIc
    }

    override fun clearFocus() {
        isCustomInputBridgeActive = false
        inputBuffer.setLength(0)
        super.clearFocus()
    }
}

data class TabMetadata(
    val defaultTitle: String?,
    val defaultUrl: String? = null, 
    val streamItemJson: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    var isIdle: Boolean = false,
    var isDesktopMode: Boolean = false,
    var zoomLevel: Int = 100
)

sealed class BrowserDialogState {
    data class Download(val url: String, val fileName: String, val sizeMb: Float) : BrowserDialogState()
    data class PopupBlocked(val resultMsg: Message) : BrowserDialogState()
    data class StreamPicker(val streams: List<String>, val streamInfos: List<String>) : BrowserDialogState()
    data class AdvancedBlockElement(val data: JSONObject) : BrowserDialogState()
    data class SaveBlockRule(val url: String, val selector: String) : BrowserDialogState()
    data class SaveAutoplayProfile(val url: String, val selectors: List<String>) : BrowserDialogState()
    data class Error(val message: String) : BrowserDialogState()
    data class ContextMenu(val url: String, val x: Float, val y: Float, val selectedText: String = "", val isEditable: Boolean = false, val hasSelection: Boolean = false) : BrowserDialogState()
    data class PlaybackHijackAsk(val url: String, val title: String?, val alternativeUrls: List<String>?, val alternativeNames: List<String>?, val view: View?, val callback: WebChromeClient.CustomViewCallback?) : BrowserDialogState()
}

class BrowserViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    val prefs: SharedPreferences = appContext.getSharedPreferences("BrowserSettings", Context.MODE_PRIVATE)

    private val _webViews = mutableStateListOf<WebView>()
    private val webViewHosts = ConcurrentHashMap<WebView, String>()
    private val webViewUserAgents = ConcurrentHashMap<WebView, String>()

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

    private val _isDesktopMode = MutableStateFlow(false)
    val isDesktopMode: StateFlow<Boolean> = _isDesktopMode.asStateFlow()

    private val _currentZoomLevel = MutableStateFlow(100)
    val currentZoomLevel: StateFlow<Int> = _currentZoomLevel.asStateFlow()

    private var pendingCustomView: View? = null
    var customViewCallback: WebChromeClient.CustomViewCallback? = null
    var onPlayNativeVideo: ((videoUrl: String, title: String?, alternativeUrls: List<String>?, alternativeNames: List<String>?) -> Unit)? = null
    var onEditorAction: ((Int) -> Unit)? = null
    var onShowNativeTextMenu: ((screenX: Float, screenY: Float, isEditable: Boolean, hasSelection: Boolean) -> Unit)? = null

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

    private val _browserPredictedQuery = MutableStateFlow("")
    val browserPredictedQuery: StateFlow<String> = _browserPredictedQuery.asStateFlow()

    private var browserSuggestJob: kotlinx.coroutines.Job? = null

    private val _hasIdleTabs = MutableStateFlow(false)
    val hasIdleTabs: StateFlow<Boolean> = _hasIdleTabs.asStateFlow()

    fun updateBrowserSuggestions(query: String) {
        browserSuggestJob?.cancel()
        val clean = query.trim()
        if (clean.length < 2) {
            _browserPredictedQuery.value = ""
            return
        }

        browserSuggestJob = viewModelScope.launch(Dispatchers.IO) {
            val history = _historyList.value
            val favorites = _favoritesList.value
            var localMatch = ""

            for (i in 0 until favorites.length()) {
                val item = favorites.optJSONObject(i) ?: continue
                val url = item.optString("url").removePrefix("https://").removePrefix("http://").removePrefix("www.")
                val title = item.optString("title")
                if (url.startsWith(clean, ignoreCase = true) && !url.equals(clean, ignoreCase = true)) {
                    localMatch = url
                    break
                }
                if (title.startsWith(clean, ignoreCase = true) && !title.equals(clean, ignoreCase = true)) {
                    localMatch = title
                    break
                }
            }

            if (localMatch.isEmpty()) {
                for (i in 0 until history.length()) {
                    val item = history.optJSONObject(i) ?: continue
                    val url = item.optString("url").removePrefix("https://").removePrefix("http://").removePrefix("www.")
                    val title = item.optString("title")
                    if (url.startsWith(clean, ignoreCase = true) && !url.equals(clean, ignoreCase = true)) {
                        localMatch = url
                        break
                    }
                    if (title.startsWith(clean, ignoreCase = true) && !title.equals(clean, ignoreCase = true)) {
                        localMatch = title
                        break
                    }
                }
            }

            if (localMatch.isNotEmpty()) {
                withContext(Dispatchers.Main) {
                    _browserPredictedQuery.value = localMatch
                }
                return@launch
            }

            kotlinx.coroutines.delay(150)
            if (browserSuggestJob?.isActive != true) return@launch

            try {
                val encoded = java.net.URLEncoder.encode(clean, "UTF-8")
                val url = URL("https://suggestqueries.google.com/complete/search?client=firefox&q=$encoded")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                if (conn.responseCode == 200) {
                    val response = conn.inputStream.bufferedReader().use { it.readText() }
                    val jsonArray = JSONArray(response)
                    if (jsonArray.length() > 1) {
                        val suggestions = jsonArray.getJSONArray(1)
                        for (i in 0 until suggestions.length()) {
                            val sug = suggestions.getString(i)
                            if (sug.startsWith(clean, ignoreCase = true) && !sug.equals(clean, ignoreCase = true)) {
                                withContext(Dispatchers.Main) {
                                    _browserPredictedQuery.value = sug
                                }
                                return@launch
                            }
                        }
                    }
                }
            } catch (_: Exception) {}

            withContext(Dispatchers.Main) {
                _browserPredictedQuery.value = ""
            }
        }
    }

    fun clearBrowserSuggestions() {
        browserSuggestJob?.cancel()
        _browserPredictedQuery.value = ""
    }

    private var currentHost = ""
    var isExtractionActive = false
    val recordedSelectors = mutableListOf<String>()

    fun reloadPreferences() {
        isLightTheme.value = prefs.getBoolean("light_theme", false)
        silentPopupBlock.value = prefs.getBoolean("silent_popup_block", true)
        extractVideoPref.value = prefs.getInt("extract_video_pref", 0)
        videoTriggerPref.value = prefs.getInt("video_trigger_pref", 1)
        clickjackPref.value = prefs.getBoolean("clickjack_prevention", true)
        navigationModePref.value = prefs.getInt("navigation_mode_pref", 0)
        scrollTopbarEnabled.value = prefs.getBoolean("scroll_topbar_enabled", true)
    }

    companion object {
        const val DESKTOP_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36"

        const val LEXICAL_FIX_SCRIPT = """
            (function() {
                if (window.__lexicalFixInjected) return;
                window.__lexicalFixInjected = true;

                function getEditableRoot(node) {
                    if (!node) return null;
                    try {
                        if (node.nodeType === 1 && (node.getAttribute('role') === 'textbox' || node.isContentEditable || node.hasAttribute('data-lexical-editor'))) {
                            return node;
                        }
                        if (node.parentElement) {
                            return node.parentElement.closest ? node.parentElement.closest('[role="textbox"], [contenteditable="true"], [data-lexical-editor="true"]') : null;
                        }
                    } catch(e) {}
                    return null;
                }

                // Snap selection back into <p> atomically without clearing ranges
                document.addEventListener('selectionchange', function() {
                    var active = document.activeElement;
                    var editor = getEditableRoot(active);
                    if (editor) {
                        var sel = window.getSelection();
                        if (sel && sel.anchorNode === document.body) {
                            var p = editor.querySelector('p') || editor;
                            try {
                                sel.setBaseAndExtent(p, 0, p, 0);
                            } catch(e) {}
                        }
                    }
                }, true);

                // Arm Lexical focus on touch/pointer down
                document.addEventListener('pointerdown', function(e) {
                    var ed = getEditableRoot(e.target);
                    if (ed && ed.__lexicalEditor) {
                        try {
                            ed.__lexicalEditor.focus();
                        } catch(err) {}
                    }
                }, true);
            })();
        """

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
        seedDefaultAutoplayProfiles()
    }

    private fun seedDefaultAutoplayProfiles() {
        val seededVersion = prefs.getInt("seeded_autoplay_version", 0)
        if (seededVersion < 2) {
            val profilesJson = prefs.getString("autoplay_profiles", "[]") ?: "[]"
            val array = JSONArray(profilesJson)

            var hasFbProfile = false
            var hasGenericAutoplay = false

            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val id = obj.optString("id")
                if (id == "default_facebook_desktop_comments") hasFbProfile = true
                if (id == "default_generic_video_autoplay") hasGenericAutoplay = true
            }

            if (!hasFbProfile) {
                val fbProfileScript = """
                    (function() {
                        if (window.__poobiLexicalLoaded) return;
                        window.__poobiLexicalLoaded = true;

                        function findEditor(target) {
                            var curr = target;
                            while (curr && curr !== document.body) {
                                if (curr.__lexicalEditor) return curr;
                                curr = curr.parentElement;
                            }
                            if (target && target.parentElement) {
                                var ed = target.parentElement.querySelector('[data-lexical-editor="true"], [role="textbox"][contenteditable="true"]');
                                if (ed && ed.__lexicalEditor) return ed;
                            }
                            return null;
                        }

                        window.__poobiInputBridge = {
                            insert: function(str) {
                                var ed = window.__activeLexicalEditor || document.querySelector('.xzsf02u[role="textbox"]') || document.querySelector('[role="textbox"][contenteditable="true"]');
                                if (!ed || !ed.__lexicalEditor) return;
                                var editor = ed.__lexicalEditor;
                                var targetCmd = null;
                                editor._commands.forEach(function(val, key) {
                                    if (key && key.type === 'CONTROLLED_TEXT_INSERTION_COMMAND') targetCmd = key;
                                });
                                if (targetCmd) editor.dispatchCommand(targetCmd, str);
                            },
                            delete: function() {
                                var ed = window.__activeLexicalEditor || document.querySelector('.xzsf02u[role="textbox"]') || document.querySelector('[role="textbox"][contenteditable="true"]');
                                if (!ed) return;
                                ed.dispatchEvent(new InputEvent("beforeinput", {
                                    bubbles: true,
                                    cancelable: true,
                                    inputType: "deleteContentBackward"
                                }));
                            },
                            enter: function() {
                                var ed = window.__activeLexicalEditor || document.querySelector('.xzsf02u[role="textbox"]') || document.querySelector('[role="textbox"][contenteditable="true"]');
                                if (!ed || !ed.__lexicalEditor) return;
                                var editor = ed.__lexicalEditor;
                                var enterCmd = null;
                                editor._commands.forEach(function(val, key) {
                                    if (key && (key.type === 'KEY_ENTER_COMMAND' || key.type === 'INSERT_PARAGRAPH_COMMAND')) enterCmd = key;
                                });
                                if (enterCmd) editor.dispatchCommand(enterCmd, null);
                            }
                        };

                        document.addEventListener('selectionchange', function() {
                            var active = document.activeElement;
                            var ed = findEditor(active);
                            if (ed) {
                                var sel = window.getSelection();
                                if (sel && sel.anchorNode === document.body) {
                                    var p = ed.querySelector('p') || ed;
                                    try { sel.setBaseAndExtent(p, 0, p, 0); } catch(e) {}
                                }
                            }
                        }, true);

                        document.addEventListener('click', function(e) {
                            var ed = findEditor(e.target);
                            if (ed && ed.__lexicalEditor) {
                                window.__activeLexicalEditor = ed;
                                try {
                                    ed.__lexicalEditor.focus();
                                    var text = (ed.innerText || "").replace(/\n$/, "");
                                    if (window.AndroidAutoplay && window.AndroidAutoplay.setRichEditorActive) {
                                        window.AndroidAutoplay.setRichEditorActive(true, text);
                                    }
                                } catch(err) {}
                            }
                        }, true);
                    })();
                """.trimIndent()

                array.put(JSONObject().apply {
                    put("id", "default_facebook_desktop_comments")
                    put("name", "Facebook Desktop Comment Box Fix")
                    put("enabled", true)
                    put("urlPatterns", JSONArray().put("*facebook.com*"))
                    put("script", fbProfileScript)
                    put("use_script", true)
                    put("selectors", JSONArray())
                })
            }

            if (!hasGenericAutoplay) {
                val genericScript = """
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
                """.trimIndent()

                array.put(JSONObject().apply {
                    put("id", "default_generic_video_autoplay")
                    put("name", "Generic Play Button Clicker")
                    put("enabled", true)
                    put("urlPatterns", JSONArray().put("*"))
                    put("script", genericScript)
                    put("use_script", true)
                    put("selectors", JSONArray())
                })
            }

            prefs.edit().putString("autoplay_profiles", array.toString()).putInt("seeded_autoplay_version", 2).apply()
            refreshLists()
        }
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
    }

    fun getWebViewsList(): List<WebView> = _webViews

    fun createNewTab(
        context: Context, 
        url: String? = null, 
        switchTo: Boolean = true, 
        title: String? = null,
        idle: Boolean = false,
        isDesktop: Boolean = false,
        zoom: Int = 100
    ): WebView {
        val newWebView = WebAppView(context).apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
            visibility = View.INVISIBLE
            tag = TabMetadata(
                defaultTitle = title, 
                defaultUrl = url, 
                isIdle = idle,
                isDesktopMode = isDesktop,
                zoomLevel = zoom
            )
        }
        setupWebView(newWebView)
        _webViews.add(newWebView)

        if (url != null && !idle) {
            AdBlockManager.checkAndPromptInitialSetup(context)
            newWebView.loadUrl(url)
        }
        saveTabs()
        if (switchTo) {
            switchTab(_webViews.size - 1)
            if (url.isNullOrBlank()) {
                _topBarVisible.value = true
            }
        }
        updateIdleTabsState()
        return newWebView
    }

    fun switchTab(index: Int) {
        if (index !in _webViews.indices) {
            Log.e("TVBrowser", "switchTab failure: Index $index out of bounds (Size: ${_webViews.size})")
            return
        }
        
        val oldWebView = currentWebView
        oldWebView?.visibility = View.INVISIBLE
        oldWebView?.onPause()

        _currentTabIndex.value = index
        _isBrowsing.value = true
        val targetWebView = _webViews[index]
        targetWebView.visibility = View.VISIBLE
        targetWebView.onResume()
        interceptedMediaUrls.clear()
        interceptedSubtitleUrls.clear()

        val metadata = targetWebView.tag as? TabMetadata
        _isDesktopMode.value = metadata?.isDesktopMode ?: false
        _currentZoomLevel.value = metadata?.zoomLevel ?: 100

        val idleUrl = metadata?.defaultUrl
        if (metadata?.isIdle == true && !idleUrl.isNullOrBlank()) {
            metadata.isIdle = false
            targetWebView.loadUrl(idleUrl)
        } else if ((targetWebView.url.isNullOrBlank() || targetWebView.url == "about:blank") && !idleUrl.isNullOrBlank()) {
            targetWebView.loadUrl(idleUrl)
        }
        updateIdleTabsState()
        
        val url = targetWebView.url ?: idleUrl ?: ""
        _currentUrl.value = url
        targetWebView.requestFocus()
    }

    fun closeTab(index: Int) {
        if (index !in _webViews.indices) {
            Log.e("TVBrowser", "closeTab failure: Index $index out of bounds (Size: ${_webViews.size})")
            return
        }

        val wv = _webViews[index]
        val wasBrowsing = _isBrowsing.value
        val wasActiveTab = (index == _currentTabIndex.value)

        (wv.parent as? android.view.ViewGroup)?.removeView(wv)

        try {
            wv.onPause()
            wv.stopLoading()
            wv.loadUrl("about:blank")
            wv.clearHistory()
        } catch (e: Exception) {
            Log.e("TVBrowser", "Error clearing WebView state", e)
        }

        webViewHosts.remove(wv)
        webViewUserAgents.remove(wv)
        _webViews.removeAt(index)
        wv.destroy()
        saveTabs()
        updateIdleTabsState()

        if (_webViews.isEmpty()) {
            showHomeScreen()
        } else {
            if (wasBrowsing) {
                if (wasActiveTab) {
                    val newIndex = if (index >= _webViews.size) _webViews.size - 1 else index
                    switchTab(newIndex)
                } else {
                    if (_currentTabIndex.value > index) {
                        _currentTabIndex.value -= 1
                    }
                }
            } else {
                if (_currentTabIndex.value >= _webViews.size) {
                    _currentTabIndex.value = _webViews.size - 1
                } else if (_currentTabIndex.value < 0) {
                    _currentTabIndex.value = 0
                }
            }
        }
    }

    fun loadUrlAndBrowse(
        context: Context, 
        inputUrl: String, 
        newTab: Boolean = false,
        streamItemJson: String? = null,
        season: Int? = null,
        episode: Int? = null,
        isDesktop: Boolean = false
    ) {
        AdBlockManager.checkAndPromptInitialSetup(context)
        var url = inputUrl.trim()
        if (url.isNotEmpty()) {
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = if (url.contains(".") && !url.contains(" ")) "https://$url" else "https://www.google.com/search?q=$url"
            }
            if (newTab || _webViews.isEmpty()) {
                val newWv = createNewTab(context, url, switchTo = true, isDesktop = isDesktop)
                newWv.tag = TabMetadata(
                    defaultTitle = newWv.title,
                    defaultUrl = url,
                    streamItemJson = streamItemJson,
                    season = season,
                    episode = episode,
                    isIdle = false,
                    isDesktopMode = isDesktop
                )
            } else {
                if (_currentTabIndex.value == -1) {
                    _currentTabIndex.value = 0
                }
                val prevMeta = currentWebView?.tag as? TabMetadata
                val finalDesktop = if (isDesktop) true else (prevMeta?.isDesktopMode ?: false)
                val existingZoom = prevMeta?.zoomLevel ?: 100

                currentWebView?.let { wv ->
                    wv.tag = TabMetadata(
                        defaultTitle = wv.title,
                        defaultUrl = url,
                        streamItemJson = streamItemJson,
                        season = season,
                        episode = episode,
                        isIdle = false,
                        isDesktopMode = finalDesktop,
                        zoomLevel = existingZoom
                    )
                    applyDesktopMode(wv, finalDesktop)
                    _isDesktopMode.value = finalDesktop
                    wv.loadUrl(url)
                    wv.visibility = View.VISIBLE
                }
                _isBrowsing.value = true
            }
            _topBarVisible.value = false
        }
    }

    fun restoreAllTabs(context: Context, idle: Boolean? = null) {
        val pref = prefs.getInt("restore_tabs_pref", 0)
        val shouldBeIdle = idle ?: (pref == 2)
        val savedTabs = prefs.getString("saved_tabs", "[]") ?: "[]"

        try {
            val array = JSONArray(savedTabs)
            if (array.length() == 0) return

            if (_webViews.isNotEmpty()) {
                if (!shouldBeIdle) {
                    loadAllIdleTabs()
                }
                return
            }

            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val url = obj.optString("url")
                if (url.isNotEmpty()) {
                    val title = obj.optString("title")
                    val isDesktop = obj.optBoolean("is_desktop", false)
                    val zoom = obj.optInt("zoom", 100)
                    createNewTab(
                        context = context, 
                        url = url, 
                        switchTo = false, 
                        title = title, 
                        idle = shouldBeIdle,
                        isDesktop = isDesktop,
                        zoom = zoom
                    )
                }
            }
            if (_currentTabIndex.value == -1 && _webViews.isNotEmpty()) {
                _currentTabIndex.value = 0
            }
        } catch (e: Exception) {
            Log.e("TVBrowser", "Error restoring tabs: ${e.message}")
        }
        updateIdleTabsState()
        refreshLists()
    }

    fun loadAllIdleTabs() {
        for (wv in _webViews) {
            val metadata = wv.tag as? TabMetadata
            if (metadata?.isIdle == true && !metadata.defaultUrl.isNullOrBlank()) {
                metadata.isIdle = false
                wv.loadUrl(metadata.defaultUrl)
            }
        }
        updateIdleTabsState()
    }

    fun updateIdleTabsState() {
        _hasIdleTabs.value = _webViews.any { (it.tag as? TabMetadata)?.isIdle == true }
    }

    fun showHomeScreen() {
        _webViews.forEach { wv ->
            wv.visibility = View.INVISIBLE
            wv.onPause()
        }
        _isBrowsing.value = false
        _topBarVisible.value = false
        _currentUrl.value = ""
        _currentTabIndex.value = -1
        _isDesktopMode.value = false
        _currentZoomLevel.value = 100
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
            val isDesktop = (wv.tag as? TabMetadata)?.isDesktopMode ?: false
            saveToList("favorites", url, wv.title ?: url, "${url.hashCode()}.png", isDesktop = isDesktop)
        }
    }

    fun toggleDesktopMode(context: Context? = null) {
        val wv = currentWebView ?: return
        val metadata = wv.tag as? TabMetadata ?: return
        val newMode = !metadata.isDesktopMode
        metadata.isDesktopMode = newMode
        _isDesktopMode.value = newMode
        
        applyDesktopMode(wv, newMode)
        saveTabs()
        wv.reload()

        val msg = if (newMode) "Desktop mode enabled" else "Mobile mode enabled"
        Toast.makeText(context ?: appContext, msg, Toast.LENGTH_SHORT).show()
    }

    private fun applyDesktopMode(wv: WebView, isDesktop: Boolean) {
        val defaultUA = prefs.getString("user_agent", WebSettings.getDefaultUserAgent(appContext)) ?: WebSettings.getDefaultUserAgent(appContext)
        val selectedUA = if (isDesktop) DESKTOP_USER_AGENT else defaultUA
        webViewUserAgents[wv] = selectedUA
        wv.settings.userAgentString = selectedUA
        wv.settings.useWideViewPort = isDesktop
        wv.settings.loadWithOverviewMode = isDesktop
    }

    fun zoomIn(context: Context? = null) {
        val current = _currentZoomLevel.value
        val next = (current + 10).coerceAtMost(300)
        setZoomLevel(next, context)
    }

    fun zoomOut(context: Context? = null) {
        val current = _currentZoomLevel.value
        val next = (current - 10).coerceAtLeast(50)
        setZoomLevel(next, context)
    }

    fun resetZoom(context: Context? = null) {
        setZoomLevel(100, context)
    }

    fun setZoomLevel(percent: Int, context: Context? = null) {
        val clamped = percent.coerceIn(50, 300)
        _currentZoomLevel.value = clamped
        val wv = currentWebView ?: return
        val metadata = wv.tag as? TabMetadata
        metadata?.zoomLevel = clamped
        wv.settings.textZoom = clamped
        saveTabs()
        Toast.makeText(context ?: appContext, "Zoom: $clamped%", Toast.LENGTH_SHORT).show()
    }

    fun executeCopy(selectedText: String, context: Context) {
        if (selectedText.isNotEmpty()) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
            clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("Copied Text", selectedText))
            Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
        }
    }

    fun triggerContextMenuAtCursor(cursorX: Float, cursorY: Float) {
        val wv = currentWebView ?: return
        val density = appContext.resources.displayMetrics.density
        val location = IntArray(2)
        wv.getLocationOnScreen(location)
        val wvX = (cursorX - location[0]).coerceAtLeast(0f)
        val wvY = (cursorY - location[1]).coerceAtLeast(0f)
        val x = (wvX / density).toInt()
        val y = (wvY / density).toInt()

        wv.evaluateJavascript("""
            (function() {
                var el = document.elementFromPoint($x, $y);
                var link = '';
                var temp = el;
                while(temp && temp.tagName !== 'A') temp = temp.parentElement;
                if(temp) link = temp.href;

                function checkEditable(node) {
                    if (!node) return null;
                    var tag = node.tagName ? node.tagName.toLowerCase() : '';
                    if (tag === 'textarea') return node;
                    if (tag === 'input') {
                        var type = (node.type || 'text').toLowerCase();
                        var nonText = ['button', 'checkbox', 'file', 'hidden', 'image', 'radio', 'reset', 'submit', 'range', 'color'];
                        if (nonText.indexOf(type) === -1) return node;
                    }
                    if (node.isContentEditable) return node;
                    return null;
                }

                var targetInput = checkEditable(el);
                if (!targetInput && el) {
                    var p = el.parentElement;
                    while (p && p !== document.body) {
                        targetInput = checkEditable(p);
                        if (targetInput) break;
                        p = p.parentElement;
                    }
                }
                if (!targetInput && el) targetInput = checkEditable(el.querySelector('input, textarea, [contenteditable="true"]'));
                if (!targetInput && el && el.parentElement) targetInput = checkEditable(el.parentElement.querySelector('input, textarea, [contenteditable="true"]'));
                if (!targetInput) targetInput = checkEditable(document.activeElement);

                var isEditable = targetInput !== null;

                var selText = '';
                var sStart = -1;
                var sEnd = -1;

                if (targetInput && typeof targetInput.selectionStart === 'number' && typeof targetInput.selectionEnd === 'number' && targetInput.selectionStart !== targetInput.selectionEnd) {
                    sStart = targetInput.selectionStart;
                    sEnd = targetInput.selectionEnd;
                    selText = targetInput.value.substring(sStart, sEnd);
                }

                window.__savedRange = null;
                var sel = window.getSelection();
                if (sel && sel.rangeCount > 0 && !sel.isCollapsed) {
                    try {
                        window.__savedRange = sel.getRangeAt(0).cloneRange();
                        if (!selText) {
                            selText = sel.toString();
                        }
                    } catch(e) {}
                }

                var hasSelection = selText.trim().length > 0;

                window.__targetInput = targetInput;
                window.__selStart = sStart;
                window.__selEnd = sEnd;

                return {
                    link: link || '',
                    selectedText: selText,
                    isEditable: isEditable,
                    hasSelection: hasSelection
                };
            })()
        """.trimIndent()) { result ->
            if (result != null && result != "null") {
                try {
                    val json = JSONObject(result)
                    val link = json.optString("link")
                    val selectedText = json.optString("selectedText")
                    val isEditable = json.optBoolean("isEditable", false)
                    val hasSelection = json.optBoolean("hasSelection", false)

                    _currentDialog.value = BrowserDialogState.ContextMenu(
                        url = link,
                        x = cursorX,
                        y = cursorY,
                        selectedText = selectedText,
                        isEditable = isEditable,
                        hasSelection = hasSelection
                    )
                } catch (e: Exception) { }
            }
        }
    }

    fun executeCut(selectedText: String, context: Context) {
        val wv = currentWebView ?: return

        if (selectedText.isNotEmpty()) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
            clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("Cut Text", selectedText))
            Toast.makeText(context, "Cut to clipboard", Toast.LENGTH_SHORT).show()
        }

        val quotedText = JSONObject.quote(selectedText)
        val script = """
            (function() {
                var text = $quotedText;
                var el = window.__targetInput || document.activeElement;

                if (el && (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA')) {
                    el.focus();
                    var val = el.value || '';
                    var idx = text ? val.indexOf(text) : -1;
                    var s = window.__selStart;
                    var e = window.__selEnd;

                    if (typeof el.setRangeText === 'function') {
                        if (typeof s === 'number' && typeof e === 'number' && e > s && val.substring(s, e) === text) {
                            el.setRangeText('', s, e, 'end');
                        } else if (idx !== -1) {
                            el.setRangeText('', idx, idx + text.length, 'end');
                        } else if (typeof el.selectionStart === 'number' && typeof el.selectionEnd === 'number' && el.selectionEnd > el.selectionStart) {
                            el.setRangeText('', el.selectionStart, el.selectionEnd, 'end');
                        }
                    } else if (idx !== -1) {
                        el.value = val.substring(0, idx) + val.substring(idx + text.length);
                        el.setSelectionRange(idx, idx);
                    }
                    el.dispatchEvent(new Event('input', { bubbles: true }));
                    return 'cut_input';
                }

                if (window.__savedRange) {
                    try {
                        window.__savedRange.deleteContents();
                        var sel = window.getSelection();
                        if (sel) {
                            sel.removeAllRanges();
                            sel.addRange(window.__savedRange);
                        }
                        if (el) {
                            el.dispatchEvent(new Event('input', { bubbles: true }));
                        }
                        return 'cut_range';
                    } catch(e) {}
                }

                return 'cut_none';
            })();
        """.trimIndent()

        wv.evaluateJavascript(script, null)
    }

    fun openLinkAtInNewTab(screenX: Float, screenY: Float) {
        val wv = currentWebView ?: return
        val density = appContext.resources.displayMetrics.density
        val location = IntArray(2)
        wv.getLocationOnScreen(location)
        val wvX = (screenX - location[0]).coerceAtLeast(0f)
        val wvY = (screenY - location[1]).coerceAtLeast(0f)
        val cssX = (wvX / density).toInt()
        val cssY = (wvY / density).toInt()

        wv.evaluateJavascript("""
            (function() {
                var el = document.elementFromPoint($cssX, $cssY);
                var temp = el;
                while (temp && temp.tagName !== 'A') {
                    temp = temp.parentElement;
                }
                return temp ? temp.href : '';
            })()
        """.trimIndent()) { result ->
            val rawLink = result?.replace("\"", "")?.trim() ?: ""
            if (rawLink.startsWith("http://") || rawLink.startsWith("https://")) {
                loadUrlAndBrowse(appContext, rawLink, newTab = true)
                Toast.makeText(appContext, "Opened link in new tab", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun downloadAndCacheHlsPlaylist(
        playlistUrl: String, 
        headers: Map<String, String>
    ): Triple<String, ByteArray, Map<String, String>>? {
        try {
            val connection = URL(playlistUrl).openConnection() as HttpURLConnection
            headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
            connection.connectTimeout = 6000
            connection.readTimeout = 6000
            
            val responseCode = connection.responseCode
            
            if (responseCode in 200..299) {
                val content = connection.inputStream.bufferedReader().use { it.readText() }

                if (!content.trimStart().startsWith("#EXTM3U")) {
                    return null
                }
                
                val isSubtitlePlaylist = content.contains(".vtt", ignoreCase = true) || 
                                         content.contains(".srt", ignoreCase = true) || 
                                         content.contains(".webvtt", ignoreCase = true) ||
                                         content.contains(".ass", ignoreCase = true)
                
                if (isSubtitlePlaylist) {
                    interceptedSubtitleUrls[playlistUrl] = headers
                    return null
                }

                val lines = content.lines()
                val resolvedLines = mutableListOf<String>()
                for (line in lines) {
                    val trimmed = line.trim()
                    if (trimmed.startsWith("#EXT-X-I-FRAME-STREAM-INF")) {
                        continue
                    }
                    if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                        if (!trimmed.startsWith("http") && !trimmed.startsWith("data:")) {
                            try {
                                resolvedLines.add(URL(URL(playlistUrl), trimmed).toString())
                            } catch (e: Exception) {
                                resolvedLines.add(trimmed)
                            }
                        } else {
                            resolvedLines.add(trimmed)
                        }
                    } else {
                        resolvedLines.add(line)
                    }
                }
                
                val resolvedContent = resolvedLines.joinToString("\n")
                val uniqueFilename = "cached_playlist_${playlistUrl.hashCode()}.m3u8"
                val localFile = File(appContext.cacheDir, uniqueFilename)
                localFile.writeText(resolvedContent)
                val responseHeaders = mutableMapOf<String, String>()
                connection.headerFields.forEach { (key, values) ->
                    if (key != null && values.isNotEmpty()) {
                        responseHeaders[key] = values.joinToString(", ")
                    }
                }
                
                return Triple(
                    Uri.fromFile(localFile).toString(), 
                    resolvedContent.toByteArray(Charsets.UTF_8), 
                    responseHeaders
                )
            } else {
                Log.w("BrowserViewModel", "Failed synchronous m3u8 download. Response code: $responseCode")
            }
        } catch (e: Exception) {
            Log.e("BrowserViewModel", "Exception during synchronous m3u8 download", e)
        }
        return null
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView(wv: WebView) {
        wv.isFocusable = true
        wv.isFocusableInTouchMode = true
        val defaultUA = WebSettings.getDefaultUserAgent(appContext)
        val customUserAgent = prefs.getString("user_agent", defaultUA) ?: defaultUA

        val metadata = wv.tag as? TabMetadata
        val isDesktop = metadata?.isDesktopMode ?: false
        val zoom = metadata?.zoomLevel ?: 100
        val selectedUA = if (isDesktop) DESKTOP_USER_AGENT else customUserAgent
        
        webViewUserAgents[wv] = selectedUA
        
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            userAgentString = selectedUA
            useWideViewPort = isDesktop
            loadWithOverviewMode = isDesktop
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            textZoom = zoom
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                isAlgorithmicDarkeningAllowed = !isLightTheme.value
            }
        }

        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)

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

        val scrollGuardScript = """
            (function() {
                if (window.__poobiScrollGuardInjected) return;
                window.__poobiScrollGuardInjected = true;
                const origScrollTo = window.scrollTo;
                const origScroll = window.scroll;

                function isTopScroll(args) {
                    if (!args || args.length === 0) return false;
                    if (typeof args[0] === 'object' && args[0] !== null) {
                        return args[0].top === 0 && (args[0].left === undefined || args[0].left === 0);
                    }
                    return args[0] === 0 && (args[1] === undefined || args[1] === 0);
                }

                window.scrollTo = function() {
                    const currentScroll = window.scrollY || document.documentElement.scrollTop || document.body.scrollTop || 0;
                    if (currentScroll > 100 && isTopScroll(arguments)) {
                        return;
                    }
                    return origScrollTo.apply(this, arguments);
                };

                window.scroll = function() {
                    const currentScroll = window.scrollY || document.documentElement.scrollTop || document.body.scrollTop || 0;
                    if (currentScroll > 100 && isTopScroll(arguments)) {
                        return;
                    }
                    return origScroll.apply(this, arguments);
                };
            })();
        """.trimIndent()

        wv.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                interceptedMediaUrls.clear()
                interceptedSubtitleUrls.clear()
                view?.evaluateJavascript(LEXICAL_FIX_SCRIPT, null)
                view?.evaluateJavascript(scrollGuardScript, null)
                if (url != null && view != null) {
                    val host = Uri.parse(url).host ?: ""
                    webViewHosts[view] = host
                    if (view == currentWebView) currentHost = host
                    view.evaluateJavascript(AdBlockManager.getSurrogateScript(), null)
                    AdBlockManager.injectCosmeticCss(view, host)
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (url == null || url == "about:blank" || view == null) return

                view.evaluateJavascript(LEXICAL_FIX_SCRIPT, null)
                view.evaluateJavascript(scrollGuardScript, null)

                if (view == currentWebView) {
                    currentHost = Uri.parse(url).host ?: ""
                    _currentUrl.value = url
                }

                injectClickjackPrevention(view)
                applyBlockedElements(view)
                val pageHost = Uri.parse(url).host ?: ""
                AdBlockManager.injectCosmeticCss(view, pageHost)
                saveTabs()

                if (videoTriggerPref.value == 0 && _isBrowsing.value && view == currentWebView) {
                    triggerAutoPlayClicks(view)
                }

                if (!_isBrowsing.value || view != currentWebView) {
                    view.evaluateJavascript("(function() { var v = document.querySelectorAll('video, audio'); for(var i=0; i<v.length; i++) { v[i].pause(); } })();", null)
                }

                view.evaluateJavascript("(function() { return document.documentElement.innerText; })();") { content ->
                    if (content != null && content.contains("#EXTM3U")) {
                        val headers = mutableMapOf<String, String>()
                        CookieManager.getInstance().getCookie(url ?: "")?.let { headers["Cookie"] = it }
                        headers["User-Agent"] = webViewUserAgents[view] ?: view.settings.userAgentString
                        if (url != null) {
                            interceptedMediaUrls[url] = headers
                        }
                    }
                }

                view.postDelayed({ 
                    saveSnapshot(
                        url = url, 
                        title = view.title?.takeIf { it.isNotBlank() } ?: "Website", 
                        wv = view
                    ) 
                }, 2500)
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val requestedUrl = request?.url?.toString()
                if (requestedUrl != null) {
                    if (requestedUrl.contains("disable-devtool") || requestedUrl.contains("devtools-detector")) {
                        return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream("".toByteArray()))
                    }

                    val urlPath = requestedUrl.substringBefore('?').lowercase()
                    val urlLower = requestedUrl.lowercase()

                    val isVideo = urlPath.contains("m3u8") || 
                                 urlPath.contains("mpd") || 
                                 urlPath.contains("/hls/") || 
                                 urlPath.contains(".mp4") || 
                                 urlPath.contains(".mkv") ||
                                 (urlLower.contains(".m3u8") && !urlPath.endsWith(".gif"))

                    if (isVideo) {
                        val wasEmpty = interceptedMediaUrls.isEmpty()
                        val headers = request.requestHeaders.toMutableMap()
                        
                        CookieManager.getInstance().getCookie(requestedUrl)?.let {
                            headers["Cookie"] = it
                        }

                        val activeUserAgent = (if (view != null) webViewUserAgents[view] else null)
                            ?: prefs.getString("user_agent", DESKTOP_USER_AGENT)
                            ?: DESKTOP_USER_AGENT
                        val viewUrl = _currentUrl.value

                        if (!headers.containsKey("User-Agent")) {
                            headers["User-Agent"] = activeUserAgent
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

                        if (urlLower.contains("m3u8") && !urlLower.contains(".ts")) {
                            val cachedData = downloadAndCacheHlsPlaylist(requestedUrl, headers)
                            if (cachedData != null) {
                                val (localFileUrl, contentBytes, responseHeaders) = cachedData
                                
                                interceptedMediaUrls[localFileUrl] = headers
                                
                                return WebResourceResponse(
                                    "application/x-mpegURL",
                                    "UTF-8",
                                    200,
                                    "OK",
                                    responseHeaders,
                                    ByteArrayInputStream(contentBytes)
                                )
                            } else {
                                return super.shouldInterceptRequest(view, request)
                            }
                        }
                        interceptedMediaUrls[requestedUrl] = headers
                    }
                    if (requestedUrl.contains(".srt") || requestedUrl.contains(".vtt") || requestedUrl.contains(".ass")) {
                        interceptedSubtitleUrls[requestedUrl] = request.requestHeaders
                    }

                    val filterOption = AdBlockUtils.mapRequestToFilterOption(request)
                    val host = getEffectiveHost(view, request)
                    if (AdBlockManager.shouldBlock(requestedUrl, filterOption, host)) {
                        return AdBlockManager.createBlockedResponse(filterOption)
                    }
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                if (url.startsWith("intent://") || url.startsWith("market://")) return true

                val sourceHost = getEffectiveHost(view, request)
                if (AdBlockManager.shouldBlock(url, com.brave.adblock.AdBlockClient.FilterOption.DOCUMENT, sourceHost)) {
                    Log.d("AdBlock", "Blocked navigation to ad URL: $url")
                    return true
                }

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
        if (extractVideoPref.value == 2) {
            if (view != null) {
                _customView.value = view
            }
            return
        }
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

            var finalCandidates = candidates.distinct()

            val hasPlaylist = finalCandidates.any { it.contains(".m3u8", ignoreCase = true) || it.contains(".mpd", ignoreCase = true) || it.contains("cached_playlist") }
            if (hasPlaylist) {
                finalCandidates = finalCandidates.filterNot { 
                    it.contains(".ts", ignoreCase = true) || 
                    it.contains(".m4s", ignoreCase = true) || 
                    it.contains("seg-", ignoreCase = true) || 
                    it.contains("segment", ignoreCase = true)
                }

                val hasLocalCache = finalCandidates.any { it.startsWith("file://") && it.contains("cached_playlist") }
                if (hasLocalCache) {
                    finalCandidates = finalCandidates.filterNot {
                        it.startsWith("http") && (it.contains(".m3u8", ignoreCase = true) || it.contains(".mpd", ignoreCase = true))
                    }
                }
            }

            if (finalCandidates.isNotEmpty()) {
                val pref = extractVideoPref.value
                if (pref == 1 && finalCandidates.size == 1) {
                    playVideoInNativePlayer(finalCandidates[0], wv.title)
                    callback?.onCustomViewHidden()
                } else if (pref == 2) {
                    _customView.value = view
                    isExtractionActive = false
                } else {
                    parseHlsAndShowPicker(finalCandidates, view, callback)
                }
            } else {
                isExtractionActive = false
                if (view != null) {
                    _customView.value = view
                }
            }
        }
    }

    fun handleHijackChoice(
        useNative: Boolean,
        remember: Boolean,
        ask: BrowserDialogState.PlaybackHijackAsk
    ) {
        if (remember) {
            val newPref = if (useNative) 1 else 2
            prefs.edit().putInt("extract_video_pref", newPref).apply()
            extractVideoPref.value = newPref
        }

        if (useNative) {
            playVideoInNativePlayer(
                url = ask.url,
                title = ask.title,
                alternativeUrls = ask.alternativeUrls,
                alternativeNames = ask.alternativeNames
            )
            ask.callback?.onCustomViewHidden()
        } else {
            if (ask.view != null) {
                _customView.value = ask.view
                isExtractionActive = false
            } else {
                resumeTimersOnCurrent()
            }
        }
        dismissDialog()
    }

    private fun parseHlsAndShowPicker(
        streams: List<String>,
        view: View?,
        callback: WebChromeClient.CustomViewCallback?
    ) {
        val streamInfos = mutableListOf<String>()
        val streamUrls = mutableListOf<String>()

        viewModelScope.launch(Dispatchers.IO) {
            for (url in streams) {
                val headers = interceptedMediaUrls[url] ?: emptyMap()
                if (url.contains(".m3u8") || url.contains("/streamsvr/") || url.contains("m3u8-proxy") || url.contains("cached_playlist")) {
                    try {
                        val content = if (url.startsWith("file://")) {
                            val path = Uri.parse(url).path ?: url.removePrefix("file://")
                            File(path).readText()
                        } else {
                            val connection = URL(url).openConnection() as HttpURLConnection
                            headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
                            if (!headers.containsKey("User-Agent")) {
                                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
                            }
                            connection.connectTimeout = 5000
                            connection.readTimeout = 5000
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

            withContext(Dispatchers.Main) {
                if (streamUrls.isNotEmpty()) {
                    isExtractionActive = false
                    val pref = extractVideoPref.value
                    if (pref == 1) {
                        playVideoInNativePlayer(
                            url = streamUrls[0],
                            title = currentWebView?.title,
                            alternativeUrls = streamUrls,
                            alternativeNames = streamInfos
                        )
                        callback?.onCustomViewHidden()
                    } else if (pref == 2) {
                        if (view != null) {
                            _customView.value = view
                            isExtractionActive = false
                        } else {
                            resumeTimersOnCurrent()
                        }
                    } else {
                        _currentDialog.value = BrowserDialogState.PlaybackHijackAsk(
                            url = streamUrls[0],
                            title = currentWebView?.title,
                            alternativeUrls = streamUrls,
                            alternativeNames = streamInfos,
                            view = view,
                            callback = callback
                        )
                    }
                } else {
                    isExtractionActive = false
                    if (view != null) {
                        _customView.value = view
                    }
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
        val wv = currentWebView
        if (wv != null) {
            wv.onPause()
            wv.pauseTimers()
            wv.evaluateJavascript("""
                (function() {
                    var videos = document.getElementsByTagName('video');
                    for (var i = 0; i < videos.length; i++) {
                        try {
                            videos[i].pause();
                            videos[i].src = "";
                            videos[i].load();
                        } catch(e) {}
                    }

                    var iframes = document.querySelectorAll('iframe');
                    for (var j = 0; j < iframes.length; j++) {
                        try {
                            iframes[j].src = "about:blank";
                        } catch(e) {}
                    }
                    return true;
                })();
            """.trimIndent()) {
                onPlayNativeVideo?.invoke(url, title, alternativeUrls, alternativeNames)
            }
        } else {
            onPlayNativeVideo?.invoke(url, title, alternativeUrls, alternativeNames)
        }
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

    fun inspectActiveElement() {
        val wv = currentWebView ?: return
        wv.evaluateJavascript("""
            (function() {
                var el = document.activeElement;
                if (!el || el === document.body || el === document.documentElement) {
                    return null;
                }
                var options = [];
                var tag = el.tagName.toLowerCase();
                if (el.id) options.push({type: 'ID', value: '#' + el.id});
                
                var classes = Array.from(el.classList).filter(function(c) {
                    return !c.startsWith('poobi-') && !c.includes('focused');
                });
                classes.forEach(function(c) {
                    options.push({type: 'Class', value: '.' + c});
                });
                
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

                return {
                    tagName: el.tagName,
                    id: el.id,
                    className: el.className,
                    candidatesCount: 1,
                    candidateIndex: 0,
                    options: options
                };
            })()
        """.trimIndent()) { result ->
            if (result == null || result == "null") {
                _currentDialog.value = BrowserDialogState.Error("No webpage element was highlighted or active.")
                return@evaluateJavascript
            }
            try {
                _currentDialog.value = BrowserDialogState.AdvancedBlockElement(JSONObject(result))
            } catch (e: Exception) {
                _currentDialog.value = BrowserDialogState.Error("Failed to parse the highlighted element's selectors.")
            }
        }
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
        if (wv.width <= 0 || wv.height <= 0) return
        try {
            val thumb = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(thumb)
            canvas.scale(320f / wv.width.toFloat(), 180f / wv.height.toFloat())
            wv.draw(canvas)
            val isDesktop = (wv.tag as? TabMetadata)?.isDesktopMode ?: false
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val filename = "${url.hashCode()}.png"
                    val file = File(appContext.filesDir, filename)
                    FileOutputStream(file).use { thumb.compress(Bitmap.CompressFormat.PNG, 80, it) }
                    thumb.recycle()
                    saveToList("history", url, title, filename, isDesktop = isDesktop)
                } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.e("BrowserViewModel", "Error taking snapshot", e)
        }
    }

    private fun saveToList(key: String, url: String, title: String, thumbFile: String, isDesktop: Boolean = false) {
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
                put("is_desktop", isDesktop)
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
            val metadata = wv.tag as? TabMetadata
            val url = wv.url?.takeIf { it.isNotBlank() && it != "about:blank" } ?: metadata?.defaultUrl ?: "about:blank"
            if (url == "about:blank") continue
            val obj = JSONObject().apply {
                put("url", url)
                put("title", wv.title?.takeIf { it.isNotBlank() } ?: metadata?.defaultTitle ?: url)
                put("is_desktop", metadata?.isDesktopMode ?: false)
                put("zoom", metadata?.zoomLevel ?: 100)
            }
            array.put(obj)
        }
        prefs.edit().putString("saved_tabs", array.toString()).apply()
        refreshLists()
    }

    private fun getEffectiveHost(view: WebView?, request: WebResourceRequest?): String {
        request?.requestHeaders?.let { headers ->
            val referer = headers.entries.find { it.key.equals("referer", ignoreCase = true) }?.value
            if (!referer.isNullOrEmpty()) {
                val refHost = Uri.parse(referer).host
                if (!refHost.isNullOrBlank()) return refHost
            }
            val origin = headers.entries.find { it.key.equals("origin", ignoreCase = true) }?.value
            if (!origin.isNullOrEmpty()) {
                val origHost = Uri.parse(origin).host
                if (!origHost.isNullOrBlank()) return origHost
            }
        }
        val cached = if (view != null) webViewHosts[view] else null
        if (!cached.isNullOrBlank()) return cached

        val currentUrlVal = _currentUrl.value
        if (currentUrlVal.isNotBlank()) {
            val h = Uri.parse(currentUrlVal).host
            if (!h.isNullOrBlank()) return h
        }

        return currentHost.ifEmpty { request?.url?.host ?: "" }
    }

    inner class WebAppInterface {
        @JavascriptInterface
        fun onElementClicked(selector: String) {
            if (_isRecordingAutoplay.value) {
                recordedSelectors.add(selector)
            }
        }

        @JavascriptInterface
        fun setRichEditorActive(active: Boolean, currentText: String) {
            val wv = currentWebView as? WebAppView ?: return
            wv.post {
                wv.isCustomInputBridgeActive = active
                if (active) {
                    wv.syncInputText(currentText)
                    val imm = appContext.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
                    imm?.restartInput(wv)
                    imm?.showSoftInput(wv, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                }
            }
        }
    }
}