package com.poobi.tvbrowser.tvhome

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.util.Log
import android.util.LruCache
import androidx.tvprovider.media.tv.ChannelLogoUtils
import androidx.tvprovider.media.tv.PreviewChannel
import androidx.tvprovider.media.tv.PreviewChannelHelper
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram
import com.chaquo.python.Python
import com.poobi.tvbrowser.R
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

object TvHomeSyncManager {
    private const val TAG = "TvHomeSyncManager"
    private const val PREFS_NAME = "TvHomePrefs"
    private const val KEY_CHANNEL_FAVORITES = "tv_channel_favorites_id"
    private const val KEY_CHANNEL_RECENTS = "tv_channel_recents_id"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val episodeStillCache = LruCache<String, String>(100)
    private val backdropCache = LruCache<String, String>(100)

    private var favSyncJob: Job? = null
    private var recentsSyncJob: Job? = null

    fun init(context: Context) {
        scope.launch {
            try {
                syncFavoritesChannelInternal(context)
                syncRecentlyWatchedChannelInternal(context)
            } catch (e: Exception) {
                Log.w(TAG, "TvHome initialization error: ${e.message}")
            }
        }
    }

    fun clearAllWatchNext(context: Context) {
        scope.launch {
            try {
                val deletedRows = context.contentResolver.delete(
                    TvContractCompat.WatchNextPrograms.CONTENT_URI,
                    null,
                    null
                )
                Log.d(TAG, "Cleared $deletedRows stale Watch Next items.")
            } catch (e: Exception) {
                Log.e(TAG, "Failed clearing Watch Next: ${e.message}")
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
            .authority("play")
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

    fun updateWatchNextContinue(
        context: Context,
        item: JSONObject,
        season: Int?,
        episode: Int?,
        positionMs: Long,
        durationMs: Long
    ) {
        scope.launch {
            try {
                val isTv = season != null && episode != null
                val id = item.optString("id").ifEmpty { item.optString("imdb", "0") }
                val internalId = if (isTv) "tv_${id}_${season}_${episode}" else "movie_${id}"

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

                val builder = WatchNextProgram.Builder()
                    .setType(if (isTv) TvContractCompat.WatchNextPrograms.TYPE_TV_EPISODE else TvContractCompat.WatchNextPrograms.TYPE_MOVIE)
                    .setWatchNextType(TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
                    .setLastEngagementTimeUtcMillis(System.currentTimeMillis())
                    .setTitle(title)
                    .setDescription(overview)
                    .setPosterArtUri(landscapeUri)
                    .setPosterArtAspectRatio(aspectRatio)
                    .setIntentUri(deepLink)
                    .setInternalProviderId(internalId)
                    .setLastPlaybackPositionMillis(positionMs.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt())
                    .setDurationMillis(durationMs.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt())

                if (isTv) {
                    builder.setEpisodeTitle("S${season}E${episode}")
                        .setSeasonNumber(season ?: 1)
                        .setEpisodeNumber(episode ?: 1)
                }

                upsertWatchNext(context, internalId, builder.build())
            } catch (e: Exception) {
                Log.e(TAG, "Failed updating Watch Next continue: ${e.message}", e)
            }
        }
    }

    fun updateWatchNextNext(
        context: Context,
        item: JSONObject,
        season: Int,
        episode: Int,
        nextEpisodeJson: JSONObject?
    ) {
        scope.launch {
            try {
                val id = item.optString("id").ifEmpty { item.optString("imdb", "0") }
                val internalId = "tv_${id}_${season}_${episode}"

                val showTitleRaw = item.optString("title").ifEmpty { item.optString("name", "Unknown") }
                val showTitle = showTitleRaw.replace(Regex("\\s\\(\\d{4}\\)$"), "")
                val epName = nextEpisodeJson?.optString("name") ?: "Episode $episode"
                val overview = nextEpisodeJson?.optString("overview") ?: item.optString("overview", "")

                val (landscapeUri, aspectRatio) = resolveLandscapeImage(
                    context, 
                    item, 
                    season, 
                    episode, 
                    nextEpisodeJson?.optString("still_path")
                )

                val deepLink = buildDeepLinkUri(
                    mediaType = "tv",
                    id = id,
                    title = showTitle,
                    season = season,
                    episode = episode,
                    autoPlay = true
                )

                val builder = WatchNextProgram.Builder()
                    .setType(TvContractCompat.WatchNextPrograms.TYPE_TV_EPISODE)
                    .setWatchNextType(TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_NEXT)
                    .setLastEngagementTimeUtcMillis(System.currentTimeMillis())
                    .setTitle(showTitle)
                    .setEpisodeTitle(epName)
                    .setSeasonNumber(season)
                    .setEpisodeNumber(episode)
                    .setDescription(overview)
                    .setPosterArtUri(landscapeUri)
                    .setPosterArtAspectRatio(aspectRatio)
                    .setIntentUri(deepLink)
                    .setInternalProviderId(internalId)

                upsertWatchNext(context, internalId, builder.build())
            } catch (e: Exception) {
                Log.e(TAG, "Failed updating Watch Next (next episode): ${e.message}", e)
            }
        }
    }

    fun removeWatchNext(context: Context, item: JSONObject, season: Int?, episode: Int?) {
        scope.launch {
            try {
                val isTv = season != null && episode != null
                val id = item.optString("id").ifEmpty { item.optString("imdb", "0") }
                val internalId = if (isTv) "tv_${id}_${season}_${episode}" else "movie_${id}"

                val existingId = getExistingWatchNextProgramId(context, internalId)
                if (existingId != null && existingId > 0) {
                    val programUri = TvContractCompat.buildWatchNextProgramUri(existingId)
                    context.contentResolver.delete(programUri, null, null)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed removing from Watch Next: ${e.message}")
            }
        }
    }

    private fun upsertWatchNext(context: Context, internalId: String, program: WatchNextProgram) {
        val helper = PreviewChannelHelper(context)
        val existingId = getExistingWatchNextProgramId(context, internalId)
        if (existingId != null && existingId > 0) {
            helper.updateWatchNextProgram(program, existingId)
        } else {
            helper.publishWatchNextProgram(program)
        }
    }

    private fun getExistingWatchNextProgramId(context: Context, internalProviderId: String): Long? {
        return try {
            val cursor = context.contentResolver.query(
                TvContractCompat.WatchNextPrograms.CONTENT_URI,
                arrayOf(TvContractCompat.WatchNextPrograms._ID, TvContractCompat.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID),
                "${TvContractCompat.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID} = ?",
                arrayOf(internalProviderId),
                null
            )
            cursor?.use {
                if (it.moveToFirst()) {
                    it.getLong(it.getColumnIndexOrThrow(TvContractCompat.WatchNextPrograms._ID))
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }

    fun syncFavoritesChannel(context: Context) {
        favSyncJob?.cancel()
        favSyncJob = scope.launch {
            delay(500)
            syncFavoritesChannelInternal(context)
        }
    }

    fun syncRecentlyWatchedChannel(context: Context) {
        recentsSyncJob?.cancel()
        recentsSyncJob = scope.launch {
            delay(500)
            syncRecentlyWatchedChannelInternal(context)
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

            syncProgramsForChannel(context, helper, channelId, items, "fav")
        } catch (e: Exception) {
            Log.e(TAG, "Failed syncing Favorites channel: ${e.message}", e)
        }
    }

    private suspend fun syncRecentlyWatchedChannelInternal(context: Context) {
        try {
            val helper = PreviewChannelHelper(context)
            val channelId = getOrCreateChannel(
                context = context,
                helper = helper,
                prefKey = KEY_CHANNEL_RECENTS,
                displayName = "Recently Watched",
                description = "Recently played media on Poobi",
                appLinkUri = "poobi://recent",
                internalId = "poobi_channel_recent",
                isDefault = false
            ) ?: return

            val prefs = context.getSharedPreferences("BrowserSettings", Context.MODE_PRIVATE)
            val recentArray = JSONArray(prefs.getString("streams_recently_played", "[]") ?: "[]")
            val items = mutableListOf<JSONObject>()
            for (i in 0 until recentArray.length().coerceAtMost(25)) {
                val entry = recentArray.getJSONObject(i)
                val item = entry.optJSONObject("item")
                if (item != null) {
                    val cloned = JSONObject(item.toString())
                    if (entry.has("season")) cloned.put("season", entry.getInt("season"))
                    if (entry.has("episode")) cloned.put("episode", entry.getInt("episode"))
                    items.add(cloned)
                }
            }

            syncProgramsForChannel(context, helper, channelId, items, "recent")
        } catch (e: Exception) {
            Log.e(TAG, "Failed syncing Recently Watched channel: ${e.message}", e)
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
            val all = helper.allChannels
            for (ch in all) {
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

    private suspend fun syncProgramsForChannel(
        context: Context,
        helper: PreviewChannelHelper,
        channelId: Long,
        items: List<JSONObject>,
        channelType: String
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

                val season = if (item.has("season")) item.optInt("season") else null
                val episode = if (item.has("episode")) item.optInt("episode") else null

                val (landscapeUri, aspectRatio) = resolveLandscapeImage(context, item, season, episode)

                val deepLink = buildDeepLinkUri(
                    mediaType = if (isTv) "tv" else "movie",
                    id = id,
                    title = title,
                    season = season,
                    episode = episode,
                    autoPlay = (channelType == "recent")
                )

                val displayTitle = if (season != null && episode != null) "$title S${season}E${episode}" else title

                val builder = PreviewProgram.Builder()
                    .setChannelId(channelId)
                    .setTitle(displayTitle)
                    .setDescription(overview)
                    .setType(if (isTv) TvContractCompat.PreviewPrograms.TYPE_TV_SERIES else TvContractCompat.PreviewPrograms.TYPE_MOVIE)
                    .setPosterArtAspectRatio(aspectRatio)
                    .setPosterArtUri(landscapeUri)
                    .setIntentUri(deepLink)
                    .setInternalProviderId("${channelType}_${id}_${season ?: 0}_${episode ?: 0}")
                    .setWeight(count - index)

                try {
                    helper.publishPreviewProgram(builder.build())
                } catch (e: Exception) {
                    Log.e(TAG, "Failed publishing program $displayTitle: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed syncing programs for channel $channelId: ${e.message}", e)
        }
    }
}