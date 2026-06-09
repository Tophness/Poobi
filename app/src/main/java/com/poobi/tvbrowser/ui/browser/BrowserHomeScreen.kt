package com.poobi.tvbrowser.ui.browser

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.KeyEvent
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.poobi.tvbrowser.R
import com.poobi.tvbrowser.ui.shared.TvFocusableBox
import com.poobi.tvbrowser.ui.shared.TvSearchField
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class HomeTab { Tabs, Bookmarks, History, Downloads }

@Composable
fun BrowserHomeScreen(viewModel: BrowserViewModel) {
    val context = LocalContext.current

    var urlInput by remember { mutableStateOf("") }
    var activeTab by remember { mutableStateOf<HomeTab>(HomeTab.Tabs) }

    val historyArray by viewModel.historyList.collectAsState()
    val bookmarksArray by viewModel.favoritesList.collectAsState()
    val downloadsArray by viewModel.downloadsList.collectAsState()
    val savedTabsArray by viewModel.savedTabsList.collectAsState()

    val openTabsFocusRequester = remember { FocusRequester() }

    var bookmarkPage by remember { mutableStateOf(0) }
    val ITEMS_PER_PAGE = 10

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 40.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TvSearchField(
                value = urlInput,
                onValueChange = { urlInput = it },
                placeholder = "Search Google or type a URL...",
                onSearch = { viewModel.loadUrlAndBrowse(context, urlInput) },
                modifier = Modifier
                    .weight(1f)
                    .height(60.dp)
                    .onPreviewKeyEvent { keyEvent ->
                        val nativeEvent = keyEvent.nativeKeyEvent
                        val isActionDown = nativeEvent.action == KeyEvent.ACTION_DOWN
                        
                        if (isActionDown) {
                            when (nativeEvent.keyCode) {
                                KeyEvent.KEYCODE_DPAD_DOWN -> {
                                    openTabsFocusRequester.requestFocus()
                                    true
                                }
                                else -> false
                            }
                        } else false
                    }
            )

            TvFocusableBox(
                modifier = Modifier.size(60.dp).padding(start = 10.dp),
                onClick = { 
                    viewModel.loadUrlAndBrowse(context, urlInput)
                }
            ) { isFocused ->
                Icon(
                    painter = painterResource(id = R.drawable.ic_go),
                    contentDescription = "Go",
                    tint = Color.White,
                    modifier = Modifier.fillMaxSize().padding(12.dp)
                )
            }
        }

        // Sub Category Row Navigation Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 20.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            HomeCategoryButton(
                text = "Open Tabs", 
                iconRes = R.drawable.ic_tab, 
                isSelected = activeTab == HomeTab.Tabs,
                focusRequester = openTabsFocusRequester
            ) { activeTab = HomeTab.Tabs }
            HomeCategoryButton("Bookmarks", R.drawable.ic_heart_empty, activeTab == HomeTab.Bookmarks) { activeTab = HomeTab.Bookmarks }
            HomeCategoryButton("History", R.drawable.ic_history, activeTab == HomeTab.History) { activeTab = HomeTab.History }
            HomeCategoryButton("Downloads", R.drawable.ic_download, activeTab == HomeTab.Downloads) { activeTab = HomeTab.Downloads }
        }

        Box(modifier = Modifier.fillMaxWidth().height(2.dp).background(Color(0xFF00BCD4)))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color(0xFF1A1A1D))
                .padding(15.dp)
        ) {
            when (activeTab) {
                HomeTab.Tabs -> {
                    Column {
                        if (savedTabsArray.length() > 0) {
                            Row(modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.End) {
                                Button(
                                    onClick = { viewModel.restoreAllTabs(context) },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF333333))
                                ) {
                                    Text("Restore All Tabs", color = Color.White)
                                }
                            }
                        }
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(savedTabsArray.length()) { index ->
                                val item = savedTabsArray.getJSONObject(index)
                                val itemUrl = item.optString("url")
                                TvFocusableHoldToDeleteBox(
                                    modifier = Modifier.width(240.dp).height(50.dp),
                                    onTriggerDelete = {
                                        val array = JSONArray()
                                        for (i in 0 until savedTabsArray.length()) {
                                            if (i != index) array.put(savedTabsArray.get(i))
                                        }
                                        viewModel.prefs.edit().putString("saved_tabs", array.toString()).apply()
                                        viewModel.refreshLists()
                                    },
                                    onClick = { viewModel.loadUrlAndBrowse(context, itemUrl, newTab = true) }
                                ) { isFocused, progress ->
                                    TabItemCard(
                                        title = item.optString("title", "Saved Tab"),
                                        progress = progress
                                    )
                                }
                            }
                        }
                    }
                }

                HomeTab.Bookmarks -> {
                    Column {
                        val startIdx = bookmarkPage * ITEMS_PER_PAGE
                        val endIdx = minOf(startIdx + ITEMS_PER_PAGE, bookmarksArray.length())

                        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.End) {
                            if (bookmarkPage > 0) {
                                Button(
                                    onClick = { bookmarkPage-- },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF333333)),
                                    modifier = Modifier.padding(end = 8.dp)
                                ) {
                                    Text("< Prev", color = Color.White)
                                }
                            }
                            if (endIdx < bookmarksArray.length()) {
                                Button(
                                    onClick = { bookmarkPage++ },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF333333))
                                ) {
                                    Text("Next >", color = Color.White)
                                }
                            }
                        }

                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(endIdx - startIdx) { offset ->
                                val index = bookmarksArray.length() - 1 - (startIdx + offset)
                                if (index >= 0) {
                                    val item = bookmarksArray.getJSONObject(index)
                                    val itemUrl = item.optString("url")
                                    TvFocusableHoldToDeleteBox(
                                        modifier = Modifier.width(200.dp).height(160.dp),
                                        onTriggerDelete = { viewModel.removeFromList("favorites", itemUrl) },
                                        onClick = { viewModel.loadUrlAndBrowse(context, itemUrl, newTab = true) }
                                    ) { isFocused, progress ->
                                        BrowserCardItem(
                                            item = item,
                                            listKey = "favorites",
                                            progress = progress,
                                            isFocused = isFocused
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                HomeTab.History -> {
                    Column {
                        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.End) {
                            Button(
                                onClick = {
                                    viewModel.prefs.edit().putString("history", "[]").apply()
                                    viewModel.refreshLists()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935))
                            ) {
                                Text("Clear History", color = Color.White)
                            }
                        }

                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            items(historyArray.length()) { index ->
                                val item = historyArray.getJSONObject(historyArray.length() - 1 - index)
                                val itemUrl = item.optString("url")
                                TvFocusableHoldToDeleteBox(
                                    modifier = Modifier.width(200.dp).height(160.dp),
                                    onTriggerDelete = { viewModel.removeFromList("history", itemUrl) },
                                    onClick = { viewModel.loadUrlAndBrowse(context, itemUrl, newTab = true) }
                                ) { isFocused, progress ->
                                    BrowserCardItem(
                                        item = item,
                                        listKey = "history",
                                        progress = progress,
                                        isFocused = isFocused
                                    )
                                }
                            }
                        }
                    }
                }

                HomeTab.Downloads -> {
                    Column {
                        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.End) {
                            Button(
                                onClick = {
                                    viewModel.prefs.edit().putString("downloads", "[]").apply()
                                    viewModel.refreshLists()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935))
                            ) {
                                Text("Clear Downloads", color = Color.White)
                            }
                        }

                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            items(downloadsArray.length()) { index ->
                                val item = downloadsArray.getJSONObject(downloadsArray.length() - 1 - index)
                                val itemTitle = item.optString("title")
                                TvFocusableHoldToDeleteBox(
                                    modifier = Modifier.width(200.dp).height(160.dp),
                                    onTriggerDelete = {
                                        val array = JSONArray()
                                        for (i in 0 until downloadsArray.length()) {
                                            if (i != index) array.put(downloadsArray.get(i))
                                        }
                                        viewModel.prefs.edit().putString("downloads", array.toString()).apply()
                                        viewModel.refreshLists()
                                    },
                                    onClick = { openDownloadedFile(context, itemTitle) }
                                ) { isFocused, progress ->
                                    BrowserCardItem(
                                        item = item,
                                        listKey = "downloads",
                                        progress = progress,
                                        isFocused = isFocused
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun HomeCategoryButton(
    text: String, 
    iconRes: Int, 
    isSelected: Boolean,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit
) {
    val modifier = Modifier
        .height(50.dp)
        .let { if (focusRequester != null) it.focusRequester(focusRequester) else it }

    TvFocusableBox(
        modifier = modifier,
        isTabStyle = true,
        onClick = onClick,
        onFocus = onClick 
    ) { isFocused ->
        Row(
            modifier = Modifier.padding(horizontal = 25.dp).fillMaxHeight(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                painter = painterResource(id = iconRes),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(24.dp)
            )
            Text(
                text = text,
                color = Color.White,
                fontWeight = if (isSelected || isFocused) FontWeight.Bold else FontWeight.Normal
            )
        }
    }
}

private fun openDownloadedFile(context: Context, fileName: String) {
    try {
        val file = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), fileName)
        if (!file.exists()) {
            Toast.makeText(context, "File missing or deleted", Toast.LENGTH_SHORT).show()
            return
        }

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
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
        context.startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(context, "Cannot open this file type.", Toast.LENGTH_SHORT).show()
    }
}