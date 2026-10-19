package com.poobi.tvbrowser.browser

import android.content.Intent
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.poobi.tvbrowser.R
import com.poobi.tvbrowser.SettingsActivity
import com.poobi.tvbrowser.shared.TvFocusableBox
import com.poobi.tvbrowser.shared.TvInputField

private val DesktopWindowsIcon: ImageVector = ImageVector.Builder(
    name = "DesktopWindows",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f
).path(fill = SolidColor(Color.White)) {
    moveTo(21f, 2f)
    horizontalLineTo(3f)
    curveTo(1.9f, 2f, 1f, 2.9f, 1f, 4f)
    verticalLineToRelative(12f)
    curveToRelative(0f, 1.1f, 0.9f, 2f, 2f, 2f)
    horizontalLineToRelative(7f)
    verticalLineToRelative(2f)
    horizontalLineTo(8f)
    verticalLineToRelative(2f)
    horizontalLineToRelative(8f)
    verticalLineToRelative(-2f)
    horizontalLineToRelative(-2f)
    verticalLineToRelative(-2f)
    horizontalLineToRelative(7f)
    curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
    verticalLineTo(4f)
    curveToRelative(0f, -1.1f, -0.9f, -2f, -2f, -2f)
    close()
    moveTo(21f, 16f)
    horizontalLineTo(3f)
    verticalLineTo(4f)
    horizontalLineToRelative(18f)
    verticalLineToRelative(12f)
    close()
}.build()

private val ZoomInIcon: ImageVector = ImageVector.Builder(
    name = "ZoomIn",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f
).path(fill = SolidColor(Color.White)) {
    moveTo(15.5f, 14f)
    horizontalLineToRelative(-0.79f)
    lineToRelative(-0.28f, -0.27f)
    curveTo(15.41f, 12.59f, 16f, 11.11f, 16f, 9.5f)
    curveTo(16f, 5.91f, 13.09f, 3f, 9.5f, 3f)
    curveTo(5.91f, 3f, 3f, 5.91f, 3f, 9.5f)
    curveTo(3f, 13.09f, 5.91f, 16f, 9.5f, 16f)
    curveToRelative(1.61f, 0f, 3.09f, -0.59f, 4.23f, -1.57f)
    lineToRelative(0.27f, 0.28f)
    verticalLineToRelative(0.79f)
    lineToRelative(5f, 4.99f)
    lineTo(20.49f, 19f)
    lineToRelative(-4.99f, -5f)
    close()
    moveTo(9.5f, 14f)
    curveTo(7.01f, 14f, 5f, 11.99f, 5f, 9.5f)
    curveTo(5f, 7.01f, 7.01f, 5f, 9.5f, 5f)
    curveTo(11.99f, 5f, 14f, 7.01f, 14f, 9.5f)
    curveTo(14f, 11.99f, 11.99f, 14f, 9.5f, 14f)
    close()
    moveTo(12f, 10f)
    horizontalLineToRelative(-2f)
    verticalLineToRelative(2f)
    horizontalLineTo(9f)
    verticalLineToRelative(-2f)
    horizontalLineTo(7f)
    verticalLineTo(9f)
    horizontalLineToRelative(2f)
    verticalLineTo(7f)
    horizontalLineToRelative(1f)
    verticalLineToRelative(2f)
    horizontalLineToRelative(2f)
    verticalLineToRelative(1f)
    close()
}.build()

