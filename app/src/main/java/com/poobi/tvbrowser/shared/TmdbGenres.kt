package com.poobi.tvbrowser.shared

import android.content.Context
import com.poobi.tvbrowser.R
import org.json.JSONArray
import org.json.JSONObject

object TmdbGenres {
    val genreResMap = mapOf(
        28 to R.string.genre_action,
        12 to R.string.genre_adventure,
        16 to R.string.genre_animation,
        35 to R.string.genre_comedy,
        80 to R.string.genre_crime,
        99 to R.string.genre_documentary,
        18 to R.string.genre_drama,
        10751 to R.string.genre_family,
        14 to R.string.genre_fantasy,
        36 to R.string.genre_history,
        27 to R.string.genre_horror,
        10402 to R.string.genre_music,
        9648 to R.string.genre_mystery,
        10749 to R.string.genre_romance,
        878 to R.string.genre_scifi,
        10770 to R.string.genre_tv_movie,
        53 to R.string.genre_thriller,
        10752 to R.string.genre_war,
        37 to R.string.genre_western,
        10759 to R.string.genre_action_adventure,
        10762 to R.string.genre_kids,
        10763 to R.string.genre_news,
        10764 to R.string.genre_reality,
        10765 to R.string.genre_scifi_fantasy,
        10766 to R.string.genre_soap,
        10767 to R.string.genre_talk,
        10768 to R.string.genre_war_politics
    )

    fun getGenreName(context: Context?, id: Int): String? {
        val resId = genreResMap[id] ?: return null
        return context?.getString(resId)
    }

    fun formatGenres(genresArr: JSONArray?, context: Context? = null): String {
        if (genresArr == null || genresArr.length() == 0) return ""
        val names = mutableListOf<String>()
        val ctx = context ?: LanguageHelper.applicationContext

        for (i in 0 until genresArr.length()) {
            val obj = genresArr.opt(i)
            if (obj is JSONObject) {
                val id = obj.optInt("id", -1)
                val localized = if (id != -1) getGenreName(ctx, id) else null
                val n = localized ?: obj.optString("name")
                if (n.isNotBlank()) names.add(n)
            } else if (obj is Int) {
                val localized = getGenreName(ctx, obj)
                if (localized != null) names.add(localized)
            }
        }
        return names.joinToString(" • ")
    }
}