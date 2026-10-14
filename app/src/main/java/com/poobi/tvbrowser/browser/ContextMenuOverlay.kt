package com.poobi.tvbrowser.browser

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.poobi.tvbrowser.R
import com.poobi.tvbrowser.shared.KeyTracker
import com.poobi.tvbrowser.shared.TvFocusableBox

private val CutIcon: ImageVector by lazy {
    ImageVector.Builder(name = "Cut", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .path(fill = SolidColor(Color.White)) {
            moveTo(9.64f, 7.64f)
            curveToRelative(0.23f, -0.5f, 0.36f, -1.05f, 0.36f, -1.64f)
            curveToRelative(0f, -2.21f, -1.79f, -4f, -4f, -4f)
            reflectiveCurveTo(2f, 3.79f, 2f, 6f)
            reflectiveCurveToRelative(1.79f, 4f, 4f, 4f)
            curveToRelative(0.59f, 0f, 1.14f, -0.13f, 1.64f, -0.36f)
            lineTo(10f, 12f)
            lineToRelative(-2.36f, 2.36f)
            curveTo(7.14f, 14.13f, 6.59f, 14f, 6f, 14f)
            curveToRelative(-2.21f, 0f, -4f, 1.79f, -4f, 4f)
            reflectiveCurveToRelative(1.79f, 4f, 4f, 4f)
            reflectiveCurveToRelative(4f, -1.79f, 4f, -4f)
            curveToRelative(0f, -0.59f, -0.13f, -1.14f, -0.36f, -1.64f)
            lineTo(12f, 14f)
            lineToRelative(7f, 7f)
            horizontalLineToRelative(3f)
            verticalLineToRelative(-1f)
            lineTo(9.64f, 7.64f)
            close()
            moveTo(6f, 8f)
            curveToRelative(-1.1f, 0f, -2f, -0.9f, -2f, -2f)
            reflectiveCurveToRelative(0.9f, -2f, 2f, -2f)
            reflectiveCurveToRelative(2f, 0.9f, 2f, 2f)
            reflectiveCurveToRelative(-0.9f, 2f, -2f, 2f)
            close()
            moveTo(6f, 20f)
            curveToRelative(-1.1f, 0f, -2f, -0.9f, -2f, -2f)
            reflectiveCurveToRelative(0.9f, -2f, 2f, -2f)
            reflectiveCurveToRelative(2f, 0.9f, 2f, 2f)
            reflectiveCurveToRelative(-0.9f, 2f, -2f, 2f)
            close()
            moveTo(19f, 3f)
            lineToRelative(-6f, 6f)
            lineToRelative(2f, 2f)
            lineToRelative(7f, -7f)
            verticalLineTo(3f)
            horizontalLineToRelative(-3f)
            close()
        }.build()
}

private val CopyIcon: ImageVector by lazy {
    ImageVector.Builder(name = "Copy", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .path(fill = SolidColor(Color.White)) {
            moveTo(16f, 1f)
            horizontalLineTo(4f)
            curveToRelative(-1.1f, 0f, -2f, 0.9f, -2f, 2f)
            verticalLineToRelative(14f)
            horizontalLineToRelative(2f)
            verticalLineTo(3f)
            horizontalLineToRelative(12f)
            verticalLineTo(1f)
            close()
            moveTo(19f, 5f)
            horizontalLineTo(8f)
            curveToRelative(-1.1f, 0f, -2f, 0.9f, -2f, 2f)
            verticalLineToRelative(14f)
            curveToRelative(0f, 1.1f, 0.9f, 2f, 2f, 2f)
            horizontalLineToRelative(11f)
            curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
            verticalLineTo(7f)
            curveToRelative(0f, -1.1f, -0.9f, -2f, -2f, -2f)
            close()
            moveTo(19f, 21f)
            horizontalLineTo(8f)
            verticalLineTo(7f)
            horizontalLineToRelative(11f)
            verticalLineToRelative(14f)
            close()
        }.build()
}

private val PasteIcon: ImageVector by lazy {
    ImageVector.Builder(name = "Paste", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .path(fill = SolidColor(Color.White)) {
            moveTo(19f, 2f)
            horizontalLineToRelative(-4.18f)
            curveTo(14.4f, 0.84f, 13.3f, 0f, 12f, 0f)
            reflectiveCurveToRelative(-2.4f, 0.84f, -2.82f, 2f)
            horizontalLineTo(5f)
            curveToRelative(-1.1f, 0f, -2f, 0.9f, -2f, 2f)
            verticalLineToRelative(16f)
            curveToRelative(0f, 1.1f, 0.9f, 2f, 2f, 2f)
            horizontalLineToRelative(14f)
            curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
            verticalLineTo(4f)
            curveToRelative(0f, -1.1f, -0.9f, -2f, -2f, -2f)
            close()
            moveTo(12f, 2f)
            curveToRelative(0.55f, 0f, 1f, 0.45f, 1f, 1f)
            reflectiveCurveToRelative(-0.45f, 1f, -1f, 1f)
            reflectiveCurveToRelative(-1f, -0.45f, -1f, -1f)
            reflectiveCurveToRelative(0.45f, -1f, 1f, -1f)
            close()
            moveTo(19f, 20f)
            horizontalLineTo(5f)
            verticalLineTo(4f)
            horizontalLineToRelative(2f)
            verticalLineToRelative(3f)
            horizontalLineToRelative(10f)
            verticalLineTo(4f)
            horizontalLineToRelative(2f)
            verticalLineToRelative(16f)
            close()
        }.build()
}

private val SelectAllIcon: ImageVector by lazy {
    ImageVector.Builder(name = "SelectAll", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .path(fill = SolidColor(Color.White)) {
            moveTo(3f, 5f)
            horizontalLineToRelative(2f)
            verticalLineTo(3f)
            curveToRelative(-1.1f, 0f, -2f, 0.9f, -2f, 2f)
            close()
            moveTo(3f, 13f)
            horizontalLineToRelative(2f)
            verticalLineToRelative(-2f)
            horizontalLineTo(3f)
            verticalLineToRelative(2f)
            close()
            moveTo(7f, 21f)
            horizontalLineToRelative(2f)
            verticalLineToRelative(-2f)
            horizontalLineTo(7f)
            verticalLineToRelative(2f)
            close()
            moveTo(3f, 9f)
            horizontalLineToRelative(2f)
            verticalLineTo(7f)
            horizontalLineTo(3f)
            verticalLineToRelative(2f)
            close()
            moveTo(13f, 3f)
            horizontalLineToRelative(-2f)
            verticalLineToRelative(2f)
            horizontalLineToRelative(2f)
            verticalLineTo(3f)
            close()
            moveTo(19f, 3f)
            verticalLineToRelative(2f)
            horizontalLineToRelative(2f)
            curveToRelative(0f, -1.1f, -0.9f, -2f, -2f, -2f)
            close()
            moveTo(5f, 21f)
            verticalLineToRelative(-2f)
            horizontalLineTo(3f)
            curveToRelative(0f, 1.1f, 0.9f, 2f, 2f, 2f)
            close()
            moveTo(3f, 17f)
            horizontalLineToRelative(2f)
            verticalLineToRelative(-2f)
            horizontalLineTo(3f)
            verticalLineToRelative(2f)
            close()
            moveTo(9f, 3f)
            horizontalLineTo(7f)
            verticalLineToRelative(2f)
            horizontalLineToRelative(2f)
            verticalLineTo(3f)
            close()
            moveTo(11f, 21f)
            horizontalLineToRelative(2f)
            verticalLineToRelative(-2f)
            horizontalLineToRelative(-2f)
            verticalLineToRelative(2f)
            close()
            moveTo(19f, 13f)
            horizontalLineToRelative(2f)
            verticalLineToRelative(-2f)
            horizontalLineToRelative(-2f)
            verticalLineToRelative(2f)
            close()
            moveTo(19f, 21f)
            curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
            horizontalLineToRelative(-2f)
            verticalLineToRelative(2f)
            close()
            moveTo(19f, 9f)
            horizontalLineToRelative(2f)
            verticalLineTo(7f)
            horizontalLineToRelative(-2f)
            verticalLineToRelative(2f)
            close()
            moveTo(19f, 17f)
            horizontalLineToRelative(2f)
            verticalLineToRelative(-2f)
            horizontalLineToRelative(-2f)
            verticalLineToRelative(2f)
            close()
            moveTo(15f, 21f)
            horizontalLineToRelative(2f)
            verticalLineToRelative(-2f)
            horizontalLineToRelative(-2f)
            verticalLineToRelative(2f)
            close()
            moveTo(15f, 5f)
            horizontalLineToRelative(2f)
            verticalLineTo(3f)
            horizontalLineToRelative(-2f)
            verticalLineToRelative(2f)
            close()
            moveTo(7f, 17f)
            horizontalLineToRelative(10f)
            verticalLineTo(7f)
            horizontalLineTo(7f)
            verticalLineToRelative(10f)
            close()
            moveTo(9f, 9f)
            horizontalLineToRelative(6f)
            verticalLineToRelative(6f)
            horizontalLineTo(9f)
            verticalLineTo(9f)
            close()
        }.build()
}

@Composable
fun ContextMenuOverlay(
    cursorX: Float,
    cursorY: Float,
    url: String,
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
    val menuWidthPx = (240 * density).toInt()
    val menuHeightPx = (320 * density).toInt()

    val clampedX = cursorX.toInt().coerceIn(16, (screenW - menuWidthPx - 16).coerceAtLeast(16))
    val clampedY = cursorY.toInt().coerceIn(16, (screenH - menuHeightPx - 16).coerceAtLeast(16))

    val firstButton = when {
        isEditable && hasSelection -> "cut"
        hasSelection -> "copy"
        isEditable && hasClipboard -> "paste"
        isEditable -> "select_all"
        url.isNotEmpty() && url.startsWith("http") -> "new_tab"
        else -> "refresh"
    }

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
            if (isEditable && hasSelection) {
                ContextMenuBtn(
                    text = "Cut",
                    icon = CutIcon,
                    focusRequester = if (firstButton == "cut") firstItemFocusRequester else null,
                    onClick = { onCut(); onDismiss() }
                )
            }

            if (hasSelection) {
                ContextMenuBtn(
                    text = "Copy",
                    icon = CopyIcon,
                    focusRequester = if (firstButton == "copy") firstItemFocusRequester else null,
                    onClick = { onCopy(); onDismiss() }
                )
            }

            if (isEditable && hasClipboard) {
                ContextMenuBtn(
                    text = "Paste",
                    icon = PasteIcon,
                    focusRequester = if (firstButton == "paste") firstItemFocusRequester else null,
                    onClick = { onPaste(); onDismiss() }
                )
            }

            if (isEditable || hasSelection) {
                ContextMenuBtn(
                    text = "Select All",
                    icon = SelectAllIcon,
                    focusRequester = if (firstButton == "select_all") firstItemFocusRequester else null,
                    onClick = { onSelectAll(); onDismiss() }
                )
            }

            if (url.isNotEmpty() && url.startsWith("http")) {
                ContextMenuBtn(
                    text = "Open in New Tab",
                    icon = R.drawable.ic_tab,
                    focusRequester = if (firstButton == "new_tab") firstItemFocusRequester else null,
                    onClick = { onOpenInNewTab(); onDismiss() }
                )
            }

            ContextMenuBtn(
                text = "Refresh Page",
                icon = R.drawable.ic_refresh,
                focusRequester = if (firstButton == "refresh") firstItemFocusRequester else null,
                onClick = { onRefresh(); onDismiss() }
            )

            ContextMenuBtn(
                text = "Block Element",
                icon = R.drawable.ic_close,
                focusRequester = null,
                onClick = { onBlockElement(); onDismiss() }
            )
        }
    }
}

@Composable
private fun ContextMenuBtn(
    text: String,
    icon: Any,
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
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val contentColor = if (isHighlighted) Color.Black else Color.White
            when (icon) {
                is ImageVector -> {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = contentColor,
                        modifier = Modifier.size(18.dp)
                    )
                }
                is Int -> {
                    Icon(
                        painter = painterResource(id = icon),
                        contentDescription = null,
                        tint = contentColor,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            Text(
                text = text,
                color = contentColor,
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