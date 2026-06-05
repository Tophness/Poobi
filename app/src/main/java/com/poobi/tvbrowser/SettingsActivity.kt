package com.poobi.tvbrowser

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.chaquo.python.Python
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class SettingsActivity : AppCompatActivity() {

    private var silentPopupBlock = true
    private var extractVideoPref = 0
    private var videoTriggerPref = 1 // 0: Auto, 1: Fullscreen only (Default)
    private var restorePref = 0
    private var historyLimit = 20
    private var isLightTheme = false
    private var embeddedSubsEnabled = true
    private var scrollTopbarEnabled = true
    private var historyIconPref = 0
    private var bookmarkIconPref = 0
    private var clickjackPref = true
    private var navigationModePref = 0 // 0: Cursor, 1: D-pad
    private var autoSubPref = 0 // 0: Ask, 1: Automatic, 2: Never
    private var autoSubCount = 1 // 0 means "All"
    private var autoSubWaitPref = 0 // 0: Dialog, 1: Progressively/Stop on Play
    private var subRetentionDays = 3
    private var exoFallbackPref = 0

    // Streaming Settings
    private var timeoutMode = "Both"
    private var globalTimeout = 30
    private var perSourceTimeout = 15
    private var useWhitelist = false
    private var enabledPacks = mutableListOf<String>()
    private var whitelistedHosts = mutableListOf<String>()
    private var allHostsList = mutableListOf<HostItem>()
    private var filteredHostsList = mutableListOf<HostItem>()
    private lateinit var hostsAdapter: HostAdapter

    data class HostItem(val name: String, val category: String, val isHeader: Boolean = false)

    // Subtitle Settings
    private var subLangs = "English"
    private var subLimit = 20
    private val subServices = mutableMapOf<String, Boolean>()
    private var openSubUser = ""
    private var openSubPass = ""
    private var openSubOrgUser = ""
    private var openSubOrgPass = ""
    private var subdlKey = ""
    private var subsourceKey = ""

    private var activeCategory: Button? = null

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val prefs = getSharedPreferences("BrowserSettings", Context.MODE_PRIVATE)

        val catGeneral = findViewById<Button>(R.id.cat_general)
        val catWeb = findViewById<Button>(R.id.cat_web)
        val catPlayer = findViewById<Button>(R.id.cat_player)
        val catInterface = findViewById<Button>(R.id.cat_interface)
        val catAutoplay = findViewById<Button>(R.id.cat_autoplay)
        val catSorting = findViewById<Button>(R.id.cat_sorting)
        val catStreaming = findViewById<Button>(R.id.cat_streaming)
        val catSubtitles = findViewById<Button>(R.id.cat_subtitles)
        val catBlocked = findViewById<Button>(R.id.cat_blocked)

        val panelGeneral = findViewById<LinearLayout>(R.id.panel_general)
        val panelWeb = findViewById<LinearLayout>(R.id.panel_web)
        val panelPlayer = findViewById<LinearLayout>(R.id.panel_player)
        val panelInterface = findViewById<LinearLayout>(R.id.panel_interface)
        val panelAutoplay = findViewById<LinearLayout>(R.id.panel_autoplay)
        val panelSorting = findViewById<LinearLayout>(R.id.panel_sorting)
        val panelStreaming = findViewById<LinearLayout>(R.id.panel_streaming)
        val panelSubtitles = findViewById<LinearLayout>(R.id.panel_subtitles)
        val panelBlocked = findViewById<LinearLayout>(R.id.panel_blocked)

        fun findFirstFocusable(view: View): View? {
            if (view.isFocusable && view.visibility == View.VISIBLE && view !is LinearLayout && view.id != View.NO_ID) return view
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    val child = view.getChildAt(i)
                    val found = findFirstFocusable(child)
                    if (found != null) return found
                }
            }
            return null
        }

        fun showPanel(panel: View, category: Button) {
            if (activeCategory == category && panel.visibility == View.VISIBLE) return

            panelGeneral.visibility = View.GONE
            panelWeb.visibility = View.GONE
            panelPlayer.visibility = View.GONE
            panelInterface.visibility = View.GONE
            panelAutoplay.visibility = View.GONE
            panelSorting.visibility = View.GONE
            panelStreaming.visibility = View.GONE
            panelSubtitles.visibility = View.GONE
            panelBlocked.visibility = View.GONE
            panel.visibility = View.VISIBLE

            catGeneral.alpha = 0.5f
            catWeb.alpha = 0.5f
            catPlayer.alpha = 0.5f
            catInterface.alpha = 0.5f
            catAutoplay.alpha = 0.5f
            catSorting.alpha = 0.5f
            catStreaming.alpha = 0.5f
            catSubtitles.alpha = 0.5f
            catBlocked.alpha = 0.5f

            activeCategory?.isSelected = false
            activeCategory = category
            category.isSelected = true
            category.alpha = 1.0f

            if (panel == panelAutoplay) {
                refreshAutoplayProfiles()
            }
            if (panel == panelSorting) {
                refreshSortingPanel()
            }
            if (panel == panelStreaming) {
                refreshStreamingPanel()
            }
            if (panel == panelSubtitles) {
                refreshSubtitlesPanel()
            }
            if (panel == panelBlocked) {
                refreshBlockedElements()
            }

            // Dynamic Focus: Point nextFocusRight to the first focusable element in the new panel
            panel.post {
                val firstFocusable = findFirstFocusable(panel)
                if (firstFocusable != null) {
                    category.nextFocusRightId = firstFocusable.id
                }
            }
        }

        val focusListener = View.OnFocusChangeListener { v, hasFocus ->
            if (hasFocus && v is Button) {
                when(v.id) {
                    R.id.cat_general -> showPanel(panelGeneral, catGeneral)
                    R.id.cat_web -> showPanel(panelWeb, catWeb)
                    R.id.cat_player -> showPanel(panelPlayer, catPlayer)
                    R.id.cat_interface -> showPanel(panelInterface, catInterface)
                    R.id.cat_autoplay -> showPanel(panelAutoplay, catAutoplay)
                    R.id.cat_sorting -> showPanel(panelSorting, catSorting)
                    R.id.cat_streaming -> showPanel(panelStreaming, catStreaming)
                    R.id.cat_subtitles -> showPanel(panelSubtitles, catSubtitles)
                    R.id.cat_blocked -> showPanel(panelBlocked, catBlocked)
                }
            }
        }

        catGeneral.onFocusChangeListener = focusListener
        catWeb.onFocusChangeListener = focusListener
        catPlayer.onFocusChangeListener = focusListener
        catInterface.onFocusChangeListener = focusListener
        catAutoplay.onFocusChangeListener = focusListener
        catSorting.onFocusChangeListener = focusListener
        catStreaming.onFocusChangeListener = focusListener
        catSubtitles.onFocusChangeListener = focusListener
        catBlocked.onFocusChangeListener = focusListener

        // Initial state
        showPanel(panelGeneral, catGeneral)

        // Setting Views
        val urlInput = findViewById<EditText>(R.id.adblock_url_input)
        val popupBtn = findViewById<Button>(R.id.popup_pref_btn)
        val videoBtn = findViewById<Button>(R.id.video_pref_btn)
        val videoTriggerBtn = findViewById<Button>(R.id.video_trigger_btn)
        val restoreBtn = findViewById<Button>(R.id.restore_pref_btn)
        val historyBtn = findViewById<Button>(R.id.history_limit_btn)
        val themeBtn = findViewById<Button>(R.id.theme_btn)
        val embeddedSubsBtn = findViewById<Button>(R.id.embedded_subs_btn)
        val scrollTopbarBtn = findViewById<Button>(R.id.scroll_topbar_btn)
        val navigationModeBtn = findViewById<Button>(R.id.navigation_mode_btn)
        val historyIconBtn = findViewById<Button>(R.id.history_icon_btn)
        val bookmarkIconBtn = findViewById<Button>(R.id.bookmark_icon_btn)
        val clickjackBtn = findViewById<Button>(R.id.clickjack_btn)
        val exoFallbackBtn = findViewById<Button>(R.id.exo_fallback_btn)
        val saveBtn = findViewById<Button>(R.id.save_button)

        // Load Prefs
        urlInput.setText(prefs.getString("custom_adblock_url", "https://easylist.to/easylist/easylist.txt"))
        ViewUtils.applySmartDpadFocus(urlInput)
        silentPopupBlock = prefs.getBoolean("silent_popup_block", true)
        extractVideoPref = prefs.getInt("extract_video_pref", 0)
        videoTriggerPref = prefs.getInt("video_trigger_pref", 1)
        restorePref = prefs.getInt("restore_tabs_pref", 0)
        historyLimit = prefs.getInt("history_limit", 20)
        isLightTheme = prefs.getBoolean("light_theme", false)
        embeddedSubsEnabled = prefs.getBoolean("embedded_subs_enabled", true)
        scrollTopbarEnabled = prefs.getBoolean("scroll_topbar_enabled", true)
        historyIconPref = prefs.getInt("history_icon_pref", 0)
        bookmarkIconPref = prefs.getInt("bookmark_icon_pref", 0)
        clickjackPref = prefs.getBoolean("clickjack_prevention", true)
        navigationModePref = prefs.getInt("navigation_mode_pref", 0)
        autoSubPref = prefs.getInt("auto_sub_pref", 0)
        exoFallbackPref = prefs.getInt("exo_fallback_pref", 0)

        fun updateUI() {
            popupBtn.text = if (silentPopupBlock) "Popups: Block Silently" else "Popups: Ask to Allow"
            videoBtn.text = when (extractVideoPref) {
                1 -> "Native Player Hijack: Always"
                2 -> "Native Player Hijack: Never"
                else -> "Native Player Hijack: Ask"
            }
            videoTriggerBtn.text = if (videoTriggerPref == 0) "Auto-play Video: Enabled" else "Auto-play Video: Disabled"
            restoreBtn.text = when (restorePref) {
                1 -> "Restore Tabs: Always"
                2 -> "Restore Tabs: Never"
                else -> "Restore Tabs: Ask"
            }
            historyBtn.text = if (historyLimit == 0) "History Limit: Unlimited" else "History Limit: $historyLimit"
            themeBtn.text = if (isLightTheme) "Theme: Light" else "Theme: Dark"
            embeddedSubsBtn.text = if (embeddedSubsEnabled) "Embedded Subtitles: Enabled" else "Embedded Subtitles: Disabled"
            scrollTopbarBtn.text = if (scrollTopbarEnabled) "Scroll Up for Top Bar: Enabled" else "Scroll Up for Top Bar: Disabled"
            navigationModeBtn.text = if (navigationModePref == 0) "Navigation Mode: Cursor" else "Navigation Mode: D-pad"
            historyIconBtn.text = if (historyIconPref == 0) "History Icons: Thumbnail" else "History Icons: Favicon"
            bookmarkIconBtn.text = if (bookmarkIconPref == 0) "Bookmark Icons: Thumbnail" else "Bookmark Icons: Favicon"
            clickjackBtn.text = if (clickjackPref) "Clickjack Prevention: Enabled" else "Clickjack Prevention: Disabled"
            exoFallbackBtn.text = when (exoFallbackPref) {
                1 -> "On Error: Always open in Browser"
                2 -> "On Error: Never open in Browser"
                else -> "On Error: Ask to open in Browser"
            }
        }
        updateUI()

        popupBtn.setOnClickListener { silentPopupBlock = !silentPopupBlock; updateUI() }
        videoBtn.setOnClickListener { extractVideoPref = (extractVideoPref + 1) % 3; updateUI() }
        videoTriggerBtn.setOnClickListener { videoTriggerPref = (videoTriggerPref + 1) % 2; updateUI() }
        restoreBtn.setOnClickListener { restorePref = (restorePref + 1) % 3; updateUI() }
        historyBtn.setOnClickListener {
            historyLimit = when (historyLimit) {
                10 -> 20; 20 -> 50; 50 -> 100; 100 -> 0; else -> 10
            }
            updateUI()
        }
        themeBtn.setOnClickListener { isLightTheme = !isLightTheme; updateUI() }
        embeddedSubsBtn.setOnClickListener { embeddedSubsEnabled = !embeddedSubsEnabled; updateUI() }
        scrollTopbarBtn.setOnClickListener { scrollTopbarEnabled = !scrollTopbarEnabled; updateUI() }
        historyIconBtn.setOnClickListener { historyIconPref = (historyIconPref + 1) % 2; updateUI() }
        bookmarkIconBtn.setOnClickListener { bookmarkIconPref = (bookmarkIconPref + 1) % 2; updateUI() }
        clickjackBtn.setOnClickListener { clickjackPref = !clickjackPref; updateUI() }
        exoFallbackBtn.setOnClickListener { exoFallbackPref = (exoFallbackPref + 1) % 3; updateUI() }
        navigationModeBtn.setOnClickListener { navigationModePref = (navigationModePref + 1) % 2; updateUI() }

        saveBtn.setOnClickListener {
            val newUrl = urlInput.text.toString()
            prefs.edit()
                .putString("custom_adblock_url", newUrl)
                .putBoolean("silent_popup_block", silentPopupBlock)
                .putInt("extract_video_pref", extractVideoPref)
                .putInt("video_trigger_pref", videoTriggerPref)
                .putInt("restore_tabs_pref", restorePref)
                .putInt("history_limit", historyLimit)
                .putBoolean("light_theme", isLightTheme)
                .putBoolean("embedded_subs_enabled", embeddedSubsEnabled)
                .putBoolean("scroll_topbar_enabled", scrollTopbarEnabled)
                .putInt("history_icon_pref", historyIconPref)
                .putInt("bookmark_icon_pref", bookmarkIconPref)
                .putBoolean("clickjack_prevention", clickjackPref)
                .putInt("navigation_mode_pref", navigationModePref)
                .putInt("auto_sub_pref", autoSubPref)
                .putInt("exo_fallback_pref", exoFallbackPref)
                .apply()

            val appContext = applicationContext
            @Suppress("OPT_IN_USAGE")
            kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                AdBlockManager.updateRules(appContext, newUrl)
            }

            Toast.makeText(this, "Settings Saved!", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun refreshAutoplayProfiles() {
        val container = findViewById<LinearLayout>(R.id.autoplay_profiles_container) ?: return
        if (container.hasFocus()) activeCategory?.requestFocus()
        container.removeAllViews()
        val prefs = getSharedPreferences("BrowserSettings", Context.MODE_PRIVATE)
        val array = JSONArray(prefs.getString("autoplay_profiles", "[]") ?: "[]")
        
        var defaultProfileIndex = -1
        for (i in 0 until array.length()) {
            if (array.getJSONObject(i).optString("id") == "default") {
                defaultProfileIndex = i; break
            }
        }
        
        if (defaultProfileIndex == -1) {
            val defaultProfile = JSONObject().apply {
                put("id", "default")
                put("name", "Default (Generic Search)")
                put("enabled", true)
                put("urlPatterns", JSONArray().put("*"))
                put("script", DEFAULT_AUTOPLAY_SCRIPT.trimIndent())
                put("is_custom", false)
            }
            array.put(defaultProfile)
            prefs.edit().putString("autoplay_profiles", array.toString()).apply()
        } else {
            val defaultProfile = array.getJSONObject(defaultProfileIndex)
            if (!defaultProfile.optBoolean("is_custom", false)) {
                val currentScript = defaultProfile.optString("script")
                val sourceScript = DEFAULT_AUTOPLAY_SCRIPT.trimIndent()
                if (currentScript != sourceScript) {
                    defaultProfile.put("script", sourceScript)
                    prefs.edit().putString("autoplay_profiles", array.toString()).apply()
                }
            }
        }

        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val isEnabled = obj.optBoolean("enabled", true)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                background = getDrawable(R.drawable.bg_focusable)
                setPadding(30, 10, 20, 10)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 10) }
                isFocusable = false
            }
            val nameTxt = TextView(this).apply {
                text = obj.getString("name")
                setTextColor(if (isEnabled) android.graphics.Color.WHITE else android.graphics.Color.GRAY)
                textSize = 16f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val toggle = Switch(this).apply {
                isChecked = isEnabled
                background = getDrawable(R.drawable.bg_focusable)
                isFocusable = true
                id = View.generateViewId() // Ensure it can be found by focus search if needed
                nextFocusLeftId = R.id.cat_autoplay
                setOnCheckedChangeListener { _, checked ->
                    obj.put("enabled", checked)
                    updateAutoplayProfile(obj, i, false)
                    nameTxt.setTextColor(if (checked) android.graphics.Color.WHITE else android.graphics.Color.GRAY)
                }
            }
            val editBtn = ImageButton(this).apply {
                setImageResource(R.drawable.ic_settings)
                background = getDrawable(R.drawable.bg_focusable)
                isFocusable = true
                id = View.generateViewId()
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(20, 20, 20, 20)
                layoutParams = LinearLayout.LayoutParams(90, 90).apply { marginStart = 20 }
                setOnClickListener { editAutoplayProfile(obj, i) }
                nextFocusLeftId = R.id.cat_autoplay
            }
            val deleteBtn = ImageButton(this).apply {
                setImageResource(R.drawable.ic_close)
                background = getDrawable(R.drawable.bg_focusable)
                isFocusable = true
                id = View.generateViewId()
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(20, 20, 20, 20)
                layoutParams = LinearLayout.LayoutParams(90, 90).apply { marginStart = 10 }
                setOnClickListener {
                    if (obj.optString("id") == "default") {
                        Toast.makeText(this@SettingsActivity, "Cannot delete default profile", Toast.LENGTH_SHORT).show()
                    } else {
                        AlertDialog.Builder(this@SettingsActivity).setTitle("Delete Profile").setMessage("Are you sure?").setPositiveButton("Delete") { _, _ -> deleteAutoplayProfile(i) }.setNegativeButton("Cancel", null).show()
                    }
                }
                nextFocusLeftId = R.id.cat_autoplay
            }
            row.addView(nameTxt); row.addView(toggle); row.addView(editBtn); row.addView(deleteBtn)
            container.addView(row)
        }
        val addNewBtn = Button(this).apply {
            text = "+ Add New Profile"
            background = getDrawable(R.drawable.bg_green_focusable)
            setTextColor(android.graphics.Color.WHITE)
            setPadding(30, 20, 30, 20)
            setOnClickListener { addNewAutoplayProfile() }
        }
        container.addView(addNewBtn)
    }

    private fun editAutoplayProfile(profile: JSONObject, index: Int) {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 20, 40, 20) }
        val nameInput = EditText(this).apply { id = View.generateViewId(); hint = "Profile Name"; setText(profile.optString("name")); setSelectAllOnFocus(false); isSingleLine = true; ViewUtils.applySmartDpadFocus(this) }
        val patternsInput = EditText(this).apply { id = View.generateViewId(); hint = "URL Patterns (comma separated, e.g. *example.com*)"; setText(profile.optJSONArray("urlPatterns")?.let { arr -> (0 until arr.length()).map { arr.getString(it) }.joinToString(", ") } ?: ""); setSelectAllOnFocus(false); isSingleLine = true; ViewUtils.applySmartDpadFocus(this) }
        layout.addView(TextView(this).apply { text = "Name"; setTextColor(android.graphics.Color.GRAY) }); layout.addView(nameInput)
        layout.addView(TextView(this).apply { text = "URL Patterns (* for wildcards)"; setTextColor(android.graphics.Color.GRAY); setPadding(0, 20, 0, 0) }); layout.addView(patternsInput)
        layout.addView(TextView(this).apply { text = "Clicker Mode"; setTextColor(android.graphics.Color.GRAY); setPadding(0, 20, 0, 0) })
        val modeBtn = Button(this).apply { id = View.generateViewId(); background = getDrawable(R.drawable.bg_focusable); isFocusable = true }
        layout.addView(modeBtn)

        var useScript = profile.optBoolean("use_script", profile.optString("id") == "default" || profile.optJSONArray("selectors") == null || profile.optJSONArray("selectors")!!.length() == 0)
        val scriptHeader = TextView(this).apply { text = "Script"; setTextColor(android.graphics.Color.GRAY); setPadding(0, 20, 0, 0) }
        val selectorsHeader = TextView(this).apply { text = "Element Selectors"; setTextColor(android.graphics.Color.GRAY); setPadding(0, 20, 0, 0) }
        val scriptInput = EditText(this).apply { id = View.generateViewId(); hint = "JavaScript Clicker"; setText(profile.optString("script")); inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE; minLines = 3; setSelectAllOnFocus(false); ViewUtils.applySmartDpadFocus(this) }
        val selectorsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val addSelectorBtn = Button(this).apply { text = "+ Add Element Selector"; background = getDrawable(R.drawable.bg_focusable); isFocusable = true; id = View.generateViewId() }

        fun updateModeUI() {
            modeBtn.text = if (useScript) "Custom Script" else "Simple Element List"
            scriptHeader.visibility = if (useScript) View.VISIBLE else View.GONE
            scriptInput.visibility = if (useScript) View.VISIBLE else View.GONE
            selectorsHeader.visibility = if (useScript) View.GONE else View.VISIBLE
            selectorsContainer.visibility = if (useScript) View.GONE else View.VISIBLE
            addSelectorBtn.visibility = if (useScript) View.GONE else View.VISIBLE
        }
        modeBtn.setOnClickListener { useScript = !useScript; updateModeUI() }
        
        val selectorList = mutableListOf<String>()
        profile.optJSONArray("selectors")?.let { arr -> for (i in 0 until arr.length()) selectorList.add(arr.getString(i)) }
        fun refreshSelectorsUI() {
            selectorsContainer.removeAllViews()
            selectorList.forEachIndexed { idx, selector ->
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
                val input = EditText(this).apply { setText(selector); hint = "e.g. .play-button"; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f); setSelectAllOnFocus(false); isSingleLine = true; ViewUtils.applySmartDpadFocus(this); addTextChangedListener(object : TextWatcher { override fun afterTextChanged(s: Editable?) { selectorList[idx] = s.toString() }; override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}; override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {} }) }
                val delBtn = ImageButton(this).apply { setImageResource(R.drawable.ic_close); background = getDrawable(R.drawable.bg_focusable); layoutParams = LinearLayout.LayoutParams(90, 90).apply { marginStart = 10 }; setOnClickListener { selectorList.removeAt(idx); refreshSelectorsUI() } }
                row.addView(input); row.addView(delBtn); selectorsContainer.addView(row)
            }
        }
        addSelectorBtn.setOnClickListener { selectorList.add(""); refreshSelectorsUI() }
        layout.addView(scriptHeader); layout.addView(scriptInput); layout.addView(selectorsHeader); layout.addView(selectorsContainer); layout.addView(addSelectorBtn)
        refreshSelectorsUI(); updateModeUI()

        AlertDialog.Builder(this).setTitle("Edit Autoplay Profile").setView(layout).setPositiveButton("Save") { _, _ ->
            profile.put("name", nameInput.text.toString())
            profile.put("urlPatterns", JSONArray(patternsInput.text.toString().split(",").map { it.trim() }.filter { it.isNotEmpty() }))
            profile.put("use_script", useScript)
            profile.put("selectors", JSONArray(selectorList.filter { it.isNotEmpty() }))
            profile.put("script", scriptInput.text.toString())
            if (profile.optString("id") == "default") profile.put("is_custom", !(useScript && scriptInput.text.toString() == DEFAULT_AUTOPLAY_SCRIPT.trimIndent()))
            updateAutoplayProfile(profile, index)
        }.setNegativeButton("Cancel", null).show()
    }

    private fun addNewAutoplayProfile() {
        val newProfile = JSONObject().apply { put("id", java.util.UUID.randomUUID().toString()); put("name", "New Profile"); put("enabled", true); put("urlPatterns", JSONArray().put("*")); put("script", "") }
        editAutoplayProfile(newProfile, -1)
    }

    private fun updateAutoplayProfile(profile: JSONObject, index: Int, refresh: Boolean = true) {
        val prefs = getSharedPreferences("BrowserSettings", Context.MODE_PRIVATE)
        val array = JSONArray(prefs.getString("autoplay_profiles", "[]"))
        if (index >= 0) array.put(index, profile) else array.put(profile)
        prefs.edit().putString("autoplay_profiles", array.toString()).apply()
        if (refresh) refreshAutoplayProfiles()
    }

    private fun deleteAutoplayProfile(index: Int) {
        val prefs = getSharedPreferences("BrowserSettings", Context.MODE_PRIVATE)
        val array = JSONArray(prefs.getString("autoplay_profiles", "[]"))
        val newArray = JSONArray()
        for (i in 0 until array.length()) { if (i != index) newArray.put(array.get(i)) }
        prefs.edit().putString("autoplay_profiles", newArray.toString()).apply()
        refreshAutoplayProfiles()
    }

    private fun refreshBlockedElements() {
        val container = findViewById<LinearLayout>(R.id.blocked_elements_container) ?: return
        if (container.hasFocus()) activeCategory?.requestFocus()
        container.removeAllViews()
        val prefs = getSharedPreferences("BrowserSettings", Context.MODE_PRIVATE)
        val array = JSONArray(prefs.getString("blocked_elements", "[]") ?: "[]")

        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val isEnabled = obj.optBoolean("enabled", true)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                background = getDrawable(R.drawable.bg_focusable)
                setPadding(30, 10, 20, 10)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 10) }
                isFocusable = false
            }
            val nameTxt = TextView(this).apply {
                text = obj.getString("name")
                setTextColor(if (isEnabled) android.graphics.Color.WHITE else android.graphics.Color.GRAY)
                textSize = 16f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val toggle = Switch(this).apply {
                isChecked = isEnabled
                background = getDrawable(R.drawable.bg_focusable)
                isFocusable = true
                id = View.generateViewId()
                nextFocusLeftId = R.id.cat_blocked
                setOnCheckedChangeListener { _, checked ->
                    obj.put("enabled", checked)
                    updateBlockedElement(obj, i, false)
                    nameTxt.setTextColor(if (checked) android.graphics.Color.WHITE else android.graphics.Color.GRAY)
                }
            }
            val editBtn = ImageButton(this).apply {
                setImageResource(R.drawable.ic_settings)
                background = getDrawable(R.drawable.bg_focusable)
                isFocusable = true
                id = View.generateViewId()
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(20, 20, 20, 20)
                layoutParams = LinearLayout.LayoutParams(90, 90).apply { marginStart = 20 }
                setOnClickListener { editBlockedElement(obj, i) }
                nextFocusLeftId = R.id.cat_blocked
            }
            val deleteBtn = ImageButton(this).apply {
                setImageResource(R.drawable.ic_close)
                background = getDrawable(R.drawable.bg_focusable)
                isFocusable = true
                id = View.generateViewId()
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(20, 20, 20, 20)
                layoutParams = LinearLayout.LayoutParams(90, 90).apply { marginStart = 10 }
                setOnClickListener {
                    AlertDialog.Builder(this@SettingsActivity).setTitle("Delete Rule").setMessage("Are you sure?").setPositiveButton("Delete") { _, _ -> deleteBlockedElement(i) }.setNegativeButton("Cancel", null).show()
                }
                nextFocusLeftId = R.id.cat_blocked
            }
            row.addView(nameTxt); row.addView(toggle); row.addView(editBtn); row.addView(deleteBtn)
            container.addView(row)
        }
        val addNewBtn = Button(this).apply {
            text = "+ Add New Rule"
            background = getDrawable(R.drawable.bg_green_focusable)
            setTextColor(android.graphics.Color.WHITE)
            setPadding(30, 20, 30, 20)
            setOnClickListener { addNewBlockedElement() }
        }
        container.addView(addNewBtn)
    }

    private fun editBlockedElement(rule: JSONObject, index: Int) {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 20, 40, 20) }
        val nameInput = EditText(this).apply { id = View.generateViewId(); hint = "Rule Name (e.g. Site Name)"; setText(rule.optString("name")); setSelectAllOnFocus(false); isSingleLine = true; ViewUtils.applySmartDpadFocus(this) }
        val patternsInput = EditText(this).apply { id = View.generateViewId(); hint = "URL Patterns (comma separated)"; setText(rule.optJSONArray("urlPatterns")?.let { arr -> (0 until arr.length()).map { arr.getString(it) }.joinToString(", ") } ?: ""); setSelectAllOnFocus(false); isSingleLine = true; ViewUtils.applySmartDpadFocus(this) }
        val selectorsInput = EditText(this).apply { id = View.generateViewId(); hint = "CSS Selectors (one per line)"; setText(rule.optJSONArray("selectors")?.let { arr -> (0 until arr.length()).map { arr.getString(it) }.joinToString("\n") } ?: ""); inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE; minLines = 3; setSelectAllOnFocus(false); ViewUtils.applySmartDpadFocus(this) }
        nameInput.nextFocusDownId = patternsInput.id; patternsInput.nextFocusUpId = nameInput.id; patternsInput.nextFocusDownId = selectorsInput.id; selectorsInput.nextFocusUpId = patternsInput.id
        layout.addView(TextView(this).apply { text = "Name"; setTextColor(android.graphics.Color.GRAY) }); layout.addView(nameInput)
        layout.addView(TextView(this).apply { text = "URL Patterns (* for wildcards)"; setTextColor(android.graphics.Color.GRAY); setPadding(0, 20, 0, 0) }); layout.addView(patternsInput)
        layout.addView(TextView(this).apply { text = "CSS Selectors (to hide)"; setTextColor(android.graphics.Color.GRAY); setPadding(0, 20, 0, 0) }); layout.addView(selectorsInput)
        val scrollView = android.widget.ScrollView(this).apply { addView(layout) }
        AlertDialog.Builder(this).setTitle("Edit Blocked Elements").setView(scrollView).setPositiveButton("Save") { _, _ ->
            rule.put("name", nameInput.text.toString())
            rule.put("urlPatterns", JSONArray(patternsInput.text.toString().split(",").map { it.trim() }.filter { it.isNotEmpty() }))
            rule.put("selectors", JSONArray(selectorsInput.text.toString().split("\n").map { it.trim() }.filter { it.isNotEmpty() }))
            updateBlockedElement(rule, index)
        }.setNegativeButton("Cancel", null).show()
    }

    private fun addNewBlockedElement() {
        val newRule = JSONObject().apply { put("id", java.util.UUID.randomUUID().toString()); put("name", "New Rule"); put("enabled", true); put("urlPatterns", JSONArray().put("*")); put("selectors", JSONArray()) }
        editBlockedElement(newRule, -1)
    }

    private fun updateBlockedElement(rule: JSONObject, index: Int, refresh: Boolean = true) {
        val prefs = getSharedPreferences("BrowserSettings", Context.MODE_PRIVATE)
        val array = JSONArray(prefs.getString("blocked_elements", "[]"))
        if (index >= 0) array.put(index, rule) else array.put(rule)
        prefs.edit().putString("blocked_elements", array.toString()).apply()
        if (refresh) refreshBlockedElements()
    }

    private fun deleteBlockedElement(index: Int) {
        val prefs = getSharedPreferences("BrowserSettings", Context.MODE_PRIVATE)
        val array = JSONArray(prefs.getString("blocked_elements", "[]"))
        val newArray = JSONArray()
        for (i in 0 until array.length()) { if (i != index) newArray.put(array.get(i)) }
        prefs.edit().putString("blocked_elements", newArray.toString()).apply()
        refreshBlockedElements()
    }

    private fun refreshSortingPanel() {
        val container = findViewById<LinearLayout>(R.id.sorting_priorities_container) ?: return
        container.removeAllViews()
        val prefs = getSharedPreferences("BrowserSettings", Context.MODE_PRIVATE)

        val priorities = mutableListOf<SortCriteria>()
        val json = prefs.getString("sort_priorities", null)
        if (json != null) {
            try {
                val array = JSONArray(json)
                for (i in 0 until array.length()) {
                    priorities.add(SortCriteria.valueOf(array.getString(i)))
                }
            } catch (e: Exception) {
                priorities.addAll(SourceSorter.DEFAULT_PRIORITIES)
            }
        } else {
            priorities.addAll(SourceSorter.DEFAULT_PRIORITIES)
        }

        val inflater = android.view.LayoutInflater.from(this)
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
                    saveSortPriorities(priorities)
                    refreshSortingPanel()
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
                    saveSortPriorities(priorities)
                    refreshSortingPanel()
                    val targetRow = container.getChildAt(index + 1)
                    val targetBtn = targetRow.findViewById<View>(R.id.btn_down)
                    if (targetBtn.visibility == View.VISIBLE) targetBtn.requestFocus()
                    else targetRow.findViewById<View>(R.id.btn_up).requestFocus()
                }
            }
            container.addView(row)
        }
    }

    private fun saveSortPriorities(priorities: List<SortCriteria>) {
        val prefs = getSharedPreferences("BrowserSettings", Context.MODE_PRIVATE)
        val array = JSONArray()
        priorities.forEach { array.put(it.name) }
        prefs.edit().putString("sort_priorities", array.toString()).apply()
    }

    private fun refreshStreamingPanel() {
        val prefs = getSharedPreferences("BrowserSettings", Context.MODE_PRIVATE)
        timeoutMode = prefs.getString("timeout_mode", "Both") ?: "Both"
        globalTimeout = prefs.getInt("global_timeout", 30)
        perSourceTimeout = prefs.getInt("per_source_timeout", 15)
        useWhitelist = prefs.getBoolean("use_only_whitelisted_hosts", true)
        val packsArr = JSONArray(prefs.getString("enabled_packs", "[]") ?: "[]")
        enabledPacks.clear(); for (i in 0 until packsArr.length()) enabledPacks.add(packsArr.getString(i))
        val hostsArr = JSONArray(prefs.getString("whitelisted_hosts", "[]") ?: "[]")
        whitelistedHosts.clear(); for (i in 0 until hostsArr.length()) whitelistedHosts.add(hostsArr.getString(i))

        val timeoutBtn = findViewById<Button>(R.id.timeout_mode_btn)
        val globalLayout = findViewById<LinearLayout>(R.id.global_timeout_layout)
        val globalInput = findViewById<EditText>(R.id.global_timeout_input)
        val sourceLayout = findViewById<LinearLayout>(R.id.source_timeout_layout)
        val sourceInput = findViewById<EditText>(R.id.source_timeout_input)
        val whitelistBtn = findViewById<Button>(R.id.whitelist_toggle_btn)
        val whitelistControls = findViewById<LinearLayout>(R.id.whitelist_controls_layout)
        val packsContainer = findViewById<LinearLayout>(R.id.provider_packs_container)
        val hostsRecycler = findViewById<RecyclerView>(R.id.whitelist_hosts_recycler)
        val hostSearchInput = findViewById<EditText>(R.id.host_search_input)

        hostsRecycler.layoutManager = LinearLayoutManager(this)
        hostsAdapter = HostAdapter()
        hostsRecycler.adapter = hostsAdapter

        fun updateTimeoutUI() {
            timeoutBtn.text = "Timeout Mode: $timeoutMode"
            globalLayout.visibility = if (timeoutMode == "Global" || timeoutMode == "Both") View.VISIBLE else View.GONE
            sourceLayout.visibility = if (timeoutMode == "Per-Source" || timeoutMode == "Both") View.VISIBLE else View.GONE
        }
        timeoutBtn.setOnClickListener { timeoutMode = when (timeoutMode) { "Global" -> "Per-Source"; "Per-Source" -> "Both"; else -> "Global" }; updateTimeoutUI(); saveStreamingSettings() }
        globalInput.setText(globalTimeout.toString())
        globalInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { globalTimeout = s.toString().toIntOrNull() ?: 30; saveStreamingSettings() }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
        sourceInput.setText(perSourceTimeout.toString())
        sourceInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { perSourceTimeout = s.toString().toIntOrNull() ?: 15; saveStreamingSettings() }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
        updateTimeoutUI()
        fun updateWhitelistUI() {
            whitelistBtn.text = if (useWhitelist) "Use Whitelist: Enabled" else "Use Whitelist: Disabled"
            whitelistControls.visibility = View.VISIBLE
            updateWhitelistUIControls(useWhitelist)
        }
        whitelistBtn.setOnClickListener { useWhitelist = !useWhitelist; updateWhitelistUI(); saveStreamingSettings() }
        updateWhitelistUI()

        packsContainer.removeAllViews()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val py = Python.getInstance(); val packs = JSONArray(py.getModule("main").callAttr("get_enabled_packs").toString())
                withContext(Dispatchers.Main) {
                    val wasEmpty = enabledPacks.isEmpty()
                    if (wasEmpty) {
                        for (i in 0 until packs.length()) enabledPacks.add(packs.getString(i))
                    }
                    for (i in 0 until packs.length()) {
                        val name = packs.getString(i)
                        packsContainer.addView(CheckBox(this@SettingsActivity).apply { 
                            text = name
                            setTextColor(android.graphics.Color.WHITE)
                            isChecked = enabledPacks.contains(name)
                            setOnCheckedChangeListener { _, c -> 
                                if (c) { if (!enabledPacks.contains(name)) enabledPacks.add(name) } 
                                else { enabledPacks.remove(name) }
                                saveStreamingSettings() 
                            } 
                        })
                    }
                    if (wasEmpty) saveStreamingSettings()
                }
            } catch (e: Exception) {}
        }
        fun refreshHosts(filter: String = "") {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    if (allHostsList.isEmpty()) {
                        val py = Python.getInstance()
                        val hostsJson = py.getModule("main").callAttr("get_all_hosts").toString()
                        val hostsObj = JSONObject(hostsJson)
                        val newList = mutableListOf<HostItem>()
                        val keys = hostsObj.keys()
                        while(keys.hasNext()) {
                            val category = keys.next()
                            val hosts = hostsObj.getJSONArray(category)
                            if (hosts.length() > 0) {
                                newList.add(HostItem(category, category, true))
                                for (i in 0 until hosts.length()) {
                                    newList.add(HostItem(hosts.getString(i), category, false))
                                }
                            }
                        }
                        allHostsList.clear()
                        allHostsList.addAll(newList)
                    }

                    filteredHostsList = if (filter.isEmpty()) {
                        allHostsList.toMutableList()
                    } else {
                        val result = mutableListOf<HostItem>()
                        var lastHeader: HostItem? = null
                        allHostsList.forEach { 
                            if (it.isHeader) {
                                lastHeader = it
                            } else if (it.name.contains(filter, true)) {
                                if (lastHeader != null) {
                                    result.add(lastHeader)
                                    lastHeader = null
                                }
                                result.add(it)
                            }
                        }
                        result
                    }

                    withContext(Dispatchers.Main) {
                        hostsAdapter.notifyDataSetChanged()
                    }
                } catch (e: Exception) {
                    Log.e("TVBrowser", "refreshHosts error: ${e.message}")
                }
            }
        }
        findViewById<Button>(R.id.btn_whitelist_select_all).setOnClickListener { 
            filteredHostsList.forEach { if (!it.isHeader) { if (!whitelistedHosts.contains(it.name)) whitelistedHosts.add(it.name) } }
            hostsAdapter.notifyDataSetChanged()
            saveStreamingSettings()
        }
        findViewById<Button>(R.id.btn_whitelist_clear_all).setOnClickListener { 
            whitelistedHosts.clear()
            hostsAdapter.notifyDataSetChanged()
            saveStreamingSettings() 
        }
        hostSearchInput.addTextChangedListener(object : TextWatcher { override fun afterTextChanged(s: Editable?) { refreshHosts(s.toString()) }; override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}; override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {} })
        refreshHosts()
    }

    private inner class HostAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemViewType(position: Int) = if (filteredHostsList[position].isHeader) 0 else 1
        
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return if (viewType == 0) {
                val tv = TextView(parent.context).apply {
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(android.graphics.Color.parseColor("#0e639c"))
                    setPadding(20, 30, 20, 10)
                    textSize = 16f
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                }
                object : RecyclerView.ViewHolder(tv) {}
            } else {
                val cb = CheckBox(parent.context).apply {
                    setTextColor(android.graphics.Color.WHITE)
                    background = parent.context.getDrawable(R.drawable.bg_focusable)
                    setPadding(20, 20, 20, 20)
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                }
                object : RecyclerView.ViewHolder(cb) {}
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val item = filteredHostsList[position]
            if (item.isHeader) {
                (holder.itemView as TextView).text = item.name
            } else {
                val cb = holder.itemView as CheckBox
                cb.setOnCheckedChangeListener(null)
                cb.text = item.name
                cb.isChecked = whitelistedHosts.contains(item.name)
                cb.isEnabled = useWhitelist
                cb.alpha = if (useWhitelist) 1.0f else 0.5f
                cb.setOnCheckedChangeListener { _, c -> 
                    if (c) { if (!whitelistedHosts.contains(item.name)) whitelistedHosts.add(item.name) } 
                    else { whitelistedHosts.remove(item.name) }
                    saveStreamingSettings()
                }
            }
        }

        override fun getItemCount() = filteredHostsList.size
    }

    private fun updateWhitelistUIControls(enabled: Boolean) {
        val searchInput = findViewById<EditText>(R.id.host_search_input)
        val selectAll = findViewById<Button>(R.id.btn_whitelist_select_all)
        val clearAll = findViewById<Button>(R.id.btn_whitelist_clear_all)

        searchInput.isEnabled = enabled
        selectAll.isEnabled = enabled
        clearAll.isEnabled = enabled
        
        if (::hostsAdapter.isInitialized) {
            hostsAdapter.notifyDataSetChanged()
        }
    }

    private fun saveStreamingSettings() {
        val prefs = getSharedPreferences("BrowserSettings", Context.MODE_PRIVATE)
        prefs.edit().putString("timeout_mode", timeoutMode).putInt("global_timeout", globalTimeout).putInt("per_source_timeout", perSourceTimeout).putBoolean("use_only_whitelisted_hosts", useWhitelist).putString("enabled_packs", JSONArray(enabledPacks).toString()).putString("whitelisted_hosts", JSONArray(whitelistedHosts).toString()).apply()
        lifecycleScope.launch(Dispatchers.IO) { 
            try { 
                val py = Python.getInstance()
                val cfg = JSONObject().apply { 
                    put("timeout_mode", timeoutMode)
                    put("global_timeout", globalTimeout)
                    put("per_source_timeout", perSourceTimeout)
                    put("use_only_whitelisted_hosts", useWhitelist)
                    put("whitelisted_hosts", JSONArray(whitelistedHosts))
                    put("enabled_packs", JSONArray(enabledPacks))
                } 
                py.getModule("main").callAttr("set_config", cfg.toString())
            } catch (e: Exception) {}
        }
    }

    private fun refreshSubtitlesPanel() {
        val prefs = getSharedPreferences("BrowserSettings", Context.MODE_PRIVATE)
        autoSubPref = prefs.getInt("auto_sub_pref", 0)
        autoSubCount = prefs.getInt("auto_sub_count", 1)
        autoSubWaitPref = prefs.getInt("auto_sub_wait_pref", 0)
        subRetentionDays = prefs.getInt("sub_retention_days", 3)
        subLangs = prefs.getString("subtitles_languages", "English") ?: "English"
        subLimit = prefs.getInt("subtitles_limit", 20)
        val serviceKeys = listOf("addic7ed", "bsplayer", "opensubtitles", "opensubtitles_org", "podnadpisi", "subdl", "subsource")
        serviceKeys.forEach { subServices[it] = prefs.getBoolean("${it}_enabled", it != "bsplayer" && it != "opensubtitles_org" && it != "podnadpisi") }
        openSubUser = prefs.getString("opensubtitles_username", "") ?: ""; openSubPass = prefs.getString("opensubtitles_password", "") ?: ""
        openSubOrgUser = prefs.getString("opensubtitles_org_username", "") ?: ""; openSubOrgPass = prefs.getString("opensubtitles_org_password", "") ?: ""
        subdlKey = prefs.getString("subdl_apikey", "") ?: ""; subsourceKey = prefs.getString("subsource_apikey", "") ?: ""

        val autoSubBtn = findViewById<Button>(R.id.auto_sub_pref_btn)
        val autoSubCountLayout = findViewById<LinearLayout>(R.id.auto_sub_count_layout)
        val autoSubCountBtn = findViewById<Button>(R.id.auto_sub_count_btn)
        val autoSubWaitBtn = findViewById<Button>(R.id.auto_sub_wait_btn)
        
        val subRetentionBtn = findViewById<Button>(R.id.sub_retention_btn)

        val langsInput = findViewById<EditText>(R.id.sub_langs_input)
        val limitInput = findViewById<EditText>(R.id.sub_limit_input)
        val servicesContainer = findViewById<LinearLayout>(R.id.sub_services_container)
        val openSubUserInput = findViewById<EditText>(R.id.opensub_user_input)
        val openSubPassInput = findViewById<EditText>(R.id.opensub_pass_input)
        val openSubOrgUserInput = findViewById<EditText>(R.id.opensub_org_user_input)
        val openSubOrgPassInput = findViewById<EditText>(R.id.opensub_org_pass_input)
        val subdlKeyInput = findViewById<EditText>(R.id.subdl_key_input)
        val subsourceKeyInput = findViewById<EditText>(R.id.subsource_key_input)

        fun updateAutoSubUI() { 
            autoSubBtn.text = when(autoSubPref) { 1 -> "Auto-search Subtitles: Automatic"; 2 -> "Auto-search Subtitles: Never"; else -> "Auto-search Subtitles: Ask" }
            autoSubCountLayout.visibility = if (autoSubPref == 1) View.VISIBLE else View.GONE
        }
        autoSubBtn.setOnClickListener { autoSubPref = (autoSubPref + 1) % 3; updateAutoSubUI(); saveSubtitlesSettings() }
        updateAutoSubUI()

        fun updateAutoSubCountUI() {
            autoSubCountBtn.text = if (autoSubCount == 0) "Add: All Subtitles" else "Add: $autoSubCount Subtitle${if (autoSubCount > 1) "s" else ""}"
        }
        autoSubCountBtn.setOnClickListener { 
            autoSubCount = if (autoSubCount >= 5) 0 else if (autoSubCount == 0) 1 else autoSubCount + 1
            updateAutoSubCountUI()
            saveSubtitlesSettings()
        }
        updateAutoSubCountUI()

        fun updateAutoSubWaitUI() {
            autoSubWaitBtn.text = when(autoSubWaitPref) {
                0 -> "When launching video: Stop adding subtitles"
                1 -> "When launching video: Ask to wait for subtitles"
                2 -> "When launching video: Launch anyway & add progressively"
                else -> "When launching video: Stop adding subtitles"
            }
        }
        autoSubWaitBtn.setOnClickListener {
            autoSubWaitPref = (autoSubWaitPref + 1) % 3
            updateAutoSubWaitUI()
            saveSubtitlesSettings()
        }
        updateAutoSubWaitUI()

        fun updateSubRetentionUI() {
            subRetentionBtn.text = if (subRetentionDays == 0) "Retention: Indefinite" else "Retention: $subRetentionDays Day${if (subRetentionDays > 1) "s" else ""}"
        }
        subRetentionBtn.setOnClickListener {
            subRetentionDays = when(subRetentionDays) {
                1 -> 3; 3 -> 7; 7 -> 14; 14 -> 30; 30 -> 0; else -> 1
            }
            updateSubRetentionUI()
            saveSubtitlesSettings()
        }
        updateSubRetentionUI()

        langsInput.setText(subLangs); langsInput.addTextChangedListener(object : TextWatcher { override fun afterTextChanged(s: Editable?) { subLangs = s.toString(); saveSubtitlesSettings() }; override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}; override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {} })
        limitInput.setText(subLimit.toString()); limitInput.addTextChangedListener(object : TextWatcher { override fun afterTextChanged(s: Editable?) { subLimit = s.toString().toIntOrNull() ?: 20; saveSubtitlesSettings() }; override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}; override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {} })

        servicesContainer.removeAllViews()
        serviceKeys.forEach { key -> servicesContainer.addView(CheckBox(this).apply { text = key.replace("_", " ").capitalize(); setTextColor(android.graphics.Color.WHITE); isChecked = subServices[key] ?: true; setOnCheckedChangeListener { _, c -> subServices[key] = c; saveSubtitlesSettings() } }) }

        openSubUserInput.setText(openSubUser); openSubUserInput.addTextChangedListener(object : TextWatcher { override fun afterTextChanged(s: Editable?) { openSubUser = s.toString(); saveSubtitlesSettings() }; override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}; override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {} })
        openSubPassInput.setText(openSubPass); openSubPassInput.addTextChangedListener(object : TextWatcher { override fun afterTextChanged(s: Editable?) { openSubPass = s.toString(); saveSubtitlesSettings() }; override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}; override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {} })
        openSubOrgUserInput.setText(openSubOrgUser); openSubOrgUserInput.addTextChangedListener(object : TextWatcher { override fun afterTextChanged(s: Editable?) { openSubOrgUser = s.toString(); saveSubtitlesSettings() }; override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}; override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {} })
        openSubOrgPassInput.setText(openSubOrgPass); openSubOrgPassInput.addTextChangedListener(object : TextWatcher { override fun afterTextChanged(s: Editable?) { openSubOrgPass = s.toString(); saveSubtitlesSettings() }; override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}; override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {} })
        subdlKeyInput.setText(subdlKey); subdlKeyInput.addTextChangedListener(object : TextWatcher { override fun afterTextChanged(s: Editable?) { subdlKey = s.toString(); saveSubtitlesSettings() }; override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}; override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {} })
        subsourceKeyInput.setText(subsourceKey); subsourceKeyInput.addTextChangedListener(object : TextWatcher { override fun afterTextChanged(s: Editable?) { subsourceKey = s.toString(); saveSubtitlesSettings() }; override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}; override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {} })
    }

    private fun saveSubtitlesSettings() {
        val prefs = getSharedPreferences("BrowserSettings", Context.MODE_PRIVATE); val editor = prefs.edit()
        editor.putInt("auto_sub_pref", autoSubPref).putInt("auto_sub_count", autoSubCount).putInt("auto_sub_wait_pref", autoSubWaitPref)
        editor.putInt("sub_retention_days", subRetentionDays)
        editor.putString("subtitles_languages", subLangs).putInt("subtitles_limit", subLimit)
        subServices.forEach { (k, v) -> editor.putBoolean("${k}_enabled", v) }
        editor.putString("opensubtitles_username", openSubUser).putString("opensubtitles_password", openSubPass).putString("opensubtitles_org_username", openSubOrgUser).putString("opensubtitles_org_password", openSubOrgPass).putString("subdl_apikey", subdlKey).putString("subsource_apikey", subsourceKey).apply()
        lifecycleScope.launch(Dispatchers.IO) { try { val py = Python.getInstance(); val cfg = JSONObject().apply { put("subtitles_languages", subLangs); put("subtitles_limit", subLimit); subServices.forEach { (k, v) -> put("${k}_enabled", v) }; put("opensubtitles_username", openSubUser); put("opensubtitles_password", openSubPass); put("opensubtitles_org_username", openSubOrgUser); put("opensubtitles_org_password", openSubOrgPass); put("subdl_apikey", subdlKey); put("subsource_apikey", subsourceKey); put("sub_retention_days", subRetentionDays) }; py.getModule("main").callAttr("set_config", cfg.toString()) } catch (e: Exception) {} }
    }
}
