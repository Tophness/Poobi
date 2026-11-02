package com.poobi.tvbrowser.player

import android.view.KeyEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.poobi.tvbrowser.R
import com.poobi.tvbrowser.shared.TvFocusableBox
import com.poobi.tvbrowser.shared.TvInputField
import kotlinx.coroutines.delay

enum class AlignmentScreen { Menu, Search, Waveform }

@Composable
fun CustomSubtitleOverlay(
    playerEngine: PlayerEngine,
    modifier: Modifier = Modifier
) {
    val activeCue by playerEngine.subtitleAlignmentManager.activeCue.collectAsState()
    val isPlayerActive by playerEngine.isPlayerActive.collectAsState()
    val isUIVisible by playerEngine.subtitleAlignmentManager.isUIVisible.collectAsState()

    var playPosition by remember { mutableStateOf(0L) }
    var isTextTrackSelected by remember { mutableStateOf(false) }

    LaunchedEffect(isPlayerActive) {
        if (isPlayerActive) {
            while (true) {
                playerEngine.exoPlayer?.let { player ->
                    playPosition = player.currentPosition
                    isTextTrackSelected = playerEngine.isTextTrackSelected()
                }
                delay(200)
            }
        }
    }

    LaunchedEffect(playPosition) {
        playerEngine.subtitleAlignmentManager.updateActiveCue(playPosition)
    }

    if (isUIVisible) return
    if (!isTextTrackSelected) return
    if (!playerEngine.isSelectedTrackExternal()) return

    activeCue?.let { cue ->
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(bottom = 56.dp, start = 80.dp, end = 80.dp),
            contentAlignment = Alignment.BottomCenter
        ) {
            Box(
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    text = cue.annotatedString,
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    lineHeight = 30.sp,
                    style = androidx.compose.ui.text.TextStyle(
                        shadow = androidx.compose.ui.graphics.Shadow(
                            color = Color.Black,
                            offset = Offset(2f, 2f),
                            blurRadius = 4f
                        )
                    )
                )
            }
        }
    }
}

