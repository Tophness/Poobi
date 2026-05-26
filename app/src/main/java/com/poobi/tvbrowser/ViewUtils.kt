package com.poobi.tvbrowser

import android.os.Build
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.widget.EditText

object ViewUtils {
    /**
     * Applies focus navigation logic for D-pads on EditTexts.
     * 
     * Behavior:
     * 1. If the On-Screen Keyboard (OSK) is NOT open, D-pad presses immediately jump focus
     *     to the next UI element in that direction.
     * 2. If the OSK IS open (or it's a virtual event), D-pad presses move the text cursor
     *     inside the EditText, and only jump focus when hitting the text boundaries.
     */
    fun applySmartDpadFocus(editText: EditText) {
        editText.setOnKeyListener { v, keyCode, event ->
            if (v is EditText && event.action == KeyEvent.ACTION_DOWN) {
                val isMultiline = (v.inputType and android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0
                
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ||
                    keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {

                    var isKeyboardOpen = false
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        isKeyboardOpen = v.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true
                    }
                    
                    // Hardware remotes typically have deviceId > 0.
                    // Virtual keyboards/IME usually inject events with deviceId <= 0.
                    if (event.deviceId <= 0) {
                        isKeyboardOpen = true
                    }

                    // CASE 1: Keyboard is closed - Always jump focus
                    if (!isKeyboardOpen) {
                        val direction = when (keyCode) {
                            KeyEvent.KEYCODE_DPAD_UP -> View.FOCUS_UP
                            KeyEvent.KEYCODE_DPAD_DOWN -> View.FOCUS_DOWN
                            KeyEvent.KEYCODE_DPAD_LEFT -> View.FOCUS_LEFT
                            KeyEvent.KEYCODE_DPAD_RIGHT -> View.FOCUS_RIGHT
                            else -> -1
                        }
                        if (direction != -1) {
                            return@setOnKeyListener v.focusSearch(direction)?.requestFocus() ?: false
                        }
                    }

                    // CASE 2: Keyboard is open - Move cursor, jump focus only at boundaries
                    when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_LEFT -> {
                            if (v.selectionStart <= 0) {
                                return@setOnKeyListener v.focusSearch(View.FOCUS_LEFT)?.requestFocus() ?: false
                            }
                        }
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            if (v.selectionEnd >= v.text.length) {
                                return@setOnKeyListener v.focusSearch(View.FOCUS_RIGHT)?.requestFocus() ?: false
                            }
                        }
                        KeyEvent.KEYCODE_DPAD_UP -> {
                            if (!isMultiline) {
                                return@setOnKeyListener v.focusSearch(View.FOCUS_UP)?.requestFocus() ?: false
                            }
                        }
                        KeyEvent.KEYCODE_DPAD_DOWN -> {
                            if (!isMultiline) {
                                return@setOnKeyListener v.focusSearch(View.FOCUS_DOWN)?.requestFocus() ?: false
                            }
                        }
                    }
                    
                    // Allow the EditText to handle the event (move cursor) if we haven't jumped focus
                    return@setOnKeyListener false
                }
            }
            false
        }
    }
}
