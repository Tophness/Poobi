package com.poobi.tvbrowser

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.api.services.drive.DriveScopes
import com.poobi.tvbrowser.browser.AdBlockManager
import com.poobi.tvbrowser.shared.PythonDialogListener
import com.poobi.tvbrowser.shared.sync.DriveSyncManager
import com.poobi.tvbrowser.streams.SortCriteria
import com.poobi.tvbrowser.streams.SourceSorter
import com.poobi.tvbrowser.shared.TvFocusableBox
import com.poobi.tvbrowser.shared.TvInputField
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

fun Modifier.tvSettingsFocus(
    shape: RoundedCornerShape = RoundedCornerShape(8.dp)
): Modifier = composed {
    var isFocused by remember { mutableStateOf(false) }
    this
        .onFocusChanged { isFocused = it.isFocused }
        .border(
            width = if (isFocused) 2.dp else 0.dp,
            color = if (isFocused) Color(0xFF00BCD4) else Color.Transparent,
            shape = shape
        )
        .background(
            color = if (isFocused) Color(0xFF00BCD4).copy(alpha = 0.15f) else Color.Transparent,
            shape = shape
        )
}

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var driveSyncManager: DriveSyncManager
    private var googleSignInClient: GoogleSignInClient? = null

    // Google Sign-In Callbacks mapped to state
    private var onSignInRequested: (() -> Unit)? = null
    private var onSignOutRequested: ((onComplete: () -> Unit) -> Unit)? = null

    private var googleSignInStatusState = mutableStateOf("Not signed in.")
    private var isGoogleSignedInState = mutableStateOf(false)

    // Lifted Class-level settings variables
    // General Panel
    private var lightTheme by mutableStateOf(false)
    private var restoreOption by mutableStateOf(0)
    private var histLimit by mutableStateOf(20)
    private var historyIconOption by mutableStateOf(0)
    private var bookmarkIconOption by mutableStateOf(0)

    // Web Panel
    private var silentBlock by mutableStateOf(true)
    private var clickjack by mutableStateOf(true)
    private var adblockUrl by mutableStateOf("")

    // Player Panel
    private var extractPref by mutableStateOf(0)
    private var fallbackPref by mutableStateOf(0)
    private var embeddedSubs by mutableStateOf(true)
    private var upNextMode by mutableStateOf("Ask")
    private var upNextTime by mutableStateOf(20)
    private var upNextTimeStr by mutableStateOf("20")
    private var autoplayNext by mutableStateOf("Closest Source")
    private var episodeFocusMode by mutableStateOf(0)

    // Interface Panel
    private var scrollTopbar by mutableStateOf(true)
    private var navMode by mutableStateOf(0)
    private var scrapeTabOrder by mutableStateOf("Streams,Torrents")
    private var torrentLanguage by mutableStateOf("English")

    // Torrent Cache auto-clean settings
    private var torrentCacheCleanMode by mutableStateOf(0)
    private var torrentCacheCleanDays by mutableStateOf(0)
    private var torrentCacheCleanDaysStr by mutableStateOf("")
	private var torrentPrebufferPieces by mutableStateOf(1)

    // Streaming Panel
    private var timeoutMode by mutableStateOf("Both")
    private var globalTimeout by mutableStateOf(30)
    private var globalTimeoutStr by mutableStateOf("30")
    private var sourceTimeout by mutableStateOf(15)
    private var sourceTimeoutStr by mutableStateOf("15")
    private var enforceWhitelist by mutableStateOf(true)
    private val whitelistedHosts = mutableStateListOf<String>()

    // Subtitles Panel
    private var autoSubPref by mutableStateOf(0)
    private var countPref by mutableStateOf(1)
    private var waitPref by mutableStateOf(0)
    private var retentionDays by mutableStateOf(3)
    private var subsLanguages by mutableStateOf("English")
    private var subtitlesLimit by mutableStateOf(20)
    private var subtitlesLimitStr by mutableStateOf("20")
    private var opensubUser by mutableStateOf("")
    private var opensubPass by mutableStateOf("")
    private var opensubOrgUser by mutableStateOf("")
    private var opensubOrgPass by mutableStateOf("")
    private var subdlApikey by mutableStateOf("")
    private var subsourceApikey by mutableStateOf("")

    enum class Category {
        General, Web, Player, Interface, Streaming, Autoplay, Subtitles, Sorting, Blocked, Trakt, TMDb, Torrents, Sync
    }

    private val signInLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.getResult(com.google.android.gms.common.api.ApiException::class.java)
            if (account != null) {
                handleSignInSuccess(account)
            }
        } catch (e: Exception) {
            Log.e("Settings", "Google sign in failed", e)
            Toast.makeText(this, "Sign-in failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("BrowserSettings", Context.MODE_PRIVATE)
        driveSyncManager = DriveSyncManager(this)
        loadSettingsFromPrefs()

        lifecycleScope.launch(Dispatchers.IO) {
            initPython()
            prepopulatePreferences()
            withContext(Dispatchers.Main) {
                syncDefaultAutoplayProfile()
            }
        }
        
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(DriveScopes.DRIVE_APPDATA))
            .build()
        googleSignInClient = GoogleSignIn.getClient(this, gso)

        val account = GoogleSignIn.getLastSignedInAccount(this)
        if (account != null) {
            googleSignInStatusState.value = "Signed in as: ${account.email}"
            isGoogleSignedInState.value = true
            driveSyncManager.initService(account)
        }

        onSignInRequested = {
            googleSignInClient?.signInIntent?.let { intent ->
                signInLauncher.launch(intent)
            }
        }

        onSignOutRequested = { onComplete ->
            googleSignInClient?.signOut()?.addOnCompleteListener {
                onComplete()
            }
        }

        setContent {
            var selectedCategory by remember { mutableStateOf(Category.General) }

            Row(
                modifier = Modifier.fillMaxSize().background(Color(0xFF1A1A1D))
            ) {
                Column(
                    modifier = Modifier.width(260.dp).fillMaxHeight().background(Color(0xFF222225)).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Settings", color = Color(0xFF00BCD4), fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 16.dp))

                    LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(Category.values()) { category ->
                            SidebarCategoryItem(category = category, isSelected = selectedCategory == category, onSelect = { selectedCategory = category })
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = { saveAndExit() }, 
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)), 
                        modifier = Modifier.fillMaxWidth().tvSettingsFocus(RoundedCornerShape(20.dp))
                    ) {
                        Text("Save & Exit", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }

                Box(modifier = Modifier.fillMaxHeight().width(1.dp).background(Color(0xFF333338)))
                Box(modifier = Modifier.weight(1f).fillMaxHeight().padding(24.dp)) {
                    when (selectedCategory) {
                        Category.General -> GeneralPanel()
                        Category.Web -> WebPanel()
                        Category.Player -> PlayerPanel()
                        Category.Interface -> InterfacePanel()
                        Category.Streaming -> StreamingPanel()
                        Category.Autoplay -> AutoplayPanel()
                        Category.Subtitles -> SubtitlesPanel()
                        Category.Sorting -> SortingPanel()
                        Category.Blocked -> BlockedPanel()
                        Category.Trakt -> TraktPanel()
                        Category.TMDb -> TmdbPanel()
                        Category.Torrents -> TorrentPanel()
                        Category.Sync -> SyncPanel()
                    }
                }
            }
        }
    }

    private fun initPython() {
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }
    }

    private fun prepopulatePreferences() {
        if (!prefs.contains("whitelisted_hosts")) {
            try {
                val py = Python.getInstance()
                val main = py.getModule("main")
                val defaultWhitelist = main.callAttr("get_default_whitelist").toString()
                prefs.edit().putString("whitelisted_hosts", defaultWhitelist).apply()
            } catch (e: Exception) {
                Log.e("Settings", "Failed to prepopulate default whitelist", e)
            }
        }
        if (!prefs.contains("enabled_packs")) {
            try {
                val py = Python.getInstance()
                val main = py.getModule("main")
                val defaultPacks = main.callAttr("get_enabled_packs").toString()
                prefs.edit().putString("enabled_packs", defaultPacks).apply()
            } catch (e: Exception) {
                Log.e("Settings", "Failed to prepopulate default enabled packs", e)
            }
        }
    }

    private fun loadSettingsFromPrefs() {
        // General Panel
        lightTheme = prefs.getBoolean("light_theme", false)
        restoreOption = prefs.getInt("restore_tabs_pref", 0)
        histLimit = prefs.getInt("history_limit", 20)
        historyIconOption = prefs.getInt("history_icon_pref", 0)
        bookmarkIconOption = prefs.getInt("bookmark_icon_pref", 0)

        // Web Panel
        silentBlock = prefs.getBoolean("silent_popup_block", true)
        clickjack = prefs.getBoolean("clickjack_prevention", true)
        adblockUrl = prefs.getString("custom_adblock_url", "https://easylist.to/easylist/easylist.txt") ?: ""

        // Player Panel
        extractPref = prefs.getInt("extract_video_pref", 0)
        fallbackPref = prefs.getInt("exo_fallback_pref", 0)
        embeddedSubs = prefs.getBoolean("embedded_subs_enabled", true)
        upNextMode = prefs.getString("up_next_popup_pref", "Ask") ?: "Ask"
        upNextTime = prefs.getInt("up_next_time_pref", 20)
        upNextTimeStr = upNextTime.toString()
        autoplayNext = prefs.getString("autoplay_next_pref", "Closest Source") ?: "Closest Source"
        episodeFocusMode = prefs.getInt("episode_focus_mode", 0)

        // Interface Panel
        scrollTopbar = prefs.getBoolean("scroll_topbar_enabled", true)
        navMode = prefs.getInt("navigation_mode_pref", 0)
        scrapeTabOrder = prefs.getString("scrape_tab_order", "Streams,Torrents") ?: "Streams,Torrents"
        torrentLanguage = prefs.getString("torrent_language", "English") ?: "English"

        // Torrent Cache Auto-Clean Panel variables
        torrentCacheCleanMode = prefs.getInt("torrent_cache_clean_mode", 0)
        torrentCacheCleanDays = prefs.getInt("torrent_cache_clean_days", 0)
        torrentCacheCleanDaysStr = if (torrentCacheCleanDays > 0) torrentCacheCleanDays.toString() else ""
		torrentPrebufferPieces = prefs.getInt("torrent_prebuffer_pieces", 1)

        // Streaming Panel
        timeoutMode = prefs.getString("timeout_mode", "Both") ?: "Both"
        globalTimeout = prefs.getInt("global_timeout", 30)
        globalTimeoutStr = globalTimeout.toString()
        sourceTimeout = prefs.getInt("per_source_timeout", 15)
        sourceTimeoutStr = sourceTimeout.toString()
        enforceWhitelist = prefs.getBoolean("use_only_whitelisted_hosts", true)
        
        whitelistedHosts.clear()
        val hostsJson = prefs.getString("whitelisted_hosts", "[]") ?: "[]"
        val hostsArr = JSONArray(hostsJson)
        for (i in 0 until hostsArr.length()) {
            whitelistedHosts.add(hostsArr.getString(i))
        }

        // Subtitles Panel
        autoSubPref = prefs.getInt("auto_sub_pref", 0)
        countPref = prefs.getInt("auto_sub_count", 1)
        waitPref = prefs.getInt("auto_sub_wait_pref", 0)
        retentionDays = prefs.getInt("sub_retention_days", 3)
        subsLanguages = prefs.getString("subtitles_languages", "English") ?: "English"
        subtitlesLimit = prefs.getInt("subtitles_limit", 20)
        subtitlesLimitStr = subtitlesLimit.toString()
        opensubUser = prefs.getString("opensubtitles_username", "") ?: ""
        opensubPass = prefs.getString("opensubtitles_password", "") ?: ""
        opensubOrgUser = prefs.getString("opensubtitles_org_username", "") ?: ""
        opensubOrgPass = prefs.getString("opensubtitles_org_password", "") ?: ""
        subdlApikey = prefs.getString("subdl_apikey", "") ?: ""
        subsourceApikey = prefs.getString("subsource_apikey", "") ?: ""
    }

    private fun handleSignInSuccess(account: com.google.android.gms.auth.api.signin.GoogleSignInAccount) {
        lifecycleScope.launch {
            try {
                driveSyncManager.initService(account)
                val success = driveSyncManager.downloadSettings()
                if (success) {
                    syncToPython()
                    Toast.makeText(this@SettingsActivity, "Settings synced from Drive!", Toast.LENGTH_LONG).show()
                    recreate()
                } else {
                    driveSyncManager.uploadSettings()
                    isGoogleSignedInState.value = true
                    googleSignInStatusState.value = "Signed in as: ${account.email}"
                }
            } catch (e: Exception) {
                Toast.makeText(this@SettingsActivity, "Sync failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private suspend fun syncToPython() = withContext(Dispatchers.IO) {
        try {
            val py = Python.getInstance()
            val main = py.getModule("main")
            val cfg = JSONObject().apply {
                put("timeout_mode", prefs.getString("timeout_mode", "Both"))
                put("global_timeout", prefs.getInt("global_timeout", 30))
                put("per_source_timeout", prefs.getInt("per_source_timeout", 15))
                put("use_only_whitelisted_hosts", prefs.getBoolean("use_only_whitelisted_hosts", true))
                put("whitelisted_hosts", JSONArray(prefs.getString("whitelisted_hosts", "[]")))
                put("enabled_packs", JSONArray(prefs.getString("enabled_packs", "[]")))
                put("subtitles_languages", prefs.getString("subtitles_languages", "English"))
                put("subtitles_limit", prefs.getInt("subtitles_limit", 20))
                put("sub_retention_days", prefs.getInt("sub_retention_days", 3))
                put("up_next_popup_pref", prefs.getString("up_next_popup_pref", "Ask"))
                put("up_next_time_pref", prefs.getInt("up_next_time_pref", 20))
                put("autoplay_next_pref", prefs.getString("autoplay_next_pref", "Closest Source"))
                val serviceKeys = listOf("addic7ed", "bsplayer", "opensubtitles", "opensubtitles_org", "podnadpisi", "subdl", "subsource")
                serviceKeys.forEach { put("${it}_enabled", prefs.getBoolean("${it}_enabled", it != "bsplayer" && it != "opensubtitles_org" && it != "podnadpisi")) }
                put("opensubtitles_username", prefs.getString("opensubtitles_username", ""))
                put("opensubtitles_password", prefs.getString("opensubtitles_password", ""))
                put("opensubtitles_org_username", prefs.getString("opensubtitles_org_username", ""))
                put("opensubtitles_org_password", prefs.getString("opensubtitles_org_password", ""))
                put("subdl_apikey", prefs.getString("subdl_apikey", ""))
                put("subsource_apikey", prefs.getString("subsource_apikey", ""))
            }
            main.callAttr("set_config", cfg.toString())
        } catch (e: Exception) {
            Log.e("Settings", "Failed to sync to Python", e)
        }
    }

    private fun syncDefaultAutoplayProfile() {
        try {
            val array = JSONArray(prefs.getString("autoplay_profiles", "[]") ?: "[]")
            var defaultProfileIndex = -1
            for (i in 0 until array.length()) {
                if (array.getJSONObject(i).optString("id") == "default") {
                    defaultProfileIndex = i; break
                }
            }

            val defaultAutoplayScript = """
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

            if (defaultProfileIndex == -1) {
                val defaultProfile = JSONObject().apply {
                    put("id", "default")
                    put("name", "Default (Generic Search)")
                    put("enabled", true)
                    put("urlPatterns", JSONArray().put("*"))
                    put("script", defaultAutoplayScript)
                    put("use_script", true)
                    put("selectors", JSONArray())
                    put("is_custom", false)
                }
                array.put(defaultProfile)
                val listString = array.toString()
                lifecycleScope.launch(Dispatchers.IO) {
                    prefs.edit().putString("autoplay_profiles", listString).apply()
                }
            } else {
                val defaultProfile = array.getJSONObject(defaultProfileIndex)
                if (!defaultProfile.optBoolean("is_custom", false)) {
                    val currentScript = defaultProfile.optString("script")
                    if (currentScript != defaultAutoplayScript) {
                        defaultProfile.put("script", defaultAutoplayScript)
                        val listString = array.toString()
                        lifecycleScope.launch(Dispatchers.IO) {
                            prefs.edit().putString("autoplay_profiles", listString).apply()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("Settings", "Failed to sync default autoplay profile", e)
        }
    }

    @Composable
    fun SidebarCategoryItem(category: Category, isSelected: Boolean, onSelect: () -> Unit) {
        var isFocused by remember { mutableStateOf(false) }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .onFocusChanged { state ->
                    if (state.isFocused) {
                        onSelect()
                    }
                    isFocused = state.isFocused
                }
                .clickable { onSelect() }
                .focusable()
                .border(
                    width = if (isFocused) 2.dp else 0.dp,
                    color = if (isFocused) Color(0xFF00BCD4) else Color.Transparent,
                    shape = RoundedCornerShape(8.dp)
                )
                .background(
                    when {
                        isSelected -> Color(0xFF00BCD4)
                        isFocused -> Color(0xFF00BCD4).copy(alpha = 0.15f)
                        else -> Color.Transparent
                    }
                )
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Text(category.name, color = if (isSelected) Color.White else Color.LightGray, fontSize = 16.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
        }
    }

    @Composable
    fun GeneralPanel() {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            PanelHeader("General Settings")
            ToggleSettingRow("Use Light Theme", lightTheme) { lightTheme = it }
            DropdownSettingRow("Restore Session Mode", listOf("Ask to Restore", "Always Restore", "Never Restore"), restoreOption) { restoreOption = it }
            DropdownSettingRow("History Retention Limit", listOf("10 Entries", "20 Entries", "50 Entries", "Unlimited"), when (histLimit) { 10 -> 0; 20 -> 1; 50 -> 2; else -> 3 }) {
                histLimit = when (it) { 0 -> 10; 1 -> 20; 2 -> 50; else -> 0 }
            }
            DropdownSettingRow("History Icons Style", listOf("Snapshots (Thumbnail)", "Favicons"), historyIconOption) { historyIconOption = it }
            DropdownSettingRow("Bookmark Icons Style", listOf("Snapshots (Thumbnail)", "Favicons"), bookmarkIconOption) { bookmarkIconOption = it }
        }
    }

    @Composable
    fun WebPanel() {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            PanelHeader("Web & AdBlocker Settings")
            ToggleSettingRow("Block Popups Silently", silentBlock) { silentBlock = it }
            ToggleSettingRow("Prevent Clickjacking", clickjack) { clickjack = it }
            TvInputField(
                value = adblockUrl, 
                onValueChange = { adblockUrl = it }, 
                label = "Custom AdBlock Rules URL (EasyList Format)", 
                placeholder = "https://...",
                containerColor = Color(0xFF222225),
                imeAction = ImeAction.Done
            )
        }
    }

    @Composable
    fun PlayerPanel() {
        Column(modifier = Modifier.fillMaxSize().padding(bottom = 16.dp)) {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item { PanelHeader("Native Player Preferences") }
                item { DropdownSettingRow("Video Extraction Hijack", listOf("Ask to Play", "Always Play in Native Player", "Never Play (Use Site Browser)"), extractPref) { extractPref = it } }
                item { DropdownSettingRow("Fallback on Playback Error", listOf("Ask user to switch", "Always fall back to Browser", "Never fall back"), fallbackPref) { fallbackPref = it } }
                item { ToggleSettingRow("Embedded Player Subtitles", embeddedSubs) { embeddedSubs = it } }

                item { Spacer(modifier = Modifier.height(8.dp)) }
                item { PanelHeader("Binge Watching & Autoplay") }

                item {
                    DropdownSettingRow(
                        label = "Up Next Overlay Mode",
                        options = listOf("Ask to Play Next", "Always Play Automatically", "Never Show Overlay"),
                        selectedIndex = when(upNextMode) { "Always" -> 1; "Never" -> 2; else -> 0 }
                    ) { index ->
                        upNextMode = when(index) { 1 -> "Always"; 2 -> "Never"; else -> "Ask" }
                    }
                }

                item {
                    TvInputField(
                        value = upNextTimeStr,
                        onValueChange = { newValue ->
                            val filtered = newValue.filter { it.isDigit() }
                            upNextTimeStr = filtered
                            if (filtered.isNotEmpty()) {
                                upNextTime = filtered.toInt()
                            }
                        },
                        label = "Show Overlay Seconds Before End",
                        placeholder = "20",
                        containerColor = Color(0xFF222225),
                        imeAction = ImeAction.Done
                    )
                }

                item {
                    DropdownSettingRow(
                        label = "Source Selection for Next Episode",
                        options = listOf("Closest Source (Matching Host)", "Best Quality Source", "Ask (Show Scraper Results)"),
                        selectedIndex = when(autoplayNext) { "Best Source" -> 1; "Ask" -> 2; else -> 0 }
                    ) { index ->
                        autoplayNext = when(index) { 1 -> "Best Source"; 2 -> "Ask"; else -> "Closest Source" }
                    }
                }

                item {
                    DropdownSettingRow(
                        label = "Episode Auto-Focus Style",
                        options = listOf("Next Episode after Latest Watched", "First Unwatched Episode"),
                        selectedIndex = episodeFocusMode
                    ) { index ->
                        episodeFocusMode = index
                    }
                }
            }
        }
    }

    @Composable
    fun InterfacePanel() {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            PanelHeader("Interface Navigation Settings")
            ToggleSettingRow("Scroll up for Navigation Bar", scrollTopbar) { scrollTopbar = it }
            DropdownSettingRow("Default Pointer Navigation Mode", listOf("Simulated Pointer (Cursor)", "Physical target navigation (D-pad selection)"), navMode) { navMode = it }
            DropdownSettingRow("Scraper Result Tab Order", listOf("Streams, Torrents", "Torrents, Streams"), if (scrapeTabOrder == "Streams,Torrents") 0 else 1) { 
                scrapeTabOrder = if (it == 0) "Streams,Torrents" else "Torrents,Streams"
            }
        }
    }

    @Composable
    fun StreamingPanel() {
        var hostSearchQuery by remember { mutableStateOf("") }
        var providerHosts by remember { mutableStateOf(emptyList<String>()) }
        var resolveurlHosts by remember { mutableStateOf(emptyList<String>()) }
        var providerPacksList by remember { mutableStateOf(emptyList<String>()) }

        LaunchedEffect(Unit) {
            withContext(Dispatchers.IO) {
                try {
                    val py = Python.getInstance()
                    val hostsJson = py.getModule("main").callAttr("get_all_hosts").toString()
                    val hostsObj = JSONObject(hostsJson)
                    val pList = mutableListOf<String>()
                    val rList = mutableListOf<String>()

                    if (hostsObj.has("Provider Pack Hosts")) {
                        val arr = hostsObj.getJSONArray("Provider Pack Hosts")
                        for (i in 0 until arr.length()) pList.add(arr.getString(i))
                    }
                    if (hostsObj.has("ResolveURL Hosts")) {
                        val arr = hostsObj.getJSONArray("ResolveURL Hosts")
                        for (i in 0 until arr.length()) rList.add(arr.getString(i))
                    }

                    val packsArr = JSONArray(py.getModule("main").callAttr("get_enabled_packs").toString())
                    val packsList = (0 until packsArr.length()).map { packsArr.getString(it) }

                    withContext(Dispatchers.Main) {
                        providerHosts = pList
                        resolveurlHosts = rList
                        providerPacksList = packsList
                    }
                } catch (e: Exception) {}
            }
        }

        Column(modifier = Modifier.fillMaxSize()) {
            LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { PanelHeader("Streaming & Scraper Configurations") }

                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        DropdownSettingRow("Timeout Logic", listOf("Global Timeout Only", "Per-Source Only", "Both Engines Active"), when (timeoutMode) { "Global" -> 0; "Per-Source" -> 1; else -> 2 }, modifier = Modifier.weight(1f)) { 
                            timeoutMode = when (it) { 0 -> "Global"; 1 -> "Per-Source"; else -> "Both" }
                        }
                        ToggleSettingRow("Enforce Host Whitelist", enforceWhitelist, modifier = Modifier.weight(1f)) { 
                            enforceWhitelist = it
                        }
                    }
                }

                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Box(modifier = Modifier.weight(1f)) {
                            TvInputField(
                                value = globalTimeoutStr, 
                                onValueChange = { newValue ->
                                    val filtered = newValue.filter { it.isDigit() }
                                    globalTimeoutStr = filtered
                                    if (filtered.isNotEmpty()) {
                                        globalTimeout = filtered.toInt()
                                    }
                                }, 
                                label = "Global Engine Timeout (Seconds)",
                                placeholder = "30",
                                containerColor = Color(0xFF222225),
                                imeAction = ImeAction.Done
                            )
                        }
                        Box(modifier = Modifier.weight(1f)) {
                            TvInputField(
                                value = sourceTimeoutStr, 
                                onValueChange = { newValue ->
                                    val filtered = newValue.filter { it.isDigit() }
                                    sourceTimeoutStr = filtered
                                    if (filtered.isNotEmpty()) {
                                        sourceTimeout = filtered.toInt()
                                    }
                                }, 
                                label = "Per-Source Timeout Limit (Seconds)",
                                placeholder = "15",
                                containerColor = Color(0xFF222225),
                                imeAction = ImeAction.Done
                            )
                        }
                    }
                }

                if (providerPacksList.isNotEmpty()) {
                    item { Text("Provider Packages", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp) }
                    items(providerPacksList) { pack ->
                        var isChecked by remember { mutableStateOf(prefs.getBoolean("pack_$pack", true)) }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(40.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    isChecked = !isChecked
                                    lifecycleScope.launch(Dispatchers.IO) {
                                        prefs.edit().putBoolean("pack_$pack", isChecked).apply()
                                    }
                                }
                                .tvSettingsFocus()
                                .padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(pack, color = Color.LightGray, fontSize = 14.sp, modifier = Modifier.weight(1f))
                            Checkbox(
                                checked = isChecked, 
                                onCheckedChange = { checked -> 
                                    isChecked = checked
                                    lifecycleScope.launch(Dispatchers.IO) {
                                        prefs.edit().putBoolean("pack_$pack", checked).apply()
                                    }
                                }, 
                                modifier = Modifier.scale(0.85f)
                            )
                        }
                    }
                }

                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF222225))
                            .padding(12.dp)
                    ) {
                        Text("Host Whitelists", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        
                        Spacer(modifier = Modifier.height(6.dp))

                        TvInputField(
                            value = hostSearchQuery,
                            onValueChange = { hostSearchQuery = it },
                            placeholder = "Search whitelists...",
                            containerColor = Color(0xFF222225),
                            imeAction = ImeAction.Done,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                enabled = enforceWhitelist,
                                onClick = {
                                    val filteredP = providerHosts.filter { it.contains(hostSearchQuery, true) }
                                    val filteredR = resolveurlHosts.filter { it.contains(hostSearchQuery, true) }
                                    filteredP.forEach { if (!whitelistedHosts.contains(it)) whitelistedHosts.add(it) }
                                    filteredR.forEach { if (!whitelistedHosts.contains(it)) whitelistedHosts.add(it) }
                                },
                                modifier = Modifier.tvSettingsFocus(RoundedCornerShape(20.dp))
                            ) { Text("Select All", fontSize = 12.sp) }

                            Button(
                                enabled = enforceWhitelist,
                                onClick = {
                                    whitelistedHosts.clear()
                                },
                                modifier = Modifier.tvSettingsFocus(RoundedCornerShape(20.dp))
                            ) { Text("Clear All", fontSize = 12.sp) }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(modifier = Modifier.fillMaxWidth().height(240.dp)) {
                            // Column Left: Provider Whitelist (Extremely dense padding setup)
                            Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                                Text("Provider Pack Whitelist", color = Color(0xFF00BCD4), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                LazyColumn(modifier = Modifier.fillMaxSize().background(Color(0x0DFFFFFF))) {
                                    val filtered = providerHosts.filter { it.contains(hostSearchQuery, true) }
                                    items(filtered) { host ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(36.dp)
                                                .clip(RoundedCornerShape(6.dp))
                                                .clickable(enabled = enforceWhitelist) {
                                                    val isCurrentlyWhitelisted = whitelistedHosts.contains(host)
                                                    if (isCurrentlyWhitelisted) whitelistedHosts.remove(host) else whitelistedHosts.add(host)
                                                }
                                                .tvSettingsFocus()
                                                .padding(horizontal = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(host, color = if (enforceWhitelist) Color.White else Color.Gray, fontSize = 12.sp, modifier = Modifier.weight(1f))
                                            Checkbox(
                                                checked = whitelistedHosts.contains(host),
                                                enabled = enforceWhitelist,
                                                onCheckedChange = { checked ->
                                                    if (checked) whitelistedHosts.add(host) else whitelistedHosts.remove(host)
                                                },
                                                modifier = Modifier.scale(0.75f)
                                            )
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.width(10.dp))

                            // Column Right: ResolveURL Whitelist
                            Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                                Text("ResolveURL Whitelist", color = Color(0xFF00BCD4), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                LazyColumn(modifier = Modifier.fillMaxSize().background(Color(0x0DFFFFFF))) {
                                    val filtered = resolveurlHosts.filter { it.contains(hostSearchQuery, true) }
                                    items(filtered) { host ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(36.dp)
                                                .clip(RoundedCornerShape(6.dp))
                                                .clickable(enabled = enforceWhitelist) {
                                                    val isCurrentlyWhitelisted = whitelistedHosts.contains(host)
                                                    if (isCurrentlyWhitelisted) whitelistedHosts.remove(host) else whitelistedHosts.add(host)
                                                }
                                                .tvSettingsFocus()
                                                .padding(horizontal = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(host, color = if (enforceWhitelist) Color.White else Color.Gray, fontSize = 12.sp, modifier = Modifier.weight(1f))
                                            Checkbox(
                                                checked = whitelistedHosts.contains(host),
                                                enabled = enforceWhitelist,
                                                onCheckedChange = { checked ->
                                                    if (checked) whitelistedHosts.add(host) else whitelistedHosts.remove(host)
                                                },
                                                modifier = Modifier.scale(0.75f)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun AutoplayPanel() {
        var autoplayMaster by remember { mutableStateOf(prefs.getInt("video_trigger_pref", 1) == 0) }
        var profilesList by remember { mutableStateOf(JSONArray(prefs.getString("autoplay_profiles", "[]") ?: "[]")) }
        var showEditDialog by remember { mutableStateOf(false) }
        var editProfileIndex by remember { mutableStateOf(-1) }
        var editProfileData by remember { mutableStateOf(JSONObject()) }

        Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            PanelHeader("Autoplay Configuration Profiles")

            ToggleSettingRow("Auto-play Video globally", autoplayMaster) {
                autoplayMaster = it
                lifecycleScope.launch(Dispatchers.IO) {
                    prefs.edit().putInt("video_trigger_pref", if (it) 0 else 1).apply()
                }
            }

            LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed((0 until profilesList.length()).toList()) { index, _ ->
                    val profile = profilesList.getJSONObject(index)
                    val isEnabled = profile.optBoolean("enabled", true)
                    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF222225))) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(profile.optString("name"), color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            Checkbox(
                                checked = isEnabled, 
                                onCheckedChange = { checked -> 
                                    profile.put("enabled", checked)
                                    val listString = profilesList.toString()
                                    lifecycleScope.launch(Dispatchers.IO) {
                                        prefs.edit().putString("autoplay_profiles", listString).apply()
                                    }
                                    profilesList = JSONArray(listString) 
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(onClick = { editProfileIndex = index; editProfileData = JSONObject(profile.toString()); showEditDialog = true }, modifier = Modifier.tvSettingsFocus(RoundedCornerShape(20.dp))) { Text("Edit") }
                            Spacer(modifier = Modifier.width(4.dp))
                            Button(
                                onClick = { 
                                    val arr = JSONArray()
                                    for(i in 0 until profilesList.length()) { 
                                        if (i != index) arr.put(profilesList.getJSONObject(i)) 
                                    }
                                    val listString = arr.toString()
                                    lifecycleScope.launch(Dispatchers.IO) {
                                        prefs.edit().putString("autoplay_profiles", listString).apply()
                                    }
                                    profilesList = arr 
                                }, 
                                colors = ButtonDefaults.buttonColors(containerColor = Color.Red), 
                                modifier = Modifier.tvSettingsFocus(RoundedCornerShape(20.dp))
                            ) { Text("Delete") }
                        }
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(onClick = { editProfileIndex = -1; editProfileData = JSONObject().apply { put("id", UUID.randomUUID().toString()); put("name", "New Profile"); put("enabled", true); put("urlPatterns", JSONArray().put("*")); put("script", ""); put("use_script", true); put("selectors", JSONArray()) }; showEditDialog = true }, modifier = Modifier.tvSettingsFocus(RoundedCornerShape(20.dp))) { Text("Add New Autoplay Profile") }
                }
            }
        }

        if (showEditDialog) {
            var name by remember { mutableStateOf(editProfileData.optString("name")) }
            var patterns by remember { mutableStateOf(editProfileData.optJSONArray("urlPatterns")?.let { arr -> (0 until arr.length()).map { arr.getString(it) }.joinToString(",") } ?: "*") }
            var useScript by remember { mutableStateOf(editProfileData.optBoolean("use_script", true)) }
            var script by remember { mutableStateOf(editProfileData.optString("script")) }
            val selectors = remember { mutableStateListOf<String>().apply { addAll(editProfileData.optJSONArray("selectors")?.let { arr -> (0 until arr.length()).map { arr.getString(it) } } ?: emptyList()) } }

            AlertDialog(
                onDismissRequest = { showEditDialog = false },
                title = { Text("Edit Autoplay Profile") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        TvInputField(value = name, onValueChange = { name = it }, label = "Profile Name", placeholder = "e.g. My Autoplay", containerColor = Color(0xFF222225), imeAction = ImeAction.Done)
                        TvInputField(value = patterns, onValueChange = { patterns = it }, label = "URL Patterns", placeholder = "e.g. *host*", containerColor = Color(0xFF222225), imeAction = ImeAction.Done)

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Action Mode", color = Color.Gray, modifier = Modifier.weight(1f))
                            Button(onClick = { useScript = !useScript }, modifier = Modifier.tvSettingsFocus(RoundedCornerShape(20.dp))) {
                                Text(if (useScript) "Custom Script" else "Simple Element List")
                            }
                        }

                        if (useScript) {
                            TvInputField(value = script, onValueChange = { script = it }, label = "JavaScript Action", placeholder = "Javascript functions...", containerColor = Color(0xFF222225), imeAction = ImeAction.Done)
                        } else {
                            Text("Click Selectors", color = Color.Gray)
                            LazyColumn(modifier = Modifier.height(120.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                itemsIndexed(selectors.toList()) { idx, s ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(modifier = Modifier.weight(1f)) {
                                            TvInputField(value = s, onValueChange = { selectors[idx] = it }, placeholder = ".button-class", containerColor = Color(0xFF222225), imeAction = ImeAction.Done)
                                        }
                                        Button(onClick = { selectors.removeAt(idx) }, colors = ButtonDefaults.buttonColors(containerColor = Color.Red), modifier = Modifier.tvSettingsFocus(RoundedCornerShape(20.dp))) { Text("X") }
                                    }
                                }
                                item {
                                    Button(onClick = { selectors.add("") }, modifier = Modifier.tvSettingsFocus(RoundedCornerShape(20.dp))) { Text("+ Add Selector") }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        editProfileData.put("name", name)
                        editProfileData.put("urlPatterns", JSONArray(patterns.split(",").map { it.trim() }))
                        editProfileData.put("use_script", useScript)
                        editProfileData.put("script", script)
                        editProfileData.put("selectors", JSONArray(selectors.toList()))

                        if (editProfileIndex >= 0) profilesList.put(editProfileIndex, editProfileData) else profilesList.put(editProfileData)
                        val listString = profilesList.toString()
                        lifecycleScope.launch(Dispatchers.IO) {
                            prefs.edit().putString("autoplay_profiles", listString).apply()
                        }
                        profilesList = JSONArray(listString)
                        showEditDialog = false
                    }, modifier = Modifier.tvSettingsFocus(RoundedCornerShape(20.dp))) { Text("Save") }
                },
                dismissButton = { Button(onClick = { showEditDialog = false }, modifier = Modifier.tvSettingsFocus(RoundedCornerShape(20.dp))) { Text("Cancel") } }
            )
        }
    }

    @Composable
    fun SubtitlesPanel() {
        var showCustomSubCountDialog by remember { mutableStateOf(false) }
        var customSubCountInput by remember { mutableStateOf("") }

        val autoSubCountOptions = listOf("1", "2", "3", "4", "5", "Other", "All")
        val selectedSubCountIndex = when (countPref) {
            1 -> 0
            2 -> 1
            3 -> 2
            4 -> 3
            5 -> 4
            0 -> 6
            else -> 5
        }

        LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { PanelHeader("Subtitle Search Preferences") }

            item { DropdownSettingRow("Auto-search Behavior", listOf("Ask me to search", "Search Automatically", "Never Search"), autoSubPref) { 
                autoSubPref = it
            } }
            if (autoSubPref == 1) {
                item {
                    DropdownSettingRow("Subtitles To Download", autoSubCountOptions, selectedSubCountIndex) { index ->
                        if (index == 5) {
                            customSubCountInput = if (countPref !in 0..5) countPref.toString() else "10"
                            showCustomSubCountDialog = true
                        } else {
                            countPref = when (index) {
                                0 -> 1
                                1 -> 2
                                2 -> 3
                                3 -> 4
                                4 -> 5
                                6 -> 0
                                else -> 1
                            }
                        }
                    }
                }
                item { DropdownSettingRow("When Video Launches", listOf("Stop downloading immediately", "Ask if I want to wait", "Keep downloading in background"), waitPref) { 
                    waitPref = it
                } }
            }

            item { DropdownSettingRow("Subtitle File Retention", listOf("Keep Indefinitely", "1 Day", "3 Days", "7 Days", "14 Days", "30 Days"), when (retentionDays) { 0 -> 0; 1 -> 1; 3 -> 2; 7 -> 3; 14 -> 4; 30 -> 5; else -> 2 }) { 
                retentionDays = when (it) { 0 -> 0; 1 -> 1; 2 -> 3; 3 -> 7; 4 -> 14; 5 -> 30; else -> 3 }
            } }

            item { TvInputField(
                value = subsLanguages, 
                onValueChange = { subsLanguages = it }, 
                label = "Preferred Languages (Comma-separated ISO codes)", 
                placeholder = "English", 
                containerColor = Color(0xFF222225), 
                imeAction = ImeAction.Done
            ) }

            item { TvInputField(
                value = subtitlesLimitStr, 
                onValueChange = { newValue ->
                    val filtered = newValue.filter { it.isDigit() }
                    subtitlesLimitStr = filtered
                    if (filtered.isNotEmpty()) {
                        subtitlesLimit = filtered.toInt()
                    }
                }, 
                label = "Subtitles Search Limit", 
                placeholder = "20", 
                containerColor = Color(0xFF222225), 
                imeAction = ImeAction.Done
            ) }

            item { Spacer(modifier = Modifier.height(10.dp)) }
            item { Text("Subtitle Accounts & API Keys", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp) }

            item { TvInputField(
                value = opensubUser, 
                onValueChange = { opensubUser = it }, 
                label = "OpenSubtitles Username", 
                placeholder = "Username", 
                containerColor = Color(0xFF222225), 
                imeAction = ImeAction.Done
            ) }
            item { TvInputField(
                value = opensubPass, 
                onValueChange = { opensubPass = it }, 
                label = "OpenSubtitles Password", 
                placeholder = "Password", 
                isPassword = true, 
                containerColor = Color(0xFF222225), 
                imeAction = ImeAction.Done
            ) }

            item { TvInputField(
                value = opensubOrgUser, 
                onValueChange = { opensubOrgUser = it }, 
                label = "OpenSubtitles.org Username", 
                placeholder = "Username", 
                containerColor = Color(0xFF222225), 
                imeAction = ImeAction.Done
            ) }
            item { TvInputField(
                value = opensubOrgPass, 
                onValueChange = { opensubOrgPass = it }, 
                label = "OpenSubtitles.org Password", 
                placeholder = "Password", 
                isPassword = true, 
                containerColor = Color(0xFF222225), 
                imeAction = ImeAction.Done
            ) }

            item { TvInputField(
                value = subdlApikey, 
                onValueChange = { subdlApikey = it }, 
                label = "SubDL API Key", 
                placeholder = "API Key", 
                containerColor = Color(0xFF222225), 
                imeAction = ImeAction.Done
            ) }
            item { TvInputField(
                value = subsourceApikey, 
                onValueChange = { subsourceApikey = it }, 
                label = "SubSource API Key", 
                placeholder = "API Key", 
                containerColor = Color(0xFF222225), 
                imeAction = ImeAction.Done
            ) }

            item { Spacer(modifier = Modifier.height(10.dp)) }
            item { Text("Subtitle Scraper Engines", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp) }

            val serviceKeys = listOf("addic7ed", "bsplayer", "opensubtitles", "opensubtitles_org", "podnadpisi", "subdl", "subsource")
            items(serviceKeys) { key ->
                var isEnabled by remember { mutableStateOf(prefs.getBoolean("${key}_enabled", key != "bsplayer" && key != "opensubtitles_org" && key != "podnadpisi")) }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            isEnabled = !isEnabled
                            lifecycleScope.launch(Dispatchers.IO) {
                                prefs.edit().putBoolean("${key}_enabled", isEnabled).apply()
                            }
                        }
                        .tvSettingsFocus()
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(key.replace("_", " ").replaceFirstChar { it.uppercase() }, color = Color.LightGray, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Checkbox(
                        checked = isEnabled, 
                        onCheckedChange = { checked -> 
                            isEnabled = checked
                            lifecycleScope.launch(Dispatchers.IO) {
                                prefs.edit().putBoolean("${key}_enabled", checked).apply()
                            }
                        }, 
                        modifier = Modifier.scale(0.85f)
                    )
                }
            }
        }

        if (showCustomSubCountDialog) {
            AlertDialog(
                onDismissRequest = { showCustomSubCountDialog = false },
                title = { Text("Custom Subtitle Download Limit", color = Color.White) },
                containerColor = Color(0xFF222225),
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Enter numeric count limit:", color = Color.LightGray)
                        TvInputField(
                            value = customSubCountInput,
                            onValueChange = { customSubCountInput = it.filter { char -> char.isDigit() } },
                            placeholder = "e.g. 10",
                            containerColor = Color(0xFF222225),
                            imeAction = ImeAction.Done
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val intValue = customSubCountInput.toIntOrNull() ?: 5
                            countPref = intValue
                            showCustomSubCountDialog = false
                        },
                        modifier = Modifier.tvSettingsFocus(RoundedCornerShape(20.dp))
                    ) {
                        Text("OK", color = Color.White)
                    }
                },
                dismissButton = {
                    Button(
                        onClick = { showCustomSubCountDialog = false },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray),
                        modifier = Modifier.tvSettingsFocus(RoundedCornerShape(20.dp))
                    ) {
                        Text("Cancel", color = Color.White)
                    }
                }
            )
        }
    }

    @Composable
    fun SortingPanel() {
        var streamPriorities by remember {
            mutableStateOf(
                prefs.getString("sort_priorities", null)?.let {
                    val arr = JSONArray(it)
                    (0 until arr.length()).map { i -> arr.getString(i) }
                } ?: listOf("NATIVE", "DIRECT", "RESOLUTION", "SOURCE")
            )
        }

        var torrentPriorities by remember {
            mutableStateOf(
                prefs.getString("torrent_sort_priorities", null)?.let {
                    val arr = JSONArray(it)
                    (0 until arr.length()).map { i -> arr.getString(i) }
                } ?: listOf("LANGUAGE", "SIZE", "SEEDERS", "RESOLUTION")
            )
        }

        Column(modifier = Modifier.fillMaxSize()) {
            PanelHeader("Results Sorter Priorities")
            
            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Regular Streams Sorter",
                        color = Color(0xFF00BCD4),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )

                    streamPriorities.forEachIndexed { index, criteria ->
                        val displayName = when (criteria) {
                            "NATIVE" -> "Native Player Compatibility"
                            "DIRECT" -> "Direct Links"
                            "RESOLUTION" -> "Resolution"
                            "SOURCE" -> "Host / Source Name"
                            else -> criteria
                        }

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF222225))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = displayName,
                                    color = Color.White,
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 14.sp,
                                    modifier = Modifier.weight(1f)
                                )

                                if (index > 0) {
                                    TvFocusableBox(
                                        modifier = Modifier.size(32.dp),
                                        onClick = {
                                            val list = streamPriorities.toMutableList()
                                            val temp = list[index]
                                            list[index] = list[index - 1]
                                            list[index - 1] = temp
                                            streamPriorities = list
                                            val prioritiesStr = JSONArray(list).toString()
                                            prefs.edit().putString("sort_priorities", prioritiesStr).apply()
                                        }
                                    ) {
                                        Icon(
                                            painter = painterResource(id = R.drawable.ic_arrow_up),
                                            contentDescription = "Move Up",
                                            tint = Color.White,
                                            modifier = Modifier.fillMaxSize().padding(6.dp)
                                        )
                                    }
                                }
                                if (index < streamPriorities.size - 1) {
                                    Spacer(modifier = Modifier.width(4.dp))
                                    TvFocusableBox(
                                        modifier = Modifier.size(32.dp),
                                        onClick = {
                                            val list = streamPriorities.toMutableList()
                                            val temp = list[index]
                                            list[index] = list[index + 1]
                                            list[index + 1] = temp
                                            streamPriorities = list
                                            val prioritiesStr = JSONArray(list).toString()
                                            prefs.edit().putString("sort_priorities", prioritiesStr).apply()
                                        }
                                    ) {
                                        Icon(
                                            painter = painterResource(id = R.drawable.ic_arrow_down),
                                            contentDescription = "Move Down",
                                            tint = Color.White,
                                            modifier = Modifier.fillMaxSize().padding(6.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(1.dp)
                        .background(Color(0xFF333338))
                )

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Torrents Sorter",
                        color = Color(0xFF00BCD4),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )

                    torrentPriorities.forEachIndexed { index, criteria ->
                        val displayName = when (criteria) {
                            "LANGUAGE" -> "Language"
                            "SIZE" -> "File Size"
                            "SEEDERS" -> "Seeders"
                            "RESOLUTION" -> "Resolution"
                            else -> criteria
                        }

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF222225))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = displayName,
                                    color = Color.White,
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 14.sp,
                                    modifier = Modifier.weight(1f)
                                )

                                if (index > 0) {
                                    TvFocusableBox(
                                        modifier = Modifier.size(32.dp),
                                        onClick = {
                                            val list = torrentPriorities.toMutableList()
                                            val temp = list[index]
                                            list[index] = list[index - 1]
                                            list[index - 1] = temp
                                            torrentPriorities = list
                                            val prioritiesStr = JSONArray(list).toString()
                                            prefs.edit().putString("torrent_sort_priorities", prioritiesStr).apply()
                                        }
                                    ) {
                                        Icon(
                                            painter = painterResource(id = R.drawable.ic_arrow_up),
                                            contentDescription = "Move Up",
                                            tint = Color.White,
                                            modifier = Modifier.fillMaxSize().padding(6.dp)
                                        )
                                    }
                                }
                                if (index < torrentPriorities.size - 1) {
                                    Spacer(modifier = Modifier.width(4.dp))
                                    TvFocusableBox(
                                        modifier = Modifier.size(32.dp),
                                        onClick = {
                                            val list = torrentPriorities.toMutableList()
                                            val temp = list[index]
                                            list[index] = list[index + 1]
                                            list[index + 1] = temp
                                            torrentPriorities = list
                                            val prioritiesStr = JSONArray(list).toString()
                                            prefs.edit().putString("torrent_sort_priorities", prioritiesStr).apply()
                                        }
                                    ) {
                                        Icon(
                                            painter = painterResource(id = R.drawable.ic_arrow_down),
                                            contentDescription = "Move Down",
                                            tint = Color.White,
                                            modifier = Modifier.fillMaxSize().padding(6.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun BlockedPanel() {
        var rulesList by remember { mutableStateOf(JSONArray(prefs.getString("blocked_elements", "[]") ?: "[]")) }
        var showEditDialog by remember { mutableStateOf(false) }
        var editRuleIndex by remember { mutableStateOf(-1) }
        var editRuleData by remember { mutableStateOf(JSONObject()) }

        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            PanelHeader("Blocked Webpage Elements Rules")
            LazyColumn(modifier = Modifier.fillMaxWidth().height(260.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed((0 until rulesList.length()).toList()) { index, _ ->
                    val rule = rulesList.getJSONObject(index)
                    val isEnabled = rule.optBoolean("enabled", true)
                    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF222225))) {
                        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(rule.optString("name"), color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            Checkbox(
                                checked = isEnabled, 
                                onCheckedChange = { checked -> 
                                    rule.put("enabled", checked)
                                    val rulesStr = rulesList.toString()
                                    lifecycleScope.launch(Dispatchers.IO) {
                                        prefs.edit().putString("blocked_elements", rulesStr).apply()
                                    }
                                    rulesList = JSONArray(rulesStr) 
                                }
                            )
                            Button(onClick = { editRuleIndex = index; editRuleData = JSONObject(rule.toString()); showEditDialog = true }, modifier = Modifier.padding(start = 8.dp).tvSettingsFocus(RoundedCornerShape(20.dp))) { Text("Edit") }
                            Button(
                                onClick = {
                                    val arr = JSONArray()
                                    for (i in 0 until rulesList.length()) {
                                        if (i != index) arr.put(rulesList.getJSONObject(i))
                                    }
                                    val rulesStr = arr.toString()
                                    lifecycleScope.launch(Dispatchers.IO) {
                                        prefs.edit().putString("blocked_elements", rulesStr).apply()
                                    }
                                    rulesList = arr
                                },
                                modifier = Modifier
                                    .padding(start = 8.dp)
                                    .tvSettingsFocus(RoundedCornerShape(20.dp)),
                                colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
                            ) {
                                Text("Delete")
                            }
                        }
                    }
                }
            }
            Button(onClick = { editRuleIndex = -1; editRuleData = JSONObject().apply { put("id", UUID.randomUUID().toString()); put("name", "New CSS Rule"); put("enabled", true); put("urlPatterns", JSONArray().put("*")); put("selectors", JSONArray()) }; showEditDialog = true }, modifier = Modifier.tvSettingsFocus(RoundedCornerShape(20.dp))) { Text("Add New CSS Block Rule") }
        }

        if (showEditDialog) {
            var name by remember { mutableStateOf(editRuleData.optString("name")) }
            var patterns by remember { mutableStateOf(editRuleData.optJSONArray("urlPatterns")?.let { arr -> (0 until arr.length()).map { arr.getString(it) }.joinToString(",") } ?: "*") }
            var selectors by remember { mutableStateOf(editRuleData.optJSONArray("selectors")?.let { arr -> (0 until arr.length()).map { arr.getString(it) }.joinToString("\n") } ?: "") }

            AlertDialog(
                onDismissRequest = { showEditDialog = false },
                title = { Text("Edit Block Rule") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        TvInputField(value = name, onValueChange = { name = it }, label = "Rule Name", placeholder = "Rule Name", containerColor = Color(0xFF222225), imeAction = ImeAction.Done)
                        TvInputField(value = patterns, onValueChange = { patterns = it }, label = "URL Patterns", placeholder = "URL Patterns", containerColor = Color(0xFF222225), imeAction = ImeAction.Done)
                        TvInputField(value = selectors, onValueChange = { selectors = it }, label = "CSS Selectors (one per line)", placeholder = ".selector", containerColor = Color(0xFF222225), imeAction = ImeAction.Done)
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        editRuleData.put("name", name)
                        editRuleData.put("urlPatterns", JSONArray(patterns.split(",").map { it.trim() }))
                        editRuleData.put("selectors", JSONArray(selectors.split("\n").map { it.trim() }))
                        if (editRuleIndex >= 0) rulesList.put(editRuleIndex, editRuleData) else rulesList.put(editRuleData)
                        val rulesStr = rulesList.toString()
                        lifecycleScope.launch(Dispatchers.IO) {
                            prefs.edit().putString("blocked_elements", rulesStr).apply()
                        }
                        rulesList = JSONArray(rulesStr)
                        showEditDialog = false
                    }, modifier = Modifier.tvSettingsFocus(RoundedCornerShape(20.dp))) { Text("Save") }
                },
                dismissButton = { Button(onClick = { showEditDialog = false }, modifier = Modifier.tvSettingsFocus(RoundedCornerShape(20.dp))) { Text("Cancel") } }
            )
        }
    }

    @Composable
    fun TraktPanel() {
        var traktUser by remember { mutableStateOf("") }
        var showTraktAuthDialog by remember { mutableStateOf(false) }
        var traktUserCode by remember { mutableStateOf("") }
        var traktVerificationUrl by remember { mutableStateOf("") }
        var traktDeviceCode by remember { mutableStateOf("") }
        var traktInterval by remember { mutableStateOf(5L) }
        var traktExpires by remember { mutableStateOf(600L) }

        fun loadTraktUsername() {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val name = Python.getInstance().getModule("trakt.trakt_auth").callAttr("get_trakt_username").toString()
                    if (name.isNotEmpty() && name != "0") {
                        traktUser = name
                    }
                } catch (e: Exception) {}
            }
        }

        var traktPollingJob: Job? = null
        fun startTraktPolling() {
            traktPollingJob?.cancel()
            traktPollingJob = lifecycleScope.launch(Dispatchers.IO) {
                val startTime = System.currentTimeMillis()
                val py = Python.getInstance()
                val traktAuth = py.getModule("trakt.trakt_auth")
                while (System.currentTimeMillis() - startTime < traktExpires * 1000) {
                    if (!showTraktAuthDialog) break

                    val pollResultStr = traktAuth.callAttr("poll_for_token", traktDeviceCode).toString()
                    val pollResult = JSONObject(pollResultStr)

                    if (pollResult.getString("status") == "success") {
                        withContext(Dispatchers.Main) {
                            showTraktAuthDialog = false
                            Toast.makeText(this@SettingsActivity, "Trakt Authorized!", Toast.LENGTH_SHORT).show()
                            loadTraktUsername()
                        }
                        break
                    } else if (pollResult.getString("status") == "error") {
                        withContext(Dispatchers.Main) {
                            showTraktAuthDialog = false
                            Toast.makeText(this@SettingsActivity, "Auth Error: ${pollResult.optString("message")}", Toast.LENGTH_LONG).show()
                        }
                        break
                    }
                    delay(traktInterval * 1000 + 500)
                }
            }
        }

        fun startTraktAuthFlow() {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val py = Python.getInstance()
                    val traktAuth = py.getModule("trakt.trakt_auth")
                    val codeData = traktAuth.callAttr("get_device_code").toString()
                    val json = JSONObject(codeData)

                    val userCode = json.getString("user_code")
                    val verificationUrl = json.getString("verification_url")
                    val deviceCode = json.getString("device_code")
                    val interval = json.getLong("interval")
                    val expires = json.getLong("expires_in")

                    withContext(Dispatchers.Main) {
                        traktUserCode = userCode
                        traktVerificationUrl = verificationUrl
                        traktDeviceCode = deviceCode
                        traktInterval = interval
                        traktExpires = expires
                        showTraktAuthDialog = true
                        startTraktPolling()
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@SettingsActivity, "Error starting auth: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        LaunchedEffect(Unit) {
            loadTraktUsername()
        }

        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            PanelHeader("Trakt.tv Sync Integration")
            if (traktUser.isNotEmpty()) {
                Text("Authorized account: $traktUser", color = Color(0xFF4CAF50), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Button(onClick = {
                    lifecycleScope.launch(Dispatchers.IO) {
                        try {
                            Python.getInstance().getModule("trakt.trakt_auth").callAttr("logout_trakt")
                            withContext(Dispatchers.Main) {
                                traktUser = ""
                                Toast.makeText(this@SettingsActivity, "Logged out of Trakt", Toast.LENGTH_SHORT).show()
                            }
                        } catch (e: Exception) {}
                    }
                }, colors = ButtonDefaults.buttonColors(containerColor = Color.Red), modifier = Modifier.tvSettingsFocus(RoundedCornerShape(20.dp))) {
                    Text("Logout Trakt", color = Color.White)
                }
            } else {
                Text("Trakt account not linked.", color = Color.Gray)
                Button(onClick = { startTraktAuthFlow() }, modifier = Modifier.tvSettingsFocus(RoundedCornerShape(20.dp))) {
                    Text("Authorize Trakt Account")
                }
            }
        }

        if (showTraktAuthDialog) {
            AlertDialog(
                onDismissRequest = { showTraktAuthDialog = false },
                title = { Text("Authorize Trakt.tv", color = Color.White) },
                containerColor = Color(0xFF222225),
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("1. Visit verification page:", color = Color.LightGray)
                        Text(traktVerificationUrl, color = Color(0xFF00BCD4), fontWeight = FontWeight.Bold)
                        Text("2. Enter the following code on your device:", color = Color.LightGray)
                        Text(traktUserCode, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        Text("Waiting for authentication from Trakt...", color = Color.Gray)
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        val intent = Intent(this@SettingsActivity, MainActivity::class.java).apply {
                            putExtra("open_url", traktVerificationUrl)
                            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        }
                        startActivity(intent)
                    }, modifier = Modifier.tvSettingsFocus(RoundedCornerShape(20.dp))) {
                        Text("Open Link", color = Color.White)
                    }
                },
                dismissButton = {
                    Button(onClick = { showTraktAuthDialog = false }, colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray), modifier = Modifier.tvSettingsFocus(RoundedCornerShape(20.dp))) {
                        Text("Cancel", color = Color.White)
                    }
                }
            )
        }
    }

    @Composable
    fun TmdbPanel() {
        var usernameVal by remember { mutableStateOf("") }
        var passwordVal by remember { mutableStateOf("") }
        var isAuthorized by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) {
            withContext(Dispatchers.IO) {
                try {
                    val py = Python.getInstance()
                    val control = py.getModule("modules.control")
                    val u = control.callAttr("setting", "tmdb.user").toString()
                    val p = control.callAttr("setting", "tmdb.pass").toString()
                    val session = control.callAttr("setting", "tmdb.session").toString()
                    if (u != "0" && u.isNotEmpty()) usernameVal = u
                    if (p != "0" && p.isNotEmpty()) passwordVal = p
                    if (session.isNotEmpty() && session != "0") isAuthorized = true
                } catch (e: Exception) {}
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            PanelHeader("TMDb Account Credentials")

            if (isAuthorized) {
                Text("Authorized TMDb Session Active", color = Color(0xFF4CAF50), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Button(onClick = {
                    lifecycleScope.launch(Dispatchers.IO) {
                        try {
                            val py = Python.getInstance()
                            py.getModule("tmdb.tmdb_utils").callAttr("delete_session")
                            withContext(Dispatchers.Main) {
                                isAuthorized = false
                                Toast.makeText(this@SettingsActivity, "Logged out of TMDb", Toast.LENGTH_SHORT).show()
                            }
                        } catch (e: Exception) {}
                    }
                }, colors = ButtonDefaults.buttonColors(containerColor = Color.Red), modifier = Modifier.tvSettingsFocus(RoundedCornerShape(20.dp))) {
                    Text("Logout", color = Color.White)
                }
            } else {
                TvInputField(value = usernameVal, onValueChange = { usernameVal = it }, label = "Username", placeholder = "Username", containerColor = Color(0xFF222225), imeAction = ImeAction.Done)
                TvInputField(value = passwordVal, onValueChange = { passwordVal = it }, label = "Password", placeholder = "Password", isPassword = true, containerColor = Color(0xFF222225), imeAction = ImeAction.Done)

                Button(onClick = {
                    lifecycleScope.launch(Dispatchers.IO) {
                        try {
                            val py = Python.getInstance(); val control = py.getModule("modules.control")
                            control.callAttr("setSetting", "tmdb.user", usernameVal); control.callAttr("setSetting", "tmdb.pass", passwordVal)

                            control.callAttr("set_dialog_listener", object : PythonDialogListener {
                                override fun infoDialog(message: String, heading: String, sound: Boolean, icon: String) {
                                    runOnUiThread { Toast.makeText(this@SettingsActivity, "$heading: $message", Toast.LENGTH_LONG).show() }
                                }
                                override fun okDialog(message: String, heading: String): Boolean {
                                    val future = CompletableDeferred<Boolean>()
                                    runOnUiThread {
                                        AlertDialog.Builder(this@SettingsActivity).setTitle(heading).setMessage(message)
                                            .setPositiveButton("OK") { _, _ -> future.complete(true) }.setOnCancelListener { future.complete(true) }.show()
                                    }
                                    return runBlocking { future.await() }
                                }
                                override fun yesnoDialog(message: String, heading: String, nolabel: String, yeslabel: String): Boolean {
                                    val future = CompletableDeferred<Boolean>()
                                    runOnUiThread {
                                        AlertDialog.Builder(this@SettingsActivity).setTitle(heading).setMessage(message)
                                            .setPositiveButton(yeslabel.ifEmpty { "Yes" }) { _, _ -> future.complete(true) }
                                            .setNegativeButton(nolabel.ifEmpty { "No" }) { _, _ -> future.complete(false) }.setOnCancelListener { future.complete(false) }.show()
                                    }
                                    return runBlocking { future.await() }
                                }
                            })

                            py.getModule("tmdb.tmdb_utils").callAttr("authTMDb")
                            withContext(Dispatchers.Main) {
                                isAuthorized = true
                                Toast.makeText(this@SettingsActivity, "Credentials verified successfully", Toast.LENGTH_SHORT).show()
                            }
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(this@SettingsActivity, "TMDb Authentication failed: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }, modifier = Modifier.tvSettingsFocus(RoundedCornerShape(20.dp))) { Text("Login") }
            }
        }
    }

	@Composable
	fun TorrentPanel() {
		var cacheSize by remember { mutableStateOf(0L) }
		var cacheItems by remember { mutableStateOf(emptyList<com.poobi.tvbrowser.torrent.TorrentCacheItem>()) }
		var showDaysPickerDialog by remember { mutableStateOf(false) }

		fun refreshCache() {
			val server = com.poobi.tvbrowser.torrent.TorrentStreamServer.getInstance(this@SettingsActivity)
			cacheSize = server.getCacheSize()
			cacheItems = server.getCacheItems()
		}

		LaunchedEffect(Unit) {
			refreshCache()
		}

		Column(modifier = Modifier.fillMaxSize()) {
			LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
				item { PanelHeader("Torrent Streaming Configuration") }

				item {
					DropdownSettingRow(
						label = "Playback Pre-buffer Size",
						options = listOf("1 Piece (Fastest / Default)", "2 Pieces", "4 Pieces (Standard)", "8 Pieces (Most Stable)"),
						selectedIndex = when (torrentPrebufferPieces) {
							1 -> 0
							2 -> 1
							4 -> 2
							8 -> 3
							else -> 0
						}
					) { index ->
						val pieces = when (index) {
							0 -> 1
							1 -> 2
							2 -> 4
							3 -> 8
							else -> 1
						}
						torrentPrebufferPieces = pieces
						lifecycleScope.launch(Dispatchers.IO) {
							prefs.edit().putInt("torrent_prebuffer_pieces", pieces).apply()
						}
					}
				}

				item {
					DropdownSettingRow(
						label = "Preferred Torrent Language",
						options = listOf("English", "Russian", "Spanish", "Portuguese", "Italian", "French", "German", "Polish", "Hindi"),
						selectedIndex = listOf("English", "Russian", "Spanish", "Portuguese", "Italian", "French", "German", "Polish", "Hindi").indexOf(torrentLanguage).coerceAtLeast(0)
					) { index ->
						torrentLanguage = listOf("English", "Russian", "Spanish", "Portuguese", "Italian", "French", "German", "Polish", "Hindi")[index]
						lifecycleScope.launch(Dispatchers.IO) {
							prefs.edit().putString("torrent_language", torrentLanguage).apply()
						}
					}
				}

				item {
					DropdownSettingRow(
						label = "Torrent Cache Auto-Clean Mode",
						options = listOf("Manual Only", "Every time a new torrent starts", "Every time a video is exited", "Every X days"),
						selectedIndex = torrentCacheCleanMode
					) { index ->
						torrentCacheCleanMode = index
						if (index == 3) {
							showDaysPickerDialog = true
						}
					}
				}

				if (torrentCacheCleanMode == 3) {
					item {
						Card(
							modifier = Modifier.fillMaxWidth().clickable { showDaysPickerDialog = true }.tvSettingsFocus(),
							colors = CardDefaults.cardColors(containerColor = Color(0xFF222225))
						) {
							Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
								Column(modifier = Modifier.weight(1f)) {
									Text("Clean Interval", color = Color.LightGray, fontSize = 12.sp)
									Text(if (torrentCacheCleanDays > 0) "Every $torrentCacheCleanDays days" else "Disabled / Click to set", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
								}
							}
						}
					}
				}

				item { Spacer(modifier = Modifier.height(8.dp)) }
				item { Text("Storage Cache Manager", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp) }

				item {
					Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF222225))) {
						Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
							Column(modifier = Modifier.weight(1f)) {
								Text("Total Cached P2P Storage", color = Color.LightGray, fontSize = 12.sp)
								Text("%.2f MB".format(cacheSize / (1024f * 1024f)), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
							}
							Button(
								onClick = {
									val server = com.poobi.tvbrowser.torrent.TorrentStreamServer.getInstance(this@SettingsActivity)
									server.clearAllCache()
									refreshCache()
								},
								colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
								modifier = Modifier.tvSettingsFocus(RoundedCornerShape(20.dp))
							) {
								Text("Clear All")
							}
						}
					}
				}

				if (cacheItems.isNotEmpty()) {
					item { Text("Cached Directory Sub-folders", color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp)) }
					items(cacheItems) { cacheItem ->
						Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = Color(0x0DFFFFFF))) {
							Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
								Column(modifier = Modifier.weight(1f)) {
									Text(cacheItem.name, color = Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
									Text("%.2f MB".format(cacheItem.size / (1024f * 1024f)), color = Color.Gray, fontSize = 11.sp)
								}
								Button(
									onClick = {
										val server = com.poobi.tvbrowser.torrent.TorrentStreamServer.getInstance(this@SettingsActivity)
										server.deleteCacheItem(cacheItem.path)
										refreshCache()
									},
									colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray),
									modifier = Modifier.tvSettingsFocus(RoundedCornerShape(20.dp))
								) {
									Text("Clear", fontSize = 11.sp)
								}
							}
						}
					}
				}
			}
		}

		if (showDaysPickerDialog) {
			AlertDialog(
				onDismissRequest = { showDaysPickerDialog = false },
				title = { Text("Auto-Clean Interval", color = Color.White) },
				containerColor = Color(0xFF222225),
				text = {
					Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
						Text("Enter number of days between cache cleans:", color = Color.LightGray)
						TvInputField(
							value = torrentCacheCleanDaysStr,
							onValueChange = { newValue ->
								val filtered = newValue.filter { it.isDigit() }
								torrentCacheCleanDaysStr = filtered
								if (filtered.isNotEmpty()) {
									torrentCacheCleanDays = filtered.toInt()
								} else {
									torrentCacheCleanDays = 0
								}
							},
							placeholder = "e.g. 7",
							containerColor = Color(0xFF222225),
							imeAction = ImeAction.Done
						)
					}
				},
				confirmButton = {
					Button(
						onClick = {
							showDaysPickerDialog = false
						},
						modifier = Modifier.tvSettingsFocus(RoundedCornerShape(20.dp))
					) {
						Text("OK", color = Color.White)
					}
				},
				dismissButton = {
					Button(
						onClick = {
							showDaysPickerDialog = false
						},
						colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray),
						modifier = Modifier.tvSettingsFocus(RoundedCornerShape(20.dp))
					) {
						Text("Cancel", color = Color.White)
					}
				}
			)
		}
	}

    @Composable
    fun SyncPanel() {
        val syncStatus by remember { googleSignInStatusState }
        val isSignedState by remember { isGoogleSignedInState }
        var isProgressVisible by remember { mutableStateOf(false) }

        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            PanelHeader("Google Drive Sync")
            Text("Sync your settings, bookmarks, and history across devices using your own Google Drive storage (App Data folder). This is private and only accessible by this app.", color = Color.Gray)

            Text("Status: $syncStatus", color = Color.White, fontWeight = FontWeight.Bold)

            if (isSignedState) {
                Button(
                    onClick = {
                        onSignOutRequested?.invoke {
                            googleSignInStatusState.value = "Not signed in."
                            isGoogleSignedInState.value = false
                            Toast.makeText(this@SettingsActivity, "Signed out successfully", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
                    modifier = Modifier.fillMaxWidth().tvSettingsFocus(RoundedCornerShape(20.dp))
                ) {
                    Text("Logout", color = Color.White)
                }
            } else {
                Button(
                    onClick = {
                        onSignInRequested?.invoke()
                    },
                    modifier = Modifier.fillMaxWidth().tvSettingsFocus(RoundedCornerShape(20.dp))
                ) {
                    Text("Sign in with Google")
                }
            }

            if (isProgressVisible) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
            }

            Button(
                onClick = {
                    val account = GoogleSignIn.getLastSignedInAccount(this@SettingsActivity)
                    if (account != null) {
                        lifecycleScope.launch {
                            isProgressVisible = true
                            driveSyncManager.initService(account)
                            val success = driveSyncManager.uploadSettings()
                            isProgressVisible = false
                            Toast.makeText(this@SettingsActivity, if (success) "Force sync uploaded successfully!" else "Sync failed.", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        Toast.makeText(this@SettingsActivity, "Please sign in to Google first", Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.fillMaxWidth().tvSettingsFocus(RoundedCornerShape(20.dp))
            ) {
                Text("Force Sync Now")
            }
        }
    }

    @Composable
    fun PanelHeader(text: String) { Text(text, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp)) }

    @Composable
    fun ToggleSettingRow(label: String, checked: Boolean, modifier: Modifier = Modifier, onCheckedChange: (Boolean) -> Unit) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .height(48.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable { onCheckedChange(!checked) }
                .tvSettingsFocus()
                .padding(horizontal = 8.dp), 
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, color = Color.LightGray, fontSize = 16.sp, modifier = Modifier.weight(1f))
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun DropdownSettingRow(label: String, options: List<String>, selectedIndex: Int, modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
        var showDialog by remember { mutableStateOf(false) }
        Row(modifier = modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = Color.LightGray, fontSize = 16.sp, modifier = Modifier.weight(1f))

            TvFocusableBox(
                modifier = Modifier
                    .width(220.dp)
                    .height(50.dp)
                    .tvSettingsFocus(),
                onClick = { showDialog = true }
            ) {
                Box(contentAlignment = Alignment.CenterStart, modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp)) {
                    Text(options.getOrNull(selectedIndex) ?: "", color = Color.White, fontSize = 14.sp)
                }
            }
        }

        if (showDialog) {
            AlertDialog(
                onDismissRequest = { showDialog = false },
                title = { Text(label, color = Color.White) },
                containerColor = Color(0xFF222225),
                text = {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        itemsIndexed(options) { index, option ->
                            TvFocusableBox(
                                modifier = Modifier.fillMaxWidth().height(48.dp).tvSettingsFocus(),
                                onClick = { onSelect(index); showDialog = false }
                            ) {
                                Box(contentAlignment = Alignment.CenterStart, modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp)) {
                                    Text(option, color = Color.White)
                                }
                            }
                        }
                    }
                },
                confirmButton = {}
            )
        }
    }

    private fun saveAndExit() {
        val newAdblockUrl = prefs.getString("custom_adblock_url", "https://easylist.to/easylist/easylist.txt") ?: ""

        // Use a persistent CoroutineScope bound to global Dispatchers rather than the Activity lifecycle.
        // This ensures saving completes even if the activity's lifecyclescope gets cancelled prematurely.
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val py = Python.getInstance()
                val main = py.getModule("main")

                // Re-compile enabled_packs dynamically based on individual pack_ boolean flags from memory
                val allPacksJson = main.callAttr("get_enabled_packs").toString()
                val allPacksArr = JSONArray(allPacksJson)
                val enabledPacksList = mutableListOf<String>()
                for (i in 0 until allPacksArr.length()) {
                    val pack = allPacksArr.getString(i)
                    if (prefs.getBoolean("pack_$pack", true)) {
                        enabledPacksList.add(pack)
                    }
                }

                // Write ALL states to SharedPreferences at once
                prefs.edit().apply {
                    // General
                    putBoolean("light_theme", lightTheme)
                    putInt("restore_tabs_pref", restoreOption)
                    putInt("history_limit", histLimit)
                    putInt("history_icon_pref", historyIconOption)
                    putInt("bookmark_icon_pref", bookmarkIconOption)

                    // Web
                    putBoolean("silent_popup_block", silentBlock)
                    putBoolean("clickjack_prevention", clickjack)
                    putString("custom_adblock_url", adblockUrl)

                    // Player
                    putInt("extract_video_pref", extractPref)
                    putInt("exo_fallback_pref", fallbackPref)
                    putBoolean("embedded_subs_enabled", embeddedSubs)
                    putString("up_next_popup_pref", upNextMode)
                    putInt("up_next_time_pref", upNextTime)
                    putString("autoplay_next_pref", autoplayNext)
                    putInt("episode_focus_mode", episodeFocusMode)

                    // Interface
                    putBoolean("scroll_topbar_enabled", scrollTopbar)
                    putInt("navigation_mode_pref", navMode)
                    putString("scrape_tab_order", scrapeTabOrder)
                    putString("torrent_language", torrentLanguage)

                    // Torrent Auto-Clean
                    putInt("torrent_cache_clean_mode", torrentCacheCleanMode)
                    putInt("torrent_cache_clean_days", torrentCacheCleanDays)
                    putInt("torrent_prebuffer_pieces", torrentPrebufferPieces)

                    // Streaming
                    putString("timeout_mode", timeoutMode)
                    putInt("global_timeout", globalTimeout)
                    putInt("per_source_timeout", sourceTimeout)
                    putBoolean("use_only_whitelisted_hosts", enforceWhitelist)
                    putString("enabled_packs", JSONArray(enabledPacksList).toString())
                    putString("whitelisted_hosts", JSONArray(whitelistedHosts).toString())

                    // Subtitles
                    putInt("auto_sub_pref", autoSubPref)
                    putInt("auto_sub_count", countPref)
                    putInt("auto_sub_wait_pref", waitPref)
                    putInt("sub_retention_days", retentionDays)
                    putString("subtitles_languages", subsLanguages)
                    putInt("subtitles_limit", subtitlesLimit)
                    putString("opensubtitles_username", opensubUser)
                    putString("opensubtitles_password", opensubPass)
                    putString("opensubtitles_org_username", opensubOrgUser)
                    putString("opensubtitles_org_password", opensubOrgPass)
                    putString("subdl_apikey", subdlApikey)
                    putString("subsource_apikey", subsourceApikey)
                }.apply()

                // Launch EasyList adblock download in a detached, separate coroutine.
                // This prevents rules downloading from blocking the immediate Save & Exit workflow.
                val appContext = applicationContext
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        AdBlockManager.updateRules(appContext, newAdblockUrl)
                    } catch (e: Exception) {
                        Log.e("Settings", "Failed background rules download update", e)
                    }
                }

                // Sync whole configuration to Python
                val cfg = JSONObject().apply {
                    put("timeout_mode", timeoutMode)
                    put("global_timeout", globalTimeout)
                    put("per_source_timeout", sourceTimeout)
                    put("use_only_whitelisted_hosts", enforceWhitelist)
                    put("whitelisted_hosts", JSONArray(whitelistedHosts))
                    put("enabled_packs", JSONArray(enabledPacksList))
                    put("subtitles_languages", subsLanguages)
                    put("subtitles_limit", subtitlesLimit)
                    put("sub_retention_days", retentionDays)
                    put("up_next_popup_pref", upNextMode)
                    put("up_next_time_pref", upNextTime)
                    put("autoplay_next_pref", autoplayNext)
                    put("scrape_tab_order", scrapeTabOrder)
					put("torrent_language", torrentLanguage)

                    val serviceKeys = listOf("addic7ed", "bsplayer", "opensubtitles", "opensubtitles_org", "podnadpisi", "subdl", "subsource")
                    serviceKeys.forEach { key ->
                        put("${key}_enabled", prefs.getBoolean("${key}_enabled", key != "bsplayer" && key != "opensubtitles_org" && key != "podnadpisi"))
                    }

                    put("opensubtitles_username", opensubUser)
                    put("opensubtitles_password", opensubPass)
                    put("opensubtitles_org_username", opensubOrgUser)
                    put("opensubtitles_org_password", opensubOrgPass)
                    put("subdl_apikey", subdlApikey)
                    put("subsource_apikey", subsourceApikey)
                }
                main.callAttr("set_config", cfg.toString())

                // Cloud Drive Sync is launched in an independent background coroutine
                // to prevent network delays from delaying the UI transition.
                val account = GoogleSignIn.getLastSignedInAccount(this@SettingsActivity)
                if (account != null) {
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            driveSyncManager.initService(account)
                            driveSyncManager.uploadSettings()
                        } catch (e: Exception) {
                            Log.e("Settings", "Failed background Google Drive upload", e)
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    Toast.makeText(this@SettingsActivity, "Settings Saved Successfully", Toast.LENGTH_SHORT).show()
                    finish()
                }
            } catch (e: Exception) {
                Log.e("Settings", "Failed during save configuration actions", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@SettingsActivity, "Error saving settings", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}