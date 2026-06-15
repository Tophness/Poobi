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
import com.poobi.tvbrowser.shared.SubtitleData
import com.poobi.tvbrowser.torrent.TorrentSortCriteria
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
        val isWebpage: Boolean = false
    ) : StreamsEvent()
    data class ShowToast(val message: String) : StreamsEvent()
    data class ShowSubtitlePicker(val subs: JSONArray) : StreamsEvent()
    data class AskSubtitleWait(val sourceDataJson: String) : StreamsEvent()
    data class AddSubtitlesBatch(val subtitles: List<SubtitleData>) : StreamsEvent()
}

class StreamsViewModel(application: Application) : AndroidViewModel(application) {

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

    private val _activeCategoryIndex = MutableStateFlow(0)
    val activeCategoryIndex: StateFlow<Int> = _activeCategoryIndex.asStateFlow()

    val genreMap = mutableStateMapOf<Int, String>()

    private val _isScraping = MutableStateFlow(false)
    val isScraping: StateFlow<Boolean> = _isScraping.asStateFlow()

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

    var lastScrapedSeason: Int? = null
    var lastScrapedEpisode: Int? = null
    var lastSelectedSource: JSONObject? = null

    val interceptedSubtitleUrls = ConcurrentHashMap<String, Map<String, String>>()
    
    private val detailsCache = LruCache<String, JSONObject>(30)
    private val episodesCache = LruCache<String, JSONArray>(50)
    private val searchCache = LruCache<String, JSONArray>(15)
    private val libraryCache = LruCache<String, JSONArray>(15)

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

