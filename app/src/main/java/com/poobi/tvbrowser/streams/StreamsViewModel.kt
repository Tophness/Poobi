package com.poobi.tvbrowser.streams

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import android.util.LruCache
import androidx.compose.runtime.mutableStateMapOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.chaquo.python.Python
import com.poobi.tvbrowser.shared.StreamResumeManager
import com.poobi.tvbrowser.shared.SubtitleData
import com.poobi.tvbrowser.torrent.TorrentSortCriteria
import com.poobi.tvbrowser.tvhome.TvHomeSyncManager
import com.poobi.tvbrowser.torrent.TorrentStreamServer
import com.poobi.tvbrowser.torrent.TorrentSorter
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch

sealed class StreamsEvent {
    data class PlayVideo(
        val url: String, 
        val title: String, 
        val headers: Map<String, String>, 
        val subtitles: Map<String, Map<String, String>>, 
        val item: JSONObject, 
        val season: Int?, 
        val episode: Int?, 
        val nextEpisode: JSONObject? = null,
        val isWebpage: Boolean = false,
        val isTrailer: Boolean = false,
        val alternativeUrls: List<String> = emptyList(),
        val alternativeNames: List<String> = emptyList()
    ) : StreamsEvent()
    data class ShowToast(val message: String) : StreamsEvent()
    data class ShowSubtitlePicker(val subs: JSONArray) : StreamsEvent()
    data class AddSubtitlesBatch(val subtitles: List<SubtitleData>) : StreamsEvent()
}

class StreamsViewModel(application: Application) : AndroidViewModel(application) {

    private val _showSubtitleWaitDialog = MutableStateFlow<String?>(null)
    val showSubtitleWaitDialog: StateFlow<String?> = _showSubtitleWaitDialog.asStateFlow()

    private val context = application.applicationContext
    val prefs: SharedPreferences = context.getSharedPreferences("BrowserSettings", Context.MODE_PRIVATE)

    private val _searchHistory = MutableStateFlow<List<String>>(emptyList())
    val searchHistory: StateFlow<List<String>> = _searchHistory.asStateFlow()

    private val _searchResults = MutableStateFlow<JSONArray?>(null)
    val searchResults: StateFlow<JSONArray?> = _searchResults.asStateFlow()

    private val _libraryItems = MutableStateFlow<JSONArray?>(null)
    val libraryItems: StateFlow<JSONArray?> = _libraryItems.asStateFlow()

    private val _selectedItem = MutableStateFlow<JSONObject?>(null)
    val selectedItem: StateFlow<JSONObject?> = _selectedItem.asStateFlow()
    
    private val _itemDetails = MutableStateFlow<JSONObject?>(null)
    val itemDetails: StateFlow<JSONObject?> = _itemDetails.asStateFlow()

    private val _itemEpisodes = MutableStateFlow<JSONArray?>(null)
    val itemEpisodes: StateFlow<JSONArray?> = _itemEpisodes.asStateFlow()

    private val _lastWatchedEpisode = MutableStateFlow<Int?>(null)
    val lastWatchedEpisode: StateFlow<Int?> = _lastWatchedEpisode.asStateFlow()

    private val _itemSeasons = MutableStateFlow<JSONArray?>(null)
    val itemSeasons: StateFlow<JSONArray?> = _itemSeasons.asStateFlow()

    private val _itemCast = MutableStateFlow<JSONArray?>(null)
    val itemCast: StateFlow<JSONArray?> = _itemCast.asStateFlow()

    private val _itemRecommendations = MutableStateFlow<JSONArray?>(null)
    val itemRecommendations: StateFlow<JSONArray?> = _itemRecommendations.asStateFlow()

    private val _favoritesSet = MutableStateFlow<Set<String>>(emptySet())
    val favoritesSet: StateFlow<Set<String>> = _favoritesSet.asStateFlow()

    private val _favoriteActorsSet = MutableStateFlow<Set<Int>>(emptySet())
    val favoriteActorsSet: StateFlow<Set<Int>> = _favoriteActorsSet.asStateFlow()

    private val mediaNavigationStack = mutableListOf<JSONObject>()

    private val _personDetails = MutableStateFlow<JSONObject?>(null)
    val personDetails: StateFlow<JSONObject?> = _personDetails.asStateFlow()

    private val _personCredits = MutableStateFlow<List<JSONObject>>(emptyList())
    val personCredits: StateFlow<List<JSONObject>> = _personCredits.asStateFlow()

    private val _personDisplayedCredits = MutableStateFlow<List<JSONObject>>(emptyList())
    val personDisplayedCredits: StateFlow<List<JSONObject>> = _personDisplayedCredits.asStateFlow()

    val personFilterMode = MutableStateFlow("All")
    private var fullPersonCreditsList = listOf<JSONObject>()

    private var recsPage = 1
    private var hasMoreRecs = true
    private var isLoadingMoreRecs = false

    private val _activeCategoryIndex = MutableStateFlow(0)
    val activeCategoryIndex: StateFlow<Int> = _activeCategoryIndex.asStateFlow()

    val forYouPillTitle = MutableStateFlow("favorites")
    val moviesPillTitle = MutableStateFlow("trending_today")
    val tvShowsPillTitle = MutableStateFlow("popular_tv")
    val discoverPillTitle = MutableStateFlow("discover_platform_8")

    fun selectForYouPill(title: String) {
        forYouPillTitle.value = when (title.lowercase()) {
            "favourites", "favorites" -> "favorites"
            "recently watched", "recents" -> "recents"
            else -> title
        }
    }

    private val _isScraping = MutableStateFlow(false)
    val isScraping: StateFlow<Boolean> = _isScraping.asStateFlow()

    private val _isFetchingTrailer = MutableStateFlow(false)
    val isFetchingTrailer: StateFlow<Boolean> = _isFetchingTrailer.asStateFlow()

    private val _isResolving = MutableStateFlow(false)
    val isResolving: StateFlow<Boolean> = _isResolving.asStateFlow()

    private val _scrapeStatusMsg = MutableStateFlow("")
    val scrapeStatusMsg: StateFlow<String> = _scrapeStatusMsg.asStateFlow()

    private val _scrapeProgress = MutableStateFlow(0)
    val scrapeProgress: StateFlow<Int> = _scrapeProgress.asStateFlow()

    private val _scrapeTotal = MutableStateFlow(0)
    val scrapeTotal: StateFlow<Int> = _scrapeTotal.asStateFlow()

    private val _scrapedSources = MutableStateFlow<JSONArray?>(null)
    val scrapedSources: StateFlow<JSONArray?> = _scrapedSources.asStateFlow()

    private val _torrentioSources = MutableStateFlow<JSONArray?>(null)
    val torrentioSources: StateFlow<JSONArray?> = _torrentioSources.asStateFlow()

    private val _isScrapingTorrents = MutableStateFlow(false)
    val isScrapingTorrents: StateFlow<Boolean> = _isScrapingTorrents.asStateFlow()

    private val _events = MutableStateFlow<StreamsEvent?>(null)
    val events: StateFlow<StreamsEvent?> = _events.asStateFlow()

    private val _isDownloadingSubs = MutableStateFlow(false)
    val isDownloadingSubs: StateFlow<Boolean> = _isDownloadingSubs.asStateFlow()

    private val _showSubProgressBar = MutableStateFlow(false)
    val showSubProgressBar: StateFlow<Boolean> = _showSubProgressBar.asStateFlow()

    private val _subProgress = MutableStateFlow(0f)
    val subProgress: StateFlow<Float> = _subProgress.asStateFlow()

    private val _isTryingAll = MutableStateFlow(false)
    val isTryingAll: StateFlow<Boolean> = _isTryingAll.asStateFlow()

    private var currentTryingIndex = -1
    private var tryAllJob: Job? = null
    
    private val _subStatusMsg = MutableStateFlow("")
    val subStatusMsg: StateFlow<String> = _subStatusMsg.asStateFlow()

    private val _isMovieWatched = MutableStateFlow(false)
    val isMovieWatched: StateFlow<Boolean> = _isMovieWatched.asStateFlow()

    private val _liveSuggestions = MutableStateFlow<List<JSONObject>>(emptyList())
    val liveSuggestions: StateFlow<List<JSONObject>> = _liveSuggestions.asStateFlow()

    private val _predictedQuery = MutableStateFlow("")
    val predictedQuery: StateFlow<String> = _predictedQuery.asStateFlow()

    private var liveSearchJob: Job? = null

    var lastScrapedSeason: Int? = null
    var lastScrapedEpisode: Int? = null
    var lastSelectedSource: JSONObject? = null

    private val _showProgressCounts = MutableStateFlow<Map<String, Pair<Int, Int>>>(emptyMap())
    val showProgressCounts: StateFlow<Map<String, Pair<Int, Int>>> = _showProgressCounts.asStateFlow()

    val interceptedSubtitleUrls = ConcurrentHashMap<String, Map<String, String>>()
    
    private val searchCache = LruCache<String, JSONArray>(15)

    private var scrapeJob: Job? = null
    private var scrapePollingJob: Job? = null
    private var libraryLoadJob: Job? = null
    private var isInteractingWithSources = false
    private var lastSubtitledItemKey: String? = null
    private var isAutoplayStarting = false
    
    var pendingPlayVideoSourceData: String? = null

    var isPlayingFromSavedLink = false

    private val _isBufferingTorrent = MutableStateFlow(false)
    val isBufferingTorrent: StateFlow<Boolean> = _isBufferingTorrent.asStateFlow()

    private val _torrentBufferProgress = MutableStateFlow(0f)
    val torrentBufferProgress: StateFlow<Float> = _torrentBufferProgress.asStateFlow()

    private val _torrentBufferStatus = MutableStateFlow("Initializing...")
    val torrentBufferStatus: StateFlow<String> = _torrentBufferStatus.asStateFlow()

    private val _torrentBufferSeeders = MutableStateFlow(0)
    val torrentBufferSeeders: StateFlow<Int> = _torrentBufferSeeders.asStateFlow()

    private var torrentCancelLatch: CountDownLatch? = null

    private val _activeNotifications = MutableStateFlow<List<JSONObject>>(emptyList())
    val activeNotifications: StateFlow<List<JSONObject>> = _activeNotifications.asStateFlow()

    val selectedScrapeTabIndex = MutableStateFlow(0)
    var lastSelectedSourceIndex: Int = -1
    var lastSelectedSourceKey: String? = null

    init {
        loadSearchHistory()
        refreshFavoritesSet()
        refreshFavoriteActorsSet()
        purgeOldSubtitles()
        loadProgressCounts()
        loadSavedNotifications()
        startBackgroundTraktCheck()
    }

    private fun loadSavedNotifications() {
        val json = prefs.getString("saved_new_episodes", "[]") ?: "[]"
        try {
            val arr = JSONArray(json)
            val list = mutableListOf<JSONObject>()
            for (i in 0 until arr.length()) {
                list.add(arr.getJSONObject(i))
            }
            _activeNotifications.value = list
        } catch (_: Exception) {}
    }

    private fun saveNotifications(list: List<JSONObject>) {
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        prefs.edit().putString("saved_new_episodes", arr.toString()).apply()
    }

    fun getTraktNextEpisode(item: JSONObject): Pair<Int, Int>? {
        if (!isTraktAuthorized()) return null
        return try {
            val py = Python.getInstance()
            val traktModule = py.getModule("modules.trakt")
            val watchedShows = traktModule.callAttr("cachesyncTVShows")?.asList() ?: return null
            val showId = item.optString("id")
            val imdb = item.optString("imdb")
            var matchedShow: com.chaquo.python.PyObject? = null
            for (show in watchedShows) {
                val showAsList = show.asList()
                val cachedId = showAsList[0].toString()
                if (cachedId == showId || (imdb.isNotEmpty() && cachedId == imdb)) {
                    matchedShow = show
                    break
                }
            }
            
            if (matchedShow != null) {
                val watchedList = matchedShow.asList()[2].asList()
                if (watchedList.isEmpty()) {
                    return Pair(1, 1)
                }
                
                var maxSeason = 1
                var maxEpisode = 1
                
                for (epObj in watchedList) {
                    val epPair = epObj.asList()
                    val s = epPair[0].toInt()
                    val e = epPair[1].toInt()
                    
                    if (s > 0) {
                        if (s > maxSeason) {
                            maxSeason = s
                            maxEpisode = e
                        } else if (s == maxSeason && e > maxEpisode) {
                            maxEpisode = e
                        }
                    }
                }
                Pair(maxSeason, maxEpisode + 1)
            } else null
        } catch (e: Exception) {
            Log.e("StreamsDebug", "Error getting Trakt next episode: ${e.message}")
            null
        }
    }

    private fun adjustSeasonAndEpisodeBoundaries(
        seasons: JSONArray,
        targetSeason: Int,
        targetEpisode: Int
    ): Pair<Int, Int> {
        var adjustedSeason = targetSeason
        var adjustedEpisode = targetEpisode
        var seasonObj: JSONObject? = null
        for (i in 0 until seasons.length()) {
            val s = seasons.getJSONObject(i)
            if (s.optInt("season_number") == targetSeason) {
                seasonObj = s
                break
            }
        }

        if (seasonObj != null) {
            val epCount = seasonObj.optInt("episode_count", 0)
            if (epCount > 0 && targetEpisode > epCount) {
                var nextSeasonObj: JSONObject? = null
                for (i in 0 until seasons.length()) {
                    val s = seasons.getJSONObject(i)
                    if (s.optInt("season_number") == targetSeason + 1) {
                        nextSeasonObj = s
                        break
                    }
                }

                val nextEpCount = nextSeasonObj?.optInt("episode_count", 0) ?: 0
                val nextAirDate = nextSeasonObj?.optString("air_date", "") ?: ""
                val hasNextAired = nextAirDate.isNotEmpty() && !com.poobi.tvbrowser.shared.isFutureDate(nextAirDate)

                if (nextSeasonObj != null && nextEpCount > 0 && (hasNextAired || nextAirDate.isEmpty())) {
                    adjustedSeason = targetSeason + 1
                    adjustedEpisode = 1
                } else {
                    adjustedSeason = targetSeason
                    adjustedEpisode = epCount
                }
            }
        }
        return Pair(adjustedSeason, adjustedEpisode)
    }

