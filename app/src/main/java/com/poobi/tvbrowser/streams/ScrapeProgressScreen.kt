package com.poobi.tvbrowser.streams

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.poobi.tvbrowser.R
import com.poobi.tvbrowser.shared.LanguageHelper
import com.poobi.tvbrowser.shared.TvFocusableBox
import org.json.JSONArray
import org.json.JSONObject

@Composable
fun ScrapeTab(
    text: String,
    isSelected: Boolean,
    onFocus: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailingContent: @Composable RowScope.() -> Unit = {}
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val textColor = when {
        isFocused -> Color(0xFF00BCD4)
        isSelected -> Color(0xFF00BCD4)
        else -> Color.Gray
    }

    val underlineColor = when {
        isFocused -> Color.White
        isSelected -> Color(0xFF00BCD4)
        else -> Color.Transparent
    }

    Box(
        modifier = modifier
            .height(48.dp)
            .onFocusChanged { state ->
                if (state.isFocused) {
                    onFocus()
                }
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .focusable(interactionSource = interactionSource),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxHeight()
        ) {
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    text = text,
                    color = textColor,
                    fontSize = 16.sp,
                    fontWeight = if (isSelected || isFocused) FontWeight.Bold else FontWeight.Normal
                )
                trailingContent()
            }
            Box(
                modifier = Modifier
                    .width(40.dp)
                    .height(3.dp)
                    .clip(RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp))
                    .background(underlineColor)
            )
        }
    }
}

