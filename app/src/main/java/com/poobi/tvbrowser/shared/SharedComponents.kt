package com.poobi.tvbrowser.shared

import android.graphics.Bitmap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun Modifier.mouseWheelHorizontalScroll(
    state: LazyListState
): Modifier = this.pointerInput(state) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent()
            if (event.type == PointerEventType.Scroll) {
                var totalDelta = 0f
                for (change in event.changes) {
                    val deltaY = change.scrollDelta.y
                    val deltaX = change.scrollDelta.x
                    val delta = if (deltaY != 0f) deltaY else deltaX
                    totalDelta += delta
                    change.consume()
                }
                if (totalDelta != 0f) {
                    state.dispatchRawDelta(totalDelta * 180f)
                }
            }
        }
    }
}

object ImageCache {
    fun get(url: String): Bitmap? = AppCacheManager.memoryBitmapCache.get(url)
    fun put(url: String, bitmap: Bitmap) {
        AppCacheManager.memoryBitmapCache.put(url, bitmap)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TvFocusableBox(
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    onFocus: (() -> Unit)? = null,
    isTabStyle: Boolean = false,
    isSelected: Boolean = false,
    defaultColor: Color = Color(0xFF333333),
    highlightColor: Color = Color(0xFF40C4FF),
    shape: RoundedCornerShape = if (isTabStyle) RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp, bottomStart = 0.dp, bottomEnd = 0.dp) else RoundedCornerShape(8.dp),
    content: @Composable BoxScope.(isFocused: Boolean) -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    var isPointerHovered by remember { mutableStateOf(false) }
    val isMouseActive by HardwareInputManager.lastInputWasMouse.collectAsState()

    val isHighlighted = if (isMouseActive) isPointerHovered else isFocused

    val scale by animateFloatAsState(
        targetValue = if (isHighlighted) 1.03f else 1.0f,
        animationSpec = tween(durationMillis = 150),
        label = "FocusScale"
    )

    val backgroundColor = when {
        isHighlighted -> highlightColor
        isSelected && isTabStyle -> highlightColor
        else -> defaultColor
    }

    val borderColor = when {
        isHighlighted -> Color.White
        else -> Color.Transparent
    }

    Box(
        modifier = modifier
            .scale(scale)
            .clip(shape)
            .background(backgroundColor)
            .border(if (isHighlighted) 2.dp else 0.dp, borderColor, shape)
            .onFocusChanged { state ->
                if (state.isFocused) {
                    onFocus?.invoke()
                }
            }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        when (event.type) {
                            PointerEventType.Enter, PointerEventType.Move -> {
                                HardwareInputManager.notifyMouseUsed()
                                isPointerHovered = true
                            }
                            PointerEventType.Exit -> {
                                isPointerHovered = false
                            }
                        }
                    }
                }
            }
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick
            )
            .focusable(interactionSource = interactionSource)
    ) {
        content(isHighlighted)
    }
}

@Composable
fun RemoteImage(
    url: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop
) {
    val context = LocalContext.current
    var imageBitmap by remember(url) { 
        mutableStateOf<ImageBitmap?>(
            AppCacheManager.getImageBitmap(context, url)?.asImageBitmap()
        ) 
    }

    LaunchedEffect(url) {
        if (imageBitmap == null && url.isNotBlank() && url != "https://image.tmdb.org/t/p/w185" && !url.endsWith("null")) {
            withContext(Dispatchers.IO) {
                val diskCached = AppCacheManager.getImageBitmap(context, url)
                if (diskCached != null) {
                    withContext(Dispatchers.Main) {
                        imageBitmap = diskCached.asImageBitmap()
                    }
                    return@withContext
                }

                try {
                    val connection = URL(url).openConnection() as HttpURLConnection
                    connection.connectTimeout = 6000
                    connection.readTimeout = 6000
                    val bytes = connection.inputStream.use { it.readBytes() }
                    val bitmap = AppCacheManager.saveImageBytes(context, url, bytes)
                    if (bitmap != null) {
                        withContext(Dispatchers.Main) {
                            imageBitmap = bitmap.asImageBitmap()
                        }
                    }
                } catch (_: Exception) {}
            }
        }
    }

    val bitmap = imageBitmap
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = contentScale
        )
    } else {
        Box(
            modifier = modifier.background(Color(0xFF2E2E38)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = null,
                tint = Color.Gray,
                modifier = Modifier.size(32.dp)
            )
        }
    }
}

fun isFutureDate(dateStr: String?): Boolean {
    if (dateStr.isNullOrEmpty() || dateStr == "null") return false
    return try {
        val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        dateStr > todayStr
    } catch (e: Exception) {
        false
    }
}