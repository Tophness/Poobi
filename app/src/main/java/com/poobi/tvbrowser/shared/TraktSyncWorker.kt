package com.poobi.tvbrowser.shared

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerParameters
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.poobi.tvbrowser.tvhome.TvHomeSyncManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class TraktSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val context = applicationContext
        val prefs = context.getSharedPreferences("BrowserSettings", Context.MODE_PRIVATE)
        val favsJson = prefs.getString("streams_favorites", "[]") ?: "[]"

        if (favsJson == "[]") return@withContext Result.success()

        try {
            if (!Python.isStarted()) {
                Python.start(AndroidPlatform(context))
            }
            val py = Python.getInstance()
            val cacheFile = File(context.cacheDir, "last_episode_check.json")
            val lastCheckJson = if (cacheFile.exists()) cacheFile.readText() else "{}"

            val checker = py.getModule("trakt.episode_check")
            val resultsStr = checker.callAttr("check_new_episodes", favsJson, lastCheckJson).toString()
            val resultObj = JSONObject(resultsStr)
            val newEpisodes = resultObj.getJSONArray("new_episodes")
            val updatedLastCheck = resultObj.getJSONObject("last_check")

            cacheFile.writeText(updatedLastCheck.toString())

            if (newEpisodes.length() > 0) {
                val currentSavedStr = prefs.getString("saved_new_episodes", "[]") ?: "[]"
                val currentSavedArr = JSONArray(currentSavedStr)
                val existingList = mutableListOf<JSONObject>()
                for (i in 0 until currentSavedArr.length()) {
                    existingList.add(currentSavedArr.getJSONObject(i))
                }

                for (i in 0 until newEpisodes.length()) {
                    existingList.add(newEpisodes.getJSONObject(i))
                }

                val arrToSave = JSONArray()
                existingList.forEach { arrToSave.put(it) }
                prefs.edit().putString("saved_new_episodes", arrToSave.toString()).apply()

                TvHomeSyncManager.syncNewEpisodesChannel(context, existingList)
            }
            Result.success()
        } catch (e: Exception) {
            Log.e("TraktSyncWorker", "Error executing background episode check", e)
            Result.retry()
        }
    }
}