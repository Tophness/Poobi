package com.poobi.tvbrowser.player

import android.content.Context
import android.os.Environment
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class DiskSubtitleItem(
    val file: File,
    val displayName: String,
    val language: String = "und"
)

data class DiskMediaSubtitleGroup(
    val mediaTitle: String,
    val subtitles: List<DiskSubtitleItem>
)

object DiskSubtitleManager {

    fun scanDiskSubtitles(context: Context): List<DiskMediaSubtitleGroup> {
        val groupsMap = mutableMapOf<String, MutableList<DiskSubtitleItem>>()
        val seenFilePaths = mutableSetOf<String>()

        try {
            val subDir = File(context.filesDir, "userdata/subtitles")
            val cacheFile = File(subDir, "cache_mapping.json")
            val prefs = context.getSharedPreferences("BrowserSettings", Context.MODE_PRIVATE)
            val recents = JSONArray(prefs.getString("streams_recently_played", "[]") ?: "[]")
            val favs = JSONArray(prefs.getString("streams_favorites", "[]") ?: "[]")

            val keyToTitleMap = mutableMapOf<String, String>()
            for (i in 0 until recents.length()) {
                val obj = recents.getJSONObject(i)
                val item = obj.optJSONObject("item")
                val id = item?.optInt("id") ?: 0
                val season = obj.optInt("season", 0)
                val ep = obj.optInt("episode", 0)
                val title = obj.optString("display_title").ifEmpty {
                    item?.optString("title")?.ifEmpty { item.optString("name") } ?: ""
                }
                if (id > 0 && title.isNotEmpty()) {
                    keyToTitleMap["${id}_${season}_${ep}"] = title
                    keyToTitleMap["$id"] = title
                }
            }
            for (i in 0 until favs.length()) {
                val item = favs.getJSONObject(i)
                val id = item.optInt("id")
                val title = item.optString("title").ifEmpty { item.optString("name") }
                if (id > 0 && title.isNotEmpty()) {
                    keyToTitleMap["$id"] = title
                }
            }

            if (cacheFile.exists()) {
                val json = JSONObject(cacheFile.readText())
                val keys = json.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val groupTitle = keyToTitleMap[key] ?: keyToTitleMap[key.substringBefore("_")]
                    val array = json.getJSONArray(key)
                    for (j in 0 until array.length()) {
                        val subObj = array.getJSONObject(j)
                        val urlStr = subObj.getString("url")
                        val path = android.net.Uri.parse(urlStr).path
                        if (path != null) {
                            val f = File(path)
                            if (f.exists() && f.isFile) {
                                seenFilePaths.add(f.absolutePath)
                                val label = subObj.optString("label").ifEmpty { f.name }
                                val lang = subObj.optString("lang", "und")
                                val targetGroup = groupTitle ?: cleanTitleFromFilename(f.name)
                                groupsMap.getOrPut(targetGroup) { mutableListOf() }
                                    .add(DiskSubtitleItem(f, label, lang))
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("DiskSubtitleManager", "Error reading cache_mapping", e)
        }

        try {
            val subDir = File(context.filesDir, "userdata/subtitles")
            if (subDir.exists() && subDir.isDirectory) {
                subDir.listFiles()?.forEach { file ->
                    if (file.isFile && isSubtitleFile(file) && !seenFilePaths.contains(file.absolutePath)) {
                        seenFilePaths.add(file.absolutePath)
                        val cleanName = cleanSubtitleDisplayName(file.name)
                        val groupName = cleanTitleFromFilename(file.name)
                        groupsMap.getOrPut(groupName) { mutableListOf() }
                            .add(DiskSubtitleItem(file, cleanName))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("DiskSubtitleManager", "Error scanning userdata/subtitles", e)
        }

        try {
            val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (downloadDir != null && downloadDir.exists() && downloadDir.isDirectory) {
                scanDirectoryForSubtitles(downloadDir, groupsMap, seenFilePaths, maxDepth = 2)
            }
        } catch (e: Exception) {
            Log.e("DiskSubtitleManager", "Error scanning Downloads directory", e)
        }

        return groupsMap.map { (title, subs) ->
            DiskMediaSubtitleGroup(
                mediaTitle = title,
                subtitles = subs.distinctBy { it.file.absolutePath }
            )
        }.filter { it.subtitles.isNotEmpty() }
        .sortedBy { it.mediaTitle.lowercase() }
    }

    private fun scanDirectoryForSubtitles(
        dir: File,
        groupsMap: MutableMap<String, MutableList<DiskSubtitleItem>>,
        seenFilePaths: MutableSet<String>,
        maxDepth: Int
    ) {
        if (maxDepth <= 0) return
        val files = dir.listFiles() ?: return
        for (file in files) {
            if (file.isDirectory) {
                scanDirectoryForSubtitles(file, groupsMap, seenFilePaths, maxDepth - 1)
            } else if (file.isFile && isSubtitleFile(file)) {
                if (!seenFilePaths.contains(file.absolutePath)) {
                    seenFilePaths.add(file.absolutePath)
                    val groupName = if (dir.name != "Download" && dir.name != "Downloads") {
                        dir.name
                    } else {
                        cleanTitleFromFilename(file.name)
                    }
                    val cleanName = cleanSubtitleDisplayName(file.name)
                    groupsMap.getOrPut(groupName) { mutableListOf() }
                        .add(DiskSubtitleItem(file, cleanName))
                }
            }
        }
    }

    private fun isSubtitleFile(file: File): Boolean {
        val name = file.name.lowercase()
        return name.endsWith(".srt") || name.endsWith(".vtt") || name.endsWith(".ass") || name.endsWith(".ssa")
    }

    private fun cleanSubtitleDisplayName(filename: String): String {
        var name = filename.replace(Regex("_[0-9a-fA-F\\-]{36}"), "")
        name = name.substringBeforeLast(".")
        return name.replace("_", " ").trim()
    }

    private fun cleanTitleFromFilename(filename: String): String {
        var name = filename.replace(Regex("_[0-9a-fA-F\\-]{36}"), "")
        name = name.substringBeforeLast(".")
        name = name.replace(Regex("\\[.*?\\]"), "").replace(Regex("\\(.*?\\)"), "")
        name = name.replace(Regex("(?i)(1080p|720p|4k|2160p|bluray|web-dl|webrip|dvdrip|dvdscr|x264|x265|hevc|h264|aac|ac3|sub|eng|english|ita|rus|spa|fre|ger)"), "")
        name = name.replace(Regex("(?i)s\\d+e\\d+.*"), "")
        name = name.replace(".", " ").replace("_", " ").replace("-", " ").trim()
        return if (name.length > 2) name else filename.substringBeforeLast(".")
    }
}