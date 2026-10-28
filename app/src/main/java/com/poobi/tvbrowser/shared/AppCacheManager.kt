package com.poobi.tvbrowser.shared

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import android.util.LruCache
import androidx.tvprovider.media.tv.TvContractCompat
import com.poobi.tvbrowser.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

object AppCacheManager {
    private const val TAG = "AppCacheManager"

    private const val MAX_IMAGE_CACHE_BYTES = 80L * 1024L * 1024L // 80 MB limit
    private const val MAX_JSON_CACHE_BYTES = 20L * 1024L * 1024L  // 20 MB limit

    private val ramMemoryMax = (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt().coerceAtLeast(4096)
    val memoryBitmapCache = object : LruCache<String, Bitmap>(ramMemoryMax) {
        override fun sizeOf(key: String, bitmap: Bitmap): Int = bitmap.byteCount / 1024
    }

    private val detailsMemCache = LruCache<String, JSONObject>(40)
    private val episodesMemCache = LruCache<String, JSONArray>(60)
    private val libraryMemCache = LruCache<String, JSONArray>(20)

    private fun md5(str: String): String {
        val digest = MessageDigest.getInstance("MD5")
        val bytes = digest.digest(str.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun getImageDir(context: Context): File =
        File(context.cacheDir, "image_cache").apply { if (!exists()) mkdirs() }

    private fun getJsonDir(context: Context): File =
        File(context.cacheDir, "tmdb_json_cache").apply { if (!exists()) mkdirs() }

    // --- BITMAP IMAGE CACHE (DISK + RAM) ---

    fun getImageBitmap(context: Context, url: String): Bitmap? {
        if (url.isBlank()) return null
        val mem = memoryBitmapCache.get(url)
        if (mem != null && !mem.isRecycled) return mem

        val file = File(getImageDir(context), md5(url))
        if (file.exists() && file.length() > 0) {
            try {
                file.setLastModified(System.currentTimeMillis())
                val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                if (bitmap != null) {
                    memoryBitmapCache.put(url, bitmap)
                    return bitmap
                }
            } catch (e: Exception) {
                file.delete()
            }
        }
        return null
    }

    suspend fun saveImageBytes(context: Context, url: String, bytes: ByteArray): Bitmap? = withContext(Dispatchers.IO) {
        if (bytes.isEmpty()) return@withContext null
        val file = File(getImageDir(context), md5(url))
        try {
            FileOutputStream(file).use { it.write(bytes) }
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (bitmap != null) {
                memoryBitmapCache.put(url, bitmap)
            }
            pruneCacheDir(getImageDir(context), MAX_IMAGE_CACHE_BYTES)
            bitmap
        } catch (e: Exception) {
            Log.w(TAG, "Failed saving image to disk: ${e.message}")
            null
        }
    }

    // --- DETAILS JSON CACHE (DISK + RAM) ---

    fun getDetails(context: Context, mediaType: String, id: String): JSONObject? {
        val key = "${mediaType}_$id"
        val mem = detailsMemCache.get(key)
        if (mem != null) return mem

        val file = File(getJsonDir(context), "details_$key.json")
        if (file.exists()) {
            return try {
                file.setLastModified(System.currentTimeMillis())
                val obj = JSONObject(file.readText(Charsets.UTF_8))
                detailsMemCache.put(key, obj)
                obj
            } catch (e: Exception) {
                file.delete()
                null
            }
        }
        return null
    }

    fun saveDetails(context: Context, mediaType: String, id: String, data: JSONObject) {
        val key = "${mediaType}_$id"
        detailsMemCache.put(key, data)
        try {
            val file = File(getJsonDir(context), "details_$key.json")
            file.writeText(data.toString(), Charsets.UTF_8)
            pruneCacheDir(getJsonDir(context), MAX_JSON_CACHE_BYTES)
        } catch (_: Exception) {}
    }

    // --- EPISODES JSON CACHE (DISK + RAM) ---

    fun getEpisodes(context: Context, showId: String, season: Int): JSONArray? {
        val key = "${showId}_$season"
        val mem = episodesMemCache.get(key)
        if (mem != null) return mem

        val file = File(getJsonDir(context), "episodes_$key.json")
        if (file.exists()) {
            return try {
                file.setLastModified(System.currentTimeMillis())
                val arr = JSONArray(file.readText(Charsets.UTF_8))
                episodesMemCache.put(key, arr)
                arr
            } catch (e: Exception) {
                file.delete()
                null
            }
        }
        return null
    }

    fun saveEpisodes(context: Context, showId: String, season: Int, array: JSONArray) {
        val key = "${showId}_$season"
        episodesMemCache.put(key, array)
        try {
            val file = File(getJsonDir(context), "episodes_$key.json")
            file.writeText(array.toString(), Charsets.UTF_8)
            pruneCacheDir(getJsonDir(context), MAX_JSON_CACHE_BYTES)
        } catch (_: Exception) {}
    }

    // --- ACTOR CREDITS CACHE (DISK + RAM) ---

    fun getActorCredits(context: Context, actorId: Int): JSONArray? {
        val key = "actor_credits_$actorId"
        val mem = libraryMemCache.get(key)
        if (mem != null) return mem

        val file = File(getJsonDir(context), "credits_$actorId.json")
        if (file.exists()) {
            val age = System.currentTimeMillis() - file.lastModified()
            if (age < 7L * 24L * 60L * 60L * 1000L) { // 7 days TTL for actor filmographies
                return try {
                    val arr = JSONArray(file.readText(Charsets.UTF_8))
                    libraryMemCache.put(key, arr)
                    arr
                } catch (e: Exception) {
                    file.delete()
                    null
                }
            }
        }
        return null
    }

    fun saveActorCredits(context: Context, actorId: Int, array: JSONArray) {
        val key = "actor_credits_$actorId"
        libraryMemCache.put(key, array)
        try {
            val file = File(getJsonDir(context), "credits_$actorId.json")
            file.writeText(array.toString(), Charsets.UTF_8)
            pruneCacheDir(getJsonDir(context), MAX_JSON_CACHE_BYTES)
        } catch (_: Exception) {}
    }

    // --- LIBRARY CATEGORY CACHE (DISK + RAM) ---

    fun getLibraryCategory(context: Context, key: String): JSONArray? {
        val mem = libraryMemCache.get(key)
        if (mem != null) return mem

        val file = File(getJsonDir(context), "lib_${md5(key)}.json")
        if (file.exists()) {
            val age = System.currentTimeMillis() - file.lastModified()
            if (age < 24L * 60L * 60L * 1000L) { // 24 hours TTL
                return try {
                    val arr = JSONArray(file.readText(Charsets.UTF_8))
                    libraryMemCache.put(key, arr)
                    arr
                } catch (e: Exception) {
                    file.delete()
                    null
                }
            }
        }
        return null
    }

    fun saveLibraryCategory(context: Context, key: String, array: JSONArray) {
        libraryMemCache.put(key, array)
        try {
            val file = File(getJsonDir(context), "lib_${md5(key)}.json")
            file.writeText(array.toString(), Charsets.UTF_8)
            pruneCacheDir(getJsonDir(context), MAX_JSON_CACHE_BYTES)
        } catch (_: Exception) {}
    }

    fun clearFeedCaches(context: Context) {
        libraryMemCache.evictAll()
        try {
            val dir = getJsonDir(context)
            val files = dir.listFiles() ?: return
            for (file in files) {
                if (file.name.startsWith("lib_")) {
                    file.delete()
                }
            }
        } catch (_: Exception) {}
    }

    suspend fun resolveLandscapeImage(
        context: Context,
        item: JSONObject,
        season: Int? = null,
        episode: Int? = null
    ): Pair<Uri, Int> = withContext(Dispatchers.IO) {
        val id = item.optString("id").ifEmpty { item.optString("imdb") }
        val isEpisode = season != null && episode != null

        if (isEpisode) {
            val still = item.optString("still_path").takeIf { it.isNotBlank() && it != "null" }
            if (!still.isNullOrBlank()) {
                val url = if (still.startsWith("http")) still else "https://image.tmdb.org/t/p/w780$still"
                return@withContext Pair(Uri.parse(url), TvContractCompat.PreviewProgramColumns.ASPECT_RATIO_16_9)
            }

            if (id.isNotBlank() && id != "0") {
                val cachedEps = getEpisodes(context, id, season!!)
                if (cachedEps != null) {
                    for (i in 0 until cachedEps.length()) {
                        val epObj = cachedEps.optJSONObject(i) ?: continue
                        if (epObj.optInt("episode_number") == episode) {
                            val epStill = epObj.optString("still_path")
                            if (epStill.isNotBlank() && epStill != "null") {
                                item.put("still_path", epStill)
                                val url = if (epStill.startsWith("http")) epStill else "https://image.tmdb.org/t/p/w780$epStill"
                                return@withContext Pair(Uri.parse(url), TvContractCompat.PreviewProgramColumns.ASPECT_RATIO_16_9)
                            }
                        }
                    }
                }
            }
        }

        val backdrop = item.optString("backdrop_path").takeIf { it.isNotBlank() && it != "null" }
        if (!backdrop.isNullOrBlank()) {
            val url = if (backdrop.startsWith("http")) backdrop else "https://image.tmdb.org/t/p/w780$backdrop"
            return@withContext Pair(Uri.parse(url), TvContractCompat.PreviewProgramColumns.ASPECT_RATIO_16_9)
        }

        if (id.isNotBlank() && id != "0") {
            val mediaType = if (isEpisode || item.has("name")) "tv" else item.optString("media_type", "movie")
            val cachedDetails = getDetails(context, mediaType, id)
            val b = cachedDetails?.optString("backdrop_path")
            if (!b.isNullOrBlank() && b != "null") {
                val url = if (b.startsWith("http")) b else "https://image.tmdb.org/t/p/w780$b"
                return@withContext Pair(Uri.parse(url), TvContractCompat.PreviewProgramColumns.ASPECT_RATIO_16_9)
            }
        }

        val poster = item.optString("poster_path").takeIf { it.isNotBlank() && it != "null" }
        if (!poster.isNullOrBlank()) {
            val url = if (poster.startsWith("http")) poster else "https://image.tmdb.org/t/p/w500$poster"
            return@withContext Pair(Uri.parse(url), TvContractCompat.PreviewProgramColumns.ASPECT_RATIO_2_3)
        }

        Pair(
            Uri.parse("android.resource://${context.packageName}/${R.drawable.tv_banner}"),
            TvContractCompat.PreviewProgramColumns.ASPECT_RATIO_16_9
        )
    }

    private fun pruneCacheDir(dir: File, maxSizeBytes: Long) {
        try {
            val files = dir.listFiles() ?: return
            var totalSize = files.sumOf { it.length() }
            if (totalSize <= maxSizeBytes) return

            val sorted = files.sortedBy { it.lastModified() }
            val targetSize = (maxSizeBytes * 0.70f).toLong()

            for (file in sorted) {
                val len = file.length()
                if (file.delete()) {
                    totalSize -= len
                    if (totalSize <= targetSize) break
                }
            }
        } catch (_: Exception) {}
    }
}