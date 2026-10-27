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
    cardFocusRequester: FocusRequester? = null,
    subTabFocusRequester: FocusRequester? = null
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

    val isLandscapeCard = viewModel.isLandscapeCard

    val isTraktAuthed = remember {
        try {
            com.chaquo.python.Python.getInstance().getModule("modules.control").callAttr("setting", "trakt.authed").toString() == "yes"
        } catch (_: Exception) { false }
    }

    val forYouPills = remember(isTraktAuthed) {
        val list = mutableListOf<Triple<String, Boolean, () -> Unit>>()
        if (viewModel.prefs.getBoolean("feed_for_you_favorites", true)) list.add(Triple("Favourites", false, { viewModel.loadFavorites() }))
        if (viewModel.prefs.getBoolean("feed_for_you_recents", true)) list.add(Triple("Recently Watched", false, { viewModel.loadRecentlyPlayed() }))
        if (viewModel.prefs.getBoolean("feed_for_you_unfinished", true)) list.add(Triple("Unfinished Shows", false, { viewModel.loadUnfinishedShows() }))
        if (viewModel.prefs.getBoolean("feed_for_you_because", true)) list.add(Triple("Because You Watched", false, { viewModel.loadBecauseYouWatched() }))
        if (viewModel.prefs.getBoolean("feed_for_you_spotlight", true)) list.add(Triple("Cast in Spotlight", false, { viewModel.loadCastInSpotlight() }))

        val traktEnabled = viewModel.prefs.getBoolean("feed_for_you_trakt", true)
        if (traktEnabled) {
            if (viewModel.prefs.getBoolean("feed_trakt_watched", true)) list.add(Triple("Most Watched", true, { viewModel.loadTraktFeed("trakt_watched_weekly", "watched") }))
            if (viewModel.prefs.getBoolean("feed_trakt_anticipated", true)) list.add(Triple("Anticipated", true, { viewModel.loadTraktFeed("trakt_anticipated", "anticipated") }))
            if (viewModel.prefs.getBoolean("feed_trakt_collected", true)) list.add(Triple("Most Collected", true, { viewModel.loadTraktFeed("trakt_collected", "collected") }))

            if (isTraktAuthed) {
                if (viewModel.prefs.getBoolean("feed_trakt_watchlist", true)) list.add(Triple("Watchlist", true, { viewModel.loadTraktFeed("trakt_user_watchlist", "watchlist") }))
                if (viewModel.prefs.getBoolean("feed_trakt_recommendations", true)) list.add(Triple("For Me", true, { viewModel.loadTraktFeed("trakt_user_recs", "recommendations") }))
            }
        }
        list
    }

    val moviesPills = remember {
        val list = mutableListOf<Triple<String, Boolean, () -> Unit>>()
        if (viewModel.prefs.getBoolean("feed_movies_trending_today", true)) list.add(Triple("Trending Today", false, { viewModel.loadLibraryCategory("Trending Today", "get_trending", "movie") }))
        if (viewModel.prefs.getBoolean("feed_movies_trending_week", true)) list.add(Triple("Trending This Week", false, { viewModel.loadLibraryCategory("Trending Week", "get_trending_week", "movie") }))
        if (viewModel.prefs.getBoolean("feed_movies_in_cinemas", true)) list.add(Triple("In Cinemas", false, { viewModel.loadLibraryCategory("In Cinemas", "get_movies_in_cinemas") }))
        if (viewModel.prefs.getBoolean("feed_movies_upcoming", true)) list.add(Triple("Upcoming", false, { viewModel.loadLibraryCategory("Upcoming", "get_upcoming_movies") }))
        if (viewModel.prefs.getBoolean("feed_movies_popular", true)) list.add(Triple("Popular", false, { viewModel.loadLibraryCategory("Popular", "get_popular", "movie") }))
        if (viewModel.prefs.getBoolean("feed_movies_top_rated", true)) list.add(Triple("Top Rated", false, { viewModel.loadLibraryCategory("Top Rated", "get_top_rated", "movie") }))
        if (viewModel.prefs.getBoolean("feed_movies_hidden_gems", true)) list.add(Triple("Hidden Gems", false, {
            val params = JSONObject().apply {
                put("vote_average.gte", 7.8)
                put("vote_count.gte", 300)
                put("sort_by", "vote_average.desc")
            }
            viewModel.loadDiscoverCategory("discover_movie_hidden_gems", true, params)
        }))

        val movieGenres = listOf(
            "Action" to 28, "Adventure" to 12, "Animation" to 16, "Comedy" to 35,
            "Crime" to 80, "Documentary" to 99, "Drama" to 18, "Family" to 10751,
            "Fantasy" to 14, "History" to 36, "Horror" to 27, "Music" to 10402,
            "Mystery" to 9648, "Romance" to 10749, "Sci-Fi" to 878, "Thriller" to 53,
            "War" to 10752, "Western" to 37
        )
        movieGenres.forEach { (genreName, genreId) ->
            if (viewModel.prefs.getBoolean("genre_movie_$genreId", true)) {
                list.add(Triple(genreName, false, {
                    val p = JSONObject().apply {
                        put("with_genres", genreId)
                        put("sort_by", "popularity.desc")
                    }
                    viewModel.loadDiscoverCategory("discover_movie_genre_$genreId", true, p)
                }))
            }
        }
        list
    }

    val tvShowsPills = remember {
        val list = mutableListOf<Triple<String, Boolean, () -> Unit>>()
        if (viewModel.prefs.getBoolean("feed_tv_popular", true)) list.add(Triple("Popular TV", false, { viewModel.loadLibraryCategory("Popular TV", "get_popular", "tv") }))
        if (viewModel.prefs.getBoolean("feed_tv_trending_week", true)) list.add(Triple("Trending This Week", false, { viewModel.loadLibraryCategory("Trending TV Week", "get_trending_week", "tv") }))
        if (viewModel.prefs.getBoolean("feed_tv_airing_today", true)) list.add(Triple("Airing Today", false, { viewModel.loadLibraryCategory("Airing Today", "get_airing_today") }))
        if (viewModel.prefs.getBoolean("feed_tv_airing_week", true)) list.add(Triple("Airing This Week", false, { viewModel.loadLibraryCategory("Airing This Week", "get_on_the_air") }))
        if (viewModel.prefs.getBoolean("feed_tv_top_rated", true)) list.add(Triple("Top Rated TV", false, { viewModel.loadLibraryCategory("Top Rated TV", "get_top_rated_tv") }))

        val tvGenres = listOf(
            "Action & Adventure" to 10759, "Animation" to 16, "Comedy" to 35, "Crime" to 80,
            "Documentary" to 99, "Drama" to 18, "Kids" to 10762, "Mystery" to 9648,
            "News" to 10763, "Reality TV" to 10764, "Sci-Fi & Fantasy" to 10765,
            "Soap" to 10766, "Talk Shows" to 10767, "War & Politics" to 10768
        )
        tvGenres.forEach { (genreName, genreId) ->
            if (viewModel.prefs.getBoolean("genre_tv_$genreId", true)) {
                list.add(Triple(genreName, false, {
                    val p = JSONObject().apply {
                        put("with_genres", genreId)
                        put("sort_by", "popularity.desc")
                    }
                    viewModel.loadDiscoverCategory("discover_tv_genre_$genreId", false, p)
                }))
            }
        }
        list
    }

    val discoverPills = remember {
        val list = mutableListOf<Triple<String, Boolean, () -> Unit>>()
        val platforms = listOf(
            "Netflix" to (8 to true),
            "Disney+" to (337 to true),
            "Amazon Prime" to (9 to true),
            "Apple TV+" to (350 to true),
            "Max" to (1899 to true)
        )
        platforms.forEach { (name, info) ->
            val (providerId, isMovie) = info
            if (viewModel.prefs.getBoolean("feed_platform_$providerId", true)) {
                list.add(Triple(name, false, {
                    val p = JSONObject().apply {
                        put("with_watch_providers", providerId)
                        put("watch_region", "US")
                        put("sort_by", "popularity.desc")
                    }
                    viewModel.loadDiscoverCategory("discover_platform_$providerId", isMovie, p)
                }))
            }
        }

        val eras = listOf(
            "2000s Hits" to (2000 to 2009),
            "90s Nostalgia" to (1990 to 1999),
            "80s Classics" to (1980 to 1989),
            "70s Cinema" to (1970 to 1979)
        )
        eras.forEach { (eraName, range) ->
            if (viewModel.prefs.getBoolean("feed_era_${range.first}", true)) {
                list.add(Triple(eraName, false, {
                    val p = JSONObject().apply {
                        put("primary_release_date.gte", "${range.first}-01-01")
                        put("primary_release_date.lte", "${range.second}-12-31")
                        put("sort_by", "popularity.desc")
                    }
                    viewModel.loadDiscoverCategory("discover_era_${range.first}", true, p)
                }))
            }
        }

        if (viewModel.prefs.getBoolean("feed_world_anime", true)) list.add(Triple("Anime", false, {
            val p = JSONObject().apply {
                put("with_genres", 16)
                put("with_original_language", "ja")
                put("sort_by", "popularity.desc")
            }
            viewModel.loadDiscoverCategory("discover_anime", false, p)
        }))

        if (viewModel.prefs.getBoolean("feed_world_kdrama", true)) list.add(Triple("K-Dramas", false, {
            val p = JSONObject().apply {
                put("with_origin_country", "KR")
                put("sort_by", "popularity.desc")
            }
            viewModel.loadDiscoverCategory("discover_kdrama", false, p)
        }))

        if (viewModel.prefs.getBoolean("feed_world_british", true)) list.add(Triple("British Mysteries", false, {
            val p = JSONObject().apply {
                put("with_origin_country", "GB")
                put("with_genres", "18,9648")
                put("sort_by", "popularity.desc")
            }
            viewModel.loadDiscoverCategory("discover_british", false, p)
        }))

        if (viewModel.prefs.getBoolean("feed_curated_cult", true)) list.add(Triple("Cult Classics", false, {
            val p = JSONObject().apply {
                put("primary_release_date.lte", "2010-01-01")
                put("vote_average.gte", 7.5)
                put("vote_count.gte", 200)
                put("sort_by", "popularity.desc")
            }
            viewModel.loadDiscoverCategory("discover_cult_classics", true, p)
        }))
        list
    }

    val forYouPillTitle by viewModel.forYouPillTitle.collectAsState()
    val moviesPillTitle by viewModel.moviesPillTitle.collectAsState()
    val tvShowsPillTitle by viewModel.tvShowsPillTitle.collectAsState()
    val discoverPillTitle by viewModel.discoverPillTitle.collectAsState()

    val currentPills = when (activeCategoryIndex) {
        1 -> forYouPills
        2 -> moviesPills
        3 -> tvShowsPills
        4 -> discoverPills
        else -> emptyList()
    }

    val currentActivePillIndex = when (activeCategoryIndex) {
        1 -> forYouPills.indexOfFirst { it.first == forYouPillTitle }.let { if (it >= 0) it else 0 }
        2 -> moviesPills.indexOfFirst { it.first == moviesPillTitle }.let { if (it >= 0) it else 0 }
        3 -> tvShowsPills.indexOfFirst { it.first == tvShowsPillTitle }.let { if (it >= 0) it else 0 }
        4 -> discoverPills.indexOfFirst { it.first == discoverPillTitle }.let { if (it >= 0) it else 0 }
        else -> 0
    }

    val mainCategoryTargetRequester = subTabFocusRequester ?: remember { FocusRequester() }
    val bottomSubPillFocusRequester = remember { FocusRequester() }

    val categories = listOf(
        "Search" to { viewModel.loadSearchHistory() },
        "For You" to { forYouPills.getOrNull(currentActivePillIndex)?.third?.invoke() ?: viewModel.loadFavorites() },
        "Movies" to { moviesPills.getOrNull(currentActivePillIndex)?.third?.invoke() ?: viewModel.loadLibraryCategory("Trending Today", "get_trending", "movie") },
        "TV Shows" to { tvShowsPills.getOrNull(currentActivePillIndex)?.third?.invoke() ?: viewModel.loadLibraryCategory("Popular TV", "get_popular", "tv") },
        "Discover" to { discoverPills.getOrNull(currentActivePillIndex)?.third?.invoke() }
    )

    val activePillTitle = currentPills.getOrNull(currentActivePillIndex)?.first ?: ""
    val isFavorites = activeCategoryIndex == 1 && activePillTitle == "Favourites"
    val isRecentlyWatched = activeCategoryIndex == 1 && activePillTitle == "Recently Watched"

    val defaultSort = when {
        isFavorites -> "Last Added"
        isRecentlyWatched -> "Last Accessed"
        else -> "Default"
    }

    val sortPrefKey = "streams_card_sort_cat_${activeCategoryIndex}_$currentActivePillIndex"
    var currentSort by remember(activeCategoryIndex, currentActivePillIndex) {
        mutableStateOf(viewModel.prefs.getString(sortPrefKey, defaultSort) ?: defaultSort)
    }

    var showSortDialog by remember { mutableStateOf(false) }

    val searchFieldFocusRequester = searchBarFocusRequester ?: remember { FocusRequester() }
    val searchButtonFocusRequester = remember { FocusRequester() }

    var lastFocusedHistoryIndex by remember { mutableStateOf(0) }
    val lastHistoryFocusRequester = remember { FocusRequester() }

    var lastFocusedCardIndex by remember(activeCategoryIndex, currentActivePillIndex, searchResults, currentSort) { 
        mutableStateOf(if (viewModel.isReturningFromDetails) viewModel.lastViewedCardIndex else 0) 
    }
    val lastCardFocusRequester = cardFocusRequester ?: remember { FocusRequester() }
    val sortButtonFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        if (viewModel.isReturningFromDetails) {
            kotlinx.coroutines.delay(120)
            try {
                lastCardFocusRequester.requestFocus()
            } catch (_: Exception) {}
            viewModel.isReturningFromDetails = false
        }
    }

    val categoryTabFocusRequesters = remember(categories.size) { List(categories.size) { FocusRequester() } }

    val currentTabFocusTarget = remember(activeCategoryIndex, categoryTabFocusRequesters) {
        categoryTabFocusRequesters.getOrNull(activeCategoryIndex) ?: FocusRequester.Default
    }

    val isDeletable = isFavorites || isRecentlyWatched

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

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 6.dp)) {
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 4.dp, horizontal = 4.dp)
        ) {
            itemsIndexed(categories) { index, pair ->
                val isActive = activeCategoryIndex == index
                val tabFocusRequester = categoryTabFocusRequesters[index]

                TvFocusableBox(
                    modifier = Modifier
                        .height(38.dp)
                        .focusRequester(tabFocusRequester)
                        .then(if (isActive) Modifier.focusRequester(mainCategoryTargetRequester) else Modifier)
                        .focusProperties {
                            up = topTabFocusRequester ?: FocusRequester.Default
                            down = if (activeCategoryIndex == 0) searchFieldFocusRequester else (if (hasCardsInCenter) lastCardFocusRequester else bottomSubPillFocusRequester)
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
                                },
                                shape = RoundedCornerShape(8.dp)
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
                                    .fillMaxWidth(0.6f)
                                    .height(3.dp)
                                    .background(Color(0xFFFFB74D), shape = RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp))
                            )
                        }
                    }
                }
            }
        }

        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF333338)))

        if (activeCategoryIndex == 0) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
                    .focusProperties {
                        up = categoryTabFocusRequesters.getOrNull(0) ?: FocusRequester.Default
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
                            up = categoryTabFocusRequesters.getOrNull(0) ?: FocusRequester.Default
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
                            up = categoryTabFocusRequesters.getOrNull(0) ?: FocusRequester.Default
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
                Spacer(modifier = Modifier.height(10.dp))
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
                        .padding(top = 6.dp)
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
                                    down = lastCardFocusRequester
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
                .padding(top = 4.dp)
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
                    val initialScrollIdx = if (viewModel.isReturningFromDetails) (viewModel.lastViewedCardIndex - 1).coerceAtLeast(0) else 0
                    val mainCardsListState = key(activeCategoryIndex, currentActivePillIndex, currentSort) { 
                        rememberLazyListState(initialFirstVisibleItemIndex = initialScrollIdx) 
                    }

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
                                        "${activeCategoryIndex}_${currentActivePillIndex}_${id}_${s}_${e}_$currentSort"
                                    }
                                ) { index, wrapper ->
                                    if (index >= objList.size - 4 && !isFavorites && !isRecentlyWatched) {
                                        viewModel.loadMoreFeedItems()
                                    }

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
                                            up = if (activeCategoryIndex == 0) searchFieldFocusRequester else currentTabFocusTarget
                                            down = if (activeCategoryIndex != 0 && currentPills.isNotEmpty()) bottomSubPillFocusRequester else FocusRequester.Cancel
                                            if (index == 0) {
                                                left = sortButtonFocusRequester
                                            }
                                        }

                                    if (isDeletable) {
                                        TvFocusableHoldToDeleteBox(
                                            modifier = Modifier.wrapContentSize().then(itemFocusModifier),
                                            onFocus = { lastFocusedCardIndex = index },
                                            onTriggerDelete = {
                                                if (isFavorites) {
                                                    viewModel.toggleFavorite(item, isCurrentlyViewingFavorites = true)
                                                } else if (isRecentlyWatched) {
                                                    viewModel.removeFromRecentlyPlayed(wrapper)
                                                }
                                            },
                                            onClick = { 
                                                viewModel.lastViewedCardIndex = index
                                                viewModel.isReturningFromDetails = true
                                                if (isRecentlyWatched) viewModel.selectRecentlyPlayedItem(wrapper)
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
                                                isDeletable = true,
                                                isLandscape = isLandscapeCard
                                            )
                                        }
                                    } else {
                                        TvFocusableBox(
                                            modifier = Modifier.wrapContentSize().then(itemFocusModifier),
                                            onFocus = { lastFocusedCardIndex = index },
                                            onClick = { 
                                                viewModel.lastViewedCardIndex = index
                                                viewModel.isReturningFromDetails = true
                                                viewModel.selectMediaItem(item) 
                                            }
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
                                                isDeletable = false,
                                                isLandscape = isLandscapeCard
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
                                        up = if (activeCategoryIndex == 0) searchFieldFocusRequester else currentTabFocusTarget
                                        down = if (activeCategoryIndex != 0 && currentPills.isNotEmpty()) bottomSubPillFocusRequester else FocusRequester.Cancel
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

        if (activeCategoryIndex != 0 && currentPills.isNotEmpty()) {
            val pillListState = rememberLazyListState()

            LaunchedEffect(currentActivePillIndex, activeCategoryIndex) {
                if (currentPills.isNotEmpty() && currentActivePillIndex in currentPills.indices) {
                    try {
                        pillListState.animateScrollToItem(currentActivePillIndex)
                    } catch (_: Exception) {}
                }
            }

            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF333338)))

            LazyRow(
                state = pillListState,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .padding(vertical = 4.dp)
                    .mouseWheelHorizontalScroll(pillListState),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                itemsIndexed(currentPills) { pIdx, pill ->
                    val isSelected = pIdx == currentActivePillIndex

                    TvFocusableBox(
                        modifier = Modifier
                            .height(34.dp)
                            .then(if (isSelected) Modifier.focusRequester(bottomSubPillFocusRequester) else Modifier)
                            .onPreviewKeyEvent { keyEvent ->
                                val nativeEvent = keyEvent.nativeKeyEvent
                                if (nativeEvent.action == android.view.KeyEvent.ACTION_DOWN &&
                                    nativeEvent.keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP) {
                                    try {
                                        lastCardFocusRequester.requestFocus()
                                        return@onPreviewKeyEvent true
                                    } catch (_: Exception) {}
                                }
                                false
                            }
                            .focusProperties {
                                up = if (hasCardsInCenter) lastCardFocusRequester else currentTabFocusTarget
                                down = FocusRequester.Cancel
                            },
                        defaultColor = if (isSelected) Color(0xFF00BCD4).copy(alpha = 0.25f) else Color(0xFF222225),
                        onClick = {
                            if (currentActivePillIndex != pIdx) {
                                when (activeCategoryIndex) {
                                    1 -> viewModel.forYouPillTitle.value = pill.first
                                    2 -> viewModel.moviesPillTitle.value = pill.first
                                    3 -> viewModel.tvShowsPillTitle.value = pill.first
                                    4 -> viewModel.discoverPillTitle.value = pill.first
                                }
                            }
                            pill.third()
                        },
                        onFocus = {
                            if (currentActivePillIndex != pIdx) {
                                when (activeCategoryIndex) {
                                    1 -> viewModel.forYouPillTitle.value = pill.first
                                    2 -> viewModel.moviesPillTitle.value = pill.first
                                    3 -> viewModel.tvShowsPillTitle.value = pill.first
                                    4 -> viewModel.discoverPillTitle.value = pill.first
                                }
                                pill.third()
                            }
                        }
                    ) { isFocused ->
                        Row(
                            modifier = Modifier.fillMaxHeight().padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (pill.second) {
                                androidx.compose.foundation.Image(
                                    painter = painterResource(id = R.drawable.trakt),
                                    contentDescription = "Trakt",
                                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                                    modifier = Modifier
                                        .height(14.dp)
                                        .aspectRatio(434f / 147f)
                                        .padding(end = 6.dp)
                                )
                            }
                            Text(
                                text = pill.first,
                                color = if (isFocused) Color.Black else if (isSelected) Color(0xFF00BCD4) else Color.LightGray,
                                fontSize = 12.sp,
                                fontWeight = if (isSelected || isFocused) FontWeight.Bold else FontWeight.Normal
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