package com.poobi.tvbrowser.browser

import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.poobi.tvbrowser.R
import com.poobi.tvbrowser.SettingsActivity
import com.poobi.tvbrowser.shared.TvFocusableBox
import com.poobi.tvbrowser.shared.TvInputField

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserTopBar(
    viewModel: BrowserViewModel,
    homeIconFocusRequester: FocusRequester
) {
    val context = LocalContext.current

    val currentUrl by viewModel.currentUrl.collectAsState()
    val activeIndex by viewModel.currentTabIndex.collectAsState()
    val isRecording by viewModel.isRecordingAutoplay.collectAsState()
    val videoTriggerPref by viewModel.videoTriggerPref.collectAsState()

    val favorites by viewModel.favoritesList.collectAsState()
    val isFav = remember(favorites, currentUrl) { viewModel.isFavorited(currentUrl) }

    var urlInput by remember(currentUrl) { mutableStateOf(currentUrl) }

    val addressBarFocusRequester = remember { FocusRequester() }

    val tabCount = viewModel.getWebViewsList().size
    val tabFocusRequesters = remember(tabCount) { List(tabCount) { FocusRequester() } }

    LaunchedEffect(viewModel.getWebViewsList().size, activeIndex) {
        if (viewModel.getWebViewsList().size > 0 && viewModel.isBrowsing.value) {
            if (activeIndex in tabFocusRequesters.indices) {
                try {
                    tabFocusRequesters[activeIndex].requestFocus()
                } catch (e: Exception) {
                    addressBarFocusRequester.requestFocus()
                }
            } else {
                addressBarFocusRequester.requestFocus()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xF2111111))
    ) {
        // Tabs Selection Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
                .background(Color(0xFF222222))
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LazyRow(modifier = Modifier.weight(1f)) {
                itemsIndexed(viewModel.getWebViewsList()) { index, wv ->
                    val title = wv.title ?: wv.url ?: "New Tab"
                    val itemFocusRequester = tabFocusRequesters.getOrNull(index) ?: remember { FocusRequester() }
                    
                    TvFocusableHoldToDeleteBox(
                        modifier = Modifier
                            .width(240.dp)
                            .height(50.dp)
                            .focusRequester(itemFocusRequester),
                        isTabStyle = true,
                        isSelected = index == activeIndex,
                        onTriggerDelete = { viewModel.closeTab(index) },
                        onClick = { viewModel.switchTab(index) }
                    ) { isFocused, progress ->
                        TabItemCard(
                            title = title,
                            progress = progress
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.width(10.dp))
            TopBarIconButton(R.drawable.ic_add) { viewModel.createNewTab(context) }
        }

        // Action Navigation Toolbar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(65.dp)
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TvFocusableBox(
                modifier = Modifier
                    .size(40.dp)
                    .focusRequester(homeIconFocusRequester),
                onClick = { viewModel.showHomeScreen() }
            ) { isFocused ->
                Icon(
                    painter = painterResource(id = R.drawable.ic_home),
                    contentDescription = "Home",
                    tint = Color.White,
                    modifier = Modifier.fillMaxSize().padding(8.dp)
                )
            }

            TopBarIconButton(R.drawable.ic_back) {
                if (viewModel.currentWebView?.canGoBack() == true) {
                    viewModel.currentWebView?.goBack()
                } else {
                    viewModel.closeTab(viewModel.currentTabIndex.value)
                }
            }
            TopBarIconButton(R.drawable.ic_forward) { viewModel.currentWebView?.goForward() }

            TvInputField(
                value = urlInput,
                onValueChange = { urlInput = it },
                placeholder = "Type a URL...",
                imeAction = ImeAction.Go,
                onAction = { viewModel.loadUrlAndBrowse(context, urlInput) },
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 4.dp)
                    .height(48.dp)
                    .focusRequester(addressBarFocusRequester)
            )

            TopBarIconButton(R.drawable.ic_go) { 
                viewModel.loadUrlAndBrowse(context, urlInput)
            }
            TopBarIconButton(R.drawable.ic_refresh) { viewModel.currentWebView?.reload() }

            TopBarIconButton(
                iconId = R.drawable.ic_auto_play,
                tint = if (videoTriggerPref == 0) Color(0xFF00BCD4) else Color.White
            ) {
                val nextMode = if (videoTriggerPref == 0) 1 else 0
                viewModel.prefs.edit().putInt("video_trigger_pref", nextMode).apply()
                viewModel.videoTriggerPref.value = nextMode
            }

            if (videoTriggerPref == 0) {
                TopBarIconButton(
                    iconId = if (isRecording) R.drawable.ic_stop else R.drawable.ic_record,
                    tint = if (isRecording) Color.Red else Color.White
                ) {
                    if (isRecording) viewModel.stopRecordingAutoplay() else viewModel.startRecordingAutoplay()
                }
            }

            TopBarIconButton(
                iconId = if (isFav) R.drawable.ic_heart_filled else R.drawable.ic_heart_empty,
                tint = if (isFav) Color(0xFFE91E63) else Color.White
            ) {
                viewModel.toggleFavoriteOnCurrent()
            }
            
            TopBarIconButton(R.drawable.ic_settings) {
                context.startActivity(Intent(context, SettingsActivity::class.java))
            }
        }
    }
}

@Composable
fun TopBarIconButton(iconId: Int, tint: Color = Color.White, onClick: () -> Unit) {
    TvFocusableBox(
        modifier = Modifier.size(40.dp),
        onClick = onClick
    ) {
        Icon(
            painter = painterResource(id = iconId),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.fillMaxSize().padding(8.dp)
        )
    }
}