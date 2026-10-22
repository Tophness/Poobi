package com.poobi.tvbrowser.streams

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
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
import com.poobi.tvbrowser.shared.mouseWheelHorizontalScroll
import org.json.JSONArray
import org.json.JSONObject

@Composable
fun StreamsDashboardScreen(
    viewModel: StreamsViewModel,
    searchBarFocusRequester: FocusRequester? = null,
    topTabFocusRequester: FocusRequester? = null,
    cardFocusRequester: FocusRequester? = null
) {
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val searchHistory by viewModel.searchHistory.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    val libraryItems by viewModel.libraryItems.collectAsState()
    val isScraping by viewModel.isScraping.collectAsState()
    val activeCategoryIndex by viewModel.activeCategoryIndex.collectAsState()
    val progressCounts by viewModel.showProgressCounts.collectAsState()
    val liveSuggestions by viewModel.liveSuggestions.collectAsState()
    val predictedQuery by viewModel.predictedQuery.collectAsState()
    var searchInput by remember { mutableStateOf("") }

    val predictedSuffix = remember(searchInput, predictedQuery) {
        if (searchInput.length >= 2 && predictedQuery.startsWith(searchInput, ignoreCase = true)) {
            predictedQuery.substring(searchInput.length)
        } else ""
    }

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

    val isFavorites = activeCategoryIndex == 1
    val isRecentlyWatched = activeCategoryIndex == 2

    val defaultSort = when (activeCategoryIndex) {
        1 -> "Last Added"
        2 -> "Last Accessed"
        else -> "Default"
    }

    val sortPrefKey = "streams_card_sort_cat_$activeCategoryIndex"
    var currentSort by remember(activeCategoryIndex) {
        mutableStateOf(viewModel.prefs.getString(sortPrefKey, defaultSort) ?: defaultSort)
    }

    var showSortDialog by remember { mutableStateOf(false) }

    val searchFieldFocusRequester = searchBarFocusRequester ?: remember { FocusRequester() }
    val searchButtonFocusRequester = remember { FocusRequester() }

    var lastFocusedHistoryIndex by remember { mutableStateOf(0) }
    val lastHistoryFocusRequester = remember { FocusRequester() }

    var lastFocusedCardIndex by remember(activeCategoryIndex, searchResults, currentSort) { mutableStateOf(0) }
    val lastCardFocusRequester = cardFocusRequester ?: remember { FocusRequester() }
    val sortButtonFocusRequester = remember { FocusRequester() }

    val categoryTabFocusRequesters = remember(categories.size) { List(categories.size) { FocusRequester() } }

    val currentTabFocusTarget = remember(activeCategoryIndex, categoryTabFocusRequesters) {
        categoryTabFocusRequesters.getOrNull(activeCategoryIndex) ?: FocusRequester.Default
    }

    val isDeletable = activeCategoryIndex == 1 || activeCategoryIndex == 2

    val hasCardsInCenter = when {
        activeCategoryIndex != 0 -> libraryItems != null && libraryItems!!.length() > 0
        searchResults != null -> searchResults!!.length() > 0
        liveSuggestions.isNotEmpty() -> true
        else -> false
    }

    val searchDownTarget = when {
        hasCardsInCenter -> lastCardFocusRequester
        searchHistory.isNotEmpty() -> lastHistoryFocusRequester
        else -> currentTabFocusTarget
    }

    val bottomUpTarget = when {
        hasCardsInCenter -> lastCardFocusRequester
        activeCategoryIndex == 0 && searchHistory.isNotEmpty() -> lastHistoryFocusRequester
        activeCategoryIndex == 0 -> searchFieldFocusRequester
        else -> topTabFocusRequester ?: FocusRequester.Default
    }

    BackHandler(enabled = searchResults != null) {
        viewModel.clearSearchResults()
        try {
            searchFieldFocusRequester.requestFocus()
        } catch (_: Exception) {}
    }

    val triggerSearch: () -> Unit = {
        val queryToSearch = searchInput.trim()
        if (queryToSearch.isNotEmpty()) {
            viewModel.clearLiveSearch()
            viewModel.performSearch(queryToSearch)
            try {
                lastCardFocusRequester.requestFocus()
            } catch (_: Exception) {}
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 10.dp)) {
        if (activeCategoryIndex == 0) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
                    .focusProperties {
                        if (topTabFocusRequester != null) {
                            up = topTabFocusRequester
                        }
                    },
                verticalAlignment = Alignment.CenterVertically
            ) {
                TvSearchField(
                    value = searchInput,
                    onValueChange = { 
                        searchInput = it
                        viewModel.updateLiveSearch(it)
                    },
                    placeholder = "Search Movie or TV Show...",
                    predictedSuffix = predictedSuffix,
                    onAcceptPrediction = {
                        if (predictedSuffix.isNotEmpty()) {
                            val full = searchInput + predictedSuffix
                            searchInput = full
                            viewModel.updateLiveSearch(full)
                        }
                    },
                    onSearch = triggerSearch,
                    focusRequester = searchFieldFocusRequester,
                    modifier = Modifier
                        .weight(1f)
                        .height(36.dp)
                        .focusProperties {
                            right = searchButtonFocusRequester
                            down = searchDownTarget
                        }
                )
                TvFocusableBox(
                    modifier = Modifier
                        .padding(start = 10.dp)
                        .size(36.dp)
                        .focusRequester(searchButtonFocusRequester)
                        .focusProperties {
                            left = searchFieldFocusRequester
                            down = searchDownTarget
                        },
                    onClick = triggerSearch
                ) { isFocused ->
                    Icon(
                        painter = painterResource(id = R.drawable.ic_go), 
                        contentDescription = "Search", 
                        tint = if (isFocused) Color.Black else Color.White,
                        modifier = Modifier.fillMaxSize().padding(8.dp)
                    )
                }
            }

            if (searchResults == null && liveSuggestions.isEmpty() && searchHistory.isNotEmpty()) {
                Spacer(modifier = Modifier.height(14.dp))
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
                                color = if (isFocused) Color.Black else Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                
                val targetHistoryIndex = lastFocusedHistoryIndex.coerceIn(0, (searchHistory.size - 1).coerceAtLeast(0))
                val searchHistoryListState = rememberLazyListState()
                LazyRow(
                    state = searchHistoryListState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .mouseWheelHorizontalScroll(searchHistoryListState), 
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 4.dp, horizontal = 4.dp)
                ) {
                    itemsIndexed(searchHistory) { index, query ->
                        val isTargetHistory = (index == targetHistoryIndex)
                        TvFocusableHoldToDeleteBox(
                            modifier = Modifier
                                .height(42.dp)
                                .then(if (isTargetHistory) Modifier.focusRequester(lastHistoryFocusRequester) else Modifier)
                                .focusProperties {
                                    up = searchFieldFocusRequester
                                    down = currentTabFocusTarget
                                },
                            onFocus = { lastFocusedHistoryIndex = index },
                            onTriggerDelete = {
                                val list = searchHistory.toMutableList()
                                list.removeAt(index)
                                viewModel.prefs.edit().putString("streams_search_history", JSONArray(list).toString()).apply()
                                viewModel.loadSearchHistory()
                            },
                            onClick = { 
                                searchInput = query
                                viewModel.clearLiveSearch()
                                viewModel.performSearch(query) 
                            }
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
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = query,
                                    color = if (isFocused) Color.Black else Color.White,
                                    fontWeight = if (isFocused) FontWeight.Bold else FontWeight.Normal,
                                    fontSize = 13.sp
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

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(top = 10.dp)
        ) {
            if (isScraping) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    CircularProgressIndicator(color = Color(0xFF00BCD4))
                }
            } else if (activeCategoryIndex == 0 && searchResults == null && liveSuggestions.isNotEmpty()) {
                val suggestionsListState = rememberLazyListState()
                val targetSuggestionIndex = lastFocusedCardIndex.coerceIn(0, (liveSuggestions.size - 1).coerceAtLeast(0))
                LazyRow(
                    state = suggestionsListState,
                    modifier = Modifier
                        .fillMaxSize()
                        .mouseWheelHorizontalScroll(suggestionsListState),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    contentPadding = PaddingValues(vertical = 8.dp, horizontal = 8.dp)
                ) {
                    itemsIndexed(
                        items = liveSuggestions,
                        key = { idx, item -> "sug_${item.optString("id", idx.toString())}" }
                    ) { index, item ->
                        val isTarget = (index == targetSuggestionIndex)
                        TvFocusableBox(
                            modifier = Modifier
                                .wrapContentSize()
                                .then(if (isTarget) Modifier.focusRequester(lastCardFocusRequester) else Modifier)
                                .focusProperties {
                                    up = searchFieldFocusRequester
                                    down = currentTabFocusTarget
                                },
                            onFocus = { lastFocusedCardIndex = index },
                            onClick = { 
                                viewModel.clearLiveSearch()
                                viewModel.selectMediaItem(item) 
                            }
                        ) { isFocused ->
                            RichMediaCard(
                                item = item,
                                viewModel = viewModel,
                                isFocused = isFocused,
                                progress = 0f,
                                isDeletable = false
                            )
                        }
                    }
                }
            } else {
                val itemsToShow = if (activeCategoryIndex == 0) searchResults else libraryItems
                if (itemsToShow != null) {
                    val baseList = if (isFavorites) {
                        (itemsToShow.length() - 1 downTo 0).map { itemsToShow.getJSONObject(it) }
                    } else {
                        (0 until itemsToShow.length()).map { itemsToShow.getJSONObject(it) }
                    }

                    val recentPlayedJson = remember(activeCategoryIndex, currentSort) {
                        viewModel.prefs.getString("streams_recently_played", "[]") ?: "[]"
                    }

                    val recentRankMap = remember(recentPlayedJson) {
                        val map = mutableMapOf<String, Int>()
                        try {
                            val arr = JSONArray(recentPlayedJson)
                            for (i in 0 until arr.length()) {
                                val entry = arr.optJSONObject(i) ?: continue
                                val itm = entry.optJSONObject("item") ?: entry
                                val id = itm.optString("id")
                                val imdb = itm.optString("imdb")
                                if (id.isNotEmpty() && !map.containsKey(id)) map[id] = i
                                if (imdb.isNotEmpty() && !map.containsKey(imdb)) map[imdb] = i
                            }
                        } catch (_: Exception) {}
                        map
                    }

                    val accessTimeMap = remember(activeCategoryIndex, currentSort) {
                        val map = mutableMapOf<String, Long>()
                        try {
                            val json = viewModel.prefs.getString("media_last_accessed_map", "{}") ?: "{}"
                            val obj = JSONObject(json)
                            obj.keys().forEach { k -> map[k] = obj.optLong(k, 0L) }
                        } catch (_: Exception) {}
                        map
                    }

                    val getItemTitle = { wrapper: JSONObject ->
                        val itm = if (wrapper.has("item")) wrapper.optJSONObject("item") ?: wrapper else wrapper
                        val raw = itm.optString("title").ifEmpty {
                            itm.optString("name", "")
                        }.ifEmpty {
                            wrapper.optString("display_title", "")
                        }
                        raw.replace(Regex("\\s\\(\\d{4}\\)$"), "").trim()
                    }

                    val getAccessScore = { wrapper: JSONObject ->
                        val itm = if (wrapper.has("item")) wrapper.optJSONObject("item") ?: wrapper else wrapper
                        val id = itm.optString("id")
                        val imdb = itm.optString("imdb")

                        val t1 = accessTimeMap[id] ?: 0L
                        val t2 = if (imdb.isNotEmpty()) accessTimeMap[imdb] ?: 0L else 0L
                        val directTimestamp = maxOf(t1, t2)
                        if (directTimestamp > 0L) {
                            directTimestamp
                        } else {
                            val r1 = recentRankMap[id]
                            val r2 = if (imdb.isNotEmpty()) recentRankMap[imdb] else null
                            val rank = when {
                                r1 != null && r2 != null -> minOf(r1, r2)
                                r1 != null -> r1
                                r2 != null -> r2
                                else -> null
                            }
                            if (rank != null) {
                                1_000_000L - rank
                            } else {
                                0L
                            }
                        }
                    }

                    val objList = remember(baseList, currentSort, recentRankMap, accessTimeMap) {
                        when (currentSort) {
                            "A-Z" -> baseList.sortedBy { getItemTitle(it).lowercase() }
                            "Last Accessed" -> {
                                if (isRecentlyWatched) {
                                    baseList
                                } else {
                                    baseList.sortedWith { a, b ->
                                        val sA = getAccessScore(a)
                                        val sB = getAccessScore(b)
                                        if (sA != sB) sB.compareTo(sA) else 0
                                    }
                                }
                            }
                            "Last Added" -> {
                                if (isFavorites) {
                                    baseList
                                } else {
                                    (itemsToShow.length() - 1 downTo 0).map { itemsToShow.getJSONObject(it) }
                                }
                            }
                            else -> baseList
                        }
                    }

                    val targetCardIndex = lastFocusedCardIndex.coerceIn(0, (objList.size - 1).coerceAtLeast(0))
                    val mainCardsListState = key(activeCategoryIndex, currentSort) { rememberLazyListState() }

                    if (objList.isEmpty()) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            Text("No items found", color = Color.Gray, fontSize = 16.sp)
                        }
                    } else {
                        Box(modifier = Modifier.fillMaxSize()) {
                            LazyRow(
                                state = mainCardsListState,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .mouseWheelHorizontalScroll(mainCardsListState), 
                                horizontalArrangement = Arrangement.spacedBy(10.dp), 
                                verticalAlignment = Alignment.CenterVertically,
                                contentPadding = PaddingValues(start = 24.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)
                            ) {
                                itemsIndexed(
                                    items = objList,
                                    key = { idx, wrapper ->
                                        val itm = if (wrapper.has("item")) wrapper.getJSONObject("item") else wrapper
                                        val id = itm.optString("id").ifEmpty { itm.optString("imdb", idx.toString()) }
                                        val s = wrapper.optInt("season", -1)
                                        val e = wrapper.optInt("episode", -1)
                                        "${activeCategoryIndex}_${id}_${s}_${e}_$currentSort"
                                    }
                                ) { index, wrapper ->
                                    val item = if (wrapper.has("item")) wrapper.getJSONObject("item") else wrapper
                                    val sNum = if (wrapper.has("season")) wrapper.getInt("season") else null
                                    val eNum = if (wrapper.has("episode")) wrapper.getInt("episode") else null
                                    
                                    val showId = item.optString("id")
                                    val counts = progressCounts[showId] ?: Pair(0, 0)
                                    val unwatchedCount = counts.first
                                    val newerCount = counts.second

                                    val isTarget = (index == targetCardIndex)
                                    val itemFocusModifier = Modifier
                                        .then(if (isTarget) Modifier.focusRequester(lastCardFocusRequester) else Modifier)
                                        .focusProperties {
                                            up = if (activeCategoryIndex == 0) searchFieldFocusRequester else (topTabFocusRequester ?: FocusRequester.Default)
                                            down = currentTabFocusTarget
                                            if (index == 0) {
                                                left = sortButtonFocusRequester
                                            }
                                        }

                                    if (isDeletable) {
                                        TvFocusableHoldToDeleteBox(
                                            modifier = Modifier.wrapContentSize().then(itemFocusModifier),
                                            onFocus = { lastFocusedCardIndex = index },
                                            onTriggerDelete = {
                                                if (activeCategoryIndex == 1) {
                                                    viewModel.toggleFavorite(item, isCurrentlyViewingFavorites = true)
                                                } else if (activeCategoryIndex == 2) {
                                                    viewModel.removeFromRecentlyPlayed(wrapper)
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
                                            onFocus = { lastFocusedCardIndex = index },
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

                            TvFocusableBox(
                                modifier = Modifier
                                    .align(Alignment.CenterStart)
                                    .offset(x = (-32).dp)
                                    .size(40.dp)
                                    .focusRequester(sortButtonFocusRequester)
                                    .focusProperties {
                                        up = if (activeCategoryIndex == 0) searchFieldFocusRequester else (topTabFocusRequester ?: FocusRequester.Default)
                                        down = currentTabFocusTarget
                                        right = lastCardFocusRequester
                                    },
                                onClick = { showSortDialog = true }
                            ) { isFocused ->
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Icon(
                                        painter = painterResource(id = R.drawable.ic_sort),
                                        contentDescription = "Sort Options",
                                        tint = if (isFocused) Color.Black else Color.White,
                                        modifier = Modifier.size(22.dp)
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
                .height(72.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 8.dp, horizontal = 4.dp)
        ) {
            itemsIndexed(categories) { index, pair ->
                val isActive = activeCategoryIndex == index
                TvFocusableBox(
                    modifier = Modifier
                        .height(42.dp)
                        .focusRequester(categoryTabFocusRequesters[index])
                        .focusProperties {
                            up = bottomUpTarget
                        },
                    onClick = { 
                        if (activeCategoryIndex != index) {
                            viewModel.setActiveCategoryIndex(index)
                            searchInput = ""
                            viewModel.clearLiveSearch()
                            if (index > 0) pair.second() else viewModel.loadSearchHistory() 
                        }
                    },
                    onFocus = { 
                        if (activeCategoryIndex != index) {
                            viewModel.setActiveCategoryIndex(index)
                            searchInput = ""
                            viewModel.clearLiveSearch()
                            if (index > 0) pair.second() else viewModel.loadSearchHistory() 
                        }
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
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp)
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

    if (showSortDialog) {
        val sortOptions = when (activeCategoryIndex) {
            1 -> listOf("Last Added", "Last Accessed", "A-Z")
            2 -> listOf("Last Accessed", "A-Z")
            else -> listOf("Default", "A-Z")
        }

        AlertDialog(
            onDismissRequest = { showSortDialog = false },
            title = { Text("Sort Options", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp) },
            containerColor = Color(0xFF222225),
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    sortOptions.forEach { option ->
                        val isSelected = currentSort == option
                        TvFocusableBox(
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            onClick = {
                                viewModel.prefs.edit().putString(sortPrefKey, option).apply()
                                currentSort = option
                                showSortDialog = false
                            }
                        ) { isFocused ->
                            Row(
                                modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = option,
                                    color = if (isFocused) Color.Black else Color.White,
                                    fontWeight = if (isSelected || isFocused) FontWeight.Bold else FontWeight.Normal,
                                    fontSize = 15.sp,
                                    modifier = Modifier.weight(1f)
                                )
                                if (isSelected) {
                                    Icon(
                                        painter = painterResource(id = R.drawable.ic_watched),
                                        contentDescription = "Selected",
                                        tint = if (isFocused) Color.Black else Color(0xFF00BCD4),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TvFocusableBox(
                    modifier = Modifier.height(40.dp).width(90.dp),
                    defaultColor = Color(0xFF333338),
                    shape = RoundedCornerShape(20.dp),
                    onClick = { showSortDialog = false }
                ) { isFocused ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Close", color = if (isFocused) Color.Black else Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
        )
    }
}