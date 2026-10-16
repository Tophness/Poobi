package com.poobi.tvbrowser.tvhome

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.util.Log
import android.util.LruCache
import androidx.tvprovider.media.tv.PreviewChannel
import androidx.tvprovider.media.tv.PreviewChannelHelper
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram
import com.chaquo.python.Python
import com.poobi.tvbrowser.R
import com.poobi.tvbrowser.shared.StreamResumeManager
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

object TvHomeSyncManager {
    private const val TAG = "TvHomeSyncManager"
    private const val PREFS_NAME = "TvHomePrefs"
    private const val KEY_CHANNEL_FAVORITES = "tv_channel_favorites_id"
    private const val KEY_CHANNEL_NEW_EPISODES = "tv_channel_new_episodes_id"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val episodeStillCache = LruCache<String, String>(100)
    private val backdropCache = LruCache<String, String>(100)

    private var favSyncJob: Job? = null
    private var newEpSyncJob: Job? = null
    private var watchNextSyncJob: Job? = null

    fun init(context: Context) {
        scope.launch {
            try {
                syncFavoritesChannelInternal(context)
                syncNewEpisodesChannelInternal(context)
                syncWatchNextInternal(context)
            } catch (e: Exception) {
                Log.w(TAG, "TvHome initialization error: ${e.message}")
            }
        }
    }

