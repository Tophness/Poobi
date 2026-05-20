package com.poobi.tvbrowser

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.webkit.MimeTypeMap
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import org.json.JSONArray
import java.io.File

class DownloadsActivity : AppCompatActivity() {

    private lateinit var downloadsContainer: LinearLayout
    private lateinit var emptyText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_downloads)

        downloadsContainer = findViewById(R.id.downloads_list_container)
        emptyText = findViewById(R.id.downloads_empty_text)

        loadDownloads()
    }

    private fun loadDownloads() {
        downloadsContainer.removeAllViews()
        val prefs = getSharedPreferences("BrowserSettings", Context.MODE_PRIVATE)
        val downloadsArray = JSONArray(prefs.getString("downloads", "[]"))

        if (downloadsArray.length() == 0) {
            emptyText.visibility = View.VISIBLE
            return
        } else {
            emptyText.visibility = View.GONE
        }

        val inflater = LayoutInflater.from(this)

        // Iterate backwards for newest first
        for (i in (downloadsArray.length() - 1) downTo 0) {
            val obj = downloadsArray.optJSONObject(i) ?: continue
            val fileName = obj.getString("title")
            val url = obj.getString("url")

            val view = inflater.inflate(R.layout.item_download_row, downloadsContainer, false)
            val nameTxt = view.findViewById<TextView>(R.id.dl_filename)
            val statusTxt = view.findViewById<TextView>(R.id.dl_status)

            nameTxt.text = fileName

            val file = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), fileName)
            if (file.exists()) {
                val sizeMb = file.length() / (1024f * 1024f)
                statusTxt.text = String.format("Downloaded - %.2f MB", sizeMb)
            } else {
                statusTxt.text = "File missing or deleted"
                statusTxt.setTextColor(android.graphics.Color.parseColor("#FF5252"))
            }

            view.setOnClickListener {
                if (file.exists()) openFile(file)
                else Toast.makeText(this, "File is missing", Toast.LENGTH_SHORT).show()
            }

            view.setOnLongClickListener {
                showDeleteDialog(fileName, url, file)
                true
            }

            downloadsContainer.addView(view)
        }
    }

    private fun openFile(file: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "${packageName}.provider", file)
            val extension = file.extension.lowercase()
            val mimeType = when (extension) {
                "apk" -> "application/vnd.android.package-archive"
                else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "*/*"
            }

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Cannot open this file type.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showDeleteDialog(fileName: String, url: String, file: File) {
        AlertDialog.Builder(this)
            .setTitle("Remove Download?")
            .setMessage("Do you want to remove $fileName from the list? (This will also delete the file from storage)")
            .setPositiveButton("Remove & Delete") { _, _ ->
                if (file.exists()) file.delete()

                val prefs = getSharedPreferences("BrowserSettings", Context.MODE_PRIVATE)
                val array = JSONArray(prefs.getString("downloads", "[]"))
                val newArray = JSONArray()

                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    if (obj.getString("url") != url) newArray.put(obj)
                }

                prefs.edit().putString("downloads", newArray.toString()).apply()
                loadDownloads() // Refresh UI
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}