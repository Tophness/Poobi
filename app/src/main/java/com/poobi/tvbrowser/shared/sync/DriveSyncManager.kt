package com.poobi.tvbrowser.shared.sync

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
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

enum class SyncOutcome {
    RESTORED_FROM_REMOTE,
    UPLOADED_LOCAL_TO_REMOTE,
    ALREADY_UP_TO_DATE,
    FAILED
}

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

    fun buildBackupPayload(): JSONObject {
        val payload = JSONObject()
        val prefs = context.getSharedPreferences("BrowserSettings", Context.MODE_PRIVATE)

        val currentTimestamp = prefs.getLong("settings_last_modified", System.currentTimeMillis())
        payload.put("timestamp", currentTimestamp)

        val allEntries = prefs.all
        val prefsJson = JSONObject()
        for ((key, value) in allEntries) {
            prefsJson.put(key, value)
        }
        payload.put("shared_preferences", prefsJson)

        val userdataJson = JSONObject()
        val userdataDir = File(context.filesDir, "userdata")
        if (userdataDir.exists() && userdataDir.isDirectory) {
            collectUserdataFiles(userdataDir, userdataDir, userdataJson, "userdata")
        }

        val filesDir = context.filesDir
        val candidates = listOf(
            File(filesDir, "addon_data"),
            File(filesDir, "profile"),
            File(context.cacheDir.parentFile, "app_chaquopy")
        )
        for (dir in candidates) {
            if (dir.exists() && dir.isDirectory) {
                collectUserdataFiles(dir, filesDir, userdataJson, "files_root")
            }
        }

        payload.put("userdata_files", userdataJson)
        return payload
    }

    fun applyBackupPayload(payload: JSONObject) {
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
                            editor.putString(key, value.toString())
                        }
                    }
                }
            }
            if (payload.has("timestamp")) {
                editor.putLong("settings_last_modified", payload.getLong("timestamp"))
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
    }


    suspend fun saveToLocalBackupFile(): File? = withContext(Dispatchers.IO) {
        try {
            val payload = buildBackupPayload()
            val jsonBytes = payload.toString(2).toByteArray(Charsets.UTF_8)
            val fileName = "poobi_settings_backup.json"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val queryUri = MediaStore.Downloads.EXTERNAL_CONTENT_URI

                val projection = arrayOf(MediaStore.Downloads._ID)
                val selection = "${MediaStore.Downloads.DISPLAY_NAME} = ?"
                val selectionArgs = arrayOf(fileName)
                try {
                    resolver.query(queryUri, projection, selection, selectionArgs, null)?.use { cursor ->
                        while (cursor.moveToNext()) {
                            val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID))
                            val itemUri = ContentUris.withAppendedId(queryUri, id)
                            resolver.delete(itemUri, null, null)
                        }
                    }
                } catch (e: Exception) {
                    Log.w("DriveSync", "MediaStore cleanup warning: ${e.message}")
                }

                val contentValues = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "application/json")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }

                val itemUri = resolver.insert(queryUri, contentValues)
                if (itemUri != null) {
                    resolver.openOutputStream(itemUri, "wt")?.use { output ->
                        output.write(jsonBytes)
                    }
                    contentValues.clear()
                    contentValues.put(MediaStore.Downloads.IS_PENDING, 0)
                    resolver.update(itemUri, contentValues, null, null)

                    return@withContext File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                        fileName
                    )
                }
            }

            try {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (downloadsDir != null && (downloadsDir.exists() || downloadsDir.mkdirs())) {
                    val target = File(downloadsDir, fileName)
                    FileOutputStream(target).use { it.write(jsonBytes) }
                    return@withContext target
                }
            } catch (e: Exception) {
                Log.w("DriveSync", "Direct Downloads write failed: ${e.message}")
            }

            val fallbackDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: context.getExternalFilesDir(null)
                ?: context.filesDir
            val fallbackFile = File(fallbackDir, fileName)
            fallbackFile.parentFile?.mkdirs()
            FileOutputStream(fallbackFile).use { it.write(jsonBytes) }
            fallbackFile
        } catch (e: Exception) {
            Log.e("DriveSync", "Failed saving local backup file", e)
            null
        }
    }

    suspend fun restoreFromLocalBackupFile(): Boolean = withContext(Dispatchers.IO) {
        try {
            val fileNameCandidates = listOf(
                "poobi_settings_backup.json",
                "settings_backup.json",
                "poobi_backup.json"
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val queryUri = MediaStore.Downloads.EXTERNAL_CONTENT_URI
                val projection = arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME)

                for (name in fileNameCandidates) {
                    val selection = "${MediaStore.Downloads.DISPLAY_NAME} = ?"
                    val selectionArgs = arrayOf(name)
                    try {
                        resolver.query(
                            queryUri,
                            projection,
                            selection,
                            selectionArgs,
                            "${MediaStore.Downloads.DATE_MODIFIED} DESC"
                        )?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID))
                                val itemUri = ContentUris.withAppendedId(queryUri, id)
                                val text = resolver.openInputStream(itemUri)?.bufferedReader()?.use { it.readText() }
                                if (!text.isNullOrBlank()) {
                                    val payload = JSONObject(text)
                                    applyBackupPayload(payload)
                                    return@withContext true
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.w("DriveSync", "MediaStore read check failed for $name: ${e.message}")
                    }
                }
            }

            val candidateDirs = listOfNotNull(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                File(Environment.getExternalStorageDirectory(), "Download"),
                context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                context.getExternalFilesDir(null),
                context.filesDir
            )

            for (dir in candidateDirs) {
                if (dir.exists() && dir.isDirectory) {
                    for (name in fileNameCandidates) {
                        val file = File(dir, name)
                        if (file.exists() && file.isFile && file.length() > 0) {
                            try {
                                val text = file.readText(Charsets.UTF_8)
                                if (text.isNotBlank()) {
                                    val payload = JSONObject(text)
                                    applyBackupPayload(payload)
                                    return@withContext true
                                }
                            } catch (e: Exception) {
                                Log.w("DriveSync", "Failed reading candidate file ${file.absolutePath}: ${e.message}")
                            }
                        }
                    }
                }
            }

            false
        } catch (e: Exception) {
            Log.e("DriveSync", "Failed restoring from local backup file", e)
            false
        }
    }


    suspend fun syncSettings(): SyncOutcome = withContext(Dispatchers.IO) {
        val service = driveService ?: return@withContext SyncOutcome.FAILED
        try {
            val prefs = context.getSharedPreferences("BrowserSettings", Context.MODE_PRIVATE)
            val localTimestamp = prefs.getLong("settings_last_modified", 0L)

            val files = service.files().list()
                .setSpaces("appDataFolder")
                .setFields("files(id, name, modifiedTime)")
                .execute()

            val driveFile = files.files.find { it.name == "settings.json" }

            if (driveFile == null) {
                val uploaded = uploadSettings()
                return@withContext if (uploaded) SyncOutcome.UPLOADED_LOCAL_TO_REMOTE else SyncOutcome.FAILED
            }

            val outputStream = ByteArrayOutputStream()
            service.files().get(driveFile.id).executeMediaAndDownloadTo(outputStream)
            val payload = JSONObject(outputStream.toString())

            val remoteTimestamp = if (payload.has("timestamp")) {
                payload.getLong("timestamp")
            } else {
                driveFile.modifiedTime?.value ?: 0L
            }

            if (remoteTimestamp > localTimestamp) {
                applyBackupPayload(payload)
                SyncOutcome.RESTORED_FROM_REMOTE
            } else if (localTimestamp > remoteTimestamp) {
                val uploaded = uploadSettings()
                if (uploaded) SyncOutcome.UPLOADED_LOCAL_TO_REMOTE else SyncOutcome.FAILED
            } else {
                SyncOutcome.ALREADY_UP_TO_DATE
            }
        } catch (e: Exception) {
            Log.e("DriveSync", "Smart cloud sync failed", e)
            SyncOutcome.FAILED
        }
    }

    suspend fun uploadSettings(): Boolean = withContext(Dispatchers.IO) {
        val service = driveService ?: return@withContext false
        try {
            val payload = buildBackupPayload()
            val tempFile = File(context.cacheDir, "settings_backup.json")
            FileOutputStream(tempFile).use { it.write(payload.toString().toByteArray()) }

            val files = service.files().list()
                .setSpaces("appDataFolder")
                .setFields("files(id, name)")
                .execute()

            val existingFile = files.files.find { it.name == "settings.json" }
            val fileMetadata = com.google.api.services.drive.model.File().apply {
                name = "settings.json"
                if (existingFile == null) {
                    parents = Collections.singletonList("appDataFolder")
                }
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


    private fun collectUserdataFiles(dir: File, baseDir: File, filesMap: JSONObject, prefix: String = "") {
        val files = dir.listFiles() ?: return
        for (file in files) {
            if (file.isDirectory) {
                if (file.name == "subtitles" || file.name == "__pycache__") continue
                collectUserdataFiles(file, baseDir, filesMap, prefix)
            } else if (file.isFile) {
                try {
                    val relativePath = if (prefix.isNotEmpty()) {
                        "$prefix/${file.relativeTo(baseDir).path.replace('\\', '/')}"
                    } else {
                        file.relativeTo(baseDir).path.replace('\\', '/')
                    }
                    val bytes = file.readBytes()
                    val base64Data = Base64.encodeToString(bytes, Base64.NO_WRAP)
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