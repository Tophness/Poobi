package com.poobi.tvbrowser.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.poobi.tvbrowser.shared.TvFocusableBox
import com.poobi.tvbrowser.shared.KeyTracker

@Composable
fun ContextMenuOverlay(
    cursorX: Float,
    cursorY: Float,
    url: String,
    onOpenInNewTab: () -> Unit,
    onRefresh: () -> Unit,
    onBlockElement: () -> Unit,
    onDismiss: () -> Unit
) {
    val firstItemFocusRequester = remember { FocusRequester() }
    val context = LocalContext.current
    val isRemoteInitiated = remember { KeyTracker.isKeyFresh() }

    LaunchedEffect(Unit) {
        if (isRemoteInitiated) {
            firstItemFocusRequester.requestFocus()
        }
    }

    val displayMetrics = context.resources.displayMetrics
    val screenW = displayMetrics.widthPixels
    val screenH = displayMetrics.heightPixels
    val density = displayMetrics.density
    val menuWidthPx = (240 * density).toInt()
    val menuHeightPx = (200 * density).toInt()

    val clampedX = cursorX.toInt().coerceIn(16, (screenW - menuWidthPx - 16).coerceAtLeast(16))
    val clampedY = cursorY.toInt().coerceIn(16, (screenH - menuHeightPx - 16).coerceAtLeast(16))

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Transparent)
            .clickableNoIndication { onDismiss() }
    ) {
        Column(
            modifier = Modifier
                .offset { IntOffset(clampedX, clampedY) }
                .width(220.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xE6222222))
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (url.isNotEmpty() && url.startsWith("http")) {
                ContextMenuBtn(
                    text = "Open in New Tab",
                    focusRequester = firstItemFocusRequester,
                    onClick = {
                        onOpenInNewTab()
                        onDismiss()
                    }
                )
            }

            ContextMenuBtn(
                text = "Refresh Page",
                focusRequester = if (url.isEmpty() || !url.startsWith("http")) firstItemFocusRequester else null,
                onClick = {
                    onRefresh()
                    onDismiss()
                }
            )

            ContextMenuBtn(
                text = "Block Element",
                focusRequester = null,
                onClick = {
                    onBlockElement()
                    onDismiss()
                }
            )
        }
    }
}

@Composable
private fun ContextMenuBtn(
    text: String,
    focusRequester: FocusRequester?,
    onClick: () -> Unit
) {
    val modifier = Modifier
        .fillMaxWidth()
        .height(44.dp)
        .let { if (focusRequester != null) it.focusRequester(focusRequester) else it }

    TvFocusableBox(
        modifier = modifier,
        onClick = onClick
    ) { isHighlighted ->
        Box(
            modifier = Modifier.fillMaxSize().padding(horizontal = 15.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Text(
                text = text,
                color = if (isHighlighted) Color.Black else Color.White,
                fontSize = 14.sp,
                fontWeight = if (isHighlighted) FontWeight.Bold else FontWeight.Normal
            )
        }
    }
}

@Composable
private fun Modifier.clickableNoIndication(onClick: () -> Unit): Modifier = this.then(
    Modifier.clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onClick = onClick
    )
)