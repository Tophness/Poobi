package com.poobi.tvbrowser.shared

import android.content.Context
import android.net.Uri
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class SelectedSubtitleInfo(
    val isOff: Boolean = false,
    val url: String = "",
    val label: String = "",
    val lang: String = "",
    val offsetMs: Long = 0L
)

object SubtitleCacheManager {
    private const val TAG = "SubtitleCacheManager"
    private const val PREFS_SELECTED_SUBS = "selected_subtitles_map"

    fun getCacheFile(context: Context): File {
        val dir = File(context.filesDir, "userdata/subtitles")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "cache_mapping.json")
    }

    fun getItemKey(item: JSONObject?, season: Int?, episode: Int?): String {
        if (item == null) return ""
        val id = item.optInt("id", 0).let { 
            if (it != 0) it.toString() 
            else item.optString("id").ifEmpty { item.optString("imdb") } 
        }
        if (id.isEmpty() || id == "0") return ""
        val s = season ?: 0
        val e = episode ?: 0
        return "${id}_${s}_${e}"
    }

    fun getSelectedSubtitle(context: Context, itemKey: String): SelectedSubtitleInfo? {
        if (itemKey.isEmpty()) return null
        val prefs = context.getSharedPreferences("BrowserSettings", Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(PREFS_SELECTED_SUBS, "{}") ?: "{}"
        try {
            val root = JSONObject(jsonStr)
            val specificObj = root.optJSONObject(itemKey)
            val fallbackObj = if (specificObj == null && itemKey.contains("_")) {
                root.optJSONObject("show_${itemKey.substringBefore("_")}")
            } else null
            val obj = specificObj ?: fallbackObj ?: return null
            return SelectedSubtitleInfo(
                isOff = obj.optBoolean("is_off", false),
                url = obj.optString("url", ""),
                label = obj.optString("label", ""),
                lang = obj.optString("lang", ""),
                offsetMs = specificObj?.optLong("offset_ms", 0L) ?: 0L
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed reading selected subtitle preference: ${e.message}")
            return null
        }
    }

    fun setSelectedSubtitle(context: Context, itemKey: String, info: SelectedSubtitleInfo) {
        if (itemKey.isEmpty()) return
        val prefs = context.getSharedPreferences("BrowserSettings", Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(PREFS_SELECTED_SUBS, "{}") ?: "{}"
        try {
            val root = JSONObject(jsonStr)
            val obj = JSONObject().apply {
                put("is_off", info.isOff)
                put("url", info.url)
                put("label", info.label)
                put("lang", info.lang)
                put("offset_ms", info.offsetMs)
            }
            root.put(itemKey, obj)
            if (itemKey.contains("_")) {
                val showId = itemKey.substringBefore("_")
                if (showId.isNotEmpty()) {
                    root.put("show_$showId", obj)
                }
            }
            prefs.edit().putString(PREFS_SELECTED_SUBS, root.toString()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed saving selected subtitle preference: ${e.message}")
        }
    }

    fun loadSubtitles(context: Context, itemKey: String): List<SubtitleData> {
        if (itemKey.isEmpty()) return emptyList()
        val file = getCacheFile(context)
        if (!file.exists()) return emptyList()
        try {
            val json = JSONObject(file.readText())
            if (json.has(itemKey)) {
                val array = json.optJSONArray(itemKey) ?: return emptyList()
                val results = mutableListOf<SubtitleData>()
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val urlStr = obj.getString("url")
                    val filePath = Uri.parse(urlStr).path
                    if (filePath != null && File(filePath).exists()) {
                        results.add(
                            SubtitleData(
                                url = urlStr,
                                label = obj.getString("label"),
                                lang = obj.getString("lang")
                            )
                        )
                    }
                }
                return results
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed loading subtitle cache: ${e.message}")
        }
        return emptyList()
    }

    fun saveSubtitles(context: Context, itemKey: String, subs: List<SubtitleData>) {
        if (itemKey.isEmpty() || subs.isEmpty()) return
        val file = getCacheFile(context)
        try {
            val json = if (file.exists()) JSONObject(file.readText()) else JSONObject()
            val existing = loadSubtitles(context, itemKey).toMutableList()
            subs.forEach { newSub ->
                if (existing.none { it.url == newSub.url }) {
                    existing.add(newSub)
                }
            }
            val array = JSONArray()
            existing.forEach { sub ->
                array.put(JSONObject().apply {
                    put("url", sub.url)
                    put("label", sub.label)
                    put("lang", sub.lang)
                })
            }
            json.put(itemKey, array)
            file.writeText(json.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed saving subtitle cache: ${e.message}")
        }
    }

    fun removeSubtitles(context: Context, itemKey: String) {
        if (itemKey.isEmpty()) return
        val file = getCacheFile(context)
        if (!file.exists()) return
        try {
            val json = JSONObject(file.readText())
            if (json.has(itemKey)) {
                json.remove(itemKey)
                file.writeText(json.toString())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed removing subtitle cache: ${e.message}")
        }
    }
}