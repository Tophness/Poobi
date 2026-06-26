package com.poobi.tvbrowser.player

import android.view.KeyEvent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.poobi.tvbrowser.shared.RemoteImage
import org.json.JSONObject
import kotlinx.coroutines.delay

@Composable
fun UpNextFocusableBox(
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    onUpKeyRedirect: () -> Unit = {},
    onBackRedirect: () -> Unit = {},
    content: @Composable BoxScope.(isFocused: Boolean) -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.03f else 1.0f,
        animationSpec = tween(durationMillis = 150),
        label = "UpNextFocusScale"
    )

    val backgroundColor = if (isFocused) Color(0xFF40C4FF) else Color.Transparent
    val borderColor = if (isFocused) Color.White else Color.Transparent

    Box(
        modifier = modifier
            .scale(scale)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .clip(RoundedCornerShape(8.dp))
            .background(backgroundColor)
            .border(if (isFocused) 2.dp else 0.dp, borderColor, RoundedCornerShape(8.dp))
            .onPreviewKeyEvent { keyEvent ->
                val nativeEvent = keyEvent.nativeKeyEvent
                if (nativeEvent.action == KeyEvent.ACTION_DOWN) {
                    when (nativeEvent.keyCode) {
                        KeyEvent.KEYCODE_DPAD_UP -> {
                            onUpKeyRedirect()
                            true
                        }
                        KeyEvent.KEYCODE_BACK -> {
                            onBackRedirect()
                            true
                        }
                        else -> false
                    }
                } else false
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .focusable(interactionSource = interactionSource)
    ) {
        content(isFocused)
    }
}

