package com.poobi.tvbrowser.shared.sync

import android.content.Context
import android.util.Base64
import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.FileContent
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Collections

class DriveSyncManager(private val context: Context) {

    private var driveService: Drive? = null

    fun initService(account: GoogleSignInAccount) {
        val credential = GoogleAccountCredential.usingOAuth2(
            context, Collections.singleton(DriveScopes.DRIVE_APPDATA)
        )
        credential.selectedAccount = account.account

        driveService = Drive.Builder(
            com.google.api.client.http.javanet.NetHttpTransport(),
            GsonFactory.getDefaultInstance(),
            credential
        ).setApplicationName("TVBrowser").build()
    }

    suspend fun uploadSettings(): Boolean = withContext(Dispatchers.IO) {
        val service = driveService ?: return@withContext false
        try {
            val payload = JSONObject()
            val prefs = context.getSharedPreferences("BrowserSettings", Context.MODE_PRIVATE)
            val allEntries = prefs.all
            val prefsJson = JSONObject()
            for ((key, value) in allEntries) {
                prefsJson.put(key, value)
            }
            payload.put("shared_preferences", prefsJson)
            val userdataJson = JSONObject()
            val userdataDir = File(context.filesDir, "userdata")
            if (userdataDir.exists() && userdataDir.isDirectory) {
                collectUserdataFiles(userdataDir, userdataDir, userdataJson)
            }
            payload.put("userdata_files", userdataJson)
            val tempFile = File(context.cacheDir, "settings_backup.json")
            FileOutputStream(tempFile).use { it.write(payload.toString().toByteArray()) }

            val files = service.files().list()
                .setSpaces("appDataFolder")
                .setFields("files(id, name)")
                .execute()
            
            val existingFile = files.files.find { it.name == "settings.json" }

            val fileMetadata = com.google.api.services.drive.model.File()
            fileMetadata.name = "settings.json"
            if (existingFile == null) {
                fileMetadata.parents = Collections.singletonList("appDataFolder")
            }

            val mediaContent = FileContent("application/json", tempFile)

            if (existingFile != null) {
                service.files().update(existingFile.id, null, mediaContent).execute()
            } else {
                service.files().create(fileMetadata, mediaContent).execute()
            }
            true
        } catch (e: Exception) {
            Log.e("DriveSync", "Upload failed", e)
            false
        }
    }

    suspend fun downloadSettings(): Boolean = withContext(Dispatchers.IO) {
        val service = driveService ?: return@withContext false
        try {
            val files = service.files().list()
                .setSpaces("appDataFolder")
                .setFields("files(id, name)")
                .execute()

            val driveFile = files.files.find { it.name == "settings.json" } ?: return@withContext false

            val outputStream = ByteArrayOutputStream()
            service.files().get(driveFile.id).executeMediaAndDownloadTo(outputStream)
            
            val payload = JSONObject(outputStream.toString())
            if (payload.has("shared_preferences")) {
                val prefsJson = payload.getJSONObject("shared_preferences")
                val prefs = context.getSharedPreferences("BrowserSettings", Context.MODE_PRIVATE)
                val editor = prefs.edit()
                
                val keys = prefsJson.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = prefsJson.get(key)
                    when (value) {
                        is Boolean -> editor.putBoolean(key, value)
                        is Int -> editor.putInt(key, value)
                        is Long -> editor.putLong(key, value)
                        is Double -> editor.putFloat(key, value.toFloat())
                        is Float -> editor.putFloat(key, value)
                        else -> {
                            if (value != JSONObject.NULL) {
                                // Gracefully falls back to stringifying arrays/objects/raw text
                                editor.putString(key, value.toString())
                            }
                        }
                    }
                }
                editor.apply()
            }

            if (payload.has("userdata_files")) {
                val userdataJson = payload.getJSONObject("userdata_files")
                val userdataDir = File(context.filesDir, "userdata")
                if (!userdataDir.exists()) {
                    userdataDir.mkdirs()
                }
                restoreUserdataFiles(userdataDir, userdataJson)
            }

            true
        } catch (e: Exception) {
            Log.e("DriveSync", "Download failed", e)
            false
        }
    }

    private fun collectUserdataFiles(dir: File, baseDir: File, filesMap: JSONObject) {
        val files = dir.listFiles() ?: return
        for (file in files) {
            if (file.isDirectory) {
                if (file.name == "subtitles") continue
                collectUserdataFiles(file, baseDir, filesMap)
            } else if (file.isFile) {
                try {
                    val relativePath = file.relativeTo(baseDir).path
                    val bytes = file.readBytes()
                    val base64Data = Base64.encodeToString(bytes, Base64.DEFAULT)
                    filesMap.put(relativePath, base64Data)
                } catch (e: Exception) {
                    Log.e("DriveSync", "Failed to package userdata file: ${file.name}", e)
                }
            }
        }
    }

    private fun restoreUserdataFiles(baseDir: File, filesMap: JSONObject) {
        val keys = filesMap.keys()
        while (keys.hasNext()) {
            val relativePath = keys.next()
            try {
                val base64Data = filesMap.getString(relativePath)
                val targetFile = File(baseDir, relativePath)
                targetFile.parentFile?.mkdirs()
                
                val bytes = Base64.decode(base64Data, Base64.DEFAULT)
                targetFile.writeBytes(bytes)
            } catch (e: Exception) {
                Log.e("DriveSync", "Failed to restore userdata file: $relativePath", e)
            }
        }
    }
}