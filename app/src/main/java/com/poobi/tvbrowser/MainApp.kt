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
import androidx.compose.material3.LinearProgressIndicator
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
import com.poobi.tvbrowser.player.CustomSubtitleOverlay
import com.poobi.tvbrowser.player.SubtitleAlignmentOverlay
import com.poobi.tvbrowser.shared.TvFocusableBox
import com.poobi.tvbrowser.shared.TvInputField
import com.poobi.tvbrowser.shared.KeyTracker
import com.poobi.tvbrowser.streams.MediaDetailsScreen
import com.poobi.tvbrowser.streams.ScrapeProgressScreen
import com.poobi.tvbrowser.streams.StreamsDashboardScreen
import com.poobi.tvbrowser.streams.StreamsViewModel
import com.poobi.tvbrowser.streams.NewEpisodeNotificationOverlay
import com.poobi.tvbrowser.streams.SubtitleWaitOverlay
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.delay

enum class AppTab { Browser, Streams }

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

    LaunchedEffect(isSelected) {
        if (isSelected) {
            try {
                focusRequester.requestFocus()
            } catch (e: Exception) {}
        }
    }

    val backgroundColor = when {
        isFocused -> Color(0xFF00BCD4)
        isSelected -> Color(0xFF00BCD4).copy(alpha = 0.25f)
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

    LaunchedEffect(isFocused) {
        if (isFocused) {
            val keyCode = keyTracker.lastKeyCode
            val isUserInitiated = (keyCode == KeyEvent.KEYCODE_DPAD_UP ||
                                  keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
                                  keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) &&
                                  keyTracker.isKeyFresh()

            if (isUserInitiated) {
                currentOnFocus()
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
                val lastKey = keyTracker.lastKeyCode
                val isDpadLeft = lastKey == KeyEvent.KEYCODE_DPAD_LEFT
                val isDpadRight = lastKey == KeyEvent.KEYCODE_DPAD_RIGHT
                val isStartup = lastKey == -1

                val computedCanFocus = if (text == "Browser") {
                    currentIsSelected || isDpadLeft || isStartup
                } else {
                    currentIsSelected || isDpadRight || isStartup
                }

                this.canFocus = computedCanFocus || isFocused
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
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

    val browserTabFocusRequester = remember { FocusRequester() }
    val streamsTabFocusRequester = remember { FocusRequester() }
    val streamsContentTabFocusRequester = remember { FocusRequester() }

    val isBufferingTorrent by streamsViewModel.isBufferingTorrent.collectAsState()
    val torrentBufferStatus by streamsViewModel.torrentBufferStatus.collectAsState()
    val torrentBufferProgress by streamsViewModel.torrentBufferProgress.collectAsState()
    val torrentBufferSeeders by streamsViewModel.torrentBufferSeeders.collectAsState()

    val isControllerVisible by playerEngine.isControllerVisible.collectAsState()
    val isScrapeScreenActive = (isScraping && selectedMedia != null) || scrapedSources != null
    val showSubtitleWaitDialog by streamsViewModel.showSubtitleWaitDialog.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1A1A1D))
    ) {
        Column(modifier = Modifier.fillMaxSize()) {

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
                                when (direction) {
                                    FocusDirection.Up -> {
                                        val target = if (currentTab == AppTab.Browser) browserTabFocusRequester else streamsTabFocusRequester
                                        target
                                    }
                                    FocusDirection.Left, FocusDirection.Right -> {
                                        FocusRequester.Default
                                    }
                                    else -> {
                                        FocusRequester.Default
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
                        keyTracker = KeyTracker
                    )

                    ModernTab(
                        text = "Streams",
                        isSelected = currentTab == AppTab.Streams,
                        onFocus = { browserViewModel.currentAppTab.value = 1 },
                        onClick = { browserViewModel.currentAppTab.value = 1 },
                        focusRequester = streamsTabFocusRequester,
                        keyTracker = KeyTracker,
                        modifier = Modifier.focusProperties {
                            if (isScrapeScreenActive) {
                                down = streamsContentTabFocusRequester
                            }
                        }
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

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (currentTab) {
                    AppTab.Browser -> {
                        if (isBrowsing) {
                            Column {
                                if (topBarVisible) {
                                    BrowserTopBar(
                                        viewModel = browserViewModel,
                                        onNavigateDown = {
                                            browserViewModel.hideTopBar()
                                            cursorManager.clearKeyStates()
                                            cursorManager.wakeCursor()
                                        }
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
                            isScrapeScreenActive -> {
                                ScrapeProgressScreen(viewModel = streamsViewModel, streamsContentTabFocusRequester = streamsContentTabFocusRequester)
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
                            setShowSubtitleButton(true)
                            setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
                            playerEngine.playerView = this
                            
                            setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
                                val visible = visibility == android.view.View.VISIBLE
                                playerEngine.setControllerVisible(visible)
                                if (visible) {
                                    post {
                                        if (!playerEngine.showUpNext.value) {
                                            val playPauseBtn = findViewById<android.view.View>(androidx.media3.ui.R.id.exo_play_pause)
                                                ?: findViewById<android.view.View>(resources.getIdentifier("exo_play_pause", "id", "androidx.media3.ui"))
                                                ?: findViewById<android.view.View>(resources.getIdentifier("exo_play_pause", "id", context.packageName))
                                            playPauseBtn?.requestFocus()
                                        }
                                    }
                                }
                            })

                            post {
                                val basicControlsId = try {
                                    androidx.media3.ui.R.id.exo_basic_controls
                                } catch (e: Throwable) {
                                    resources.getIdentifier("exo_basic_controls", "id", "androidx.media3.ui")
                                }
                                val settingsBtnId = try {
                                    androidx.media3.ui.R.id.exo_settings
                                } catch (e: Throwable) {
                                    resources.getIdentifier("exo_settings", "id", "androidx.media3.ui")
                                }

                                val basicControls = findViewById<android.widget.LinearLayout>(basicControlsId)
                                val settingsBtn = findViewById<android.view.View>(settingsBtnId)

                                if (basicControls != null) {
                                    val existingBtn = basicControls.findViewWithTag<android.view.View>("exo_quality_button_tag")
                                    if (existingBtn == null) {
                                        val qualityBtn = android.widget.TextView(ctx).apply {
                                            tag = "exo_quality_button_tag"

                                            val format = playerEngine.exoPlayer?.videoFormat
                                            val height = format?.height
                                            text = if (height != null && height > 0) "${height}P" else "AUTO"
                                            
                                            gravity = android.view.Gravity.CENTER
                                            val density = resources.displayMetrics.density
                                            val padH = (14 * density).toInt()
                                            val padV = (6 * density).toInt()
                                            setPadding(padH, padV, padH, padV)
                                            textSize = 13f
                                            setTypeface(null, android.graphics.Typeface.BOLD)
                                            setMinWidth((70 * density).toInt())

                                            val normalDrawable = android.graphics.drawable.GradientDrawable().apply {
                                                setColor(android.graphics.Color.TRANSPARENT)
                                                setStroke((1.5f * density).toInt(), android.graphics.Color.WHITE)
                                                cornerRadius = 4f * density
                                            }
                                            val focusedDrawable = android.graphics.drawable.GradientDrawable().apply {
                                                setColor(android.graphics.Color.parseColor("#00BCD4"))
                                                setStroke((1.5f * density).toInt(), android.graphics.Color.WHITE)
                                                cornerRadius = 4f * density
                                            }

                                            val sld = android.graphics.drawable.StateListDrawable().apply {
                                                addState(intArrayOf(android.R.attr.state_focused), focusedDrawable)
                                                addState(intArrayOf(), normalDrawable)
                                            }
                                            background = sld

                                            val colorStateList = android.content.res.ColorStateList(
                                                arrayOf(
                                                    intArrayOf(android.R.attr.state_focused),
                                                    intArrayOf()
                                                ),
                                                intArrayOf(
                                                    android.graphics.Color.BLACK,
                                                    android.graphics.Color.WHITE
                                                )
                                            )
                                            setTextColor(colorStateList)

                                            layoutParams = android.widget.LinearLayout.LayoutParams(
                                                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                                                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                                            ).apply {
                                                gravity = android.view.Gravity.CENTER_VERTICAL
                                                rightMargin = (10 * density).toInt()
                                                leftMargin = (10 * density).toInt()
                                            }
                                            
                                            setOnClickListener {
                                                playerEngine.showResolutionSelector()
                                            }
                                            
                                            isFocusable = true
                                        }

                                        val index = if (settingsBtn != null) basicControls.indexOfChild(settingsBtn) else -1
                                        if (index >= 0) {
                                            basicControls.addView(qualityBtn, index)
                                        } else {
                                            basicControls.addView(qualityBtn)
                                        }
                                    }

                                    val existingSyncBtn = basicControls.findViewWithTag<android.view.View>("exo_sub_sync_button_tag")
                                    if (existingSyncBtn == null) {
                                        val syncBtn = android.widget.TextView(ctx).apply {
                                            tag = "exo_sub_sync_button_tag"
                                            text = "SUB SYNC"
                                            
                                            gravity = android.view.Gravity.CENTER
                                            val density = resources.displayMetrics.density
                                            val padH = (14 * density).toInt()
                                            val padV = (6 * density).toInt()
                                            setPadding(padH, padV, padH, padV)
                                            textSize = 13f
                                            setTypeface(null, android.graphics.Typeface.BOLD)
                                            setMinWidth((70 * density).toInt())

                                            val normalDrawable = android.graphics.drawable.GradientDrawable().apply {
                                                setColor(android.graphics.Color.TRANSPARENT)
                                                setStroke((1.5f * density).toInt(), android.graphics.Color.WHITE)
                                                cornerRadius = 4f * density
                                            }
                                            val focusedDrawable = android.graphics.drawable.GradientDrawable().apply {
                                                setColor(android.graphics.Color.parseColor("#00BCD4"))
                                                setStroke((1.5f * density).toInt(), android.graphics.Color.WHITE)
                                                cornerRadius = 4f * density
                                            }

                                            val sld = android.graphics.drawable.StateListDrawable().apply {
                                                addState(intArrayOf(android.R.attr.state_focused), focusedDrawable)
                                                addState(intArrayOf(), normalDrawable)
                                            }
                                            background = sld

                                            val colorStateList = android.content.res.ColorStateList(
                                                arrayOf(
                                                    intArrayOf(android.R.attr.state_focused),
                                                    intArrayOf()
                                                ),
                                                intArrayOf(
                                                    android.graphics.Color.BLACK,
                                                    android.graphics.Color.WHITE
                                                )
                                            )
                                            setTextColor(colorStateList)

                                            layoutParams = android.widget.LinearLayout.LayoutParams(
                                                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                                                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                                            ).apply {
                                                gravity = android.view.Gravity.CENTER_VERTICAL
                                                rightMargin = (10 * density).toInt()
                                                leftMargin = (10 * density).toInt()
                                            }
                                            
                                            setOnClickListener {
                                                playerEngine.subtitleAlignmentManager.showUI()
                                                playerEngine.disableNativeSubtitles(true)
                                            }
                                            
                                            isFocusable = true
                                        }

                                        val index = if (settingsBtn != null) basicControls.indexOfChild(settingsBtn) else -1
                                        if (index >= 0) {
                                            basicControls.addView(syncBtn, index)
                                        } else {
                                            basicControls.addView(syncBtn)
                                        }
                                    }
                                }
                            }
                            
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
                val nextEpisodeStr = remember(nextEpisodeData) { nextEpisodeData?.toString() ?: "" }
                key(nextEpisodeStr) {
                    UpNextOverlay(
                        isVisible = showUpNext,
                        isControllerVisible = isControllerVisible,
                        nextEpisodeJson = nextEpisodeData,
                        playerEngine = playerEngine,
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
                        },
                        onSeek = { direction, repeatCount ->
                            playerEngine.seekVideo(direction, repeatCount)
                        }
                    )
                }

                val showQualitySelector by playerEngine.showQualitySelector.collectAsState()
                val qualityOptions by playerEngine.qualityOptions.collectAsState()
                val currentQuality by playerEngine.currentQuality.collectAsState()

                if (showQualitySelector && qualityOptions.isNotEmpty()) {
                    AlertDialog(
                        onDismissRequest = { playerEngine.dismissQualitySelector() },
                        title = { Text("Select Video Quality", color = Color.White) },
                        containerColor = Color(0xFF222225),
                        text = {
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                itemsIndexed(qualityOptions) { idx, option ->
                                    val isSelected = currentQuality == option
                                    TvFocusableBox(
                                        modifier = Modifier.fillMaxWidth().height(48.dp),
                                        onClick = {
                                            playerEngine.selectQuality(option)
                                        }
                                    ) { isFocused ->
                                        Row(
                                            modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = option.name, 
                                                color = if (isFocused) Color.Black else Color.White,
                                                modifier = Modifier.weight(1f)
                                            )
                                            if (isSelected) {
                                                Icon(
                                                    painter = painterResource(id = R.drawable.ic_watched),
                                                    contentDescription = "Selected",
                                                    tint = if (isFocused) Color.Black else Color(0xFF00BCD4),
                                                    modifier = Modifier.size(24.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        },
                        confirmButton = {
                            Button(
                                onClick = { playerEngine.dismissQualitySelector() },
                                colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)
                            ) {
                                Text("Close", color = Color.White)
                            }
                        }
                    )
                }

                val subtitleRenderingMode = remember { mutableStateOf(0) }
                LaunchedEffect(isPlayerActive) {
                    if (isPlayerActive) {
                        subtitleRenderingMode.value = playerEngine.prefs.getInt("subtitle_rendering_mode", 0)
                    }
                }

                if (subtitleRenderingMode.value == 1) {
                    CustomSubtitleOverlay(
                        playerEngine = playerEngine,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                SubtitleAlignmentOverlay(
                    manager = playerEngine.subtitleAlignmentManager,
                    audioCapturer = playerEngine.audioWaveformCapturer,
                    player = playerEngine.exoPlayer!!,
                    onDismiss = {
                        playerEngine.subtitleAlignmentManager.hideUI()
                    }
                )

                val isSyncVisible by playerEngine.subtitleAlignmentManager.isUIVisible.collectAsState()
                var wasSyncVisible by remember { mutableStateOf(false) }

                LaunchedEffect(isSyncVisible) {
                    playerEngine.playerView?.let { pView ->
                        if (isSyncVisible) {
                            pView.useController = false
                            pView.hideController()
                        } else {
                            pView.useController = true
                        }
                    }
                    
                    if (!isSyncVisible && wasSyncVisible) {
                        playerEngine.requestPlayPauseFocus()
                    }
                    wasSyncVisible = isSyncVisible
                }
            }
        }

        if (dialogState is BrowserDialogState.ContextMenu) {
            val contextMenu = dialogState as BrowserDialogState.ContextMenu
            com.poobi.tvbrowser.browser.ContextMenuOverlay(
                cursorX = contextMenu.x,
                cursorY = contextMenu.y,
                url = contextMenu.url,
                onOpenInNewTab = {
                    browserViewModel.loadUrlAndBrowse(context, contextMenu.url, newTab = true)
                    browserViewModel.dismissDialog()
                },
                onRefresh = {
                    browserViewModel.currentWebView?.reload()
                    browserViewModel.dismissDialog()
                },
                onBlockElement = {
                    browserViewModel.blockElementAtCursor(contextMenu.x, contextMenu.y)
                },
                onDismiss = {
                    browserViewModel.dismissDialog()
                }
            )
        }

        if (dialogState is BrowserDialogState.SaveBlockRule) {
            val saveRule = dialogState as BrowserDialogState.SaveBlockRule
            var ruleName by remember { 
                mutableStateOf(
                    android.net.Uri.parse(saveRule.url).host ?: "Custom Rule"
                ) 
            }
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
                            placeholder = "e.g. ad-banner",
                            onAction = {
                                browserViewModel.saveBlockedElementRule(ruleName, saveRule.url, saveRule.selector)
                            }
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            browserViewModel.saveBlockedElementRule(ruleName, saveRule.url, saveRule.selector)
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

        if (dialogState is BrowserDialogState.PlaybackHijackAsk) {
            val ask = dialogState as BrowserDialogState.PlaybackHijackAsk
            var rememberDecision by remember { mutableStateOf(false) }

            AlertDialog(
                onDismissRequest = {
                    browserViewModel.handleHijackChoice(useNative = false, remember = false, ask = ask)
                },
                title = { Text("Video Detected", color = Color.White) },
                containerColor = Color(0xFF222225),
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = "Video intercepted. Would you like to play it inside the native player or keep using the website player?",
                            color = Color.LightGray
                        )
                        
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
                                    text = "Remember my decision (Don't ask again)",
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
                            browserViewModel.handleHijackChoice(useNative = true, remember = rememberDecision, ask = ask)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                    ) {
                        Text("Play Native", color = Color.White)
                    }
                },
                dismissButton = {
                    Button(
                        onClick = {
                            browserViewModel.handleHijackChoice(useNative = false, remember = rememberDecision, ask = ask)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)
                    ) {
                        Text("Website Player", color = Color.White)
                    }
                }
            )
        }

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

        if (isBufferingTorrent) {
            AlertDialog(
                onDismissRequest = { streamsViewModel.cancelTorrentBuffering() },
                title = { Text("Pre-Buffering Video Stream", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp) },
                containerColor = Color(0xFF222225),
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Text(
                            text = torrentBufferStatus,
                            color = Color.LightGray,
                            fontSize = 14.sp
                        )

                        LinearProgressIndicator(
                            progress = { torrentBufferProgress },
                            modifier = Modifier.fillMaxWidth().height(8.dp),
                            color = Color(0xFF00BCD4),
                            trackColor = Color(0xFF333338)
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = if (torrentBufferSeeders > 0) "Seeds: $torrentBufferSeeders" else "Locating seeders...",
                                color = if (torrentBufferSeeders > 0) Color(0xFFFFB74D) else Color.Gray,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "${(torrentBufferProgress * 100).toInt()}%",
                                color = Color(0xFF00BCD4),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                },
                confirmButton = {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = { streamsViewModel.forcePlayTorrentNow() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
                            modifier = Modifier.tvSettingsFocus(RoundedCornerShape(20.dp))
                        ) {
                            Text("Play Now", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                        
                        Button(
                            onClick = { streamsViewModel.cancelTorrentBuffering() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935)),
                            modifier = Modifier.tvSettingsFocus(RoundedCornerShape(20.dp))
                        ) {
                            Text("Cancel", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                },
                dismissButton = null
            )
        }

        showSubtitleWaitDialog?.let { sourceDataJson ->
            SubtitleWaitOverlay(
                viewModel = streamsViewModel,
                sourceDataJson = sourceDataJson,
                onDismiss = { streamsViewModel.dismissSubtitleWaitDialog() }
            )
        }

        val activeNotifications by streamsViewModel.activeNotifications.collectAsState()

        NewEpisodeNotificationOverlay(
            isVisible = activeNotifications.isNotEmpty(),
            notificationsList = activeNotifications,
            onPlayEpisode = { notification, index ->
                browserViewModel.currentAppTab.value = 1
                streamsViewModel.playNotificationEpisode(notification, index)
            },
            onDismissItem = { index ->
                streamsViewModel.dismissNotificationAt(index)
            },
            onDismissAll = {
                streamsViewModel.dismissAllNotifications()
            }
        )
    }
}