@Composable
fun SubtitleAlignmentOverlay(
    manager: SubtitleAlignmentManager,
    audioCapturer: AudioWaveformCapturer,
    player: androidx.media3.exoplayer.ExoPlayer,
    onDismiss: () -> Unit
) {
    val isVisible by manager.isUIVisible.collectAsState()
    val offsetMs by manager.offsetMs.collectAsState()

    if (!isVisible) return

    var screen by remember { mutableStateOf(AlignmentScreen.Menu) }
    var searchQuery by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(screen) {
        delay(100)
        try {
            focusRequester.requestFocus()
        } catch (_: Exception) {}
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.40f))
    ) {
        when (screen) {
            AlignmentScreen.Menu -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier
                            .width(380.dp)
                            .wrapContentHeight()
                            .background(Color(0xFF1E1E24), RoundedCornerShape(12.dp))
                            .border(width = 1.dp, color = Color(0xFF2D2D35), shape = RoundedCornerShape(12.dp))
                            .padding(24.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.sub_sync_title),
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )

                        Text(
                            text = stringResource(R.string.sub_sync_offset, offsetMs),
                            color = Color(0xFF00BCD4),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )

                        TvFocusableBox(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(40.dp)
                                .focusRequester(focusRequester)
                                .focusProperties {
                                    up = FocusRequester.Cancel
                                    left = FocusRequester.Cancel
                                    right = FocusRequester.Cancel
                                },
                            onClick = { screen = AlignmentScreen.Waveform }
                        ) { isFocused ->
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(stringResource(R.string.sub_sync_btn_visual), color = if (isFocused) Color.Black else Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                        }

                        TvFocusableBox(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(40.dp)
                                .focusProperties {
                                    left = FocusRequester.Cancel
                                    right = FocusRequester.Cancel
                                },
                            onClick = { screen = AlignmentScreen.Search }
                        ) { isFocused ->
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(stringResource(R.string.sub_sync_btn_search), color = if (isFocused) Color.Black else Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                        }

                        TvFocusableBox(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(40.dp)
                                .focusProperties {
                                    left = FocusRequester.Cancel
                                    right = FocusRequester.Cancel
                                },
                            onClick = { manager.setOffset(0L) }
                        ) { isFocused ->
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(stringResource(R.string.sub_sync_btn_reset), color = if (isFocused) Color.Black else Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                        }

                        TvFocusableBox(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(40.dp)
                                .focusProperties {
                                    down = FocusRequester.Cancel
                                    left = FocusRequester.Cancel
                                    right = FocusRequester.Cancel
                                },
                            onClick = { onDismiss() }
                        ) { isFocused ->
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(stringResource(R.string.sub_sync_btn_exit), color = if (isFocused) Color.Black else Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }

            AlignmentScreen.Search -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier
                            .width(420.dp)
                            .wrapContentHeight()
                            .background(Color(0xFF1E1E24), RoundedCornerShape(12.dp))
                            .border(width = 1.dp, color = Color(0xFF2D2D35), shape = RoundedCornerShape(12.dp))
                            .padding(24.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.sub_search_title),
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )

                        Text(
                            text = stringResource(R.string.sub_search_desc),
                            color = Color.Gray,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center
                        )

                        TvInputField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = stringResource(R.string.sub_search_placeholder),
                            focusRequester = focusRequester,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .focusProperties {
                                    up = FocusRequester.Cancel
                                },
                            onAction = {
                                if (searchQuery.isNotBlank()) {
                                    manager.findAndJumpToCue(player, searchQuery)
                                    screen = AlignmentScreen.Waveform
                                }
                            }
                        )

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            TvFocusableBox(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(40.dp)
                                    .focusProperties {
                                        down = FocusRequester.Cancel
                                    },
                                onClick = {
                                    if (searchQuery.isNotBlank()) {
                                        manager.findAndJumpToCue(player, searchQuery)
                                        screen = AlignmentScreen.Waveform
                                    }
                                }
                            ) { isFocused ->
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text(stringResource(R.string.sub_search_btn_align), color = if (isFocused) Color.Black else Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                }
                            }

                            TvFocusableBox(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(40.dp)
                                    .focusProperties {
                                        down = FocusRequester.Cancel
                                    },
                                onClick = { screen = AlignmentScreen.Menu }
                            ) { isFocused ->
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text(stringResource(R.string.btn_back), color = if (isFocused) Color.Black else Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                }
                            }
                        }
                    }
                }
            }

            AlignmentScreen.Waveform -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(230.dp)
                            .background(Color.Transparent)
                            .padding(12.dp)
                    ) {
                        WaveformAlignmentLayout(
                            manager = manager,
                            audioCapturer = audioCapturer,
                            player = player,
                            focusRequester = focusRequester,
                            onBack = {
                                manager.stopLoop()
                                screen = AlignmentScreen.Menu
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun WaveformAlignmentLayout(
    manager: SubtitleAlignmentManager,
    audioCapturer: AudioWaveformCapturer,
    player: androidx.media3.exoplayer.ExoPlayer,
    focusRequester: FocusRequester,
    onBack: () -> Unit
) {
    val offsetMs by manager.offsetMs.collectAsState()
    val cues by manager.cues.collectAsState()
    val activeCue by manager.activeCue.collectAsState()
    val isLooping by manager.isLooping.collectAsState()

    var playerPos by remember { mutableStateOf(player.currentPosition) }

    LaunchedEffect(Unit) {
        if (!manager.isLooping.value) {
            val currentPos = player.currentPosition
            val currentCues = manager.cues.value
            val targetCue = manager.activeCue.value ?: currentCues.minByOrNull { Math.abs(it.startTimeMs - currentPos) }
            if (targetCue != null) {
                manager.startLoop(player, targetCue)
            }
        }

        while (true) {
            playerPos = player.currentPosition
            delay(16)
        }
    }

    val timelineFocusRequester = focusRequester
    val applyFocusRequester = remember { FocusRequester() }
    val cancelFocusRequester = remember { FocusRequester() }
    val loopBtnFocusRequester = remember { FocusRequester() }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxSize()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.sub_waveform_title),
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
            
            if (manager.lastAdjustmentMsg.isNotEmpty()) {
                Text(
                    text = manager.lastAdjustmentMsg,
                    color = Color(0xFF81C784),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(modifier = Modifier.height(2.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(30.dp)
                .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                .padding(horizontal = 8.dp, vertical = 2.dp),
            contentAlignment = Alignment.Center
        ) {
            val silenceText = stringResource(R.string.sub_waveform_silence)
            Text(
                text = (activeCue?.text ?: silenceText).replace("\n", " "),
                color = if (activeCue != null) Color.White else Color.Gray,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color(0xFF0F0F11), RoundedCornerShape(4.dp))
                .border(1.dp, Color(0xFF222225), RoundedCornerShape(4.dp))
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val width = size.width
                val height = size.height
                val centerY = height / 2f
                val centerX = width / 2f

                val scaleFactor = width / 8000f 
                val halfSpanMs = (centerX / scaleFactor).toLong()
                val startTimelineMs = playerPos - halfSpanMs
                val endTimelineMs = playerPos + halfSpanMs

                fun getSmoothedAmplitude(timeBucket: Long): Float {
                    var sum = 0f
                    var count = 0
                    val window = 2 
                    for (i in -window..window) {
                        val amp = audioCapturer.amplitudeCache[timeBucket + i]
                        if (amp != null) {
                            sum += amp
                            count++
                        }
                    }
                    return if (count > 0) sum / count else 0f
                }

                val stepMs = 50L
                var maxVisibleAmp = 0.01f
                for (t in startTimelineMs..endTimelineMs step stepMs) {
                    val amp = getSmoothedAmplitude(t / 50L)
                    if (amp > maxVisibleAmp) {
                        maxVisibleAmp = amp
                    }
                }
                val gain = (1.0f / maxVisibleAmp).coerceAtMost(10f)

                for (t in startTimelineMs..endTimelineMs step stepMs) {
                    val rawAmp = getSmoothedAmplitude(t / 50L)
                    val amp = (rawAmp * gain).coerceIn(0.05f, 1f)
                    val x = centerX + (t - playerPos) * scaleFactor
                    val waveHeight = amp * 28f
                    
                    drawLine(
                        color = Color(0xFF00BCD4).copy(alpha = 0.85f),
                        start = Offset(x, centerY - 15f - waveHeight),
                        end = Offset(x, centerY - 15f + waveHeight),
                        strokeWidth = 2f
                    )
                }

                val visibleCues = cues.filter {
                    val startAdjusted = it.startTimeMs + offsetMs
                    val endAdjusted = it.endTimeMs + offsetMs
                    startAdjusted <= endTimelineMs && endAdjusted >= startTimelineMs
                }

                val paint = android.graphics.Paint().apply {
                    color = android.graphics.Color.WHITE
                    textSize = 24f 
                    textAlign = android.graphics.Paint.Align.CENTER
                    typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
                }

                visibleCues.forEach { cue ->
                    val startX = centerX + (cue.startTimeMs + offsetMs - playerPos) * scaleFactor
                    val endX = centerX + (cue.endTimeMs + offsetMs - playerPos) * scaleFactor
                    
                    drawRect(
                        color = Color(0xFF4CAF50).copy(alpha = 0.5f),
                        topLeft = Offset(startX, centerY + 10f),
                        size = Size(endX - startX, 16f)
                    )
                    
                    drawLine(
                        color = Color(0xFF4CAF50),
                        start = Offset(startX, centerY + 10f),
                        end = Offset(startX, centerY + 26f),
                        strokeWidth = 3f
                    )

                    val blockCenter = (startX + endX) / 2f
                    val maxTextWidth = (endX - startX).coerceAtLeast(60f)
                    
                    var textToDraw = cue.text.replace("\n", " ")
                    if (paint.measureText(textToDraw) > maxTextWidth) {
                        var len = textToDraw.length
                        while (len > 0 && paint.measureText(textToDraw.substring(0, len) + "...") > maxTextWidth) {
                            len--
                        }
                        textToDraw = if (len > 0) textToDraw.substring(0, len) + "..." else ""
                    }
                    
                    drawContext.canvas.nativeCanvas.drawText(
                        textToDraw,
                        blockCenter,
                        centerY + 45f,
                        paint
                    )
                }

                if (manager.showDottedFromLine) {
                    val fromX = centerX + (manager.originalUnalignedCueTimeMs - playerPos) * scaleFactor
                    val clampedX = fromX.coerceIn(5f, width - 5f)
                    
                    drawLine(
                        color = Color.Magenta,
                        start = Offset(clampedX, 0f),
                        end = Offset(clampedX, height),
                        strokeWidth = 2f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f)
                    )
                }

                drawLine(
                    color = Color.Red,
                    start = Offset(centerX, 0f),
                    end = Offset(centerX, height),
                    strokeWidth = 3f
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .focusRequester(timelineFocusRequester)
                .focusProperties {
                    up = FocusRequester.Cancel
                    down = loopBtnFocusRequester
                }
                .focusable()
                .onPreviewKeyEvent { keyEvent ->
                    val nativeEvent = keyEvent.nativeKeyEvent
                    if (nativeEvent.action == KeyEvent.ACTION_DOWN) {
                        when (nativeEvent.keyCode) {
                            KeyEvent.KEYCODE_DPAD_LEFT -> {
                                manager.shiftOffset(-250L)
                                true
                            }
                            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                manager.shiftOffset(250L)
                                true
                            }
                            else -> false
                        }
                    } else false
                }
        ) {
            var isFocused by remember { mutableStateOf(false) }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onFocusChanged { isFocused = it.isFocused }
                    .border(
                        width = if (isFocused) 2.dp else 0.dp,
                        color = if (isFocused) Color(0xFF00BCD4) else Color.Transparent,
                        shape = RoundedCornerShape(4.dp)
                    )
                    .background(
                        color = if (isFocused) Color(0xFF00BCD4).copy(alpha = 0.1f) else Color.Transparent,
                        shape = RoundedCornerShape(4.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(R.string.sub_waveform_offset, offsetMs),
                        color = if (isFocused) Color(0xFF00BCD4) else Color(0xFFFFB74D),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (isFocused) stringResource(R.string.sub_waveform_hint_focused) else stringResource(R.string.sub_waveform_hint_unfocused),
                        color = Color.Gray,
                        fontSize = 10.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(34.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (isLooping) stringResource(R.string.sub_waveform_looping) else stringResource(R.string.sub_waveform_playback_running),
                color = Color.Gray,
                fontSize = 11.sp
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TvFocusableBox(
                    modifier = Modifier
                        .height(34.dp)
                        .width(100.dp)
                        .focusRequester(loopBtnFocusRequester)
                        .focusProperties {
                            up = timelineFocusRequester
                            right = applyFocusRequester
                            down = FocusRequester.Cancel
                        },
                    onClick = {
                        if (isLooping) manager.stopLoop()
                        else {
                            activeCue?.let { manager.startLoop(player, it) }
                        }
                    }
                ) { isFocused ->
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = if (isLooping) stringResource(R.string.sub_waveform_stop_loop) else stringResource(R.string.sub_waveform_loop_track),
                            color = if (isFocused) Color.Black else Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }
                }

                TvFocusableBox(
                    modifier = Modifier
                        .height(34.dp)
                        .width(100.dp)
                        .focusRequester(applyFocusRequester)
                        .focusProperties {
                            up = timelineFocusRequester
                            left = loopBtnFocusRequester
                            right = cancelFocusRequester
                            down = FocusRequester.Cancel
                        },
                    onClick = { manager.confirmChanges() }
                ) { isFocused ->
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.btn_apply), color = if (isFocused) Color.Black else Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }

                TvFocusableBox(
                    modifier = Modifier
                        .height(34.dp)
                        .width(100.dp)
                        .focusRequester(cancelFocusRequester)
                        .focusProperties {
                            up = timelineFocusRequester
                            left = applyFocusRequester
                            down = FocusRequester.Cancel
                        },
                    onClick = { manager.cancelChanges() }
                ) { isFocused ->
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.btn_cancel), color = if (isFocused) Color.Black else Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}