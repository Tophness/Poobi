package com.poobi.tvbrowser.streams

import androidx.compose.runtime.Immutable
import com.poobi.tvbrowser.shared.TmdbGenres
import org.json.JSONObject

@Immutable
data class SpotlightActor(
    val id: Int = 0,
    val name: String = "",
    val profilePath: String = ""
)

@Immutable
data class MediaItem(
    val id: String,
    val imdb: String = "",
    val title: String,
    val year: String = "0000",
    val mediaType: String = "movie",
    val rating: Double = 0.0,
    val posterPath: String? = null,
    val backdropPath: String? = null,
    val stillPath: String? = null,
    val overview: String = "",
    val genresStr: String = "",
    val isTrakt: Boolean = false,
    val traktBadge: String = "",
    val spotlightActors: List<SpotlightActor> = emptyList(),
    val spotlightActorProfile: String? = null,
    val spotlightActorName: String? = null,
    val character: String? = null,
    val rawJson: JSONObject = JSONObject()
) {
    fun getImageUrl(isLandscape: Boolean = false): String {
        val path = if (isLandscape) {
            stillPath ?: backdropPath ?: posterPath
        } else {
            stillPath ?: posterPath ?: backdropPath
        }
        return if (!path.isNullOrBlank() && path != "null") {
            if (path.startsWith("http")) path 
            else if (isLandscape) "https://image.tmdb.org/t/p/w780$path"
            else "https://image.tmdb.org/t/p/w342$path"
        } else ""
    }

    val imageUrl: String
        get() = getImageUrl(isLandscape = false)

    companion object {
        fun fromJson(json: JSONObject, season: Int? = null, episode: Int? = null): MediaItem {
            val itm = if (json.has("item")) json.optJSONObject("item") ?: json else json
            val id = itm.optString("id").ifEmpty { itm.optString("imdb", "") }
            val imdb = itm.optString("imdb", "")
            val rawTitle = (itm.optString("title").takeIf { it.isNotEmpty() } ?: itm.optString("name", ""))
                .ifEmpty { json.optString("display_title", "Unknown") }
            val title = rawTitle.replace(Regex("\\s\\(\\d{4}\\)$"), "").trim()
            val releaseDate = itm.optString("release_date").takeIf { !it.isNullOrEmpty() }
                ?: itm.optString("first_air_date").takeIf { !it.isNullOrEmpty() }
                ?: "0000"
            val year = if (releaseDate.length >= 4) releaseDate.substring(0, 4) else "0000"
            val mediaType = itm.optString("media_type").takeIf { it.isNotEmpty() && it != "null" }
                ?: if (itm.has("name") || itm.has("first_air_date")) "tv" else "movie"
            val rating = itm.optDouble("vote_average", 0.0)
            val posterPath = itm.optString("poster_path").takeIf { it.isNotBlank() && it != "null" }
            val backdropPath = itm.optString("backdrop_path").takeIf { it.isNotBlank() && it != "null" }

            val isEpisode = (season != null && episode != null) ||
                    (json.has("season") && json.has("episode")) ||
                    (itm.has("season") && itm.has("episode"))
            val stillPath = if (isEpisode) {
                itm.optString("still_path").takeIf { it.isNotBlank() && it != "null" }
                    ?: json.optString("still_path").takeIf { it.isNotBlank() && it != "null" }
            } else null

            val overview = itm.optString("overview", "")
            val genresArr = itm.optJSONArray("genre_ids") ?: itm.optJSONArray("genres")
            val genresStr = TmdbGenres.formatGenres(genresArr)

            val isTrakt = itm.optBoolean("is_trakt", false) || json.optBoolean("is_trakt", false)
            val traktBadge = itm.optString("trakt_badge").ifEmpty { json.optString("trakt_badge", "") }

            val actorsList = mutableListOf<SpotlightActor>()
            val actorsArr = itm.optJSONArray("spotlight_actors") ?: json.optJSONArray("spotlight_actors")
            if (actorsArr != null) {
                for (i in 0 until actorsArr.length()) {
                    val aObj = actorsArr.optJSONObject(i) ?: continue
                    val aName = aObj.optString("name")
                    val aProfile = aObj.optString("profile_path")
                    val aId = aObj.optInt("id", 0)
                    if (aName.isNotEmpty() || aProfile.isNotEmpty()) {
                        actorsList.add(SpotlightActor(id = aId, name = aName, profilePath = aProfile))
                    }
                }
            }
            if (actorsList.isEmpty()) {
                val singleProfile = itm.optString("spotlight_actor_profile").takeIf { it.isNotBlank() && it != "null" }
                    ?: json.optString("spotlight_actor_profile").takeIf { it.isNotBlank() && it != "null" }
                val singleName = itm.optString("spotlight_actor_name").takeIf { it.isNotBlank() && it != "null" }
                    ?: json.optString("spotlight_actor_name").takeIf { it.isNotBlank() && it != "null" }
                if (!singleName.isNullOrEmpty() || !singleProfile.isNullOrEmpty()) {
                    actorsList.add(SpotlightActor(name = singleName ?: "", profilePath = singleProfile ?: ""))
                }
            }

            val spotlightActorProfile = actorsList.firstOrNull()?.profilePath?.takeIf { it.isNotBlank() && it != "null" }
            val spotlightActorName = actorsList.firstOrNull()?.name?.takeIf { it.isNotBlank() && it != "null" }
            val character = itm.optString("character").takeIf { it.isNotBlank() && it != "null" }

            return MediaItem(
                id = id,
                imdb = imdb,
                title = title,
                year = year,
                mediaType = mediaType,
                rating = rating,
                posterPath = posterPath,
                backdropPath = backdropPath,
                stillPath = stillPath,
                overview = overview,
                genresStr = genresStr,
                isTrakt = isTrakt,
                traktBadge = traktBadge,
                spotlightActors = actorsList,
                spotlightActorProfile = spotlightActorProfile,
                spotlightActorName = spotlightActorName,
                character = character,
                rawJson = itm
            )
        }
    }
}