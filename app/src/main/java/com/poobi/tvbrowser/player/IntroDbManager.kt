package com.poobi.tvbrowser.player

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class TimestampSegment(val startMs: Long?, val endMs: Long?)

data class MediaTimestamps(
    val intro: List<TimestampSegment> = emptyList(),
    val recap: List<TimestampSegment> = emptyList(),
    val credits: List<TimestampSegment> = emptyList(),
    val preview: List<TimestampSegment> = emptyList()
)

class IntroDbManager(private val context: Context, private val scope: CoroutineScope) {

    @Volatile
    var mediaTimestamps: MediaTimestamps? = null
        private set

    var hasFetchedTimestamps = false
        private set

    fun reset() {
        mediaTimestamps = null
        hasFetchedTimestamps = false
    }

    fun markFetchedWithoutRequest() {
        hasFetchedTimestamps = true
    }

    fun fetchMediaTimestamps(tmdbId: Int, isTv: Boolean, season: Int?, episode: Int?, durationMs: Long) {
        if (tmdbId <= 0) return
        hasFetchedTimestamps = true
        scope.launch(Dispatchers.IO) {
            try {
                val builder = Uri.parse("https://api.theintrodb.org/v3/media").buildUpon()
                    .appendQueryParameter("tmdb_id", tmdbId.toString())
                if (isTv) {
                    if (season != null) builder.appendQueryParameter("season", season.toString())
                    if (episode != null) builder.appendQueryParameter("episode", episode.toString())
                }
                if (durationMs > 0) {
                    builder.appendQueryParameter("duration_ms", durationMs.toString())
                }
                val url = URL(builder.build().toString())
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.requestMethod = "GET"
                
                if (conn.responseCode == 200) {
                    val responseText = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(responseText)
                    
                    val introList = parseSegments(json.optJSONArray("intro"))
                    val recapList = parseSegments(json.optJSONArray("recap"))
                    val creditsList = parseSegments(json.optJSONArray("credits"))
                    val previewList = parseSegments(json.optJSONArray("preview"))
                    
                    mediaTimestamps = MediaTimestamps(introList, recapList, creditsList, previewList)
                }
            } catch (e: Exception) {
                Log.e("IntroDbManager", "Failed to fetch media timestamps", e)
            }
        }
    }

    private fun parseSegments(array: JSONArray?): List<TimestampSegment> {
        val list = mutableListOf<TimestampSegment>()
        if (array == null) return list
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val start = if (obj.isNull("start_ms")) null else obj.optLong("start_ms")
            val end = if (obj.isNull("end_ms")) null else obj.optLong("end_ms")
            if (start != null && end != null && start == 0L && end == 0L) {
                continue
            }
            list.add(TimestampSegment(start, end))
        }
        return list
    }

    fun getClosingCreditsStartMs(durationMs: Long): Long? {
        val ts = mediaTimestamps ?: return null
        for (seg in ts.credits) {
            val start = seg.startMs ?: continue
            if (durationMs > 0 && start >= durationMs * 0.6f) {
                return start
            }
        }
        return null
    }
}