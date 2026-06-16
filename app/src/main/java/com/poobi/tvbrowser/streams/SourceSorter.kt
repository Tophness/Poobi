package com.poobi.tvbrowser.streams

import org.json.JSONArray
import org.json.JSONObject

enum class SortCriteria {
    NATIVE,
    RESOLUTION,
    DIRECT,
    SOURCE
}

class SourceSorter(private val priorities: List<SortCriteria>) {

    companion object {
        val DEFAULT_PRIORITIES = listOf(
            SortCriteria.NATIVE,
            SortCriteria.DIRECT,
            SortCriteria.RESOLUTION,
            SortCriteria.SOURCE
        )

        private val qualityMap = mapOf(
            "4k" to 0,
            "2160p" to 0,
            "1080p" to 1,
            "720p" to 2,
            "hd" to 2,
            "sd" to 3,
            "480p" to 3,
            "360p" to 4,
            "cam" to 5,
            "scr" to 5
        )

        fun getQualityValue(quality: String?): Int {
            if (quality == null) return 4
            val q = quality.lowercase()
            return qualityMap.entries.find { q.contains(it.key) }?.value ?: 4
        }
    }

    fun sort(sources: JSONArray): JSONArray {
        val list = mutableListOf<Pair<JSONObject, JSONObject>>()
        for (i in 0 until sources.length()) {
            val wrapper = sources.getJSONObject(i)
            val data = JSONObject(wrapper.getString("source_data"))
            list.add(Pair(wrapper, data))
        }

        list.sortWith { pairA, pairB ->
            val dataA = pairA.second
            val dataB = pairB.second

            var result = 0
            for (criteria in priorities) {
                result = when (criteria) {
                    SortCriteria.NATIVE -> compareNative(dataA, dataB)
                    SortCriteria.RESOLUTION -> compareResolution(dataA, dataB)
                    SortCriteria.DIRECT -> compareDirect(dataA, dataB)
                    SortCriteria.SOURCE -> compareSource(dataA, dataB)
                }
                if (result != 0) break
            }
            result
        }

        val sortedArray = JSONArray()
        list.forEach { sortedArray.put(it.first) }
        return sortedArray
    }

    private fun compareNative(a: JSONObject, b: JSONObject): Int {
        val isNativeA = isNativePlayable(a)
        val isNativeB = isNativePlayable(b)
        return if (isNativeA == isNativeB) 0 else if (isNativeA) -1 else 1
    }

    private fun isNativePlayable(data: JSONObject): Boolean {
        if (data.optBoolean("is_video", false)) return true
        if (data.optBoolean("direct", false)) return true
        
        val url = data.optString("url", "").lowercase()
        val videoExtensions = listOf(".m3u8", ".mp4", ".mkv", ".ts", ".webm", ".mpd", ".avi", ".flv", ".mov")
        if (videoExtensions.any { url.split("?")[0].endsWith(it) } || 
            url.contains("/hls/") || 
            url.contains("m3u8") || 
            url.contains("mpd")) return true
        
        return false
    }

    private fun compareResolution(a: JSONObject, b: JSONObject): Int {
        val qA = getQualityValue(a.optString("quality"))
        val qB = getQualityValue(b.optString("quality"))
        return qA.compareTo(qB)
    }

    private fun compareDirect(a: JSONObject, b: JSONObject): Int {
        val isDirectA = a.optBoolean("direct", false)
        val isDirectB = b.optBoolean("direct", false)
        return if (isDirectA == isDirectB) 0 else if (isDirectA) -1 else 1
    }

    private fun compareSource(a: JSONObject, b: JSONObject): Int {
        val sA = a.optString("source", "").lowercase()
        val sB = b.optString("source", "").lowercase()
        return sA.compareTo(sB)
    }
}
