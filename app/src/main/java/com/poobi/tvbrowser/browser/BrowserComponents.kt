package com.poobi.tvbrowser.browser

import android.graphics.BitmapFactory
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.poobi.tvbrowser.R
import org.json.JSONObject
import java.io.File

@Composable
fun BrowserCardItem(
    item: JSONObject,
    listKey: String,
    progress: Float,
    isFocused: Boolean
) {
    val context = LocalContext.current
    val title = item.optString("title", "Unknown")
    val url = item.optString("url", "")
    val thumbFile = item.optString("thumb", "")

    var bitmap by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }

    LaunchedEffect(thumbFile) {
        if (thumbFile.isNotEmpty()) {
            val file = File(context.filesDir, thumbFile)
            if (file.exists()) {
                val decoded = BitmapFactory.decodeFile(file.absolutePath)
                if (decoded != null) {
                    bitmap = decoded.asImageBitmap()
                }
            }
        }
    }

    val primaryTextColor = if (isFocused) Color.Black else Color.White
    val secondaryTextColor = if (isFocused) Color(0xFF222222) else Color(0xFFAAAAAA)

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(90.dp)
                    .background(Color(0xFF222222)),
                contentAlignment = Alignment.Center
            ) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap!!,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        painter = painterResource(
                            id = when (listKey) {
                                "history" -> R.drawable.ic_history
                                "downloads" -> R.drawable.ic_download
                                else -> R.drawable.ic_heart_empty
                            }
                        ),
                        contentDescription = null,
                        tint = Color.Gray,
                        modifier = Modifier.size(40.dp)
                    )
                }
            }

            Text(
                text = title,
                color = primaryTextColor,
                fontSize = 14.sp,
                fontWeight = if (isFocused) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp)
            )

            if (listKey == "downloads") {
                val missingLabel = stringResource(R.string.status_missing)
                var statusText by remember { mutableStateOf("Checking...") }
                var statusColor by remember { mutableStateOf(secondaryTextColor) }

                LaunchedEffect(title, isFocused) {
                    val file = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), title)
                    if (file.exists()) {
                        val sizeMb = file.length() / (1024f * 1024f)
                        statusText = String.format("%.2f MB", sizeMb)
                        statusColor = if (isFocused) Color(0xFF222222) else Color(0xFFAAAAAA)
                    } else {
                        statusText = missingLabel
                        statusColor = if (isFocused) Color(0xFFB71C1C) else Color(0xFFFF5252)
                    }
                }

                Text(
                    text = statusText,
                    color = statusColor,
                    fontSize = 11.sp,
                    maxLines = 1
                )
            } else {
                Text(
                    text = url,
                    color = secondaryTextColor,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        if (isFocused || progress > 0f) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
            ) {
                HoldToDeleteCloseButton(progress = progress)
            }
        }
    }
}

@Composable
fun TabItemCard(
    title: String,
    progress: Float,
    isFocused: Boolean = false,
    isSelected: Boolean = false,
    onCloseClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 15.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            color = if (isFocused && !isSelected) Color.Black else Color.White,
            fontSize = 15.sp,
            fontWeight = if (isFocused || isSelected) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )

        HoldToDeleteCloseButton(
            progress = progress,
            onClick = onCloseClick
        )
    }
}

@Composable
fun HoldToDeleteCloseButton(
    progress: Float,
    onClick: (() -> Unit)? = null
) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.5f))
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onClick
                    )
                } else Modifier
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(id = R.drawable.ic_close),
            contentDescription = "Close",
            tint = Color(0xFFCCCCCC),
            modifier = Modifier.size(16.dp)
        )

        if (progress > 0f) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val strokeWidth = 3.dp.toPx()
                drawArc(
                    color = Color(0xFF00BCD4),
                    startAngle = 270f,
                    sweepAngle = 360f * progress,
                    useCenter = false,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )
            }
        }
    }
}