package com.poobi.tvbrowser.tvhome

import android.app.SearchManager
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.BaseColumns
import com.chaquo.python.Python
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

class PoobiSearchProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "com.poobi.tvbrowser.search"

        private val COLUMNS = arrayOf(
            BaseColumns._ID,
            SearchManager.SUGGEST_COLUMN_TEXT_1,
            SearchManager.SUGGEST_COLUMN_TEXT_2,
            SearchManager.SUGGEST_COLUMN_RESULT_CARD_IMAGE,
            SearchManager.SUGGEST_COLUMN_INTENT_DATA,
            SearchManager.SUGGEST_COLUMN_INTENT_ACTION,
            SearchManager.SUGGEST_COLUMN_CONTENT_TYPE,
            SearchManager.SUGGEST_COLUMN_PRODUCTION_YEAR,
            SearchManager.SUGGEST_COLUMN_DURATION
        )
    }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val query = if (selectionArgs != null && selectionArgs.isNotEmpty()) {
            selectionArgs[0]
        } else {
            uri.lastPathSegment?.takeIf { it != "search_suggest_query" }
        } ?: ""

        val cursor = MatrixCursor(COLUMNS)
        if (query.isBlank()) return cursor

        val cleanQuery = query.trim().lowercase()
        val context = context ?: return cursor
        val prefs = context.getSharedPreferences("BrowserSettings", Context.MODE_PRIVATE)

        val seenIds = mutableSetOf<String>()
        var rowId = 1L

        fun addRow(item: JSONObject) {
            val id = item.optString("id").ifEmpty { item.optString("imdb") }
            if (id.isEmpty() || seenIds.contains(id)) return
            seenIds.add(id)

            val titleRaw = item.optString("title").ifEmpty { item.optString("name", "Unknown") }
            val title = titleRaw.replace(Regex("\\s\\(\\d{4}\\)$"), "")
            val mediaType = item.optString("media_type").ifEmpty { if (item.has("name")) "tv" else "movie" }
            val year = (item.optString("release_date").ifEmpty { item.optString("first_air_date") }).take(4)
            val posterPath = item.optString("poster_path")
            val posterUrl = if (posterPath.isNotBlank() && posterPath != "null") {
                if (posterPath.startsWith("http")) posterPath else "https://image.tmdb.org/t/p/w500$posterPath"
            } else ""

            val deepLink = TvHomeSyncManager.buildDeepLinkUri(
                mediaType = mediaType,
                id = id,
                title = title,
                autoPlay = (mediaType != "tv")
            ).toString()

            cursor.addRow(
                arrayOf(
                    rowId++,
                    title,
                    if (year.isNotEmpty()) "$year • ${mediaType.uppercase()}" else mediaType.uppercase(),
                    posterUrl,
                    deepLink,
                    "android.intent.action.VIEW",
                    "video/mp4",
                    year,
                    0L
                )
            )
        }

        try {
            val favs = JSONArray(prefs.getString("streams_favorites", "[]") ?: "[]")
            for (i in 0 until favs.length()) {
                val it = favs.getJSONObject(i)
                val t = it.optString("title").ifEmpty { it.optString("name") }
                if (t.lowercase().contains(cleanQuery)) {
                    addRow(it)
                }
            }

            val recents = JSONArray(prefs.getString("streams_recently_played", "[]") ?: "[]")
            for (i in 0 until recents.length()) {
                val it = recents.getJSONObject(i).optJSONObject("item") ?: continue
                val t = it.optString("title").ifEmpty { it.optString("name") }
                if (t.lowercase().contains(cleanQuery)) {
                    addRow(it)
                }
            }
        } catch (_: Exception) {}

        if (cursor.count < 5 && cleanQuery.length >= 3 && Python.isStarted()) {
            try {
                runBlocking(Dispatchers.IO) {
                    withTimeoutOrNull(1200L) {
                        val py = Python.getInstance()
                        val tmdb = py.getModule("tmdb.tmdb_utils")
                        val resultsJson = tmdb.callAttr("search", cleanQuery).toString()
                        val results = JSONObject(resultsJson).optJSONArray("results")
                        if (results != null) {
                            for (i in 0 until results.length().coerceAtMost(6)) {
                                addRow(results.getJSONObject(i))
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        return cursor
    }

    override fun getType(uri: Uri): String = SearchManager.SUGGEST_MIME_TYPE

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}