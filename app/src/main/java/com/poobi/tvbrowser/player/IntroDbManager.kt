package com.poobi.tvbrowser.player

import android.content.Context
import android.net.Uri
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    private val skippedSegments = mutableSetOf<String>()

    fun reset() {
        mediaTimestamps = null
        hasFetchedTimestamps = false
        skippedSegments.clear()
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
                    Log.d("IntroDbManager", "Fetched timestamps successfully: $mediaTimestamps")
                } else {
                    Log.w("IntroDbManager", "Timestamps API returned code: ${conn.responseCode}")
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
            list.add(TimestampSegment(start, end))
        }
        return list
    }

    fun checkAndSkipSegments(pos: Long, player: androidx.media3.exoplayer.ExoPlayer): Boolean {
        val ts = mediaTimestamps ?: return false
        
        for (seg in ts.intro) {
            val start = seg.startMs ?: 0L
            val end = seg.endMs
            if (end != null && pos in start..end) {
                val segId = "intro_${start}_$end"
                if (!skippedSegments.contains(segId)) {
                    skippedSegments.add(segId)
                    player.seekTo(end)
                    Toast.makeText(context, "Skipped Intro", Toast.LENGTH_SHORT).show()
                    return true
                }
            }
        }
        
        for (seg in ts.recap) {
            val start = seg.startMs ?: 0L
            val end = seg.endMs
            if (end != null && pos in start..end) {
                val segId = "recap_${start}_$end"
                if (!skippedSegments.contains(segId)) {
                    skippedSegments.add(segId)
                    player.seekTo(end)
                    Toast.makeText(context, "Skipped Recap", Toast.LENGTH_SHORT).show()
                    return true
                }
            }
        }
        return false
    }

    fun getCreditsStartMs(): Long? {
        return mediaTimestamps?.credits?.firstOrNull { it.startMs != null }?.startMs
    }
}