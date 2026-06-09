package com.poobi.tvbrowser.ui.streams

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.compose.runtime.mutableStateMapOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.chaquo.python.Python
import com.poobi.tvbrowser.SortCriteria
import com.poobi.tvbrowser.SourceSorter
import com.poobi.tvbrowser.SubtitleData
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

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

    private val _itemSeasons = MutableStateFlow<JSONArray?>(null)
    val itemSeasons: StateFlow<JSONArray?> = _itemSeasons.asStateFlow()

    private val _itemCast = MutableStateFlow<JSONArray?>(null)
    val itemCast: StateFlow<JSONArray?> = _itemCast.asStateFlow()

    private val _itemRecommendations = MutableStateFlow<JSONArray?>(null)
    val itemRecommendations: StateFlow<JSONArray?> = _itemRecommendations.asStateFlow()

    private val _favoritesSet = MutableStateFlow<Set<String>>(emptySet())
    val favoritesSet: StateFlow<Set<String>> = _favoritesSet.asStateFlow()

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

    private val _events = MutableStateFlow<StreamsEvent?>(null)
    val events: StateFlow<StreamsEvent?> = _events.asStateFlow()

    private val _isDownloadingSubs = MutableStateFlow(false)
    val isDownloadingSubs: StateFlow<Boolean> = _isDownloadingSubs.asStateFlow()
    
    private val _subStatusMsg = MutableStateFlow("")
    val subStatusMsg: StateFlow<String> = _subStatusMsg.asStateFlow()

    var lastScrapedSeason: Int? = null
    var lastScrapedEpisode: Int? = null
    var lastSelectedSource: JSONObject? = null
    val interceptedSubtitleUrls = mutableMapOf<String, Map<String, String>>()
    val cachedEpisodes = mutableMapOf<String, JSONArray>()

    private var scrapeJob: Job? = null
    private var scrapePollingJob: Job? = null
    private var libraryLoadJob: Job? = null
    private var isInteractingWithSources = false
    private var lastSubtitledItemKey: String? = null
    private var isAutoplayStarting = false
    
    var pendingPlayVideoSourceData: String? = null

    var isPlayingFromSavedLink = false

    init {
        loadSearchHistory()
        refreshFavoritesSet()
        loadGenres()
        purgeOldSubtitles()
        startBackgroundTraktCheck()
    }

    fun consumeEvent() { _events.value = null }

    fun clearScrapedSources() {
        stopScrape()
        _scrapedSources.value = null
    }

    fun clearSelectedMedia() { _selectedItem.value = null }
    fun clearSearchResults() { _searchResults.value = null }

    private fun loadGenres() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val py = Python.getInstance()
                val tmdb = py.getModule("tmdb.tmdb_api")
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
        val historyJson = prefs.getString("streams_search_history", "[]") ?: "[]"
        val array = JSONArray(historyJson)
        val list = mutableListOf<String>()
        for (i in 0 until array.length()) list.add(array.getString(i))
        _searchHistory.value = list
    }

    fun addToSearchHistory(query: String) {
        val list = _searchHistory.value.toMutableList()
        list.remove(query)
        list.add(0, query)
        if (list.size > 20) list.removeAt(list.size - 1)
        _searchHistory.value = list
        prefs.edit().putString("streams_search_history", JSONArray(list).toString()).apply()
    }

    fun clearSearchHistory() {
        prefs.edit().putString("streams_search_history", "[]").apply()
        _searchHistory.value = emptyList()
    }

    fun performSearch(query: String) {
        if (query.isBlank()) return
        addToSearchHistory(query)
        _scrapeStatusMsg.value = "Searching..."
        _isScraping.value = true
        _searchResults.value = null

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val py = Python.getInstance()
                val scraper = py.getModule("main")
                val resultsJson = scraper.callAttr("search", query).toString()
                withContext(Dispatchers.Main) {
                    _searchResults.value = JSONArray(resultsJson)
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
        cancelLibraryJobs()
        libraryLoadJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val py = Python.getInstance()
                val tmdb = py.getModule("tmdb.tmdb_api")
                val resultJson = if (arg != null) {
                    tmdb.callAttr(method, arg).toString()
                } else {
                    tmdb.callAttr(method).toString()
                }
                withContext(Dispatchers.Main) { 
                    _libraryItems.value = JSONObject(resultJson).getJSONArray("results") 
                }
            } catch (e: Exception) {}
        }
    }

    fun loadFavorites() { 
        cancelLibraryJobs()
        _libraryItems.value = JSONArray(prefs.getString("streams_favorites", "[]") ?: "[]") 
    }
    
    fun loadRecentlyPlayed() { 
        cancelLibraryJobs()
        _libraryItems.value = JSONArray(prefs.getString("streams_recently_played", "[]") ?: "[]")
    }
    
    fun removeFromRecentlyPlayed(index: Int) {
        val recentJson = prefs.getString("streams_recently_played", "[]") ?: "[]"
        val array = JSONArray(recentJson)
        if (index >= 0 && index < array.length()) {
            val entry = array.getJSONObject(index)
            val displayTitle = entry.optString("display_title")
            
            val newList = JSONArray()
            for (i in 0 until array.length()) {
                if (i != index) newList.put(array.get(i))
            }
            prefs.edit().putString("streams_recently_played", newList.toString()).apply()
            
            if (displayTitle.isNotEmpty()) {
                prefs.edit().remove("resume_stream_$displayTitle").apply()
            }
            
            loadRecentlyPlayed()
        }
    }

    fun onVideoPlaybackStarted(
        url: String, 
        title: String, 
        item: JSONObject, 
        season: Int?, 
        episode: Int?, 
        headers: Map<String, String>
    ) {
        val displayTitle = if (season != null && episode != null) "$title S${season}E$episode" else title
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
        val recentJson = prefs.getString("streams_recently_played", "[]") ?: "[]"
        val array = JSONArray(recentJson)
        val newList = mutableListOf<JSONObject>()
        for (i in 0 until array.length()) newList.add(array.getJSONObject(i))

        val id = item.optString("id")
        val imdb = item.optString("imdb")

        val existingIndex = newList.indexOfFirst {
            val itItem = it.optJSONObject("item")
            val itId = itItem?.optString("id")
            val itImdb = itItem?.optString("imdb")
            val sameItem = (itId == id && id.isNotEmpty()) || (itImdb == imdb && imdb.isNotEmpty())
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

        prefs.edit().putString("streams_recently_played", JSONArray(newList).toString()).apply()
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
                subtitles = subtitleUrls,
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
        _favoritesSet.value = set
    }

    fun toggleFavorite(item: JSONObject, isCurrentlyViewingFavorites: Boolean = false) {
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
            loadFavorites()
        }
    }

    fun selectMediaItem(item: JSONObject, initialSeason: Int? = null) {
        _selectedItem.value = item
        _itemDetails.value = null
        _itemEpisodes.value = null
        _itemSeasons.value = null
        _itemCast.value = null
        _itemRecommendations.value = null
        
        val id = item.optInt("id")
        val mediaType = item.optString("media_type").takeIf { it.isNotEmpty() && it != "null" }
            ?: if (item.has("name") || item.has("first_air_date")) "tv" else "movie"

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val py = Python.getInstance()
                val tmdb = py.getModule("tmdb.tmdb_api")
                val detailsJson = tmdb.callAttr("get_details", id, mediaType).toString()
                val details = JSONObject(detailsJson)

                withContext(Dispatchers.Main) {
                    _itemDetails.value = details
                    _itemRecommendations.value = details.optJSONObject("recommendations")?.optJSONArray("results")
                    _itemCast.value = details.optJSONObject("credits")?.optJSONArray("cast")

                    if (mediaType == "tv") {
                        val seasons = details.optJSONArray("seasons")
                        _itemSeasons.value = seasons
                        if (seasons != null && seasons.length() > 0) {
                            val seasonToLoad = initialSeason ?: seasons.getJSONObject(0).optInt("season_number")
                            loadEpisodes(item, seasonToLoad)
                        }
                    }
                }
            } catch (e: Exception) {}
        }
    }

    fun loadEpisodes(item: JSONObject, seasonNumber: Int) {
        val id = item.optInt("id")
        _itemEpisodes.value = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val py = Python.getInstance()
                val scraper = py.getModule("main")
                val episodesJson = scraper.callAttr("get_tv_episodes", id, seasonNumber).toString()
                val episodes = JSONArray(episodesJson)
                cachedEpisodes["${id}_$seasonNumber"] = episodes
                try {
                    val statusJson = py.getModule("trakt.episode_check").callAttr("get_watched_status", id, seasonNumber, episodes.toString()).toString()
                    val watchedStatus = JSONArray(statusJson)
                    for (i in 0 until episodes.length()) {
                        episodes.getJSONObject(i).put("is_watched", if (i < watchedStatus.length()) watchedStatus.getBoolean(i) else false)
                    }
                } catch (e: Exception) {}
                withContext(Dispatchers.Main) { _itemEpisodes.value = episodes }
            } catch (e: Exception) {}
        }
    }

    fun stopScrape() {
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

        _isScraping.value = true
        _isResolving.value = false
        _scrapedSources.value = null
        _scrapeProgress.value = 0
        _scrapeTotal.value = 0
        _scrapeStatusMsg.value = "Starting scrapers..."
        isInteractingWithSources = false

        scrapePollingJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    val statusStr = Python.getInstance().getModule("main").callAttr("get_scrape_status").toString()
                    val status = JSONObject(statusStr)
                    val current = status.optInt("current", 0)
                    val total = status.optInt("total", 0)
                    val message = status.optString("message", "")
                    val sources = status.optJSONArray("sources")

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

                        if (sources != null && !isInteractingWithSources) _scrapedSources.value = sortSources(sources)
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.e("StreamsViewModel", "Polling loop experienced an error: ${e.message}")
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
        _subStatusMsg.value = "Searching external subtitles..."

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val py = Python.getInstance()
                val scraper = py.getModule("main")
                val subsJson = scraper.callAttr("search_subtitles", item.toString(), season, episode).toString()
                
                withContext(Dispatchers.Main) {
                    _isDownloadingSubs.value = false
                    _events.value = StreamsEvent.ShowSubtitlePicker(JSONArray(subsJson))
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _isDownloadingSubs.value = false
                    _events.value = StreamsEvent.ShowToast("Subtitle search failed")
                }
            }
        }
    }

    fun downloadSubtitles(subs: List<JSONObject>, silent: Boolean = false) {
        if (subs.isEmpty()) return
        _isDownloadingSubs.value = true
        _subStatusMsg.value = "Downloading subtitles..."

        viewModelScope.launch(Dispatchers.IO) {
            var count = 0
            val newlyDownloadedSubs = mutableListOf<SubtitleData>()
            try {
                val py = Python.getInstance()
                val scraper = py.getModule("main")
                for (sub in subs) {
                    val serviceName = sub.getString("service")
                    val actionArgs = sub.getString("action_args")
                    val filePath = scraper.callAttr("get_subtitle_file", serviceName, actionArgs).toString()

                    if (filePath.isNotEmpty()) {
                        val subUri = android.net.Uri.fromFile(java.io.File(filePath)).toString()
                        val source = sub.optString("service", "Unknown")
                        val name = sub.optString("name", "Subtitle")
                        val label = "[$source] $name"
                        val lang = sub.optString("lang", "und")

                        interceptedSubtitleUrls[subUri] = mapOf("label" to label, "lang" to lang)
                        newlyDownloadedSubs.add(SubtitleData(subUri, label, lang))
                        count++
                    }
                }
                withContext(Dispatchers.Main) {
                    _isDownloadingSubs.value = false
                    if (newlyDownloadedSubs.isNotEmpty()) {
                        _events.value = StreamsEvent.AddSubtitlesBatch(newlyDownloadedSubs)
                    }
                    if (!silent) _events.value = StreamsEvent.ShowToast("Finished downloading $count subtitles")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _isDownloadingSubs.value = false
                    if (!silent) _events.value = StreamsEvent.ShowToast("Subtitle download failed")
                }
            }
        }
    }

    fun cancelSubtitleDownloads() { 
        _isDownloadingSubs.value = false 
        pendingPlayVideoSourceData = null
    }

    private fun performAutoSubtitleSearch(item: JSONObject, season: Int?, episode: Int?) {
        val itemKey = "${item.optInt("id")}_${season ?: 0}_${episode ?: 0}"
        if (_isDownloadingSubs.value || lastSubtitledItemKey == itemKey) return
        
        lastSubtitledItemKey = itemKey
        _isDownloadingSubs.value = true
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

                    for (i in 0 until prioritizedSubs.size) {
                        if (downloadedCount >= countToGet || !_isDownloadingSubs.value) break
                        
                        val sub = prioritizedSubs[i]
                        withContext(Dispatchers.Main) { 
                            _subStatusMsg.value = "Downloading subtitle ${downloadedCount + 1} / $countToGet..." 
                        }
                        
                        val serviceName = sub.getString("service")
                        val actionArgs = sub.getString("action_args")
                        val filePath = scraper.callAttr("get_subtitle_file", serviceName, actionArgs).toString()

                        if (filePath.isNotEmpty()) {
                            val subUri = android.net.Uri.fromFile(java.io.File(filePath)).toString()
                            val source = sub.optString("service", "Unknown")
                            val name = sub.optString("name", "Subtitle")
                            val label = "[$source] $name"
                            val lang = sub.optString("lang", "und")
                            interceptedSubtitleUrls[subUri] = mapOf("label" to label, "lang" to lang)
                            
                            newlyDownloadedSubs.add(SubtitleData(subUri, label, lang))
                            downloadedCount++
                        }
                    }
                    
                    withContext(Dispatchers.Main) { 
                        _isDownloadingSubs.value = false 
                        
                        // Progressive Loading (Mode 2): inject all subtitles at once as a batch
                        if (newlyDownloadedSubs.isNotEmpty()) {
                            _events.value = StreamsEvent.AddSubtitlesBatch(newlyDownloadedSubs)
                        }

                        // Wait Loading (Mode 1): resolve and play held video once finished
                        pendingPlayVideoSourceData?.let { pendingSource ->
                            resolveAndPlayInternal(pendingSource)
                            pendingPlayVideoSourceData = null
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) { 
                        _isDownloadingSubs.value = false
                        pendingPlayVideoSourceData?.let { pendingSource ->
                            resolveAndPlayInternal(pendingSource)
                            pendingPlayVideoSourceData = null
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { 
                    _isDownloadingSubs.value = false
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
        isPlayingFromSavedLink = false
        lastSelectedSource = try { JSONObject(sourceDataJson) } catch (e: Exception) { rawItem }
        isInteractingWithSources = true
        _scrapeStatusMsg.value = "Pausing Scrapers..."

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
                        val title = _selectedItem.value?.optString("title")?.takeIf { it.isNotBlank() } ?: _selectedItem.value?.optString("name") ?: "Unknown"

                        if (streamUrl.isNotEmpty() && streamUrl.startsWith("http")) {
                            val headersMap = mutableMapOf<String, String>()
                            
                            var nextEp: JSONObject? = null
                            if (lastScrapedSeason != null && lastScrapedEpisode != null) {
                                val currentEp = lastScrapedEpisode!!
                                val item = _selectedItem.value
                                if (item != null) {
                                    val cacheKey = "${item.optInt("id")}_$lastScrapedSeason"
                                    val episodes = cachedEpisodes[cacheKey]
                                    if (episodes != null) {
                                        for (i in 0 until episodes.length()) {
                                            val ep = episodes.getJSONObject(i)
                                            if (ep.optInt("episode_number") == currentEp + 1) {
                                                nextEp = ep
                                                break
                                            }
                                        }
                                    }
                                }
                            }

                            _events.value = StreamsEvent.PlayVideo(
                                url = streamUrl, 
                                title = title, 
                                headers = headersMap,
                                subtitles = interceptedSubtitleUrls,
                                item = _selectedItem.value!!, 
                                season = lastScrapedSeason, 
                                episode = lastScrapedEpisode, 
                                nextEpisode = nextEp,
                                isWebpage = !isVideo
                            )
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
                val py = Python.getInstance()
                val config = JSONObject(py.getModule("main").get("GLOBAL_CONFIG").toString())
                val autoplayMode = config.optString("autoplay_next_pref", "Closest Source")

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

                        if (isNewScrapeStarted && sources != null && sources.length() > 0) {
                             _scrapedSources.value = sortSources(sources)
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

                val filesDir = context.filesDir
                val thresholdMs = System.currentTimeMillis() - (subRetentionDays * 24L * 60L * 60L * 1000L)
                val appFiles = filesDir.listFiles() ?: return@launch
                
                var deletedCount = 0
                for (file in appFiles) {
                    if (file.isFile && (file.name.endsWith(".srt") || file.name.endsWith(".vtt") || file.name.endsWith(".ass"))) {
                        if (file.lastModified() < thresholdMs) {
                            if (file.delete()) {
                                deletedCount++
                            }
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
            while (isActive) {
                try {
                    val favsJson = prefs.getString("streams_favorites", "[]") ?: "[]"
                    if (favsJson != "[]") {
                        val lastCheckJson = prefs.getString("last_episode_check", "{}") ?: "{}"
                        val py = Python.getInstance()
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
                    Log.e("TVBrowser", "Trakt episode sync task error: ${e.message}")
                }
                delay(900000)
            }
        }
    }
}