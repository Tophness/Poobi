package com.poobi.tvbrowser.player

import android.view.KeyEvent
import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.poobi.tvbrowser.shared.RemoteImage
import com.poobi.tvbrowser.shared.TvFocusableBox
import org.json.JSONObject
import kotlinx.coroutines.delay

@Composable
fun UpNextOverlay(
    isVisible: Boolean,
    nextEpisodeJson: JSONObject?,
    onTriggerAutoplay: () -> Unit,
    onDismissOverlay: () -> Unit,
    onSeek: (direction: Int, repeatCount: Int) -> Unit
) {
    if (isVisible && nextEpisodeJson != null) {
        val focusRequester = remember { FocusRequester() }

        LaunchedEffect(isVisible) {
            if (isVisible) {
                delay(150)
                focusRequester.requestFocus()
            }
        }

        Popup(
            alignment = Alignment.BottomEnd,
            onDismissRequest = onDismissOverlay,
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
                        .padding(bottom = 100.dp, end = 50.dp),
                    contentAlignment = Alignment.BottomEnd
                ) {
                    var isDismissing by remember { mutableStateOf(false) }
                    val xButtonScale by animateFloatAsState(targetValue = if (isDismissing) 1.4f else 1f)

                    if (isDismissing) {
                        LaunchedEffect(Unit) {
                            delay(400)
                            onDismissOverlay()
                            isDismissing = false
                        }
                    }

                    TvFocusableBox(
                        modifier = Modifier
                            .focusRequester(focusRequester)
                            .width(420.dp)
                            .wrapContentHeight()
                            .onPreviewKeyEvent { keyEvent ->
                                val nativeEvent = keyEvent.nativeKeyEvent
                                val keyCode = nativeEvent.keyCode
                                val isDown = nativeEvent.action == KeyEvent.ACTION_DOWN

                                if (isDown) {
                                    when (keyCode) {
                                        KeyEvent.KEYCODE_BACK -> {
                                            onDismissOverlay()
                                            return@onPreviewKeyEvent true
                                        }
                                        KeyEvent.KEYCODE_DPAD_LEFT -> {
                                            onSeek(-1, nativeEvent.repeatCount)
                                            return@onPreviewKeyEvent true
                                        }
                                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                            onSeek(1, nativeEvent.repeatCount)
                                            return@onPreviewKeyEvent true
                                        }
                                    }
                                }
                                false
                            },
                        onClick = onTriggerAutoplay,
                        onLongClick = {
                            isDismissing = true
                        }
                    ) { isFocused ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            val thumbPath = nextEpisodeJson.optString("still_path") ?: ""
                            RemoteImage(
                                url = "https://image.tmdb.org/t/p/w300$thumbPath",
                                contentDescription = "Next Episode Thumbnail",
                                modifier = Modifier
                                    .size(120.dp, 80.dp)
                                    .clip(RoundedCornerShape(6.dp))
                            )

                            Spacer(modifier = Modifier.width(14.dp))

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
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )

                                val airDate = nextEpisodeJson.optString("air_date") ?: ""
                                if (airDate.isNotEmpty()) {
                                    val formattedDate = try {
                                        val parser = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                                        val formatter = java.text.SimpleDateFormat("d MMMM yyyy", java.util.Locale.getDefault())
                                        val date = parser.parse(airDate)
                                        if (date != null) formatter.format(date) else airDate
                                    } catch (e: Exception) {
                                        airDate
                                    }
                                    Text(
                                        text = "Aired: $formattedDate",
                                        color = if (isFocused) Color(0xFF1B5E20) else Color(0xFF81C784),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                                val overview = nextEpisodeJson.optString("overview") ?: ""
                                if (overview.isNotEmpty()) {
                                    Text(
                                        text = overview,
                                        color = if (isFocused) Color(0xFF333333) else Color.LightGray,
                                        fontSize = 11.sp,
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(top = 4.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(8.dp))

                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .scale(xButtonScale)
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(if (isDismissing) Color.Red else (if (isFocused) Color.Black.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.15f)))
                                    .clickable { onDismissOverlay() },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "✕", 
                                    color = if (isFocused && !isDismissing) Color.Black else Color.White, 
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