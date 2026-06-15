package com.poobi.tvbrowser.torrent

import org.json.JSONArray
import org.json.JSONObject
import com.poobi.tvbrowser.shared.LanguageHelper
import com.poobi.tvbrowser.streams.SourceSorter

enum class TorrentSortCriteria {
    LANGUAGE,
    SIZE,
    SEEDERS,
    RESOLUTION
}

class TorrentSorter(
    private val priorities: List<TorrentSortCriteria>,
    private val preferredLanguage: String
) {

    companion object {
        val DEFAULT_PRIORITIES = listOf(
            TorrentSortCriteria.LANGUAGE,
            TorrentSortCriteria.SIZE,
            TorrentSortCriteria.SEEDERS,
            TorrentSortCriteria.RESOLUTION
        )

        fun parseSizeToBytes(title: String): Long {
            val sizeRegex = """💾\s*([\d\.]+)\s*(GB|MB|KB)""".toRegex(RegexOption.IGNORE_CASE)
            val match = sizeRegex.find(title) ?: return Long.MAX_VALUE
            val value = match.groupValues[1].toDoubleOrNull() ?: return Long.MAX_VALUE
            val unit = match.groupValues[2].uppercase()
            return when (unit) {
                "GB" -> (value * 1024 * 1024 * 1024).toLong()
                "MB" -> (value * 1024 * 1024).toLong()
                "KB" -> (value * 1024).toLong()
                else -> Long.MAX_VALUE
            }
        }

        fun matchesPreferredLanguage(title: String, data: JSONObject, preferredLanguage: String): Boolean {
            val langCode = when (preferredLanguage.lowercase()) {
                "english" -> "en"
                "russian" -> "ru"
                "spanish" -> "es"
                "portuguese" -> "pt"
                "italian" -> "it"
                "french" -> "fr"
                "german" -> "de"
                "polish" -> "pl"
                "hindi" -> "hi"
                else -> "en"
            }
            val dataLang = data.optString("language", "").lowercase()
            if (dataLang == langCode) return true
            
            val keywords = LanguageHelper.getPrioritizationKeywords(preferredLanguage)
            return keywords.any { title.contains(it, ignoreCase = true) }
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
            val wrapperA = pairA.first
            val wrapperB = pairB.first
            val dataA = pairA.second
            val dataB = pairB.second

            val titleA = wrapperA.optString("title", "")
            val titleB = wrapperB.optString("title", "")

            var result = 0
            for (criteria in priorities) {
                result = when (criteria) {
                    TorrentSortCriteria.LANGUAGE -> {
                        val matchA = matchesPreferredLanguage(titleA, dataA, preferredLanguage)
                        val matchB = matchesPreferredLanguage(titleB, dataB, preferredLanguage)
                        if (matchA == matchB) 0 else if (matchA) -1 else 1
                    }
                    TorrentSortCriteria.SIZE -> {
                        val sizeA = parseSizeToBytes(titleA)
                        val sizeB = parseSizeToBytes(titleB)
                        sizeA.compareTo(sizeB)
                    }
                    TorrentSortCriteria.SEEDERS -> {
                        val seedersA = dataA.optInt("seeders", 0)
                        val seedersB = dataB.optInt("seeders", 0)
                        seedersB.compareTo(seedersA) // Highest seeders first
                    }
                    TorrentSortCriteria.RESOLUTION -> {
                        val qA = SourceSorter.getQualityValue(dataA.optString("quality"))
                        val qB = SourceSorter.getQualityValue(dataB.optString("quality"))
                        qA.compareTo(qB)
                    }
                }
                if (result != 0) break
            }
            result
        }

        val sortedArray = JSONArray()
        list.forEach { sortedArray.put(it.first) }
        return sortedArray
    }
}