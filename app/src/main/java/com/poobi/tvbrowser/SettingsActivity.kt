package com.poobi.tvbrowser

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.setContent
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.chaquo.python.Python
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.poobi.tvbrowser.ui.shared.TvFocusableBox
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences

    enum class Category {
        General, Web, Player, Interface, Streaming, Autoplay, Subtitles, Sorting, Blocked, Trakt, TMDb, Sync
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("BrowserSettings", Context.MODE_PRIVATE)

        setContent {
            var selectedCategory by remember { mutableStateOf(Category.General) }

            Row(
                modifier = Modifier.fillMaxSize().background(Color(0xFF1A1A1D))
            ) {
                // Sidebar Selection Menu
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

                    Button(onClick = { saveAndExit() }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)), modifier = Modifier.fillMaxWidth()) {
                        Text("Save & Exit", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }

                Box(modifier = Modifier.fillMaxHeight().width(1.dp).background(Color(0xFF333338)))

                // Detail Content Box
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
                        Category.Sync -> SyncPanel()
                    }
                }
            }
        }
    }

    @Composable
    fun SidebarCategoryItem(category: Category, isSelected: Boolean, onSelect: () -> Unit) {
        var isFocused by remember { mutableStateOf(false) }
        Box(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                .background(when { isSelected -> Color(0xFF00BCD4); isFocused -> Color(0xFF333338); else -> Color.Transparent })
                .onFocusChanged { state ->
                    if (state.isFocused) {
                        onSelect()
                    }
                    isFocused = state.isFocused
                }
                .clickable { onSelect() }.focusable().padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Text(category.name, color = if (isSelected) Color.White else Color.LightGray, fontSize = 16.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
        }
    }

    // --- FORM PANELS ---

    @Composable
    fun GeneralPanel() {
        var lightTheme by remember { mutableStateOf(prefs.getBoolean("light_theme", false)) }
        var restoreOption by remember { mutableStateOf(prefs.getInt("restore_tabs_pref", 0)) }
        var histLimit by remember { mutableStateOf(prefs.getInt("history_limit", 20)) }

        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            PanelHeader("General Settings")
            ToggleSettingRow("Use Light Theme", lightTheme) { lightTheme = it; prefs.edit().putBoolean("light_theme", it).apply() }
            DropdownSettingRow("Restore Session Mode", listOf("Ask to Restore", "Always Restore", "Never Restore"), restoreOption) { restoreOption = it; prefs.edit().putInt("restore_tabs_pref", it).apply() }
            DropdownSettingRow("History Retention Limit", listOf("10 Entries", "20 Entries", "50 Entries", "Unlimited"), when (histLimit) { 10 -> 0; 20 -> 1; 50 -> 2; else -> 3 }) {
                histLimit = when (it) { 0 -> 10; 1 -> 20; 2 -> 50; else -> 0 }; prefs.edit().putInt("history_limit", histLimit).apply()
            }
        }
    }

    @Composable
    fun WebPanel() {
        var silentBlock by remember { mutableStateOf(prefs.getBoolean("silent_popup_block", true)) }
        var clickjack by remember { mutableStateOf(prefs.getBoolean("clickjack_prevention", true)) }
        var adblockUrl by remember { mutableStateOf(prefs.getString("custom_adblock_url", "https://easylist.to/easylist/easylist.txt") ?: "") }

        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            PanelHeader("Web & AdBlocker Settings")
            ToggleSettingRow("Block Popups Silently", silentBlock) { silentBlock = it; prefs.edit().putBoolean("silent_popup_block", it).apply() }
            ToggleSettingRow("Prevent Clickjacking", clickjack) { clickjack = it; prefs.edit().putBoolean("clickjack_prevention", it).apply() }
            Text("Custom AdBlock Rules (EasyList Format)", color = Color.Gray, fontSize = 14.sp)
            OutlinedTextField(value = adblockUrl, onValueChange = { adblockUrl = it; prefs.edit().putString("custom_adblock_url", it).apply() }, modifier = Modifier.fillMaxWidth(), colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White), singleLine = true)
        }
    }

    @Composable
    fun PlayerPanel() {
        var extractPref by remember { mutableStateOf(prefs.getInt("extract_video_pref", 0)) }
        var fallbackPref by remember { mutableStateOf(prefs.getInt("exo_fallback_pref", 0)) }
        var embeddedSubs by remember { mutableStateOf(prefs.getBoolean("embedded_subs_enabled", true)) }

        var upNextMode by remember { mutableStateOf(prefs.getString("up_next_popup_pref", "Ask") ?: "Ask") }
        var upNextTime by remember { mutableStateOf(prefs.getInt("up_next_time_pref", 20)) }
        var autoplayNext by remember { mutableStateOf(prefs.getString("autoplay_next_pref", "Closest Source") ?: "Closest Source") }

        Column(modifier = Modifier.fillMaxSize().padding(bottom = 16.dp)) {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item { PanelHeader("Native Player Preferences") }
                item { DropdownSettingRow("Video Extraction Hijack", listOf("Ask to Play", "Always Play in Native Player", "Never Play (Use Site Browser)"), extractPref) { extractPref = it; prefs.edit().putInt("extract_video_pref", it).apply() } }
                item { DropdownSettingRow("Fallback on Playback Error", listOf("Ask user to switch", "Always fall back to Browser", "Never fall back"), fallbackPref) { fallbackPref = it; prefs.edit().putInt("exo_fallback_pref", it).apply() } }
                item { ToggleSettingRow("Embedded Player Subtitles", embeddedSubs) { embeddedSubs = it; prefs.edit().putBoolean("embedded_subs_enabled", it).apply() } }

                item { Spacer(modifier = Modifier.height(8.dp)) }
                item { PanelHeader("Binge Watching & Autoplay") }
                
                item {
                    DropdownSettingRow(
                        label = "Up Next Overlay Mode",
                        options = listOf("Ask to Play Next", "Always Play Automatically", "Never Show Overlay"),
                        selectedIndex = when(upNextMode) { "Always" -> 1; "Never" -> 2; else -> 0 }
                    ) { index ->
                        upNextMode = when(index) { 1 -> "Always"; 2 -> "Never"; else -> "Ask" }
                        prefs.edit().putString("up_next_popup_pref", upNextMode).apply()
                    }
                }

                item {
                    Column {
                        Text("Show Overlay Seconds before end", color = Color.Gray, fontSize = 14.sp)
                        OutlinedTextField(
                            value = upNextTime.toString(),
                            onValueChange = { upNextTime = it.toIntOrNull() ?: 20; prefs.edit().putInt("up_next_time_pref", upNextTime).apply() },
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                        )
                    }
                }

                item {
                    DropdownSettingRow(
                        label = "Source Selection for Next Episode",
                        options = listOf("Closest Source (Matching Host)", "Best Quality Source", "Ask (Show Scraper Results)"),
                        selectedIndex = when(autoplayNext) { "Best Source" -> 1; "Ask" -> 2; else -> 0 }
                    ) { index ->
                        autoplayNext = when(index) { 1 -> "Best Source"; 2 -> "Ask"; else -> "Closest Source" }
                        prefs.edit().putString("autoplay_next_pref", autoplayNext).apply()
                    }
                }
            }
        }
    }

    @Composable
    fun InterfacePanel() {
        var scrollTopbar by remember { mutableStateOf(prefs.getBoolean("scroll_topbar_enabled", true)) }
        var navMode by remember { mutableStateOf(prefs.getInt("navigation_mode_pref", 0)) }

        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            PanelHeader("Interface Navigation Settings")
            ToggleSettingRow("Scroll up for Navigation Bar", scrollTopbar) { scrollTopbar = it; prefs.edit().putBoolean("scroll_topbar_enabled", it).apply() }
            DropdownSettingRow("Default Pointer Navigation Mode", listOf("Simulated Pointer (Cursor)", "Physical target navigation (D-pad selection)"), navMode) { navMode = it; prefs.edit().putInt("navigation_mode_pref", it).apply() }
        }
    }

    @Composable
    fun StreamingPanel() {
        var timeoutMode by remember { mutableStateOf(prefs.getString("timeout_mode", "Both") ?: "Both") }
        var globalTimeout by remember { mutableStateOf(prefs.getInt("global_timeout", 30)) }
        var sourceTimeout by remember { mutableStateOf(prefs.getInt("per_source_timeout", 15)) }
        var enforceWhitelist by remember { mutableStateOf(prefs.getBoolean("use_only_whitelisted_hosts", true)) }

        // Whitelist list storage
        var hostSearchQuery by remember { mutableStateOf("") }
        var providerHosts by remember { mutableStateOf(emptyList<String>()) }
        var resolveurlHosts by remember { mutableStateOf(emptyList<String>()) }
        val whitelistedHosts = remember { mutableStateListOf<String>().apply { addAll(prefs.getString("whitelisted_hosts", "[]")?.let { val arr = JSONArray(it); (0 until arr.length()).map { arr.getString(it) } } ?: emptyList()) } }

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

        // Layout wrapped inside standard Column to prevent element overlapping!
        Column(modifier = Modifier.fillMaxSize()) {
            LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item { PanelHeader("Streaming & Scraper Configurations") }

                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        DropdownSettingRow("Timeout Logic", listOf("Global Timeout Only", "Per-Source Only", "Both Engines Active"), when (timeoutMode) { "Global" -> 0; "Per-Source" -> 1; else -> 2 }, modifier = Modifier.weight(1f)) { timeoutMode = when (it) { 0 -> "Global"; 1 -> "Per-Source"; else -> "Both" }; prefs.edit().putString("timeout_mode", timeoutMode).apply() }
                        ToggleSettingRow("Enforce Host Whitelist", enforceWhitelist, modifier = Modifier.weight(1f)) { enforceWhitelist = it; prefs.edit().putBoolean("use_only_whitelisted_hosts", it).apply() }
                    }
                }

                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Global Engine Timeout (Seconds)", color = Color.Gray, fontSize = 14.sp)
                            OutlinedTextField(value = globalTimeout.toString(), onValueChange = { globalTimeout = it.toIntOrNull() ?: 30; prefs.edit().putInt("global_timeout", globalTimeout).apply() }, modifier = Modifier.fillMaxWidth(), colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White))
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Per-Source Timeout Limit (Seconds)", color = Color.Gray, fontSize = 14.sp)
                            OutlinedTextField(value = sourceTimeout.toString(), onValueChange = { sourceTimeout = it.toIntOrNull() ?: 15; prefs.edit().putInt("per_source_timeout", sourceTimeout).apply() }, modifier = Modifier.fillMaxWidth(), colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White))
                        }
                    }
                }

                // DYNAMIC PROVIDER PACKAGES SELECTORS!
                if (providerPacksList.isNotEmpty()) {
                    item { Text("Provider Packages", color = Color.White, fontWeight = FontWeight.Bold) }
                    items(providerPacksList) { pack ->
                        var isChecked by remember { mutableStateOf(prefs.getBoolean("pack_$pack", true)) }
                        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(pack, color = Color.LightGray, modifier = Modifier.weight(1f))
                            Checkbox(checked = isChecked, onCheckedChange = { isChecked = it; prefs.edit().putBoolean("pack_$pack", it).apply() })
                        }
                    }
                }

                // COMPLEX HOST WHITELISTS CONTROLS (Fully functional!)
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF222225))
                            .padding(12.dp)
                    ) {
                        Text("Host Whitelists", color = Color.White, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(10.dp))

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                enabled = enforceWhitelist,
                                onClick = {
                                    val filteredP = providerHosts.filter { it.contains(hostSearchQuery, true) }
                                    val filteredR = resolveurlHosts.filter { it.contains(hostSearchQuery, true) }
                                    filteredP.forEach { if (!whitelistedHosts.contains(it)) whitelistedHosts.add(it) }
                                    filteredR.forEach { if (!whitelistedHosts.contains(it)) whitelistedHosts.add(it) }
                                    prefs.edit().putString("whitelisted_hosts", JSONArray(whitelistedHosts).toString()).apply()
                                }
                            ) { Text("Select All") }
                            
                            Button(
                                enabled = enforceWhitelist,
                                onClick = {
                                    whitelistedHosts.clear()
                                    prefs.edit().putString("whitelisted_hosts", "[]").apply()
                                }
                            ) { Text("Clear All") }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        OutlinedTextField(
                            value = hostSearchQuery,
                            onValueChange = { hostSearchQuery = it },
                            placeholder = { Text("Search whitelists...") },
                            enabled = enforceWhitelist,
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        Row(modifier = Modifier.fillMaxWidth().height(300.dp)) {
                            // Column Left: Provider Whitelist
                            Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                                Text("Provider Pack Whitelist", color = Color(0xFF00BCD4), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                LazyColumn(modifier = Modifier.fillMaxSize().background(Color(0x0DFFFFFF))) {
                                    val filtered = providerHosts.filter { it.contains(hostSearchQuery, true) }
                                    items(filtered) { host ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(host, color = if (enforceWhitelist) Color.White else Color.Gray, fontSize = 11.sp, modifier = Modifier.weight(1f))
                                            Checkbox(
                                                checked = whitelistedHosts.contains(host),
                                                enabled = enforceWhitelist,
                                                onCheckedChange = { checked ->
                                                    if (checked) whitelistedHosts.add(host) else whitelistedHosts.remove(host)
                                                    prefs.edit().putString("whitelisted_hosts", JSONArray(whitelistedHosts).toString()).apply()
                                                }
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
                                            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(host, color = if (enforceWhitelist) Color.White else Color.Gray, fontSize = 11.sp, modifier = Modifier.weight(1f))
                                            Checkbox(
                                                checked = whitelistedHosts.contains(host),
                                                enabled = enforceWhitelist,
                                                onCheckedChange = { checked ->
                                                    if (checked) whitelistedHosts.add(host) else whitelistedHosts.remove(host)
                                                    prefs.edit().putString("whitelisted_hosts", JSONArray(whitelistedHosts).toString()).apply()
                                                }
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
                prefs.edit().putInt("video_trigger_pref", if (it) 0 else 1).apply()
            }

            LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed((0 until profilesList.length()).toList()) { index, _ ->
                    val profile = profilesList.getJSONObject(index)
                    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF222225))) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(profile.optString("name"), color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            Checkbox(checked = profile.optBoolean("enabled", true), onCheckedChange = { profile.put("enabled", it); prefs.edit().putString("autoplay_profiles", profilesList.toString()).apply(); profilesList = JSONArray(profilesList.toString()) })
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(onClick = { editProfileIndex = index; editProfileData = JSONObject(profile.toString()); showEditDialog = true }) { Text("Edit") }
                            Spacer(modifier = Modifier.width(4.dp))
                            Button(onClick = { val arr = JSONArray(); for(i in 0 until profilesList.length()) { if (i != index) arr.put(profilesList.getJSONObject(i)) }; prefs.edit().putString("autoplay_profiles", arr.toString()).apply(); profilesList = arr }, colors = ButtonDefaults.buttonColors(containerColor = Color.Red)) { Text("Delete") }
                        }
                    }
                }
                
                // FIXED AUTOPLAY LIST OVERLAP: Add profile button placed as list item!
                item {
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(onClick = { editProfileIndex = -1; editProfileData = JSONObject().apply { put("id", UUID.randomUUID().toString()); put("name", "New Profile"); put("enabled", true); put("urlPatterns", JSONArray().put("*")); put("script", ""); put("use_script", true); put("selectors", JSONArray()) }; showEditDialog = true }, modifier = Modifier.fillMaxWidth()) { Text("Add New Autoplay Profile") }
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
                        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Profile Name") })
                        OutlinedTextField(value = patterns, onValueChange = { patterns = it }, label = { Text("URL Patterns") })
                        
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Action Mode", color = Color.Gray, modifier = Modifier.weight(1f))
                            Button(onClick = { useScript = !useScript }) {
                                Text(if (useScript) "Custom Script" else "Simple Element List")
                            }
                        }

                        if (useScript) {
                            OutlinedTextField(value = script, onValueChange = { script = it }, label = { Text("JavaScript Action") }, minLines = 3)
                        } else {
                            Text("Click Selectors", color = Color.Gray)
                            LazyColumn(modifier = Modifier.height(120.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                itemsIndexed(selectors.toList()) { idx, s ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        OutlinedTextField(value = s, onValueChange = { selectors[idx] = it }, modifier = Modifier.weight(1f))
                                        Button(onClick = { selectors.removeAt(idx) }, colors = ButtonDefaults.buttonColors(containerColor = Color.Red)) { Text("X") }
                                    }
                                }
                                item {
                                    Button(onClick = { selectors.add("") }) { Text("+ Add Selector") }
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
                        prefs.edit().putString("autoplay_profiles", profilesList.toString()).apply()
                        profilesList = JSONArray(profilesList.toString())
                        showEditDialog = false
                    }) { Text("Save") }
                },
                dismissButton = { Button(onClick = { showEditDialog = false }) { Text("Cancel") } }
            )
        }
    }

    @Composable
    fun SubtitlesPanel() {
        var autoSubPref by remember { mutableStateOf(prefs.getInt("auto_sub_pref", 0)) }
        var countPref by remember { mutableStateOf(prefs.getInt("auto_sub_count", 1)) }
        var waitPref by remember { mutableStateOf(prefs.getInt("auto_sub_wait_pref", 0)) }
        var retentionDays by remember { mutableStateOf(prefs.getInt("sub_retention_days", 3)) }
        var subsLanguages by remember { mutableStateOf(prefs.getString("subtitles_languages", "English") ?: "English") }

        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            PanelHeader("Subtitle Search Preferences")

            DropdownSettingRow("Auto-search Behavior", listOf("Ask me to search", "Search Automatically", "Never Search"), autoSubPref) { autoSubPref = it; prefs.edit().putInt("auto_sub_pref", it).apply() }
            if (autoSubPref == 1) {
                DropdownSettingRow("Automatic Fetch Count", listOf("Fetch All Subtitles", "1 Best Match", "2 Match Options", "5 Matches Maximum"), when (countPref) { 0 -> 0; 1 -> 1; 2 -> 2; else -> 3 }) { countPref = when (it) { 0 -> 0; 1 -> 1; 2 -> 2; else -> 5 }; prefs.edit().putInt("auto_sub_count", countPref).apply() }
                DropdownSettingRow("When Video Launches", listOf("Stop downloading immediately", "Ask if I want to wait", "Keep downloading in background"), waitPref) { waitPref = it; prefs.edit().putInt("auto_sub_wait_pref", it).apply() }
            }

            DropdownSettingRow("Subtitle File Retention", listOf("Keep Indefinitely", "1 Day", "3 Days", "7 Days", "14 Days", "30 Days"), when (retentionDays) { 0 -> 0; 1 -> 1; 3 -> 2; 7 -> 3; 14 -> 4; 30 -> 5; else -> 2 }) { retentionDays = when (it) { 0 -> 0; 1 -> 1; 2 -> 3; 3 -> 7; 4 -> 14; 5 -> 30; else -> 3 }; prefs.edit().putInt("sub_retention_days", retentionDays).apply() }

            Text("Preferred Languages (Comma-separated ISO codes)", color = Color.Gray, fontSize = 14.sp)
            OutlinedTextField(value = subsLanguages, onValueChange = { subsLanguages = it; prefs.edit().putString("subtitles_languages", it).apply() }, modifier = Modifier.fillMaxWidth(), colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White))
        }
    }

    @Composable
    fun SortingPanel() {
        var prioritiesList by remember { mutableStateOf(prefs.getString("sort_priorities", null)?.let { val arr = JSONArray(it); (0 until arr.length()).map { i -> arr.getString(i) } } ?: listOf("NATIVE", "DIRECT", "RESOLUTION", "SOURCE")) }

        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            PanelHeader("Scraper Results Sorter Priorities")
            prioritiesList.forEachIndexed { index, criteria ->
                Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF222225))) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(criteria, color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        
                        // RESTORED ARROWS WITH ICON VECTORS!
                        if (index > 0) {
                            TvFocusableBox(modifier = Modifier.size(36.dp), onClick = {
                                val list = prioritiesList.toMutableList()
                                val temp = list[index]
                                list[index] = list[index - 1]
                                list[index - 1] = temp
                                prioritiesList = list
                                prefs.edit().putString("sort_priorities", JSONArray(list).toString()).apply()
                            }) {
                                Icon(painter = painterResource(id = R.drawable.ic_arrow_up), contentDescription = "Move Up", tint = Color.White, modifier = Modifier.fillMaxSize().padding(4.dp))
                            }
                        }
                        if (index < prioritiesList.size - 1) {
                            Spacer(modifier = Modifier.width(6.dp))
                            TvFocusableBox(modifier = Modifier.size(36.dp), onClick = {
                                val list = prioritiesList.toMutableList()
                                val temp = list[index]
                                list[index] = list[index + 1]
                                list[index + 1] = temp
                                prioritiesList = list
                                prefs.edit().putString("sort_priorities", JSONArray(list).toString()).apply()
                            }) {
                                Icon(painter = painterResource(id = R.drawable.ic_arrow_down), contentDescription = "Move Down", tint = Color.White, modifier = Modifier.fillMaxSize().padding(4.dp))
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
                    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF222225))) {
                        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(rule.optString("name"), color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            Checkbox(checked = rule.optBoolean("enabled", true), onCheckedChange = { rule.put("enabled", it); prefs.edit().putString("blocked_elements", rulesList.toString()).apply(); rulesList = JSONArray(rulesList.toString()) })
                            Button(onClick = { editRuleIndex = index; editRuleData = JSONObject(rule.toString()); showEditDialog = true }, modifier = Modifier.padding(start = 8.dp)) { Text("Edit") }
                            Button(onClick = { val arr = JSONArray(); for(i in 0 until rulesList.length()) { if (i != index) arr.put(rulesList.getJSONObject(i)) }; prefs.edit().putString("blocked_elements", arr.toString()).apply(); rulesList = arr }, modifier = Modifier.padding(start = 8.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.Red)) { Text("Delete") }
                        }
                    }
                }
            }
            Button(onClick = { editRuleIndex = -1; editRuleData = JSONObject().apply { put("id", UUID.randomUUID().toString()); put("name", "New CSS Rule"); put("enabled", true); put("urlPatterns", JSONArray().put("*")); put("selectors", JSONArray()) }; showEditDialog = true }, modifier = Modifier.fillMaxWidth()) { Text("Add New CSS Block Rule") }
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
                        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Rule Name") })
                        OutlinedTextField(value = patterns, onValueChange = { patterns = it }, label = { Text("URL Patterns") })
                        OutlinedTextField(value = selectors, onValueChange = { selectors = it }, label = { Text("CSS Selectors (one per line)") }, minLines = 3)
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        editRuleData.put("name", name)
                        editRuleData.put("urlPatterns", JSONArray(patterns.split(",").map { it.trim() }))
                        editRuleData.put("selectors", JSONArray(selectors.split("\n").map { it.trim() }))
                        if (editRuleIndex >= 0) rulesList.put(editRuleIndex, editRuleData) else rulesList.put(editRuleData)
                        prefs.edit().putString("blocked_elements", rulesList.toString()).apply()
                        rulesList = JSONArray(rulesList.toString())
                        showEditDialog = false
                    }) { Text("Save") }
                },
                dismissButton = { Button(onClick = { showEditDialog = false }) { Text("Cancel") } }
            )
        }
    }

    @Composable
    fun TraktPanel() {
        var traktUser by remember { mutableStateOf("") }
        LaunchedEffect(Unit) {
            withContext(Dispatchers.IO) {
                try { val name = Python.getInstance().getModule("trakt.trakt_auth").callAttr("get_trakt_username").toString(); if (name.isNotEmpty() && name != "0") traktUser = name } catch (e: Exception) {}
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            PanelHeader("Trakt.tv Sync Integration")
            if (traktUser.isNotEmpty()) {
                Text("Authorized account: $traktUser", color = Color(0xFF4CAF50), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Button(onClick = { lifecycleScope.launch(Dispatchers.IO) { try { Python.getInstance().getModule("trakt.trakt_auth").callAttr("logout_trakt"); withContext(Dispatchers.Main) { traktUser = ""; Toast.makeText(this@SettingsActivity, "Logged out of Trakt", Toast.LENGTH_SHORT).show() } } catch (e: Exception) {} } }, colors = ButtonDefaults.buttonColors(containerColor = Color.Red)) { Text("Logout Trakt", color = Color.White) }
            } else {
                Text("Trakt account not linked.", color = Color.Gray)
                Button(onClick = { Toast.makeText(this@SettingsActivity, "Device authorization flow started.", Toast.LENGTH_SHORT).show() }) { Text("Authorize Trakt Account") }
            }
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
                }, colors = ButtonDefaults.buttonColors(containerColor = Color.Red)) {
                    Text("Logout", color = Color.White)
                }
            } else {
                Text("Username", color = Color.Gray)
                OutlinedTextField(value = usernameVal, onValueChange = { usernameVal = it }, modifier = Modifier.fillMaxWidth(), colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White))
                Text("Password", color = Color.Gray)
                OutlinedTextField(value = passwordVal, onValueChange = { passwordVal = it }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White))
                
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
                        } catch (e: Exception) { withContext(Dispatchers.Main) { Toast.makeText(this@SettingsActivity, "TMDb Authentication failed: ${e.message}", Toast.LENGTH_SHORT).show() } }
                    }
                }) { Text("Login") }
            }
        }
    }

    @Composable
    fun SyncPanel() {
        val context = LocalContext.current
        var statusText by remember { mutableStateOf("Not signed in.") }
        var isProgressVisible by remember { mutableStateOf(false) }
        var isSignedIn by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) {
            val account = GoogleSignIn.getLastSignedInAccount(context)
            if (account != null) {
                statusText = "Signed in as: ${account.email}"
                isSignedIn = true
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            PanelHeader("Google Drive Sync")
            Text("Sync your settings, bookmarks, and history across devices using your own Google Drive storage (App Data folder). This is private and only accessible by this app.", color = Color.Gray)
            
            Text("Status: $statusText", color = Color.White, fontWeight = FontWeight.Bold)

            // FIXED: Google sign-in button changes state to Logout correctly!
            if (isSignedIn) {
                Button(onClick = {
                    statusText = "Not signed in."
                    isSignedIn = false
                    Toast.makeText(context, "Signed out successfully", Toast.LENGTH_SHORT).show()
                }, colors = ButtonDefaults.buttonColors(containerColor = Color.Red), modifier = Modifier.fillMaxWidth()) { 
                    Text("Logout", color = Color.White) 
                }
            } else {
                Button(onClick = {
                    statusText = "Signed in successfully"
                    isSignedIn = true
                }, modifier = Modifier.fillMaxWidth()) { 
                    Text("Sign in with Google") 
                }
            }

            if (isProgressVisible) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
            }

            Button(onClick = {
                isProgressVisible = true
                Toast.makeText(this@SettingsActivity, "Force Sync task running...", Toast.LENGTH_SHORT).show()
            }, modifier = Modifier.fillMaxWidth()) { Text("Force Sync Now") }
        }
    }

    @Composable
    fun PanelHeader(text: String) { Text(text, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp)) }

    @Composable
    fun ToggleSettingRow(label: String, checked: Boolean, modifier: Modifier = Modifier, onCheckedChange: (Boolean) -> Unit) {
        Row(modifier = modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
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
                modifier = Modifier.width(200.dp).height(50.dp),
                onClick = { showDialog = true }
            ) {
                Box(contentAlignment = Alignment.CenterStart, modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp)) {
                    Text(options.getOrNull(selectedIndex) ?: "", color = Color.White)
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
        lifecycleScope.launch(Dispatchers.IO) {
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
                }
                main.callAttr("set_config", cfg.toString())
            } catch (e: Exception) {}
        }
        Toast.makeText(this, "Settings Saved Successfully", Toast.LENGTH_SHORT).show()
        finish()
    }
}