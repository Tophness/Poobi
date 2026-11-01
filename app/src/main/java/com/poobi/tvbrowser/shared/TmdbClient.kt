package com.poobi.tvbrowser.shared

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object TmdbClient {
    private const val API_URL = "https://api.themoviedb.org/3"
    private const val READ_TOKEN = "eyJhbGciOiJIUzI1NiJ9.eyJhdWQiOiI1NWQ0ZWEwYWNiMDRhMTAwNTNjMmJlNjM3ZWI3MDdhOSIsIm5iZiI6MTc2NTAzMzYyMi4yNzgsInN1YiI6IjY5MzQ0Njk2MGY0YjhlN2QwODA1Y2U3MCIsInNjb3BlcyI6WyJhcGlfcmVhZCJdLCJ2ZXJzaW9uIjoxfQ.phA7fC-bjtOcl_BHzESyOU4L__3eAhM1c5Nb4A2VK2A"

    private fun getLanguage(context: Context): String {
        val lang = LanguageHelper.getAppLanguage(context).code
        return if (lang.isNotBlank() && lang != "system") lang else "en"
    }

    private suspend fun get(endpoint: String, params: Map<String, String> = emptyMap()): JSONObject = withContext(Dispatchers.IO) {
        val queryBuilder = StringBuilder()
        params.forEach { (k, v) ->
            if (queryBuilder.isNotEmpty()) queryBuilder.append("&")
            queryBuilder.append(URLEncoder.encode(k, "UTF-8")).append("=").append(URLEncoder.encode(v, "UTF-8"))
        }

        val fullUrl = if (queryBuilder.isNotEmpty()) "$API_URL$endpoint?$queryBuilder" else "$API_URL$endpoint"
        val connection = URL(fullUrl).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 8000
        connection.readTimeout = 12000
        connection.setRequestProperty("Authorization", "Bearer $READ_TOKEN")
        connection.setRequestProperty("Content-Type", "application/json;charset=utf-8")

        val responseCode = connection.responseCode
        if (responseCode in 200..299) {
            val reader = BufferedReader(InputStreamReader(connection.inputStream, "UTF-8"))
            val response = reader.use { it.readText() }
            JSONObject(response)
        } else {
            JSONObject()
        }
    }

    suspend fun getMoviesInCinemas(context: Context, page: Int = 1): JSONArray {
        val res = get("/movie/now_playing", mapOf("page" to page.toString(), "language" to getLanguage(context)))
        return res.optJSONArray("results") ?: JSONArray()
    }

    suspend fun getTrending(context: Context, mediaType: String = "all", timeWindow: String = "day", page: Int = 1): JSONArray {
        val res = get("/trending/$mediaType/$timeWindow", mapOf("page" to page.toString(), "language" to getLanguage(context)))
        return res.optJSONArray("results") ?: JSONArray()
    }

    suspend fun getTrendingWeek(context: Context, mediaType: String = "all", page: Int = 1): JSONArray {
        val res = get("/trending/$mediaType/week", mapOf("page" to page.toString(), "language" to getLanguage(context)))
        return res.optJSONArray("results") ?: JSONArray()
    }

    suspend fun getPopular(context: Context, mediaType: String = "movie", page: Int = 1): JSONArray {
        val res = get("/$mediaType/popular", mapOf("page" to page.toString(), "language" to getLanguage(context)))
        return res.optJSONArray("results") ?: JSONArray()
    }

    suspend fun getTopRated(context: Context, mediaType: String = "movie", page: Int = 1): JSONArray {
        val res = get("/$mediaType/top_rated", mapOf("page" to page.toString(), "language" to getLanguage(context)))
        return res.optJSONArray("results") ?: JSONArray()
    }

    suspend fun getTopRatedTv(context: Context, page: Int = 1): JSONArray {
        val res = get("/tv/top_rated", mapOf("page" to page.toString(), "language" to getLanguage(context)))
        return res.optJSONArray("results") ?: JSONArray()
    }

    suspend fun getUpcomingMovies(context: Context, page: Int = 1): JSONArray {
        val res = get("/movie/upcoming", mapOf("page" to page.toString(), "language" to getLanguage(context)))
        return res.optJSONArray("results") ?: JSONArray()
    }

    suspend fun getAiringToday(context: Context, page: Int = 1): JSONArray {
        val res = get("/tv/airing_today", mapOf("page" to page.toString(), "language" to getLanguage(context)))
        return res.optJSONArray("results") ?: JSONArray()
    }

    suspend fun getOnTheAir(context: Context, page: Int = 1): JSONArray {
        val res = get("/tv/on_the_air", mapOf("page" to page.toString(), "language" to getLanguage(context)))
        return res.optJSONArray("results") ?: JSONArray()
    }

    suspend fun discoverMovies(context: Context, params: JSONObject, page: Int = 1): JSONObject {
        val map = mutableMapOf("page" to page.toString(), "language" to getLanguage(context))
        params.keys().forEach { k -> map[k] = params.getString(k) }
        return get("/discover/movie", map)
    }

    suspend fun discoverTv(context: Context, params: JSONObject, page: Int = 1): JSONObject {
        val map = mutableMapOf("page" to page.toString(), "language" to getLanguage(context))
        params.keys().forEach { k -> map[k] = params.getString(k) }
        return get("/discover/tv", map)
    }

    suspend fun getRecommendations(context: Context, tmdbId: String, mediaType: String = "movie", page: Int = 1): JSONArray {
        val res = get("/$mediaType/$tmdbId/recommendations", mapOf("page" to page.toString(), "language" to getLanguage(context)))
        return res.optJSONArray("results") ?: JSONArray()
    }

    suspend fun getDetails(context: Context, tmdbId: String, mediaType: String = "movie"): JSONObject {
        val lang = getLanguage(context)
        val appendTo = if (mediaType == "tv" || mediaType == "tvshow") {
            "aggregate_credits,credits,videos,images,external_ids,recommendations,content_ratings"
        } else {
            "credits,videos,images,external_ids,recommendations,release_dates"
        }
        val res = get("/${if (mediaType == "tvshow") "tv" else mediaType}/$tmdbId", mapOf(
            "append_to_response" to appendTo,
            "language" to lang,
            "include_image_language" to "$lang,en,null"
        ))
        if (mediaType == "tv" || mediaType == "tvshow") {
            val agg = res.optJSONObject("aggregate_credits")
            val aggCast = agg?.optJSONArray("cast")
            if (aggCast != null) {
                for (i in 0 until aggCast.length()) {
                    val actor = aggCast.getJSONObject(i)
                    if (!actor.has("character") && actor.has("roles")) {
                        val roles = actor.optJSONArray("roles")
                        if (roles != null && roles.length() > 0) {
                            val roleNames = mutableListOf<String>()
                            for (r in 0 until roles.length()) {
                                val c = roles.getJSONObject(r).optString("character")
                                if (c.isNotEmpty()) roleNames.add(c)
                            }
                            actor.put("character", roleNames.joinToString(" / "))
                        }
                    }
                }
                if (!res.has("credits")) res.put("credits", JSONObject())
                res.getJSONObject("credits").put("cast", aggCast)
            }
        }
        return res
    }

    suspend fun getSeasonCredits(tvId: String, seasonNumber: Int): JSONArray {
        val res = get("/tv/$tvId/season/$seasonNumber/credits")
        return res.optJSONArray("cast") ?: JSONArray()
    }

    suspend fun getTvEpisodes(context: Context, tvId: String, seasonNumber: Int): JSONArray {
        val res = get("/tv/$tvId/season/$seasonNumber", mapOf("language" to getLanguage(context)))
        return res.optJSONArray("episodes") ?: JSONArray()
    }

    suspend fun getPersonDetails(context: Context, personId: Int): JSONObject {
        return get("/person/$personId", mapOf(
            "append_to_response" to "combined_credits,images",
            "language" to getLanguage(context)
        ))
    }

    suspend fun getExternalIds(tmdbId: String, mediaType: String): JSONObject {
        val path = if (mediaType == "tvshow") "tv" else mediaType
        return get("/$path/$tmdbId/external_ids")
    }

    suspend fun search(context: Context, query: String, page: Int = 1): JSONArray {
        val res = get("/search/multi", mapOf(
            "query" to query,
            "page" to page.toString(),
            "language" to getLanguage(context)
        ))
        return res.optJSONArray("results") ?: JSONArray()
    }
}