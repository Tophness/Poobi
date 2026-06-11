package com.poobi.tvbrowser.browser

import android.view.KeyEvent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun TvFocusableHoldToDeleteBox(
    modifier: Modifier = Modifier,
    onTriggerDelete: () -> Unit,
    onClick: () -> Unit,
    onFocus: (() -> Unit)? = null,
    isTabStyle: Boolean = false,
    isSelected: Boolean = false,
    content: @Composable BoxScope.(isFocused: Boolean, progress: Float) -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    var holdProgress by remember { mutableStateOf(0f) }
    var holdJob by remember { mutableStateOf<Job?>(null) }

    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.03f else 1.0f,
        animationSpec = tween(durationMillis = 150),
        label = "DeleteFocusScale"
    )

    val backgroundColor = when {
        isFocused -> Color(0xFF40C4FF)
        isSelected && isTabStyle -> Color(0xFF40C4FF)
        else -> Color(0xFF333333)
    }

    val borderColor = when {
        isFocused -> Color.White
        else -> Color.Transparent
    }

    val shape = if (isTabStyle) {
        RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp, bottomStart = 0.dp, bottomEnd = 0.dp)
    } else {
        RoundedCornerShape(8.dp)
    }

    Box(
        modifier = modifier
            .scale(scale)
            .clip(shape)
            .background(backgroundColor)
            .border(if (isFocused) 2.dp else 0.dp, borderColor, shape)
            .onFocusChanged { state ->
                if (state.isFocused) {
                    onFocus?.invoke()
                }
            }
            .onPreviewKeyEvent { keyEvent ->
                val nativeEvent = keyEvent.nativeKeyEvent
                val isOkKey = nativeEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || 
                             nativeEvent.keyCode == KeyEvent.KEYCODE_ENTER

                if (isOkKey) {
                    if (nativeEvent.action == KeyEvent.ACTION_DOWN) {
                        if (nativeEvent.repeatCount == 0 && holdJob == null) {
                            holdProgress = 0f
                            holdJob = coroutineScope.launch {
                                val startTime = System.currentTimeMillis()
                                val duration = 1000L // 1 Second hold-to-delete limit
                                while (System.currentTimeMillis() - startTime < duration) {
                                    val elapsed = System.currentTimeMillis() - startTime
                                    holdProgress = (elapsed.toFloat() / duration).coerceIn(0f, 1f)
                                    delay(16)
                                }
                                holdProgress = 1f
                                onTriggerDelete()
                                holdProgress = 0f
                                holdJob = null
                            }
                        }
                        return@onPreviewKeyEvent true
                    } else if (nativeEvent.action == KeyEvent.ACTION_UP) {
                        if (holdJob != null) {
                            holdJob?.cancel()
                            holdJob = null
                            if (holdProgress < 0.5f) {
                                onClick()
                            }
                            holdProgress = 0f
                        }
                        return@onPreviewKeyEvent true
                    }
                }
                false
            }
            .focusable(interactionSource = interactionSource),
        contentAlignment = Alignment.Center
    ) {
        content(isFocused, holdProgress)
    }
}