@Composable
fun ScrapeProgressScreen(viewModel: StreamsViewModel) {
    val status by viewModel.scrapeStatusMsg.collectAsState()
    val progress by viewModel.scrapeProgress.collectAsState()
    val total by viewModel.scrapeTotal.collectAsState()
    val sources by viewModel.scrapedSources.collectAsState()
    val torrentioSources by viewModel.torrentioSources.collectAsState()
    val isScraping by viewModel.isScraping.collectAsState()
    val isScrapingTorrents by viewModel.isScrapingTorrents.collectAsState()
    val isResolving by viewModel.isResolving.collectAsState()

    val subStatus by viewModel.subStatusMsg.collectAsState()
    val isDownloadingSubs by viewModel.isDownloadingSubs.collectAsState()
    val showSubProgressBar by viewModel.showSubProgressBar.collectAsState()
    val subProgress by viewModel.subProgress.collectAsState()
    val isTryingAll by viewModel.isTryingAll.collectAsState()

    val tabOrder = viewModel.prefs.getString("scrape_tab_order", "Streams,Torrents") ?: "Streams,Torrents"
    val tabs = remember(tabOrder) { tabOrder.split(",") }
    var selectedTabIndex by remember { mutableStateOf(0) }
    
    val selectedTab = tabs.getOrNull(selectedTabIndex) ?: "Streams"

    var showSortDialog by remember { mutableStateOf(false) }

    // Focus requesters mapped to each tab index to control exit paths cleanly
    val tabFocusRequesters = remember(tabs.size) { List(tabs.size) { FocusRequester() } }

    val stopScanningFocusRequester = remember { FocusRequester() }
    val sortStreamsFocusRequester = remember { FocusRequester() }
    val sortTorrentsFocusRequester = remember { FocusRequester() }

    val currentSources = if (selectedTab == "Streams") sources else torrentioSources
    val isCurrentTabScraping = if (selectedTab == "Streams") isScraping else isScrapingTorrents

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 10.dp)) {

        Row(
            modifier = Modifier
                .wrapContentWidth()
                .height(50.dp)
                .background(Color(0xFF1E1E24), RoundedCornerShape(25.dp))
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            tabs.forEachIndexed { index, tabName ->
                val isTabSelected = selectedTabIndex == index
                val isTabScraping = if (tabName == "Streams") isScraping else isScrapingTorrents

                if (index > 0) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight(0.4f)
                            .width(1.dp)
                            .background(Color.Gray.copy(alpha = 0.4f))
                    )
                }

                ScrapeTab(
                    text = tabName,
                    isSelected = isTabSelected,
                    onFocus = { selectedTabIndex = index },
                    onClick = { selectedTabIndex = index },
                    modifier = Modifier.focusRequester(tabFocusRequesters[index])
                ) {
                    val textColor = if (isTabSelected) Color(0xFF00BCD4) else Color.Gray
                    val count = if (tabName == "Streams") sources?.length() ?: 0 else torrentioSources?.length() ?: 0

                    if (count > 0) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "($count)",
                            color = textColor,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    
                    if (isTabScraping) {
                        Spacer(modifier = Modifier.width(8.dp))
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            color = textColor,
                            strokeWidth = 2.dp
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        Box(
            modifier = Modifier.fillMaxWidth().height(48.dp),
            contentAlignment = Alignment.Center
        ) {
            if (selectedTab == "Streams") {
                Row(
                    modifier = Modifier.align(Alignment.CenterStart),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isScraping && !isResolving) {
                        TvFocusableBox(
                            modifier = Modifier
                                .height(44.dp)
                                .wrapContentWidth()
                                .focusRequester(stopScanningFocusRequester)
                                .focusProperties {
                                    up = tabFocusRequesters.getOrNull(0) ?: FocusRequester.Default
                                },
                            onClick = { viewModel.stopScrape(triggerSubtitles = true) }
                        ) { isFocused ->
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp).fillMaxHeight(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_stop), 
                                    contentDescription = null, 
                                    tint = if (isFocused) Color.Black else Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text("Stop Scanning", color = if (isFocused) Color.Black else Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            }
                        }
                    }
                }

                Text(
                    text = status, 
                    color = Color(0xFF00BCD4), 
                    fontSize = 15.sp, 
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.Center)
                )

                Row(
                    modifier = Modifier.align(Alignment.CenterEnd),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (sources != null && sources!!.length() > 0) {
                        TvFocusableBox(
                            modifier = Modifier.height(44.dp).wrapContentWidth(),
                            onClick = { viewModel.startTryAll() }
                        ) { isFocused ->
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp).fillMaxHeight(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    painter = painterResource(id = if (isTryingAll) R.drawable.ic_stop else R.drawable.ic_auto_play),
                                    contentDescription = null,
                                    tint = if (isFocused) Color.Black else Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(if (isTryingAll) "Stop" else "Try All", color = if (isFocused) Color.Black else Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            }
                        }

                        TvFocusableBox(
                            modifier = Modifier
                                .height(44.dp)
                                .wrapContentWidth()
                                .focusRequester(sortStreamsFocusRequester)
                                .focusProperties {
                                    up = tabFocusRequesters.getOrNull(0) ?: FocusRequester.Default
                                },
                            onClick = { showSortDialog = true }
                        ) { isFocused ->
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp).fillMaxHeight(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_sort), 
                                    contentDescription = null, 
                                    tint = if (isFocused) Color.Black else Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text("Sort", color = if (isFocused) Color.Black else Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            }
                        }
                    }
                }
            } else {
                Row(
                    modifier = Modifier.align(Alignment.CenterStart),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (torrentioSources != null && torrentioSources!!.length() > 0) {
                        TvFocusableBox(
                            modifier = Modifier
                                .height(44.dp)
                                .wrapContentWidth()
                                .focusRequester(sortTorrentsFocusRequester)
                                .focusProperties {
                                    up = tabFocusRequesters.getOrNull(1) ?: FocusRequester.Default
                                },
                            onClick = { showSortDialog = true }
                        ) { isFocused ->
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp).fillMaxHeight(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_sort), 
                                    contentDescription = null, 
                                    tint = if (isFocused) Color.Black else Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text("Sort", color = if (isFocused) Color.Black else Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(15.dp))

        if (selectedTab == "Streams" && isScraping) {
            LinearProgressIndicator(
                progress = { if (total > 0) progress.toFloat() / total.toFloat() else 0f },
                modifier = Modifier.fillMaxWidth().height(8.dp),
                color = Color(0xFF00BCD4),
                trackColor = Color(0xFF333333)
            )
            Spacer(modifier = Modifier.height(15.dp))
        }
        
        if (selectedTab == "Streams" && isDownloadingSubs) {
            Text(subStatus, color = Color(0xFFFFC107), fontSize = 14.sp, fontWeight = FontWeight.Bold)
            if (showSubProgressBar) {
                Spacer(modifier = Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { subProgress },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    color = Color(0xFFFFC107),
                    trackColor = Color(0xFF333333)
                )
            }
            Spacer(modifier = Modifier.height(15.dp))
        }

        if (isCurrentTabScraping && (currentSources == null || currentSources.length() == 0)) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color(0xFF00BCD4))
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(if (selectedTab == "Streams") "Searching regular sources..." else "Querying Torrentio...", color = Color.Gray)
                }
            }
        } else if (currentSources != null) {
            if (currentSources.length() == 0 && !isCurrentTabScraping) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Text("No sources found.", color = Color.White, fontSize = 18.sp)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f), 
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (selectedTab == "Streams") {
                        item {
                            val autoSubPref = viewModel.prefs.getInt("auto_sub_pref", 0)
                            if (autoSubPref == 2) {
                                TvFocusableBox(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp)
                                        .padding(bottom = 8.dp)
                                        .focusProperties {
                                            up = if (isScraping && !isResolving) stopScanningFocusRequester else sortStreamsFocusRequester
                                        },
                                    onClick = { 
                                        viewModel.selectedItem.value?.let { item ->
                                            viewModel.fetchManualSubtitles(
                                                item = item, 
                                                season = viewModel.lastScrapedSeason, 
                                                episode = viewModel.lastScrapedEpisode
                                            )
                                        }
                                    }
                                ) { isFocused ->
                                    Row(
                                        modifier = Modifier.fillMaxSize(),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("🔍 Search External Subtitles", color = if (isFocused) Color.Black else Color.White, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }

                    items(currentSources.length()) { idx ->
                        val s = currentSources.optJSONObject(idx) ?: return@items
                        val sourceDataStr = s.optString("source_data", "{}")
                        val rawData = JSONObject(sourceDataStr)
                        val quality = rawData.optString("quality", "SD")
                        val sourceName = rawData.optString("source", "Unknown")
                        val providerName = rawData.optString("provider", "Unknown")

                        val rawTitle = s.optString("title", "")
                        val isVideo = rawData.optBoolean("is_video", true)
                        val isBrowser = !isVideo || rawTitle.startsWith("[BROWSER]")
                        val isCaptcha = rawData.optBoolean("requires_captcha", false) || rawTitle.contains("[CAPTCHA]")
                        val seeders = rawData.optInt("seeders", -1)

                        val isFirstItem = idx == 0 && (selectedTab == "Torrents" || viewModel.prefs.getInt("auto_sub_pref", 0) != 2)

                        TvFocusableBox(
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusProperties {
                                    if (isFirstItem) {
                                        up = if (selectedTab == "Streams") {
                                            if (isScraping && !isResolving) stopScanningFocusRequester else sortStreamsFocusRequester
                                        } else {
                                            sortTorrentsFocusRequester
                                        }
                                    }
                                }, 
                            onClick = { viewModel.resolveAndPlay(sourceDataStr, s) }
                        ) { isFocused ->
                            Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    painter = painterResource(id = if (isBrowser) R.drawable.ic_go else R.drawable.ic_auto_play),
                                    contentDescription = null,
                                    tint = if (isFocused) Color.Black else Color.White,
                                    modifier = Modifier.size(32.dp).padding(end = 8.dp)
                                )

                                if (selectedTab == "Torrents" && seeders >= 0) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier.padding(horizontal = 8.dp).width(50.dp)
                                    ) {
                                        Text(
                                            text = "SEEDS",
                                            color = if (isFocused) Color.Black.copy(alpha = 0.7f) else Color(0xFF00BCD4),
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = "$seeders",
                                            color = if (isFocused) Color.Black else Color(0xFF00BCD4),
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(6.dp))
                                }

                                Column(modifier = Modifier.weight(1f)) {
                                    if (selectedTab == "Torrents") {
                                        val parsedLangs = LanguageHelper.parseLanguages(rawTitle)
                                        if (parsedLangs.isNotEmpty()) {
                                            Text(
                                                text = parsedLangs,
                                                color = Color(0xFF4CAF50),
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(bottom = 2.dp)
                                            )
                                        }
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.Top
                                    ) {
                                        val cleanTitle = remember(rawTitle) {
                                            val baseTitle = rawTitle.ifEmpty { "[$quality] $sourceName ($providerName)" }
                                            baseTitle.lineSequence().firstOrNull { it.isNotBlank() } ?: baseTitle
                                        }

                                        Text(
                                            text = cleanTitle, 
                                            color = if (isFocused) Color.Black else Color.White, 
                                            fontSize = 18.sp, 
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.weight(1f)
                                        )

                                        if (selectedTab == "Torrents") {
                                            val size = parseSize(rawTitle)
                                            if (size.isNotEmpty()) {
                                                Spacer(modifier = Modifier.width(12.dp))
                                                Text(
                                                    text = size,
                                                    color = Color(0xFF4CAF50),
                                                    fontSize = 16.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.padding(top = 2.dp)
                                                )
                                            }
                                        }
                                    }

                                    if (selectedTab == "Streams") {
                                        if (isCaptcha) {
                                            Text(
                                                text = "> Requires captcha interaction", 
                                                color = if (isFocused) Color.Black.copy(alpha = 0.8f) else Color(0xFFFFC107), 
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        } else if (isBrowser) {
                                            Text(
                                                text = "> Open in browser", 
                                                color = if (isFocused) Color.Black.copy(alpha = 0.8f) else Color(0xFFE53935), 
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        } else {
                                            Text(
                                                text = "Play video", 
                                                color = if (isFocused) Color.Black.copy(alpha = 0.8f) else Color(0xFF00BCD4), 
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Bold
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

    if (showSortDialog) {
        val isTorrent = selectedTab == "Torrents"
        val prefsKey = if (isTorrent) "torrent_sort_priorities" else "sort_priorities"
        val defaultList = if (isTorrent) {
            listOf("LANGUAGE", "SIZE", "SEEDERS", "RESOLUTION")
        } else {
            listOf("NATIVE", "DIRECT", "RESOLUTION", "SOURCE")
        }

        var prioritiesList by remember {
            mutableStateOf(
                viewModel.prefs.getString(prefsKey, null)?.let {
                    val arr = JSONArray(it)
                    (0 until arr.length()).map { i -> arr.getString(i) }
                } ?: defaultList
            )
        }

        AlertDialog(
            onDismissRequest = { showSortDialog = false },
            title = { Text(if (isTorrent) "Sort Torrents" else "Sort Priorities", color = Color.White) },
            containerColor = Color(0xFF222225),
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    prioritiesList.forEachIndexed { index, criteria ->
                        val displayName = when (criteria) {
                            "LANGUAGE" -> "Language"
                            "SIZE" -> "File Size"
                            "SEEDERS" -> "Seeders"
                            "RESOLUTION" -> "Resolution"
                            "NATIVE" -> "Native Player Compatibility"
                            "DIRECT" -> "Direct Links"
                            "SOURCE" -> "Host / Source Name"
                            else -> criteria
                        }
                        Row(modifier = Modifier.fillMaxWidth().background(Color(0xFF333338)).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(text = displayName, color = Color.White, modifier = Modifier.weight(1f))
                            
                            if (index > 0) {
                                TvFocusableBox(
                                    modifier = Modifier.size(36.dp),
                                    onClick = {
                                        val list = prioritiesList.toMutableList()
                                        val temp = list[index]
                                        list[index] = list[index - 1]
                                        list[index - 1] = temp
                                        prioritiesList = list
                                        viewModel.prefs.edit().putString(prefsKey, JSONArray(list).toString()).apply()
                                    }
                                ) {
                                    Icon(painter = painterResource(id = R.drawable.ic_arrow_up), contentDescription = "Move Up", tint = Color.White, modifier = Modifier.fillMaxSize().padding(4.dp))
                                }
                            }
                            if (index < prioritiesList.size - 1) {
                                Spacer(modifier = Modifier.width(6.dp))
                                TvFocusableBox(
                                    modifier = Modifier.size(36.dp),
                                    onClick = {
                                        val list = prioritiesList.toMutableList()
                                        val temp = list[index]
                                        list[index] = list[index + 1]
                                        list[index + 1] = temp
                                        prioritiesList = list
                                        viewModel.prefs.edit().putString(prefsKey, JSONArray(list).toString()).apply()
                                    }
                                ) {
                                    Icon(painter = painterResource(id = R.drawable.ic_arrow_down), contentDescription = "Move Down", tint = Color.White, modifier = Modifier.fillMaxSize().padding(4.dp))
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { 
                Button(onClick = { 
                    showSortDialog = false 
                    viewModel.applySortPriorities()
                }) { Text("Apply") } 
            }
        )
    }
}

private fun parseSize(title: String): String {
    if (title.isEmpty()) return ""
    val sizeRegex = """💾\s*([\d\.]+\s*(?:GB|MB|KB))""".toRegex(RegexOption.IGNORE_CASE)
    val match = sizeRegex.find(title)
    return match?.groupValues?.get(1) ?: ""
}