package com.poobi.tvbrowser

import android.content.Intent
import android.net.Uri
import android.util.Log
import android.view.KeyEvent
import android.view.ViewGroup
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import com.poobi.tvbrowser.R
import com.poobi.tvbrowser.SettingsActivity
import com.poobi.tvbrowser.MainActivity
import com.poobi.tvbrowser.browser.BrowserDialogState
import com.poobi.tvbrowser.browser.BrowserHomeScreen
import com.poobi.tvbrowser.browser.BrowserTopBar
import com.poobi.tvbrowser.browser.BrowserViewModel
import com.poobi.tvbrowser.browser.ContextMenuOverlay
import com.poobi.tvbrowser.browser.CursorManager
import com.poobi.tvbrowser.player.PlayerEngine
import com.poobi.tvbrowser.player.UpNextOverlay
import com.poobi.tvbrowser.shared.TvFocusableBox
import com.poobi.tvbrowser.shared.TvInputField
import com.poobi.tvbrowser.streams.MediaDetailsScreen
import com.poobi.tvbrowser.streams.ScrapeProgressScreen
import com.poobi.tvbrowser.streams.StreamsDashboardScreen
import com.poobi.tvbrowser.streams.StreamsViewModel
import org.json.JSONArray
import org.json.JSONObject

enum class AppTab { Browser, Streams }