    init {
        loadSearchHistory()
        refreshFavoritesSet()
        loadGenres()
        purgeOldSubtitles()
        startBackgroundTraktCheck()
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

                server.prepareTorrent(
                    infoHash = infoHash,
                    fileIdx = fileIdx,
                    prebufferPiecesLimit = prebufferLimit,
                    onStatusUpdate = { status, progress, seeders ->
                        _torrentBufferStatus.value = status
                        _torrentBufferProgress.value = progress
                        _torrentBufferSeeders.value = seeders
                    },
                    onReady = {
                        viewModelScope.launch(Dispatchers.Main) {
                            _isBufferingTorrent.value = false
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

    fun setActiveCategoryIndex(index: Int) {
        _activeCategoryIndex.value = index
    }

    fun consumeEvent() { _events.value = null }

    fun clearScrapedSources() {
        stopScrape()
        _scrapedSources.value = null
        _torrentioSources.value = null
        _isScrapingTorrents.value = false
    }

    fun clearSelectedMedia() { _selectedItem.value = null }
    fun clearSearchResults() { _searchResults.value = null }
    private suspend fun getPythonInstance(): Python {
        while (!Python.isStarted()) {
            delay(100)
        }
        return Python.getInstance()
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

    private fun loadGenres() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                delay(3000)
                val py = getPythonInstance()
                val tmdb = py.getModule("tmdb.tmdb_utils")
                val movieGenres = JSONObject(tmdb.callAttr("get_genres", "movie").toString()).getJSONArray("genres")
                val tvGenres = JSONObject(tmdb.callAttr("get_genres", "tv").toString()).getJSONArray("genres")
                withContext(Dispatchers.Main) {
                    for (i in 0 until movieGenres.length()) {
                        val g = movieGenres.getJSONObject(i)
                        genreMap[g.getInt("id")] = g.getString("name")
                    }
                    for (i in 0 until tvGenres.length()) {
                        val g = tvGenres.getJSONObject(i)
                        genreMap[g.getInt("id")] = g.getString("name")
                    }
                }
            } catch (e: Exception) {}
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

    fun clearSearchHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            prefs.edit().putString("streams_search_history", "[]").apply()
            withContext(Dispatchers.Main) {
                _searchHistory.value = emptyList()
            }
        }
    }

    fun performSearch(query: String) {
        if (query.isBlank()) return
        addToSearchHistory(query)
        
        val cached = searchCache.get(query)
        if (cached != null) {
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
                val resultsJson = scraper.callAttr("search", query).toString()
                val results = JSONArray(resultsJson)
                searchCache.put(query, results)
                withContext(Dispatchers.Main) {
                    _searchResults.value = results
                    _isScraping.value = false
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _events.value = StreamsEvent.ShowToast("Search error: ${e.message}")
                    _isScraping.value = false
                    _searchResults.value = JSONArray()
                }
            }
        }
    }

    private fun cancelLibraryJobs() {
        libraryLoadJob?.cancel()
        _libraryItems.value = null
    }

    fun loadLibraryCategory(title: String, method: String, arg: String? = null) {
        val cacheKey = "${method}_${arg ?: ""}"
        val cached = libraryCache.get(cacheKey)
        if (cached != null) {
            cancelLibraryJobs()
            _libraryItems.value = cached
            return
        }

        cancelLibraryJobs()
        libraryLoadJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                delay(150)
                if (!isActive) return@launch

                val py = Python.getInstance()
                val tmdb = py.getModule("tmdb.tmdb_utils")
                val resultJson = if (arg != null) {
                    tmdb.callAttr(method, arg).toString()
                } else {
                    tmdb.callAttr(method).toString()
                }
                val results = JSONObject(resultJson).optJSONArray("results")
                if (results != null) {
                    libraryCache.put(cacheKey, results)
                    withContext(Dispatchers.Main) { 
                        _libraryItems.value = results 
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
            }
        }
    }

    fun loadFavorites() { 
        cancelLibraryJobs()
        viewModelScope.launch(Dispatchers.IO) {
            val list = JSONArray(prefs.getString("streams_favorites", "[]") ?: "[]")
            withContext(Dispatchers.Main) {
                _libraryItems.value = list
            }
        }
    }
    
    fun loadRecentlyPlayed() { 
        cancelLibraryJobs()
        viewModelScope.launch(Dispatchers.IO) {
            val list = JSONArray(prefs.getString("streams_recently_played", "[]") ?: "[]")
            withContext(Dispatchers.Main) {
                _libraryItems.value = list
            }
        }
    }
    
    fun removeFromRecentlyPlayed(index: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val recentJson = prefs.getString("streams_recently_played", "[]") ?: "[]"
            val array = JSONArray(recentJson)
            if (index >= 0 && index < array.length()) {
                val entry = array.getJSONObject(index)
                val displayTitle = entry.optString("display_title")
                
                val subtitlesArr = entry.optJSONArray("subtitles")
                if (subtitlesArr != null) {
                    for (i in 0 until subtitlesArr.length()) {
                        try {
                            val subObj = subtitlesArr.getJSONObject(i)
                            val subUrl = subObj.optString("url")
                            if (subUrl.isNotEmpty()) {
                                val uri = android.net.Uri.parse(subUrl)
                                if (uri.scheme == "file") {
                                    val path = uri.path
                                    if (path != null) {
                                        val file = File(path)
                                        if (file.exists() && file.isFile) {
                                            file.delete()
                                        }
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("StreamsViewModel", "Failed to delete subtitle file: ${e.message}")
                        }
                    }
                }

                val item = entry.optJSONObject("item")
                if (item != null) {
                    val itemId = item.optInt("id")
                    val season = if (entry.has("season")) entry.getInt("season") else 0
                    val episode = if (entry.has("episode")) entry.getInt("episode") else 0
                    val itemKey = "${itemId}_${season}_${episode}"
                    removeSubtitlesFromCacheMap(itemKey)
                }
                
                val newList = JSONArray()
                for (i in 0 until array.length()) {
                    if (i != index) newList.put(array.get(i))
                }
                prefs.edit().putString("streams_recently_played", newList.toString()).apply()
                
                if (displayTitle.isNotEmpty()) {
                    prefs.edit().remove("resume_stream_$displayTitle").apply()
                }
                
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
        if (subtitles.isNotEmpty()) {
            val copiedSubtitles = subtitles.toMap()
            interceptedSubtitleUrls.clear()
            interceptedSubtitleUrls.putAll(copiedSubtitles)
        }

        addToRecentlyPlayed(displayTitle, item, season, episode, url, headers)

        val autoSubMode = prefs.getInt("auto_sub_pref", 0)
        if (autoSubMode == 0) {
            fetchManualSubtitles(item, season, episode)
        } else if (autoSubMode == 1 && interceptedSubtitleUrls.isEmpty()) {
            performAutoSubtitleSearch(item, season, episode)
        }
    }

    private fun addToRecentlyPlayed(
        displayTitle: String, 
        item: JSONObject, 
        season: Int?, 
        episode: Int?, 
        videoUrl: String? = null, 
        headers: Map<String, String>? = null
    ) {
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

            val newEntry = JSONObject().apply {
                put("display_title", displayTitle)
                put("item", item)
                if (season != null) put("season", season)
                if (episode != null) put("episode", episode)

                val finalUrl = videoUrl ?: existing?.optString("video_url")
                if (!finalUrl.isNullOrEmpty()) put("video_url", finalUrl)

                val finalHeaders = if (headers != null) JSONObject(headers) else existing?.optJSONObject("headers")
                if (finalHeaders != null) put("headers", finalHeaders)

                if (interceptedSubtitleUrls.isNotEmpty()) {
                    val subsArray = JSONArray()
                    interceptedSubtitleUrls.forEach { (url, info) ->
                        val subObj = JSONObject()
                        subObj.put("url", url)
                        val infoObj = JSONObject()
                        info.forEach { (k, v) -> infoObj.put(k, v) }
                        subObj.put("info", infoObj)
                        subsArray.put(subObj)
                    }
                    put("subtitles", subsArray)
                } else if (existing?.has("subtitles") == true) {
                    put("subtitles", existing.getJSONArray("subtitles"))
                }
            }
            newList.add(0, newEntry)
            if (newList.size > 20) newList.removeAt(newList.size - 1)

            prefs.edit().putString("streams_recently_played", newList.toString()).apply()
        }
    }

    fun selectRecentlyPlayedItem(entry: JSONObject) {
        val item = entry.getJSONObject("item")
        val season = if (entry.has("season")) entry.getInt("season") else null
        val episode = if (entry.has("episode")) entry.getInt("episode") else null
        val videoUrl = entry.optString("video_url")
        val title = entry.optString("display_title")

        lastScrapedSeason = season
        lastScrapedEpisode = episode

        selectMediaItem(item, initialSeason = season)

        if (videoUrl.isNotEmpty()) {
            val headersMap = mutableMapOf<String, String>()
            entry.optJSONObject("headers")?.let { headers ->
                headers.keys().forEach { headersMap[it] = headers.getString(it) }
            }
            
            val subtitleUrls = mutableMapOf<String, Map<String, String>>()
            entry.optJSONArray("subtitles")?.let { subs ->
                for (i in 0 until subs.length()) {
                    val sub = subs.getJSONObject(i)
                    val url = sub.getString("url")
                    val info = sub.getJSONObject("info")
                    val infoMap = mutableMapOf<String, String>()
                    info.keys().forEach { infoMap[it] = info.getString(it) }
                    subtitleUrls[url] = infoMap
                }
            }
            
            interceptedSubtitleUrls.clear()
            interceptedSubtitleUrls.putAll(subtitleUrls)

            isPlayingFromSavedLink = true

            _events.value = StreamsEvent.PlayVideo(
                url = videoUrl,
                title = title,
                headers = headersMap,
                subtitles = subtitleUrls.toMap(),
                item = item,
                season = season,
                episode = episode,
                nextEpisode = null 
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
                array.put(item)
                _events.value = StreamsEvent.ShowToast("Added to Favorites")
            }
            prefs.edit().putString("streams_favorites", array.toString()).apply()
            refreshFavoritesSet()

            if (isCurrentlyViewingFavorites) {
                withContext(Dispatchers.Main) {
                    loadFavorites()
                }
            }
        }
    }

    fun selectMediaItem(item: JSONObject, initialSeason: Int? = null) {
        _selectedItem.value = item
        _itemDetails.value = null
        _itemEpisodes.value = null
        _itemSeasons.value = null
        _lastWatchedEpisode.value = null
        _isMovieWatched.value = false
        
        val id = item.optInt("id")
        val mediaType = item.optString("media_type").takeIf { it.isNotEmpty() && it != "null" }
            ?: if (item.has("name") || item.has("first_air_date")) "tv" else "movie"

        if (mediaType == "movie") {
            checkMovieWatchedStatus(item)
        }

        val cacheKey = "${mediaType}_$id"
        val cached = detailsCache.get(cacheKey)
        if (cached != null) {
            _itemDetails.value = cached
            _itemRecommendations.value = cached.optJSONObject("recommendations")?.optJSONArray("results")
            _itemCast.value = cached.optJSONObject("credits")?.optJSONArray("cast")

            if (mediaType == "tv") {
                val seasons = cached.optJSONArray("seasons")
                val sortedSeasons = if (seasons != null) sortSeasons(seasons) else null
                _itemSeasons.value = sortedSeasons
                if (sortedSeasons != null && sortedSeasons.length() > 0) {
                    val seasonToLoad = initialSeason ?: getLastWatchedSeason(item) ?: sortedSeasons.getJSONObject(0).optInt("season_number")
                    loadEpisodes(item, seasonToLoad, isAutoSelect = true)
                }
            }
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val py = Python.getInstance()
                val tmdb = py.getModule("tmdb.tmdb_utils")
                val detailsJson = tmdb.callAttr("get_details", id, mediaType).toString()
                val details = JSONObject(detailsJson)
                
                if (mediaType == "tv") {
                    val seasons = details.optJSONArray("seasons")
                    if (seasons != null) details.put("seasons", sortSeasons(seasons))
                }
                
                detailsCache.put(cacheKey, details)

                withContext(Dispatchers.Main) {
                    _itemDetails.value = details
                    _itemRecommendations.value = details.optJSONObject("recommendations")?.optJSONArray("results")
                    _itemCast.value = details.optJSONObject("credits")?.optJSONArray("cast")

                    if (mediaType == "tv") {
                        val seasons = details.optJSONArray("seasons")
                        _itemSeasons.value = seasons
                        if (seasons != null && seasons.length() > 0) {
                            val seasonToLoad = initialSeason ?: getLastWatchedSeason(item) ?: seasons.getJSONObject(0).optInt("season_number")
                            loadEpisodes(item, seasonToLoad, isAutoSelect = true)
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
                        _events.value = StreamsEvent.ShowToast(if (currentStatus) "Marked as unwatched" else "Marked as watched")
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
                        _events.value = StreamsEvent.ShowToast(if (isCurrentlyWatched) "Episode marked unwatched" else "Episode marked watched")
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

        val cacheKey = "${id}_$season"
        val cachedArray = episodesCache.get(cacheKey)
        if (cachedArray != null) {
            for (i in 0 until cachedArray.length()) {
                val ep = cachedArray.getJSONObject(i)
                if (ep.optInt("episode_number") == episode) {
                    ep.put("is_watched", newWatchedState)
                    break
                }
            }
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

    fun selectCastMember(personId: Int, personName: String) {
        _scrapeStatusMsg.value = "Loading $personName's works..."
        _isScraping.value = true
        _searchResults.value = null
        _selectedItem.value = null
        _activeCategoryIndex.value = 0

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val py = Python.getInstance()
                val tmdb = py.getModule("tmdb.tmdb_utils")
                val detailsJson = tmdb.callAttr("get_person_details", personId).toString()
                val details = JSONObject(detailsJson)
                val combinedCredits = details.optJSONObject("combined_credits")
                val castArray = combinedCredits?.optJSONArray("cast") ?: JSONArray()
                
                val formattedResults = JSONArray()
                for (i in 0 until castArray.length()) {
                    val credit = castArray.getJSONObject(i)
                    val mediaType = credit.optString("media_type")
                    if (mediaType == "movie" || mediaType == "tv") {
                        val title = credit.optString("title").takeIf { it.isNotEmpty() } ?: credit.optString("name", "")
                        val releaseDate = credit.optString("release_date").takeIf { !it.isNullOrEmpty() } ?: credit.optString("first_air_date") ?: "0000"
                        val year = if (releaseDate.length >= 4) releaseDate.substring(0, 4) else "0000"
                        
                        val formatted = JSONObject().apply {
                            put("title", "$title ($year)")
                            put("id", credit.optInt("id"))
                            put("media_type", mediaType)
                            put("release_date", releaseDate)
                            put("overview", credit.optString("overview"))
                            put("poster_path", credit.optString("poster_path"))
                            put("backdrop_path", credit.optString("backdrop_path"))
                            put("vote_average", credit.optDouble("vote_average", 0.0))
                            put("genre_ids", credit.optJSONArray("genre_ids") ?: JSONArray())
                            put("orig_title", title)
                            put("year", year)
                        }
                        formattedResults.put(formatted)
                    }
                }

                withContext(Dispatchers.Main) {
                    _searchResults.value = formattedResults
                    _isScraping.value = false
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _isScraping.value = false
                    _searchResults.value = JSONArray()
                    _events.value = StreamsEvent.ShowToast("Failed to load credits: ${e.message}")
                }
            }
        }
    }

    fun loadEpisodes(item: JSONObject, seasonNumber: Int, isAutoSelect: Boolean = false) {
        val id = item.optInt("id")
        _itemEpisodes.value = null

        if (isAutoSelect) {
            val lastEp = getLastWatchedEpisode(item, seasonNumber)
            _lastWatchedEpisode.value = if (lastEp != null) lastEp + 1 else null
        } else {
            _lastWatchedEpisode.value = null
        }

        val cacheKey = "${id}_$seasonNumber"
        val cached = episodesCache.get(cacheKey)
        if (cached != null) {
            _itemEpisodes.value = cached
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val py = Python.getInstance()
                val scraper = py.getModule("main")
                val episodesJson = scraper.callAttr("get_tv_episodes", id, seasonNumber).toString()
                val episodes = JSONArray(episodesJson)
                episodesCache.put(cacheKey, episodes)
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
                if (streamUrl.isEmpty() || !streamUrl.startsWith("http")) {
                    withContext(Dispatchers.Main) { tryNextSource() }
                    return@launch
                }

                val headersMap = mutableMapOf<String, String>()
                try {
                    val sourceData = JSONObject(sourceDataJson)
                    val hObj = sourceData.optJSONObject("headers")
                    hObj?.keys()?.forEach { headersMap[it] = hObj.getString(it) }
                } catch (e: Exception) {}

                if (checkUrlValidity(streamUrl, headersMap)) {
                    withContext(Dispatchers.Main) {
                        _isResolving.value = false
                        _scrapeStatusMsg.value = "Valid source found! Playing..."
                        playStream(streamUrl, json.optBoolean("is_video", false), sourceDataJson)
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
        try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "HEAD"
            headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            
            val responseCode = connection.responseCode
            val contentType = connection.contentType ?: ""

            if (responseCode in 200..299) {
                if (contentType.contains("mpegurl", ignoreCase = true) || url.contains(".m3u8", ignoreCase = true)) {
                    return@withContext verifyM3u8Content(url, headers)
                }

                val isValid = contentType.contains("video", ignoreCase = true) ||
                        contentType.contains("mp4", ignoreCase = true) ||
                        contentType.contains("octet-stream", ignoreCase = true)

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
                        val buffer = ByteArray(32)
                        val read = input.read(buffer)
                        if (read >= 4) {
                            if (buffer[0] == 0x89.toByte() && buffer[1] == 0x50.toByte() && 
                                buffer[2] == 0x4E.toByte() && buffer[3] == 0x47.toByte()) {
                                return@withContext false
                            }
                            
                            val head = String(buffer, 0, read)
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

    private fun playStream(streamUrl: String, isVideo: Boolean, sourceDataJson: String) {
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

        var nextEp: JSONObject? = null
        if (lastScrapedSeason != null && lastScrapedEpisode != null) {
            val currentEp = lastScrapedEpisode!!
            val item = _selectedItem.value
            if (item != null) {
                val cacheKey = "${item.optInt("id")}_$lastScrapedSeason"
                val episodes = episodesCache.get(cacheKey)
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

        _events.value = StreamsEvent.PlayVideo(
            url = streamUrl,
            title = fullTitle,
            headers = headersMap,
            subtitles = interceptedSubtitleUrls.toMap(),
            item = _selectedItem.value!!,
            season = lastScrapedSeason,
            episode = lastScrapedEpisode,
            nextEpisode = nextEp,
            isWebpage = !isVideo
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
        val count = sources?.length() ?: 0
        _scrapeStatusMsg.value = "Finished! Found $count sources."
        _scrapeProgress.value = _scrapeTotal.value
        
        _scrapedSources.value = if (sources != null && sources.length() > 0) sortSources(sources) else JSONArray()
        
        if (prefs.getInt("auto_sub_pref", 0) == 1) { 
            performAutoSubtitleSearch(item, season, episode)
        }
    }

    fun performScrape(item: JSONObject, season: Int? = null, episode: Int? = null) {
        isPlayingFromSavedLink = false
        stopScrape()
        
        _selectedItem.value = item
        lastScrapedSeason = season
        lastScrapedEpisode = episode
        interceptedSubtitleUrls.clear()
        lastSubtitledItemKey = null
        _subStatusMsg.value = ""

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
                        val obj = JSONObject()
                        val source = r.optString("source", "Unknown")
                        val quality = r.optString("quality", "SD")
                        val provider = r.optString("provider", "Torrentio")
                        val torrentTitle = r.optString("title", "")
                        
                        val displayTitle = if (torrentTitle.isNotEmpty()) {
                            torrentTitle
                        } else {
                            "[$quality] $source ($provider)"
                        }
                        
                        obj.put("title", displayTitle)
                        obj.put("source_data", r.toString())
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
                    val sortedSources = if (sources != null && !isInteractingWithSources) sortSources(sources) else null

                    withContext(Dispatchers.Main) {
                        if (total > 0) {
                            _scrapeProgress.value = current
                            _scrapeTotal.value = total
                        }
                        
                        _scrapeStatusMsg.value = when {
                            _isResolving.value -> "Resolving Link..."
                            isInteractingWithSources -> "Pausing Scrapers..."
                            message.startsWith("Paused") -> "Scraping Paused: $current / $total providers..."
                            total > 0 && current < total -> "Scraping: $current / $total providers..."
                            else -> message
                        }

                        if (sortedSources != null) _scrapedSources.value = sortedSources
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
            var count = 0
            val newlyDownloadedSubs = mutableListOf<SubtitleData>()
            try {
                val py = Python.getInstance()
                val scraper = py.getModule("main")
                for (sub in subs) {
                    if (!_isDownloadingSubs.value) break
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
                            
                            try {
                                originalFile.copyTo(uniqueFile, overwrite = true)
                                val subUri = android.net.Uri.fromFile(uniqueFile).toString()
                                val source = sub.optString("service", "Unknown")
                                val name = sub.optString("name", "Subtitle")
                                val label = "[$source] $name"
                                val lang = sub.optString("lang", "und")

                                interceptedSubtitleUrls[subUri] = mapOf("label" to label, "lang" to lang)
                                newlyDownloadedSubs.add(SubtitleData(subUri, label, lang))
                                count++

                                withContext(Dispatchers.Main) {
                                    _subProgress.value = count.toFloat() / subs.size.toFloat()
                                    _subStatusMsg.value = "Downloading subtitles: $count / ${subs.size}"
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
                    if (newlyDownloadedSubs.isNotEmpty()) {
                        _events.value = StreamsEvent.AddSubtitlesBatch(newlyDownloadedSubs)
                    }
                    if (!silent) _events.value = StreamsEvent.ShowToast("Finished downloading $count subtitles")
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
    }

    private fun performAutoSubtitleSearch(item: JSONObject, season: Int?, episode: Int?) {
        val itemKey = "${item.optInt("id")}_${season ?: 0}_${episode ?: 0}"
        if (_isDownloadingSubs.value || lastSubtitledItemKey == itemKey) return
        
        val cached = loadSubtitlesFromCache(itemKey)
        if (cached.isNotEmpty()) {
            lastSubtitledItemKey = itemKey
            _subStatusMsg.value = "Using cached subtitles..."
            _isDownloadingSubs.value = true
            _showSubProgressBar.value = false
            _subProgress.value = 1.0f
            
            cached.forEach { sub ->
                interceptedSubtitleUrls[sub.url] = mapOf("label" to sub.label, "lang" to sub.lang)
            }
            
            viewModelScope.launch(Dispatchers.Main) {
                _events.value = StreamsEvent.AddSubtitlesBatch(cached)
                
                pendingPlayVideoSourceData?.let { pendingSource ->
                    resolveAndPlayInternal(pendingSource)
                    pendingPlayVideoSourceData = null
                }
                
                delay(3000)
                _isDownloadingSubs.value = false
            }
            return
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
                    val countPref = prefs.getInt("auto_sub_count", 1)
                    val countToGet = if (countPref == 0) subs.length() else countPref.coerceAtMost(subs.length())
                    
                    val prioritizedSubs = mutableListOf<JSONObject>()
                    for (i in 0 until subs.length()) {
                        val sub = subs.getJSONObject(i)
                        if (sub.optString("sync") == "true") prioritizedSubs.add(sub)
                    }
                    for (i in 0 until subs.length()) {
                        val sub = subs.getJSONObject(i)
                        if (sub.optString("sync") != "true") prioritizedSubs.add(sub)
                    }

                    var downloadedCount = 0
                    val newlyDownloadedSubs = mutableListOf<SubtitleData>()

                    withContext(Dispatchers.Main) {
                        _subStatusMsg.value = "Downloading subtitles: 0 / $countToGet"
                        _subProgress.value = 0f
                    }

                    for (i in 0 until prioritizedSubs.size) {
                        if (downloadedCount >= countToGet || !_isDownloadingSubs.value) break
                        
                        val sub = prioritizedSubs[i]
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
                                
                                try {
                                    originalFile.copyTo(uniqueFile, overwrite = true)
                                    val subUri = android.net.Uri.fromFile(uniqueFile).toString()
                                    val source = sub.optString("service", "Unknown")
                                    val name = sub.optString("name", "Subtitle")
                                    val label = "[$source] $name"
                                    val lang = sub.optString("lang", "und")

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
                        
                        if (newlyDownloadedSubs.isNotEmpty()) {
                            saveSubtitlesToCache(itemKey, newlyDownloadedSubs)
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
                    pendingPlayVideoSourceData?.let { pendingSource ->
                        resolveAndPlayInternal(pendingSource)
                        pendingPlayVideoSourceData = null
                    }
                }
            }
        }
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

    fun resolveAndPlay(sourceDataJson: String, rawItem: JSONObject) {
        stopTryAll()
        isPlayingFromSavedLink = false
        lastSelectedSource = try { JSONObject(sourceDataJson) } catch (e: Exception) { rawItem }
        isInteractingWithSources = true
        _scrapeStatusMsg.value = "Pausing Scrapers..."

        val sourceData = JSONObject(sourceDataJson)
        val infoHash = sourceData.optString("infoHash", "")
        val fileIdx = sourceData.optInt("fileIdx", -1)

        if (infoHash.isNotEmpty() && fileIdx != -1) {
            performTorrentPreBuffering(infoHash, fileIdx, sourceDataJson)
        } else {
            if (_isDownloadingSubs.value) {
                when (prefs.getInt("auto_sub_wait_pref", 0)) {
                    0 -> {
                        cancelSubtitleDownloads()
                        resolveAndPlayInternal(sourceDataJson)
                    }
                    1 -> {
                        _events.value = StreamsEvent.AskSubtitleWait(sourceDataJson)
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
        _isScraping.value = true
        _isResolving.value = true
        _scrapeStatusMsg.value = "Resolving Link..."

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val py = Python.getInstance()
                val scraper = py.getModule("main")
                try { scraper.callAttr("pause_scrape") } catch (e: Exception) {}
                val resolveResult = scraper.callAttr("resolve", sourceDataJson).toString()

                withContext(Dispatchers.Main) {
                    _isScraping.value = false
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
                        
                        if (streamUrl.isNotEmpty() && streamUrl.startsWith("http")) {
                            playStream(streamUrl, isVideo, sourceDataJson)
                        } else {
                            resumeScrape()
                            _events.value = StreamsEvent.ShowToast("Could not resolve stream URL")
                        }
                    } catch (e: Exception) {
                        resumeScrape()
                        _events.value = StreamsEvent.ShowToast("Resolve parsing error")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    resumeScrape()
                    _isScraping.value = false
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

        if (_isScraping.value) {
            _scrapeStatusMsg.value = "Resuming Scrape..."
            viewModelScope.launch(Dispatchers.IO) {
                try { 
                    Python.getInstance().getModule("main").callAttr("resume_scrape") 
                } catch (e: Exception) {}
            }
        }
    }

    fun handleNextEpisodeAutoPlay(item: JSONObject, season: Int, episode: Int) {
        isAutoplayStarting = true
        stopScrape()
        val nextEp = episode + 1

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val autoplayMode = prefs.getString("autoplay_next_pref", "Closest Source") ?: "Closest Source"

                if (autoplayMode == "Ask") {
                    withContext(Dispatchers.Main) {
                        isAutoplayStarting = false
                        performScrape(item, season, nextEp)
                    }
                    return@launch
                }

                performAutoPlayScrape(item, season, nextEp, autoplayMode)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isAutoplayStarting = false
                    performScrape(item, season, nextEp)
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
                val timeout = 45000L
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
                                     (message == "No active scrape" && frameCount > 15)

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

                if (foundSource == null && finalSourcesDeferred.isCompleted) {
                    val finalSources = finalSourcesDeferred.getCompleted()
                    if (finalSources != null && finalSources.length() > 0) {
                        val sorted = sortSources(finalSources)
                        foundSource = JSONObject(sorted.getJSONObject(0).getString("source_data"))
                    }
                }

                withContext(Dispatchers.Main) {
                    if (foundSource != null) {
                        stopScrape()
                        resolveAndPlayInternal(foundSource.toString())
                    } else {
                        _isScraping.value = false
                        performScrape(item, season, episode)
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                withContext(Dispatchers.Main) {
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
                        val lastCheckJson = prefs.getString("last_episode_check", "{}") ?: "{}"
                        val py = getPythonInstance()
                        val checker = py.getModule("trakt.episode_check")
                        val resultsStr = checker.callAttr("check_new_episodes", favsJson, lastCheckJson).toString()
                        
                        val resultObj = JSONObject(resultsStr)
                        val newEpisodes = resultObj.getJSONArray("new_episodes")
                        val updatedLastCheck = resultObj.getJSONObject("last_check")

                        prefs.edit().putString("last_episode_check", updatedLastCheck.toString()).apply()

                        if (newEpisodes.length() > 0) {
                            withContext(Dispatchers.Main) {
                                val epCounts = JSONObject(prefs.getString("new_episode_counts", "{}") ?: "{}")
                                for (i in 0 until newEpisodes.length()) {
                                    val ep = newEpisodes.getJSONObject(i)
                                    val showId = ep.getString("show_id")
                                    epCounts.put(showId, epCounts.optInt(showId, 0) + 1)
                                }
                                prefs.edit().putString("new_episode_counts", epCounts.toString()).apply()
                                _events.value = StreamsEvent.ShowToast("${newEpisodes.length()} New Episodes Alert!")
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

    private fun getLastWatchedSeason(item: JSONObject): Int? {
        val id = item.optString("id")
        val imdb = item.optString("imdb")
        val recentJson = prefs.getString("streams_recently_played", "[]") ?: "[]"
        val array = JSONArray(recentJson)
        for (i in 0 until array.length()) {
            val entry = array.getJSONObject(i)
            val itItem = entry.optJSONObject("item")
            if (itItem?.optString("id") == id || (imdb.isNotEmpty() && itItem?.optString("imdb") == imdb)) {
                if (entry.has("season")) return entry.getInt("season")
            }
        }
        return null
    }

    private fun getLastWatchedEpisode(item: JSONObject, season: Int): Int? {
        val id = item.optString("id")
        val imdb = item.optString("imdb")
        val recentJson = prefs.getString("streams_recently_played", "[]") ?: "[]"
        val array = JSONArray(recentJson)
        for (i in 0 until array.length()) {
            val entry = array.getJSONObject(i)
            val itItem = entry.optJSONObject("item")
            if (itItem?.optString("id") == id || (imdb.isNotEmpty() && itItem?.optString("imdb") == imdb)) {
                if (entry.has("season") && entry.getInt("season") == season) {
                    if (entry.has("episode")) return entry.getInt("episode")
                }
            }
        }
        return null
    }

    private fun getSubtitleCacheFile(): File {
        val dir = File(context.filesDir, "userdata/subtitles")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "cache_mapping.json")
    }

    private fun loadSubtitlesFromCache(itemKey: String): List<SubtitleData> {
        val file = getSubtitleCacheFile()
        if (!file.exists()) return emptyList()
        try {
            val json = JSONObject(file.readText())
            if (json.has(itemKey)) {
                val array = json.getJSONArray(itemKey)
                val results = mutableListOf<SubtitleData>()
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val filePath = android.net.Uri.parse(obj.getString("url")).path
                    if (filePath != null && File(filePath).exists()) {
                        results.add(SubtitleData(
                            obj.getString("url"),
                            obj.getString("label"),
                            obj.getString("lang")
                        ))
                    }
                }
                return results
            }
        } catch (e: Exception) {
            Log.e("StreamsViewModel", "Failed to load subtitle cache: ${e.message}")
        }
        return emptyList()
    }

    private fun saveSubtitlesToCache(itemKey: String, subs: List<SubtitleData>) {
        val file = getSubtitleCacheFile()
        try {
            val json = if (file.exists()) JSONObject(file.readText()) else JSONObject()
            val array = JSONArray()
            subs.forEach { sub ->
                array.put(JSONObject().apply {
                    put("url", sub.url)
                    put("label", sub.label)
                    put("lang", sub.lang)
                })
            }
            json.put(itemKey, array)
            file.writeText(json.toString())
        } catch (e: Exception) {
            Log.e("StreamsViewModel", "Failed to save subtitle cache: ${e.message}")
        }
    }

    private fun removeSubtitlesFromCacheMap(itemKey: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val file = getSubtitleCacheFile()
            if (!file.exists()) return@launch
            try {
                val json = JSONObject(file.readText())
                if (json.has(itemKey)) {
                    json.remove(itemKey)
                    file.writeText(json.toString())
                }
            } catch (e: Exception) {
                Log.e("StreamsViewModel", "Failed to remove subtitles from cache mapping: ${e.message}")
            }
        }
    }

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

        val cacheKey = "${id}_$season"
        val cachedArray = episodesCache.get(cacheKey)
        if (cachedArray != null) {
            for (i in 0 until cachedArray.length()) {
                val ep = cachedArray.getJSONObject(i)
                if (ep.optInt("episode_number") == episode) {
                    ep.put("is_watched", true)
                    break
                }
            }
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
        val id = item.optString("id")
        val mediaType = item.optString("media_type").takeIf { it.isNotEmpty() && it != "null" }
            ?: if (item.has("name") || item.has("first_air_date")) "tv" else "movie"

        _scrapeStatusMsg.value = "Fetching trailer..."
        _isScraping.value = true
        _isResolving.value = true

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val py = Python.getInstance()
                val scraper = py.getModule("main")
                
                val resultStr = scraper.callAttr("get_trailer", mediaType, id, season, episode).toString()
                val json = JSONObject(resultStr)

                withContext(Dispatchers.Main) {
                    _isScraping.value = false
                    _isResolving.value = false

                    if (json.has("error")) {
                        _events.value = StreamsEvent.ShowToast(json.getString("error"))
                        return@withContext
                    }

                    val url = json.getString("url")
                    val isVideo = json.getBoolean("is_video")

                    val titleRaw = item.optString("title").takeIf { it.isNotEmpty() } ?: item.optString("name", "Unknown")
                    val cleanTitle = titleRaw.replace(Regex("\\s\\(\\d{4}\\)$"), "")
                    val fullTitle = when {
                        season != null && episode != null -> "$cleanTitle S${season}E$episode Trailer"
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
                        isWebpage = false
                    )
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _isScraping.value = false
                    _isResolving.value = false
                    _events.value = StreamsEvent.ShowToast("Trailer failed to load.")
                }
            }
        }
    }
}