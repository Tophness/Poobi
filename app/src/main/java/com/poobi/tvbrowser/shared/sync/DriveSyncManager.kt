package com.poobi.tvbrowser.shared.sync

import android.content.Context
import android.os.Environment
import android.util.Base64
import android.util.Log
import com.chaquo.python.Python
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

        val prefsJson = JSONObject()
        for ((key, value) in prefs.all) {
            prefsJson.put(key, value)
        }
        payload.put("shared_preferences", prefsJson)

        val filesMap = JSONObject()
        val filesDir = context.filesDir

        val pySettingsFile = File(filesDir, "chaquopy/AssetFinder/userdata/settings.json")
        if (pySettingsFile.exists() && pySettingsFile.isFile) {
            packageFile(pySettingsFile, filesDir, filesMap)
        }

        val explicitFiles = listOf(
            File(filesDir, "config.json"),
            File(filesDir, "trakt_progress_cache.json")
        )
        for (f in explicitFiles) {
            if (f.exists() && f.isFile) {
                packageFile(f, filesDir, filesMap)
            }
        }

        payload.put("userdata_files", filesMap)
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
            restoreFiles(context.filesDir, userdataJson)

            try {
                if (Python.isStarted()) {
                    val py = Python.getInstance()
                    val importlib = py.getModule("importlib")

                    try {
                        py.getModule("modules.control").callAttr("refreshSettings")
                    } catch (_: Exception) {
                        try {
                            val control = py.getModule("modules.control")
                            importlib.callAttr("reload", control)
                        } catch (_: Exception) {}
                    }

                    try {
                        val traktAuth = py.getModule("trakt.trakt_auth")
                        importlib.callAttr("reload", traktAuth)
                    } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
        }
    }

    private fun packageFile(file: File, baseDir: File, filesMap: JSONObject) {
        try {
            val relativePath = file.relativeTo(baseDir).path.replace('\\', '/')
            val bytes = file.readBytes()
            val base64Data = Base64.encodeToString(bytes, Base64.NO_WRAP)
            filesMap.put(relativePath, base64Data)
        } catch (e: Exception) {
            Log.e("DriveSync", "Failed to package ${file.name}", e)
        }
    }

    private fun restoreFiles(baseDir: File, filesMap: JSONObject) {
        val keys = filesMap.keys()
        while (keys.hasNext()) {
            val relativePath = keys.next()
            try {
                val base64Data = filesMap.getString(relativePath)
                val targetFile = File(baseDir, relativePath)
                targetFile.parentFile?.mkdirs()
                val bytes = Base64.decode(base64Data, Base64.DEFAULT)
                targetFile.writeBytes(bytes)

                if (relativePath.endsWith("settings.json")) {
                    val chaquopyTarget = File(baseDir, "chaquopy/AssetFinder/userdata/settings.json")
                    if (targetFile.absolutePath != chaquopyTarget.absolutePath) {
                        chaquopyTarget.parentFile?.mkdirs()
                        chaquopyTarget.writeBytes(bytes)
                    }

                    val userdataTarget = File(baseDir, "userdata/settings.json")
                    if (targetFile.absolutePath != userdataTarget.absolutePath) {
                        userdataTarget.parentFile?.mkdirs()
                        userdataTarget.writeBytes(bytes)
                    }
                }
            } catch (e: Exception) {
                Log.e("DriveSync", "Failed to restore $relativePath", e)
            }
        }
    }

    private fun getBackupFile(): File {
        val extDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: context.getExternalFilesDir(null)
            ?: File(context.filesDir, "backups")
        if (!extDir.exists()) extDir.mkdirs()
        return File(extDir, "poobi_settings_backup.json")
    }

    suspend fun saveToLocalBackupFile(): File? = withContext(Dispatchers.IO) {
        try {
            val payload = buildBackupPayload()
            val target = getBackupFile()
            FileOutputStream(target).use { it.write(payload.toString(2).toByteArray(Charsets.UTF_8)) }
            target
        } catch (e: Exception) {
            Log.e("DriveSync", "Failed saving local backup", e)
            null
        }
    }

    suspend fun restoreFromLocalBackupFile(): Boolean = withContext(Dispatchers.IO) {
        try {
            val file = getBackupFile()
            if (!file.exists() || file.length() == 0L) {
                return@withContext false
            }
            val jsonStr = file.readText(Charsets.UTF_8)
            val payload = JSONObject(jsonStr)
            applyBackupPayload(payload)
            true
        } catch (e: Exception) {
            Log.e("DriveSync", "Failed restoring local backup", e)
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
            Log.e("DriveSync", "Smart sync failed", e)
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
}