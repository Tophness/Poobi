package com.poobi.tvbrowser.browser

import android.content.Context
import android.content.Intent
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.poobi.tvbrowser.R
import com.poobi.tvbrowser.shared.TvFocusableBox
import com.poobi.tvbrowser.shared.TvSearchField
import com.poobi.tvbrowser.shared.mouseWheelHorizontalScroll
import org.json.JSONArray
import java.io.File

enum class HomeTab { Tabs, Bookmarks, History, Downloads }

@Composable
fun BrowserHomeScreen(
    viewModel: BrowserViewModel,
    searchBarFocusRequester: FocusRequester? = null,
    topTabFocusRequester: FocusRequester? = null
) {
    val context = LocalContext.current

    var urlInput by remember { mutableStateOf("") }
    var activeTab by remember { mutableStateOf<HomeTab>(HomeTab.Tabs) }

    val historyArray by viewModel.historyList.collectAsState()
    val bookmarksArray by viewModel.favoritesList.collectAsState()
    val downloadsArray by viewModel.downloadsList.collectAsState()
    val savedTabsArray by viewModel.savedTabsList.collectAsState()

    val searchFieldFocusRequester = searchBarFocusRequester ?: remember { FocusRequester() }
    val goButtonFocusRequester = remember { FocusRequester() }
    val openTabsFocusRequester = remember { FocusRequester() }
    val bookmarksFocusRequester = remember { FocusRequester() }
    val historyFocusRequester = remember { FocusRequester() }
    val downloadsFocusRequester = remember { FocusRequester() }
    val restoreAllFocusRequester = remember { FocusRequester() }
    val tabsLazyRowFocusRequester = remember { FocusRequester() }

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
                .padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TvSearchField(
                value = urlInput,
                onValueChange = { urlInput = it },
                placeholder = "Search Google or type a URL...",
                onSearch = { viewModel.loadUrlAndBrowse(context, urlInput) },
                focusRequester = searchFieldFocusRequester,
                modifier = Modifier
                    .weight(1f)
                    .height(36.dp)
                    .focusProperties {
                        right = goButtonFocusRequester
                        down = when (activeTab) {
                            HomeTab.Tabs -> openTabsFocusRequester
                            HomeTab.Bookmarks -> bookmarksFocusRequester
                            HomeTab.History -> historyFocusRequester
                            HomeTab.Downloads -> downloadsFocusRequester
                        }
                        if (topTabFocusRequester != null) {
                            up = topTabFocusRequester
                        }
                    }
            )

            TvFocusableBox(
                modifier = Modifier
                    .padding(start = 10.dp)
                    .size(36.dp)
                    .focusRequester(goButtonFocusRequester)
                    .focusProperties {
                        left = searchFieldFocusRequester
                        down = when (activeTab) {
                            HomeTab.Tabs -> openTabsFocusRequester
                            HomeTab.Bookmarks -> bookmarksFocusRequester
                            HomeTab.History -> historyFocusRequester
                            HomeTab.Downloads -> downloadsFocusRequester
                        }
                        if (topTabFocusRequester != null) {
                            up = topTabFocusRequester
                        }
                    },
                onClick = { 
                    viewModel.loadUrlAndBrowse(context, urlInput) 
                }
            ) { isFocused ->
                Icon(
                    painter = painterResource(id = R.drawable.ic_go),
                    contentDescription = "Go",
                    tint = if (isFocused) Color.Black else Color.White,
                    modifier = Modifier.fillMaxSize().padding(8.dp)
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            HomeCategoryButton(
                text = "Open Tabs", 
                iconRes = R.drawable.ic_tab, 
                isSelected = activeTab == HomeTab.Tabs,
                focusRequester = openTabsFocusRequester,
                modifier = Modifier.focusProperties {
                    up = searchFieldFocusRequester
                }
            ) { activeTab = HomeTab.Tabs }

            HomeCategoryButton(
                text = "Bookmarks", 
                iconRes = R.drawable.ic_heart_empty, 
                isSelected = activeTab == HomeTab.Bookmarks,
                focusRequester = bookmarksFocusRequester,
                modifier = Modifier.focusProperties {
                    up = searchFieldFocusRequester
                }
            ) { activeTab = HomeTab.Bookmarks }

            HomeCategoryButton(
                text = "History", 
                iconRes = R.drawable.ic_history, 
                isSelected = activeTab == HomeTab.History,
                focusRequester = historyFocusRequester,
                modifier = Modifier.focusProperties {
                    up = searchFieldFocusRequester
                }
            ) { activeTab = HomeTab.History }

            HomeCategoryButton(
                text = "Downloads", 
                iconRes = R.drawable.ic_download, 
                isSelected = activeTab == HomeTab.Downloads,
                focusRequester = downloadsFocusRequester,
                modifier = Modifier.focusProperties {
                    up = searchFieldFocusRequester
                }
            ) { activeTab = HomeTab.Downloads }
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
                    Column(modifier = Modifier.fillMaxSize()) {
                        val tabsListState = rememberLazyListState()
                        LazyRow(
                            state = tabsListState,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(vertical = 8.dp, horizontal = 4.dp),
                            modifier = Modifier
                                .focusRequester(tabsLazyRowFocusRequester)
                                .mouseWheelHorizontalScroll(tabsListState)
                        ) {
                            items(savedTabsArray.length()) { index ->
                                val item = savedTabsArray.getJSONObject(index)
                                val itemUrl = item.optString("url")
                                TvFocusableHoldToDeleteBox(
                                    modifier = Modifier
                                        .width(240.dp)
                                        .height(50.dp)
                                        .focusProperties {
                                            up = openTabsFocusRequester
                                            if (savedTabsArray.length() > 0) {
                                                down = restoreAllFocusRequester
                                            }
                                        },
                                    onTriggerDelete = {
                                        if (index in viewModel.getWebViewsList().indices) {
                                            viewModel.closeTab(index)
                                        } else {
                                            val array = JSONArray()
                                            for (i in 0 until savedTabsArray.length()) {
                                                if (i != index) array.put(savedTabsArray.get(i))
                                            }
                                            viewModel.prefs.edit().putString("saved_tabs", array.toString()).apply()
                                            viewModel.refreshLists()
                                        }
                                    },
                                    onClick = {
                                        if (index in viewModel.getWebViewsList().indices) {
                                            viewModel.switchTab(index)
                                        } else {
                                            viewModel.loadUrlAndBrowse(context, itemUrl, newTab = true)
                                        }
                                    }
                                ) { isFocused, progress ->
                                    TabItemCard(
                                        title = item.optString("title", "Saved Tab"),
                                        progress = progress,
                                        isFocused = isFocused,
                                        onCloseClick = {
                                            if (index in viewModel.getWebViewsList().indices) {
                                                viewModel.closeTab(index)
                                            } else {
                                                val array = JSONArray()
                                                for (i in 0 until savedTabsArray.length()) {
                                                    if (i != index) array.put(savedTabsArray.get(i))
                                                }
                                                viewModel.prefs.edit().putString("saved_tabs", array.toString()).apply()
                                                viewModel.refreshLists()
                                            }
                                        }
                                    )
                                }
                            }
                        }

                        if (savedTabsArray.length() > 0) {
                            Spacer(modifier = Modifier.height(16.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TvFocusableBox(
                                    modifier = Modifier
                                        .height(40.dp)
                                        .focusRequester(restoreAllFocusRequester)
                                        .focusProperties { 
                                            up = tabsLazyRowFocusRequester 
                                        },
                                    defaultColor = Color(0xFFE53935),
                                    shape = RoundedCornerShape(20.dp),
                                    onClick = { viewModel.restoreAllTabs(context) }
                                ) { isFocused ->
                                    Box(
                                        modifier = Modifier
                                            .fillMaxHeight()
                                            .padding(horizontal = 24.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "Restore All Tabs",
                                            color = if (isFocused) Color.Black else Color.White,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                HomeTab.Bookmarks -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        val startIdx = bookmarkPage * ITEMS_PER_PAGE
                        val endIdx = minOf(startIdx + ITEMS_PER_PAGE, bookmarksArray.length())

                        if (bookmarkPage > 0 || endIdx < bookmarksArray.length()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 8.dp),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
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
                        }

                        val bookmarksListState = rememberLazyListState()
                        LazyRow(
                            state = bookmarksListState,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(vertical = 8.dp, horizontal = 4.dp),
                            modifier = Modifier.mouseWheelHorizontalScroll(bookmarksListState)
                        ) {
                            items(endIdx - startIdx) { offset ->
                                val index = bookmarksArray.length() - 1 - (startIdx + offset)
                                if (index >= 0) {
                                    val item = bookmarksArray.getJSONObject(index)
                                    val itemUrl = item.optString("url")
                                    TvFocusableHoldToDeleteBox(
                                        modifier = Modifier
                                            .width(200.dp)
                                            .height(160.dp)
                                            .focusProperties {
                                                up = bookmarksFocusRequester
                                            },
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

                        if (bookmarksArray.length() > 0) {
                            Spacer(modifier = Modifier.height(16.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TvFocusableBox(
                                    modifier = Modifier.height(40.dp),
                                    defaultColor = Color(0xFFE53935),
                                    shape = RoundedCornerShape(20.dp),
                                    onClick = {
                                        viewModel.prefs.edit().putString("favorites", "[]").apply()
                                        viewModel.refreshLists()
                                    }
                                ) { isFocused ->
                                    Box(
                                        modifier = Modifier
                                            .fillMaxHeight()
                                            .padding(horizontal = 24.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "Clear Bookmarks",
                                            color = if (isFocused) Color.Black else Color.White,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                HomeTab.History -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        val historyListState = rememberLazyListState()
                        LazyRow(
                            state = historyListState,
                            horizontalArrangement = Arrangement.spacedBy(8.dp), 
                            modifier = Modifier
                                .fillMaxWidth()
                                .mouseWheelHorizontalScroll(historyListState),
                            contentPadding = PaddingValues(vertical = 8.dp, horizontal = 4.dp)
                        ) {
                            items(historyArray.length()) { index ->
                                val item = historyArray.getJSONObject(historyArray.length() - 1 - index)
                                val itemUrl = item.optString("url")
                                TvFocusableHoldToDeleteBox(
                                    modifier = Modifier
                                        .width(200.dp)
                                        .height(160.dp)
                                        .focusProperties {
                                            up = historyFocusRequester
                                        },
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

                        if (historyArray.length() > 0) {
                            Spacer(modifier = Modifier.height(16.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TvFocusableBox(
                                    modifier = Modifier.height(40.dp),
                                    defaultColor = Color(0xFFE53935),
                                    shape = RoundedCornerShape(20.dp),
                                    onClick = {
                                        viewModel.prefs.edit().putString("history", "[]").apply()
                                        viewModel.refreshLists()
                                    }
                                ) { isFocused ->
                                    Box(
                                        modifier = Modifier
                                            .fillMaxHeight()
                                            .padding(horizontal = 24.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "Clear History",
                                            color = if (isFocused) Color.Black else Color.White,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                HomeTab.Downloads -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        val downloadsListState = rememberLazyListState()
                        LazyRow(
                            state = downloadsListState,
                            horizontalArrangement = Arrangement.spacedBy(8.dp), 
                            modifier = Modifier
                                .fillMaxWidth()
                                .mouseWheelHorizontalScroll(downloadsListState),
                            contentPadding = PaddingValues(vertical = 8.dp, horizontal = 4.dp)
                        ) {
                            items(downloadsArray.length()) { index ->
                                val item = downloadsArray.getJSONObject(downloadsArray.length() - 1 - index)
                                val itemTitle = item.optString("title")
                                TvFocusableHoldToDeleteBox(
                                    modifier = Modifier
                                        .width(200.dp)
                                        .height(160.dp)
                                        .focusProperties {
                                            up = downloadsFocusRequester
                                        },
                                    onTriggerDelete = {
                                        try {
                                            val file = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), itemTitle)
                                            if (file.exists()) file.delete()
                                        } catch (e: Exception) {}

                                        val array = JSONArray()
                                        for (i in 0 until downloadsArray.length()) {
                                            val obj = downloadsArray.getJSONObject(i)
                                            if (obj.optString("title") != itemTitle) {
                                                array.put(obj)
                                            }
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

                        if (downloadsArray.length() > 0) {
                            Spacer(modifier = Modifier.height(16.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TvFocusableBox(
                                    modifier = Modifier.height(40.dp),
                                    defaultColor = Color(0xFFE53935),
                                    shape = RoundedCornerShape(20.dp),
                                    onClick = {
                                        viewModel.prefs.edit().putString("downloads", "[]").apply()
                                        viewModel.refreshLists()
                                    }
                                ) { isFocused ->
                                    Box(
                                        modifier = Modifier
                                            .fillMaxHeight()
                                            .padding(horizontal = 24.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "Clear Downloads",
                                            color = if (isFocused) Color.Black else Color.White,
                                            fontWeight = FontWeight.Bold
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
}

@Composable
fun HomeCategoryButton(
    text: String, 
    iconRes: Int, 
    isSelected: Boolean,
    focusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val buttonModifier = modifier
        .height(46.dp)
        .let { if (focusRequester != null) it.focusRequester(focusRequester) else it }

    TvFocusableBox(
        modifier = buttonModifier,
        isTabStyle = true,
        onClick = onClick,
        onFocus = onClick 
    ) { isFocused ->
        Row(
            modifier = Modifier.padding(horizontal = 22.dp).fillMaxHeight(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                painter = painterResource(id = iconRes),
                contentDescription = null,
                tint = if (isFocused) Color.Black else Color.White,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = text,
                color = if (isFocused) Color.Black else Color.White,
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