// Synchronous, lag-proof key state tracking to prevent Compose state race conditions
class KeyTracker {
    var lastKeyCode: Int = -1
    var lastKeyPressTime: Long = 0L
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ModernTab(
    text: String,
    isSelected: Boolean,
    onFocus: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester,
    keyTracker: KeyTracker
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val currentOnFocus by rememberUpdatedState(onFocus)
    val currentIsSelected by rememberUpdatedState(isSelected)

    val backgroundColor = when {
        isFocused -> Color(0xFF00BCD4) // Vibrant Cyan on focus
        isSelected -> Color(0xFF00BCD4).copy(alpha = 0.25f) // Subtle Cyan background on selection
        else -> Color.Transparent
    }

    val textColor = when {
        isFocused -> Color.Black
        isSelected -> Color(0xFF00BCD4)
        else -> Color.Gray
    }

    val borderModifier = if (isFocused) {
        Modifier.border(2.dp, Color.White, RoundedCornerShape(20.dp))
    } else if (isSelected) {
        Modifier.border(1.dp, Color(0xFF00BCD4).copy(alpha = 0.5f), RoundedCornerShape(20.dp))
    } else {
        Modifier
    }

    // Flawless focus trigger driven directly by the InteractionSource
    LaunchedEffect(isFocused) {
        if (isFocused) {
            val keyCode = keyTracker.lastKeyCode
            val isUserInitiated = keyCode == KeyEvent.KEYCODE_DPAD_UP ||
                                  keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
                                  keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ||
                                  keyCode == -1 // Startup bypass

            Log.d("PoobiFocus", "ModernTab '$text' gained focus. isUserInitiated=$isUserInitiated, LastKey=$keyCode")

            if (isUserInitiated) {
                Log.d("PoobiFocus", "Tab switch ALLOWED for '$text'")
                currentOnFocus()
            } else {
                Log.d("PoobiFocus", "Tab switch BLOCKED for '$text'")
            }
        }
    }

    Box(
        modifier = modifier
            .focusRequester(focusRequester)
            .height(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(backgroundColor)
            .then(borderModifier)
            .focusProperties {
                // Evaluated dynamically and synchronously by the focus engine to bypass Compose recomposition latency
                val lastKey = keyTracker.lastKeyCode
                val isStartup = lastKey == -1
                val isDpadLeft = lastKey == KeyEvent.KEYCODE_DPAD_LEFT
                val isDpadRight = lastKey == KeyEvent.KEYCODE_DPAD_RIGHT

                val computedCanFocus = if (text == "Browser") {
                    currentIsSelected || isStartup || isDpadLeft
                } else {
                    currentIsSelected || isStartup || isDpadRight
                }

                this.canFocus = computedCanFocus || isFocused
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .focusable(interactionSource = interactionSource),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 24.dp),
            color = textColor,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@OptIn(ExperimentalComposeUiApi::class)
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

    val customView by browserViewModel.customView.collectAsState()

    val homeIconFocusRequester = remember { FocusRequester() }
    val browserTabFocusRequester = remember { FocusRequester() }
    val streamsTabFocusRequester = remember { FocusRequester() }

    // Synchronous track of physical key interactions
    val keyTracker = remember { KeyTracker() }

    // Log active layout transitions inside the streams tab
    LaunchedEffect(currentTab, isScraping, scrapedSources, selectedMedia) {
        Log.d("PoobiFocus", "Layout changed: currentTab=$currentTab, isScraping=$isScraping, hasSources=${scrapedSources != null}, hasSelectedMedia=${selectedMedia != null}")
    }

    LaunchedEffect(topBarVisible) {
        if (topBarVisible) {
            homeIconFocusRequester.requestFocus()
        }
    }

    LaunchedEffect(Unit) {
        try {
            if (currentTab == AppTab.Browser) {
                browserTabFocusRequester.requestFocus()
            } else {
                streamsTabFocusRequester.requestFocus()
            }
        } catch (e: Exception) {}
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1A1A1D))
            .onPreviewKeyEvent { keyEvent ->
                if (keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                    keyTracker.lastKeyCode = keyEvent.nativeKeyEvent.keyCode
                    keyTracker.lastKeyPressTime = System.currentTimeMillis()
                    Log.d("PoobiFocus", "Root onPreviewKeyEvent - Captured: ${keyTracker.lastKeyCode}")
                }
                false
            }
    ) {
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
                        .padding(horizontal = 40.dp)
                        .focusProperties {
                            onEnter = {
                                val direction = requestedFocusDirection
                                Log.d("PoobiFocus", "Row focusProperties.onEnter triggered. Direction: $direction")
                                when (direction) {
                                    FocusDirection.Up -> {
                                        val target = if (currentTab == AppTab.Browser) browserTabFocusRequester else streamsTabFocusRequester
                                        Log.d("PoobiFocus", "DPAD Up received. Explicitly directing focus to: ${if (currentTab == AppTab.Browser) "Browser" else "Streams"}")
                                        target
                                    }
                                    FocusDirection.Left, FocusDirection.Right -> {
                                        FocusRequester.Default
                                    }
                                    else -> {
                                        Log.d("PoobiFocus", "Row focus enter passed through with Default (non-blocking).")
                                        FocusRequester.Default // Pass through to avoid aborting the global search chain
                                    }
                                }
                            }
                        },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ModernTab(
                        text = "Browser",
                        isSelected = currentTab == AppTab.Browser,
                        onFocus = { browserViewModel.currentAppTab.value = 0 },
                        onClick = { browserViewModel.currentAppTab.value = 0 },
                        focusRequester = browserTabFocusRequester,
                        keyTracker = keyTracker
                    )

                    ModernTab(
                        text = "Streams",
                        isSelected = currentTab == AppTab.Streams,
                        onFocus = { browserViewModel.currentAppTab.value = 1 },
                        onClick = { browserViewModel.currentAppTab.value = 1 },
                        focusRequester = streamsTabFocusRequester,
                        keyTracker = keyTracker
                    )
                    
                    Spacer(modifier = Modifier.weight(1f))

                    val settingsInteractionSource = remember { MutableInteractionSource() }
                    val isSettingsFocused by settingsInteractionSource.collectIsFocusedAsState()

                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(if (isSettingsFocused) Color(0xFF00BCD4) else Color.Transparent)
                            .then(if (isSettingsFocused) Modifier.border(2.dp, Color.White, CircleShape) else Modifier)
                            .clickable(
                                interactionSource = settingsInteractionSource,
                                indication = null,
                                onClick = { context.startActivity(Intent(context, SettingsActivity::class.java)) }
                            )
                            .focusable(interactionSource = settingsInteractionSource),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_settings),
                            contentDescription = "Settings",
                            tint = if (isSettingsFocused) Color.Black else Color.White,
                            modifier = Modifier.size(20.dp)
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
                            // ONLY show ScrapeProgressScreen if we are actively scraping source links for a selected media item
                            (isScraping && selectedMedia != null) || scrapedSources != null -> {
                                ScrapeProgressScreen(streamsViewModel)
                            }
                            selectedMedia != null -> {
                                MediaDetailsScreen(streamsViewModel)
                            }
                            else -> {
                                StreamsDashboardScreen(streamsViewModel)
                            }
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

        if (cursorVisible && isBrowsing && !isPlayerActive && customView == null && currentTab == AppTab.Browser) {
            Image(
                painter = painterResource(id = if (handStyle) R.drawable.ic_hand else R.drawable.ic_cursor),
                contentDescription = null,
                modifier = Modifier.size(32.dp).offset { IntOffset(cx.toInt(), cy.toInt()) },
                colorFilter = ColorFilter.tint(Color(0xFF40C4FF))
            )
        }

        // website custom fullscreen HTML5 video container
        if (customView != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
            ) {
                AndroidView(
                    factory = { ctx ->
                        customView!!.apply {
                            val prevParent = parent as? ViewGroup
                            prevParent?.removeView(this)
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                        }
                    },
                    update = { view -> },
                    modifier = Modifier.fillMaxSize()
                )
            }
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
            if (rule.selector == "context_menu_trigger") {
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
            } else {
                var ruleName by remember { mutableStateOf(Uri.parse(rule.url).host ?: "Custom Rule") }
                AlertDialog(
                    onDismissRequest = { browserViewModel.dismissDialog() },
                    title = { Text("Save Blocked Element", color = Color.White) },
                    containerColor = Color(0xFF222225),
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Rule name for this site:", color = Color.LightGray)
                            TvInputField(
                                value = ruleName,
                                onValueChange = { ruleName = it },
                                placeholder = "e.g. Blocker",
                                onAction = {
                                    browserViewModel.saveBlockedElementRule(ruleName, rule.url, rule.selector)
                                }
                            )
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                browserViewModel.saveBlockedElementRule(ruleName, rule.url, rule.selector)
                            }
                        ) {
                            Text("Save", color = Color.White)
                        }
                    },
                    dismissButton = {
                        Button(
                            onClick = { browserViewModel.dismissDialog() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)
                        ) {
                            Text("Cancel", color = Color.White)
                        }
                    }
                )
            }
        }

        // StreamPicker Dialog
        if (dialogState is BrowserDialogState.StreamPicker) {
            val picker = dialogState as BrowserDialogState.StreamPicker
            val firstItemFocusRequester = remember { FocusRequester() }

            // Instantly request focus on the first stream item when the dialog opens
            LaunchedEffect(picker) {
                try {
                    firstItemFocusRequester.requestFocus()
                } catch (_: Exception) {}
            }

            AlertDialog(
                onDismissRequest = { browserViewModel.dismissStreamPicker() },
                title = { Text("Select Video Stream", color = Color.White) },
                containerColor = Color(0xFF222225),
                text = {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        itemsIndexed(picker.streamInfos) { idx, info ->
                            TvFocusableBox(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .let { if (idx == 0) it.focusRequester(firstItemFocusRequester) else it },
                                onClick = {
                                    val selectedStream = picker.streams[idx]
                                    browserViewModel.playVideoInNativePlayer(selectedStream, browserViewModel.currentWebView?.title)
                                    browserViewModel.dismissDialog()
                                }
                            ) { isFocused ->
                                Box(contentAlignment = Alignment.CenterStart, modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp)) {
                                    Text(info, color = Color.White)
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            browserViewModel.dismissStreamPicker()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)
                    ) {
                        Text("Use Website Player", color = Color.White)
                    }
                }
            )
        }

        // Download Confirmation Dialog
        if (dialogState is BrowserDialogState.Download) {
            val download = dialogState as BrowserDialogState.Download
            AlertDialog(
                onDismissRequest = { browserViewModel.dismissDialog() },
                title = { Text("Download File?", color = Color.White) },
                containerColor = Color(0xFF222225),
                text = {
                    Text(
                        text = "Do you want to download ${download.fileName}?\nSize: %.2f MB".format(download.sizeMb),
                        color = Color.LightGray
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            browserViewModel.startDownload(context, download.url, download.fileName)
                            browserViewModel.dismissDialog()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                    ) {
                        Text("Download", color = Color.White)
                    }
                },
                dismissButton = {
                    Button(
                        onClick = { browserViewModel.dismissDialog() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)
                    ) {
                        Text("Cancel", color = Color.White)
                    }
                }
            )
        }

        // PopupBlocked Dialog
        if (dialogState is BrowserDialogState.PopupBlocked) {
            val popup = dialogState as BrowserDialogState.PopupBlocked
            var rememberDecision by remember { mutableStateOf(false) }

            AlertDialog(
                onDismissRequest = { browserViewModel.dismissDialog() },
                title = { Text("Popup Blocked", color = Color.White) },
                containerColor = Color(0xFF222225),
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Do you want to allow a popup from this site?", color = Color.LightGray)
                        TvFocusableBox(
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            onClick = { rememberDecision = !rememberDecision }
                        ) { isFocused ->
                            Row(
                                modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                androidx.compose.material3.Checkbox(
                                    checked = rememberDecision,
                                    onCheckedChange = { rememberDecision = it },
                                    colors = androidx.compose.material3.CheckboxDefaults.colors(
                                        checkedColor = Color(0xFF00BCD4),
                                        uncheckedColor = Color.Gray
                                    )
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Never ask again (Silent Block)",
                                    color = Color.White,
                                    fontSize = 14.sp
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            browserViewModel.allowPopup(context, popup.resultMsg, rememberDecision)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                    ) {
                        Text("Allow", color = Color.White)
                    }
                },
                dismissButton = {
                    Button(
                        onClick = {
                            browserViewModel.denyPopup(rememberDecision)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)
                    ) {
                        Text("Block", color = Color.White)
                    }
                }
            )
        }

        // AdvancedBlockElement Dialog
        if (dialogState is BrowserDialogState.AdvancedBlockElement) {
            val block = dialogState as BrowserDialogState.AdvancedBlockElement
            var blockData by remember { mutableStateOf(block.data) }
            val options = remember(blockData) {
                val opts = blockData.optJSONArray("options") ?: JSONArray()
                (0 until opts.length()).map { opts.getJSONObject(it) }
            }
            val tagName = remember(blockData) { blockData.optString("tagName", "unknown") }
            val candCount = remember(blockData) { blockData.optInt("candidatesCount", 1) }
            val candIndex = remember(blockData) { blockData.optInt("candidateIndex", 0) }

            AlertDialog(
                onDismissRequest = {
                    browserViewModel.clearElementHighlight()
                    browserViewModel.dismissDialog()
                },
                title = { Text("Block Element ($tagName) [${candIndex + 1}/$candCount]", color = Color.White) },
                containerColor = Color(0xFF222225),
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Select selector to block:", color = Color.Gray, fontSize = 12.sp)
                        LazyColumn(modifier = Modifier.height(180.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            itemsIndexed(options) { idx, opt ->
                                val type = opt.optString("type")
                                val value = opt.optString("value")
                                TvFocusableBox(
                                    modifier = Modifier.fillMaxWidth().height(40.dp),
                                    onFocus = {
                                        browserViewModel.highlightElement(value)
                                    },
                                    onClick = {
                                        browserViewModel.clearElementHighlight()
                                        browserViewModel.dismissDialog()
                                        browserViewModel.showSaveBlockRuleDialog(value)
                                    }
                                ) { isFocused ->
                                    Box(contentAlignment = Alignment.CenterStart, modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp)) {
                                        Text("$type: $value", color = Color.White, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (candCount > 1) {
                                Button(
                                    onClick = {
                                        browserViewModel.selectNextElementCandidate { nextData ->
                                            blockData = nextData
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFC107)),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("↓ Select Underneath", color = Color.Black, fontSize = 11.sp)
                                }
                            }
                            Button(
                                onClick = {
                                    browserViewModel.selectParentElementCandidate { nextData ->
                                        blockData = nextData
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00BCD4)),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("↑ Select Parent", color = Color.White, fontSize = 11.sp)
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            browserViewModel.clearElementHighlight()
                            browserViewModel.dismissDialog()
                            (context as? MainActivity)?.startDpadSelectionMode()
                        }
                    ) {
                        Text("D-pad Navigate & Select", color = Color.White)
                    }
                }
            )
        }

        // SaveAutoplayProfile Dialog
        if (dialogState is BrowserDialogState.SaveAutoplayProfile) {
            val profile = dialogState as BrowserDialogState.SaveAutoplayProfile
            var profileName by remember { mutableStateOf(Uri.parse(profile.url).host ?: "Custom Autoplay") }
            AlertDialog(
                onDismissRequest = { browserViewModel.dismissDialog() },
                title = { Text("Save Autoplay Profile", color = Color.White) },
                containerColor = Color(0xFF222225),
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Enter a name for this profile:", color = Color.LightGray)
                        TvInputField(
                            value = profileName,
                            onValueChange = { profileName = it },
                            placeholder = "e.g. My Autoplay",
                            onAction = {
                                browserViewModel.saveAutoplayProfile(profileName, profile.url, profile.selectors)
                            }
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            browserViewModel.saveAutoplayProfile(profileName, profile.url, profile.selectors)
                        }
                    ) {
                        Text("Save", color = Color.White)
                    }
                },
                dismissButton = {
                    Button(
                        onClick = { browserViewModel.dismissDialog() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)
                    ) {
                        Text("Discard", color = Color.White)
                    }
                }
            )
        }

        // Error Dialog
        if (dialogState is BrowserDialogState.Error) {
            val error = dialogState as BrowserDialogState.Error
            AlertDialog(
                onDismissRequest = { browserViewModel.dismissDialog() },
                title = { Text("Error", color = Color.White) },
                containerColor = Color(0xFF222225),
                text = {
                    Text(error.message, color = Color.LightGray)
                },
                confirmButton = {
                    Button(
                        onClick = { browserViewModel.dismissDialog() }
                    ) {
                        Text("OK", color = Color.White)
                    }
                }
            )
        }
    }
}