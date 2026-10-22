package com.poobi.tvbrowser.shared

import org.json.JSONArray
import org.json.JSONObject

object TmdbGenres {
    private val genres = mapOf(
        28 to "Action", 12 to "Adventure", 16 to "Animation", 35 to "Comedy",
        80 to "Crime", 99 to "Documentary", 18 to "Drama", 10751 to "Family",
        14 to "Fantasy", 36 to "History", 27 to "Horror", 10402 to "Music",
        9648 to "Mystery", 10749 to "Romance", 878 to "Sci-Fi", 10770 to "TV Movie",
        53 to "Thriller", 10752 to "War", 37 to "Western",
        10759 to "Action & Adventure", 10762 to "Kids", 10763 to "News",
        10764 to "Reality", 10765 to "Sci-Fi & Fantasy", 10766 to "Soap",
        10767 to "Talk", 10768 to "War & Politics"
    )

    fun getGenreName(id: Int): String? = genres[id]

    fun formatGenres(genresArr: JSONArray?): String {
        if (genresArr == null || genresArr.length() == 0) return ""
        val names = mutableListOf<String>()
        for (i in 0 until genresArr.length()) {
            val obj = genresArr.opt(i)
            if (obj is JSONObject) {
                val n = obj.optString("name")
                if (n.isNotBlank()) names.add(n)
            } else if (obj is Int) {
                genres[obj]?.let { names.add(it) }
            }
        }
        return names.joinToString(" • ")
    }
}