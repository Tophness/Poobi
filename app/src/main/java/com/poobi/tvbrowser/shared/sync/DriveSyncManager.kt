package com.poobi.tvbrowser.shared.sync

import android.content.Context
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
            val prefs = context.getSharedPreferences("BrowserSettings", Context.MODE_PRIVATE)
            val allEntries = prefs.all
            val json = JSONObject()
            for ((key, value) in allEntries) {
                json.put(key, value)
            }

            val tempFile = File(context.cacheDir, "settings_backup.json")
            FileOutputStream(tempFile).use { it.write(json.toString().toByteArray()) }

            // Check if file already exists
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
            val jsonStr = outputStream.toString()
            val json = JSONObject(jsonStr)

            val prefs = context.getSharedPreferences("BrowserSettings", Context.MODE_PRIVATE)
            val editor = prefs.edit()
            
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                when (val value = json.get(key)) {
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                    is String -> editor.putString(key, value)
                }
            }
            editor.apply()
            true
        } catch (e: Exception) {
            Log.e("DriveSync", "Download failed", e)
            false
        }
    }
}
