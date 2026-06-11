package com.poobi.tvbrowser.streams

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.poobi.tvbrowser.R
import com.poobi.tvbrowser.shared.TvFocusableBox
import org.json.JSONArray
import org.json.JSONObject

@Composable
fun ScrapeProgressScreen(viewModel: StreamsViewModel) {
    val status by viewModel.scrapeStatusMsg.collectAsState()
    val progress by viewModel.scrapeProgress.collectAsState()
    val total by viewModel.scrapeTotal.collectAsState()
    val sources by viewModel.scrapedSources.collectAsState()
    val isScraping by viewModel.isScraping.collectAsState()
    val isResolving by viewModel.isResolving.collectAsState()

    val subStatus by viewModel.subStatusMsg.collectAsState()
    val isDownloadingSubs by viewModel.isDownloadingSubs.collectAsState()
    val showSubProgressBar by viewModel.showSubProgressBar.collectAsState()
    val subProgress by viewModel.subProgress.collectAsState()
    val isTryingAll by viewModel.isTryingAll.collectAsState()

    var showSortDialog by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 10.dp)) {
        
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TvFocusableBox(modifier = Modifier.size(50.dp), onClick = { viewModel.clearScrapedSources() }) {
                Icon(painter = painterResource(id = R.drawable.ic_back), contentDescription = "Back", tint = Color.White, modifier = Modifier.fillMaxSize().padding(10.dp))
            }

            if (isScraping && !isResolving) {
                Spacer(modifier = Modifier.width(10.dp))
                Button(onClick = { viewModel.stopScrape(triggerSubtitles = true) }, colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray), modifier = Modifier.height(50.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(painter = painterResource(id = R.drawable.ic_stop), contentDescription = null, tint = Color.White)
                        Text("Stop Scanning", color = Color.White)
                    }
                }
            }

            Spacer(modifier = Modifier.width(20.dp))
            Text(status, color = Color(0xFF00BCD4), fontSize = 16.sp, fontWeight = FontWeight.Bold)

            Spacer(modifier = Modifier.weight(1f))

            if (sources != null && sources!!.length() > 0) {
                Button(
                    onClick = { viewModel.startTryAll() },
                    colors = ButtonDefaults.buttonColors(containerColor = if (isTryingAll) Color(0xFFE91E63) else Color(0xFF4CAF50)),
                    modifier = Modifier.height(50.dp)
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painter = painterResource(id = if (isTryingAll) R.drawable.ic_stop else R.drawable.ic_auto_play),
                            contentDescription = null,
                            tint = Color.White
                        )
                        Text(if (isTryingAll) "Stop Trying" else "Try All", color = Color.White)
                    }
                }
                Spacer(modifier = Modifier.width(10.dp))
                Button(onClick = { showSortDialog = true }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF333333)), modifier = Modifier.height(50.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(painter = painterResource(id = R.drawable.ic_sort), contentDescription = null, tint = Color.White)
                        Text("Sort", color = Color.White)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(15.dp))

        if (isScraping) {
            LinearProgressIndicator(
                progress = { if (total > 0) progress.toFloat() / total.toFloat() else 0f },
                modifier = Modifier.fillMaxWidth().height(8.dp),
                color = Color(0xFF00BCD4),
                trackColor = Color(0xFF333333)
            )
            Spacer(modifier = Modifier.height(15.dp))
        }
        
        if (isDownloadingSubs) {
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

        val currentSources = sources
        if (currentSources != null) {
            if (currentSources.length() == 0 && !isScraping) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Text("No sources found.", color = Color.White, fontSize = 18.sp)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f), 
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    item {
                        val autoSubPref = viewModel.prefs.getInt("auto_sub_pref", 0)
                        if (autoSubPref == 2) {
                            Button(
                                onClick = { 
                                    viewModel.selectedItem.value?.let { item ->
                                        viewModel.fetchManualSubtitles(
                                            item = item, 
                                            season = viewModel.lastScrapedSeason, 
                                            episode = viewModel.lastScrapedEpisode
                                        )
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF333333)),
                                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                            ) {
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text("🔍 Search External Subtitles", color = Color.White, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }

                    items(currentSources.length()) { idx ->
                        val s = currentSources.optJSONObject(idx) ?: return@items
                        val rawData = JSONObject(s.optString("source_data", "{}"))
                        val quality = rawData.optString("quality", "SD")
                        val sourceName = rawData.optString("source", "Unknown")
                        val providerName = rawData.optString("provider", "Unknown")

                        val rawTitle = s.optString("title", "")
                        val isBrowser = rawTitle.startsWith("[BROWSER]")

                        TvFocusableBox(modifier = Modifier.fillMaxWidth(), onClick = { viewModel.resolveAndPlay(s.optString("source_data"), s) }) {
                            Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(painter = painterResource(id = R.drawable.ic_go), contentDescription = null, tint = Color.White, modifier = Modifier.size(32.dp).padding(end = 8.dp))
                                Column {
                                    Text("[$quality] $sourceName ($providerName)", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                    if (isBrowser) {
                                        Text("> Open in browser", color = Color.Gray, fontSize = 14.sp)
                                    } else {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                painter = painterResource(id = R.drawable.ic_auto_play),
                                                contentDescription = null,
                                                tint = Color.Gray,
                                                modifier = Modifier.size(16.dp).padding(end = 4.dp)
                                            )
                                            Text("Play video", color = Color.Gray, fontSize = 14.sp)
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
        var prioritiesList by remember {
            mutableStateOf(
                viewModel.prefs.getString("sort_priorities", null)?.let {
                    val arr = JSONArray(it)
                    (0 until arr.length()).map { i -> arr.getString(i) }
                } ?: listOf("NATIVE", "DIRECT", "RESOLUTION", "SOURCE")
            )
        }

        AlertDialog(
            onDismissRequest = { showSortDialog = false },
            title = { Text("Sort Priorities", color = Color.White) },
            containerColor = Color(0xFF222225),
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    prioritiesList.forEachIndexed { index, criteria ->
                        Row(modifier = Modifier.fillMaxWidth().background(Color(0xFF333338)).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(text = criteria, color = Color.White, modifier = Modifier.weight(1f))
                            
                            if (index > 0) {
                                TvFocusableBox(
                                    modifier = Modifier.size(36.dp),
                                    onClick = {
                                        val list = prioritiesList.toMutableList()
                                        val temp = list[index]
                                        list[index] = list[index - 1]
                                        list[index - 1] = temp
                                        prioritiesList = list
                                        viewModel.prefs.edit().putString("sort_priorities", JSONArray(list).toString()).apply()
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
                                        viewModel.prefs.edit().putString("sort_priorities", JSONArray(list).toString()).apply()
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