    fun getAppLogoBitmap(context: Context): Bitmap {
        return try {
            val drawable = context.packageManager.getApplicationIcon(context.packageName)
            val width = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 160
            val height = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 160
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            bitmap
        } catch (e: Exception) {
            val bitmap = Bitmap.createBitmap(80, 80, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val paint = android.graphics.Paint().apply {
                color = android.graphics.Color.parseColor("#00BCD4")
                isAntiAlias = true
            }
            canvas.drawCircle(40f, 40f, 40f, paint)
            bitmap
        }
    }

    private suspend fun resolveLandscapeImage(
        context: Context,
        item: JSONObject,
        season: Int? = null,
        episode: Int? = null,
        stillPathOverride: String? = null
    ): Pair<Uri, Int> {
        if (!stillPathOverride.isNullOrBlank() && stillPathOverride != "null") {
            val url = if (stillPathOverride.startsWith("http")) stillPathOverride else "https://image.tmdb.org/t/p/w780$stillPathOverride"
            return Pair(Uri.parse(url), TvContractCompat.PreviewProgramColumns.ASPECT_RATIO_16_9)
        }

        val id = item.optString("id").ifEmpty { item.optString("imdb") }
        val isTv = item.optString("media_type") == "tv" || item.has("name") || (season != null && episode != null)
        val mediaType = if (isTv) "tv" else "movie"

        if (isTv && season != null && episode != null && id.isNotBlank() && id != "0") {
            val epKey = "${id}_${season}_${episode}"
            val cachedStill = episodeStillCache.get(epKey)
            if (!cachedStill.isNullOrBlank()) {
                val url = if (cachedStill.startsWith("http")) cachedStill else "https://image.tmdb.org/t/p/w780$cachedStill"
                return Pair(Uri.parse(url), TvContractCompat.PreviewProgramColumns.ASPECT_RATIO_16_9)
            }

            val itemStill = item.optString("still_path")
            if (itemStill.isNotBlank() && itemStill != "null") {
                episodeStillCache.put(epKey, itemStill)
                val url = if (itemStill.startsWith("http")) itemStill else "https://image.tmdb.org/t/p/w780$itemStill"
                return Pair(Uri.parse(url), TvContractCompat.PreviewProgramColumns.ASPECT_RATIO_16_9)
            }

            val fetchedStill = fetchEpisodeStillFromServer(id, season, episode)
            if (!fetchedStill.isNullOrBlank()) {
                episodeStillCache.put(epKey, fetchedStill)
                val url = if (fetchedStill.startsWith("http")) fetchedStill else "https://image.tmdb.org/t/p/w780$fetchedStill"
                return Pair(Uri.parse(url), TvContractCompat.PreviewProgramColumns.ASPECT_RATIO_16_9)
            }
        }

        if (id.isNotBlank() && id != "0") {
            val backdropKey = "${mediaType}_$id"
            val cachedBackdrop = backdropCache.get(backdropKey)
            if (!cachedBackdrop.isNullOrBlank()) {
                val url = if (cachedBackdrop.startsWith("http")) cachedBackdrop else "https://image.tmdb.org/t/p/w780$cachedBackdrop"
                return Pair(Uri.parse(url), TvContractCompat.PreviewProgramColumns.ASPECT_RATIO_16_9)
            }

            val itemBackdrop = item.optString("backdrop_path")
            if (itemBackdrop.isNotBlank() && itemBackdrop != "null") {
                backdropCache.put(backdropKey, itemBackdrop)
                val url = if (itemBackdrop.startsWith("http")) itemBackdrop else "https://image.tmdb.org/t/p/w780$itemBackdrop"
                return Pair(Uri.parse(url), TvContractCompat.PreviewProgramColumns.ASPECT_RATIO_16_9)
            }

            val fetchedBackdrop = fetchBackdropFromServer(id, mediaType)
            if (!fetchedBackdrop.isNullOrBlank()) {
                backdropCache.put(backdropKey, fetchedBackdrop)
                val url = if (fetchedBackdrop.startsWith("http")) fetchedBackdrop else "https://image.tmdb.org/t/p/w780$fetchedBackdrop"
                return Pair(Uri.parse(url), TvContractCompat.PreviewProgramColumns.ASPECT_RATIO_16_9)
            }
        }

        val posterPath = item.optString("poster_path")
        if (posterPath.isNotBlank() && posterPath != "null") {
            val url = if (posterPath.startsWith("http")) posterPath else "https://image.tmdb.org/t/p/w500$posterPath"
            return Pair(Uri.parse(url), TvContractCompat.PreviewProgramColumns.ASPECT_RATIO_2_3)
        }

        return Pair(
            Uri.parse("android.resource://${context.packageName}/${R.drawable.tv_banner}"),
            TvContractCompat.PreviewProgramColumns.ASPECT_RATIO_16_9
        )
    }

    private fun fetchEpisodeStillFromServer(showId: String, season: Int, episode: Int): String? {
        if (Python.isStarted()) {
            try {
                val py = Python.getInstance()
                val main = py.getModule("main")
                val idParam = showId.toIntOrNull() ?: showId
                val episodesJson = main.callAttr("get_tv_episodes", idParam, season).toString()
                val arr = JSONArray(episodesJson)
                for (i in 0 until arr.length()) {
                    val ep = arr.getJSONObject(i)
                    if (ep.optInt("episode_number") == episode) {
                        val still = ep.optString("still_path")
                        if (still.isNotBlank() && still != "null") {
                            return still
                        }
                    }
                }
            } catch (_: Exception) {}
        }
        return null
    }

    private fun fetchBackdropFromServer(id: String, mediaType: String): String? {
        if (Python.isStarted()) {
            try {
                val py = Python.getInstance()
                val tmdb = py.getModule("tmdb.tmdb_utils")
                val idParam = id.toIntOrNull() ?: id
                val detailsJson = tmdb.callAttr("get_details", idParam, mediaType).toString()
                val obj = JSONObject(detailsJson)
                val backdrop = obj.optString("backdrop_path")
                if (backdrop.isNotBlank() && backdrop != "null") {
                    return backdrop
                }
            } catch (_: Exception) {}
        }
        return null
    }

    fun buildDeepLinkUri(
        mediaType: String,
        id: String,
        title: String? = null,
        season: Int? = null,
        episode: Int? = null,
        autoPlay: Boolean = false
    ): Uri {
        val builder = Uri.Builder()
            .scheme("poobi")
            .authority(if (autoPlay) "play" else "details")
            .appendQueryParameter("type", mediaType)
            .appendQueryParameter("id", id)

        if (!title.isNullOrBlank()) {
            builder.appendQueryParameter("title", title)
        }
        if (season != null && season > 0) {
            builder.appendQueryParameter("season", season.toString())
        }
        if (episode != null && episode > 0) {
            builder.appendQueryParameter("episode", episode.toString())
        }
        if (autoPlay) {
            builder.appendQueryParameter("play", "true")
        }
        return builder.build()
    }

    fun getWatchNextInternalId(item: JSONObject, season: Int? = null, episode: Int? = null): String {
        val isTv = item.optString("media_type") == "tv" || item.has("name") || (season != null && episode != null)
        val id = item.optString("id").ifEmpty { item.optString("imdb", "0") }
        return if (isTv) "poobi_tv_$id" else "poobi_movie_$id"
    }

    private fun getExistingWatchNextPrograms(context: Context): MutableMap<String, Long> {
        val existingMap = mutableMapOf<String, Long>()
        try {
            val cursor = context.contentResolver.query(
                TvContractCompat.WatchNextPrograms.CONTENT_URI,
                arrayOf(
                    TvContractCompat.WatchNextPrograms._ID,
                    TvContractCompat.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID
                ),
                null,
                null,
                null
            )
            cursor?.use {
                val idCol = it.getColumnIndex(TvContractCompat.WatchNextPrograms._ID)
                val providerIdCol = it.getColumnIndex(TvContractCompat.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID)

                if (idCol == -1) return existingMap

                while (it.moveToNext()) {
                    val programId = it.getLong(idCol)
                    val internalId = if (providerIdCol != -1) it.getString(providerIdCol) ?: "" else ""

                    if (internalId.isNotEmpty()) {
                        if (existingMap.containsKey(internalId)) {
                            try {
                                context.contentResolver.delete(
                                    TvContractCompat.buildWatchNextProgramUri(programId),
                                    null,
                                    null
                                )
                                Log.d(TAG, "Deleted duplicate Watch Next entry: $programId ($internalId)")
                            } catch (_: Exception) {}
                        } else {
                            existingMap[internalId] = programId
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying Watch Next programs: ${e.message}", e)
        }
        return existingMap
    }

    fun removeWatchNext(context: Context, item: JSONObject, season: Int? = null, episode: Int? = null) {
        scope.launch {
            try {
                val internalId = getWatchNextInternalId(item, season, episode)
                val existingMap = getExistingWatchNextPrograms(context)
                val programId = existingMap[internalId]
                if (programId != null && programId > 0) {
                    context.contentResolver.delete(
                        TvContractCompat.buildWatchNextProgramUri(programId),
                        null,
                        null
                    )
                    context.contentResolver.notifyChange(TvContractCompat.WatchNextPrograms.CONTENT_URI, null)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed removing from Watch Next: ${e.message}")
            }
        }
    }

    fun syncWatchNext(context: Context) {
        watchNextSyncJob?.cancel()
        watchNextSyncJob = scope.launch {
            delay(200)
            syncWatchNextInternal(context)
        }
    }

    private suspend fun syncWatchNextInternal(context: Context) {
        try {
            val helper = PreviewChannelHelper(context)
            val existingPrograms = getExistingWatchNextPrograms(context)
            val prefs = context.getSharedPreferences("BrowserSettings", Context.MODE_PRIVATE)
            val recentArray = JSONArray(prefs.getString("streams_recently_played", "[]") ?: "[]")

            val processedShows = mutableSetOf<String>()
            val now = System.currentTimeMillis()

            for (index in 0 until recentArray.length().coerceAtMost(20)) {
                val entry = recentArray.getJSONObject(index)
                val item = entry.optJSONObject("item") ?: continue
                val season = if (entry.has("season")) entry.optInt("season") else null
                val episode = if (entry.has("episode")) entry.optInt("episode") else null
                val isTv = item.optString("media_type") == "tv" || item.has("name") || (season != null && episode != null)
                val id = item.optString("id").ifEmpty { item.optString("imdb", "0") }
                if (id.isEmpty() || id == "0") continue

                val internalId = getWatchNextInternalId(item, season, episode)
                if (processedShows.contains(internalId)) continue
                processedShows.add(internalId)

                val displayTitle = entry.optString("display_title").ifEmpty {
                    val t = item.optString("title").ifEmpty { item.optString("name", "Unknown") }
                    if (season != null && episode != null) "$t S${season}E$episode" else t
                }

                val resumePos = StreamResumeManager.getPosition(context, displayTitle)
                val dur = entry.optLong("duration_ms", 0L)
                val isCompleted = dur > 0L && resumePos >= (dur * 0.92f).toLong()

                if (isCompleted && !isTv) {
                    continue
                }

                val titleRaw = item.optString("title").ifEmpty { item.optString("name", "Unknown") }
                val title = titleRaw.replace(Regex("\\s\\(\\d{4}\\)$"), "")
                val overview = item.optString("overview", "")
                val (landscapeUri, aspectRatio) = resolveLandscapeImage(context, item, season, episode)

                val deepLink = buildDeepLinkUri(
                    mediaType = if (isTv) "tv" else "movie",
                    id = id,
                    title = title,
                    season = season,
                    episode = episode,
                    autoPlay = true
                )

                val engagementTime = now - (index * 1000L)
                val builder = WatchNextProgram.Builder()
                    .setType(if (isTv) TvContractCompat.WatchNextPrograms.TYPE_TV_EPISODE else TvContractCompat.WatchNextPrograms.TYPE_MOVIE)
                    .setWatchNextType(if (isCompleted) TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_NEXT else TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
                    .setLastEngagementTimeUtcMillis(engagementTime)
                    .setTitle(title)
                    .setDescription(overview)
                    .setPosterArtUri(landscapeUri)
                    .setPosterArtAspectRatio(aspectRatio)
                    .setIntentUri(deepLink)
                    .setInternalProviderId(internalId)

                if (isTv && season != null && episode != null) {
                    builder.setEpisodeTitle("S${season}E${episode}")
                        .setSeasonNumber(season)
                        .setEpisodeNumber(episode)
                }

                if (!isCompleted && resumePos > 5000L && dur > 0L) {
                    builder.setLastPlaybackPositionMillis(resumePos.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt())
                    builder.setDurationMillis(dur.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt())
                }

                val existingProgramId = existingPrograms.remove(internalId)
                val program = builder.build()

                if (existingProgramId != null && existingProgramId > 0) {
                    try {
                        helper.updateWatchNextProgram(program, existingProgramId)
                    } catch (e: Exception) {
                        helper.publishWatchNextProgram(program)
                    }
                } else {
                    helper.publishWatchNextProgram(program)
                }
            }

            for ((_, orphanId) in existingPrograms) {
                try {
                    context.contentResolver.delete(
                        TvContractCompat.buildWatchNextProgramUri(orphanId),
                        null,
                        null
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Error removing orphan Watch Next item $orphanId: ${e.message}")
                }
            }

            context.contentResolver.notifyChange(TvContractCompat.WatchNextPrograms.CONTENT_URI, null)
        } catch (e: Exception) {
            Log.e(TAG, "Failed syncing Watch Next from recently played: ${e.message}", e)
        }
    }

    fun syncFavoritesChannel(context: Context) {
        favSyncJob?.cancel()
        favSyncJob = scope.launch {
            delay(500)
            syncFavoritesChannelInternal(context)
        }
    }

    fun syncNewEpisodesChannel(context: Context, notificationsList: List<JSONObject>? = null) {
        newEpSyncJob?.cancel()
        newEpSyncJob = scope.launch {
            delay(500)
            syncNewEpisodesChannelInternal(context, notificationsList)
        }
    }

    private suspend fun syncFavoritesChannelInternal(context: Context) {
        try {
            val helper = PreviewChannelHelper(context)
            val channelId = getOrCreateChannel(
                context = context,
                helper = helper,
                prefKey = KEY_CHANNEL_FAVORITES,
                displayName = "Poobi Favourites",
                description = "Your favorite Movies and TV Shows",
                appLinkUri = "poobi://favorites",
                internalId = "poobi_channel_favorites",
                isDefault = true
            ) ?: return

            val prefs = context.getSharedPreferences("BrowserSettings", Context.MODE_PRIVATE)
            val favsArray = JSONArray(prefs.getString("streams_favorites", "[]") ?: "[]")
            val items = (0 until favsArray.length()).map { favsArray.getJSONObject(it) }.take(25)

            syncProgramsForFavoritesChannel(context, helper, channelId, items)
        } catch (e: Exception) {
            Log.e(TAG, "Failed syncing Favorites channel: ${e.message}", e)
        }
    }

    private suspend fun syncNewEpisodesChannelInternal(context: Context, notificationsList: List<JSONObject>? = null) {
        try {
            val helper = PreviewChannelHelper(context)
            val channelId = getOrCreateChannel(
                context = context,
                helper = helper,
                prefKey = KEY_CHANNEL_NEW_EPISODES,
                displayName = "New Episodes",
                description = "Newly aired episodes for your favorite shows",
                appLinkUri = "poobi://new_episodes",
                internalId = "poobi_channel_new_episodes",
                isDefault = true
            ) ?: return

            val episodes = notificationsList ?: run {
                val prefs = context.getSharedPreferences("BrowserSettings", Context.MODE_PRIVATE)
                val raw = prefs.getString("saved_new_episodes", "[]") ?: "[]"
                val arr = JSONArray(raw)
                (0 until arr.length()).map { arr.getJSONObject(it) }
            }

            syncProgramsForNewEpisodesChannel(context, helper, channelId, episodes)
        } catch (e: Exception) {
            Log.e(TAG, "Failed syncing New Episodes channel: ${e.message}", e)
        }
    }

    private fun getOrCreateChannel(
        context: Context,
        helper: PreviewChannelHelper,
        prefKey: String,
        displayName: String,
        description: String,
        appLinkUri: String,
        internalId: String,
        isDefault: Boolean
    ): Long? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val cachedChannelId = prefs.getLong(prefKey, -1L)

        if (cachedChannelId > 0) {
            val existing = try { helper.getPreviewChannel(cachedChannelId) } catch (_: Exception) { null }
            if (existing != null) {
                return cachedChannelId
            }
        }

        try {
            for (ch in helper.allChannels) {
                if (ch.internalProviderId == internalId) {
                    prefs.edit().putLong(prefKey, ch.id).apply()
                    return ch.id
                }
            }
        } catch (_: Exception) {}

        return try {
            val logoBitmap = getAppLogoBitmap(context)

            val channel = PreviewChannel.Builder()
                .setDisplayName(displayName)
                .setDescription(description)
                .setAppLinkIntentUri(Uri.parse(appLinkUri))
                .setInternalProviderId(internalId)
                .setLogo(logoBitmap)
                .build()

            val newId = if (isDefault) {
                try { helper.publishDefaultChannel(channel) } catch (_: Exception) { helper.publishChannel(channel) }
            } else {
                helper.publishChannel(channel)
            }

            if (newId > 0) {
                try { TvContractCompat.requestChannelBrowsable(context, newId) } catch (_: Exception) {}
                prefs.edit().putLong(prefKey, newId).apply()
                newId
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "Error creating channel $displayName: ${e.message}", e)
            null
        }
    }

    private suspend fun syncProgramsForFavoritesChannel(
        context: Context,
        helper: PreviewChannelHelper,
        channelId: Long,
        items: List<JSONObject>
    ) {
        try {
            context.contentResolver.delete(
                TvContractCompat.buildPreviewProgramsUriForChannel(channelId),
                null,
                null
            )

            val count = items.size
            for (index in items.indices) {
                val item = items[index]
                val isTv = item.optString("media_type") == "tv" || item.has("name")
                val id = item.optString("id").ifEmpty { item.optString("imdb", "0") }
                val titleRaw = item.optString("title").ifEmpty { item.optString("name", "Unknown") }
                val title = titleRaw.replace(Regex("\\s\\(\\d{4}\\)$"), "")
                val overview = item.optString("overview", "")

                val (landscapeUri, aspectRatio) = resolveLandscapeImage(context, item)

                val deepLink = buildDeepLinkUri(
                    mediaType = if (isTv) "tv" else "movie",
                    id = id,
                    title = title,
                    autoPlay = false
                )

                val builder = PreviewProgram.Builder()
                    .setChannelId(channelId)
                    .setTitle(title)
                    .setDescription(overview)
                    .setType(if (isTv) TvContractCompat.PreviewPrograms.TYPE_TV_SERIES else TvContractCompat.PreviewPrograms.TYPE_MOVIE)
                    .setPosterArtAspectRatio(aspectRatio)
                    .setPosterArtUri(landscapeUri)
                    .setIntentUri(deepLink)
                    .setInternalProviderId("fav_$id")
                    .setWeight(count - index)

                try {
                    helper.publishPreviewProgram(builder.build())
                } catch (e: Exception) {
                    Log.e(TAG, "Failed publishing favorite $title: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed syncing programs for favorites channel: ${e.message}", e)
        }
    }

    private suspend fun syncProgramsForNewEpisodesChannel(
        context: Context,
        helper: PreviewChannelHelper,
        channelId: Long,
        notifications: List<JSONObject>
    ) {
        try {
            context.contentResolver.delete(
                TvContractCompat.buildPreviewProgramsUriForChannel(channelId),
                null,
                null
            )

            val count = notifications.size
            for (index in notifications.indices) {
                val notification = notifications[index]
                val itemObj = notification.optJSONObject("item")
                val showTitle = notification.optString("show_title", "Unknown")
                val epName = notification.optString("episode_name", "New Episode")
                val season = notification.optInt("season", 1)
                val episodeNum = notification.optInt("number", 1)
                val overview = notification.optString("episode_overview").takeIf { it.isNotEmpty() }
                    ?: itemObj?.optString("overview")
                    ?: ""

                val showId = notification.optInt("show_id", 0).let {
                    if (it > 0) it.toString() else itemObj?.optString("id") ?: "0"
                }

                val item = itemObj ?: JSONObject().apply {
                    put("id", showId)
                    put("name", showTitle)
                    put("media_type", "tv")
                }

                val (landscapeUri, aspectRatio) = resolveLandscapeImage(
                    context,
                    item,
                    season,
                    episodeNum,
                    notification.optString("still_path")
                )

                val deepLink = buildDeepLinkUri(
                    mediaType = "tv",
                    id = showId,
                    title = showTitle,
                    season = season,
                    episode = episodeNum,
                    autoPlay = true
                )

                val builder = PreviewProgram.Builder()
                    .setChannelId(channelId)
                    .setTitle(showTitle)
                    .setEpisodeTitle("S${season}E${episodeNum}: $epName")
                    .setDescription(overview)
                    .setType(TvContractCompat.PreviewPrograms.TYPE_TV_EPISODE)
                    .setSeasonNumber(season)
                    .setEpisodeNumber(episodeNum)
                    .setPosterArtAspectRatio(aspectRatio)
                    .setPosterArtUri(landscapeUri)
                    .setIntentUri(deepLink)
                    .setInternalProviderId("new_ep_${showId}_${season}_${episodeNum}")
                    .setWeight(count - index)

                try {
                    helper.publishPreviewProgram(builder.build())
                } catch (e: Exception) {
                    Log.e(TAG, "Failed publishing new episode program: ${e.message}")
                }
            }

            context.contentResolver.notifyChange(TvContractCompat.Channels.CONTENT_URI, null)
        } catch (e: Exception) {
            Log.e(TAG, "Failed syncing programs for New Episodes channel: ${e.message}", e)
        }
    }
}