private val ZoomOutIcon: ImageVector = ImageVector.Builder(
    name = "ZoomOut",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f
).path(fill = SolidColor(Color.White)) {
    moveTo(15.5f, 14f)
    horizontalLineToRelative(-0.79f)
    lineToRelative(-0.28f, -0.27f)
    curveTo(15.41f, 12.59f, 16f, 11.11f, 16f, 9.5f)
    curveTo(16f, 5.91f, 13.09f, 3f, 9.5f, 3f)
    curveTo(5.91f, 3f, 3f, 5.91f, 3f, 9.5f)
    curveTo(3f, 13.09f, 5.91f, 16f, 9.5f, 16f)
    curveToRelative(1.61f, 0f, 3.09f, -0.59f, 4.23f, -1.57f)
    lineToRelative(0.27f, 0.28f)
    verticalLineToRelative(0.79f)
    lineToRelative(5f, 4.99f)
    lineTo(20.49f, 19f)
    lineToRelative(-4.99f, -5f)
    close()
    moveTo(9.5f, 14f)
    curveTo(7.01f, 14f, 5f, 11.99f, 5f, 9.5f)
    curveTo(5f, 7.01f, 7.01f, 5f, 9.5f, 5f)
    curveTo(11.99f, 5f, 14f, 7.01f, 14f, 9.5f)
    curveTo(14f, 11.99f, 11.99f, 14f, 9.5f, 14f)
    close()
    moveTo(7f, 9f)
    horizontalLineToRelative(5f)
    verticalLineToRelative(1f)
    horizontalLineTo(7f)
    close()
}.build()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserTopBar(
    viewModel: BrowserViewModel,
    onNavigateDown: () -> Unit
) {
    val context = LocalContext.current

    val currentUrl by viewModel.currentUrl.collectAsState()
    val activeIndex by viewModel.currentTabIndex.collectAsState()
    val isRecording by viewModel.isRecordingAutoplay.collectAsState()
    val videoTriggerPref by viewModel.videoTriggerPref.collectAsState()
    val isDesktopMode by viewModel.isDesktopMode.collectAsState()
    val currentZoom by viewModel.currentZoomLevel.collectAsState()

    var showZoomDialog by remember { mutableStateOf(false) }

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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp)
                .background(Color(0xFF222222)),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LazyRow(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(start = 16.dp, end = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                itemsIndexed(viewModel.getWebViewsList()) { index, wv ->
                    val metadata = wv.tag as? TabMetadata
                    val title = wv.title?.takeIf { it.isNotBlank() } ?: metadata?.defaultTitle ?: wv.url ?: "New Tab"
                    val itemFocusRequester = tabFocusRequesters.getOrNull(index) ?: remember { FocusRequester() }
                    
                    TvFocusableHoldToDeleteBox(
                        modifier = Modifier
                            .width(220.dp)
                            .height(42.dp)
                            .focusRequester(itemFocusRequester),
                        isTabStyle = true,
                        isSelected = index == activeIndex,
                        onTriggerDelete = { viewModel.closeTab(index) },
                        onClick = { viewModel.switchTab(index) }
                    ) { isFocused, progress ->
                        TabItemCard(
                            title = title,
                            progress = progress,
                            isFocused = isFocused,
                            isSelected = index == activeIndex,
                            onCloseClick = { viewModel.closeTab(index) }
                        )
                    }
                }
            }
            
            Box(modifier = Modifier.padding(end = 16.dp)) {
                TopBarIconButton(R.drawable.ic_add) { viewModel.createNewTab(context) }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp)
                .padding(horizontal = 16.dp)
                .onPreviewKeyEvent { keyEvent ->
                    if (keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                        keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                        onNavigateDown()
                        true
                    } else {
                        false
                    }
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            TvFocusableBox(
                modifier = Modifier.size(40.dp),
                onClick = { viewModel.showHomeScreen() }
            ) { isFocused ->
                Icon(
                    painter = painterResource(id = R.drawable.ic_home),
                    contentDescription = "Home",
                    tint = if (isFocused) Color.Black else Color.White,
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
                    .height(40.dp)
                    .focusRequester(addressBarFocusRequester)
            )

            TopBarIconButton(R.drawable.ic_go) { 
                viewModel.loadUrlAndBrowse(context, urlInput)
            }
            TopBarIconButton(R.drawable.ic_refresh) { viewModel.currentWebView?.reload() }

            TopBarIconButton(
                imageVector = ZoomOutIcon,
                contentDescription = "Zoom Out"
            ) {
                viewModel.zoomOut(context)
            }

            TvFocusableBox(
                modifier = Modifier
                    .width(52.dp)
                    .height(40.dp),
                onClick = {
                    if (currentZoom != 100) {
                        viewModel.resetZoom(context)
                    } else {
                        showZoomDialog = true
                    }
                },
                onLongClick = {
                    showZoomDialog = true
                }
            ) { isFocused ->
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "$currentZoom%",
                        color = when {
                            isFocused -> Color.Black
                            currentZoom != 100 -> Color(0xFF00BCD4)
                            else -> Color.White
                        },
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            TopBarIconButton(
                imageVector = ZoomInIcon,
                contentDescription = "Zoom In"
            ) {
                viewModel.zoomIn(context)
            }

            TopBarIconButton(
                imageVector = DesktopWindowsIcon,
                tint = if (isDesktopMode) Color(0xFF00BCD4) else Color.White,
                contentDescription = if (isDesktopMode) "Desktop Mode (Enabled)" else "Desktop Mode (Disabled)"
            ) {
                viewModel.toggleDesktopMode(context)
            }

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

    if (showZoomDialog) {
        val presets = listOf(50, 75, 90, 100, 110, 125, 150, 175, 200, 250, 300)
        AlertDialog(
            onDismissRequest = { showZoomDialog = false },
            title = { Text("Page Zoom (${currentZoom}%)", color = Color.White) },
            containerColor = Color(0xFF222225),
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Adjust webpage text and magnification:",
                        color = Color.LightGray,
                        fontSize = 13.sp
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TvFocusableBox(
                            modifier = Modifier.height(38.dp).width(100.dp),
                            onClick = { viewModel.zoomOut(context) }
                        ) { isFocused ->
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("- Zoom Out", color = if (isFocused) Color.Black else Color.White, fontWeight = FontWeight.Bold)
                            }
                        }

                        TvFocusableBox(
                            modifier = Modifier.height(38.dp).width(100.dp),
                            defaultColor = Color(0xFF333338),
                            onClick = { viewModel.resetZoom(context) }
                        ) { isFocused ->
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("100% (Reset)", color = if (isFocused) Color.Black else Color(0xFF00BCD4), fontWeight = FontWeight.Bold)
                            }
                        }

                        TvFocusableBox(
                            modifier = Modifier.height(38.dp).width(100.dp),
                            onClick = { viewModel.zoomIn(context) }
                        ) { isFocused ->
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("+ Zoom In", color = if (isFocused) Color.Black else Color.White, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Preset Magnification:", color = Color.Gray, fontSize = 12.sp)

                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(vertical = 4.dp)
                    ) {
                        items(presets.size) { idx ->
                            val p = presets[idx]
                            val isSelected = p == currentZoom
                            TvFocusableBox(
                                modifier = Modifier.height(36.dp).width(64.dp),
                                defaultColor = if (isSelected) Color(0xFF00BCD4).copy(alpha = 0.3f) else Color(0xFF2D2D35),
                                onClick = {
                                    viewModel.setZoomLevel(p, context)
                                    showZoomDialog = false
                                }
                            ) { isFocused ->
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text(
                                        text = "$p%",
                                        color = when {
                                            isFocused -> Color.Black
                                            isSelected -> Color(0xFF00BCD4)
                                            else -> Color.White
                                        },
                                        fontWeight = if (isSelected || isFocused) FontWeight.Bold else FontWeight.Normal,
                                        fontSize = 13.sp
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TvFocusableBox(
                    modifier = Modifier.height(40.dp).width(90.dp),
                    defaultColor = Color(0xFF00BCD4),
                    shape = RoundedCornerShape(20.dp),
                    onClick = { showZoomDialog = false }
                ) { isFocused ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Done", color = if (isFocused) Color.Black else Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
        )
    }
}

@Composable
fun TopBarIconButton(iconId: Int, tint: Color = Color.White, onClick: () -> Unit) {
    TvFocusableBox(
        modifier = Modifier.size(40.dp),
        onClick = onClick
    ) { isFocused ->
        Icon(
            painter = painterResource(id = iconId),
            contentDescription = null,
            tint = if (isFocused) Color.Black else tint,
            modifier = Modifier.fillMaxSize().padding(8.dp)
        )
    }
}

@Composable
fun TopBarIconButton(
    imageVector: ImageVector,
    tint: Color = Color.White,
    contentDescription: String? = null,
    onClick: () -> Unit
) {
    TvFocusableBox(
        modifier = Modifier.size(40.dp),
        onClick = onClick
    ) { isFocused ->
        Icon(
            imageVector = imageVector,
            contentDescription = contentDescription,
            tint = if (isFocused) Color.Black else tint,
            modifier = Modifier.fillMaxSize().padding(8.dp)
        )
    }
}