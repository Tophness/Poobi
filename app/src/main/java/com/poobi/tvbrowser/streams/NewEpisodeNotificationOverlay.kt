package com.poobi.tvbrowser.streams

import android.view.KeyEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.poobi.tvbrowser.R
import com.poobi.tvbrowser.shared.RemoteImage
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun NewEpisodeNotificationOverlay(
    isVisible: Boolean,
    notificationsList: List<JSONObject>,
    onPlayEpisode: (JSONObject, Int) -> Unit,
    onDismissItem: (Int) -> Unit,
    onDismissAll: () -> Unit
) {
    if (isVisible && notificationsList.isNotEmpty()) {
        val focusRequester = remember { FocusRequester() }
        val dismissAllFocusRequester = remember { FocusRequester() }
        val coroutineScope = rememberCoroutineScope()
        
        var currentIndex by remember(notificationsList.size) { 
            mutableStateOf(0) 
        }
        
        val activeIndex = currentIndex.coerceIn(0, notificationsList.size - 1)
        val notificationData = notificationsList.getOrNull(activeIndex)

        var holdProgress by remember { mutableStateOf(0f) }
        var holdJob by remember { mutableStateOf<Job?>(null) }

        if (notificationData != null) {
            val showTitle = notificationData.optString("show_title", "Unknown Show")
            val episodeTitle = notificationData.optString("episode_name", "New Episode")
            val season = notificationData.optInt("season", 0)
            val episodeNum = notificationData.optInt("number", 0)

            val itemObj = notificationData.optJSONObject("item")
            val rating = itemObj?.optDouble("vote_average", 0.0) ?: 0.0
            val overview = notificationData.optString("episode_overview").takeIf { it.isNotEmpty() }
                ?: itemObj?.optString("overview")
                ?: "No description available."

            val stillPath = notificationData.optString("still_path", "")
            val posterPath = itemObj?.optString("poster_path", "") ?: ""
            val backdropPath = itemObj?.optString("backdrop_path", "") ?: ""
            
            val imageUrl = when {
                stillPath.isNotEmpty() -> "https://image.tmdb.org/t/p/w300$stillPath"
                backdropPath.isNotEmpty() -> "https://image.tmdb.org/t/p/w780$backdropPath"
                posterPath.isNotEmpty() -> "https://image.tmdb.org/t/p/w342$posterPath"
                else -> ""
            }

            val airdateStr = notificationData.optString("airdate") ?: ""
            val formattedDate = remember(airdateStr) {
                if (airdateStr.isNotEmpty()) {
                    try {
                        val parser = if (airdateStr.contains("T")) {
                            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
                                timeZone = TimeZone.getTimeZone("UTC")
                            }
                        } else {
                            SimpleDateFormat("yyyy-MM-dd", Locale.US)
                        }

                        val cleanDateStr = airdateStr.replace("Z", "").split(".")[0]
                        val date = parser.parse(cleanDateStr)
                        if (date != null) {
                            val localFormatter = SimpleDateFormat("d MMMM h:mm a", Locale.getDefault()).apply {
                                timeZone = TimeZone.getDefault()
                            }
                            localFormatter.format(date)
                        } else {
                            airdateStr
                        }
                    } catch (e: Exception) {
                        airdateStr
                    }
                } else ""
            }

            LaunchedEffect(isVisible) {
                if (isVisible) {
                    delay(300)
                    try {
                        focusRequester.requestFocus()
                    } catch (e: Exception) {}
                }
            }

            Popup(
                alignment = Alignment.BottomEnd,
                onDismissRequest = onDismissAll,
                properties = PopupProperties(
                    focusable = true,
                    dismissOnBackPress = true,
                    dismissOnClickOutside = false
                )
            ) {
                AnimatedVisibility(
                    visible = isVisible,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(bottom = 120.dp, end = 50.dp),
                        contentAlignment = Alignment.BottomEnd
                    ) {
                        Column(
                            horizontalAlignment = Alignment.End,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.wrapContentSize()
                        ) {
                            val cardInteractionSource = remember { MutableInteractionSource() }
                            val isCardFocused by cardInteractionSource.collectIsFocusedAsState()

                            val cardScale by animateFloatAsState(
                                targetValue = if (isCardFocused) 1.03f else 1.0f,
                                animationSpec = tween(durationMillis = 150),
                                label = "CardScale"
                            )

                            val containerColor = when {
                                holdProgress > 0f -> Color(0xFFE53935).copy(alpha = 0.95f)
                                isCardFocused -> Color(0xFF40C4FF)
                                else -> Color(0xFF1E1E24).copy(alpha = 0.95f)
                            }

                            val headerColor = if (isCardFocused && holdProgress == 0f) Color(0xFF0D47A1) else Color(0xFF00BCD4)
                            val ratingColor = if (isCardFocused && holdProgress == 0f) Color.Black else Color(0xFFFFB74D)
                            val titleColor = if (isCardFocused && holdProgress == 0f) Color.Black else Color.White
                            val subtitleColor = if (isCardFocused && holdProgress == 0f) Color(0xFF0D47A1) else Color(0xFF40C4FF)
                            val dateColor = if (isCardFocused && holdProgress == 0f) Color(0xFF1B5E20) else Color(0xFF81C784)
                            val overviewColor = if (isCardFocused && holdProgress == 0f) Color(0xFF333333) else Color.LightGray

                            Box(
                                modifier = Modifier
                                    .focusRequester(focusRequester)
                                    .width(420.dp)
                                    .wrapContentHeight()
                                    .scale(cardScale)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(containerColor)
                                    .border(
                                        width = if (isCardFocused) 2.dp else 1.dp,
                                        color = if (isCardFocused) Color.White else Color(0xFF333338),
                                        shape = RoundedCornerShape(12.dp)
                                    )
                                    .onPreviewKeyEvent { keyEvent ->
                                        val nativeEvent = keyEvent.nativeKeyEvent
                                        val keyCode = nativeEvent.keyCode
                                        val isDown = nativeEvent.action == KeyEvent.ACTION_DOWN

                                        if (isDown) {
                                            when (keyCode) {
                                                KeyEvent.KEYCODE_BACK -> {
                                                    onDismissAll()
                                                    return@onPreviewKeyEvent true
                                                }
                                                KeyEvent.KEYCODE_DPAD_LEFT -> {
                                                    if (notificationsList.size > 1) {
                                                        currentIndex = if (activeIndex > 0) activeIndex - 1 else notificationsList.size - 1
                                                        return@onPreviewKeyEvent true
                                                    }
                                                }
                                                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                                    if (notificationsList.size > 1) {
                                                        currentIndex = if (activeIndex < notificationsList.size - 1) activeIndex + 1 else 0
                                                        return@onPreviewKeyEvent true
                                                    }
                                                }
                                                KeyEvent.KEYCODE_DPAD_DOWN -> {
                                                    try {
                                                        dismissAllFocusRequester.requestFocus()
                                                        return@onPreviewKeyEvent true
                                                    } catch (e: Exception) {}
                                                }
                                                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                                                    if (nativeEvent.repeatCount == 0 && holdJob == null) {
                                                        holdProgress = 0f
                                                        holdJob = coroutineScope.launch {
                                                            val startTime = System.currentTimeMillis()
                                                            val duration = 1000L
                                                            while (System.currentTimeMillis() - startTime < duration) {
                                                                val elapsed = System.currentTimeMillis() - startTime
                                                                holdProgress = (elapsed.toFloat() / duration).coerceIn(0f, 1f)
                                                                delay(16)
                                                            }
                                                            holdProgress = 1f
                                                            onDismissItem(activeIndex)
                                                            holdProgress = 0f
                                                            holdJob = null
                                                        }
                                                    }
                                                    return@onPreviewKeyEvent true
                                                }
                                            }
                                        } else {
                                            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                                                if (holdJob != null) {
                                                    holdJob?.cancel()
                                                    holdJob = null
                                                    if (holdProgress < 0.5f) {
                                                        onPlayEpisode(notificationData, activeIndex)
                                                    }
                                                    holdProgress = 0f
                                                }
                                                return@onPreviewKeyEvent true
                                            }
                                        }
                                        false
                                    }
                                    .focusable(interactionSource = cardInteractionSource)
                                    .padding(12.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.Top,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    if (imageUrl.isNotEmpty()) {
                                        RemoteImage(
                                            url = imageUrl,
                                            contentDescription = showTitle,
                                            modifier = Modifier
                                                .size(110.dp, 150.dp)
                                                .clip(RoundedCornerShape(6.dp))
                                        )
                                    } else {
                                        Box(
                                            modifier = Modifier
                                                .size(110.dp, 150.dp)
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(Color.Gray.copy(alpha = 0.2f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                painter = painterResource(id = R.drawable.ic_auto_play),
                                                contentDescription = null,
                                                tint = titleColor.copy(alpha = 0.5f),
                                                modifier = Modifier.size(32.dp)
                                            )
                                        }
                                    }

                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            val currentHeader = if (notificationsList.size > 1) {
                                                "NEW EPISODE (${activeIndex + 1}/${notificationsList.size})"
                                            } else {
                                                "NEW EPISODE AVAILABLE"
                                            }
                                            Text(
                                                text = currentHeader,
                                                color = headerColor,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                            if (rating > 0.0) {
                                                Text(
                                                    text = "⭐ %.1f".format(rating),
                                                    color = ratingColor,
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }

                                        Text(
                                            text = showTitle,
                                            color = titleColor,
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )

                                        Text(
                                            text = "S${season}E${episodeNum}: $episodeTitle",
                                            color = subtitleColor,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.padding(top = 2.dp)
                                        )

                                        if (formattedDate.isNotEmpty()) {
                                            Text(
                                                text = "Aired: $formattedDate",
                                                color = dateColor,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(top = 2.dp)
                                            )
                                        }

                                        Text(
                                            text = overview,
                                            color = overviewColor,
                                            fontSize = 11.sp,
                                            maxLines = 4,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.padding(top = 6.dp)
                                        )
                                    }
                                }
                            }

                            Row(
                                modifier = Modifier.width(420.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (notificationsList.size > 1) {
                                    Text(
                                        text = "◀  Press Left / Right to cycle  ▶",
                                        color = Color.Gray,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier.padding(start = 4.dp)
                                    )
                                } else {
                                    Spacer(modifier = Modifier.width(1.dp))
                                }

                                val dismissInteractionSource = remember { MutableInteractionSource() }
                                val isDismissFocused by dismissInteractionSource.collectIsFocusedAsState()

                                val dismissScale by animateFloatAsState(
                                    targetValue = if (isDismissFocused) 1.05f else 1.0f,
                                    animationSpec = tween(durationMillis = 150),
                                    label = "DismissScale"
                                )

                                Box(
                                    modifier = Modifier
                                        .focusRequester(dismissAllFocusRequester)
                                        .scale(dismissScale)
                                        .clip(RoundedCornerShape(18.dp))
                                        .background(if (isDismissFocused) Color(0xFFE53935) else Color(0xFF333338))
                                        .border(
                                            width = if (isDismissFocused) 2.dp else 1.dp,
                                            color = if (isDismissFocused) Color.White else Color.Transparent,
                                            shape = RoundedCornerShape(18.dp)
                                        )
                                        .onPreviewKeyEvent { keyEvent ->
                                            val nativeEvent = keyEvent.nativeKeyEvent
                                            val isDown = nativeEvent.action == KeyEvent.ACTION_DOWN
                                            if (isDown) {
                                                when (nativeEvent.keyCode) {
                                                    KeyEvent.KEYCODE_BACK -> {
                                                        onDismissAll()
                                                        return@onPreviewKeyEvent true
                                                    }
                                                    KeyEvent.KEYCODE_DPAD_UP -> {
                                                        try {
                                                            focusRequester.requestFocus()
                                                            return@onPreviewKeyEvent true
                                                        } catch (e: Exception) {}
                                                    }
                                                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                                                        onDismissAll()
                                                        return@onPreviewKeyEvent true
                                                    }
                                                }
                                            }
                                            false
                                        }
                                        .clickable(
                                            interactionSource = dismissInteractionSource,
                                            indication = null,
                                            onClick = onDismissAll
                                        )
                                        .focusable(interactionSource = dismissInteractionSource)
                                        .padding(horizontal = 16.dp, vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = if (notificationsList.size > 1) "Dismiss All" else "Dismiss",
                                        color = Color.White,
                                        fontSize = 12.sp,
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