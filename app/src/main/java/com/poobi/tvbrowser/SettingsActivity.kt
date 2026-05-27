package com.poobi.tvbrowser

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.launch
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
        val catBlocked = findViewById<Button>(R.id.cat_blocked)

        val panelGeneral = findViewById<LinearLayout>(R.id.panel_general)
        val panelWeb = findViewById<LinearLayout>(R.id.panel_web)
        val panelPlayer = findViewById<LinearLayout>(R.id.panel_player)
        val panelInterface = findViewById<LinearLayout>(R.id.panel_interface)
        val panelAutoplay = findViewById<LinearLayout>(R.id.panel_autoplay)
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
            panelBlocked.visibility = View.GONE
            panel.visibility = View.VISIBLE

            catGeneral.alpha = 0.5f
            catWeb.alpha = 0.5f
            catPlayer.alpha = 0.5f
            catInterface.alpha = 0.5f
            catAutoplay.alpha = 0.5f
            catBlocked.alpha = 0.5f

            activeCategory?.isSelected = false
            activeCategory = category
            category.isSelected = true
            category.alpha = 1.0f

            if (panel == panelAutoplay) {
                refreshAutoplayProfiles()
            }
            if (panel == panelBlocked) {
                refreshBlockedElements()
            }

            // Dynamic Focus: Point nextFocusRight to the first focusable element in the new panel
            // IMPORTANT: This must happen AFTER refresh functions so the container is populated
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
                    R.id.cat_blocked -> showPanel(panelBlocked, catBlocked)
                }
            }
        }

        catGeneral.onFocusChangeListener = focusListener
        catWeb.onFocusChangeListener = focusListener
        catPlayer.onFocusChangeListener = focusListener
        catInterface.onFocusChangeListener = focusListener
        catAutoplay.onFocusChangeListener = focusListener
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
        
        // General fix: If focus is inside the container being refreshed, move it to the category button
        // to prevent it from jumping to the first category (General) and resetting the screen.
        if (container.hasFocus()) {
            activeCategory?.requestFocus()
        }

        container.removeAllViews()
        
        val prefs = getSharedPreferences("BrowserSettings", Context.MODE_PRIVATE)
        val profilesJson = prefs.getString("autoplay_profiles", "[]") ?: "[]"
        val array = JSONArray(profilesJson)
        
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
                val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                params.setMargins(0, 0, 0, 10)
                layoutParams = params
                isFocusable = false // Let children handle focus
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
                val btnParams = LinearLayout.LayoutParams(90, 90)
                btnParams.marginStart = 20
                layoutParams = btnParams
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
                val btnParams = LinearLayout.LayoutParams(90, 90)
                btnParams.marginStart = 10
                layoutParams = btnParams
                setOnClickListener {
                    if (obj.optString("id") == "default") {
                        Toast.makeText(this@SettingsActivity, "Cannot delete default profile", Toast.LENGTH_SHORT).show()
                    } else {
                        AlertDialog.Builder(this@SettingsActivity)
                            .setTitle("Delete Profile")
                            .setMessage("Are you sure you want to delete '${obj.getString("name")}'?")
                            .setPositiveButton("Delete") { _, _ -> deleteAutoplayProfile(i) }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                }
                nextFocusLeftId = R.id.cat_autoplay
            }

            row.addView(nameTxt)
            row.addView(toggle)
            row.addView(editBtn)
            row.addView(deleteBtn)
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
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 20)
        }
        
        val nameInput = EditText(this).apply { 
            id = View.generateViewId()
            hint = "Profile Name"
            setText(profile.optString("name"))
            setSelectAllOnFocus(false)
            isSingleLine = true
            ViewUtils.applySmartDpadFocus(this)
        }
        val patternsInput = EditText(this).apply { 
            id = View.generateViewId()
            hint = "URL Patterns (comma separated, e.g. *example.com*)"
            setText(profile.optJSONArray("urlPatterns")?.let { arr ->
                (0 until arr.length()).map { arr.getString(it) }.joinToString(", ")
            } ?: "")
            setSelectAllOnFocus(false)
            isSingleLine = true
            ViewUtils.applySmartDpadFocus(this)
        }

        layout.addView(TextView(this).apply { text = "Name"; setTextColor(android.graphics.Color.GRAY) })
        layout.addView(nameInput)
        layout.addView(TextView(this).apply { text = "URL Patterns (* for wildcards)"; setTextColor(android.graphics.Color.GRAY); setPadding(0, 20, 0, 0) })
        layout.addView(patternsInput)

        // Mode Selection
        layout.addView(TextView(this).apply { text = "Clicker Mode"; setTextColor(android.graphics.Color.GRAY); setPadding(0, 20, 0, 0) })
        val modeBtn = Button(this).apply {
            id = View.generateViewId()
            background = getDrawable(R.drawable.bg_focusable)
            isFocusable = true
        }
        layout.addView(modeBtn)

        var useScript = profile.optBoolean("use_script", profile.optString("id") == "default" || profile.optJSONArray("selectors") == null || profile.optJSONArray("selectors")!!.length() == 0)

        // Custom Script UI
        val scriptLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 10, 0, 0)
        }
        val scriptInput = EditText(this).apply { 
            id = View.generateViewId()
            hint = "JavaScript Clicker"
            setText(profile.optString("script"))
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            setSelectAllOnFocus(false)
            ViewUtils.applySmartDpadFocus(this)
        }
        scriptLayout.addView(TextView(this).apply { text = "Script"; setTextColor(android.graphics.Color.GRAY) })
        scriptLayout.addView(scriptInput)
        layout.addView(scriptLayout)

        // Simple List UI
        val listLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 10, 0, 0)
        }
        val selectorsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val addSelectorBtn = Button(this).apply {
            text = "+ Add Element Selector"
            background = getDrawable(R.drawable.bg_focusable)
            isFocusable = true
            id = View.generateViewId()
        }
        listLayout.addView(TextView(this).apply { text = "Element Selectors"; setTextColor(android.graphics.Color.GRAY) })
        listLayout.addView(selectorsContainer)
        listLayout.addView(addSelectorBtn)
        layout.addView(listLayout)

        fun updateModeUI() {
            modeBtn.text = if (useScript) "Custom Script" else "Simple Element List"
            scriptLayout.visibility = if (useScript) View.VISIBLE else View.GONE
            listLayout.visibility = if (useScript) View.GONE else View.VISIBLE
        }

        modeBtn.setOnClickListener {
            useScript = !useScript
            updateModeUI()
        }

        val selectorList = mutableListOf<String>()
        profile.optJSONArray("selectors")?.let { arr ->
            for (i in 0 until arr.length()) selectorList.add(arr.getString(i))
        }

        fun refreshSelectorsUI() {
            selectorsContainer.removeAllViews()
            selectorList.forEachIndexed { idx, selector ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(0, 5, 0, 5)
                }
                val input = EditText(this).apply {
                    setText(selector)
                    hint = "e.g. .play-button"
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    setSelectAllOnFocus(false)
                    isSingleLine = true
                    ViewUtils.applySmartDpadFocus(this)
                    addTextChangedListener(object : android.text.TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                            selectorList[idx] = s.toString()
                        }
                        override fun afterTextChanged(s: android.text.Editable?) {}
                    })
                }
                val delBtn = ImageButton(this).apply {
                    setImageResource(R.drawable.ic_close)
                    background = getDrawable(R.drawable.bg_focusable)
                    isFocusable = true
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    setPadding(20, 20, 20, 20)
                    layoutParams = LinearLayout.LayoutParams(90, 90).apply { marginStart = 10 }
                    setOnClickListener {
                        selectorList.removeAt(idx)
                        refreshSelectorsUI()
                    }
                }
                row.addView(input)
                row.addView(delBtn)
                selectorsContainer.addView(row)
            }
        }

        addSelectorBtn.setOnClickListener {
            selectorList.add("")
            refreshSelectorsUI()
        }

        refreshSelectorsUI()
        updateModeUI()

        val scrollView = android.widget.ScrollView(this).apply {
            addView(layout)
        }

        AlertDialog.Builder(this)
            .setTitle("Edit Autoplay Profile")
            .setView(scrollView)
            .setPositiveButton("Save") { _, _ ->
                val newPatterns = patternsInput.text.toString().split(",").map { it.trim() }.filter { it.isNotEmpty() }
                profile.put("name", nameInput.text.toString())
                profile.put("urlPatterns", JSONArray(newPatterns))
                profile.put("use_script", useScript)
                
                val filteredSelectors = selectorList.filter { it.isNotEmpty() }
                profile.put("selectors", JSONArray(filteredSelectors))
                
                val newScript = scriptInput.text.toString()
                profile.put("script", newScript)

                if (profile.optString("id") == "default") {
                    if (useScript && newScript != DEFAULT_AUTOPLAY_SCRIPT.trimIndent()) {
                        profile.put("is_custom", true)
                    } else if (!useScript) {
                        profile.put("is_custom", true)
                    }
                }
                
                updateAutoplayProfile(profile, index)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addNewAutoplayProfile() {
        val newProfile = JSONObject().apply {
            put("id", java.util.UUID.randomUUID().toString())
            put("name", "New Profile")
            put("enabled", true)
            put("urlPatterns", JSONArray().put("*"))
            put("script", "")
        }
        editAutoplayProfile(newProfile, -1)
    }

    private fun updateAutoplayProfile(profile: JSONObject, index: Int, refresh: Boolean = true) {
        val prefs = getSharedPreferences("BrowserSettings", Context.MODE_PRIVATE)
        val array = JSONArray(prefs.getString("autoplay_profiles", "[]"))
        if (index >= 0) {
            array.put(index, profile)
        } else {
            array.put(profile)
        }
        prefs.edit().putString("autoplay_profiles", array.toString()).apply()
        if (refresh) refreshAutoplayProfiles()
    }

    private fun deleteAutoplayProfile(index: Int) {
        val prefs = getSharedPreferences("BrowserSettings", Context.MODE_PRIVATE)
        val array = JSONArray(prefs.getString("autoplay_profiles", "[]"))
        val newArray = JSONArray()
        for (i in 0 until array.length()) {
            if (i != index) newArray.put(array.get(i))
        }
        prefs.edit().putString("autoplay_profiles", newArray.toString()).apply()
        refreshAutoplayProfiles()
    }

    private fun refreshBlockedElements() {
        val container = findViewById<LinearLayout>(R.id.blocked_elements_container) ?: return
        
        if (container.hasFocus()) {
            activeCategory?.requestFocus()
        }

        container.removeAllViews()
        
        val prefs = getSharedPreferences("BrowserSettings", Context.MODE_PRIVATE)
        val blockedJson = prefs.getString("blocked_elements", "[]") ?: "[]"
        val array = JSONArray(blockedJson)

        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val isEnabled = obj.optBoolean("enabled", true)

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                background = getDrawable(R.drawable.bg_focusable)
                setPadding(30, 10, 20, 10)
                val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                params.setMargins(0, 0, 0, 10)
                layoutParams = params
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
                val btnParams = LinearLayout.LayoutParams(90, 90)
                btnParams.marginStart = 20
                layoutParams = btnParams
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
                val btnParams = LinearLayout.LayoutParams(90, 90)
                btnParams.marginStart = 10
                layoutParams = btnParams
                setOnClickListener {
                    AlertDialog.Builder(this@SettingsActivity)
                        .setTitle("Delete Rule")
                        .setMessage("Are you sure you want to delete '${obj.getString("name")}'?")
                        .setPositiveButton("Delete") { _, _ -> deleteBlockedElement(i) }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
                nextFocusLeftId = R.id.cat_blocked
            }

            row.addView(nameTxt)
            row.addView(toggle)
            row.addView(editBtn)
            row.addView(deleteBtn)
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
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 20)
        }
        
        val nameInput = EditText(this).apply { 
            id = View.generateViewId()
            hint = "Rule Name (e.g. Site Name)"
            setText(rule.optString("name"))
            setSelectAllOnFocus(false)
            isSingleLine = true
            ViewUtils.applySmartDpadFocus(this)
        }
        val patternsInput = EditText(this).apply { 
            id = View.generateViewId()
            hint = "URL Patterns (comma separated)"
            setText(rule.optJSONArray("urlPatterns")?.let { arr ->
                (0 until arr.length()).map { arr.getString(it) }.joinToString(", ")
            } ?: "")
            setSelectAllOnFocus(false)
            isSingleLine = true
            ViewUtils.applySmartDpadFocus(this)
        }
        val selectorsInput = EditText(this).apply { 
            id = View.generateViewId()
            hint = "CSS Selectors (one per line)"
            setText(rule.optJSONArray("selectors")?.let { arr ->
                (0 until arr.length()).map { arr.getString(it) }.joinToString("\n")
            } ?: "")
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            setSelectAllOnFocus(false)
            ViewUtils.applySmartDpadFocus(this)
        }

        nameInput.nextFocusDownId = patternsInput.id
        patternsInput.nextFocusUpId = nameInput.id
        patternsInput.nextFocusDownId = selectorsInput.id
        selectorsInput.nextFocusUpId = patternsInput.id

        layout.addView(TextView(this).apply { text = "Name"; setTextColor(android.graphics.Color.GRAY) })
        layout.addView(nameInput)
        layout.addView(TextView(this).apply { text = "URL Patterns (* for wildcards)"; setTextColor(android.graphics.Color.GRAY); setPadding(0, 20, 0, 0) })
        layout.addView(patternsInput)
        layout.addView(TextView(this).apply { text = "CSS Selectors (to hide)"; setTextColor(android.graphics.Color.GRAY); setPadding(0, 20, 0, 0) })
        layout.addView(selectorsInput)

        val scrollView = android.widget.ScrollView(this).apply {
            addView(layout)
        }

        AlertDialog.Builder(this)
            .setTitle("Edit Blocked Elements")
            .setView(scrollView)
            .setPositiveButton("Save") { _, _ ->
                val newPatterns = patternsInput.text.toString().split(",").map { it.trim() }.filter { it.isNotEmpty() }
                val newSelectors = selectorsInput.text.toString().split("\n").map { it.trim() }.filter { it.isNotEmpty() }
                rule.put("name", nameInput.text.toString())
                rule.put("urlPatterns", JSONArray(newPatterns))
                rule.put("selectors", JSONArray(newSelectors))
                
                updateBlockedElement(rule, index)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addNewBlockedElement() {
        val newRule = JSONObject().apply {
            put("id", java.util.UUID.randomUUID().toString())
            put("name", "New Rule")
            put("enabled", true)
            put("urlPatterns", JSONArray().put("*"))
            put("selectors", JSONArray())
        }
        editBlockedElement(newRule, -1)
    }

    private fun updateBlockedElement(rule: JSONObject, index: Int, refresh: Boolean = true) {
        val prefs = getSharedPreferences("BrowserSettings", Context.MODE_PRIVATE)
        val array = JSONArray(prefs.getString("blocked_elements", "[]"))
        if (index >= 0) {
            array.put(index, rule)
        } else {
            array.put(rule)
        }
        prefs.edit().putString("blocked_elements", array.toString()).apply()
        if (refresh) refreshBlockedElements()
    }

    private fun deleteBlockedElement(index: Int) {
        val prefs = getSharedPreferences("BrowserSettings", Context.MODE_PRIVATE)
        val array = JSONArray(prefs.getString("blocked_elements", "[]"))
        val newArray = JSONArray()
        for (i in 0 until array.length()) {
            if (i != index) newArray.put(array.get(i))
        }
        prefs.edit().putString("blocked_elements", newArray.toString()).apply()
        refreshBlockedElements()
    }
}
