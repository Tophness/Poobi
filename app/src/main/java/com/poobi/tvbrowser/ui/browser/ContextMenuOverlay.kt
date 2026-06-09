package com.poobi.tvbrowser.ui.browser

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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.poobi.tvbrowser.ui.shared.TvFocusableBox

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
    // Focus requester dedicated to contextual list item navigation
    val firstItemFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        firstItemFocusRequester.requestFocus()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Transparent)
            .clickableNoIndication { onDismiss() }
    ) {
        Column(
            modifier = Modifier
                .offset { IntOffset(cursorX.toInt(), cursorY.toInt()) }
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
        .height(48.dp)
        .let { if (focusRequester != null) it.focusRequester(focusRequester) else it }

    TvFocusableBox(
        modifier = modifier,
        onClick = onClick
    ) { isFocused ->
        Box(
            modifier = Modifier.fillMaxSize().padding(horizontal = 15.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Text(
                text = text,
                color = Color.White,
                fontSize = 14.sp
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