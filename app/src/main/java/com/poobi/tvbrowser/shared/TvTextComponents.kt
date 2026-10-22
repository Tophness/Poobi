package com.poobi.tvbrowser.shared

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object InputFieldState {
    var isEditing: Boolean = false
    var onDismissRequest: (() -> Unit)? = null
}

@Composable
fun TvMarqueeText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontWeight: FontWeight? = null,
    maxLines: Int = 1,
    isFocused: Boolean = false
) {
    Text(
        text = text,
        color = color,
        fontSize = fontSize,
        fontWeight = fontWeight,
        maxLines = maxLines,
        overflow = if (maxLines == 1) TextOverflow.Clip else TextOverflow.Ellipsis,
        modifier = modifier
            .then(if (isFocused && maxLines == 1) Modifier.basicMarquee() else Modifier)
    )
}

class PredictiveTextVisualTransformation(private val suffix: String) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        if (suffix.isEmpty()) return TransformedText(text, OffsetMapping.Identity)
        val combined = buildAnnotatedString {
            append(text)
            withStyle(SpanStyle(color = Color.Gray)) {
                append(suffix)
            }
        }
        val offsetMapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int = offset
            override fun transformedToOriginal(offset: Int): Int = offset.coerceAtMost(text.length)
        }
        return TransformedText(combined, offsetMapping)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TvInputField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    label: String = "",
    isPassword: Boolean = false,
    containerColor: Color = Color(0xFF333333),
    imeAction: ImeAction = ImeAction.Search,
    focusRequester: FocusRequester? = null,
    predictedSuffix: String = "",
    onAcceptPrediction: (() -> Unit)? = null,
    onAction: () -> Unit = {}
) {
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    var isEditing by remember { mutableStateOf(false) }
    var isAcceptingRight by remember { mutableStateOf(false) }

    val defaultOuterFocusRequester = remember { FocusRequester() }
    val outerFocusRequester = focusRequester ?: defaultOuterFocusRequester
    val innerFocusRequester = remember { FocusRequester() }

    val outerInteractionSource = remember { MutableInteractionSource() }
    val isOuterFocused by outerInteractionSource.collectIsFocusedAsState()

    val innerInteractionSource = remember { MutableInteractionSource() }
    val isInnerFocused by innerInteractionSource.collectIsFocusedAsState()

    val isFocused = isOuterFocused || isInnerFocused

    var textFieldValue by remember {
        mutableStateOf(TextFieldValue(text = value, selection = TextRange(value.length)))
    }

    LaunchedEffect(value) {
        if (textFieldValue.text != value) {
            textFieldValue = textFieldValue.copy(
                text = value,
                selection = TextRange(value.length)
            )
        }
    }

    val isImeVisible = WindowInsets.isImeVisible
    LaunchedEffect(isImeVisible) {
        if (!isImeVisible && isEditing) {
            isEditing = false
        }
    }

    LaunchedEffect(isEditing) {
        InputFieldState.isEditing = isEditing
        if (isEditing) {
            try {
                innerFocusRequester.requestFocus()
            } catch (_: Exception) {}
            keyboardController?.show()
        } else {
            keyboardController?.hide()
            if (isInnerFocused) {
                try {
                    outerFocusRequester.requestFocus()
                } catch (_: Exception) {}
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            InputFieldState.isEditing = false
        }
    }

    val fieldModifier = if (label.isEmpty()) {
        modifier
    } else {
        Modifier
            .fillMaxWidth()
            .height(44.dp)
    }

    val visualTransformation = remember(isPassword, predictedSuffix) {
        if (isPassword) PasswordVisualTransformation()
        else if (predictedSuffix.isNotEmpty()) PredictiveTextVisualTransformation(predictedSuffix)
        else VisualTransformation.None
    }

    val content = @Composable {
        Box(
            modifier = fieldModifier
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier.focusRequester(defaultOuterFocusRequester))
                .clip(RoundedCornerShape(8.dp))
                .background(containerColor)
                .border(
                    width = if (isFocused) 1.5.dp else 1.dp,
                    color = if (isFocused) Color(0xFF00BCD4) else Color.Transparent,
                    shape = RoundedCornerShape(8.dp)
                )
                .onFocusChanged { state ->
                    if (!state.isFocused && !state.hasFocus) {
                        if (isEditing) {
                            isEditing = false
                        }
                    }
                }
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.type == PointerEventType.Press) {
                                if (!isEditing) {
                                    HardwareInputManager.notifyMouseUsed()
                                    isEditing = true
                                }
                            }
                        }
                    }
                }
                .onPreviewKeyEvent { keyEvent ->
                    if (isEditing) return@onPreviewKeyEvent false

                    val nativeEvent = keyEvent.nativeKeyEvent
                    val keyCode = nativeEvent.keyCode
                    val isEnterKey = keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
                    val isDpadCenter = keyCode == KeyEvent.KEYCODE_DPAD_CENTER

                    if (isEnterKey || isDpadCenter) {
                        if (nativeEvent.action == KeyEvent.ACTION_DOWN) {
                            return@onPreviewKeyEvent true
                        } else if (nativeEvent.action == KeyEvent.ACTION_UP) {
                            val isHwKeyboard = HardwareInputManager.isHardwareKeyboardActive(context)
                            if (isEnterKey && isHwKeyboard && value.isNotBlank()) {
                                onAction()
                            } else {
                                isEditing = true
                            }
                            return@onPreviewKeyEvent true
                        }
                    }

                    val unicodeChar = nativeEvent.getUnicodeChar(nativeEvent.metaState)
                    val isPrintable = unicodeChar != 0 && !Character.isISOControl(unicodeChar)

                    if (isPrintable) {
                        if (nativeEvent.action == KeyEvent.ACTION_DOWN) {
                            val newText = value + unicodeChar.toChar()
                            onValueChange(newText)
                            textFieldValue = TextFieldValue(text = newText, selection = TextRange(newText.length))
                            isEditing = true
                        }
                        return@onPreviewKeyEvent true
                    }

                    if (keyCode == KeyEvent.KEYCODE_DEL) {
                        if (nativeEvent.action == KeyEvent.ACTION_DOWN) {
                            if (value.isNotEmpty()) {
                                val newText = value.dropLast(1)
                                onValueChange(newText)
                                textFieldValue = TextFieldValue(text = newText, selection = TextRange(newText.length))
                            }
                            isEditing = true
                        }
                        return@onPreviewKeyEvent true
                    }

                    false
                }
                .clickable(
                    interactionSource = outerInteractionSource,
                    indication = null,
                    onClick = {
                        isEditing = true
                    }
                )
                .focusable(
                    interactionSource = outerInteractionSource
                )
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            BasicTextField(
                value = textFieldValue,
                onValueChange = { newTfv ->
                    textFieldValue = newTfv
                    if (newTfv.text != value) {
                        onValueChange(newTfv.text)
                    }
                },
                readOnly = !isEditing,
                textStyle = TextStyle(
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Normal,
                    platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false)
                ),
                cursorBrush = if (isEditing) SolidColor(Color(0xFF00BCD4)) else SolidColor(Color.Transparent),
                visualTransformation = visualTransformation,
                keyboardOptions = KeyboardOptions(imeAction = imeAction),
                keyboardActions = KeyboardActions(onAny = {
                    isEditing = false
                    keyboardController?.hide()
                    try {
                        outerFocusRequester.requestFocus()
                    } catch (_: Exception) {}
                    onAction()
                }),
                singleLine = true,
                interactionSource = innerInteractionSource,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(innerFocusRequester)
                    .focusProperties {
                        canFocus = isEditing
                    }
                    .onPreviewKeyEvent { keyEvent ->
                        if (!isEditing) return@onPreviewKeyEvent false

                        val nativeEvent = keyEvent.nativeKeyEvent
                        val keyCode = nativeEvent.keyCode
                        val isEnterKey = keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
                        val isDpadCenter = keyCode == KeyEvent.KEYCODE_DPAD_CENTER

                        if (keyCode == KeyEvent.KEYCODE_BACK) {
                            if (nativeEvent.action == KeyEvent.ACTION_DOWN) {
                                return@onPreviewKeyEvent true
                            } else if (nativeEvent.action == KeyEvent.ACTION_UP) {
                                isEditing = false
                                keyboardController?.hide()
                                try {
                                    outerFocusRequester.requestFocus()
                                } catch (_: Exception) {}
                                return@onPreviewKeyEvent true
                            }
                        }

                        if (isEnterKey || isDpadCenter) {
                            if (nativeEvent.action == KeyEvent.ACTION_DOWN) {
                                return@onPreviewKeyEvent true
                            } else if (nativeEvent.action == KeyEvent.ACTION_UP) {
                                isEditing = false
                                keyboardController?.hide()
                                try {
                                    outerFocusRequester.requestFocus()
                                } catch (_: Exception) {}
                                onAction()
                                return@onPreviewKeyEvent true
                            }
                        }

                        if (keyCode == KeyEvent.KEYCODE_TAB && predictedSuffix.isNotEmpty()) {
                            if (nativeEvent.action == KeyEvent.ACTION_DOWN) {
                                return@onPreviewKeyEvent true
                            } else if (nativeEvent.action == KeyEvent.ACTION_UP) {
                                onAcceptPrediction?.invoke()
                                return@onPreviewKeyEvent true
                            }
                        }

                        val isCursorAtEnd = textFieldValue.selection.collapsed && textFieldValue.selection.max >= textFieldValue.text.length
                        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && predictedSuffix.isNotEmpty()) {
                            if (isCursorAtEnd) {
                                if (nativeEvent.action == KeyEvent.ACTION_DOWN) {
                                    isAcceptingRight = true
                                    return@onPreviewKeyEvent true
                                } else if (nativeEvent.action == KeyEvent.ACTION_UP) {
                                    if (isAcceptingRight) {
                                        isAcceptingRight = false
                                        onAcceptPrediction?.invoke()
                                        return@onPreviewKeyEvent true
                                    }
                                }
                            } else if (nativeEvent.action == KeyEvent.ACTION_UP) {
                                isAcceptingRight = false
                            }
                        }

                        if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                            if (nativeEvent.action == KeyEvent.ACTION_DOWN) {
                                return@onPreviewKeyEvent true
                            } else if (nativeEvent.action == KeyEvent.ACTION_UP) {
                                isEditing = false
                                keyboardController?.hide()
                                try {
                                    outerFocusRequester.requestFocus()
                                } catch (_: Exception) {}
                                val direction = if (keyCode == KeyEvent.KEYCODE_DPAD_UP) FocusDirection.Up else FocusDirection.Down
                                focusManager.moveFocus(direction)
                                return@onPreviewKeyEvent true
                            }
                        }

                        false
                    },
                decorationBox = { innerTextField ->
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        if (value.isEmpty()) {
                            Text(
                                text = placeholder,
                                color = Color.Gray,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontSize = 14.sp
                            )
                        }
                        innerTextField()
                    }
                }
            )
        }
    }

    if (label.isNotEmpty()) {
        Column(modifier = modifier.fillMaxWidth()) {
            Text(label, color = Color.Gray, fontSize = 14.sp, modifier = Modifier.padding(bottom = 4.dp))
            content()
        }
    } else {
        content()
    }
}

@Composable
fun TvSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    predictedSuffix: String = "",
    onAcceptPrediction: (() -> Unit)? = null,
    onSearch: () -> Unit
) {
    TvInputField(
        value = value,
        onValueChange = onValueChange,
        placeholder = placeholder,
        modifier = modifier,
        imeAction = ImeAction.Search,
        focusRequester = focusRequester,
        predictedSuffix = predictedSuffix,
        onAcceptPrediction = onAcceptPrediction,
        onAction = onSearch
    )
}