package com.poobi.tvbrowser.shared.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import java.io.File

class DownloadCompleteReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return

        val downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
        if (downloadId == -1L) return

        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager ?: return
        val query = DownloadManager.Query().setFilterById(downloadId)

        try {
            val cursor = downloadManager.query(query)
            if (cursor != null && cursor.moveToFirst()) {
                val statusIdx = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                val status = if (statusIdx != -1) cursor.getInt(statusIdx) else -1

                if (status == DownloadManager.STATUS_SUCCESSFUL) {
                    val uriIdx = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                    val localUriStr = if (uriIdx != -1) cursor.getString(uriIdx) else null

                    val mediaTypeIdx = cursor.getColumnIndex(DownloadManager.COLUMN_MEDIA_TYPE)
                    val mediaType = if (mediaTypeIdx != -1) cursor.getString(mediaTypeIdx) else ""

                    if (localUriStr != null) {
                        val isApk = localUriStr.endsWith(".apk", ignoreCase = true) ||
                                mediaType.equals("application/vnd.android.package-archive", ignoreCase = true)

                        if (isApk) {
                            val uri = Uri.parse(localUriStr)
                            val path = uri.path
                            if (path != null) {
                                val file = File(path)
                                if (file.exists()) {
                                    ApkInstaller.installApk(context, file)
                                    return
                                }
                            }

                            val contentUri = downloadManager.getUriForDownloadedFile(downloadId)
                            if (contentUri != null) {
                                ApkInstaller.installFromUri(context, contentUri)
                            }
                        }
                    }
                }
                cursor.close()
            }
        } catch (e: Exception) {
            Log.e("DownloadReceiver", "Failed processing completed download", e)
        }
    }
}