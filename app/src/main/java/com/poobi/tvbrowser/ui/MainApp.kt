package com.poobi.tvbrowser.ui

import android.content.Intent
import android.util.Log
import android.view.ViewGroup
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import com.poobi.tvbrowser.R
import com.poobi.tvbrowser.SettingsActivity
import com.poobi.tvbrowser.ui.browser.BrowserDialogState
import com.poobi.tvbrowser.ui.browser.BrowserHomeScreen
import com.poobi.tvbrowser.ui.browser.BrowserTopBar
import com.poobi.tvbrowser.ui.browser.BrowserViewModel
import com.poobi.tvbrowser.ui.browser.ContextMenuOverlay
import com.poobi.tvbrowser.ui.browser.CursorManager
import com.poobi.tvbrowser.ui.player.PlayerEngine
import com.poobi.tvbrowser.ui.player.UpNextOverlay
import com.poobi.tvbrowser.ui.shared.TvFocusableBox
import com.poobi.tvbrowser.ui.streams.MediaDetailsScreen
import com.poobi.tvbrowser.ui.streams.ScrapeProgressScreen
import com.poobi.tvbrowser.ui.streams.StreamsDashboardScreen
import com.poobi.tvbrowser.ui.streams.StreamsViewModel

enum class AppTab { Browser, Streams }

