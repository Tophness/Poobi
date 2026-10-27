package com.poobi.tvbrowser

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import android.Manifest
import android.view.KeyEvent
import androidx.lifecycle.lifecycleScope
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.api.services.drive.DriveScopes
import com.poobi.tvbrowser.browser.AdBlockManager
import com.poobi.tvbrowser.shared.HardwareInputManager
import com.poobi.tvbrowser.shared.KeyShortcut
import com.poobi.tvbrowser.shared.PythonDialogListener
import com.poobi.tvbrowser.shared.sync.DriveSyncManager
import com.poobi.tvbrowser.shared.TvFocusableBox
import com.poobi.tvbrowser.shared.TvInputField
import com.poobi.tvbrowser.shared.update.UpdateManager
import com.poobi.tvbrowser.shared.update.UpdateDialog
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

class SettingsActivity : AppCompatActivity() {

    private var pendingStorageAction: (() -> Unit)? = null

    private lateinit var prefs: SharedPreferences
    private lateinit var driveSyncManager: DriveSyncManager
    private var googleSignInClient: GoogleSignInClient? = null

    private var onSignInRequested: (() -> Unit)? = null
    private var onSignOutRequested: ((onComplete: () -> Unit) -> Unit)? = null

    private var googleSignInStatusState = mutableStateOf("Not signed in.")
    private var isGoogleSignedInState = mutableStateOf(false)

    private var lightTheme by mutableStateOf(false)
    private var restoreOption by mutableStateOf(0)
    private var histLimit by mutableStateOf(20)
    private var historyIconOption by mutableStateOf(0)
    private var bookmarkIconOption by mutableStateOf(0)

    private var silentBlock by mutableStateOf(true)
    private var clickjack by mutableStateOf(true)
    private var adblockUrl by mutableStateOf("")

    private var menuShortcut by mutableStateOf(KeyShortcut.DEFAULT)
    private var isBindingMenuShortcut by mutableStateOf(false)
    private var bindingPreviewText by mutableStateOf("")

    private var extractPref by mutableStateOf(0)
    private var fallbackPref by mutableStateOf(0)
    private var upNextMode by mutableStateOf("Ask")
    private var upNextTime by mutableStateOf(20)
    private var upNextTimeStr by mutableStateOf("20")
    private var autoplayNext by mutableStateOf("Closest Source")
    private var episodeFocusMode by mutableStateOf(0)
    private var skipIntroPref by mutableStateOf("Skip")
    private var skipRecapPref by mutableStateOf("Skip")
    private var skipEarlyCreditsPref by mutableStateOf("Skip")
    private var skipEndCreditsPref by mutableStateOf("Skip")

    private var scrollTopbar by mutableStateOf(true)
    private var navMode by mutableStateOf(0)
    private var scrapeTabOrder by mutableStateOf("Web,Torrents")
    private var torrentLanguage by mutableStateOf("English")

    private var hwKeyboardMode by mutableStateOf(0)
    private var hwMouseMode by mutableStateOf(0)
    private var hwMouseShowCursor by mutableStateOf(false)

    private var torrentCacheCleanMode by mutableStateOf(0)
    private var torrentCacheCleanDays by mutableStateOf(0)
    private var torrentCacheCleanDaysStr by mutableStateOf("")
    private var torrentPrebufferPieces by mutableStateOf(1)

    private var timeoutMode by mutableStateOf("Per-Source")
    private var globalTimeout by mutableStateOf(220)
    private var globalTimeoutStr by mutableStateOf("220")
    private var sourceTimeout by mutableStateOf(220)
    private var sourceTimeoutStr by mutableStateOf("220")
    private var scraperMaxWorkers by mutableStateOf(3)
    private var scraperMaxWorkersStr by mutableStateOf("3")
    private var enforceWhitelist by mutableStateOf(false)
    private val whitelistedHosts = mutableStateListOf<String>()

    private var subRenderingMode by mutableStateOf(0)
    private var autoSubPref by mutableStateOf(0)
    private var countPref by mutableStateOf(1)
    private var waitPref by mutableStateOf(1)
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

    private var autoUpdateCheck by mutableStateOf(true)

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
            val firstCategoryFocusRequester = remember { FocusRequester() }

            LaunchedEffect(Unit) {
                delay(60)
                try {
                    firstCategoryFocusRequester.requestFocus()
                } catch (_: Exception) {}
            }

