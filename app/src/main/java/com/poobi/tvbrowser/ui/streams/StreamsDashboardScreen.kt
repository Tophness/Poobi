package com.poobi.tvbrowser.ui.streams

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.poobi.tvbrowser.R
import com.poobi.tvbrowser.ui.browser.TvFocusableHoldToDeleteBox
import com.poobi.tvbrowser.ui.shared.TvFocusableBox
import com.poobi.tvbrowser.ui.shared.TvSearchField
import org.json.JSONArray

@Composable
fun StreamsDashboardScreen(viewModel: StreamsViewModel) {
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current

    val searchHistory by viewModel.searchHistory.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    val libraryItems by viewModel.libraryItems.collectAsState()
    val isScraping by viewModel.isScraping.collectAsState()

    var searchInput by remember { mutableStateOf("") }
    var activeCategoryIndex by remember { mutableStateOf(0) }

    val firstHistoryFocusRequester = remember { FocusRequester() }

    val categories = listOf(
        "Search" to { viewModel.loadSearchHistory() },
        "Trending" to { viewModel.loadLibraryCategory("Trending Today", "get_trending", "all") },
        "In Cinemas" to { viewModel.loadLibraryCategory("In Cinemas Now", "get_movies_in_cinemas") },
        "Upcoming" to { viewModel.loadLibraryCategory("Upcoming Movies", "get_upcoming_movies") },
        "Popular TV" to { viewModel.loadLibraryCategory("Popular TV Shows", "get_popular", "tv") },
        "Top Rated" to { viewModel.loadLibraryCategory("Top Rated Movies", "get_top_rated", "movie") },
        "Favourites" to { viewModel.loadFavorites() },
        "Recently Watched" to { viewModel.loadRecentlyPlayed() }
    )

    val isDeletable = activeCategoryIndex == 6 || activeCategoryIndex == 7

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 10.dp)) {
        if (activeCategoryIndex == 0 && searchResults == null) {
            // Interactive Search Bar
            Row(modifier = Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                TvSearchField(
                    value = searchInput,
                    onValueChange = { searchInput = it },
                    placeholder = "Search Movie or TV Show...",
                    onSearch = { viewModel.performSearch(searchInput) },
                    modifier = Modifier
                        .weight(1f)
                        .height(60.dp)
                        .onPreviewKeyEvent { keyEvent ->
                            val nativeEvent = keyEvent.nativeKeyEvent
                            if (nativeEvent.action == KeyEvent.ACTION_DOWN && nativeEvent.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                                if (searchHistory.isNotEmpty()) {
                                    firstHistoryFocusRequester.requestFocus()
                                    true
                                } else false
                            } else false
                        }
                )
                TvFocusableBox(
                    modifier = Modifier
                        .size(60.dp)
                        .padding(start = 10.dp)
                        .onPreviewKeyEvent { keyEvent ->
                            val nativeEvent = keyEvent.nativeKeyEvent
                            if (nativeEvent.action == KeyEvent.ACTION_DOWN && nativeEvent.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                                if (searchHistory.isNotEmpty()) {
                                    firstHistoryFocusRequester.requestFocus()
                                    true
                                } else false
                            } else false
                        },
                    onClick = { 
                        viewModel.performSearch(searchInput) 
                    }
                ) {
                    Icon(painter = painterResource(id = R.drawable.ic_go), contentDescription = "Search", tint = Color.White, modifier = Modifier.fillMaxSize().padding(12.dp))
                }
            }

            if (searchHistory.isNotEmpty()) {
                Spacer(modifier = Modifier.height(20.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Recent Searches", color = Color.Gray, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    
                    TvFocusableBox(
                        modifier = Modifier.height(36.dp),
                        onClick = { viewModel.clearSearchHistory() }
                    ) { isFocused ->
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.fillMaxHeight().padding(horizontal = 16.dp)
                        ) {
                            Text(
                                text = "Clear All",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                
                // Search History Chips with Hold-to-delete integration
                LazyRow(modifier = Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(searchHistory) { index, query ->
                        TvFocusableHoldToDeleteBox(
                            modifier = Modifier
                                .height(45.dp)
                                .let { if (index == 0) it.focusRequester(firstHistoryFocusRequester) else it },
                            onTriggerDelete = {
                                val list = searchHistory.toMutableList()
                                list.removeAt(index)
                                viewModel.prefs.edit().putString("streams_search_history", JSONArray(list).toString()).apply()
                                viewModel.loadSearchHistory()
                            },
                            onClick = { searchInput = query; viewModel.performSearch(query) }
                        ) { isFocused, progress ->
                            Row(modifier = Modifier.padding(horizontal = 12.dp).fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
                                Icon(painter = painterResource(id = R.drawable.ic_history), contentDescription = null, tint = Color.Gray, modifier = Modifier.size(20.dp))
                                Text(query, color = Color.White, modifier = Modifier.padding(start = 8.dp))
                            }
                        }
                    }
                }
            }
        }

        Box(modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 10.dp)) {
            if (isScraping) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    CircularProgressIndicator(color = Color(0xFF00BCD4))
                }
            } else {
                val itemsToShow = if (activeCategoryIndex == 0) searchResults else libraryItems
                if (itemsToShow != null) {
                    val objList = (0 until itemsToShow.length()).map { itemsToShow.getJSONObject(it) }
                    LazyRow(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        itemsIndexed(objList) { index, wrapper ->
                            val item = if (wrapper.has("item")) wrapper.getJSONObject("item") else wrapper
                            val sNum = if (wrapper.has("season")) wrapper.getInt("season") else null
                            val eNum = if (wrapper.has("episode")) wrapper.getInt("episode") else null
                            
                            // Wrap in a deletion gesture container only if the tab is deletable (Favourites/Recents)
                            if (isDeletable) {
                                TvFocusableHoldToDeleteBox(
                                    modifier = Modifier.wrapContentSize(),
                                    onTriggerDelete = {
                                        if (activeCategoryIndex == 6) { // Favorites List removal (Instant sync)
                                            viewModel.toggleFavorite(item, isCurrentlyViewingFavorites = true)
                                        } else if (activeCategoryIndex == 7) { // Recently Watched removal
                                            viewModel.removeFromRecentlyPlayed(index)
                                        }
                                    },
                                    onClick = { 
                                        if (activeCategoryIndex == 7) viewModel.selectRecentlyPlayedItem(wrapper)
                                        else viewModel.selectMediaItem(item) 
                                    }
                                ) { isFocused, progress ->
                                    RichMediaCard(
                                        item = item,
                                        viewModel = viewModel,
                                        season = sNum,
                                        episode = eNum,
                                        isFocused = isFocused,
                                        progress = progress,
                                        isDeletable = true
                                    )
                                }
                            } else {
                                // Default focusable box for non-deletable items
                                TvFocusableBox(
                                    modifier = Modifier.wrapContentSize(),
                                    onClick = { viewModel.selectMediaItem(item) }
                                ) { isFocused ->
                                    RichMediaCard(
                                        item = item,
                                        viewModel = viewModel,
                                        season = sNum,
                                        episode = eNum,
                                        isFocused = isFocused,
                                        progress = 0f,
                                        isDeletable = false
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        Box(modifier = Modifier.fillMaxWidth().height(2.dp).background(Color(0xFF00BCD4)).padding(top = 10.dp))

        // Dynamic footer tab row selector
        LazyRow(modifier = Modifier.fillMaxWidth().height(70.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(categories) { index, pair ->
                TvFocusableBox(
                    modifier = Modifier.height(45.dp),
                    onClick = { activeCategoryIndex = index; searchInput = ""; if (index > 0) pair.second() else viewModel.loadSearchHistory() },
                    onFocus = { activeCategoryIndex = index; searchInput = ""; if (index > 0) pair.second() else viewModel.loadSearchHistory() }
                ) { isFocused ->
                    Text(
                        text = pair.first,
                        color = Color.White,
                        fontWeight = if (activeCategoryIndex == index || isFocused) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.padding(horizontal = 25.dp, vertical = 10.dp)
                    )
                }
            }
        }
    }
}