    private fun performTorrentPreBuffering(infoHash: String, fileIdx: Int, sourceDataJson: String) {
        _isBufferingTorrent.value = true
        _torrentBufferProgress.value = 0f
        _torrentBufferStatus.value = "Starting Torrent Engine..."
        _torrentBufferSeeders.value = 0
        
        val latch = CountDownLatch(1)
        torrentCancelLatch = latch

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val server = TorrentStreamServer.getInstance(context)
                val prebufferLimit = prefs.getInt("torrent_prebuffer_pieces", 1)

                val trackers = mutableListOf<String>()
                try {
                    val dataObj = JSONObject(sourceDataJson)
                    val sourcesArr = dataObj.optJSONArray("sources")
                    if (sourcesArr != null) {
                        for (i in 0 until sourcesArr.length()) {
                            val s = sourcesArr.getString(i)
                            if (s.isNotBlank()) trackers.add(s)
                        }
                    }
                    val trackersArr = dataObj.optJSONArray("trackers")
                    if (trackersArr != null) {
                        for (i in 0 until trackersArr.length()) {
                            val t = trackersArr.getString(i)
                            if (t.isNotBlank()) trackers.add(t)
                        }
                    }
                } catch (_: Exception) {}

                server.prepareTorrent(
                    infoHash = infoHash,
                    fileIdx = fileIdx,
                    prebufferPiecesLimit = prebufferLimit,
                    trackers = trackers,
                    onStatusUpdate = { status, progress, seeders ->
                        _torrentBufferStatus.value = status
                        _torrentBufferProgress.value = progress
                        _torrentBufferSeeders.value = seeders
                    },
                    onReady = {
                        viewModelScope.launch(Dispatchers.Main) {
                            _torrentBufferStatus.value = "Starting video player..."
                            _torrentBufferProgress.value = 1f
                            resolveAndPlayInternal(sourceDataJson)
                        }
                    },
                    onError = { error ->
                        viewModelScope.launch(Dispatchers.Main) {
                            _isBufferingTorrent.value = false
                            _events.value = StreamsEvent.ShowToast("Pre-Buffering Failed: $error")
                            resumeScrape()
                        }
                    },
                    cancellationToken = latch
                )
            } catch (e: Exception) {
                viewModelScope.launch(Dispatchers.Main) {
                    _isBufferingTorrent.value = false
                    _events.value = StreamsEvent.ShowToast("Pre-Buffering Failed: ${e.message}")
                    resumeScrape()
                }
            }
        }
    }

    fun dismissSubtitleWaitDialog() {
        _showSubtitleWaitDialog.value = null
    }

    fun dismissNotificationAt(index: Int) {
        val currentList = _activeNotifications.value.toMutableList()
        if (index in currentList.indices) {
            currentList.removeAt(index)
            _activeNotifications.value = currentList
            saveNotifications(currentList)
            TvHomeSyncManager.syncNewEpisodesChannel(context, currentList)
        }
    }

    fun dismissAllNotifications() {
        _activeNotifications.value = emptyList()
        saveNotifications(emptyList())
        TvHomeSyncManager.syncNewEpisodesChannel(context, emptyList())
    }

    fun playNotificationEpisode(notification: JSONObject, index: Int) {
        dismissNotificationAt(index)
        
        val itemObj = notification.optJSONObject("item")
        val showTitle = notification.optString("show_title", "Unknown")
        val season = notification.optInt("season", 1)
        val episodeNum = notification.optInt("number", 1)

        val constructedItem = if (itemObj != null) {
            itemObj
        } else {
            val showTmdbId = notification.optInt("show_id", 0)
            JSONObject().apply {
                put("id", showTmdbId)
                put("imdb", "")
                put("media_type", "tv")
                put("name", showTitle)
            }
        }

        selectMediaItem(constructedItem, initialSeason = season)
        performScrape(constructedItem, season, episodeNum)
    }

    private suspend fun enrichNotificationWithTmdb(notification: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        try {
            val showTmdbId = notification.optInt("show_id", 0)
            val season = notification.optInt("season", 1)
            val episodeNum = notification.optInt("number", 1)

            if (showTmdbId > 0) {
                val py = getPythonInstance()
                val main = py.getModule("main")
                val detailsJson = main.callAttr("get_tv_episodes", showTmdbId, season).toString()
                val episodesArray = JSONArray(detailsJson)
                for (i in 0 until episodesArray.length()) {
                    val ep = episodesArray.getJSONObject(i)
                    if (ep.optInt("episode_number") == episodeNum) {
                        val posterPath = ep.optString("poster_path").ifEmpty { ep.optString("still_path", "") }
                        if (posterPath.isNotEmpty() && posterPath != "null") {
                            notification.put("poster_path", posterPath)
                        }
                        val overview = ep.optString("overview", "")
                        if (overview.isNotEmpty() && overview != "null") {
                            notification.put("episode_overview", overview)
                        }
                        break
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("StreamsViewModel", "Notification enrichment failed: ${e.message}")
        }
        notification
    }

    fun loadProgressCounts() {
        viewModelScope.launch(Dispatchers.IO) {
            val favsJson = prefs.getString("streams_favorites", "[]") ?: "[]"
            if (favsJson == "[]") {
                withContext(Dispatchers.Main) {
                    _showProgressCounts.value = emptyMap()
                }
                return@launch
            }
            try {
                val py = getPythonInstance()
                val checker = py.getModule("trakt.episode_check")
                val progressJsonStr = checker.callAttr("get_all_shows_progress", favsJson).toString()
                val progressObj = JSONObject(progressJsonStr)
                
                val map = mutableMapOf<String, Pair<Int, Int>>()
                progressObj.keys().forEach { key ->
                    val showData = progressObj.getJSONObject(key)
                    val unwatchedTotal = showData.optInt("unwatched_total", 0)
                    val newerUnwatched = showData.optInt("newer_unwatched", 0)
                    map[key] = Pair(unwatchedTotal, newerUnwatched)
                }
                
                withContext(Dispatchers.Main) {
                    _showProgressCounts.value = map
                }
            } catch (e: Exception) {
                Log.e("StreamsViewModel", "Failed loading Trakt progress metrics", e)
            }
        }
    }

    fun forcePlayTorrentNow() {
        try {
            val server = TorrentStreamServer.getInstance(context)
            server.forcePlayTriggered = true
        } catch (e: Exception) {}
    }

    fun cancelTorrentBuffering() {
        torrentCancelLatch?.countDown()
        _isBufferingTorrent.value = false
        resumeScrape()
    }

    fun getFavActorCount(wrapper: JSONObject): Int {
        val itm = if (wrapper.has("item")) wrapper.optJSONObject("item") ?: wrapper else wrapper
        val id = itm.optString("id")
        val mType = itm.optString("media_type")
        val actorList = (if (mType.isNotEmpty()) globalMediaToActorMap["${mType}_$id"] else null) ?: globalMediaToActorMap[id]
        return actorList?.size ?: (itm.optJSONArray("spotlight_actors")?.length() ?: 0)
    }

    fun setActiveCategoryIndex(index: Int) {
        if (index != 0) clearPendingSearchQuery()
        _activeCategoryIndex.value = index
    }

    fun consumeEvent() { _events.value = null }

    fun clearScrapedSources() {
        stopScrape()
        _scrapedSources.value = null
        _torrentioSources.value = null
        _isScrapingTorrents.value = false
        selectedScrapeTabIndex.value = 0
        lastSelectedSourceIndex = -1
        lastSelectedSourceKey = null
    }

    fun clearSelectedMedia() {
        mediaNavigationStack.clear()
        _selectedItem.value = null
        _personDetails.value = null
        _personCredits.value = emptyList()
        _personDisplayedCredits.value = emptyList()
    }
    fun clearSearchResults() {
        _searchResults.value = null
        currentSearchQuery = ""
        searchPage = 1
        hasMoreSearchPages = true
        isSearchLoadingMore = false
    }

    private var searchPage = 1
    private var hasMoreSearchPages = true
    private var isSearchLoadingMore = false
    private var currentSearchQuery = ""

    fun loadMoreSearchResults() {
        if (isSearchLoadingMore || !hasMoreSearchPages || currentSearchQuery.isBlank()) return
        isSearchLoadingMore = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val nextPage = searchPage + 1
                val py = getPythonInstance()
                val scraper = py.getModule("main")
                val resultsJson = scraper.callAttr("search", currentSearchQuery, nextPage).toString()
                val newResults = JSONArray(resultsJson)
                if (newResults.length() > 0) {
                    searchPage = nextPage
                    val parsedNew = mutableListOf<JSONObject>()
                    for (i in 0 until newResults.length()) {
                        val it = newResults.getJSONObject(i)
                        if (it.optString("media_type") in listOf("movie", "tv", "person")) {
                            parsedNew.add(it)
                        }
                    }

                    if (_searchResults.value == null && _liveSuggestions.value.isNotEmpty()) {
                        val currentList = _liveSuggestions.value.toMutableList()
                        val seenKeys = currentList.map { "${it.optString("media_type")}_${it.optString("id")}" }.toMutableSet()
                        for (itm in parsedNew) {
                            val key = "${itm.optString("media_type")}_${itm.optString("id")}"
                            if (!seenKeys.contains(key)) {
                                seenKeys.add(key)
                                currentList.add(itm)
                            }
                        }
                        withContext(Dispatchers.Main) {
                            _liveSuggestions.value = currentList
                        }
                    }

                    if (_searchResults.value != null) {
                        val current = _searchResults.value ?: JSONArray()
                        val combined = JSONArray(current.toString())
                        val seenKeys = mutableSetOf<String>()
                        for (i in 0 until combined.length()) {
                            val itm = combined.optJSONObject(i) ?: continue
                            seenKeys.add("${itm.optString("media_type")}_${itm.optString("id")}")
                        }
                        for (itm in parsedNew) {
                            val key = "${itm.optString("media_type")}_${itm.optString("id")}"
                            if (!seenKeys.contains(key)) {
                                seenKeys.add(key)
                                combined.put(itm)
                            }
                        }
                        withContext(Dispatchers.Main) {
                            _searchResults.value = combined
                        }
                    }
                } else {
                    hasMoreSearchPages = false
                }
            } catch (_: Exception) {
                hasMoreSearchPages = false
            } finally {
                isSearchLoadingMore = false
            }
        }
    }
    private suspend fun getPythonInstance(): Python = withContext(Dispatchers.IO) {
        if (!Python.isStarted()) {
            try {
                Python.start(com.chaquo.python.android.AndroidPlatform(context))
            } catch (_: Exception) {}
        }
        while (!Python.isStarted()) {
            delay(50)
        }
        Python.getInstance()
    }

    private fun isTraktAuthorized(): Boolean {
        return try {
            if (!Python.isStarted()) return false
            val py = Python.getInstance()
            val control = py.getModule("modules.control")
            control.callAttr("setting", "trakt.authed").toString() == "yes"
        } catch (e: Exception) {
            false
        }
    }

    fun loadSearchHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            val historyJson = prefs.getString("streams_search_history", "[]") ?: "[]"
            val array = JSONArray(historyJson)
            val list = mutableListOf<String>()
            for (i in 0 until array.length()) list.add(array.getString(i))
            
            withContext(Dispatchers.Main) {
                _searchHistory.value = list
            }
        }
    }

    fun addToSearchHistory(query: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val list = _searchHistory.value.toMutableList()
            list.remove(query)
            list.add(0, query)
            if (list.size > 20) list.removeAt(list.size - 1)
            
            prefs.edit().putString("streams_search_history", JSONArray(list).toString()).apply()
            withContext(Dispatchers.Main) {
                _searchHistory.value = list
            }
        }
    }

    private var _pendingSearchQuery: String? = null

    fun setPendingSearchQuery(query: String?) {
        if (!query.isNullOrBlank()) {
            _pendingSearchQuery = query.trim()
        }
    }

    fun clearPendingSearchQuery() {
        _pendingSearchQuery = null
    }

    fun commitPendingSearchQuery() {
        val q = _pendingSearchQuery
        if (!q.isNullOrBlank()) {
            addToSearchHistory(q)
            _pendingSearchQuery = null
        }
    }

    fun clearSearchHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            prefs.edit().putString("streams_search_history", "[]").apply()
            withContext(Dispatchers.Main) {
                _searchHistory.value = emptyList()
            }
        }
    }

    fun updateLiveSearch(query: String) {
        liveSearchJob?.cancel()
        val clean = query.trim()
        if (clean.length < 2) {
            _liveSuggestions.value = emptyList()
            _predictedQuery.value = ""
            return
        }

        liveSearchJob = viewModelScope.launch(Dispatchers.IO) {
            delay(250)
            if (!isActive) return@launch
            try {
                currentSearchQuery = clean
                searchPage = 1
                hasMoreSearchPages = true
                isSearchLoadingMore = false

                val cached = searchCache.get(clean)
                val resultsArray = if (cached != null) {
                    cached
                } else {
                    val py = Python.getInstance()
                    val scraper = py.getModule("main")
                    val resultsJson = scraper.callAttr("search", clean, 1).toString()
                    val arr = JSONArray(resultsJson)
                    if (arr.length() > 0) searchCache.put(clean, arr)
                    arr
                }

                if (!isActive) return@launch

                val itemsList = mutableListOf<JSONObject>()
                for (i in 0 until resultsArray.length()) {
                    val it = resultsArray.getJSONObject(i)
                    if (it.optString("media_type") in listOf("movie", "tv", "person")) {
                        itemsList.add(it)
                    }
                }

                var bestMatch = ""
                for (it in itemsList) {
                    val orig = it.optString("orig_title").ifEmpty { it.optString("title") }.replace(Regex("\\s\\(\\d{4}\\)$"), "").trim()
                    if (orig.startsWith(clean, ignoreCase = true) && !orig.equals(clean, ignoreCase = true)) {
                        bestMatch = orig
                        break
                    }
                }

                if (bestMatch.isEmpty()) {
                    for (it in itemsList) {
                        val orig = it.optString("orig_title").ifEmpty { it.optString("title") }.replace(Regex("\\s\\(\\d{4}\\)$"), "").trim()
                        if (orig.contains(clean, ignoreCase = true) && !orig.equals(clean, ignoreCase = true)) {
                            val wordMatch = orig.split(" ").find { it.startsWith(clean, ignoreCase = true) }
                            if (wordMatch != null) {
                                bestMatch = orig
                                break
                            }
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    _liveSuggestions.value = itemsList
                    _predictedQuery.value = bestMatch
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
            }
        }
    }

    fun clearLiveSearch() {
        liveSearchJob?.cancel()
        _liveSuggestions.value = emptyList()
        _predictedQuery.value = ""
    }

    fun performSearch(query: String) {
        if (query.isBlank()) return
        clearPendingSearchQuery()
        addToSearchHistory(query)
        clearLiveSearch()

        currentSearchQuery = query
        searchPage = 1
        hasMoreSearchPages = true
        isSearchLoadingMore = false

        val cached = searchCache.get(query)
        if (cached != null && cached.length() > 0) {
            _searchResults.value = cached
            _isScraping.value = false
            return
        }

        _scrapeStatusMsg.value = "Searching..."
        _isScraping.value = true
        _searchResults.value = null

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val py = Python.getInstance()
                val scraper = py.getModule("main")
                val resultsJson = scraper.callAttr("search", query, 1).toString()
                val results = JSONArray(resultsJson)
                withContext(Dispatchers.Main) {
                    _isScraping.value = false
                    if (results.length() > 0) {
                        searchCache.put(query, results)
                        _searchResults.value = results
                    } else {
                        _searchResults.value = null
                        hasMoreSearchPages = false
                        _events.value = StreamsEvent.ShowToast("No results found for \"$query\"")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _events.value = StreamsEvent.ShowToast("Search error: ${e.message}")
                    _isScraping.value = false
                    _searchResults.value = null
                    hasMoreSearchPages = false
                }
            }
        }
    }

    var lastViewedCardIndex: Int = 0
    var lastViewedMediaId: String? = null
    var isReturningFromDetails: Boolean = false

    val isLandscapeCard: Boolean
        get() = prefs.getInt("card_thumbnail_aspect_ratio", 0) == 1

    data class SpotlightActorInfo(
        val name: String,
        val profilePath: String,
        val id: Int
    )

    private val globalMediaToActorMap = java.util.concurrent.ConcurrentHashMap<String, MutableList<SpotlightActorInfo>>()
    @Volatile private var isActorMappingBuilt = false
    @Volatile private var lastActorMappingSignature = 0

    private var currentFeedLoader: (suspend (page: Int) -> JSONArray?)? = null
    private var currentFeedPage = 1
    private var isFeedLoadingMore = false
    private var hasMoreFeedPages = true
    private var discoverCastPage = 1
    private var discoverCastTotalPages = 1
    private var discoverRegularPage = 1
    private var discoverSeenIds = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    private fun cancelLibraryJobs(clearCurrentItems: Boolean = true) {
        libraryLoadJob?.cancel()
        if (clearCurrentItems) {
            _libraryItems.value = null
        }
        currentFeedLoader = null
        currentFeedPage = 1
        hasMoreFeedPages = true
        isFeedLoadingMore = false
    }

    private fun populateActorCreditsToMap(act: com.poobi.tvbrowser.shared.FavoriteActor, credits: JSONArray, overrideProfilePath: String? = null) {
        val pPath = overrideProfilePath?.takeIf { it.isNotBlank() } ?: act.profilePath
        for (j in 0 until credits.length()) {
            val cObj = credits.optJSONObject(j) ?: continue
            val cId = cObj.optString("id")
            val mType = cObj.optString("media_type")
            if (cId.isNotEmpty()) {
                val keys = mutableListOf(cId)
                if (mType.isNotEmpty()) keys.add("${mType}_$cId")
                for (k in keys) {
                    val list = globalMediaToActorMap.getOrPut(k) { java.util.Collections.synchronizedList(mutableListOf()) }
                    synchronized(list) {
                        if (list.none { it.id == act.id }) {
                            list.add(SpotlightActorInfo(act.name, pPath, act.id))
                        }
                    }
                }
            }
        }
    }

    private suspend fun ensureActorMapping(forceRefresh: Boolean = false): List<com.poobi.tvbrowser.shared.FavoriteActor> = withContext(Dispatchers.IO) {
        val allActors = com.poobi.tvbrowser.shared.FavoriteActorsManager.getActors(context)
        val selectedActors = allActors.filter { it.isEnabled }
        val signature = selectedActors.map { it.id }.hashCode()

        if (!forceRefresh && isActorMappingBuilt && signature == lastActorMappingSignature && globalMediaToActorMap.isNotEmpty()) {
            return@withContext selectedActors
        }

        if (selectedActors.isEmpty()) {
            globalMediaToActorMap.clear()
            isActorMappingBuilt = true
            lastActorMappingSignature = signature
            return@withContext emptyList()
        }

        globalMediaToActorMap.clear()

        val uncachedActors = mutableListOf<com.poobi.tvbrowser.shared.FavoriteActor>()
        for (act in selectedActors) {
            val cachedCredits = com.poobi.tvbrowser.shared.AppCacheManager.getActorCredits(context, act.id)
            if (cachedCredits != null) {
                populateActorCreditsToMap(act, cachedCredits)
            } else {
                uncachedActors.add(act)
            }
        }

        isActorMappingBuilt = true
        lastActorMappingSignature = signature

        if (uncachedActors.isNotEmpty()) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val py = getPythonInstance()
                    val tmdb = py.getModule("tmdb.tmdb_utils")
                    coroutineScope {
                        uncachedActors.chunked(4).forEach { chunk ->
                            chunk.map { act ->
                                async {
                                    try {
                                        val detailsJson = tmdb.callAttr("get_person_details", act.id).toString()
                                        val detailsObj = JSONObject(detailsJson)
                                        val combinedCredits = detailsObj.optJSONObject("combined_credits")
                                        val castArr = combinedCredits?.optJSONArray("cast") ?: JSONArray()
                                        com.poobi.tvbrowser.shared.AppCacheManager.saveActorCredits(context, act.id, castArr)
                                        populateActorCreditsToMap(act, castArr, detailsObj.optString("profile_path", ""))
                                    } catch (e: Exception) {
                                        Log.e("StreamsViewModel", "Failed fetching credits for ${act.name}: ${e.message}")
                                    }
                                }
                            }.awaitAll()
                        }
                    }
                    withContext(Dispatchers.Main) {
                        refreshCurrentFeedWithActors()
                    }
                } catch (_: Exception) {}
            }
        }

        selectedActors
    }

    private fun processFeedItems(rawArray: JSONArray, prioritizeFavCast: Boolean): JSONArray {
        val starringItems = mutableListOf<Pair<JSONObject, Int>>()
        val regularItems = mutableListOf<JSONObject>()
        val seenIds = mutableSetOf<String>()

        for (i in 0 until rawArray.length()) {
            val itm = rawArray.getJSONObject(i)
            val id = itm.optString("id")
            if (id.isEmpty() || seenIds.contains(id)) continue
            seenIds.add(id)

            val mType = itm.optString("media_type")
            var actorList = (if (mType.isNotEmpty()) globalMediaToActorMap["${mType}_$id"] else null) ?: globalMediaToActorMap[id]

            if (actorList.isNullOrEmpty() && itm.has("spotlight_actors")) {
                val arr = itm.optJSONArray("spotlight_actors")
                if (arr != null && arr.length() > 0) {
                    val favSet = _favoriteActorsSet.value
                    val list = mutableListOf<SpotlightActorInfo>()
                    for (k in 0 until arr.length()) {
                        val a = arr.getJSONObject(k)
                        val aId = a.optInt("id")
                        if (favSet.isEmpty() || favSet.contains(aId)) {
                            list.add(SpotlightActorInfo(a.optString("name"), a.optString("profile_path"), aId))
                        }
                    }
                    if (list.isNotEmpty()) {
                        actorList = list
                    }
                }
            }

            if (!actorList.isNullOrEmpty()) {
                val actorsArr = JSONArray()
                synchronized(actorList) {
                    actorList.forEach { actor ->
                        actorsArr.put(JSONObject().apply {
                            put("id", actor.id)
                            put("name", actor.name)
                            put("profile_path", actor.profilePath)
                        })
                    }
                }
                itm.put("spotlight_actors", actorsArr)
                itm.put("spotlight_actor_name", actorList[0].name)
                if (actorList[0].profilePath.isNotEmpty()) {
                    itm.put("spotlight_actor_profile", actorList[0].profilePath)
                }

                if (prioritizeFavCast) {
                    starringItems.add(Pair(itm, actorList.size))
                } else {
                    regularItems.add(itm)
                }
            } else {
                itm.remove("spotlight_actors")
                itm.remove("spotlight_actor_name")
                itm.remove("spotlight_actor_profile")
                regularItems.add(itm)
            }
        }

        if (prioritizeFavCast) {
            starringItems.sortByDescending { it.second }
        }

        val result = JSONArray()
        starringItems.forEach { result.put(it.first) }
        regularItems.forEach { result.put(it) }
        return result
    }

    fun loadLibraryCategory(title: String, method: String, arg: String? = null) {
        val prioritizeFavCast = prefs.getBoolean("genres_prioritize_fav_cast", true)
        val cacheKey = "${method}_${arg ?: ""}"
        val cached = com.poobi.tvbrowser.shared.AppCacheManager.getLibraryCategory(context, cacheKey)
        if (cached != null) {
            cancelLibraryJobs(clearCurrentItems = false)
            val processed = if (prioritizeFavCast && globalMediaToActorMap.isNotEmpty()) {
                processFeedItems(cached, prioritizeFavCast = true)
            } else {
                cached
            }
            _libraryItems.value = processed
            setupLibraryFeedPagination(method, arg)
            return
        }

        cancelLibraryJobs(clearCurrentItems = true)
        libraryLoadJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                if (prioritizeFavCast) {
                    ensureActorMapping()
                }

                val py = getPythonInstance()
                val tmdb = py.getModule("tmdb.tmdb_utils")
                val resultJson = if (arg != null) {
                    tmdb.callAttr(method, arg, 1).toString()
                } else {
                    tmdb.callAttr(method, 1).toString()
                }
                val results = JSONObject(resultJson).optJSONArray("results")
                if (results != null) {
                    val processed = processFeedItems(results, prioritizeFavCast)
                    com.poobi.tvbrowser.shared.AppCacheManager.saveLibraryCategory(context, cacheKey, processed)
                    withContext(Dispatchers.Main) { 
                        _libraryItems.value = processed 
                        setupLibraryFeedPagination(method, arg)
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
            }
        }
    }

    private fun setupLibraryFeedPagination(method: String, arg: String?) {
        currentFeedPage = 1
        hasMoreFeedPages = true
        currentFeedLoader = { page ->
            withContext(Dispatchers.IO) {
                try {
                    val prioritizeFavCast = prefs.getBoolean("genres_prioritize_fav_cast", true)
                    val py = com.chaquo.python.Python.getInstance()
                    val tmdb = py.getModule("tmdb.tmdb_utils")
                    val resultJson = if (arg != null) {
                        tmdb.callAttr(method, arg, page).toString()
                    } else {
                        tmdb.callAttr(method, page).toString()
                    }
                    val results = JSONObject(resultJson).optJSONArray("results")
                    if (results != null) processFeedItems(results, prioritizeFavCast) else null
                } catch (_: Exception) {
                    null
                }
            }
        }
    }

    fun loadDiscoverCategory(cacheKey: String, isMovie: Boolean, params: JSONObject) {
        val prioritizeFavCast = prefs.getBoolean("genres_prioritize_fav_cast", true)
        val fullCacheKey = if (prioritizeFavCast) "${cacheKey}_pfc" else cacheKey
        val cached = com.poobi.tvbrowser.shared.AppCacheManager.getLibraryCategory(context, fullCacheKey)
        if (cached != null) {
            cancelLibraryJobs(clearCurrentItems = false)
            discoverSeenIds.clear()
            for (i in 0 until cached.length()) {
                val cid = cached.getJSONObject(i).optString("id")
                if (cid.isNotEmpty()) discoverSeenIds.add(cid)
            }
            val processed = if (prioritizeFavCast && globalMediaToActorMap.isNotEmpty()) {
                processFeedItems(cached, prioritizeFavCast = true)
            } else {
                cached
            }
            _libraryItems.value = processed
            setupDiscoverFeedPagination(isMovie, params, prioritizeFavCast)
            return
        }

        cancelLibraryJobs(clearCurrentItems = true)
        libraryLoadJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val selectedActors = if (prioritizeFavCast) ensureActorMapping() else emptyList()
                val py = com.chaquo.python.Python.getInstance()
                val tmdb = py.getModule("tmdb.tmdb_utils")
                val method = if (isMovie) "discover_movies" else "discover_tv"

                discoverSeenIds.clear()
                discoverCastPage = 1
                discoverCastTotalPages = 0
                discoverRegularPage = 1

                val finalResults = JSONArray()

                if (prioritizeFavCast && selectedActors.isNotEmpty()) {
                    try {
                        val selectedActorIds = selectedActors.map { it.id }
                        val castParams = JSONObject(params.toString()).apply {
                            put("with_cast", selectedActorIds.take(50).joinToString("|"))
                        }
                        val castResStr = tmdb.callAttr(method, castParams.toString(), 1).toString()
                        val castObj = JSONObject(castResStr)
                        discoverCastTotalPages = castObj.optInt("total_pages", 1)
                        val castResults = castObj.optJSONArray("results")

                        if (castResults != null && castResults.length() > 0) {
                            val processedCast = processFeedItems(castResults, prioritizeFavCast = true)
                            for (i in 0 until processedCast.length()) {
                                val itm = processedCast.getJSONObject(i)
                                val id = itm.optString("id")
                                if (id.isNotEmpty() && !discoverSeenIds.contains(id)) {
                                    discoverSeenIds.add(id)
                                    finalResults.put(itm)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("StreamsViewModel", "Failed fetching cast discover page 1: ${e.message}")
                    }
                }

                if (finalResults.length() < 15) {
                    val resultJson = tmdb.callAttr(method, params.toString(), 1).toString()
                    val regObj = JSONObject(resultJson)
                    val results = regObj.optJSONArray("results")
                    if (results != null) {
                        val processedReg = processFeedItems(results, prioritizeFavCast = false)
                        for (i in 0 until processedReg.length()) {
                            val itm = processedReg.getJSONObject(i)
                            val id = itm.optString("id")
                            if (id.isNotEmpty() && !discoverSeenIds.contains(id)) {
                                discoverSeenIds.add(id)
                                finalResults.put(itm)
                            }
                        }
                    }
                    discoverRegularPage = 2
                }

                if (finalResults.length() > 0) {
                    com.poobi.tvbrowser.shared.AppCacheManager.saveLibraryCategory(context, fullCacheKey, finalResults)
                    withContext(Dispatchers.Main) { 
                        _libraryItems.value = finalResults 
                        setupDiscoverFeedPagination(isMovie, params, prioritizeFavCast)
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
            }
        }
    }

    private fun setupDiscoverFeedPagination(isMovie: Boolean, params: JSONObject, prioritizeFavCast: Boolean) {
        currentFeedPage = 1
        hasMoreFeedPages = true
        currentFeedLoader = { _ ->
            withContext(Dispatchers.IO) {
                try {
                    val py = com.chaquo.python.Python.getInstance()
                    val tmdb = py.getModule("tmdb.tmdb_utils")
                    val method = if (isMovie) "discover_movies" else "discover_tv"

                    val selectedActors = if (prioritizeFavCast) ensureActorMapping() else emptyList()
                    val batchResults = JSONArray()

                    if (prioritizeFavCast && selectedActors.isNotEmpty() && discoverCastPage < discoverCastTotalPages) {
                        discoverCastPage++
                        val selectedActorIds = selectedActors.map { it.id }
                        val castParams = JSONObject(params.toString()).apply {
                            put("with_cast", selectedActorIds.take(50).joinToString("|"))
                        }
                        val castResStr = tmdb.callAttr(method, castParams.toString(), discoverCastPage).toString()
                        val castObj = JSONObject(castResStr)
                        discoverCastTotalPages = castObj.optInt("total_pages", discoverCastTotalPages)
                        val castResults = castObj.optJSONArray("results")
                        if (castResults != null && castResults.length() > 0) {
                            val processedCast = processFeedItems(castResults, prioritizeFavCast = true)
                            for (i in 0 until processedCast.length()) {
                                val itm = processedCast.getJSONObject(i)
                                val id = itm.optString("id")
                                if (id.isNotEmpty() && !discoverSeenIds.contains(id)) {
                                    discoverSeenIds.add(id)
                                    batchResults.put(itm)
                                }
                            }
                        }
                    }

                    if (batchResults.length() == 0) {
                        val resultJson = tmdb.callAttr(method, params.toString(), discoverRegularPage).toString()
                        discoverRegularPage++
                        val results = JSONObject(resultJson).optJSONArray("results")
                        if (results != null && results.length() > 0) {
                            val processed = processFeedItems(results, prioritizeFavCast)
                            for (i in 0 until processed.length()) {
                                val itm = processed.getJSONObject(i)
                                val id = itm.optString("id")
                                if (id.isNotEmpty() && !discoverSeenIds.contains(id)) {
                                    discoverSeenIds.add(id)
                                    batchResults.put(itm)
                                }
                            }
                        }
                    }

                    if (batchResults.length() > 0) batchResults else null
                } catch (_: Exception) {
                    null
                }
            }
        }
    }

    fun loadMoreFeedItems() {
        if (isFeedLoadingMore || !hasMoreFeedPages) return
        val loader = currentFeedLoader ?: return
        isFeedLoadingMore = true
        viewModelScope.launch(Dispatchers.IO) {
            val nextPage = currentFeedPage + 1
            val newItems = loader(nextPage)
            if (newItems != null && newItems.length() > 0) {
                currentFeedPage = nextPage
                val current = _libraryItems.value ?: JSONArray()
                val combined = JSONArray(current.toString())
                val seenIds = mutableSetOf<String>()
                for (i in 0 until combined.length()) {
                    val itm = combined.optJSONObject(i)
                    val id = itm?.optString("id") ?: ""
                    if (id.isNotEmpty()) seenIds.add(id)
                }
                for (i in 0 until newItems.length()) {
                    val itm = newItems.getJSONObject(i)
                    val id = itm.optString("id")
                    if (id.isEmpty() || seenIds.add(id)) {
                        combined.put(itm)
                    }
                }
                withContext(Dispatchers.Main) {
                    _libraryItems.value = combined
                }
            } else {
                hasMoreFeedPages = false
            }
            isFeedLoadingMore = false
        }
    }

    fun loadTraktFeed(cacheKey: String, feedType: String) {
        val cached = com.poobi.tvbrowser.shared.AppCacheManager.getLibraryCategory(context, cacheKey)
        if (cached != null) {
            cancelLibraryJobs(clearCurrentItems = false)
            _libraryItems.value = cached
            return
        }

        cancelLibraryJobs(clearCurrentItems = true)
        libraryLoadJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                delay(100)
                if (!isActive) return@launch

                val py = Python.getInstance()
                val main = py.getModule("main")
                val resultJson = main.callAttr("get_trakt_feed", feedType).toString()
                val results = JSONArray(resultJson)
                if (results.length() > 0) {
                    com.poobi.tvbrowser.shared.AppCacheManager.saveLibraryCategory(context, cacheKey, results)
                    withContext(Dispatchers.Main) { 
                        _libraryItems.value = results 
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        _libraryItems.value = JSONArray()
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                withContext(Dispatchers.Main) {
                    _libraryItems.value = JSONArray()
                }
            }
        }
    }

    fun loadUnfinishedShows() {
        cancelLibraryJobs()
        viewModelScope.launch(Dispatchers.IO) {
            val favsJson = prefs.getString("streams_favorites", "[]") ?: "[]"
            val recentsJson = prefs.getString("streams_recently_played", "[]") ?: "[]"
            val favArray = JSONArray(favsJson)
            val recentArray = JSONArray(recentsJson)
            
            val candidates = mutableMapOf<String, JSONObject>()
            for (i in 0 until recentArray.length()) {
                val entry = recentArray.optJSONObject(i) ?: continue
                val item = entry.optJSONObject("item") ?: continue
                val isTv = item.optString("media_type") == "tv" || item.has("name")
                val id = item.optString("id").ifEmpty { item.optString("imdb") }
                if (isTv && id.isNotEmpty()) {
                    candidates[id] = item
                }
            }
            for (i in 0 until favArray.length()) {
                val item = favArray.optJSONObject(i) ?: continue
                val isTv = item.optString("media_type") == "tv" || item.has("name")
                val id = item.optString("id").ifEmpty { item.optString("imdb") }
                if (isTv && id.isNotEmpty() && !candidates.containsKey(id)) {
                    candidates[id] = item
                }
            }

            val counts = _showProgressCounts.value
            val result = JSONArray()
            candidates.forEach { (id, item) ->
                val unwatched = counts[id]?.first ?: 0
                if (unwatched > 0) {
                    result.put(item)
                }
            }

            if (result.length() == 0 && candidates.isNotEmpty()) {
                candidates.values.take(15).forEach { result.put(it) }
            }

            withContext(Dispatchers.Main) {
                _libraryItems.value = result
            }
        }
    }

    fun loadBecauseYouWatched() {
        cancelLibraryJobs()
        viewModelScope.launch(Dispatchers.IO) {
            val recentJson = prefs.getString("streams_recently_played", "[]") ?: "[]"
            val recentArray = JSONArray(recentJson)
            var seedItem: JSONObject? = null
            for (i in 0 until recentArray.length()) {
                val entry = recentArray.optJSONObject(i) ?: continue
                val item = entry.optJSONObject("item") ?: continue
                seedItem = item
                break
            }

            if (seedItem == null) {
                withContext(Dispatchers.Main) {
                    _libraryItems.value = JSONArray()
                }
                return@launch
            }

            val id = seedItem.optString("id")
            val mediaType = seedItem.optString("media_type").ifEmpty { if (seedItem.has("name")) "tv" else "movie" }
            val cacheKey = "rec_${mediaType}_$id"

            val cached = com.poobi.tvbrowser.shared.AppCacheManager.getLibraryCategory(context, cacheKey)
            if (cached != null) {
                withContext(Dispatchers.Main) { 
                    cancelLibraryJobs(clearCurrentItems = false)
                    _libraryItems.value = cached 
                }
                return@launch
            }

            try {
                val py = Python.getInstance()
                val tmdb = py.getModule("tmdb.tmdb_utils")
                val resJson = tmdb.callAttr("get_recommendations", id, mediaType).toString()
                val results = JSONObject(resJson).optJSONArray("results") ?: JSONArray()
                com.poobi.tvbrowser.shared.AppCacheManager.saveLibraryCategory(context, cacheKey, results)
                withContext(Dispatchers.Main) {
                    _libraryItems.value = results
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _libraryItems.value = JSONArray()
                }
            }
        }
    }

    data class SpotlightActorData(
        val id: Int,
        val name: String,
        val profilePath: String,
        var frequency: Int
    )

    private var spotlightRankedActors = listOf<SpotlightActorData>()
    private val spotlightActorCreditsMap = java.util.concurrent.ConcurrentHashMap<Int, List<JSONObject>>()
    private val spotlightSeenIds = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private var spotlightActorIndexOffset = 0

    private suspend fun fetchActorCredits(actor: SpotlightActorData): List<JSONObject> = withContext(Dispatchers.IO) {
        val cached = spotlightActorCreditsMap[actor.id]
        if (cached != null) return@withContext cached

        val actorCacheKey = "spotlight_actor_credits_${actor.id}"
        val cachedArr = com.poobi.tvbrowser.shared.AppCacheManager.getLibraryCategory(context, actorCacheKey)
        if (cachedArr != null) {
            val list = (0 until cachedArr.length()).map { cachedArr.getJSONObject(it) }
            spotlightActorCreditsMap[actor.id] = list
            return@withContext list
        }

        try {
            val py = com.chaquo.python.Python.getInstance()
            val tmdb = py.getModule("tmdb.tmdb_utils")
            val detailsJson = tmdb.callAttr("get_person_details", actor.id).toString()
            val combinedCredits = JSONObject(detailsJson).optJSONObject("combined_credits")
            val castArr = combinedCredits?.optJSONArray("cast") ?: JSONArray()

            val parsedList = mutableListOf<JSONObject>()
            for (j in 0 until castArr.length()) {
                val creditItem = castArr.getJSONObject(j)
                val mType = creditItem.optString("media_type")
                if (mType == "movie" || mType == "tv") {
                    parsedList.add(creditItem)
                }
            }

            val sorted = parsedList.sortedWith(
                compareByDescending<JSONObject> { it.optDouble("popularity", 0.0) }
                    .thenByDescending { it.optInt("vote_count", 0) }
            ).take(30)

            val toSave = JSONArray()
            sorted.forEach { toSave.put(it) }
            com.poobi.tvbrowser.shared.AppCacheManager.saveLibraryCategory(context, actorCacheKey, toSave)

            spotlightActorCreditsMap[actor.id] = sorted
            sorted
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun loadCastInSpotlight() {
        val cached = com.poobi.tvbrowser.shared.AppCacheManager.getLibraryCategory(context, "cast_in_spotlight")
        if (cached != null) {
            cancelLibraryJobs(clearCurrentItems = false)
            _libraryItems.value = cached
            return
        }

        cancelLibraryJobs(clearCurrentItems = true)
        viewModelScope.launch(Dispatchers.IO) {
            val favsJson = prefs.getString("streams_favorites", "[]") ?: "[]"
            val recentsJson = prefs.getString("streams_recently_played", "[]") ?: "[]"
            val favsArray = JSONArray(favsJson)
            val recentsArray = JSONArray(recentsJson)

            val seedMediaList = mutableListOf<JSONObject>()
            spotlightSeenIds.clear()

            for (i in 0 until favsArray.length()) {
                val item = favsArray.optJSONObject(i) ?: continue
                val id = item.optString("id").ifEmpty { item.optString("imdb") }
                if (id.isNotEmpty()) {
                    spotlightSeenIds.add(id)
                    seedMediaList.add(item)
                }
            }

            for (i in 0 until recentsArray.length()) {
                val entry = recentsArray.optJSONObject(i) ?: continue
                val item = entry.optJSONObject("item") ?: continue
                val id = item.optString("id").ifEmpty { item.optString("imdb") }
                if (id.isNotEmpty()) {
                    spotlightSeenIds.add(id)
                    if (seedMediaList.none { (it.optString("id").ifEmpty { it.optString("imdb") }) == id }) {
                        seedMediaList.add(item)
                    }
                }
            }

            if (seedMediaList.isEmpty()) {
                withContext(Dispatchers.Main) { _libraryItems.value = JSONArray() }
                return@launch
            }

            val actorMap = mutableMapOf<Int, SpotlightActorData>()

            for (item in seedMediaList.take(25)) {
                val id = item.optString("id")
                val mediaType = item.optString("media_type").ifEmpty { if (item.has("name")) "tv" else "movie" }

                var details = com.poobi.tvbrowser.shared.AppCacheManager.getDetails(context, mediaType, id)
                if (details == null && id.isNotEmpty() && id != "0") {
                    try {
                        val py = com.chaquo.python.Python.getInstance()
                        val tmdb = py.getModule("tmdb.tmdb_utils")
                        val dStr = tmdb.callAttr("get_details", id, mediaType).toString()
                        details = JSONObject(dStr)
                        com.poobi.tvbrowser.shared.AppCacheManager.saveDetails(context, mediaType, id, details)
                    } catch (_: Exception) {}
                }

                val cast = details?.optJSONObject("aggregate_credits")?.optJSONArray("cast")
                    ?: details?.optJSONObject("credits")?.optJSONArray("cast")
                if (cast != null) {
                    for (cIdx in 0 until cast.length().coerceAtMost(10)) {
                        val actorObj = cast.getJSONObject(cIdx)
                        val actorId = actorObj.optInt("id")
                        val actorName = actorObj.optString("name")
                        val profilePath = actorObj.optString("profile_path", "")
                        if (actorId > 0 && actorName.isNotBlank()) {
                            val data = actorMap.getOrPut(actorId) {
                                SpotlightActorData(actorId, actorName, profilePath, 0)
                            }
                            data.frequency++
                        }
                    }
                }
            }

            val selectedActorIds = com.poobi.tvbrowser.shared.FavoriteActorsManager.getSelectedActorIds(context).toSet()

            val allRanked = actorMap.values.sortedWith(
                compareByDescending<SpotlightActorData> { it.frequency }
                    .thenBy { it.name }
            )

            spotlightRankedActors = if (selectedActorIds.isNotEmpty()) {
                allRanked.filter { selectedActorIds.contains(it.id) }
            } else {
                allRanked
            }

            if (spotlightRankedActors.isEmpty()) {
                withContext(Dispatchers.Main) { _libraryItems.value = JSONArray() }
                return@launch
            }

            spotlightActorIndexOffset = 0
            val initialBatchSize = 6.coerceAtMost(spotlightRankedActors.size)
            val initialActors = spotlightRankedActors.subList(0, initialBatchSize)
            spotlightActorIndexOffset = initialBatchSize

            ensureActorMapping()
            val combinedResult = JSONArray()
            val starredList = mutableListOf<Pair<JSONObject, Int>>()

            for (actor in initialActors) {
                val credits = fetchActorCredits(actor)
                var addedForActor = 0
                for (credit in credits) {
                    val cid = credit.optString("id").ifEmpty { credit.optString("imdb") }
                    if (cid.isNotEmpty() && !spotlightSeenIds.contains(cid)) {
                        spotlightSeenIds.add(cid)
                        val creditCopy = JSONObject(credit.toString())

                        val mType = creditCopy.optString("media_type")
                        val matchedActors = (if (mType.isNotEmpty()) globalMediaToActorMap["${mType}_$cid"] else null) 
                            ?: globalMediaToActorMap[cid]

                        val actorCount: Int
                        if (!matchedActors.isNullOrEmpty()) {
                            val arr = JSONArray()
                            synchronized(matchedActors) {
                                matchedActors.forEach { a ->
                                    arr.put(JSONObject().apply {
                                        put("id", a.id)
                                        put("name", a.name)
                                        put("profile_path", a.profilePath)
                                    })
                                }
                            }
                            creditCopy.put("spotlight_actors", arr)
                            creditCopy.put("spotlight_actor_name", matchedActors[0].name)
                            if (matchedActors[0].profilePath.isNotEmpty()) {
                                creditCopy.put("spotlight_actor_profile", matchedActors[0].profilePath)
                            }
                            actorCount = matchedActors.size
                        } else {
                            creditCopy.put("spotlight_actor_name", actor.name)
                            if (actor.profilePath.isNotBlank()) {
                                creditCopy.put("spotlight_actor_profile", actor.profilePath)
                            }
                            actorCount = 1
                        }

                        starredList.add(Pair(creditCopy, actorCount))
                        addedForActor++
                        if (addedForActor >= 3) break
                    }
                }
            }

            starredList.sortByDescending { it.second }
            starredList.forEach { combinedResult.put(it.first) }

            if (combinedResult.length() > 0) {
                com.poobi.tvbrowser.shared.AppCacheManager.saveLibraryCategory(context, "cast_in_spotlight", combinedResult)
            }

            withContext(Dispatchers.Main) {
                _libraryItems.value = combinedResult
                setupSpotlightPagination()
            }
        }
    }

    private fun setupSpotlightPagination() {
        currentFeedPage = 1
        hasMoreFeedPages = true
        currentFeedLoader = { _ ->
            withContext(Dispatchers.IO) {
                val nextBatch = JSONArray()
                val batchSize = 6
                val totalActors = spotlightRankedActors.size
                if (totalActors == 0) return@withContext null

                val actorsToProcess = mutableListOf<SpotlightActorData>()
                if (spotlightActorIndexOffset < totalActors) {
                    val end = (spotlightActorIndexOffset + batchSize).coerceAtMost(totalActors)
                    actorsToProcess.addAll(spotlightRankedActors.subList(spotlightActorIndexOffset, end))
                    spotlightActorIndexOffset = end
                } else {
                    val start = (spotlightActorIndexOffset % totalActors)
                    val end = (start + batchSize).coerceAtMost(totalActors)
                    actorsToProcess.addAll(spotlightRankedActors.subList(start, end))
                    spotlightActorIndexOffset += batchSize
                }

                val pageStarredList = mutableListOf<Pair<JSONObject, Int>>()
                for (actor in actorsToProcess) {
                    val credits = fetchActorCredits(actor)
                    var addedForActor = 0
                    for (credit in credits) {
                        val cid = credit.optString("id").ifEmpty { credit.optString("imdb") }
                        if (cid.isNotEmpty() && !spotlightSeenIds.contains(cid)) {
                            spotlightSeenIds.add(cid)
                            val creditCopy = JSONObject(credit.toString())

                            val mType = creditCopy.optString("media_type")
                            val matchedActors = (if (mType.isNotEmpty()) globalMediaToActorMap["${mType}_$cid"] else null) 
                                ?: globalMediaToActorMap[cid]

                            val actorCount: Int
                            if (!matchedActors.isNullOrEmpty()) {
                                val arr = JSONArray()
                                synchronized(matchedActors) {
                                    matchedActors.forEach { a ->
                                        arr.put(JSONObject().apply {
                                            put("id", a.id)
                                            put("name", a.name)
                                            put("profile_path", a.profilePath)
                                        })
                                    }
                                }
                                creditCopy.put("spotlight_actors", arr)
                                creditCopy.put("spotlight_actor_name", matchedActors[0].name)
                                if (matchedActors[0].profilePath.isNotEmpty()) {
                                    creditCopy.put("spotlight_actor_profile", matchedActors[0].profilePath)
                                }
                                actorCount = matchedActors.size
                            } else {
                                creditCopy.put("spotlight_actor_name", actor.name)
                                if (actor.profilePath.isNotBlank()) {
                                    creditCopy.put("spotlight_actor_profile", actor.profilePath)
                                }
                                actorCount = 1
                            }

                            pageStarredList.add(Pair(creditCopy, actorCount))
                            addedForActor++
                            if (addedForActor >= 3) break
                        }
                    }
                }
                pageStarredList.sortByDescending { it.second }
                pageStarredList.forEach { nextBatch.put(it.first) }
                if (nextBatch.length() > 0) nextBatch else null
            }
        }
    }

    fun loadFavorites() { 
        cancelLibraryJobs(clearCurrentItems = false)
        val list = try { JSONArray(prefs.getString("streams_favorites", "[]") ?: "[]") } catch (_: Exception) { JSONArray() }
        _libraryItems.value = list
        loadProgressCounts()
    }
    
    fun loadRecentlyPlayed() { 
        cancelLibraryJobs(clearCurrentItems = false)
        val list = try { JSONArray(prefs.getString("streams_recently_played", "[]") ?: "[]") } catch (_: Exception) { JSONArray() }
        _libraryItems.value = list
    }
    
    fun removeFromRecentlyPlayed(entryToRemove: JSONObject) {
        viewModelScope.launch(Dispatchers.IO) {
            val recentJson = prefs.getString("streams_recently_played", "[]") ?: "[]"
            val array = JSONArray(recentJson)
            val targetItem = entryToRemove.optJSONObject("item") ?: entryToRemove
            val targetId = targetItem.optString("id")
            val targetImdb = targetItem.optString("imdb")
            val targetSeason = if (entryToRemove.has("season")) entryToRemove.optInt("season") else null
            val targetEpisode = if (entryToRemove.has("episode")) entryToRemove.optInt("episode") else null
            val targetTitle = entryToRemove.optString("display_title")

            var foundIndex = -1
            for (i in 0 until array.length()) {
                val entry = array.getJSONObject(i)
                val itm = entry.optJSONObject("item") ?: entry
                val id = itm.optString("id")
                val imdb = itm.optString("imdb")
                val sameId = (id.isNotEmpty() && id == targetId) || (imdb.isNotEmpty() && imdb == targetImdb)
                val sameSeason = (if (entry.has("season")) entry.optInt("season") else null) == targetSeason
                val sameEpisode = (if (entry.has("episode")) entry.optInt("episode") else null) == targetEpisode
                val sameTitle = targetTitle.isNotEmpty() && entry.optString("display_title") == targetTitle

                if ((sameId && sameSeason && sameEpisode) || sameTitle) {
                    foundIndex = i
                    break
                }
            }

            if (foundIndex != -1) {
                val entry = array.getJSONObject(foundIndex)
                val displayTitle = entry.optString("display_title")
                val item = entry.optJSONObject("item")
                if (item != null) {
                    val season = if (entry.has("season")) entry.getInt("season") else 0
                    val episode = if (entry.has("episode")) entry.getInt("episode") else 0
                    val itemKey = com.poobi.tvbrowser.shared.SubtitleCacheManager.getItemKey(item, season, episode)
                    if (itemKey.isNotEmpty()) {
                        val cachedSubs = com.poobi.tvbrowser.shared.SubtitleCacheManager.loadSubtitles(context, itemKey)
                        cachedSubs.forEach { sub ->
                            try {
                                val uri = android.net.Uri.parse(sub.url)
                                if (uri.scheme == "file") {
                                    val path = uri.path
                                    if (path != null) {
                                        val file = File(path)
                                        if (file.exists() && file.isFile) {
                                            file.delete()
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e("StreamsViewModel", "Failed to delete subtitle file: ${e.message}")
                            }
                        }
                        com.poobi.tvbrowser.shared.SubtitleCacheManager.removeSubtitles(context, itemKey)
                    }
                }

                val newList = JSONArray()
                for (i in 0 until array.length()) {
                    if (i != foundIndex) newList.put(array.get(i))
                }
                prefs.edit().putString("streams_recently_played", newList.toString()).commit()

                if (displayTitle.isNotEmpty()) {
                    StreamResumeManager.removePosition(prefs, displayTitle)
                }

                if (item != null) {
                    val s = if (entry.has("season")) entry.getInt("season") else null
                    val e = if (entry.has("episode")) entry.getInt("episode") else null
                    TvHomeSyncManager.removeWatchNext(context, item, s, e)
                }
                TvHomeSyncManager.syncWatchNext(context)

                withContext(Dispatchers.Main) {
                    loadRecentlyPlayed()
                }
            }
        }
    }

    fun onVideoPlaybackStarted(
        url: String, 
        displayTitle: String, 
        item: JSONObject, 
        season: Int?, 
        episode: Int?, 
        headers: Map<String, String>,
        subtitles: Map<String, Map<String, String>>
    ) {
        commitPendingSearchQuery()
        val itemKey = com.poobi.tvbrowser.shared.SubtitleCacheManager.getItemKey(item, season, episode)
        if (subtitles.isNotEmpty()) {
            val copiedSubtitles = subtitles.toMap()
            interceptedSubtitleUrls.putAll(copiedSubtitles)

            if (itemKey.isNotEmpty()) {
                val newlyDownloadedSubs = subtitles.map { (subUrl, infoMap) ->
                    SubtitleData(
                        url = subUrl,
                        label = infoMap["label"] ?: "Web Subtitle",
                        lang = infoMap["lang"] ?: "und"
                    )
                }
                com.poobi.tvbrowser.shared.SubtitleCacheManager.saveSubtitles(context, itemKey, newlyDownloadedSubs)
            }
        }

        addToRecentlyPlayed(displayTitle, item, season, episode, url, headers, sourceData = lastSelectedSource?.toString())

        val autoSubMode = prefs.getInt("auto_sub_pref", 0)
        if (autoSubMode == 0) {
            fetchManualSubtitles(item, season, episode)
        } else if (autoSubMode == 1 && interceptedSubtitleUrls.isEmpty()) {
            performAutoSubtitleSearch(item, season, episode)
        }
    }

    private suspend fun resolveEpisodeMetadata(
        showId: String,
        season: Int,
        episode: Int
    ): Pair<String?, String?> = withContext(Dispatchers.IO) {
        if (showId.isEmpty()) return@withContext Pair(null, null)

        fun findInArray(arr: JSONArray?, matchSeason: Boolean = false): Pair<String?, String?>? {
            if (arr == null) return null
            for (i in 0 until arr.length()) {
                val ep = arr.optJSONObject(i) ?: continue
                val numMatches = ep.optInt("episode_number") == episode
                val seasonMatches = !matchSeason || ep.optInt("season_number", season) == season
                if (numMatches && seasonMatches) {
                    val still = ep.optString("still_path").takeIf { it.isNotBlank() && it != "null" }
                    val overview = ep.optString("overview").takeIf { it.isNotBlank() && it != "null" }
                    return Pair(still, overview)
                }
            }
            return null
        }

        findInArray(_itemEpisodes.value, matchSeason = true)?.let { return@withContext it }

        findInArray(com.poobi.tvbrowser.shared.AppCacheManager.getEpisodes(context, showId, season))?.let { return@withContext it }

        try {
            val py = getPythonInstance()
            val scraper = py.getModule("main")
            val episodesJson = scraper.callAttr("get_tv_episodes", showId, season).toString()
            val remoteEpisodes = JSONArray(episodesJson)
            if (remoteEpisodes.length() > 0) {
                com.poobi.tvbrowser.shared.AppCacheManager.saveEpisodes(context, showId, season, remoteEpisodes)
                findInArray(remoteEpisodes)?.let { return@withContext it }
            }
        } catch (e: Exception) {
            Log.e("StreamsViewModel", "Failed resolving episode metadata: ${e.message}")
        }

        Pair(null, null)
    }

    private fun addToRecentlyPlayed(
        displayTitle: String, 
        item: JSONObject, 
        season: Int?, 
        episode: Int?, 
        videoUrl: String? = null, 
        headers: Map<String, String>? = null,
        durationMs: Long = 0L,
        sourceData: String? = null
    ) {
        markItemAccessed(item)
        viewModelScope.launch(Dispatchers.IO) {
            val recentJson = prefs.getString("streams_recently_played", "[]") ?: "[]"
            val array = JSONArray(recentJson)
            val newList = mutableListOf<JSONObject>()
            for (i in 0 until array.length()) newList.add(array.getJSONObject(i))

            val id = item.optString("id")
            val imdb = item.optString("imdb")

            val existingIndex = newList.indexOfFirst {
                val itItem = it.optJSONObject("item")
                val itId = itItem?.optString("id")
                val itIdValue = itId ?: ""
                val itImdb = itItem?.optString("imdb")
                val itImdbValue = itImdb ?: ""
                val sameItem = (itIdValue == id && id.isNotEmpty()) || (itImdbValue == imdb && imdb.isNotEmpty())
                val sameEpisode = it.optInt("season", -1) == (season ?: -1) && it.optInt("episode", -1) == (episode ?: -1)
                sameItem && sameEpisode
            }

            var existing: JSONObject? = null
            if (existingIndex != -1) {
                existing = newList.removeAt(existingIndex)
            }

            val savedItem = JSONObject(item.toString())
            if (season != null && episode != null) {
                val itemId = item.optString("id").ifEmpty { item.optString("imdb") }
                val (stillFound, overviewFound) = resolveEpisodeMetadata(itemId, season, episode)
                val finalStill = stillFound ?: savedItem.optString("still_path").takeIf { it.isNotBlank() && it != "null" }

                if (finalStill != null) {
                    savedItem.put("still_path", finalStill)
                } else {
                    savedItem.remove("still_path")
                }

                if (overviewFound != null) {
                    savedItem.put("overview", overviewFound)
                }
            }

            val newEntry = JSONObject().apply {
                put("display_title", displayTitle)
                put("item", savedItem)
                if (season != null) put("season", season)
                if (episode != null) put("episode", episode)
                val still = savedItem.optString("still_path")
                if (still.isNotBlank() && still != "null") put("still_path", still)
                val finalDuration = if (durationMs > 0L) durationMs else existing?.optLong("duration_ms", 0L) ?: 0L
                if (finalDuration > 0L) put("duration_ms", finalDuration)

                val finalUrl = videoUrl ?: existing?.optString("video_url")
                if (!finalUrl.isNullOrEmpty()) put("video_url", finalUrl)

                val finalHeaders = if (headers != null) JSONObject(headers) else existing?.optJSONObject("headers")
                if (finalHeaders != null) put("headers", finalHeaders)

                val finalSourceData = sourceData ?: existing?.optString("source_data")
                if (!finalSourceData.isNullOrEmpty()) put("source_data", finalSourceData)

                val finalAudioLang = existing?.optString("audio_language")
                if (!finalAudioLang.isNullOrEmpty()) put("audio_language", finalAudioLang)
                val finalAudioLabel = existing?.optString("audio_label")
                if (!finalAudioLabel.isNullOrEmpty()) put("audio_label", finalAudioLabel)
                if (existing != null && existing.has("audio_index")) put("audio_index", existing.getInt("audio_index"))
            }
            newList.add(0, newEntry)
            if (newList.size > 20) newList.removeAt(newList.size - 1)

            prefs.edit().putString("streams_recently_played", newList.toString()).apply()
            TvHomeSyncManager.syncWatchNext(context)
        }
    }

    fun findItem(id: String, title: String? = null): JSONObject? {
        if (id.isNotEmpty()) {
            val favsJson = prefs.getString("streams_favorites", "[]") ?: "[]"
            try {
                val array = JSONArray(favsJson)
                for (i in 0 until array.length()) {
                    val it = array.optJSONObject(i) ?: continue
                    if (it.optString("id") == id || (it.optString("imdb").isNotEmpty() && it.optString("imdb") == id)) {
                        return it
                    }
                }
            } catch (_: Exception) {}

            val recentJson = prefs.getString("streams_recently_played", "[]") ?: "[]"
            try {
                val array = JSONArray(recentJson)
                for (i in 0 until array.length()) {
                    val entry = array.optJSONObject(i) ?: continue
                    val it = entry.optJSONObject("item") ?: continue
                    if (it.optString("id") == id || (it.optString("imdb").isNotEmpty() && it.optString("imdb") == id)) {
                        return it
                    }
                }
            } catch (_: Exception) {}
        }

        if (!title.isNullOrBlank()) {
            val cleanTitle = title.trim().lowercase()
            val favsJson = prefs.getString("streams_favorites", "[]") ?: "[]"
            try {
                val array = JSONArray(favsJson)
                for (i in 0 until array.length()) {
                    val it = array.optJSONObject(i) ?: continue
                    val t = it.optString("title").ifEmpty { it.optString("name") }.trim().lowercase()
                    if (t == cleanTitle) {
                        return it
                    }
                }
            } catch (_: Exception) {}

            val recentJson = prefs.getString("streams_recently_played", "[]") ?: "[]"
            try {
                val array = JSONArray(recentJson)
                for (i in 0 until array.length()) {
                    val entry = array.optJSONObject(i) ?: continue
                    val it = entry.optJSONObject("item") ?: continue
                    val t = it.optString("title").ifEmpty { it.optString("name") }.trim().lowercase()
                    if (t == cleanTitle) {
                        return it
                    }
                }
            } catch (_: Exception) {}
        }
        return null
    }

    fun findRecentlyPlayedEntry(id: String, season: Int?, episode: Int?, title: String? = null): JSONObject? {
        val recentJson = prefs.getString("streams_recently_played", "[]") ?: "[]"
        try {
            val array = JSONArray(recentJson)
            for (i in 0 until array.length()) {
                val entry = array.optJSONObject(i) ?: continue
                val item = entry.optJSONObject("item") ?: continue
                val itemId = item.optString("id")
                val itemImdb = item.optString("imdb")

                val matchesId = id.isNotEmpty() && (itemId == id || (itemImdb.isNotEmpty() && itemImdb == id))
                val matchesTitle = !matchesId && !title.isNullOrBlank() && 
                    (item.optString("title").equals(title, ignoreCase = true) || 
                     item.optString("name").equals(title, ignoreCase = true) ||
                     entry.optString("display_title").startsWith(title, ignoreCase = true))

                if (matchesId || matchesTitle) {
                    if (season != null && episode != null) {
                        val entrySeason = if (entry.has("season")) entry.optInt("season") else null
                        val entryEpisode = if (entry.has("episode")) entry.optInt("episode") else null
                        if (entrySeason == season && entryEpisode == episode) {
                            return entry
                        }
                    } else if (season == null && episode == null) {
                        val isTv = item.optString("media_type") == "tv" || item.has("name")
                        if (!isTv || (!entry.has("season") && !entry.has("episode"))) {
                            return entry
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("StreamsViewModel", "Error finding recently played entry: ${e.message}")
        }
        return null
    }

    fun selectRecentlyPlayedItem(entry: JSONObject) {
        val item = entry.optJSONObject("item") ?: entry
        markItemAccessed(item)
        val season = if (entry.has("season")) entry.getInt("season") else null
        val episode = if (entry.has("episode")) entry.getInt("episode") else null
        val videoUrl = entry.optString("video_url")
        val title = entry.optString("display_title").ifEmpty {
            item.optString("title").ifEmpty { item.optString("name", "Unknown") }
        }

        lastScrapedSeason = season
        lastScrapedEpisode = episode

        selectMediaItem(item, initialSeason = season)

        if (videoUrl.isNotEmpty()) {
            val headersMap = mutableMapOf<String, String>()
            entry.optJSONObject("headers")?.let { headers ->
                headers.keys().forEach { headersMap[it] = headers.getString(it) }
            }
            
            val itemKey = com.poobi.tvbrowser.shared.SubtitleCacheManager.getItemKey(item, season, episode)
            val subtitleUrls = mutableMapOf<String, Map<String, String>>()
            if (itemKey.isNotEmpty()) {
                val cachedSubs = com.poobi.tvbrowser.shared.SubtitleCacheManager.loadSubtitles(context, itemKey)
                cachedSubs.forEach { sub ->
                    subtitleUrls[sub.url] = mapOf("label" to sub.label, "lang" to sub.lang)
                }
            }
            interceptedSubtitleUrls.forEach { (u, info) ->
                subtitleUrls[u] = info
            }
            interceptedSubtitleUrls.clear()
            interceptedSubtitleUrls.putAll(subtitleUrls)

            isPlayingFromSavedLink = true

            var nextEp: JSONObject? = null
            if (season != null && episode != null) {
                val episodes = com.poobi.tvbrowser.shared.AppCacheManager.getEpisodes(context, item.optInt("id").toString(), season)
                if (episodes != null) {
                    for (i in 0 until episodes.length()) {
                        val ep = episodes.getJSONObject(i)
                        if (ep.optInt("episode_number") == episode + 1) {
                            val airDate = ep.optString("air_date", "")
                            if (airDate.isNotEmpty() && !com.poobi.tvbrowser.shared.isFutureDate(airDate)) {
                                nextEp = ep
                            }
                            break
                        }
                    }
                }
            }

            _events.value = StreamsEvent.PlayVideo(
                url = videoUrl,
                title = title,
                headers = headersMap,
                subtitles = subtitleUrls.toMap(),
                item = item,
                season = season,
                episode = episode,
                nextEpisode = nextEp
            )
        } else {
            isPlayingFromSavedLink = false
            performScrape(item, season, episode)
        }
    }

    fun refreshFavoritesSet() {
        viewModelScope.launch(Dispatchers.IO) {
            val favsJson = prefs.getString("streams_favorites", "[]") ?: "[]"
            val array = JSONArray(favsJson)
            val set = mutableSetOf<String>()
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val id = item.optString("id")
                val imdb = item.optString("imdb")
                if (id.isNotEmpty()) set.add(id)
                if (imdb.isNotEmpty()) set.add(imdb)
            }
            withContext(Dispatchers.Main) {
                _favoritesSet.value = set
            }
        }
        refreshFavoriteActorsSet()
    }

    fun refreshFavoriteActorsSet() {
        viewModelScope.launch(Dispatchers.IO) {
            val actors = com.poobi.tvbrowser.shared.FavoriteActorsManager.getActors(context)
            val set = actors.filter { it.isEnabled }.map { it.id }.toSet()
            val wasEmpty = _favoriteActorsSet.value.isEmpty()
            val changed = _favoriteActorsSet.value != set
            withContext(Dispatchers.Main) {
                _favoriteActorsSet.value = set
            }
            if (changed && !wasEmpty) {
                refreshCurrentFeedWithActors()
            }
        }
    }

    fun refreshCurrentFeedWithActors() {
        viewModelScope.launch(Dispatchers.IO) {
            val prioritizeFavCast = prefs.getBoolean("genres_prioritize_fav_cast", true)
            if (prioritizeFavCast) {
                ensureActorMapping()
                val current = _libraryItems.value
                if (current != null && current.length() > 0) {
                    val refreshed = processFeedItems(current, prioritizeFavCast = true)
                    withContext(Dispatchers.Main) {
                        _libraryItems.value = refreshed
                    }
                }
            }
        }
    }

    fun toggleFavoriteActor(personId: Int, name: String, profilePath: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val currentSet = _favoriteActorsSet.value
            val isCurrentlyFav = currentSet.contains(personId)
            if (isCurrentlyFav) {
                com.poobi.tvbrowser.shared.FavoriteActorsManager.removeActor(context, personId)
                withContext(Dispatchers.Main) {
                    _favoriteActorsSet.value = currentSet - personId
                    _events.value = StreamsEvent.ShowToast("Removed $name from Favourite Cast")
                }
            } else {
                commitPendingSearchQuery()
                com.poobi.tvbrowser.shared.FavoriteActorsManager.addActor(
                    context,
                    com.poobi.tvbrowser.shared.FavoriteActor(
                        id = personId,
                        name = name,
                        profilePath = profilePath,
                        isEnabled = true,
                        sourceTitles = listOf("Added from Cast")
                    )
                )
                withContext(Dispatchers.Main) {
                    _favoriteActorsSet.value = currentSet + personId
                    _events.value = StreamsEvent.ShowToast("Added $name to Favourite Cast")
                }
            }
            ensureActorMapping(forceRefresh = true)
            withContext(Dispatchers.Main) {
                refreshCurrentFeedWithActors()
            }
        }
    }

    fun navigateBackFromMediaDetails(): Boolean {
        if (mediaNavigationStack.isNotEmpty()) {
            val previousItem = mediaNavigationStack.removeAt(mediaNavigationStack.size - 1)
            selectMediaItemInternal(previousItem, pushToStack = false)
            return true
        } else {
            clearSelectedMedia()
            return false
        }
    }

    fun toggleFavorite(item: JSONObject, isCurrentlyViewingFavorites: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            val favsJson = prefs.getString("streams_favorites", "[]") ?: "[]"
            val array = JSONArray(favsJson)
            val id = item.optString("id")
            val imdb = item.optString("imdb")
            var foundIndex = -1
            for (i in 0 until array.length()) {
                val fav = array.getJSONObject(i)
                if (fav.optString("id") == id || (imdb.isNotEmpty() && fav.optString("imdb") == imdb)) {
                    foundIndex = i; break
                }
            }
            if (foundIndex != -1) {
                array.remove(foundIndex)
                _events.value = StreamsEvent.ShowToast("Removed from Favorites")
            } else {
                if (!item.has("genre_ids") && !item.has("genres")) {
                    _itemDetails.value?.let { details ->
                        if (details.optInt("id") == item.optInt("id")) {
                            val genres = details.optJSONArray("genres")
                            if (genres != null) item.put("genres", genres)
                        }
                    }
                }

                if (item.optString("imdb").isEmpty()) {
                    _itemDetails.value?.let { details ->
                        if (details.optInt("id") == item.optInt("id")) {
                            val imdbId = details.optJSONObject("external_ids")?.optString("imdb_id") ?: ""
                            if (imdbId.isNotEmpty()) {
                                item.put("imdb", imdbId)
                            }
                        }
                    }
                }

                commitPendingSearchQuery()
                array.put(item)
                _events.value = StreamsEvent.ShowToast("Added to Favorites")
            }
            prefs.edit().putString("streams_favorites", array.toString()).apply()
            TvHomeSyncManager.syncFavoritesChannel(context)
            refreshFavoritesSet()

            viewModelScope.launch(Dispatchers.IO) {
                com.poobi.tvbrowser.shared.FavoriteActorsManager.syncWithFavorites(context)
            }

            if (isCurrentlyViewingFavorites) {
                withContext(Dispatchers.Main) {
                    loadFavorites()
                }
            }
        }
        loadProgressCounts()
    }

    fun markItemAccessed(item: JSONObject) {
        val id = item.optString("id")
        val imdb = item.optString("imdb")
        if (id.isEmpty() && imdb.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val json = prefs.getString("media_last_accessed_map", "{}") ?: "{}"
                val obj = JSONObject(json)
                val now = System.currentTimeMillis()
                if (id.isNotEmpty()) obj.put(id, now)
                if (imdb.isNotEmpty()) obj.put(imdb, now)
                prefs.edit().putString("media_last_accessed_map", obj.toString()).apply()
            } catch (_: Exception) {}
        }
    }

    fun selectMediaItem(item: JSONObject, initialSeason: Int? = null) {
        val id = item.optString("id").ifEmpty { item.optString("imdb") }
        if (id.isNotEmpty()) {
            lastViewedMediaId = id
        }
        selectMediaItemInternal(item, initialSeason, pushToStack = true)
    }

    private fun selectMediaItemInternal(item: JSONObject, initialSeason: Int? = null, pushToStack: Boolean = true) {
        if (pushToStack) {
            val current = _selectedItem.value
            if (current != null) {
                val currId = current.optString("id")
                val newId = item.optString("id")
                if (currId != newId) {
                    mediaNavigationStack.add(current)
                }
            }
        }

        markItemAccessed(item)
        val showCopy = JSONObject(item.toString())
        showCopy.remove("still_path")
        _selectedItem.value = showCopy
        _itemDetails.value = null
        _itemEpisodes.value = null
        _itemSeasons.value = null
        _lastWatchedEpisode.value = null
        _isMovieWatched.value = false
        recsPage = 1
        hasMoreRecs = true
        isLoadingMoreRecs = false
        if (initialSeason == null) {
            lastScrapedSeason = null
            lastScrapedEpisode = null
        }
        
        val id = item.optInt("id")
        val mediaType = item.optString("media_type").takeIf { it.isNotEmpty() && it != "null" }
            ?: if (item.has("name") || item.has("first_air_date")) "tv" else "movie"

        if (mediaType == "person") {
            loadPersonDetails(id, item)
            return
        }

        if (mediaType == "movie") {
            checkMovieWatchedStatus(item)
        }

        val cached = com.poobi.tvbrowser.shared.AppCacheManager.getDetails(context, mediaType, id.toString())
        if (cached != null) {
            _itemDetails.value = cached
            _itemRecommendations.value = cached.optJSONObject("recommendations")?.optJSONArray("results")
            _itemCast.value = cached.optJSONObject("aggregate_credits")?.optJSONArray("cast")
                ?: cached.optJSONObject("credits")?.optJSONArray("cast")

            if (mediaType == "tv") {
                val seasons = cached.optJSONArray("seasons")
                val sortedSeasons = if (seasons != null) sortSeasons(seasons) else null
                _itemSeasons.value = sortedSeasons
                if (sortedSeasons != null && sortedSeasons.length() > 0) {
                    viewModelScope.launch(Dispatchers.IO) {
                        val nextEpPairRaw = getTraktNextEpisode(item)
                        val nextEpPair = if (nextEpPairRaw != null) {
                            adjustSeasonAndEpisodeBoundaries(sortedSeasons, nextEpPairRaw.first, nextEpPairRaw.second)
                        } else null
                        val seasonToLoad = initialSeason ?: nextEpPair?.first ?: sortedSeasons.getJSONObject(0).optInt("season_number")
                        withContext(Dispatchers.Main) {
                            loadEpisodes(item, seasonToLoad, isAutoSelect = true, targetEpFromTrakt = nextEpPair?.second)
                        }
                    }
                }
            }
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val py = getPythonInstance()
                val tmdb = py.getModule("tmdb.tmdb_utils")
                val detailsJson = tmdb.callAttr("get_details", id, mediaType).toString()
                val details = JSONObject(detailsJson)
                
                if (mediaType == "tv") {
                    val seasons = details.optJSONArray("seasons")
                    if (seasons != null) details.put("seasons", sortSeasons(seasons))
                }
                com.poobi.tvbrowser.shared.AppCacheManager.saveDetails(context, mediaType, id.toString(), details)

                withContext(Dispatchers.Main) {
                    _itemDetails.value = details
                    _itemRecommendations.value = details.optJSONObject("recommendations")?.optJSONArray("results")
                    _itemCast.value = details.optJSONObject("aggregate_credits")?.optJSONArray("cast")
                        ?: details.optJSONObject("credits")?.optJSONArray("cast")

                    if (mediaType == "tv") {
                        val seasons = details.optJSONArray("seasons")
                        _itemSeasons.value = seasons
                        if (seasons != null && seasons.length() > 0) {
                            viewModelScope.launch(Dispatchers.IO) {
                                val nextEpPairRaw = getTraktNextEpisode(item)
                                val nextEpPair = if (nextEpPairRaw != null) {
                                    adjustSeasonAndEpisodeBoundaries(seasons, nextEpPairRaw.first, nextEpPairRaw.second)
                                } else null

                                val seasonToLoad = initialSeason ?: nextEpPair?.first ?: seasons.getJSONObject(0).optInt("season_number")
                                withContext(Dispatchers.Main) {
                                    loadEpisodes(item, seasonToLoad, isAutoSelect = true, targetEpFromTrakt = nextEpPair?.second)
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {}
        }
    }

    private fun checkMovieWatchedStatus(item: JSONObject) {
        viewModelScope.launch(Dispatchers.IO) {
            if (!isTraktAuthorized()) {
                withContext(Dispatchers.Main) {
                    _isMovieWatched.value = false
                }
                return@launch
            }
            try {
                val py = Python.getInstance()
                val checker = py.getModule("trakt.movie_check")
                val watched = checker.callAttr("is_movie_watched", item.toString()).toBoolean()
                withContext(Dispatchers.Main) {
                    _isMovieWatched.value = watched
                }
            } catch (e: Exception) {
                Log.e("StreamsViewModel", "Failed checking movie watched status: ${e.message}")
            }
        }
    }

    fun toggleMovieWatched(item: JSONObject) {
        viewModelScope.launch(Dispatchers.IO) {
            if (!isTraktAuthorized()) {
                withContext(Dispatchers.Main) {
                    _events.value = StreamsEvent.ShowToast("Trakt not authorized")
                }
                return@launch
            }

            val currentStatus = _isMovieWatched.value
            withContext(Dispatchers.Main) {
                _isMovieWatched.value = !currentStatus
            }

            try {
                val py = Python.getInstance()
                val scrobbler = py.getModule("trakt.trakt_scrobble")
                val success = if (currentStatus) {
                    scrobbler.callAttr("remove_from_history", item.toString()).toBoolean()
                } else {
                    scrobbler.callAttr("add_to_history", item.toString()).toBoolean()
                }
                withContext(Dispatchers.Main) {
                    if (success) {
                        if (!currentStatus) {
                            TvHomeSyncManager.removeWatchNext(context, item, null, null)
                        }
                        _events.value = StreamsEvent.ShowToast(if (currentStatus) "Marked as unwatched" else "Marked as watched")
                        TvHomeSyncManager.syncWatchNext(context)
                    } else {
                        _isMovieWatched.value = currentStatus
                        _events.value = StreamsEvent.ShowToast("Failed to update status on Trakt")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _isMovieWatched.value = currentStatus
                    _events.value = StreamsEvent.ShowToast("Trakt update failed")
                }
            }
        }
    }

    fun toggleEpisodeWatched(item: JSONObject, season: Int, episode: Int) {
        val episodesList = _itemEpisodes.value ?: return
        var isCurrentlyWatched = false
        var episodeIndex = -1
        for (i in 0 until episodesList.length()) {
            val ep = episodesList.getJSONObject(i)
            if (ep.optInt("episode_number") == episode) {
                isCurrentlyWatched = ep.optBoolean("is_watched", false)
                episodeIndex = i
                break
            }
        }
        if (episodeIndex == -1) return

        viewModelScope.launch(Dispatchers.IO) {
            if (!isTraktAuthorized()) {
                withContext(Dispatchers.Main) {
                    toggleEpisodeWatchedLocalState(season, episode, !isCurrentlyWatched)
                    _events.value = StreamsEvent.ShowToast("Local status updated (Trakt not linked)")
                }
                return@launch
            }

            withContext(Dispatchers.Main) {
                toggleEpisodeWatchedLocalState(season, episode, !isCurrentlyWatched)
            }

            try {
                val py = Python.getInstance()
                val scrobbler = py.getModule("trakt.trakt_scrobble")
                val success = if (isCurrentlyWatched) {
                    scrobbler.callAttr("remove_from_history", item.toString(), season, episode).toBoolean()
                } else {
                    scrobbler.callAttr("add_to_history", item.toString(), season, episode).toBoolean()
                }
                withContext(Dispatchers.Main) {
                    if (success) {
                        viewModelScope.launch(Dispatchers.IO) {
                            try {
                                val py = getPythonInstance()
                                py.getModule("trakt.episode_check").callAttr("invalidate_show_progress_cache", item.optInt("id"))
                                loadProgressCounts()
                            } catch (e: Exception) {}
                        }
                        _events.value = StreamsEvent.ShowToast(if (isCurrentlyWatched) "Episode marked unwatched" else "Episode marked watched")
                        TvHomeSyncManager.syncWatchNext(context)
                    } else {
                        toggleEpisodeWatchedLocalState(season, episode, isCurrentlyWatched)
                        _events.value = StreamsEvent.ShowToast("Failed to update Trakt")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    toggleEpisodeWatchedLocalState(season, episode, isCurrentlyWatched)
                    _events.value = StreamsEvent.ShowToast("Trakt sync failed")
                }
            }
        }
    }

    private fun toggleEpisodeWatchedLocalState(season: Int, episode: Int, newWatchedState: Boolean) {
        val id = _selectedItem.value?.optInt("id") ?: return

        val cachedArray = com.poobi.tvbrowser.shared.AppCacheManager.getEpisodes(context, id.toString(), season)
        if (cachedArray != null) {
            for (i in 0 until cachedArray.length()) {
                val ep = cachedArray.getJSONObject(i)
                if (ep.optInt("episode_number") == episode) {
                    ep.put("is_watched", newWatchedState)
                    break
                }
            }
            com.poobi.tvbrowser.shared.AppCacheManager.saveEpisodes(context, id.toString(), season, cachedArray)
        }

        val currentEpisodes = _itemEpisodes.value
        if (currentEpisodes != null) {
            val updatedArray = JSONArray(currentEpisodes.toString())
            var modified = false
            for (i in 0 until updatedArray.length()) {
                val ep = updatedArray.getJSONObject(i)
                if (ep.optInt("episode_number") == episode) {
                    ep.put("is_watched", newWatchedState)
                    modified = true
                    break
                }
            }
            if (modified) {
                _itemEpisodes.value = updatedArray
            }
        }
    }

    fun selectCastMember(personId: Int, personName: String, profilePath: String = "") {
        val personItem = JSONObject().apply {
            put("id", personId)
            put("name", personName)
            put("title", personName)
            put("media_type", "person")
            if (profilePath.isNotEmpty()) {
                put("poster_path", profilePath)
                put("profile_path", profilePath)
            }
        }
        selectMediaItem(personItem)
    }

    private fun loadPersonDetails(personId: Int, item: JSONObject) {
        _personDetails.value = null
        _personCredits.value = emptyList()
        _personDisplayedCredits.value = emptyList()
        personFilterMode.value = "All"

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val py = getPythonInstance()
                val tmdb = py.getModule("tmdb.tmdb_utils")
                val detailsJson = tmdb.callAttr("get_person_details", personId).toString()
                val details = JSONObject(detailsJson)

                val combinedCredits = details.optJSONObject("combined_credits")
                val castArray = combinedCredits?.optJSONArray("cast") ?: JSONArray()

                val rawCreditsList = mutableListOf<JSONObject>()
                val seenIds = mutableSetOf<String>()

                for (i in 0 until castArray.length()) {
                    val c = castArray.getJSONObject(i)
                    val mType = c.optString("media_type")
                    if (mType == "movie" || mType == "tv") {
                        val cId = c.optString("id")
                        val key = "${mType}_$cId"
                        if (!seenIds.contains(key)) {
                            seenIds.add(key)
                            rawCreditsList.add(c)
                        }
                    }
                }

                val favs = _favoritesSet.value
                val sorted = rawCreditsList.sortedWith { a, b ->
                    val aId = a.optString("id")
                    val aImdb = a.optString("imdb")
                    val bId = b.optString("id")
                    val bImdb = b.optString("imdb")
                    val aFav = favs.contains(aId) || (aImdb.isNotEmpty() && favs.contains(aImdb))
                    val bFav = favs.contains(bId) || (bImdb.isNotEmpty() && favs.contains(bImdb))
                    if (aFav != bFav) {
                        if (aFav) -1 else 1
                    } else {
                        val popA = a.optDouble("popularity", 0.0)
                        val popB = b.optDouble("popularity", 0.0)
                        popB.compareTo(popA)
                    }
                }

                fullPersonCreditsList = sorted

                withContext(Dispatchers.Main) {
                    _personDetails.value = details
                    _personCredits.value = sorted
                    _personDisplayedCredits.value = sorted.take(20)
                }
            } catch (e: Exception) {
                Log.e("StreamsViewModel", "Failed to load person details: ${e.message}")
            }
        }
    }

    fun filterPersonCredits(filterKey: String) {
        personFilterMode.value = filterKey
        val filtered = when (filterKey.lowercase()) {
            "movies", "movie" -> fullPersonCreditsList.filter { it.optString("media_type") == "movie" }
            "tv shows", "tv" -> fullPersonCreditsList.filter { it.optString("media_type") == "tv" }
            else -> fullPersonCreditsList
        }
        _personCredits.value = filtered
        _personDisplayedCredits.value = filtered.take(20)
    }

    fun loadMorePersonCredits() {
        val currentList = _personCredits.value
        val currentDisplayed = _personDisplayedCredits.value
        if (currentDisplayed.size < currentList.size) {
            val nextCount = (currentDisplayed.size + 20).coerceAtMost(currentList.size)
            _personDisplayedCredits.value = currentList.take(nextCount)
        }
    }

    fun loadMoreRecommendations() {
        if (isLoadingMoreRecs || !hasMoreRecs) return
        val item = _selectedItem.value ?: return
        val id = item.optString("id")
        if (id.isEmpty() || id == "0") return
        val mediaType = item.optString("media_type").takeIf { it.isNotEmpty() && it != "null" }
            ?: if (item.has("name")) "tv" else "movie"

        isLoadingMoreRecs = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val nextPage = recsPage + 1
                val py = getPythonInstance()
                val tmdb = py.getModule("tmdb.tmdb_utils")
                val resJson = tmdb.callAttr("get_recommendations", id, mediaType, nextPage).toString()
                val results = JSONObject(resJson).optJSONArray("results")
                if (results != null && results.length() > 0) {
                    recsPage = nextPage
                    val current = _itemRecommendations.value ?: JSONArray()
                    val combined = JSONArray(current.toString())
                    for (i in 0 until results.length()) {
                        combined.put(results.getJSONObject(i))
                    }
                    withContext(Dispatchers.Main) {
                        _itemRecommendations.value = combined
                    }
                } else {
                    hasMoreRecs = false
                }
            } catch (_: Exception) {
                hasMoreRecs = false
            } finally {
                isLoadingMoreRecs = false
            }
        }
    }

    fun loadSeasonCast(showId: Int, seasonNumber: Int) {
        if (showId <= 0) return

        val cachedCast = com.poobi.tvbrowser.shared.AppCacheManager.getSeasonCredits(context, showId.toString(), seasonNumber)
        if (cachedCast != null && cachedCast.length() > 0) {
            _itemCast.value = cachedCast
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val py = getPythonInstance()
                val tmdb = py.getModule("tmdb.tmdb_utils")
                val creditsObj = tmdb.callAttr("get_season_credits", showId, seasonNumber).toString()
                val castArray = JSONObject(creditsObj).optJSONArray("cast")
                if (castArray != null && castArray.length() > 0) {
                    com.poobi.tvbrowser.shared.AppCacheManager.saveSeasonCredits(context, showId.toString(), seasonNumber, castArray)
                    withContext(Dispatchers.Main) {
                        if (isActive && _selectedItem.value?.optInt("id") == showId) {
                            _itemCast.value = castArray
                        }
                    }
                } else {
                    val fallbackCast = _itemDetails.value?.optJSONObject("aggregate_credits")?.optJSONArray("cast")
                        ?: _itemDetails.value?.optJSONObject("credits")?.optJSONArray("cast")
                    if (fallbackCast != null && fallbackCast.length() > 0) {
                        withContext(Dispatchers.Main) {
                            if (isActive && _selectedItem.value?.optInt("id") == showId && (_itemCast.value == null || _itemCast.value!!.length() == 0)) {
                                _itemCast.value = fallbackCast
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("StreamsViewModel", "Failed to load season credits for $showId S$seasonNumber: ${e.message}")
            }
        }
    }

    fun loadEpisodes(item: JSONObject, seasonNumber: Int, isAutoSelect: Boolean = false, targetEpFromTrakt: Int? = null) {
        val id = item.optInt("id")
        _itemEpisodes.value = null

        if (isAutoSelect) {
            _lastWatchedEpisode.value = targetEpFromTrakt
        } else {
            _lastWatchedEpisode.value = null
        }

        loadSeasonCast(id, seasonNumber)

        val cached = com.poobi.tvbrowser.shared.AppCacheManager.getEpisodes(context, id.toString(), seasonNumber)
        if (cached != null && cached.length() > 0) {
            _itemEpisodes.value = cached
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val py = getPythonInstance()
                    val statusJson = py.getModule("trakt.episode_check").callAttr("get_watched_status", id, seasonNumber, cached.toString()).toString()
                    val watchedStatus = JSONArray(statusJson)
                    var changed = false
                    val updated = JSONArray(cached.toString())
                    for (i in 0 until updated.length()) {
                        val isW = if (i < watchedStatus.length()) watchedStatus.getBoolean(i) else false
                        if (updated.getJSONObject(i).optBoolean("is_watched", false) != isW) {
                            updated.getJSONObject(i).put("is_watched", isW)
                            changed = true
                        }
                    }
                    if (changed) {
                        com.poobi.tvbrowser.shared.AppCacheManager.saveEpisodes(context, id.toString(), seasonNumber, updated)
                        withContext(Dispatchers.Main) {
                            if (isActive && _selectedItem.value?.optInt("id") == id) {
                                _itemEpisodes.value = updated
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val py = getPythonInstance()
                val scraper = py.getModule("main")
                val episodesJson = scraper.callAttr("get_tv_episodes", id, seasonNumber).toString()
                val episodes = JSONArray(episodesJson)
                if (episodes.length() > 0) {
                    com.poobi.tvbrowser.shared.AppCacheManager.saveEpisodes(context, id.toString(), seasonNumber, episodes)
                }
                try {
                    val statusJson = py.getModule("trakt.episode_check").callAttr("get_watched_status", id, seasonNumber, episodes.toString()).toString()
                    val watchedStatus = JSONArray(statusJson)
                    for (i in 0 until episodes.length()) {
                        episodes.getJSONObject(i).put("is_watched", if (i < watchedStatus.length()) watchedStatus.getBoolean(i) else false)
                    }
                } catch (e: Exception) {}
                withContext(Dispatchers.Main) { 
                    if (isActive) _itemEpisodes.value = episodes 
                }
            } catch (e: Exception) {}
        }
    }

    fun stopScrape(triggerSubtitles: Boolean = false) {
        stopTryAll()
        scrapePollingJob?.cancel()
        scrapePollingJob = null
        scrapeJob?.cancel()
        scrapeJob = null
        _scrapeProgress.value = 0
        _scrapeTotal.value = 0
        
        runBlocking(Dispatchers.IO) {
            try { 
                Python.getInstance().getModule("main").callAttr("stop_scrape") 
            } catch (e: Exception) {
                Log.e("StreamsViewModel", "Failed stopping Python scraper cleanly: ${e.message}")
            }
        }
        
        _isScraping.value = false
        _isResolving.value = false
        _scrapeStatusMsg.value = "Scraping Stopped"

        if (triggerSubtitles) {
            val item = _selectedItem.value
            if (item != null && prefs.getInt("auto_sub_pref", 0) == 1) {
                performAutoSubtitleSearch(item, lastScrapedSeason, lastScrapedEpisode)
            }
        }
    }

    private var filteredSourcesToTry: List<JSONObject>? = null

    fun startTryAll() {
        if (_isTryingAll.value) {
            stopTryAll()
            return
        }

        val sources = _scrapedSources.value ?: return
        val list = mutableListOf<JSONObject>()
        for (i in 0 until sources.length()) {
            val s = sources.optJSONObject(i) ?: continue
            val title = s.optString("title", "")
            if (!title.startsWith("[BROWSER]")) {
                list.add(s)
            }
        }

        if (list.isEmpty()) {
            _events.value = StreamsEvent.ShowToast("No non-browser sources to try.")
            return
        }

        filteredSourcesToTry = list
        _isTryingAll.value = true
        currentTryingIndex = -1
        isInteractingWithSources = true

        viewModelScope.launch(Dispatchers.IO) {
            try { Python.getInstance().getModule("main").callAttr("pause_scrape") } catch (e: Exception) {}
        }

        tryNextSource()
    }

    fun stopTryAll(resume: Boolean = true) {
        if (!_isTryingAll.value) return
        _isTryingAll.value = false
        tryAllJob?.cancel()
        tryAllJob = null
        currentTryingIndex = -1
        filteredSourcesToTry = null
        if (resume) resumeScrape()
    }

    private fun tryNextSource() {
        if (!_isTryingAll.value) return

        val sources = filteredSourcesToTry ?: run { 
            stopTryAll()
            return 
        }
        
        currentTryingIndex++

        if (currentTryingIndex >= sources.size) {
            _events.value = StreamsEvent.ShowToast("Finished trying all sources. None found.")
            _scrapeStatusMsg.value = "Finished trying all sources."
            stopTryAll()
            return
        }

        val s = sources[currentTryingIndex]
        val sourceDataJson = s.optString("source_data")

        _isResolving.value = true
        _scrapeStatusMsg.value = "Trying source ${currentTryingIndex + 1}/${sources.size}..."

        tryAllJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val py = Python.getInstance()
                val scraper = py.getModule("main")
                val resolveResult = scraper.callAttr("resolve", sourceDataJson).toString()

                val json = JSONObject(resolveResult)
                if (json.has("error")) {
                    withContext(Dispatchers.Main) { tryNextSource() }
                    return@launch
                }

                val streamUrl = json.optString("url")
                if (streamUrl.isEmpty() || (!streamUrl.startsWith("http") && !streamUrl.startsWith("file://"))) {
                    withContext(Dispatchers.Main) { tryNextSource() }
                    return@launch
                }

                val parsedHeaders = mutableMapOf<String, String>()
                val headersJson = json.optJSONObject("headers")
                if (headersJson != null) {
                    val keys = headersJson.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        parsedHeaders[key] = headersJson.getString(key)
                    }
                }

                val headersMap = mutableMapOf<String, String>()
                try {
                    val sourceData = JSONObject(sourceDataJson)
                    val hObj = sourceData.optJSONObject("headers")
                    hObj?.keys()?.forEach { headersMap[it] = hObj.getString(it) }
                } catch (e: Exception) {}

                headersMap.putAll(parsedHeaders)

                if (checkUrlValidity(streamUrl, headersMap)) {
                    withContext(Dispatchers.Main) {
                        _isResolving.value = false
                        _scrapeStatusMsg.value = "Valid source found! Playing..."
                        playStream(streamUrl, json.optBoolean("is_video", false), sourceDataJson, parsedHeaders)
                    }
                } else {
                    withContext(Dispatchers.Main) { tryNextSource() }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                withContext(Dispatchers.Main) { tryNextSource() }
            }
        }
    }

    private suspend fun checkUrlValidity(url: String, headers: Map<String, String>): Boolean = withContext(Dispatchers.IO) {
        if (url.startsWith("file://")) {
            return@withContext true
        }
        try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "HEAD"
            headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            
            val responseCode = connection.responseCode
            val contentType = connection.contentType ?: ""

			if (responseCode in 200..299 || responseCode in 301..308) {
				if (contentType.contains("mpegurl", ignoreCase = true) || url.contains(".m3u8", ignoreCase = true)) {
					return@withContext verifyM3u8Content(url, headers)
				}

				val isValid = contentType.contains("video", ignoreCase = true) ||
							  contentType.contains("mp4", ignoreCase = true) ||
							  contentType.contains("octet-stream", ignoreCase = true) ||
							  url.split("?")[0].endsWith(".mkv", ignoreCase = true) ||
							  url.split("?")[0].endsWith(".mp4", ignoreCase = true) ||
							  responseCode in 301..308

				return@withContext isValid
			}

            if (responseCode == 405 || responseCode == 403 || responseCode == 501 || responseCode == 404) {
                val getConn = URL(url).openConnection() as HttpURLConnection
                getConn.requestMethod = "GET"
                headers.forEach { (k, v) -> getConn.setRequestProperty(k, v) }
                getConn.setRequestProperty("Range", "bytes=0-8192")
                getConn.connectTimeout = 8000
                getConn.readTimeout = 8000
                
                val getResponseCode = getConn.responseCode
                val getContentType = getConn.contentType ?: ""

                if ((getResponseCode in 200..299) || getResponseCode == 206) {
                    getConn.inputStream.use { input ->
                        val buffer = ByteArray(8192)
                        val read = input.read(buffer)
                        if (read > 0) {
                            val content = String(buffer, 0, read)
                            if (content.contains("#EXTM3U")) {
                                return@withContext verifyM3u8Segments(content, url, headers)
                            }
                            
                            val isVideoType = getContentType.contains("video", ignoreCase = true) || 
                                              getContentType.contains("octet-stream", ignoreCase = true)

                            return@withContext isVideoType
                        }
                    }
                }
            }
        } catch (e: Exception) {}
        false
    }

    private suspend fun verifyM3u8Content(url: String, headers: Map<String, String>): Boolean = withContext(Dispatchers.IO) {
        try {
            val connection = URL(url).openConnection() as HttpURLConnection
            headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            connection.inputStream.use { input ->
                val buffer = ByteArray(8192)
                val read = input.read(buffer)
                if (read > 0) {
                    val content = String(buffer, 0, read)
                    return@withContext verifyM3u8Segments(content, url, headers)
                }
            }
        } catch (e: Exception) {
            Log.e("StreamsViewModel", "Failed to verify M3U8 content: ${e.message}")
        }
        false
    }

    private suspend fun verifyM3u8Segments(content: String, baseUrl: String, headers: Map<String, String>, depth: Int = 0): Boolean = withContext(Dispatchers.IO) {
        if (!content.contains("#EXTM3U") || depth > 2) return@withContext false
        
        val lines = content.lines()
        var firstSegmentUrl: String? = null
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            firstSegmentUrl = trimmed
            break
        }

        if (firstSegmentUrl == null) {
            return@withContext false
        }

        val fullSegmentUrl = if (firstSegmentUrl.startsWith("http")) {
            firstSegmentUrl
        } else {
            try {
                val uri = java.net.URI(baseUrl)
                uri.resolve(firstSegmentUrl).toString()
            } catch (e: Exception) {
                Log.e("StreamsViewModel", "Failed to resolve relative segment URL: $firstSegmentUrl")
                return@withContext false
            }
        }

        try {
            val connection = URL(fullSegmentUrl).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
            connection.setRequestProperty("Range", "bytes=0-8192")
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            
            val responseCode = connection.responseCode
            val contentType = connection.contentType ?: ""

            if ((responseCode in 200..299) || responseCode == 206) {
                if (contentType.contains("mpegurl", ignoreCase = true) || fullSegmentUrl.contains(".m3u8", ignoreCase = true)) {
                    connection.inputStream.use { input ->
                        val buffer = ByteArray(8192)
                        val read = input.read(buffer)
                        if (read > 0) {
                            val nestedContent = String(buffer, 0, read)
                            return@withContext verifyM3u8Segments(nestedContent, fullSegmentUrl, headers, depth + 1)
                        }
                    }
                    return@withContext false
                }

                val lowerUrl = fullSegmentUrl.lowercase()
                val isSuspicious = lowerUrl.run {
                    endsWith(".png") || endsWith(".svg") || endsWith(".css") || 
                    endsWith(".js") || endsWith(".woff") || endsWith(".csv") || 
                    endsWith(".json") || endsWith(".ttf") || endsWith(".otf") || 
                    endsWith(".txt") || endsWith(".php") || endsWith(".html")
                } || contentType.contains("image", ignoreCase = true) || 
                   contentType.contains("text/", ignoreCase = true) ||
                   contentType.contains("application/javascript", ignoreCase = true) ||
                   contentType.contains("font", ignoreCase = true)

                if (isSuspicious) {
                    connection.inputStream.use { input ->
                        val buffer = ByteArray(376)
                        val read = input.read(buffer)
                        if (read >= 4) {
                            val isMp4Video = read >= 12 && (
                                (buffer[4] == 'f'.toByte() && buffer[5] == 't'.toByte() && buffer[6] == 'y'.toByte() && buffer[7] == 'p'.toByte()) ||
                                (buffer[8] == 'f'.toByte() && buffer[9] == 't'.toByte() && buffer[10] == 'y'.toByte() && buffer[11] == 'p'.toByte())
                            )

                            val isTsVideo = buffer[0] == 0x47.toByte() || (read > 188 && buffer[188] == 0x47.toByte())

                            if (isMp4Video || isTsVideo) {
                                return@withContext true
                            }

                            val isRealPng = buffer[0] == 0x89.toByte() && buffer[1] == 0x50.toByte() && 
                                            buffer[2] == 0x4E.toByte() && buffer[3] == 0x47.toByte()
                                            
                            val isRealGif = buffer[0] == 'G'.toByte() && buffer[1] == 'I'.toByte() && 
                                            buffer[2] == 'F'.toByte() && buffer[3] == '8'.toByte()

                            if (isRealPng || isRealGif) {
                                return@withContext false
                            }
                            
                            val head = String(buffer, 0, minOf(read, 64))
                            if (head.contains("<svg", true) || head.contains("<?xml", true) || 
                                head.contains("<!DOCTYPE", true) || head.contains("<html", true)) {
                                return@withContext false
                            }
                            
                            if (head.startsWith("{") || head.startsWith("[") || head.startsWith("/*") || head.startsWith("//")) {
                                return@withContext false
                            }
                        }
                    }
                }
                return@withContext true
            }
        } catch (e: Exception) {
            Log.e("StreamsViewModel", "Segment verification failed: ${e.message}")
        }

        false
    }

    private fun playStream(streamUrl: String, isVideo: Boolean, sourceDataJson: String, extraHeaders: Map<String, String> = emptyMap()) {
        _isResolving.value = false
        _isBufferingTorrent.value = false
        val cleanTitle = _selectedItem.value?.optString("title")?.takeIf { it.isNotBlank() } ?: _selectedItem.value?.optString("name") ?: "Unknown"
        val fullTitle = if (lastScrapedSeason != null && lastScrapedEpisode != null) {
            "$cleanTitle S${lastScrapedSeason}E${lastScrapedEpisode}"
        } else cleanTitle

        val headersMap = mutableMapOf<String, String>()
        try {
            val sourceData = JSONObject(sourceDataJson)
            val hObj = sourceData.optJSONObject("headers")
            hObj?.keys()?.forEach { headersMap[it] = hObj.getString(it) }
        } catch (e: Exception) {}

        headersMap.putAll(extraHeaders)

        var nextEp: JSONObject? = null
        if (lastScrapedSeason != null && lastScrapedEpisode != null) {
            val currentEp = lastScrapedEpisode!!
            val item = _selectedItem.value
            if (item != null) {
                val episodes = com.poobi.tvbrowser.shared.AppCacheManager.getEpisodes(context, item.optInt("id").toString(), lastScrapedSeason!!)
                if (episodes != null) {
                    for (i in 0 until episodes.length()) {
                        val ep = episodes.getJSONObject(i)
                        if (ep.optInt("episode_number") == currentEp + 1) {
                            val airDate = ep.optString("air_date", "")
                            if (airDate.isNotEmpty() && !com.poobi.tvbrowser.shared.isFutureDate(airDate)) {
                                nextEp = ep
                            }
                            break
                        }
                    }
                }
            }
        }

        val altUrls = mutableListOf<String>()
        val altNames = mutableListOf<String>()
        try {
            val sObj = JSONObject(sourceDataJson)
            val aUrls = sObj.optJSONArray("alternative_urls")
            val aNames = sObj.optJSONArray("alternative_names")
            if (aUrls != null && aNames != null) {
                for (i in 0 until aUrls.length()) {
                    altUrls.add(aUrls.getString(i))
                    altNames.add(aNames.getString(i))
                }
            }
        } catch (e: Exception) {}

        val item = _selectedItem.value
        val itemKey = com.poobi.tvbrowser.shared.SubtitleCacheManager.getItemKey(item, lastScrapedSeason, lastScrapedEpisode)
        val allSubs = interceptedSubtitleUrls.toMutableMap()
        if (item != null && itemKey.isNotEmpty()) {
            val cached = com.poobi.tvbrowser.shared.SubtitleCacheManager.loadSubtitles(context, itemKey)
            cached.forEach { sub ->
                if (!allSubs.containsKey(sub.url)) {
                    allSubs[sub.url] = mapOf("label" to sub.label, "lang" to sub.lang)
                }
            }
            interceptedSubtitleUrls.putAll(allSubs)
        }

        _events.value = StreamsEvent.PlayVideo(
            url = streamUrl,
            title = fullTitle,
            headers = headersMap,
            subtitles = allSubs.toMap(),
            item = _selectedItem.value!!,
            season = lastScrapedSeason,
            episode = lastScrapedEpisode,
            nextEpisode = nextEp,
            isWebpage = !isVideo,
            alternativeUrls = altUrls,
            alternativeNames = altNames
        )
    }

    fun onPlaybackError() {
        if (_isTryingAll.value) {
            viewModelScope.launch(Dispatchers.Main) {
                tryNextSource()
            }
        }
    }

    private fun onScrapeFinished(item: JSONObject, season: Int?, episode: Int?, sources: JSONArray?) {
        scrapePollingJob?.cancel()
        scrapePollingJob = null
        scrapeJob?.cancel()
        scrapeJob = null
        
        _isScraping.value = false
        _isResolving.value = false
        val count = sources?.length() ?: _scrapedSources.value?.length() ?: 0
        _scrapeStatusMsg.value = "Finished! Found $count sources."
        if (_scrapeTotal.value > 0) {
            _scrapeProgress.value = _scrapeTotal.value
        }
        
        if (sources != null && sources.length() > 0) {
            _scrapedSources.value = sortSources(sources)
        }
        
        if (prefs.getInt("auto_sub_pref", 0) == 1) { 
            performAutoSubtitleSearch(item, season, episode)
        }
    }

    fun performScrape(item: JSONObject, season: Int? = null, episode: Int? = null) {
        commitPendingSearchQuery()
        isPlayingFromSavedLink = false
        stopScrape()
        selectedScrapeTabIndex.value = 0
        lastSelectedSourceIndex = -1
        lastSelectedSourceKey = null
        
        _selectedItem.value = item
        lastScrapedSeason = season
        lastScrapedEpisode = episode
        interceptedSubtitleUrls.clear()
        lastSubtitledItemKey = null
        _subStatusMsg.value = ""

        val itemKey = com.poobi.tvbrowser.shared.SubtitleCacheManager.getItemKey(item, season, episode)
        if (itemKey.isNotEmpty()) {
            val cached = com.poobi.tvbrowser.shared.SubtitleCacheManager.loadSubtitles(context, itemKey)
            cached.forEach { sub ->
                interceptedSubtitleUrls[sub.url] = mapOf("label" to sub.label, "lang" to sub.lang)
            }
        }

        _isScraping.value = true
        _isResolving.value = false
        _scrapedSources.value = null
        _torrentioSources.value = null
        _isScrapingTorrents.value = true
        _scrapeProgress.value = 0
        _scrapeTotal.value = 0
        _scrapeStatusMsg.value = "Starting scrapers..."
        isInteractingWithSources = false

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val py = Python.getInstance()
                val tmdb = py.getModule("tmdb.tmdb_utils")
                
                val id = item.optString("id")
                val mediaType = item.optString("media_type").takeIf { it.isNotEmpty() && it != "null" }
                    ?: if (item.has("name") || item.has("first_air_date")) "tv" else "movie"
                
                Log.d("StreamsViewModel", "Torrentio Scrape - ID: $id, MediaType: $mediaType")

                var imdbId = item.optString("imdb")
                if (imdbId.isNullOrEmpty() || imdbId == "null") {
                    val details = _itemDetails.value
                    if (details != null && details.optInt("id").toString() == id) {
                        imdbId = details.optJSONObject("external_ids")?.optString("imdb_id") ?: ""
                    }
                }

                if (imdbId.isNullOrEmpty() || imdbId == "null") {
                    val extIds = JSONObject(tmdb.callAttr("get_external_ids", id, mediaType).toString())
                    imdbId = extIds.optString("imdb_id")
                }
                
                Log.d("StreamsViewModel", "Torrentio Scrape - IMDb ID: $imdbId")

                if (!imdbId.isNullOrEmpty() && imdbId != "null") {
                    val torrentio = py.getModule("torrentio.torrentio").callAttr("source")
                    val url = if (mediaType == "tv" || mediaType == "tvshow") {
                        "$imdbId:${season ?: 1}:${episode ?: 1}"
                    } else {
                        imdbId
                    }
                    
                    val torrentLanguage = prefs.getString("torrent_language", "English") ?: "English"
                    Log.d("StreamsViewModel", "Torrentio Scrape - URL Param: $url, Lang: $torrentLanguage")

                    val results = torrentio.callAttr("sources", url, emptyList<String>(), mapOf("torrent_language" to torrentLanguage))
                    Log.d("StreamsViewModel", "Torrentio Scrape - Results count: ${results.asList().size}")
                    val json = py.getModule("json")
                    val resultsJson = json.callAttr("dumps", results).toString()
                    val rawStreams = JSONArray(resultsJson)

                    val finalStreams = JSONArray()
                    for (i in 0 until rawStreams.length()) {
                        val r = rawStreams.getJSONObject(i)
                        val obj = JSONObject().apply {
                            put("title", r.optString("title", ""))
                            put("source_data", r.toString())
                        }
                        finalStreams.put(obj)
                    }

                    withContext(Dispatchers.Main) {
                        _torrentioSources.value = sortTorrents(finalStreams)
                    }
                }
            } catch (e: Exception) {
                Log.e("StreamsViewModel", "Torrentio scrape error: ${e.message}")
            } finally {
                withContext(Dispatchers.Main) {
                    _isScrapingTorrents.value = false
                }
            }
        }

        scrapePollingJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    val statusStr = Python.getInstance().getModule("main").callAttr("get_scrape_status").toString()
                    val status = JSONObject(statusStr)
                    val current = status.optInt("current", 0)
                    val total = status.optInt("total", 0)
                    val message = status.optString("message", "")
                    val sources = status.optJSONArray("sources")
                    val currentCount = _scrapedSources.value?.length() ?: 0
                    val newCount = sources?.length() ?: 0

                    val isFinished = message.startsWith("Finished") || 
                                     message.startsWith("Stopped") || 
                                     (total > 0 && current >= total)

                    val sortedSources = if (sources != null && !isInteractingWithSources && (newCount != currentCount || _scrapedSources.value == null)) {
                        sortSources(sources)
                    } else null

                    withContext(Dispatchers.Main) {
                        if (total > 0) {
                            _scrapeProgress.value = current
                            _scrapeTotal.value = total
                        }
                        
                        _scrapeStatusMsg.value = when {
                            _isResolving.value -> "Resolving Link..."
                            isInteractingWithSources -> "Pausing Scrapers..."
                            isFinished -> "Finished! Found ${sources?.length() ?: _scrapedSources.value?.length() ?: 0} sources."
                            message.startsWith("Paused") && isInteractingWithSources -> "Scraping Paused: $current / $total providers..."
                            total > 0 && current < total -> "Scraping: $current / $total providers..."
                            else -> if (message.isNotEmpty() && !message.startsWith("Paused")) message else "Scraping: $current / $total providers..."
                        }

                        if (sortedSources != null) _scrapedSources.value = sortedSources

                        if (isFinished) {
                            _isScraping.value = false
                            _isResolving.value = false
                        }
                    }

                    if (isFinished) {
                        break
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.e("StreamsViewModel", "Polling loop error: ${e.message}")
                }
                delay(1000)
            }
        }

        scrapeJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val sourcesJson = Python.getInstance().getModule("main").callAttr("scrape", item.toString(), season, episode).toString()
                val sources = JSONArray(sourcesJson)
                withContext(Dispatchers.Main) {
                    onScrapeFinished(item, season, episode, sources)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    scrapePollingJob?.cancel()
                    scrapePollingJob = null
                    _isScraping.value = false
                    _scrapedSources.value = JSONArray()
                    _events.value = StreamsEvent.ShowToast("Scrape error: ${e.message}")
                }
            }
        }
    }

    fun fetchManualSubtitles(item: JSONObject, season: Int?, episode: Int?) {
        _isDownloadingSubs.value = true
        _showSubProgressBar.value = true
        _subProgress.value = 0f
        _subStatusMsg.value = "Searching external subtitles..."

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val py = Python.getInstance()
                val scraper = py.getModule("main")
                val subsJson = scraper.callAttr("search_subtitles", item.toString(), season, episode).toString()
                
                withContext(Dispatchers.Main) {
                    _isDownloadingSubs.value = false
                    _showSubProgressBar.value = false
                    _events.value = StreamsEvent.ShowSubtitlePicker(JSONArray(subsJson))
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _isDownloadingSubs.value = false
                    _showSubProgressBar.value = false
                    _events.value = StreamsEvent.ShowToast("Subtitle search failed")
                }
            }
        }
    }

    fun downloadSubtitles(subs: List<JSONObject>, silent: Boolean = false) {
        if (subs.isEmpty()) return
        _isDownloadingSubs.value = true
        _showSubProgressBar.value = true
        _subProgress.value = 0f
        _subStatusMsg.value = "Downloading subtitles: 0 / ${subs.size}"

        viewModelScope.launch(Dispatchers.IO) {
            val newlyDownloadedSubs = java.util.Collections.synchronizedList(mutableListOf<SubtitleData>())
            val completedCount = java.util.concurrent.atomic.AtomicInteger(0)
            try {
                val py = Python.getInstance()
                val scraper = py.getModule("main")

                coroutineScope {
                    subs.map { sub ->
                        async(Dispatchers.IO) {
                            if (!_isDownloadingSubs.value) return@async
                            try {
                                val serviceName = sub.getString("service")
                                val actionArgs = sub.getString("action_args")
                                val filePath = scraper.callAttr("get_subtitle_file", serviceName, actionArgs).toString()

                                if (filePath.isNotEmpty()) {
                                    val originalFile = java.io.File(filePath)
                                    if (originalFile.exists()) {
                                        val dir = originalFile.parentFile
                                        val ext = originalFile.extension
                                        val baseName = originalFile.nameWithoutExtension
                                        val uniqueFileName = "${baseName}_${java.util.UUID.randomUUID()}.$ext"
                                        val uniqueFile = java.io.File(dir, uniqueFileName)

                                        originalFile.copyTo(uniqueFile, overwrite = true)
                                        val subUri = android.net.Uri.fromFile(uniqueFile).toString()
                                        val source = sub.optString("service", "Unknown")
                                        val name = sub.optString("name", "Subtitle")
                                        val label = "[$source] $name"
                                        val lang = sub.optString("lang", "und")

                                        interceptedSubtitleUrls[subUri] = mapOf("label" to label, "lang" to lang)
                                        newlyDownloadedSubs.add(SubtitleData(subUri, label, lang))
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e("TVBrowser", "Failed downloading subtitle: ${e.message}")
                            } finally {
                                val current = completedCount.incrementAndGet()
                                withContext(Dispatchers.Main) {
                                    _subProgress.value = current.toFloat() / subs.size.toFloat()
                                    _subStatusMsg.value = "Downloading subtitles: $current / ${subs.size}"
                                }
                            }
                        }
                    }.awaitAll()
                }

                withContext(Dispatchers.Main) {
                    _subProgress.value = 1f
                    _isDownloadingSubs.value = false
                    _showSubProgressBar.value = false
                    if (newlyDownloadedSubs.isNotEmpty()) {
                        val item = _selectedItem.value
                        val itemKey = com.poobi.tvbrowser.shared.SubtitleCacheManager.getItemKey(item, lastScrapedSeason, lastScrapedEpisode)
                        if (itemKey.isNotEmpty()) {
                            com.poobi.tvbrowser.shared.SubtitleCacheManager.saveSubtitles(context, itemKey, newlyDownloadedSubs.toList())
                        }
                        _events.value = StreamsEvent.AddSubtitlesBatch(newlyDownloadedSubs.toList())
                    }
                    if (!silent) _events.value = StreamsEvent.ShowToast("Finished downloading ${newlyDownloadedSubs.size} subtitles")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _isDownloadingSubs.value = false
                    _showSubProgressBar.value = false
                    if (!silent) _events.value = StreamsEvent.ShowToast("Subtitle download failed")
                }
            }
        }
    }

    fun cancelSubtitleDownloads() { 
        _isDownloadingSubs.value = false 
        _showSubProgressBar.value = false
        _subProgress.value = 0f
        pendingPlayVideoSourceData = null
        _showSubtitleWaitDialog.value = null
    }

    private fun getCurrentEpisodeTitle(season: Int?, episode: Int?): String {
        val episodesList = _itemEpisodes.value ?: return ""
        if (season == null || episode == null) return ""
        for (i in 0 until episodesList.length()) {
            val ep = episodesList.getJSONObject(i)
            if (ep.optInt("episode_number") == episode) {
                return ep.optString("name", "")
            }
        }
        return ""
    }

    private fun matchesEpisodeTitle(subName: String, episodeTitle: String): String {
        if (episodeTitle.isEmpty() || subName.isEmpty()) return ""
        val cleanSub = subName.lowercase().replace(Regex("[^a-zA-Z0-9\\s]"), " ")
        val cleanEp = episodeTitle.lowercase().replace(Regex("[^a-zA-Z0-9\\s]"), " ")
        
        if (cleanSub.contains(cleanEp)) return "exact"
        
        val epWords = cleanEp.split("\\s+".toRegex()).filter { it.length > 3 }
        if (epWords.isNotEmpty() && epWords.all { cleanSub.contains(it) }) return "words"
        
        return ""
    }

    private fun performAutoSubtitleSearch(item: JSONObject, season: Int?, episode: Int?): Boolean {
        val itemKey = com.poobi.tvbrowser.shared.SubtitleCacheManager.getItemKey(item, season, episode)
        if (_isDownloadingSubs.value || lastSubtitledItemKey == itemKey) return false
        
        val cached = com.poobi.tvbrowser.shared.SubtitleCacheManager.loadSubtitles(context, itemKey)
        val countPref = prefs.getInt("auto_sub_count", 1)
        val hasEnoughCached = countPref > 0 && cached.size >= countPref

        if (cached.isNotEmpty() && hasEnoughCached) {
            lastSubtitledItemKey = itemKey
            _subStatusMsg.value = "Using cached subtitles..."
            _isDownloadingSubs.value = false
            _showSubProgressBar.value = false
            _subProgress.value = 1.0f
            
            cached.forEach { sub ->
                interceptedSubtitleUrls[sub.url] = mapOf("label" to sub.label, "lang" to sub.lang)
            }
            
            viewModelScope.launch(Dispatchers.Main) {
                _events.value = StreamsEvent.AddSubtitlesBatch(cached)
                
                pendingPlayVideoSourceData?.let { pendingSource ->
                    _showSubtitleWaitDialog.value = null
                    resolveAndPlayInternal(pendingSource)
                    pendingPlayVideoSourceData = null
                }
            }
            return true
        }

        lastSubtitledItemKey = itemKey
        _isDownloadingSubs.value = true
        _showSubProgressBar.value = true
        _subProgress.value = 0f
        _subStatusMsg.value = "Searching automatic subtitles..."

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val py = Python.getInstance()
                val scraper = py.getModule("main")
                val subsJson = scraper.callAttr("search_subtitles", item.toString(), season, episode).toString()
                val subs = JSONArray(subsJson)

                if (subs.length() > 0) {
                    val countToGet = if (countPref == 0) subs.length() else countPref.coerceAtMost(subs.length())
                    
                    val episodeTitle = getCurrentEpisodeTitle(season, episode)
                    val exactMatchSubs = mutableListOf<JSONObject>()
                    val partialMatchSubs = mutableListOf<JSONObject>()
                    val mismatchedSubs = mutableListOf<JSONObject>()
                    
                    for (i in 0 until subs.length()) {
                        val sub = subs.getJSONObject(i)
                        val name = sub.optString("name", "")
                        when (matchesEpisodeTitle(name, episodeTitle)) {
                            "exact" -> exactMatchSubs.add(sub)
                            "words" -> partialMatchSubs.add(sub)
                            else -> mismatchedSubs.add(sub)
                        }
                    }

                    val finalSortedSubs = mutableListOf<JSONObject>()

                    exactMatchSubs.filter { it.optString("sync") == "true" }.forEach { finalSortedSubs.add(it) }
                    exactMatchSubs.filter { it.optString("sync") != "true" }.forEach { finalSortedSubs.add(it) }

                    partialMatchSubs.filter {it.optString("sync") == "true" }.forEach { finalSortedSubs.add(it) }
                    partialMatchSubs.filter {it.optString("sync") != "true" }.forEach { finalSortedSubs.add(it) }

                    mismatchedSubs.filter { it.optString("sync") == "true" }.forEach { finalSortedSubs.add(it) }
                    mismatchedSubs.filter { it.optString("sync") != "true" }.forEach { finalSortedSubs.add(it) }

                    var downloadedCount = cached.size
                    val newlyDownloadedSubs = mutableListOf<SubtitleData>()

                    cached.forEach { sub ->
                        interceptedSubtitleUrls[sub.url] = mapOf("label" to sub.label, "lang" to sub.lang)
                    }
                    if (cached.isNotEmpty()) {
                        withContext(Dispatchers.Main) {
                            _events.value = StreamsEvent.AddSubtitlesBatch(cached)
                        }
                    }

                    withContext(Dispatchers.Main) {
                        _subStatusMsg.value = "Downloading subtitles: $downloadedCount / $countToGet"
                        _subProgress.value = if (countToGet > 0) downloadedCount.toFloat() / countToGet.toFloat() else 0f
                    }

                    for (i in 0 until finalSortedSubs.size) {
                        if (downloadedCount >= countToGet || !_isDownloadingSubs.value) break
                        
                        val sub = finalSortedSubs[i]
                        val serviceName = sub.getString("service")
                        val actionArgs = sub.getString("action_args")
                        val subName = sub.optString("name", "Subtitle")
                        val subLang = sub.optString("lang", "und")
                        val expectedLabel = "[$serviceName] $subName"

                        val isAlreadyCached = cached.any { 
                            it.label == expectedLabel && it.lang == subLang
                        }
                        if (isAlreadyCached) {
                            continue
                        }

                        val filePath = scraper.callAttr("get_subtitle_file", serviceName, actionArgs).toString()

                        if (filePath.isNotEmpty()) {
                            val originalFile = java.io.File(filePath)
                            if (originalFile.exists()) {
                                val dir = originalFile.parentFile
                                val ext = originalFile.extension
                                val baseName = originalFile.nameWithoutExtension
                                val uniqueFileName = "${baseName}_${java.util.UUID.randomUUID()}.$ext"
                                val uniqueFile = java.io.File(dir, uniqueFileName)
                                
                                try {
                                    originalFile.copyTo(uniqueFile, overwrite = true)
                                    val subUri = android.net.Uri.fromFile(uniqueFile).toString()
                                    val label = expectedLabel
                                    val lang = subLang

                                    interceptedSubtitleUrls[subUri] = mapOf("label" to label, "lang" to lang)
                                    newlyDownloadedSubs.add(SubtitleData(subUri, label, lang))
                                    downloadedCount++

                                    withContext(Dispatchers.Main) { 
                                        _subStatusMsg.value = "Downloading subtitles: $downloadedCount / $countToGet" 
                                        _subProgress.value = downloadedCount.toFloat() / countToGet.toFloat()
                                    }
                                } catch (e: Exception) {
                                    Log.e("TVBrowser", "Failed to copy subtitle to unique path: ${e.message}")
                                }
                            }
                        }
                    }
                    
                    withContext(Dispatchers.Main) { 
                        _subProgress.value = 1f
                        _isDownloadingSubs.value = false 
                        _showSubProgressBar.value = false
                        _showSubtitleWaitDialog.value = null
                        
                        if (newlyDownloadedSubs.isNotEmpty()) {
                            com.poobi.tvbrowser.shared.SubtitleCacheManager.saveSubtitles(context, itemKey, newlyDownloadedSubs)
                            _events.value = StreamsEvent.AddSubtitlesBatch(newlyDownloadedSubs)
                        }

                        pendingPlayVideoSourceData?.let { pendingSource ->
                            resolveAndPlayInternal(pendingSource)
                            pendingPlayVideoSourceData = null
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) { 
                        _subStatusMsg.value = "No subtitles found."
                        delay(2000)
                        _isDownloadingSubs.value = false
                        _showSubProgressBar.value = false
                        _showSubtitleWaitDialog.value = null
                        pendingPlayVideoSourceData?.let { pendingSource ->
                            resolveAndPlayInternal(pendingSource)
                            pendingPlayVideoSourceData = null
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { 
                    _isDownloadingSubs.value = false
                    _showSubProgressBar.value = false
                    _showSubtitleWaitDialog.value = null
                    pendingPlayVideoSourceData?.let { pendingSource ->
                        resolveAndPlayInternal(pendingSource)
                        pendingPlayVideoSourceData = null
                    }
                }
            }
        }
        return false
    }

    fun applySortPriorities() {
        val current = _scrapedSources.value
        if (current != null) {
            _scrapedSources.value = sortSources(current)
        }
        val currentTorrents = _torrentioSources.value
        if (currentTorrents != null) {
            _torrentioSources.value = sortTorrents(currentTorrents)
        }
    }

    private fun sortTorrents(sources: JSONArray): JSONArray {
        val priorities = mutableListOf<TorrentSortCriteria>()
        val json = prefs.getString("torrent_sort_priorities", null)
        if (json != null) {
            try {
                val arr = JSONArray(json)
                for (i in 0 until arr.length()) {
                    priorities.add(TorrentSortCriteria.valueOf(arr.getString(i)))
                }
            } catch (e: Exception) { 
                priorities.addAll(TorrentSorter.DEFAULT_PRIORITIES) 
            }
        } else {
            priorities.addAll(TorrentSorter.DEFAULT_PRIORITIES)
        }
        val torrentLanguage = prefs.getString("torrent_language", "English") ?: "English"
        return TorrentSorter(priorities, torrentLanguage).sort(sources)
    }

    private fun sortSources(sources: JSONArray): JSONArray {
        val priorities = mutableListOf<SortCriteria>()
        val json = prefs.getString("sort_priorities", null)
        if (json != null) {
            try {
                val arr = JSONArray(json)
                for (i in 0 until arr.length()) {
                    priorities.add(SortCriteria.valueOf(arr.getString(i)))
                }
            } catch (e: Exception) { priorities.addAll(SourceSorter.DEFAULT_PRIORITIES) }
        } else priorities.addAll(SourceSorter.DEFAULT_PRIORITIES)
        return SourceSorter(priorities).sort(sources)
    }

    fun resolveAndPlay(sourceDataJson: String, rawItem: JSONObject, index: Int = -1) {
        stopTryAll()
        isPlayingFromSavedLink = false
        val sourceData = try { JSONObject(sourceDataJson) } catch (e: Exception) { rawItem }
        
        val infoHash = sourceData.optString("infoHash", "")
        val fileIdx = sourceData.optInt("fileIdx", -1)

        if (index >= 0) {
            lastSelectedSourceIndex = index
            val url = sourceData.optString("url").ifEmpty { sourceData.optString("link") }
            val source = sourceData.optString("source")
            val provider = sourceData.optString("provider")
            val quality = sourceData.optString("quality")
            val title = rawItem.optString("title").substringBefore("\n").trim()

            lastSelectedSourceKey = when {
                infoHash.isNotEmpty() -> "${infoHash}_$fileIdx"
                url.isNotEmpty() -> "${provider}_${source}_$url"
                else -> "${provider}_${source}_${quality}_$title"
            }
        }
        lastSelectedSource = sourceData
        isInteractingWithSources = true
        _scrapeStatusMsg.value = "Pausing Scrapers..."

        if (infoHash.isNotEmpty() && fileIdx != -1) {
            performTorrentPreBuffering(infoHash, fileIdx, sourceDataJson)
        } else {
            var hasInstantCachedSubs = false
            if (_isScraping.value && !_isDownloadingSubs.value && lastSubtitledItemKey == null) {
                val item = _selectedItem.value
                if (item != null && prefs.getInt("auto_sub_pref", 0) == 1) {
                    hasInstantCachedSubs = performAutoSubtitleSearch(item, lastScrapedSeason, lastScrapedEpisode)
                }
            }

            if (_isDownloadingSubs.value && !hasInstantCachedSubs) {
                when (prefs.getInt("auto_sub_wait_pref", 1)) {
                    0 -> {
                        cancelSubtitleDownloads()
                        resolveAndPlayInternal(sourceDataJson)
                    }
                    1 -> {
                        _showSubtitleWaitDialog.value = sourceDataJson
                    }
                    2 -> {
                        resolveAndPlayInternal(sourceDataJson)
                    }
                }
            } else {
                resolveAndPlayInternal(sourceDataJson)
            }
        }
    }

    fun resolveAndPlayInternal(sourceDataJson: String) {
        _isResolving.value = true
        _scrapeStatusMsg.value = "Resolving Link..."

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val py = Python.getInstance()
                val scraper = py.getModule("main")
                try { scraper.callAttr("pause_scrape") } catch (e: Exception) {}
                val resolveResult = scraper.callAttr("resolve", sourceDataJson).toString()

                withContext(Dispatchers.Main) {
                    _isResolving.value = false
                    try {
                        val json = JSONObject(resolveResult)
                        if (json.has("error")) {
                            resumeScrape()
                            _events.value = StreamsEvent.ShowToast("Resolve error: ${json.getString("error")}")
                            return@withContext
                        }

                        val streamUrl = json.optString("url")
                        val isVideo = json.optBoolean("is_video", false)

                        val parsedHeaders = mutableMapOf<String, String>()
                        val headersJson = json.optJSONObject("headers")
                        if (headersJson != null) {
                            val keys = headersJson.keys()
                            while (keys.hasNext()) {
                                val key = keys.next()
                                parsedHeaders[key] = headersJson.getString(key)
                            }
                        }

                        if (streamUrl.isNotEmpty() && (streamUrl.startsWith("http") || streamUrl.startsWith("file://"))) {
                            playStream(streamUrl, isVideo, sourceDataJson, parsedHeaders)
                        } else {
                            _isBufferingTorrent.value = false
                            resumeScrape()
                            _events.value = StreamsEvent.ShowToast("Could not resolve stream URL")
                        }
                    } catch (e: Exception) {
                        _isBufferingTorrent.value = false
                        resumeScrape()
                        _events.value = StreamsEvent.ShowToast("Resolve parsing error")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _isBufferingTorrent.value = false
                    resumeScrape()
                    _isResolving.value = false
                    _events.value = StreamsEvent.ShowToast("Resolve error: ${e.message}")
                }
            }
        }
    }

    fun resumeScrape() {
        if (isAutoplayStarting) {
            return
        }
        isInteractingWithSources = false

        val isAlreadyFinished = _scrapeTotal.value > 0 && _scrapeProgress.value >= _scrapeTotal.value
        if (isAlreadyFinished) {
            _isScraping.value = false
            _scrapeStatusMsg.value = "Finished! Found ${_scrapedSources.value?.length() ?: 0} sources."
            return
        }

        if (_isScraping.value || scrapeJob?.isActive == true) {
            _isScraping.value = true
            _scrapeStatusMsg.value = if (_scrapeTotal.value > 0) {
                "Scraping: ${_scrapeProgress.value} / ${_scrapeTotal.value} providers..."
            } else {
                "Resuming Scrape..."
            }
            viewModelScope.launch(Dispatchers.IO) {
                try { 
                    Python.getInstance().getModule("main").callAttr("resume_scrape") 
                } catch (e: Exception) {}
            }
        }
    }

    fun handleNextEpisodeAutoPlay(
        item: JSONObject, 
        season: Int, 
        episode: Int, 
        nextEpisodeJson: JSONObject? = null
    ) {
        isAutoplayStarting = true
        isPlayingFromSavedLink = false

        val targetSeason = nextEpisodeJson?.optInt("season_number")?.takeIf { it > 0 }
            ?: nextEpisodeJson?.optInt("season")?.takeIf { it > 0 }
            ?: season
        val targetEpisode = nextEpisodeJson?.optInt("episode_number")?.takeIf { it > 0 }
            ?: nextEpisodeJson?.optInt("number")?.takeIf { it > 0 }
            ?: (episode + 1)

        item.remove("still_path")
        _selectedItem.value?.remove("still_path")

        interceptedSubtitleUrls.clear()
        lastSubtitledItemKey = null
        _subStatusMsg.value = ""
        _isDownloadingSubs.value = false
        _showSubProgressBar.value = false
        lastScrapedSeason = targetSeason
        lastScrapedEpisode = targetEpisode

        stopScrape(triggerSubtitles = true)

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val autoplayMode = prefs.getString("autoplay_next_pref", "Closest Source") ?: "Closest Source"

                if (autoplayMode == "Ask") {
                    withContext(Dispatchers.Main) {
                        isAutoplayStarting = false
                        performScrape(item, targetSeason, targetEpisode)
                    }
                    return@launch
                }

                performAutoPlayScrape(item, targetSeason, targetEpisode, autoplayMode)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isAutoplayStarting = false
                    performScrape(item, targetSeason, targetEpisode)
                }
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun performAutoPlayScrape(item: JSONObject, season: Int, episode: Int, mode: String) {
        lastScrapedSeason = season
        lastScrapedEpisode = episode

        _isScraping.value = true
        _isResolving.value = false
        _scrapedSources.value = null
        _scrapeProgress.value = 0
        _scrapeTotal.value = 0
        _scrapeStatusMsg.value = "Starting AutoPlay Search for E$episode..."

        scrapePollingJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val py = Python.getInstance()
                val scraper = py.getModule("main")

                val finalSourcesDeferred = CompletableDeferred<JSONArray?>()

                launch {
                    try {
                        val sourcesJson = scraper.callAttr("scrape", item.toString(), season, episode).toString()
                        finalSourcesDeferred.complete(JSONArray(sourcesJson))
                    } catch (e: Exception) {
                        finalSourcesDeferred.complete(null)
                    }
                }

                var foundSource: JSONObject? = null
                val startTime = System.currentTimeMillis()

                val userTimeoutSec = prefs.getInt("global_timeout", 220)
                val timeout = userTimeoutSec * 1000L
                var frameCount = 0

                delay(2000)
                isAutoplayStarting = false

                while (foundSource == null && isActive && System.currentTimeMillis() - startTime < timeout) {
                    val statusStr = scraper.callAttr("get_scrape_status").toString()
                    val status = JSONObject(statusStr)
                    val current = status.optInt("current", 0)
                    val total = status.optInt("total", 0)
                    val sources = status.optJSONArray("sources")
                    val message = status.optString("message", "")
                    
                    frameCount++

                    val isNewScrapeStarted = total > 0 || frameCount > 8
                    val isFinished = message.startsWith("Finished") || 
                                     message.startsWith("Stopped") || 
                                     message.startsWith("Timeout") ||
                                     (total > 0 && current >= total) ||
                                     (total > 0 && message == "No active scrape" && frameCount > 30)

                    val sortedSources = if (isNewScrapeStarted && sources != null && sources.length() > 0) {
                        sortSources(sources)
                    } else null

                    withContext(Dispatchers.Main) {
                        if (total > 0) {
                            _scrapeProgress.value = current
                            _scrapeTotal.value = total
                        }
                        
                        _scrapeStatusMsg.value = when {
                            _isResolving.value -> "Resolving Link..."
                            message.startsWith("Paused") || message.startsWith("Pausing") -> "Scraping Paused: $current / $total providers..."
                            total > 0 && !isFinished -> "Scraping E$episode: $current / $total providers..."
                            else -> if (message.isNotEmpty() && message != "No active scrape") message else "Searching E$episode..."
                        }

                        if (sortedSources != null) {
                             _scrapedSources.value = sortedSources
                        }
                    }

                    if (isNewScrapeStarted && sources != null && sources.length() > 0) {
                        val rawSources = mutableListOf<JSONObject>()
                        for (i in 0 until sources.length()) {
                            rawSources.add(JSONObject(sources.getJSONObject(i).getString("source_data")))
                        }

                        if (mode == "Closest Source" && lastSelectedSource != null) {
                            val targetSource = lastSelectedSource!!.optString("source")
                            val targetProvider = lastSelectedSource!!.optString("provider")
                            val targetUrl = lastSelectedSource!!.optString("url")
                            val targetHost = if (targetUrl.contains("//")) targetUrl.split("//")[1].split("/")[0] else ""

                            foundSource = rawSources.find {
                                val u = it.optString("url")
                                val h = if (u.contains("//")) u.split("//")[1].split("/")[0] else ""
                                h == targetHost && it.optString("provider") == targetProvider && it.optString("source") == targetSource
                            } ?: rawSources.find {
                                it.optString("provider") == targetProvider && it.optString("source") == targetSource
                            } ?: rawSources.find {
                                it.optString("source") == targetSource
                            }
                        }

                        if (foundSource == null && (isFinished || mode == "Best Source")) {
                            val sorted = sortSources(sources)
                            if (sorted.length() > 0) {
                                foundSource = JSONObject(sorted.getJSONObject(0).getString("source_data"))
                            }
                        }
                    }

                    if (isFinished && foundSource == null) break
                    if (foundSource == null) delay(1500)
                }

                if (foundSource == null && !finalSourcesDeferred.isCompleted) {
                    withTimeoutOrNull(1500) {
                        finalSourcesDeferred.await()
                    }
                }

                if (foundSource == null && finalSourcesDeferred.isCompleted) {
                    val finalSources = finalSourcesDeferred.getCompleted()
                    if (finalSources != null && finalSources.length() > 0) {
                        val sorted = sortSources(finalSources)
                        foundSource = JSONObject(sorted.getJSONObject(0).getString("source_data"))
                    }
                }

                if (foundSource == null) {
                    val accumulated = _scrapedSources.value
                    if (accumulated != null && accumulated.length() > 0) {
                        foundSource = JSONObject(accumulated.getJSONObject(0).getString("source_data"))
                    }
                }

                withContext(Dispatchers.Main) {
                    if (foundSource != null) {
                        stopScrape(triggerSubtitles = true)
                        resolveAndPlay(foundSource.toString(), item)
                    } else {
                        _isScraping.value = false
                        performScrape(item, season, episode)
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                withContext(Dispatchers.Main) {
                    _isScraping.value = false
                    performScrape(item, season, episode)
                }
            }
        }
    }

    private fun purgeOldSubtitles() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val subRetentionDays = prefs.getInt("sub_retention_days", 3)
                if (subRetentionDays == 0) return@launch

                val subDir = File(context.filesDir, "userdata/subtitles")
                if (!subDir.exists()) return@launch

                val thresholdMs = System.currentTimeMillis() - (subRetentionDays * 24L * 60L * 60L * 1000L)
                val subFiles = subDir.listFiles() ?: return@launch
                
                var deletedCount = 0
                for (file in subFiles) {
                    if (file.isFile && (file.name.endsWith(".srt") || file.name.endsWith(".vtt") || file.name.endsWith(".ass"))) {
                        if (file.lastModified() < thresholdMs) {
                            if (file.delete()) {
                                deletedCount++
                            }
                        }
                    }
                }

                if (deletedCount > 0) {
                    val cacheFile = File(subDir, "cache_mapping.json")
                    if (cacheFile.exists()) {
                        try {
                            val json = JSONObject(cacheFile.readText())
                            val keys = json.keys()
                            val keysToRemove = mutableListOf<String>()
                            while (keys.hasNext()) {
                                val key = keys.next()
                                val array = json.getJSONArray(key)
                                val newArray = JSONArray()
                                for (i in 0 until array.length()) {
                                    val obj = array.getJSONObject(i)
                                    val path = android.net.Uri.parse(obj.getString("url")).path
                                    if (path != null && File(path).exists()) {
                                        newArray.put(obj)
                                    }
                                }
                                if (newArray.length() == 0) keysToRemove.add(key)
                                else json.put(key, newArray)
                            }
                            keysToRemove.forEach { json.remove(it) }
                            cacheFile.writeText(json.toString())
                        } catch (e: Exception) {
                            Log.e("TVBrowser", "Failed to clean subtitle cache mapping: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("TVBrowser", "Subtitle cleanup failed: ${e.message}")
            }
        }
    }

	private fun startBackgroundTraktCheck() {
		viewModelScope.launch(Dispatchers.IO) {
			delay(6000)
			while (isActive) {
				try {
					val favsJson = prefs.getString("streams_favorites", "[]") ?: "[]"
					if (favsJson != "[]") {
						val cacheFile = File(context.cacheDir, "last_episode_check.json")
						val lastCheckJson = if (cacheFile.exists()) cacheFile.readText() else "{}"
						val py = getPythonInstance()
						val checker = py.getModule("trakt.episode_check")
						val resultsStr = checker.callAttr("check_new_episodes", favsJson, lastCheckJson).toString()
						val resultObj = JSONObject(resultsStr)
						val newEpisodes = resultObj.getJSONArray("new_episodes")
						val updatedLastCheck = resultObj.getJSONObject("last_check")

						cacheFile.writeText(updatedLastCheck.toString())

						if (newEpisodes.length() > 0) {
							val enrichedList = mutableListOf<JSONObject>()
							for (i in 0 until newEpisodes.length()) {
								val ep = newEpisodes.getJSONObject(i)
								val showId = ep.optString("show_id", "")
								if (showId.isNotEmpty()) {
									try {
										py.getModule("trakt.episode_check").callAttr("invalidate_show_progress_cache", showId)
									} catch (e: Exception) {}
								}
								val enriched = enrichNotificationWithTmdb(ep)
								enrichedList.add(enriched)
							}
							
							withContext(Dispatchers.Main) {
								loadProgressCounts()
								
								val currentList = _activeNotifications.value.toMutableList()
								currentList.addAll(enrichedList)
								_activeNotifications.value = currentList
								saveNotifications(currentList)
								TvHomeSyncManager.syncNewEpisodesChannel(context, currentList)
							}
						}
					}
				} catch (e: Exception) {
					if (e is CancellationException) throw e
					Log.e("TVBrowser", "Trakt episode sync task error: ${e.message}")
				}
				delay(900000)
			}
		}
	}

    private fun getSubtitleCacheFile(): File = com.poobi.tvbrowser.shared.SubtitleCacheManager.getCacheFile(context)

    private fun loadSubtitlesFromCache(itemKey: String): List<SubtitleData> = 
        com.poobi.tvbrowser.shared.SubtitleCacheManager.loadSubtitles(context, itemKey)

    private fun saveSubtitlesToCache(itemKey: String, subs: List<SubtitleData>) = 
        com.poobi.tvbrowser.shared.SubtitleCacheManager.saveSubtitles(context, itemKey, subs)

    private fun removeSubtitlesFromCacheMap(itemKey: String) = 
        com.poobi.tvbrowser.shared.SubtitleCacheManager.removeSubtitles(context, itemKey)

    private fun sortSeasons(seasons: JSONArray): JSONArray {
        val list = mutableListOf<JSONObject>()
        for (i in 0 until seasons.length()) {
            list.add(seasons.getJSONObject(i))
        }
        list.sortWith { a, b ->
            val numA = a.optInt("season_number")
            val numB = b.optInt("season_number")
            when {
                numA == 0 && numB == 0 -> 0
                numA == 0 -> 1
                numB == 0 -> -1
                else -> numA.compareTo(numB)
            }
        }
        val result = JSONArray()
        list.forEach { result.put(it) }
        return result
    }

    fun markEpisodeAsWatchedLocal(season: Int, episode: Int) {
        val item = _selectedItem.value ?: return
        val id = item.optInt("id")
    
		viewModelScope.launch(Dispatchers.IO) {
			try {
				val py = getPythonInstance()
				py.getModule("trakt.episode_check").callAttr("invalidate_show_progress_cache", id)
				loadProgressCounts()
			} catch (e: Exception) {}
		}

        val cachedArray = com.poobi.tvbrowser.shared.AppCacheManager.getEpisodes(context, id.toString(), season)
        if (cachedArray != null) {
            for (i in 0 until cachedArray.length()) {
                val ep = cachedArray.getJSONObject(i)
                if (ep.optInt("episode_number") == episode) {
                    ep.put("is_watched", true)
                    break
                }
            }
            com.poobi.tvbrowser.shared.AppCacheManager.saveEpisodes(context, id.toString(), season, cachedArray)
        }

        val currentEpisodes = _itemEpisodes.value
        if (currentEpisodes != null) {
            val updatedArray = JSONArray(currentEpisodes.toString())
            var modified = false
            for (i in 0 until updatedArray.length()) {
                val ep = updatedArray.getJSONObject(i)
                if (ep.optInt("episode_number") == episode) {
                    ep.put("is_watched", true)
                    modified = true
                    break
                }
            }
            if (modified) {
                _itemEpisodes.value = updatedArray
            }
        }
    }

    fun playTrailer(item: JSONObject, season: Int? = null, episode: Int? = null) {
        commitPendingSearchQuery()
        val id = item.optString("id")
        val mediaType = item.optString("media_type").takeIf { it.isNotEmpty() && it != "null" }
            ?: if (item.has("name") || item.has("first_air_date")) "tv" else "movie"

        _isFetchingTrailer.value = true

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val py = Python.getInstance()
                val scraper = py.getModule("main")

                val resultStr = scraper.callAttr("get_trailer", mediaType, id, season, episode).toString()
                val json = JSONObject(resultStr)

                withContext(Dispatchers.Main) {
                    _isFetchingTrailer.value = false

                    if (json.has("error")) {
                        _events.value = StreamsEvent.ShowToast(json.getString("error"))
                        return@withContext
                    }

                    val url = json.getString("url")
                    val isVideo = json.getBoolean("is_video")

                    val titleRaw = item.optString("title").takeIf { it.isNotEmpty() } ?: item.optString("name", "Unknown")
                    val cleanTitle = titleRaw.replace(Regex("\\s\\(\\d{4}\\)$"), "")
                    val fullTitle = when {
                        season != null && episode != null -> "$cleanTitle S${season}E${episode} Trailer"
                        season != null -> "$cleanTitle S$season Trailer"
                        else -> "$cleanTitle Trailer"
                    }

                    _events.value = StreamsEvent.PlayVideo(
                        url = url,
                        title = fullTitle,
                        headers = emptyMap(),
                        subtitles = emptyMap(),
                        item = item,
                        season = null,
                        episode = null,
                        nextEpisode = null,
                        isWebpage = false,
                        isTrailer = true
                    )
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _isFetchingTrailer.value = false
                    _events.value = StreamsEvent.ShowToast("Trailer failed to load.")
                }
            }
        }
    }
}