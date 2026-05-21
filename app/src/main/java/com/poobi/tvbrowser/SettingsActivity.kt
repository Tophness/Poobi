package com.poobi.tvbrowser

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.launch

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val prefs = getSharedPreferences("BrowserSettings", Context.MODE_PRIVATE)

        val catGeneral = findViewById<Button>(R.id.cat_general)
        val catWeb = findViewById<Button>(R.id.cat_web)
        val catPlayer = findViewById<Button>(R.id.cat_player)
        val catInterface = findViewById<Button>(R.id.cat_interface)

        val panelGeneral = findViewById<LinearLayout>(R.id.panel_general)
        val panelWeb = findViewById<LinearLayout>(R.id.panel_web)
        val panelPlayer = findViewById<LinearLayout>(R.id.panel_player)
        val panelInterface = findViewById<LinearLayout>(R.id.panel_interface)

        fun showPanel(panel: View, category: Button) {
            panelGeneral.visibility = View.GONE
            panelWeb.visibility = View.GONE
            panelPlayer.visibility = View.GONE
            panelInterface.visibility = View.GONE
            panel.visibility = View.VISIBLE

            catGeneral.alpha = 0.5f
            catWeb.alpha = 0.5f
            catPlayer.alpha = 0.5f
            catInterface.alpha = 0.5f

            activeCategory?.isSelected = false
            activeCategory = category
            category.isSelected = true
            category.alpha = 1.0f
        }

        val focusListener = View.OnFocusChangeListener { v, hasFocus ->
            if (hasFocus && v is Button) {
                when(v.id) {
                    R.id.cat_general -> showPanel(panelGeneral, catGeneral)
                    R.id.cat_web -> showPanel(panelWeb, catWeb)
                    R.id.cat_player -> showPanel(panelPlayer, catPlayer)
                    R.id.cat_interface -> showPanel(panelInterface, catInterface)
                }
            }
        }

        catGeneral.onFocusChangeListener = focusListener
        catWeb.onFocusChangeListener = focusListener
        catPlayer.onFocusChangeListener = focusListener
        catInterface.onFocusChangeListener = focusListener

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
}