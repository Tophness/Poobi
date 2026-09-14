package com.poobi.tvbrowser.shared.update

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(
    val versionName: String,
    val releaseNotes: String,
    val downloadUrl: String,
    val fileName: String
)

object UpdateManager {
    private const val TAG = "UpdateManager"
    private const val GITHUB_API_URL = "https://api.github.com/repos/Tophness/Poobi/releases/latest"
    private const val GITHUB_RELEASES_LIST_URL = "https://api.github.com/repos/Tophness/Poobi/releases"

    private val _updateInfo = MutableStateFlow<UpdateInfo?>(null)
    val updateInfo: StateFlow<UpdateInfo?> = _updateInfo.asStateFlow()

    private val _isChecking = MutableStateFlow(false)
    val isChecking: StateFlow<Boolean> = _isChecking.asStateFlow()

    private val _isDownloading = MutableStateFlow(false)
    val isDownloading: StateFlow<Boolean> = _isDownloading.asStateFlow()

    private val _downloadProgress = MutableStateFlow(0f)
    val downloadProgress: StateFlow<Float> = _downloadProgress.asStateFlow()

    private var downloadJob: Job? = null

    fun getAppVersionName(context: Context): String {
        return try {
            val pInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            pInfo.versionName ?: "1.0"
        } catch (e: Exception) {
            "1.0"
        }
    }

    fun dismissUpdate() {
        _updateInfo.value = null
    }

    fun checkForUpdates(context: Context, isManual: Boolean = false) {
        if (_isChecking.value || _isDownloading.value) return
        _isChecking.value = true

        CoroutineScope(Dispatchers.IO).launch {
            try {
                var releaseJson = fetchReleaseFromUrl(GITHUB_API_URL)

                if (releaseJson == null) {
                    val listStr = fetchRawFromUrl(GITHUB_RELEASES_LIST_URL)
                    if (listStr != null) {
                        val arr = JSONArray(listStr)
                        if (arr.length() > 0) {
                            releaseJson = arr.getJSONObject(0)
                        }
                    }
                }

                if (releaseJson != null) {
                    val tagName = releaseJson.optString("tag_name", "").trim()
                    val remoteVersion = tagName.removePrefix("v").removePrefix("V")
                    val currentVersion = getAppVersionName(context).removePrefix("v").removePrefix("V")

                    if (isNewerVersion(remoteVersion, currentVersion)) {
                        val releaseNotes = releaseJson.optString("body", "No changelog provided.")
                        val assets = releaseJson.optJSONArray("assets")

                        val bestAsset = pickBestAssetForDevice(assets)
                        if (bestAsset != null) {
                            val downloadUrl = bestAsset.optString("browser_download_url")
                            val fileName = bestAsset.optString("name", "Poobi-update.apk")

                            withContext(Dispatchers.Main) {
                                _updateInfo.value = UpdateInfo(
                                    versionName = tagName,
                                    releaseNotes = releaseNotes,
                                    downloadUrl = downloadUrl,
                                    fileName = fileName
                                )
                            }
                        } else if (isManual) {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(context, "Update found ($tagName) but no matching APK for your device architecture.", Toast.LENGTH_LONG).show()
                            }
                        }
                    } else if (isManual) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "Poobi is up to date (v${getAppVersionName(context)}).", Toast.LENGTH_SHORT).show()
                        }
                    }
                } else if (isManual) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Could not reach GitHub releases.", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error checking updates", e)
                if (isManual) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Update check failed: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            } finally {
                withContext(Dispatchers.Main) {
                    _isChecking.value = false
                }
            }
        }
    }

    private fun fetchReleaseFromUrl(urlString: String): JSONObject? {
        val raw = fetchRawFromUrl(urlString) ?: return null
        return try { JSONObject(raw) } catch (e: Exception) { null }
    }

    private fun fetchRawFromUrl(urlString: String): String? {
        return try {
            val url = URL(urlString)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github.v3+json")
                setRequestProperty("User-Agent", "Poobi-TVBrowser")
            }
            if (conn.responseCode == 200) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else null
        } catch (e: Exception) { null }
    }

    private fun isNewerVersion(remote: String, current: String): Boolean {
        try {
            val rParts = remote.split("-")[0].split(".").map { it.filter { c -> c.isDigit() }.toIntOrNull() ?: 0 }
            val cParts = current.split("-")[0].split(".").map { it.filter { c -> c.isDigit() }.toIntOrNull() ?: 0 }
            val length = maxOf(rParts.size, cParts.size)

            for (i in 0 until length) {
                val r = rParts.getOrElse(i) { 0 }
                val c = cParts.getOrElse(i) { 0 }
                if (r > c) return true
                if (r < c) return false
            }
        } catch (_: Exception) {
            return remote != current
        }
        return false
    }

    private fun pickBestAssetForDevice(assets: JSONArray?): JSONObject? {
        if (assets == null || assets.length() == 0) return null

        val apkAssets = mutableListOf<JSONObject>()
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            val name = asset.optString("name", "").lowercase()
            if (name.endsWith(".apk")) {
                apkAssets.add(asset)
            }
        }

        if (apkAssets.isEmpty()) return null

        val deviceAbis = Build.SUPPORTED_ABIS.map { it.lowercase() }

        for (abi in deviceAbis) {
            for (asset in apkAssets) {
                val name = asset.optString("name", "").lowercase()
                val matches = when {
                    abi.contains("arm64") -> name.contains("arm64") || name.contains("aarch64")
                    abi.contains("armeabi-v7a") || abi.contains("armv7") ->
                        name.contains("armv7") || name.contains("armeabi-v7a") || (name.contains("arm") && !name.contains("arm64"))
                    abi.contains("x86_64") -> name.contains("x86_64")
                    abi.contains("x86") -> name.contains("x86") && !name.contains("x86_64")
                    else -> false
                }
                if (matches) return asset
            }
        }

        return apkAssets.find { it.optString("name").lowercase().contains("universal") }
            ?: apkAssets.firstOrNull()
    }

    fun startDownloadAndInstall(activity: Activity, update: UpdateInfo) {
        if (_isDownloading.value) return

        _isDownloading.value = true
        _downloadProgress.value = 0f

        downloadJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                val updateDir = File(activity.cacheDir, "updates").apply { if (!exists()) mkdirs() }
                val targetFile = File(updateDir, update.fileName)
                if (targetFile.exists()) targetFile.delete()

                val url = URL(update.downloadUrl)
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    connectTimeout = 15000
                    readTimeout = 30000
                    setRequestProperty("User-Agent", "Poobi-TVBrowser")
                }

                val contentLength = conn.contentLength.coerceAtLeast(1)

                conn.inputStream.use { input ->
                    FileOutputStream(targetFile).use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        var totalRead = 0L

                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            if (!isActive) {
                                targetFile.delete()
                                return@launch
                            }
                            output.write(buffer, 0, bytesRead)
                            totalRead += bytesRead
                            val progress = (totalRead.toFloat() / contentLength.toFloat()).coerceIn(0f, 1f)
                            withContext(Dispatchers.Main) {
                                _downloadProgress.value = progress
                            }
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    _isDownloading.value = false
                    _updateInfo.value = null
                    ApkInstaller.installApk(activity, targetFile)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Download failed", e)
                withContext(Dispatchers.Main) {
                    _isDownloading.value = false
                    Toast.makeText(activity, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        _isDownloading.value = false
        _downloadProgress.value = 0f
    }
}