            Row(
                modifier = Modifier.fillMaxSize().background(Color(0xFF1A1A1D))
            ) {
                Column(
                    modifier = Modifier.width(260.dp).fillMaxHeight().background(Color(0xFF222225)).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Settings", color = Color(0xFF00BCD4), fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 16.dp))

                    LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        itemsIndexed(Category.values()) { index, category ->
                            SidebarCategoryItem(
                                category = category, 
                                isSelected = selectedCategory == category, 
                                itemFocusRequester = if (index == 0) firstCategoryFocusRequester else null,
                                onSelect = { selectedCategory = category }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    TvFocusableBox(
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        defaultColor = Color(0xFF4CAF50),
                        shape = RoundedCornerShape(20.dp),
                        onClick = { saveAndExit() }
                    ) { isFocused ->
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Save & Exit", color = if (isFocused) Color.Black else Color.White, fontWeight = FontWeight.Bold)
                        }
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

            UpdateDialog()
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
        autoUpdateCheck = prefs.getBoolean("auto_update_check", true)
        lightTheme = prefs.getBoolean("light_theme", false)
        restoreOption = prefs.getInt("restore_tabs_pref", 0)
        histLimit = prefs.getInt("history_limit", 20)
        historyIconOption = prefs.getInt("history_icon_pref", 0)
        bookmarkIconOption = prefs.getInt("bookmark_icon_pref", 0)

        silentBlock = prefs.getBoolean("silent_popup_block", true)
        clickjack = prefs.getBoolean("clickjack_prevention", true)
        adblockUrl = prefs.getString("custom_adblock_url", "https://easylist.to/easylist/easylist.txt") ?: ""

        menuShortcut = KeyShortcut.loadFromPrefs(prefs)

        extractPref = prefs.getInt("extract_video_pref", 0)
        fallbackPref = prefs.getInt("exo_fallback_pref", 0)
        val legacyAutoSkip = prefs.getBoolean("auto_skip_intros", true)
        skipIntroPref = prefs.getString("skip_intro_pref", if (legacyAutoSkip) "Skip" else "Ignore") ?: "Skip"
        skipRecapPref = prefs.getString("skip_recap_pref", if (legacyAutoSkip) "Skip" else "Ignore") ?: "Skip"
        skipEarlyCreditsPref = prefs.getString("skip_early_credits_pref", "Skip") ?: "Skip"
        skipEndCreditsPref = prefs.getString("skip_end_credits_pref", "Skip") ?: "Skip"
        upNextMode = prefs.getString("up_next_popup_pref", "Ask") ?: "Ask"
        upNextTime = prefs.getInt("up_next_time_pref", 20)
        upNextTimeStr = upNextTime.toString()
        autoplayNext = prefs.getString("autoplay_next_pref", "Closest Source") ?: "Closest Source"
        episodeFocusMode = prefs.getInt("episode_focus_mode", 0)

        scrollTopbar = prefs.getBoolean("scroll_topbar_enabled", true)
        navMode = prefs.getInt("navigation_mode_pref", 0)
        scrapeTabOrder = prefs.getString("scrape_tab_order", "Web,Torrents") ?: "Web,Torrents"
        torrentLanguage = prefs.getString("torrent_language", "English") ?: "English"

        hwKeyboardMode = prefs.getInt("hw_keyboard_mode", 0)
        hwMouseMode = prefs.getInt("hw_mouse_mode", 0)
        hwMouseShowCursor = prefs.getBoolean("hw_mouse_show_cursor", false)

        torrentCacheCleanMode = prefs.getInt("torrent_cache_clean_mode", 0)
        torrentCacheCleanDays = prefs.getInt("torrent_cache_clean_days", 0)
        torrentCacheCleanDaysStr = if (torrentCacheCleanDays > 0) torrentCacheCleanDays.toString() else ""
        torrentPrebufferPieces = prefs.getInt("torrent_prebuffer_pieces", 1)

        timeoutMode = prefs.getString("timeout_mode", "Per-Source") ?: "Per-Source"
        globalTimeout = prefs.getInt("global_timeout", 220)
        globalTimeoutStr = globalTimeout.toString()
        sourceTimeout = prefs.getInt("per_source_timeout", 220)
        sourceTimeoutStr = sourceTimeout.toString()
        scraperMaxWorkers = prefs.getInt("scraper_max_workers", 3)
        scraperMaxWorkersStr = scraperMaxWorkers.toString()
        enforceWhitelist = prefs.getBoolean("use_only_whitelisted_hosts", false)
        
        whitelistedHosts.clear()
        val hostsJson = prefs.getString("whitelisted_hosts", "[]") ?: "[]"
        val hostsArr = JSONArray(hostsJson)
        for (i in 0 until hostsArr.length()) {
            whitelistedHosts.add(hostsArr.getString(i))
        }

        subRenderingMode = prefs.getInt("subtitle_rendering_mode", 0)
        autoSubPref = prefs.getInt("auto_sub_pref", 0)
        countPref = prefs.getInt("auto_sub_count", 1)
        waitPref = prefs.getInt("auto_sub_wait_pref", 1)
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
                isGoogleSignedInState.value = true
                googleSignInStatusState.value = "Signed in as: ${account.email}"

                when (val outcome = driveSyncManager.syncSettings()) {
                    com.poobi.tvbrowser.shared.sync.SyncOutcome.RESTORED_FROM_REMOTE -> {
                        syncToPython()
                        Toast.makeText(this@SettingsActivity, "Settings restored from Google Drive (cloud was newer).", Toast.LENGTH_LONG).show()
                        recreate()
                    }
                    com.poobi.tvbrowser.shared.sync.SyncOutcome.UPLOADED_LOCAL_TO_REMOTE -> {
                        Toast.makeText(this@SettingsActivity, "Local settings uploaded to Google Drive (local was newer).", Toast.LENGTH_LONG).show()
                    }
                    com.poobi.tvbrowser.shared.sync.SyncOutcome.ALREADY_UP_TO_DATE -> {
                        Toast.makeText(this@SettingsActivity, "Settings are already synchronized.", Toast.LENGTH_SHORT).show()
                    }
                    com.poobi.tvbrowser.shared.sync.SyncOutcome.FAILED -> {
                        Toast.makeText(this@SettingsActivity, "Cloud sync failed.", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(this@SettingsActivity, "Sync error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private val legacyStoragePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val readGranted = permissions[Manifest.permission.READ_EXTERNAL_STORAGE] ?: false
        val writeGranted = permissions[Manifest.permission.WRITE_EXTERNAL_STORAGE] ?: true
        if (readGranted && writeGranted) {
            val action = pendingStorageAction
            pendingStorageAction = null
            action?.invoke()
        } else {
            Toast.makeText(this, "Storage permission is required to access backups", Toast.LENGTH_LONG).show()
        }
    }

    private val manageStorageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (android.os.Environment.isExternalStorageManager()) {
                val action = pendingStorageAction
                pendingStorageAction = null
                action?.invoke()
            } else {
                Toast.makeText(this, "Permission denied: All Files Access is required", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun ensureStoragePermission(onGranted: () -> Unit): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (android.os.Environment.isExternalStorageManager()) {
                onGranted()
                return true
            }

            pendingStorageAction = onGranted

            AlertDialog.Builder(this)
                .setTitle("Storage Permission Required")
                .setMessage("Poobi needs 'All files access' to save and restore backups in your Downloads folder. Please enable it on the next screen.")
                .setPositiveButton("Allow Access") { _, _ ->
                    try {
                        val intent = Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                            data = android.net.Uri.parse("package:$packageName")
                        }
                        manageStorageLauncher.launch(intent)
                    } catch (_: Exception) {
                        try {
                            val fallbackIntent = Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                            manageStorageLauncher.launch(fallbackIntent)
                        } catch (e: Exception) {
                            val appDetailsIntent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = android.net.Uri.parse("package:$packageName")
                            }
                            manageStorageLauncher.launch(appDetailsIntent)
                        }
                    }
                }
                .setNegativeButton("Cancel") { _, _ ->
                    pendingStorageAction = null
                }
                .show()

            return false
        } else {
            val readPerm = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
            val writePerm = ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)

            if (readPerm == PackageManager.PERMISSION_GRANTED && writePerm == PackageManager.PERMISSION_GRANTED) {
                onGranted()
                return true
            }

            pendingStorageAction = onGranted
            legacyStoragePermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
            )
            return false
        }
    }

    private suspend fun syncToPython() = withContext(Dispatchers.IO) {
        try {
            val py = Python.getInstance()
            val main = py.getModule("main")
            val cfg = JSONObject().apply {
                put("timeout_mode", prefs.getString("timeout_mode", "Per-Source"))
                put("global_timeout", prefs.getInt("global_timeout", 220))
                put("per_source_timeout", prefs.getInt("per_source_timeout", 220))
                put("scraper_max_workers", prefs.getInt("scraper_max_workers", 3))
                put("use_only_whitelisted_hosts", prefs.getBoolean("use_only_whitelisted_hosts", false))
                put("whitelisted_hosts", JSONArray(prefs.getString("whitelisted_hosts", "[]")))
                put("enabled_packs", JSONArray(prefs.getString("enabled_packs", "[]")))
                put("subtitles_languages", prefs.getString("subtitles_languages", "English"))
                put("subtitles_limit", prefs.getInt("subtitles_limit", 20))
                put("sub_retention_days", prefs.getInt("sub_retention_days", 3))
                put("up_next_popup_pref", prefs.getString("up_next_popup_pref", "Ask"))
                put("up_next_time_pref", prefs.getInt("up_next_time_pref", 20))
                put("autoplay_next_pref", prefs.getString("autoplay_next_pref", "Closest Source"))
                val serviceKeys = listOf("addic7ed", "bsplayer", "opensubtitles", "opensubtitles_org", "podnadpisi", "subdl", "subsource")
                serviceKeys.forEach { put("${it}_enabled", prefs.getBoolean("${it}_enabled", it != "bsplayer" && it != "podnadpisi")) }
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

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (isBindingMenuShortcut) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                    isBindingMenuShortcut = false
                    bindingPreviewText = ""
                    return true
                }
                if (KeyShortcut.isModifierKey(event.keyCode)) {
                    bindingPreviewText = KeyShortcut.getModifiersString(event)
                    return true
                } else {
                    val newShortcut = KeyShortcut(
                        keyCode = event.keyCode,
                        alt = event.isAltPressed,
                        ctrl = event.isCtrlPressed,
                        shift = event.isShiftPressed,
                        meta = event.isMetaPressed
                    )
                    menuShortcut = newShortcut
                    KeyShortcut.saveToPrefs(prefs, newShortcut)
                    isBindingMenuShortcut = false
                    bindingPreviewText = ""
                    Toast.makeText(this, "Shortcut set to: ${newShortcut.getDisplayString()}", Toast.LENGTH_SHORT).show()
                    return true
                }
            } else if (event.action == KeyEvent.ACTION_UP) {
                if (KeyShortcut.isModifierKey(event.keyCode)) {
                    bindingPreviewText = KeyShortcut.getModifiersString(event)
                    return true
                }
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchKeyShortcutEvent(event: KeyEvent): Boolean {
        if (isBindingMenuShortcut) {
            return dispatchKeyEvent(event)
        }
        return super.dispatchKeyShortcutEvent(event)
    }

    override fun onKeyShortcut(keyCode: Int, event: KeyEvent): Boolean {
        if (isBindingMenuShortcut) {
            return dispatchKeyEvent(event)
        }
        return super.onKeyShortcut(keyCode, event)
    }

    @Composable
    fun SidebarCategoryItem(
        category: Category, 
        isSelected: Boolean, 
        itemFocusRequester: FocusRequester? = null,
        onSelect: () -> Unit
    ) {
        TvFocusableBox(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (itemFocusRequester != null) Modifier.focusRequester(itemFocusRequester) else Modifier),
            isSelected = isSelected,
            onClick = onSelect,
            onFocus = onSelect
        ) { isFocused ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = category.name, 
                    color = if (isFocused) Color.Black else if (isSelected) Color.White else Color.LightGray, 
                    fontSize = 16.sp, 
                    fontWeight = if (isSelected || isFocused) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }

    @Composable
    fun GeneralPanel() {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            item { PanelHeader("General Settings") }
            item { ToggleSettingRow("Use Light Theme", lightTheme) { lightTheme = it } }
            item { ToggleSettingRow("Check for Updates Automatically", autoUpdateCheck) { autoUpdateCheck = it } }
            item { DropdownSettingRow(label = "Restore Session Mode", options = listOf("Ask to Restore", "Always Restore & Load", "Always Restore", "Never Restore"), selectedIndex = restoreOption) {restoreOption = it } }
            item {
                DropdownSettingRow(
                    label = "History Retention Limit",
                    options = listOf("10 Entries", "20 Entries", "50 Entries", "Unlimited"),
                    selectedIndex = when (histLimit) { 10 -> 0; 20 -> 1; 50 -> 2; else -> 3 }
                ) {
                    histLimit = when (it) { 0 -> 10; 1 -> 20; 2 -> 50; else -> 0 }
                }
            }
            item { DropdownSettingRow("History Icons Style", listOf("Snapshots (Thumbnail)", "Favicons"), historyIconOption) { historyIconOption = it } }
            item { DropdownSettingRow("Bookmark Icons Style", listOf("Snapshots (Thumbnail)", "Favicons"), bookmarkIconOption) { bookmarkIconOption = it } }
            
            item {
                Spacer(modifier = Modifier.height(8.dp))
                val isCheckingUpdates by UpdateManager.isChecking.collectAsState()
                val currentVerDisplay = "${UpdateManager.getAppVersionName(this@SettingsActivity)}${if (BuildConfig.DEBUG) "-debug" else ""}"
                
                TvFocusableBox(
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    defaultColor = Color(0xFF00BCD4),
                    shape = RoundedCornerShape(20.dp),
                    onClick = { UpdateManager.checkForUpdates(this@SettingsActivity, isManual = true) }
                ) { isFocused ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = if (isCheckingUpdates) "Checking for Updates..." else "Check for Updates (v$currentVerDisplay)",
                            color = if (isFocused) Color.Black else Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }

    @Composable
    fun WebPanel() {
        val ruleCount by AdBlockManager.ruleCount.collectAsState()
        val isUpdating by AdBlockManager.isUpdating.collectAsState()

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            item { PanelHeader("Web & AdBlocker Settings") }
            item { ToggleSettingRow("Block Popups Silently", silentBlock) { silentBlock = it } }
            item { ToggleSettingRow("Prevent Clickjacking", clickjack) { clickjack = it } }

            item {
                TvInputField(
                    value = adblockUrl,
                    onValueChange = { adblockUrl = it },
                    label = "Custom AdBlock Rules URL (EasyList Format)",
                    placeholder = "https://easylist.to/easylist/easylist.txt",
                    containerColor = Color(0xFF222225),
                    imeAction = ImeAction.Done
                )
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isUpdating) "Updating filter lists..." else "Active Filter Rules: ~$ruleCount loaded",
                        color = Color(0xFF00BCD4),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )

                    TvFocusableBox(
                        modifier = Modifier.height(36.dp),
                        defaultColor = Color(0xFF333338),
                        shape = RoundedCornerShape(18.dp),
                        onClick = {
                            AdBlockManager.updateAllRules(this@SettingsActivity, adblockUrl.ifEmpty { "https://easylist.to/easylist/easylist.txt" }) { success ->
                                Toast.makeText(this@SettingsActivity, if (success) "Filter lists updated!" else "Update failed", Toast.LENGTH_SHORT).show()
                            }
                        }
                    ) { isFocused ->
                        Box(Modifier.fillMaxHeight().padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
                            Text(if (isUpdating) "Updating..." else "Update Rules Now", color = if (isFocused) Color.Black else Color.White, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun PlayerPanel() {
        Column(modifier = Modifier.fillMaxSize().padding(bottom = 16.dp)) {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item { PanelHeader("Native Player Preferences") }
                item { DropdownSettingRow("Video Extraction Hijack", listOf("Ask to Play", "Always Play in Native Player", "Never Play (Use Site Browser)"), extractPref) { extractPref = it } }
                item { DropdownSettingRow("Fallback on Playback Error", listOf("Ask user to switch", "Always fall back to Browser", "Never fall back"), fallbackPref) { fallbackPref = it } }

                item { Spacer(modifier = Modifier.height(8.dp)) }
                item { PanelHeader("Binge Watching & Autoplay") }

                val skipOptions = listOf("Skip", "Ask to Skip", "Ignore")

                item {
                    DropdownSettingRow(
                        label = "Intro Skip Action",
                        options = skipOptions,
                        selectedIndex = skipOptions.indexOf(skipIntroPref).coerceAtLeast(0)
                    ) { index ->
                        skipIntroPref = skipOptions[index]
                    }
                }

                item {
                    DropdownSettingRow(
                        label = "Recap Skip Action",
                        options = skipOptions,
                        selectedIndex = skipOptions.indexOf(skipRecapPref).coerceAtLeast(0)
                    ) { index ->
                        skipRecapPref = skipOptions[index]
                    }
                }

                item {
                    DropdownSettingRow(
                        label = "Early Credits Skip Action",
                        options = skipOptions,
                        selectedIndex = skipOptions.indexOf(skipEarlyCreditsPref).coerceAtLeast(0)
                    ) { index ->
                        skipEarlyCreditsPref = skipOptions[index]
                    }
                }

                item {
                    DropdownSettingRow(
                        label = "End Credits Skip Action",
                        options = skipOptions,
                        selectedIndex = skipOptions.indexOf(skipEndCreditsPref).coerceAtLeast(0)
                    ) { index ->
                        skipEndCreditsPref = skipOptions[index]
                    }
                }

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
                        label = "Seconds Before End (Fallback If Show End Undetected)",
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
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            item { PanelHeader("Interface Navigation Settings") }
            item { ToggleSettingRow("Scroll up for Navigation Bar", scrollTopbar) { scrollTopbar = it } }
            item { DropdownSettingRow("Default Pointer Navigation Mode", listOf("Simulated Pointer (Cursor)", "Physical target navigation (D-pad selection)"), navMode) { navMode = it } }
            item {
                DropdownSettingRow("Scraper Result Tab Order", listOf("Web, Torrents", "Torrents, Web"), if (scrapeTabOrder == "Web,Torrents") 0 else 1) { 
                    scrapeTabOrder = if (it == 0) "Web,Torrents" else "Torrents,Web"
                }
            }

            item {
                Spacer(modifier = Modifier.height(6.dp))
                PanelHeader("Hardware Devices & External Input")
            }

            item {
                DropdownSettingRow(
                    label = "Hardware Keyboard Mode",
                    options = listOf("Auto-detect (Recommended)", "Always Enabled", "Disabled (TV Remote Mode)"),
                    selectedIndex = hwKeyboardMode
                ) { hwKeyboardMode = it }
            }

            item {
                DropdownSettingRow(
                    label = "Physical Mouse Mode",
                    options = listOf("Auto-detect (Recommended)", "Always Enabled", "Disabled (TV Remote Mode)"),
                    selectedIndex = hwMouseMode
                ) { hwMouseMode = it }
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isBindingMenuShortcut) Color(0xFF00BCD4).copy(alpha = 0.15f) else Color.Transparent)
                        .padding(if (isBindingMenuShortcut) 14.dp else 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isBindingMenuShortcut) "Press desired key combination" else "Menu Button Keybind",
                            color = if (isBindingMenuShortcut) Color.White else Color.LightGray,
                            fontSize = 16.sp,
                            fontWeight = if (isBindingMenuShortcut) FontWeight.Bold else FontWeight.Normal
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = if (isBindingMenuShortcut) bindingPreviewText.ifEmpty { "Listening for keys..." } else "Current: ${menuShortcut.getDisplayString()}",
                            color = if (isBindingMenuShortcut) Color(0xFFFFB74D) else Color(0xFF00BCD4),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        TvFocusableBox(
                            modifier = Modifier.height(40.dp),
                            defaultColor = if (isBindingMenuShortcut) Color(0xFFE53935) else Color(0xFF00BCD4),
                            shape = RoundedCornerShape(20.dp),
                            onClick = {
                                if (isBindingMenuShortcut) {
                                    isBindingMenuShortcut = false
                                    bindingPreviewText = ""
                                } else {
                                    bindingPreviewText = "Listening for keys..."
                                    isBindingMenuShortcut = true
                                }
                            }
                        ) { isFocused ->
                            Box(Modifier.fillMaxHeight().padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                                Text(
                                    text = if (isBindingMenuShortcut) "Cancel" else "Rebind",
                                    color = if (isFocused) Color.Black else Color.White,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        if (!isBindingMenuShortcut) {
                            TvFocusableBox(
                                modifier = Modifier.height(40.dp),
                                defaultColor = Color(0xFF333338),
                                shape = RoundedCornerShape(20.dp),
                                onClick = {
                                    val def = KeyShortcut.DEFAULT
                                    menuShortcut = def
                                    KeyShortcut.saveToPrefs(prefs, def)
                                    Toast.makeText(this@SettingsActivity, "Reset to default: ${def.getDisplayString()}", Toast.LENGTH_SHORT).show()
                                }
                            ) { isFocused ->
                                Box(Modifier.fillMaxHeight().padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                                    Text("Reset", color = if (isFocused) Color.Black else Color.White)
                                }
                            }
                        }
                    }
                }
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
                                placeholder = "220",
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
                                placeholder = "220",
                                containerColor = Color(0xFF222225),
                                imeAction = ImeAction.Done
                            )
                        }
                    }
                }

                item {
                    TvInputField(
                        value = scraperMaxWorkersStr,
                        onValueChange = { newValue ->
                            val filtered = newValue.filter { it.isDigit() }
                            scraperMaxWorkersStr = filtered
                            if (filtered.isNotEmpty()) {
                                scraperMaxWorkers = filtered.toInt().coerceIn(1, 20)
                            }
                        },
                        label = "Scraper Worker Threads (Default: 3)",
                        placeholder = "3",
                        containerColor = Color(0xFF222225),
                        imeAction = ImeAction.Done
                    )
                }

                if (providerPacksList.isNotEmpty()) {
                    item { Text("Provider Packages", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp) }
                    items(providerPacksList) { pack ->
                        var isChecked by remember { mutableStateOf(prefs.getBoolean("pack_$pack", true)) }
                        TvFocusableBox(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp),
                            onClick = {
                                isChecked = !isChecked
                                lifecycleScope.launch(Dispatchers.IO) {
                                    prefs.edit().putBoolean("pack_$pack", isChecked).apply()
                                }
                            }
                        ) { isFocused ->
                            Row(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = pack, 
                                    color = if (isFocused) Color.Black else Color.LightGray, 
                                    fontSize = 14.sp, 
                                    fontWeight = if (isFocused) FontWeight.Bold else FontWeight.Normal,
                                    modifier = Modifier.weight(1f)
                                )
                                Checkbox(
                                    checked = isChecked, 
                                    onCheckedChange = { checked -> 
                                        isChecked = checked
                                        lifecycleScope.launch(Dispatchers.IO) {
                                            prefs.edit().putBoolean("pack_$pack", checked).apply()
                                        }
                                    }, 
                                    colors = CheckboxDefaults.colors(
                                        checkedColor = if (isFocused) Color.Black else Color(0xFF00BCD4),
                                        checkmarkColor = if (isFocused) Color.White else Color.Black
                                    ),
                                    modifier = Modifier.scale(0.85f)
                                )
                            }
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
                            TvFocusableBox(
                                modifier = Modifier.height(36.dp),
                                defaultColor = Color(0xFF333338),
                                shape = RoundedCornerShape(20.dp),
                                onClick = {
                                    val filteredP = providerHosts.filter { it.contains(hostSearchQuery, true) }
                                    val filteredR = resolveurlHosts.filter { it.contains(hostSearchQuery, true) }
                                    filteredP.forEach { if (!whitelistedHosts.contains(it)) whitelistedHosts.add(it) }
                                    filteredR.forEach { if (!whitelistedHosts.contains(it)) whitelistedHosts.add(it) }
                                }
                            ) { isFocused ->
                                Box(Modifier.fillMaxHeight().padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
                                    Text("Select All", color = if (isFocused) Color.Black else Color.White, fontSize = 12.sp)
                                }
                            }

                            TvFocusableBox(
                                modifier = Modifier.height(36.dp),
                                defaultColor = Color(0xFF333338),
                                shape = RoundedCornerShape(20.dp),
                                onClick = { whitelistedHosts.clear() }
                            ) { isFocused ->
                                Box(Modifier.fillMaxHeight().padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
                                    Text("Clear All", color = if (isFocused) Color.Black else Color.White, fontSize = 12.sp)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(modifier = Modifier.fillMaxWidth().height(240.dp)) {
                            Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                                Text("Provider Pack Whitelist", color = Color(0xFF00BCD4), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                LazyColumn(modifier = Modifier.fillMaxSize().background(Color(0x0DFFFFFF))) {
                                    val filtered = providerHosts.filter { it.contains(hostSearchQuery, true) }
                                    items(filtered) { host ->
                                        TvFocusableBox(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(36.dp),
                                            onClick = {
                                                if (enforceWhitelist) {
                                                    if (whitelistedHosts.contains(host)) whitelistedHosts.remove(host) else whitelistedHosts.add(host)
                                                }
                                            }
                                        ) { isFocused ->
                                            Row(
                                                modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = host, 
                                                    color = if (isFocused) Color.Black else if (enforceWhitelist) Color.White else Color.Gray, 
                                                    fontSize = 12.sp, 
                                                    fontWeight = if (isFocused) FontWeight.Bold else FontWeight.Normal,
                                                    modifier = Modifier.weight(1f)
                                                )
                                                Checkbox(
                                                    checked = whitelistedHosts.contains(host),
                                                    enabled = enforceWhitelist,
                                                    onCheckedChange = { checked ->
                                                        if (checked) whitelistedHosts.add(host) else whitelistedHosts.remove(host)
                                                    },
                                                    colors = CheckboxDefaults.colors(
                                                        checkedColor = if (isFocused) Color.Black else Color(0xFF00BCD4),
                                                        checkmarkColor = if (isFocused) Color.White else Color.Black
                                                    ),
                                                    modifier = Modifier.scale(0.75f)
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.width(10.dp))

                            Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                                Text("ResolveURL Whitelist", color = Color(0xFF00BCD4), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                LazyColumn(modifier = Modifier.fillMaxSize().background(Color(0x0DFFFFFF))) {
                                    val filtered = resolveurlHosts.filter { it.contains(hostSearchQuery, true) }
                                    items(filtered) { host ->
                                        TvFocusableBox(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(36.dp),
                                            onClick = {
                                                if (enforceWhitelist) {
                                                    if (whitelistedHosts.contains(host)) whitelistedHosts.remove(host) else whitelistedHosts.add(host)
                                                }
                                            }
                                        ) { isFocused ->
                                            Row(
                                                modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = host, 
                                                    color = if (isFocused) Color.Black else if (enforceWhitelist) Color.White else Color.Gray, 
                                                    fontSize = 12.sp, 
                                                    fontWeight = if (isFocused) FontWeight.Bold else FontWeight.Normal,
                                                    modifier = Modifier.weight(1f)
                                                )
                                                Checkbox(
                                                    checked = whitelistedHosts.contains(host),
                                                    enabled = enforceWhitelist,
                                                    onCheckedChange = { checked ->
                                                        if (checked) whitelistedHosts.add(host) else whitelistedHosts.remove(host)
                                                    },
                                                    colors = CheckboxDefaults.colors(
                                                        checkedColor = if (isFocused) Color.Black else Color(0xFF00BCD4),
                                                        checkmarkColor = if (isFocused) Color.White else Color.Black
                                                    ),
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
                            TvFocusableBox(
                                modifier = Modifier.height(36.dp),
                                defaultColor = Color(0xFF333338),
                                shape = RoundedCornerShape(20.dp),
                                onClick = { editProfileIndex = index; editProfileData = JSONObject(profile.toString()); showEditDialog = true }
                            ) { isFocused ->
                                Box(Modifier.fillMaxHeight().padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
                                    Text("Edit", color = if (isFocused) Color.Black else Color.White)
                                }
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                            TvFocusableBox(
                                modifier = Modifier.height(36.dp),
                                defaultColor = Color(0xFFE53935),
                                shape = RoundedCornerShape(20.dp),
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
                                }
                            ) { isFocused ->
                                Box(Modifier.fillMaxHeight().padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
                                    Text("Delete", color = if (isFocused) Color.Black else Color.White)
                                }
                            }
                        }
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(10.dp))
                    TvFocusableBox(
                        modifier = Modifier.height(44.dp),
                        defaultColor = Color(0xFF00BCD4),
                        shape = RoundedCornerShape(20.dp),
                        onClick = { editProfileIndex = -1; editProfileData = JSONObject().apply { put("id", UUID.randomUUID().toString()); put("name", "New Profile"); put("enabled", true); put("urlPatterns", JSONArray().put("*")); put("script", ""); put("use_script", true); put("selectors", JSONArray()) }; showEditDialog = true }
                    ) { isFocused ->
                        Box(Modifier.fillMaxHeight().padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                            Text("Add New Autoplay Profile", color = if (isFocused) Color.Black else Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
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
                            TvFocusableBox(
                                modifier = Modifier.height(36.dp),
                                defaultColor = Color(0xFF333338),
                                shape = RoundedCornerShape(20.dp),
                                onClick = { useScript = !useScript }
                            ) { isFocused ->
                                Box(Modifier.fillMaxHeight().padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
                                    Text(if (useScript) "Custom Script" else "Simple Element List", color = if (isFocused) Color.Black else Color.White)
                                }
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
                                        Spacer(modifier = Modifier.width(6.dp))
                                        TvFocusableBox(
                                            modifier = Modifier.size(36.dp),
                                            defaultColor = Color(0xFFE53935),
                                            shape = RoundedCornerShape(18.dp),
                                            onClick = { selectors.removeAt(idx) }
                                        ) { isFocused ->
                                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                                Text("X", color = if (isFocused) Color.Black else Color.White, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                }
                                item {
                                    TvFocusableBox(
                                        modifier = Modifier.height(36.dp),
                                        defaultColor = Color(0xFF00BCD4),
                                        shape = RoundedCornerShape(20.dp),
                                        onClick = { selectors.add("") }
                                    ) { isFocused ->
                                        Box(Modifier.fillMaxHeight().padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
                                            Text("+ Add Selector", color = if (isFocused) Color.Black else Color.White)
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TvFocusableBox(
                        modifier = Modifier.height(40.dp),
                        defaultColor = Color(0xFF4CAF50),
                        shape = RoundedCornerShape(20.dp),
                        onClick = {
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
                        }
                    ) { isFocused ->
                        Box(Modifier.fillMaxHeight().padding(horizontal = 20.dp), contentAlignment = Alignment.Center) {
                            Text("Save", color = if (isFocused) Color.Black else Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                },
                dismissButton = { 
                    TvFocusableBox(
                        modifier = Modifier.height(40.dp),
                        defaultColor = Color(0xFF333338),
                        shape = RoundedCornerShape(20.dp),
                        onClick = { showEditDialog = false }
                    ) { isFocused ->
                        Box(Modifier.fillMaxHeight().padding(horizontal = 20.dp), contentAlignment = Alignment.Center) {
                            Text("Cancel", color = if (isFocused) Color.Black else Color.White)
                        }
                    }
                }
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

            item {
                DropdownSettingRow(
                    label = "Subtitle Rendering Engine",
                    options = listOf("Native (Requires Reloading Subs)", "Custom Subtitle Overlay (Zero Delay)"),
                    selectedIndex = subRenderingMode
                ) { index ->
                    subRenderingMode = index
                }
            }

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
                var isEnabled by remember { mutableStateOf(prefs.getBoolean("${key}_enabled", key != "bsplayer" && key != "podnadpisi")) }
                TvFocusableBox(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp),
                    onClick = {
                        isEnabled = !isEnabled
                        lifecycleScope.launch(Dispatchers.IO) {
                            prefs.edit().putBoolean("${key}_enabled", isEnabled).apply()
                        }
                    }
                ) { isFocused ->
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = key.replace("_", " ").replaceFirstChar { it.uppercase() }, 
                            color = if (isFocused) Color.Black else Color.LightGray, 
                            fontSize = 14.sp, 
                            fontWeight = if (isFocused) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.weight(1f)
                        )
                        Checkbox(
                            checked = isEnabled, 
                            onCheckedChange = { checked -> 
                                isEnabled = checked
                                lifecycleScope.launch(Dispatchers.IO) {
                                    prefs.edit().putBoolean("${key}_enabled", checked).apply()
                                }
                            }, 
                            colors = CheckboxDefaults.colors(
                                checkedColor = if (isFocused) Color.Black else Color(0xFF00BCD4),
                                checkmarkColor = if (isFocused) Color.White else Color.Black
                            ),
                            modifier = Modifier.scale(0.85f)
                        )
                    }
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
                    TvFocusableBox(
                        modifier = Modifier.height(40.dp),
                        defaultColor = Color(0xFF4CAF50),
                        shape = RoundedCornerShape(20.dp),
                        onClick = {
                            val intValue = customSubCountInput.toIntOrNull() ?: 5
                            countPref = intValue
                            showCustomSubCountDialog = false
                        }
                    ) { isFocused ->
                        Box(Modifier.fillMaxHeight().padding(horizontal = 20.dp), contentAlignment = Alignment.Center) {
                            Text("OK", color = if (isFocused) Color.Black else Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                },
                dismissButton = {
                    TvFocusableBox(
                        modifier = Modifier.height(40.dp),
                        defaultColor = Color(0xFF333338),
                        shape = RoundedCornerShape(20.dp),
                        onClick = { showCustomSubCountDialog = false }
                    ) { isFocused ->
                        Box(Modifier.fillMaxHeight().padding(horizontal = 20.dp), contentAlignment = Alignment.Center) {
                            Text("Cancel", color = if (isFocused) Color.Black else Color.White)
                        }
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
                                    ) { isFocused ->
                                        Icon(
                                            painter = painterResource(id = R.drawable.ic_arrow_up),
                                            contentDescription = "Move Up",
                                            tint = if (isFocused) Color.Black else Color.White,
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
                                    ) { isFocused ->
                                        Icon(
                                            painter = painterResource(id = R.drawable.ic_arrow_down),
                                            contentDescription = "Move Down",
                                            tint = if (isFocused) Color.Black else Color.White,
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
                                    ) { isFocused ->
                                        Icon(
                                            painter = painterResource(id = R.drawable.ic_arrow_up),
                                            contentDescription = "Move Up",
                                            tint = if (isFocused) Color.Black else Color.White,
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
                                    ) { isFocused ->
                                        Icon(
                                            painter = painterResource(id = R.drawable.ic_arrow_down),
                                            contentDescription = "Move Down",
                                            tint = if (isFocused) Color.Black else Color.White,
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
                            Spacer(modifier = Modifier.width(8.dp))
                            TvFocusableBox(
                                modifier = Modifier.height(36.dp),
                                defaultColor = Color(0xFF333338),
                                shape = RoundedCornerShape(20.dp),
                                onClick = { editRuleIndex = index; editRuleData = JSONObject(rule.toString()); showEditDialog = true }
                            ) { isFocused ->
                                Box(Modifier.fillMaxHeight().padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
                                    Text("Edit", color = if (isFocused) Color.Black else Color.White)
                                }
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            TvFocusableBox(
                                modifier = Modifier.height(36.dp),
                                defaultColor = Color(0xFFE53935),
                                shape = RoundedCornerShape(20.dp),
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
                                }
                            ) { isFocused ->
                                Box(Modifier.fillMaxHeight().padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
                                    Text("Delete", color = if (isFocused) Color.Black else Color.White)
                                }
                            }
                        }
                    }
                }
            }
            TvFocusableBox(
                modifier = Modifier.height(44.dp),
                defaultColor = Color(0xFF00BCD4),
                shape = RoundedCornerShape(20.dp),
                onClick = { editRuleIndex = -1; editRuleData = JSONObject().apply { put("id", UUID.randomUUID().toString()); put("name", "New CSS Rule"); put("enabled", true); put("urlPatterns", JSONArray().put("*")); put("selectors", JSONArray()) }; showEditDialog = true }
            ) { isFocused ->
                Box(Modifier.fillMaxHeight().padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                    Text("Add New CSS Block Rule", color = if (isFocused) Color.Black else Color.White, fontWeight = FontWeight.Bold)
                }
            }
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
                    TvFocusableBox(
                        modifier = Modifier.height(40.dp),
                        defaultColor = Color(0xFF4CAF50),
                        shape = RoundedCornerShape(20.dp),
                        onClick = {
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
                        }
                    ) { isFocused ->
                        Box(Modifier.fillMaxHeight().padding(horizontal = 20.dp), contentAlignment = Alignment.Center) {
                            Text("Save", color = if (isFocused) Color.Black else Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                },
                dismissButton = { 
                    TvFocusableBox(
                        modifier = Modifier.height(40.dp),
                        defaultColor = Color(0xFF333338),
                        shape = RoundedCornerShape(20.dp),
                        onClick = { showEditDialog = false }
                    ) { isFocused ->
                        Box(Modifier.fillMaxHeight().padding(horizontal = 20.dp), contentAlignment = Alignment.Center) {
                            Text("Cancel", color = if (isFocused) Color.Black else Color.White)
                        }
                    }
                }
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
                TvFocusableBox(
                    modifier = Modifier.height(40.dp),
                    defaultColor = Color(0xFFE53935),
                    shape = RoundedCornerShape(20.dp),
                    onClick = {
                        lifecycleScope.launch(Dispatchers.IO) {
                            try {
                                Python.getInstance().getModule("trakt.trakt_auth").callAttr("logout_trakt")
                                withContext(Dispatchers.Main) {
                                    traktUser = ""
                                    Toast.makeText(this@SettingsActivity, "Logged out of Trakt", Toast.LENGTH_SHORT).show()
                                }
                            } catch (e: Exception) {}
                        }
                    }
                ) { isFocused ->
                    Box(Modifier.fillMaxHeight().padding(horizontal = 20.dp), contentAlignment = Alignment.Center) {
                        Text("Logout Trakt", color = if (isFocused) Color.Black else Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            } else {
                Text("Trakt account not linked.", color = Color.Gray)
                TvFocusableBox(
                    modifier = Modifier.height(40.dp),
                    defaultColor = Color(0xFF00BCD4),
                    shape = RoundedCornerShape(20.dp),
                    onClick = { startTraktAuthFlow() }
                ) { isFocused ->
                    Box(Modifier.fillMaxHeight().padding(horizontal = 20.dp), contentAlignment = Alignment.Center) {
                        Text("Authorize Trakt Account", color = if (isFocused) Color.Black else Color.White, fontWeight = FontWeight.Bold)
                    }
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
                    TvFocusableBox(
                        modifier = Modifier.height(40.dp),
                        defaultColor = Color(0xFF00BCD4),
                        shape = RoundedCornerShape(20.dp),
                        onClick = {
                            val intent = Intent(this@SettingsActivity, MainActivity::class.java).apply {
                                putExtra("open_url", traktVerificationUrl)
                                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            }
                            startActivity(intent)
                        }
                    ) { isFocused ->
                        Box(Modifier.fillMaxHeight().padding(horizontal = 20.dp), contentAlignment = Alignment.Center) {
                            Text("Open Link", color = if (isFocused) Color.Black else Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                },
                dismissButton = {
                    TvFocusableBox(
                        modifier = Modifier.height(40.dp),
                        defaultColor = Color(0xFF333338),
                        shape = RoundedCornerShape(20.dp),
                        onClick = { showTraktAuthDialog = false }
                    ) { isFocused ->
                        Box(Modifier.fillMaxHeight().padding(horizontal = 20.dp), contentAlignment = Alignment.Center) {
                            Text("Cancel", color = if (isFocused) Color.Black else Color.White)
                        }
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
                TvFocusableBox(
                    modifier = Modifier.height(40.dp),
                    defaultColor = Color(0xFFE53935),
                    shape = RoundedCornerShape(20.dp),
                    onClick = {
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
                    }
                ) { isFocused ->
                    Box(Modifier.fillMaxHeight().padding(horizontal = 20.dp), contentAlignment = Alignment.Center) {
                        Text("Logout", color = if (isFocused) Color.Black else Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            } else {
                TvInputField(value = usernameVal, onValueChange = { usernameVal = it }, label = "Username", placeholder = "Username", containerColor = Color(0xFF222225), imeAction = ImeAction.Done)
                TvInputField(value = passwordVal, onValueChange = { passwordVal = it }, label = "Password", placeholder = "Password", isPassword = true, containerColor = Color(0xFF222225), imeAction = ImeAction.Done)

                TvFocusableBox(
                    modifier = Modifier.height(40.dp),
                    defaultColor = Color(0xFF00BCD4),
                    shape = RoundedCornerShape(20.dp),
                    onClick = {
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
                    }
                ) { isFocused ->
                    Box(Modifier.fillMaxHeight().padding(horizontal = 20.dp), contentAlignment = Alignment.Center) {
                        Text("Login", color = if (isFocused) Color.Black else Color.White, fontWeight = FontWeight.Bold)
                    }
                }
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
                        TvFocusableBox(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            onClick = { showDaysPickerDialog = true }
                        ) { isFocused ->
                            Row(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 16.dp), 
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Clean Interval", color = if (isFocused) Color(0xFF222222) else Color.LightGray, fontSize = 12.sp)
                                    Text(
                                        text = if (torrentCacheCleanDays > 0) "Every $torrentCacheCleanDays days" else "Disabled / Click to set", 
                                        color = if (isFocused) Color.Black else Color.White, 
                                        fontSize = 16.sp, 
                                        fontWeight = FontWeight.Bold
                                    )
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
                            TvFocusableBox(
                                modifier = Modifier.height(40.dp),
                                defaultColor = Color(0xFFE53935),
                                shape = RoundedCornerShape(20.dp),
                                onClick = {
                                    val server = com.poobi.tvbrowser.torrent.TorrentStreamServer.getInstance(this@SettingsActivity)
                                    server.clearAllCache()
                                    refreshCache()
                                }
                            ) { isFocused ->
                                Box(Modifier.fillMaxHeight().padding(horizontal = 20.dp), contentAlignment = Alignment.Center) {
                                    Text("Clear All", color = if (isFocused) Color.Black else Color.White, fontWeight = FontWeight.Bold)
                                }
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
                                TvFocusableBox(
                                    modifier = Modifier.height(36.dp),
                                    defaultColor = Color(0xFF333338),
                                    shape = RoundedCornerShape(20.dp),
                                    onClick = {
                                        val server = com.poobi.tvbrowser.torrent.TorrentStreamServer.getInstance(this@SettingsActivity)
                                        server.deleteCacheItem(cacheItem.path)
                                        refreshCache()
                                    }
                                ) { isFocused ->
                                    Box(Modifier.fillMaxHeight().padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
                                        Text("Clear", color = if (isFocused) Color.Black else Color.White, fontSize = 11.sp)
                                    }
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
                    TvFocusableBox(
                        modifier = Modifier.height(40.dp),
                        defaultColor = Color(0xFF4CAF50),
                        shape = RoundedCornerShape(20.dp),
                        onClick = { showDaysPickerDialog = false }
                    ) { isFocused ->
                        Box(Modifier.fillMaxHeight().padding(horizontal = 20.dp), contentAlignment = Alignment.Center) {
                            Text("OK", color = if (isFocused) Color.Black else Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                },
                dismissButton = {
                    TvFocusableBox(
                        modifier = Modifier.height(40.dp),
                        defaultColor = Color(0xFF333338),
                        shape = RoundedCornerShape(20.dp),
                        onClick = { showDaysPickerDialog = false }
                    ) { isFocused ->
                        Box(Modifier.fillMaxHeight().padding(horizontal = 20.dp), contentAlignment = Alignment.Center) {
                            Text("Cancel", color = if (isFocused) Color.Black else Color.White)
                        }
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

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                PanelHeader("Local Backup & Restore")
                Text(
                    text = "Export or restore all settings, bookmarks, and configuration to an offline JSON file in your device's Downloads folder without requiring a Google account.",
                    color = Color.Gray,
                    fontSize = 13.sp
                )
            }

            item {
                TvFocusableBox(
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    defaultColor = Color(0xFF4CAF50),
                    shape = RoundedCornerShape(20.dp),
                    onClick = {
                        ensureStoragePermission {
                            lifecycleScope.launch {
                                isProgressVisible = true
                                val file = driveSyncManager.saveToLocalBackupFile()
                                isProgressVisible = false
                                if (file != null) {
                                    Toast.makeText(this@SettingsActivity, "Backup saved to: ${file.name}", Toast.LENGTH_LONG).show()
                                } else {
                                    Toast.makeText(this@SettingsActivity, "Failed to save local backup file.", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    }
                ) { isFocused ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Save Backup", color = if (isFocused) Color.Black else Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }

            item {
                TvFocusableBox(
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    defaultColor = Color(0xFF00BCD4),
                    shape = RoundedCornerShape(20.dp),
                    onClick = {
                        ensureStoragePermission {
                            lifecycleScope.launch {
                                isProgressVisible = true
                                val restored = driveSyncManager.restoreFromLocalBackupFile()
                                isProgressVisible = false
                                if (restored) {
                                    syncToPython()
                                    Toast.makeText(this@SettingsActivity, "Settings & data restored successfully!", Toast.LENGTH_LONG).show()
                                    recreate()
                                } else {
                                    Toast.makeText(this@SettingsActivity, "No backup JSON file found in Downloads folder!", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    }
                ) { isFocused ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Restore Backup", color = if (isFocused) Color.Black else Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(10.dp))
                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF333338)))
                Spacer(modifier = Modifier.height(10.dp))
            }

            item {
                PanelHeader("Google Drive Cloud Sync")
                Text(
                    text = "Sync your settings, bookmarks, and history across devices using your private Google Drive App Data folder. Newer settings automatically take priority.",
                    color = Color.Gray,
                    fontSize = 13.sp
                )
            }

            item {
                Text("Status: $syncStatus", color = Color.White, fontWeight = FontWeight.Bold)
            }

            if (isSignedState) {
                item {
                    TvFocusableBox(
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        defaultColor = Color(0xFFE53935),
                        shape = RoundedCornerShape(20.dp),
                        onClick = {
                            onSignOutRequested?.invoke {
                                googleSignInStatusState.value = "Not signed in."
                                isGoogleSignedInState.value = false
                                Toast.makeText(this@SettingsActivity, "Signed out successfully", Toast.LENGTH_SHORT).show()
                            }
                        }
                    ) { isFocused ->
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Logout", color = if (isFocused) Color.Black else Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            } else {
                item {
                    TvFocusableBox(
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        defaultColor = Color(0xFF00BCD4),
                        shape = RoundedCornerShape(20.dp),
                        onClick = { onSignInRequested?.invoke() }
                    ) { isFocused ->
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Sign in with Google", color = if (isFocused) Color.Black else Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            if (isProgressVisible) {
                item {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Color(0xFF00BCD4))
                    }
                }
            }

            item {
                TvFocusableBox(
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    defaultColor = Color(0xFF00BCD4),
                    shape = RoundedCornerShape(20.dp),
                    onClick = {
                        val account = GoogleSignIn.getLastSignedInAccount(this@SettingsActivity)
                        if (account != null) {
                            lifecycleScope.launch {
                                isProgressVisible = true
                                driveSyncManager.initService(account)
                                val outcome = driveSyncManager.syncSettings()
                                isProgressVisible = false
                                when (outcome) {
                                    com.poobi.tvbrowser.shared.sync.SyncOutcome.RESTORED_FROM_REMOTE -> {
                                        syncToPython()
                                        Toast.makeText(this@SettingsActivity, "Cloud backup was newer. Restored!", Toast.LENGTH_LONG).show()
                                        recreate()
                                    }
                                    com.poobi.tvbrowser.shared.sync.SyncOutcome.UPLOADED_LOCAL_TO_REMOTE -> {
                                        Toast.makeText(this@SettingsActivity, "Local settings were newer. Uploaded to Drive!", Toast.LENGTH_LONG).show()
                                    }
                                    com.poobi.tvbrowser.shared.sync.SyncOutcome.ALREADY_UP_TO_DATE -> {
                                        Toast.makeText(this@SettingsActivity, "Settings are already synchronized.", Toast.LENGTH_SHORT).show()
                                    }
                                    com.poobi.tvbrowser.shared.sync.SyncOutcome.FAILED -> {
                                        Toast.makeText(this@SettingsActivity, "Cloud sync failed.", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        } else {
                            Toast.makeText(this@SettingsActivity, "Please sign in to Google first", Toast.LENGTH_SHORT).show()
                        }
                    }
                ) { isFocused ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Force Sync Now (Two-Way)", color = if (isFocused) Color.Black else Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    @Composable
    fun PanelHeader(text: String) { Text(text, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp)) }

    @Composable
    fun ToggleSettingRow(label: String, checked: Boolean, modifier: Modifier = Modifier, onCheckedChange: (Boolean) -> Unit) {
        TvFocusableBox(
            modifier = modifier
                .fillMaxWidth()
                .height(48.dp),
            onClick = { onCheckedChange(!checked) }
        ) { isFocused ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp), 
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = label, 
                    color = if (isFocused) Color.Black else Color.LightGray, 
                    fontSize = 16.sp, 
                    fontWeight = if (isFocused) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = checked, 
                    onCheckedChange = onCheckedChange,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = if (isFocused) Color.Black else Color.White,
                        checkedTrackColor = if (isFocused) Color(0xFF004D40) else Color(0xFF00BCD4)
                    )
                )
            }
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
                    .height(48.dp),
                onClick = { showDialog = true }
            ) { isFocused ->
                Box(contentAlignment = Alignment.CenterStart, modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                    Text(
                        text = options.getOrNull(selectedIndex) ?: "", 
                        color = if (isFocused) Color.Black else Color.White, 
                        fontWeight = if (isFocused) FontWeight.Bold else FontWeight.Normal,
                        fontSize = 14.sp
                    )
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
                                modifier = Modifier.fillMaxWidth().height(48.dp),
                                onClick = { onSelect(index); showDialog = false }
                            ) { isFocused ->
                                Box(contentAlignment = Alignment.CenterStart, modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp)) {
                                    Text(
                                        text = option, 
                                        color = if (isFocused) Color.Black else Color.White,
                                        fontWeight = if (isFocused) FontWeight.Bold else FontWeight.Normal
                                    )
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

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val py = Python.getInstance()
                val main = py.getModule("main")

                val allPacksJson = main.callAttr("get_enabled_packs").toString()
                val allPacksArr = JSONArray(allPacksJson)
                val enabledPacksList = mutableListOf<String>()
                for (i in 0 until allPacksArr.length()) {
                    val pack = allPacksArr.getString(i)
                    if (prefs.getBoolean("pack_$pack", true)) {
                        enabledPacksList.add(pack)
                    }
                }

                prefs.edit().apply {
                    putString("stream_resume_points", prefs.getString("stream_resume_points", "{}"))
                    putLong("settings_last_modified", System.currentTimeMillis())
                    putBoolean("auto_update_check", autoUpdateCheck)
                    putBoolean("light_theme", lightTheme)
                    putInt("restore_tabs_pref", restoreOption)
                    putInt("history_limit", histLimit)
                    putInt("history_icon_pref", historyIconOption)
                    putInt("bookmark_icon_pref", bookmarkIconOption)

                    putBoolean("silent_popup_block", silentBlock)
                    putBoolean("clickjack_prevention", clickjack)
                    putString("custom_adblock_url", adblockUrl)

                    putInt("extract_video_pref", extractPref)
                    putInt("exo_fallback_pref", fallbackPref)
                    putString("skip_intro_pref", skipIntroPref)
                    putString("skip_recap_pref", skipRecapPref)
                    putString("skip_early_credits_pref", skipEarlyCreditsPref)
                    putString("skip_end_credits_pref", skipEndCreditsPref)
                    putBoolean("auto_skip_intros", skipIntroPref == "Skip" || skipRecapPref == "Skip" || skipEarlyCreditsPref == "Skip" || skipEndCreditsPref == "Skip")
                    putString("up_next_popup_pref", upNextMode)
                    putInt("up_next_time_pref", upNextTime)
                    putString("autoplay_next_pref", autoplayNext)
                    putInt("episode_focus_mode", episodeFocusMode)

                    putBoolean("scroll_topbar_enabled", scrollTopbar)
                    putInt("navigation_mode_pref", navMode)
                    putString("scrape_tab_order", scrapeTabOrder)
                    putString("torrent_language", torrentLanguage)

                    putInt("hw_keyboard_mode", hwKeyboardMode)
                    putInt("hw_mouse_mode", hwMouseMode)
                    putBoolean("hw_mouse_show_cursor", hwMouseShowCursor)

                    putInt("torrent_cache_clean_mode", torrentCacheCleanMode)
                    putInt("torrent_cache_clean_days", torrentCacheCleanDays)
                    putInt("torrent_prebuffer_pieces", torrentPrebufferPieces)

                    putString("timeout_mode", timeoutMode)
                    putInt("global_timeout", globalTimeout)
                    putInt("per_source_timeout", sourceTimeout)
                    putInt("scraper_max_workers", scraperMaxWorkers)
                    putBoolean("use_only_whitelisted_hosts", enforceWhitelist)
                    putString("enabled_packs", JSONArray(enabledPacksList).toString())
                    putString("whitelisted_hosts", JSONArray(whitelistedHosts).toString())

                    putInt("subtitle_rendering_mode", subRenderingMode)
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

                KeyShortcut.saveToPrefs(prefs, menuShortcut)
                HardwareInputManager.updateActiveStates(applicationContext)

                val appContext = applicationContext
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        AdBlockManager.updateRules(appContext, newAdblockUrl)
                    } catch (e: Exception) {
                        Log.e("Settings", "Failed background rules download update", e)
                    }
                }

                val cfg = JSONObject().apply {
                    put("timeout_mode", timeoutMode)
                    put("global_timeout", globalTimeout)
                    put("per_source_timeout", sourceTimeout)
                    put("scraper_max_workers", scraperMaxWorkers)
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
                        put("${key}_enabled", prefs.getBoolean("${key}_enabled", key != "bsplayer" && key != "podnadpisi"))
                    }

                    put("opensubtitles_username", opensubUser)
                    put("opensubtitles_password", opensubPass)
                    put("opensubtitles_org_username", opensubOrgUser)
                    put("opensubtitles_org_password", opensubOrgPass)
                    put("subdl_apikey", subdlApikey)
                    put("subsource_apikey", subsourceApikey)
                }
                main.callAttr("set_config", cfg.toString())

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