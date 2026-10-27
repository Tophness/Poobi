package com.poobi.tvbrowser.shared

import android.content.Context
import com.chaquo.python.Python
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class FavoriteActor(
    val id: Int,
    val name: String,
    val profilePath: String,
    var isEnabled: Boolean = true
)

object FavoriteActorsManager {
    private const val PREFS_KEY_ACTORS = "favorite_actors_pool"
    private const val SETTINGS_FILE = "BrowserSettings"

    fun getActors(context: Context): List<FavoriteActor> {
        val prefs = context.getSharedPreferences(SETTINGS_FILE, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(PREFS_KEY_ACTORS, "[]") ?: "[]"
        val list = mutableListOf<FavoriteActor>()
        try {
            val arr = JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    FavoriteActor(
                        id = obj.optInt("id"),
                        name = obj.optString("name"),
                        profilePath = obj.optString("profile_path", ""),
                        isEnabled = obj.optBoolean("is_enabled", true)
                    )
                )
            }
        } catch (_: Exception) {}
        return list.sortedBy { it.name.lowercase() }
    }

    fun saveActors(context: Context, actors: List<FavoriteActor>) {
        val prefs = context.getSharedPreferences(SETTINGS_FILE, Context.MODE_PRIVATE)
        val arr = JSONArray()
        actors.forEach { actor ->
            arr.put(JSONObject().apply {
                put("id", actor.id)
                put("name", actor.name)
                put("profile_path", actor.profilePath)
                put("is_enabled", actor.isEnabled)
            })
        }
        prefs.edit().putString(PREFS_KEY_ACTORS, arr.toString()).apply()
    }

    fun toggleActor(context: Context, actorId: Int, isEnabled: Boolean) {
        val actors = getActors(context).toMutableList()
        val index = actors.indexOfFirst { it.id == actorId }
        if (index != -1) {
            actors[index] = actors[index].copy(isEnabled = isEnabled)
            saveActors(context, actors)
        }
    }

    fun setAllActors(context: Context, isEnabled: Boolean) {
        val actors = getActors(context).map { it.copy(isEnabled = isEnabled) }
        saveActors(context, actors)
    }

    fun getSelectedActorIds(context: Context): List<Int> {
        return getActors(context).filter { it.isEnabled }.map { it.id }
    }

    suspend fun syncWithFavorites(context: Context) = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(SETTINGS_FILE, Context.MODE_PRIVATE)
        val favsRaw = prefs.getString("streams_favorites", "[]") ?: "[]"
        val favsArray = try { JSONArray(favsRaw) } catch (_: Exception) { JSONArray() }
        if (favsArray.length() == 0) return@withContext

        val existingActors = getActors(context).toMutableList()
        val existingMap = existingActors.associateBy { it.id }.toMutableMap()
        var hasNewActors = false

        for (i in 0 until favsArray.length()) {
            val item = favsArray.optJSONObject(i) ?: continue
            val id = item.optString("id").ifEmpty { item.optString("imdb") }
            val mediaType = item.optString("media_type").ifEmpty { if (item.has("name")) "tv" else "movie" }

            var details = AppCacheManager.getDetails(context, mediaType, id)
            if (details == null && id.isNotEmpty() && id != "0") {
                try {
                    val py = Python.getInstance()
                    val tmdb = py.getModule("tmdb.tmdb_utils")
                    val dStr = tmdb.callAttr("get_details", id, mediaType).toString()
                    details = JSONObject(dStr)
                    AppCacheManager.saveDetails(context, mediaType, id, details)
                } catch (_: Exception) {}
            }

            val cast = details?.optJSONObject("credits")?.optJSONArray("cast")
            if (cast != null) {
                for (cIdx in 0 until cast.length().coerceAtMost(6)) {
                    val actorObj = cast.getJSONObject(cIdx)
                    val actorId = actorObj.optInt("id")
                    val actorName = actorObj.optString("name")
                    val profilePath = actorObj.optString("profile_path", "")

                    if (actorId > 0 && actorName.isNotBlank() && !existingMap.containsKey(actorId)) {
                        val newActor = FavoriteActor(actorId, actorName, profilePath, isEnabled = true)
                        existingMap[actorId] = newActor
                        hasNewActors = true
                    }
                }
            }
        }

        if (hasNewActors) {
            saveActors(context, existingMap.values.toList())
        }
    }
}