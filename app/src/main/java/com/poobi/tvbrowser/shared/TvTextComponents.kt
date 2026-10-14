package com.poobi.tvbrowser.shared

import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.view.WindowInsets
import android.os.Build

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
    onAction: () -> Unit = {}
) {
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    var isReadOnly by remember { mutableStateOf(true) }

    Column(modifier = modifier.fillMaxWidth()) {
        if (label.isNotEmpty()) {
            Text(label, color = Color.Gray, fontSize = 14.sp, modifier = Modifier.padding(bottom = 4.dp))
        }
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text(placeholder, color = Color.Gray) },
            readOnly = isReadOnly,
            visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
            modifier = Modifier
                .fillMaxWidth()
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .onFocusChanged { state ->
                    if (!state.isFocused) {
                        isReadOnly = true
                    }
                }
                .onPreviewKeyEvent { keyEvent ->
                    val nativeEvent = keyEvent.nativeKeyEvent
                    if (nativeEvent.action == KeyEvent.ACTION_DOWN) {
                        val keyCode = nativeEvent.keyCode
                        val isEnterKey = keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
                        val isDpadCenter = keyCode == KeyEvent.KEYCODE_DPAD_CENTER

                        if (isReadOnly) {
                            if (isEnterKey || isDpadCenter) {
                                val isHwKeyboard = HardwareInputManager.isHardwareKeyboardActive(context)
                                if (isEnterKey && isHwKeyboard && value.isNotBlank()) {
                                    onAction()
                                    return@onPreviewKeyEvent true
                                }
                                isReadOnly = false
                                keyboardController?.show()
                                return@onPreviewKeyEvent true
                            } else {
                                // User pressed a character on a physical keyboard: insert it immediately on the first keystroke
                                val unicodeChar = nativeEvent.getUnicodeChar(nativeEvent.metaState)
                                if (unicodeChar != 0 && !Character.isISOControl(unicodeChar)) {
                                    isReadOnly = false
                                    keyboardController?.hide()
                                    onValueChange(value + unicodeChar.toChar())
                                    return@onPreviewKeyEvent true
                                } else if (keyCode == KeyEvent.KEYCODE_DEL) {
                                    isReadOnly = false
                                    keyboardController?.hide()
                                    if (value.isNotEmpty()) {
                                        onValueChange(value.dropLast(1))
                                    }
                                    return@onPreviewKeyEvent true
                                }
                            }
                        } else {
                            if (isEnterKey || isDpadCenter) {
                                isReadOnly = true
                                keyboardController?.hide()
                                onAction()
                                return@onPreviewKeyEvent true
                            }
                        }
                    }
                    false
                }
                .smartDpadFocus(context = context, isReadOnly = isReadOnly) {
                    isReadOnly = true
                    keyboardController?.hide()
                    onAction()
                },
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedContainerColor = containerColor,
                unfocusedContainerColor = containerColor,
                focusedBorderColor = Color(0xFF00BCD4),
                unfocusedBorderColor = Color.Transparent
            ),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = imeAction),
            keyboardActions = KeyboardActions(onAny = { 
                isReadOnly = true
                keyboardController?.hide()
                onAction()
            })
        )
    }
}

@Composable
fun TvSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    onSearch: () -> Unit
) {
    TvInputField(
        value = value,
        onValueChange = onValueChange,
        placeholder = placeholder,
        modifier = modifier,
        imeAction = ImeAction.Search,
        focusRequester = focusRequester,
        onAction = onSearch
    )
}

fun Modifier.smartDpadFocus(
    context: android.content.Context,
    isReadOnly: Boolean = true,
    onImeAction: () -> Unit
): Modifier = composed {
    val focusManager = LocalFocusManager.current
    var isFocused by remember { mutableStateOf(false) }

    this
        .onFocusChanged { isFocused = it.isFocused }
        .onPreviewKeyEvent { keyEvent ->
            val nativeEvent = keyEvent.nativeKeyEvent
            if (nativeEvent.action == KeyEvent.ACTION_DOWN) {
                val keyCode = nativeEvent.keyCode
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
                    keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {

                    var isKeyboardOpen = false
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        val windowInsets = (context as? ComponentActivity)?.window?.decorView?.rootWindowInsets
                        isKeyboardOpen = windowInsets?.isVisible(WindowInsets.Type.ime()) == true
                    }
                    if (nativeEvent.deviceId <= 0) {
                        isKeyboardOpen = true
                    }

                    if (!isKeyboardOpen && isReadOnly) {
                        val direction = when (keyCode) {
                            KeyEvent.KEYCODE_DPAD_UP -> androidx.compose.ui.focus.FocusDirection.Up
                            KeyEvent.KEYCODE_DPAD_DOWN -> androidx.compose.ui.focus.FocusDirection.Down
                            KeyEvent.KEYCODE_DPAD_LEFT -> androidx.compose.ui.focus.FocusDirection.Left
                            KeyEvent.KEYCODE_DPAD_RIGHT -> androidx.compose.ui.focus.FocusDirection.Right
                            else -> null
                        }
                        if (direction != null && focusManager.moveFocus(direction)) {
                            return@onPreviewKeyEvent true
                        }
                    }
                }
            }
            false
        }
}