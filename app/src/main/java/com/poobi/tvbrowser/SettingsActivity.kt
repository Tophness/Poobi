package com.poobi.tvbrowser

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
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

        val panelGeneral = findViewById<LinearLayout>(R.id.panel_general)
        val panelWeb = findViewById<LinearLayout>(R.id.panel_web)
        val panelPlayer = findViewById<LinearLayout>(R.id.panel_player)
        val panelInterface = findViewById<LinearLayout>(R.id.panel_interface)
        val panelAutoplay = findViewById<LinearLayout>(R.id.panel_autoplay)

        fun showPanel(panel: View, category: Button) {
            if (activeCategory == category && panel.visibility == View.VISIBLE) return

            panelGeneral.visibility = View.GONE
            panelWeb.visibility = View.GONE
            panelPlayer.visibility = View.GONE
            panelInterface.visibility = View.GONE
            panelAutoplay.visibility = View.GONE
            panel.visibility = View.VISIBLE

            catGeneral.alpha = 0.5f
            catWeb.alpha = 0.5f
            catPlayer.alpha = 0.5f
            catInterface.alpha = 0.5f
            catAutoplay.alpha = 0.5f

            activeCategory?.isSelected = false
            activeCategory = category
            category.isSelected = true
            category.alpha = 1.0f

            if (panel == panelAutoplay) {
                refreshAutoplayProfiles()
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
                }
            }
        }

        catGeneral.onFocusChangeListener = focusListener
        catWeb.onFocusChangeListener = focusListener
        catPlayer.onFocusChangeListener = focusListener
        catInterface.onFocusChangeListener = focusListener
        catAutoplay.onFocusChangeListener = focusListener

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

            val editBtn = Button(this).apply {
                setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_settings, 0, 0, 0)
                background = getDrawable(R.drawable.bg_focusable)
                isFocusable = true
                id = View.generateViewId()
                setPadding(28, 0, 0, 0) // Center the icon (approx)
                val btnParams = LinearLayout.LayoutParams(80, 80)
                btnParams.marginStart = 20
                layoutParams = btnParams
                setOnClickListener { editAutoplayProfile(obj, i) }
                nextFocusLeftId = R.id.cat_autoplay
            }

            val deleteBtn = Button(this).apply {
                setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_close, 0, 0, 0)
                background = getDrawable(R.drawable.bg_focusable)
                isFocusable = true
                id = View.generateViewId()
                setPadding(28, 0, 0, 0) // Center the icon (approx)
                val btnParams = LinearLayout.LayoutParams(80, 80)
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
        val currentScript = profile.optString("script")
        val scriptInput = EditText(this).apply { 
            id = View.generateViewId()
            hint = "JavaScript Clicker"
            setText(currentScript)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            setSelectAllOnFocus(false)
            ViewUtils.applySmartDpadFocus(this)
        }

        nameInput.nextFocusDownId = patternsInput.id
        patternsInput.nextFocusUpId = nameInput.id
        patternsInput.nextFocusDownId = scriptInput.id
        scriptInput.nextFocusUpId = patternsInput.id

        layout.addView(TextView(this).apply { text = "Name"; setTextColor(android.graphics.Color.GRAY) })
        layout.addView(nameInput)
        layout.addView(TextView(this).apply { text = "URL Patterns (* for wildcards)"; setTextColor(android.graphics.Color.GRAY); setPadding(0, 20, 0, 0) })
        layout.addView(patternsInput)
        layout.addView(TextView(this).apply { text = "Script"; setTextColor(android.graphics.Color.GRAY); setPadding(0, 20, 0, 0) })
        layout.addView(scriptInput)

        val scrollView = android.widget.ScrollView(this).apply {
            addView(layout)
        }

        AlertDialog.Builder(this)
            .setTitle("Edit Autoplay Profile")
            .setView(scrollView)
            .setPositiveButton("Save") { _, _ ->
                val newPatterns = patternsInput.text.toString().split(",").map { it.trim() }.filter { it.isNotEmpty() }
                val newScript = scriptInput.text.toString()
                profile.put("name", nameInput.text.toString())
                profile.put("urlPatterns", JSONArray(newPatterns))
                
                if (profile.optString("id") == "default") {
                    if (newScript != DEFAULT_AUTOPLAY_SCRIPT.trimIndent()) {
                        profile.put("is_custom", true)
                    }
                }
                profile.put("script", newScript)
                
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
}
