package com.poobi.tvbrowser.ui.player

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.poobi.tvbrowser.ui.shared.RemoteImage
import com.poobi.tvbrowser.ui.shared.TvFocusableBox
import org.json.JSONObject

@Composable
fun UpNextOverlay(
    isVisible: Boolean,
    nextEpisodeJson: JSONObject?,
    onTriggerAutoplay: () -> Unit,
    onDismissOverlay: () -> Unit
) {
    if (isVisible && nextEpisodeJson != null) {
        val focusRequester = remember { FocusRequester() }

        LaunchedEffect(isVisible) {
            if (isVisible) {
                focusRequester.requestFocus()
            }
        }

        Popup(
            alignment = Alignment.BottomEnd,
            properties = PopupProperties(focusable = true)
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
                            kotlinx.coroutines.delay(400)
                            onDismissOverlay()
                            isDismissing = false
                        }
                    }

                    TvFocusableBox(
                        modifier = Modifier
                            .focusRequester(focusRequester)
                            .width(340.dp)
                            .height(100.dp),
                        onClick = onTriggerAutoplay,
                        onLongClick = {
                            isDismissing = true
                        }
                    ) { isFocused ->
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val thumbPath = nextEpisodeJson.optString("still_path") ?: ""
                            RemoteImage(
                                url = "https://image.tmdb.org/t/p/w300$thumbPath",
                                contentDescription = "Next Episode Thumbnail",
                                modifier = Modifier
                                    .size(120.dp, 72.dp)
                                    .clip(RoundedCornerShape(6.dp))
                            )

                            Spacer(modifier = Modifier.width(14.dp))

                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    text = "UP NEXT",
                                    color = Color(0xFF00BCD4),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )

                                val epName = nextEpisodeJson.optString("name") ?: "Next Episode"
                                val epNum = nextEpisodeJson.optInt("episode_number")
                                Text(
                                    text = "E$epNum: $epName",
                                    color = Color.White,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .scale(xButtonScale)
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(if (isDismissing) Color.Red else Color.White.copy(alpha = 0.15f))
                                    .clickable { onDismissOverlay() },
                                contentAlignment = Alignment.Center
                            ) {
                                Text("✕", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}