@Composable
fun MainApp(
    browserViewModel: BrowserViewModel,
    streamsViewModel: StreamsViewModel,
    cursorManager: CursorManager,
    playerEngine: PlayerEngine
) {
    val context = LocalContext.current
    val currentAppTabInt by browserViewModel.currentAppTab.collectAsState()
    val currentTab = if (currentAppTabInt == 0) AppTab.Browser else AppTab.Streams

    val isPlayerActive by playerEngine.isPlayerActive.collectAsState()
    val isBrowsing by browserViewModel.isBrowsing.collectAsState()
    val topBarVisible by browserViewModel.topBarVisible.collectAsState()
    val dialogState by browserViewModel.currentDialog.collectAsState()
    val activeIndex by browserViewModel.currentTabIndex.collectAsState()

    val isScraping by streamsViewModel.isScraping.collectAsState()
    val scrapedSources by streamsViewModel.scrapedSources.collectAsState()
    val selectedMedia by streamsViewModel.selectedItem.collectAsState()

    val homeIconFocusRequester = remember { FocusRequester() }

    LaunchedEffect(topBarVisible) {
        if (topBarVisible) {
            homeIconFocusRequester.requestFocus()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF1A1A1D))) {
        Column(modifier = Modifier.fillMaxSize()) {

            // Tab Bar - Completely Hidden during Active Browsing to isolate DPAD focus!
            AnimatedVisibility(
                visible = !isPlayerActive && !isBrowsing,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(72.dp)
                        .background(Color(0xFF1E1E24))
                        .padding(horizontal = 40.dp),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    TvFocusableBox(
                        modifier = Modifier.height(50.dp),
                        isTabStyle = true,
                        isSelected = currentTab == AppTab.Browser,
                        onClick = { browserViewModel.currentAppTab.value = 0 }
                    ) { isFocused ->
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxHeight()) {
                            Text(
                                text = "Browser",
                                modifier = Modifier.padding(horizontal = 35.dp),
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    TvFocusableBox(
                        modifier = Modifier.height(50.dp),
                        isTabStyle = true,
                        isSelected = currentTab == AppTab.Streams,
                        onClick = { browserViewModel.currentAppTab.value = 1 }
                    ) { isFocused ->
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxHeight()) {
                            Text(
                                text = "Streams",
                                modifier = Modifier.padding(horizontal = 35.dp),
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    
                    Spacer(modifier = Modifier.weight(1f))
                    TvFocusableBox(
                        modifier = Modifier.size(50.dp).padding(bottom = 5.dp),
                        onClick = { context.startActivity(Intent(context, SettingsActivity::class.java)) }
                    ) { isFocused ->
                        Icon(
                            painter = painterResource(id = R.drawable.ic_settings),
                            contentDescription = "Settings",
                            tint = Color.White,
                            modifier = Modifier.fillMaxSize().padding(10.dp)
                        )
                    }
                }
            }
            
            AnimatedVisibility(
                visible = !isPlayerActive && !isBrowsing,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Box(modifier = Modifier.fillMaxWidth().height(2.dp).background(Color(0xFF00BCD4)))
            }

            // Main Content Area
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (currentTab) {
                    AppTab.Browser -> {
                        if (isBrowsing) {
                            Column {
                                if (topBarVisible) {
                                    BrowserTopBar(
                                        viewModel = browserViewModel,
                                        homeIconFocusRequester = homeIconFocusRequester
                                    )
                                }
                                
                                // Restored the highly dynamic, single-active AndroidView swap mechanics with LOGS!
                                if (activeIndex in browserViewModel.getWebViewsList().indices) {
                                    key(activeIndex) {
                                        AndroidView(
                                            factory = { ctx ->
                                                val wv = browserViewModel.getWebViewsList()[activeIndex]
                                                wv.apply {
                                                    val prevParent = parent as? ViewGroup
                                                    if (prevParent != null) {
                                                        prevParent.removeView(this)
                                                    }
                                                    layoutParams = ViewGroup.LayoutParams(
                                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                                        ViewGroup.LayoutParams.MATCH_PARENT
                                                    )
                                                }
                                            },
                                            modifier = Modifier.fillMaxSize(),
                                            update = { currentView ->
                                                val targetWebView = browserViewModel.currentWebView
                                                
                                                if (currentView != targetWebView && targetWebView != null) {
                                                    val parentContainer = currentView.parent as? ViewGroup
                                                    if (parentContainer != null) {
                                                        parentContainer.removeView(currentView)
                                                        val targetPrevParent = targetWebView.parent as? ViewGroup
                                                        if (targetPrevParent != null) {
                                                            targetPrevParent.removeView(targetWebView)
                                                        }
                                                        parentContainer.addView(targetWebView)
                                                    }
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        } else {
                            BrowserHomeScreen(browserViewModel)
                        }
                    }
                    AppTab.Streams -> {
                        when {
                            isScraping || scrapedSources != null -> ScrapeProgressScreen(streamsViewModel)
                            selectedMedia != null -> MediaDetailsScreen(streamsViewModel)
                            else -> StreamsDashboardScreen(streamsViewModel)
                        }
                    }
                }
            }
        }

        // Virtual Cursor Pointer Overlay
        val cursorVisible by cursorManager.cursorVisible.collectAsState()
        val cx by cursorManager.cursorX.collectAsState()
        val cy by cursorManager.cursorY.collectAsState()
        val handStyle by cursorManager.cursorHandStyle.collectAsState()

        if (cursorVisible && isBrowsing && !isPlayerActive && currentTab == AppTab.Browser) {
            Image(
                painter = painterResource(id = if (handStyle) R.drawable.ic_hand else R.drawable.ic_cursor),
                contentDescription = null,
                modifier = Modifier.size(32.dp).offset { IntOffset(cx.toInt(), cy.toInt()) },
                colorFilter = ColorFilter.tint(Color(0xFF40C4FF))
            )
        }

        if (isPlayerActive) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            player = playerEngine.exoPlayer
                            useController = true
                            setShowSubtitleButton(true) // Display the subtitle track selector control
                            playerEngine.playerView = this
                            
                            // Explicit type specification resolves the SAM conversion ambiguous overload candidates
                            setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
                                if (visibility == android.view.View.VISIBLE) {
                                    post {
                                        val playPauseBtn = findViewById<android.view.View>(androidx.media3.ui.R.id.exo_play_pause)
                                            ?: findViewById<android.view.View>(resources.getIdentifier("exo_play_pause", "id", "androidx.media3.ui"))
                                            ?: findViewById<android.view.View>(resources.getIdentifier("exo_play_pause", "id", context.packageName))
                                        playPauseBtn?.requestFocus()
                                    }
                                }
                            })
                            
                            requestFocus()
                        }
                    },
                    update = { view ->
                        if (view.player != playerEngine.exoPlayer) {
                            view.player = playerEngine.exoPlayer
                        }
                        playerEngine.playerView = view
                    },
                    onRelease = {
                        playerEngine.playerView = null
                    },
                    modifier = Modifier.fillMaxSize()
                )

                val showUpNext by playerEngine.showUpNext.collectAsState()
                val nextEpisodeData by playerEngine.nextEpisodeData.collectAsState()

                UpNextOverlay(
                    isVisible = showUpNext,
                    nextEpisodeJson = nextEpisodeData,
                    onTriggerAutoplay = {
                        playerEngine.dismissUpNext()
                        playerEngine.stopAndRelease()
                        streamsViewModel.selectedItem.value?.let { item ->
                            val season = streamsViewModel.lastScrapedSeason
                            val episode = streamsViewModel.lastScrapedEpisode
                            if (season != null && episode != null) {
                                streamsViewModel.handleNextEpisodeAutoPlay(item, season, episode)
                            }
                        }
                    },
                    onDismissOverlay = {
                        playerEngine.dismissUpNext()
                    }
                )
            }
        }

        // Floating Context Menu Dropdown Overlay positioned at the EXACT cursor tip
        if (dialogState is BrowserDialogState.SaveBlockRule) {
            val rule = dialogState as BrowserDialogState.SaveBlockRule
            ContextMenuOverlay(
                cursorX = cx,
                cursorY = cy,
                url = rule.url,
                onOpenInNewTab = { 
                    browserViewModel.createNewTab(context, rule.url) 
                },
                onRefresh = { browserViewModel.currentWebView?.reload() },
                onBlockElement = { browserViewModel.blockElementAtCursor(cx, cy) },
                onDismiss = { browserViewModel.dismissDialog() }
            )
        }
    }
}