@Composable
fun UpNextOverlay(
    isVisible: Boolean,
    isControllerVisible: Boolean,
    nextEpisodeJson: JSONObject?,
    playerEngine: PlayerEngine,
    onTriggerAutoplay: () -> Unit,
    onDismissOverlay: () -> Unit,
    onSeek: (direction: Int, repeatCount: Int) -> Unit
) {
    val actualVisible = isVisible && isControllerVisible

    if (actualVisible && nextEpisodeJson != null) {
        val mainCardFocusRequester = remember { FocusRequester() }
        val leftTabFocusRequester = remember { FocusRequester() }
        val rightTabFocusRequester = remember { FocusRequester() }

        var isCompressed by remember { mutableStateOf(false) }
        var hasAutoCompressed by remember { mutableStateOf(false) }
        var isDismissing by remember { mutableStateOf(false) }
        
        LaunchedEffect(mainCardFocusRequester) {
            playerEngine.upNextFocusRequester = mainCardFocusRequester
        }

        LaunchedEffect(actualVisible, isCompressed) {
            if (actualVisible) {
                delay(150)
                try {
                    if (isCompressed) {
                        leftTabFocusRequester.requestFocus()
                    } else {
                        mainCardFocusRequester.requestFocus()
                    }
                } catch (e: Exception) {}
            }
        }

        LaunchedEffect(actualVisible) {
            if (actualVisible) {
                isCompressed = false
                hasAutoCompressed = false
                delay(5000)
                if (!isCompressed && !hasAutoCompressed) {
                    isCompressed = true
                    hasAutoCompressed = true
                }
            }
        }

        val width by animateDpAsState(
            targetValue = if (isCompressed) 50.dp else 420.dp,
            animationSpec = tween(durationMillis = 400),
            label = "WidthAnimation"
        )

        val dismissAlpha by animateFloatAsState(
            targetValue = if (isDismissing) 0f else 1f,
            animationSpec = tween(durationMillis = 400),
            finishedListener = {
                if (isDismissing) {
                    onDismissOverlay()
                    isDismissing = false
                }
            },
            label = "DismissAlpha"
        )

        val cardShape = RoundedCornerShape(
            topStart = 12.dp,
            bottomStart = 12.dp,
            topEnd = 0.dp,
            bottomEnd = 0.dp
        )

        val parentBgColor = if (isCompressed) Color(0xFF00BCD4) else Color(0xFF1E1E24).copy(alpha = 0.95f)

        val navigateUpToPlayerControls = {
            val pView = playerEngine.playerView
            val playPauseBtn = pView?.findViewById<android.view.View>(androidx.media3.ui.R.id.exo_play_pause)
                ?: pView?.findViewById<android.view.View>(pView.resources.getIdentifier("exo_play_pause", "id", "androidx.media3.ui"))
            playPauseBtn?.requestFocus()
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 100.dp)
                .alpha(dismissAlpha),
            contentAlignment = Alignment.BottomEnd
        ) {
            Box(
                modifier = Modifier
                    .width(width)
                    .height(104.dp)
                    .clip(cardShape)
                    .background(parentBgColor)
                    .border(
                        width = 1.dp,
                        color = Color(0xFF333338),
                        shape = cardShape
                    )
            ) {
                if (isCompressed) {
                    UpNextFocusableBox(
                        modifier = Modifier.fillMaxSize(),
                        focusRequester = leftTabFocusRequester,
                        onClick = { 
                            isCompressed = false
                            hasAutoCompressed = true
                        },
                        onUpKeyRedirect = { navigateUpToPlayerControls() },
                        onBackRedirect = onDismissOverlay
                    ) { isFocused ->
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "◀",
                                color = if (isFocused) Color.Black else Color.White,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier
                            .requiredWidth(420.dp)
                            .fillMaxSize()
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        UpNextFocusableBox(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            focusRequester = mainCardFocusRequester,
                            onClick = onTriggerAutoplay,
                            onLongClick = { isDismissing = true },
                            onUpKeyRedirect = { navigateUpToPlayerControls() },
                            onBackRedirect = onDismissOverlay
                        ) { isFocused ->
                            val dismissColor = if (isDismissing) Color.Red.copy(alpha = 0.8f) else Color.Transparent
                            Row(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(dismissColor)
                                    .padding(8.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                val thumbPath = nextEpisodeJson.optString("still_path") ?: ""
                                RemoteImage(
                                    url = "https://image.tmdb.org/t/p/w300$thumbPath",
                                    contentDescription = "Next Episode Thumbnail",
                                    modifier = Modifier
                                        .size(110.dp, 72.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                )

                                Spacer(modifier = Modifier.width(12.dp))

                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "UP NEXT",
                                            color = if (isFocused) Color(0xFF0D47A1) else Color(0xFF00BCD4),
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        val rating = nextEpisodeJson.optDouble("vote_average", 0.0)
                                        if (rating > 0.0) {
                                            Text(
                                                text = "⭐ %.1f".format(rating),
                                                color = if (isFocused) Color.Black else Color(0xFFFFB74D),
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }

                                    val epName = nextEpisodeJson.optString("name") ?: "Next Episode"
                                    val epNum = nextEpisodeJson.optInt("episode_number")
                                    Text(
                                        text = "E$epNum: $epName",
                                        color = if (isFocused) Color.Black else Color.White,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )

                                    val overview = nextEpisodeJson.optString("overview") ?: ""
                                    if (overview.isNotEmpty()) {
                                        Text(
                                            text = overview,
                                            color = if (isFocused) Color(0xFF333333) else Color.LightGray,
                                            fontSize = 11.sp,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        UpNextFocusableBox(
                            modifier = Modifier
                                .width(44.dp)
                                .fillMaxHeight(),
                            focusRequester = rightTabFocusRequester,
                            onClick = { 
                                isCompressed = true
                                hasAutoCompressed = true
                            },
                            onUpKeyRedirect = { navigateUpToPlayerControls() },
                            onBackRedirect = onDismissOverlay
                        ) { isFocused ->
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "▶",
                                    color = if (isFocused) Color.Black else Color.White,
                                    fontSize = 18.sp,
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