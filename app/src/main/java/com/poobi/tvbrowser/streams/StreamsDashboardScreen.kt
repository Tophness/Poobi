package com.poobi.tvbrowser.streams

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.poobi.tvbrowser.browser.HoldToDeleteCloseButton
import com.poobi.tvbrowser.browser.TvFocusableHoldToDeleteBox
import com.poobi.tvbrowser.shared.TvFocusableBox
import com.poobi.tvbrowser.shared.TvSearchField
import com.poobi.tvbrowser.shared.KeyTracker
import kotlinx.coroutines.delay
import org.json.JSONArray

@Composable
fun StreamsDashboardScreen(viewModel: StreamsViewModel) {
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val searchHistory by viewModel.searchHistory.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    val libraryItems by viewModel.libraryItems.collectAsState()
    val isScraping by viewModel.isScraping.collectAsState()
    val activeCategoryIndex by viewModel.activeCategoryIndex.collectAsState()
    val progressCounts by viewModel.showProgressCounts.collectAsState()
    var searchInput by remember { mutableStateOf("") }
    val firstHistoryFocusRequester = remember { FocusRequester() }

    val searchFieldFocusRequester = remember { FocusRequester() }
    val firstResultFocusRequester = remember { FocusRequester() }

    val categories = listOf(
        "Search" to { viewModel.loadSearchHistory() },
        "Favourites" to { viewModel.loadFavorites() },
        "Recently Watched" to { viewModel.loadRecentlyPlayed() },
        "Trending" to { viewModel.loadLibraryCategory("Trending Today", "get_trending", "all") },
        "In Cinemas" to { viewModel.loadLibraryCategory("In Cinemas Now", "get_movies_in_cinemas") },
        "Upcoming" to { viewModel.loadLibraryCategory("Upcoming Movies", "get_upcoming_movies") },
        "Popular TV" to { viewModel.loadLibraryCategory("Popular TV Shows", "get_popular", "tv") },
        "Top Rated" to { viewModel.loadLibraryCategory("Top Rated Movies", "get_top_rated", "movie") }
    )

    val isDeletable = activeCategoryIndex == 1 || activeCategoryIndex == 2

    LaunchedEffect(activeCategoryIndex, searchResults) {
        if (activeCategoryIndex == 0) {
            val startTime = System.currentTimeMillis()
            if (searchResults == null) {
                try {
                    delay(100)
                    if (KeyTracker.lastKeyPressTime < startTime) {
                        searchFieldFocusRequester.requestFocus()
                    }
                } catch (e: Exception) {}
            } else if (searchResults!!.length() > 0) {
                try {
                    delay(200)
                    if (KeyTracker.lastKeyPressTime < startTime) {
                        firstResultFocusRequester.requestFocus()
                    }
                } catch (e: Exception) {}
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 10.dp)) {
        if (activeCategoryIndex == 0 && searchResults == null) {
            Row(modifier = Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                TvSearchField(
                    value = searchInput,
                    onValueChange = { searchInput = it },
                    placeholder = "Search Movie or TV Show...",
                    onSearch = { viewModel.performSearch(searchInput) },
                    focusRequester = searchFieldFocusRequester,
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
                
                LazyRow(
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp), 
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 8.dp, horizontal = 4.dp)
                ) {
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
                            Row(
                                modifier = Modifier
                                    .padding(horizontal = 12.dp)
                                    .fillMaxHeight(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_history),
                                    contentDescription = null,
                                    tint = Color.Gray,
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    text = query,
                                    color = Color.White
                                )
                                if (isFocused || progress > 0f) {
                                    HoldToDeleteCloseButton(progress = progress)
                                }
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
                    LazyRow(
                        modifier = Modifier.fillMaxSize(), 
                        horizontalArrangement = Arrangement.spacedBy(10.dp), 
                        verticalAlignment = Alignment.CenterVertically,
                        contentPadding = PaddingValues(vertical = 8.dp, horizontal = 8.dp)
                    ) {
                        itemsIndexed(objList) { index, wrapper ->
                            val item = if (wrapper.has("item")) wrapper.getJSONObject("item") else wrapper
                            val sNum = if (wrapper.has("season")) wrapper.getInt("season") else null
                            val eNum = if (wrapper.has("episode")) wrapper.getInt("episode") else null
                            
                            val showId = item.optString("id")
                            val counts = progressCounts[showId] ?: Pair(0, 0)
                            val unwatchedCount = counts.first
                            val newerCount = counts.second

                            val isFirstResult = index == 0 && activeCategoryIndex == 0 && searchResults != null
                            val itemFocusModifier = if (isFirstResult) Modifier.focusRequester(firstResultFocusRequester) else Modifier

                            if (isDeletable) {
                                TvFocusableHoldToDeleteBox(
                                    modifier = Modifier.wrapContentSize().then(itemFocusModifier),
                                    onTriggerDelete = {
                                        if (activeCategoryIndex == 1) {
                                            viewModel.toggleFavorite(item, isCurrentlyViewingFavorites = true)
                                        } else if (activeCategoryIndex == 2) {
                                            viewModel.removeFromRecentlyPlayed(index)
                                        }
                                    },
                                    onClick = { 
                                        if (activeCategoryIndex == 2) viewModel.selectRecentlyPlayedItem(wrapper)
                                        else viewModel.selectMediaItem(item) 
                                    }
                                ) { isFocused, progress ->
                                    RichMediaCard(
                                        item = item,
                                        viewModel = viewModel,
                                        unwatchedCount = unwatchedCount,
                                        newerCount = newerCount,
                                        season = sNum,
                                        episode = eNum,
                                        isFocused = isFocused,
                                        progress = progress,
                                        isDeletable = true
                                    )
                                }
                            } else {
                                TvFocusableBox(
                                    modifier = Modifier.wrapContentSize().then(itemFocusModifier),
                                    onClick = { viewModel.selectMediaItem(item) }
                                ) { isFocused ->
                                    RichMediaCard(
                                        item = item,
                                        viewModel = viewModel,
                                        unwatchedCount = unwatchedCount,
                                        newerCount = newerCount,
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

        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 8.dp, horizontal = 4.dp)
        ) {
            itemsIndexed(categories) { index, pair ->
                val isActive = activeCategoryIndex == index
                TvFocusableBox(
                    modifier = Modifier.height(45.dp),
                    onClick = { 
                        viewModel.setActiveCategoryIndex(index)
                        searchInput = ""
                        if (index > 0) pair.second() else viewModel.loadSearchHistory() 
                    },
                    onFocus = { 
                        viewModel.setActiveCategoryIndex(index)
                        searchInput = ""
                        if (index > 0) pair.second() else viewModel.loadSearchHistory() 
                    }
                ) { isFocused ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                when {
                                    isFocused -> Color.Transparent
                                    isActive -> Color(0xFF2E2E35)
                                    else -> Color(0xFF1F1F23)
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = pair.first,
                            color = when {
                                isFocused -> Color.Black
                                isActive -> Color(0xFFFFB74D)
                                else -> Color.LightGray
                            },
                            fontSize = 14.sp,
                            fontWeight = if (isActive || isFocused) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                        )
                        if (isActive && !isFocused) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth(0.5f)
                                    .height(3.dp)
                                    .background(Color(0xFFFFB74D), shape = RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp))
                            )
                        }
                    }
                }
            }
        }
    }
}