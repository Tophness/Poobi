package com.poobi.tvbrowser.browser

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.poobi.tvbrowser.R
import com.poobi.tvbrowser.shared.KeyTracker
import com.poobi.tvbrowser.shared.TvFocusableBox

@Composable
fun ContextMenuOverlay(
    cursorX: Float,
    cursorY: Float,
    url: String,
    selectedText: String = "",
    isEditable: Boolean = false,
    hasSelection: Boolean = false,
    hasClipboard: Boolean = false,
    onCut: () -> Unit = {},
    onCopy: () -> Unit = {},
    onPaste: () -> Unit = {},
    onSelectAll: () -> Unit = {},
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
    val menuWidthPx = (220 * density).toInt()
    val menuHeightPx = (320 * density).toInt()

    val clampedX = cursorX.toInt().coerceIn(16, (screenW - menuWidthPx - 16).coerceAtLeast(16))
    val clampedY = cursorY.toInt().coerceIn(16, (screenH - menuHeightPx - 16).coerceAtLeast(16))

    var firstAssigned = false

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
                .background(Color(0xE6222225))
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (isEditable && hasSelection) {
                val req = if (!firstAssigned) { firstAssigned = true; firstItemFocusRequester } else null
                ContextMenuBtn(
                    text = stringResource(R.string.menu_cut),
                    icon = { CutMenuIcon() },
                    focusRequester = req,
                    onClick = { onCut(); onDismiss() }
                )
            }

            if (hasSelection) {
                val req = if (!firstAssigned) { firstAssigned = true; firstItemFocusRequester } else null
                ContextMenuBtn(
                    text = stringResource(R.string.menu_copy),
                    icon = { CopyMenuIcon() },
                    focusRequester = req,
                    onClick = { onCopy(); onDismiss() }
                )
            }

            if (isEditable && hasClipboard) {
                val req = if (!firstAssigned) { firstAssigned = true; firstItemFocusRequester } else null
                ContextMenuBtn(
                    text = stringResource(R.string.menu_paste),
                    icon = { PasteMenuIcon() },
                    focusRequester = req,
                    onClick = { onPaste(); onDismiss() }
                )
            }

            if (isEditable) {
                val req = if (!firstAssigned) { firstAssigned = true; firstItemFocusRequester } else null
                ContextMenuBtn(
                    text = stringResource(R.string.menu_select_all),
                    icon = { SelectAllMenuIcon() },
                    focusRequester = req,
                    onClick = { onSelectAll(); onDismiss() }
                )
            }

            if (url.isNotEmpty() && url.startsWith("http")) {
                val req = if (!firstAssigned) { firstAssigned = true; firstItemFocusRequester } else null
                ContextMenuBtn(
                    text = stringResource(R.string.menu_open_new_tab),
                    icon = {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_add),
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    focusRequester = req,
                    onClick = { onOpenInNewTab(); onDismiss() }
                )
            }

            val refreshReq = if (!firstAssigned) { firstAssigned = true; firstItemFocusRequester } else null
            ContextMenuBtn(
                text = stringResource(R.string.menu_refresh),
                icon = {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_refresh),
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                },
                focusRequester = refreshReq,
                onClick = { onRefresh(); onDismiss() }
            )

            ContextMenuBtn(
                text = stringResource(R.string.menu_block_element),
                icon = {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_close),
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                },
                focusRequester = null,
                onClick = { onBlockElement(); onDismiss() }
            )
        }
    }
}

@Composable
private fun ContextMenuBtn(
    text: String,
    icon: @Composable () -> Unit,
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
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            icon()
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
private fun CutMenuIcon() {
    Canvas(modifier = Modifier.size(16.dp)) {
        val stroke = 1.5.dp.toPx()
        drawCircle(color = Color.White, radius = 2.5.dp.toPx(), center = Offset(4.dp.toPx(), 12.dp.toPx()), style = Stroke(stroke))
        drawCircle(color = Color.White, radius = 2.5.dp.toPx(), center = Offset(12.dp.toPx(), 12.dp.toPx()), style = Stroke(stroke))
        drawLine(color = Color.White, start = Offset(5.dp.toPx(), 10.dp.toPx()), end = Offset(13.dp.toPx(), 2.dp.toPx()), strokeWidth = stroke)
        drawLine(color = Color.White, start = Offset(11.dp.toPx(), 10.dp.toPx()), end = Offset(3.dp.toPx(), 2.dp.toPx()), strokeWidth = stroke)
    }
}

@Composable
private fun CopyMenuIcon() {
    Canvas(modifier = Modifier.size(16.dp)) {
        val stroke = 1.5.dp.toPx()
        drawRect(color = Color.White, topLeft = Offset(5.dp.toPx(), 1.dp.toPx()), size = Size(9.dp.toPx(), 11.dp.toPx()), style = Stroke(stroke))
        drawRect(color = Color.White, topLeft = Offset(2.dp.toPx(), 4.dp.toPx()), size = Size(9.dp.toPx(), 11.dp.toPx()), style = Stroke(stroke))
    }
}

@Composable
private fun PasteMenuIcon() {
    Canvas(modifier = Modifier.size(16.dp)) {
        val stroke = 1.5.dp.toPx()
        drawRect(color = Color.White, topLeft = Offset(2.dp.toPx(), 3.dp.toPx()), size = Size(12.dp.toPx(), 12.dp.toPx()), style = Stroke(stroke))
        drawRect(color = Color.White, topLeft = Offset(5.dp.toPx(), 1.dp.toPx()), size = Size(6.dp.toPx(), 3.dp.toPx()))
    }
}

@Composable
private fun SelectAllMenuIcon() {
    Canvas(modifier = Modifier.size(16.dp)) {
        val stroke = 1.5.dp.toPx()
        drawRect(color = Color.White, topLeft = Offset(2.dp.toPx(), 2.dp.toPx()), size = Size(12.dp.toPx(), 12.dp.toPx()), style = Stroke(stroke))
        drawLine(color = Color.White, start = Offset(5.dp.toPx(), 6.dp.toPx()), end = Offset(11.dp.toPx(), 6.dp.toPx()), strokeWidth = stroke)
        drawLine(color = Color.White, start = Offset(5.dp.toPx(), 10.dp.toPx()), end = Offset(11.dp.toPx(), 10.dp.toPx()), strokeWidth = stroke)
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