package com.poobi.tvbrowser.shared

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONObject

object StreamResumeManager {
    private const val TAG = "StreamResumeManager"
    private const val PREF_KEY = "stream_resume_points"
    private const val SETTINGS_FILE = "BrowserSettings"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(SETTINGS_FILE, Context.MODE_PRIVATE)
    }

    fun getPosition(prefs: SharedPreferences, title: String?): Long {
        if (title.isNullOrBlank()) return 0L
        return try {
            val jsonStr = prefs.getString(PREF_KEY, "{}") ?: "{}"
            val jsonObj = JSONObject(jsonStr)
            jsonObj.optLong(title, 0L)
        } catch (e: Exception) {
            Log.e(TAG, "Error reading resume position for $title", e)
            0L
        }
    }

    fun getPosition(context: Context, title: String?): Long {
        return getPosition(getPrefs(context), title)
    }

    fun savePosition(prefs: SharedPreferences, title: String?, positionMs: Long, isCompleted: Boolean) {
        if (title.isNullOrBlank()) return
        try {
            val jsonStr = prefs.getString(PREF_KEY, "{}") ?: "{}"
            val jsonObj = JSONObject(jsonStr)
            if (isCompleted) {
                jsonObj.remove(title)
            } else if (positionMs > 5000L) {
                jsonObj.put(title, positionMs)
            }
            prefs.edit().putString(PREF_KEY, jsonObj.toString()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error saving resume position for $title", e)
        }
    }

    fun savePosition(context: Context, title: String?, positionMs: Long, isCompleted: Boolean) {
        savePosition(getPrefs(context), title, positionMs, isCompleted)
    }

    fun removePosition(prefs: SharedPreferences, title: String?) {
        if (title.isNullOrBlank()) return
        try {
            val jsonStr = prefs.getString(PREF_KEY, "{}") ?: "{}"
            val jsonObj = JSONObject(jsonStr)
            if (jsonObj.has(title)) {
                jsonObj.remove(title)
                prefs.edit().putString(PREF_KEY, jsonObj.toString()).apply()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error removing resume position for $title", e)
        }
    }

    fun removePosition(context: Context, title: String?) {
        removePosition(getPrefs(context), title)
    }
}