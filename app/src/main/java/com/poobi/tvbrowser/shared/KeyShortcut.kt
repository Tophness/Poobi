package com.poobi.tvbrowser.shared

import android.content.SharedPreferences
import android.view.KeyEvent

data class KeyShortcut(
    val keyCode: Int = KeyEvent.KEYCODE_DPAD_UP,
    val alt: Boolean = true,
    val ctrl: Boolean = false,
    val shift: Boolean = false,
    val meta: Boolean = false
) {
    fun matches(event: KeyEvent): Boolean {
        if (event.keyCode != keyCode) return false
        if (alt != event.isAltPressed) return false
        if (ctrl != event.isCtrlPressed) return false
        if (shift != event.isShiftPressed) return false
        if (meta != event.isMetaPressed) return false
        return true
    }

    fun getDisplayString(): String {
        val parts = mutableListOf<String>()
        if (ctrl) parts.add("Ctrl")
        if (alt) parts.add("Alt")
        if (shift) parts.add("Shift")
        if (meta) parts.add("Meta")
        parts.add(getReadableKeyName(keyCode))
        return parts.joinToString(" + ")
    }

    companion object {
        val DEFAULT = KeyShortcut(keyCode = KeyEvent.KEYCODE_DPAD_UP, alt = true)

        fun isModifierKey(keyCode: Int): Boolean {
            return when (keyCode) {
                KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT,
                KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT,
                KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT,
                KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT -> true
                else -> false
            }
        }

        fun getModifiersString(event: KeyEvent): String {
            val parts = mutableListOf<String>()
            if (event.isCtrlPressed) parts.add("Ctrl")
            if (event.isAltPressed) parts.add("Alt")
            if (event.isShiftPressed) parts.add("Shift")
            if (event.isMetaPressed) parts.add("Meta")
            return if (parts.isNotEmpty()) parts.joinToString(" + ") + " + ..." else "Press keys..."
        }

        fun getReadableKeyName(keyCode: Int): String {
            return when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> "Up Arrow"
                KeyEvent.KEYCODE_DPAD_DOWN -> "Down Arrow"
                KeyEvent.KEYCODE_DPAD_LEFT -> "Left Arrow"
                KeyEvent.KEYCODE_DPAD_RIGHT -> "Right Arrow"
                KeyEvent.KEYCODE_DPAD_CENTER -> "D-pad Center"
                KeyEvent.KEYCODE_ENTER -> "Enter"
                KeyEvent.KEYCODE_NUMPAD_ENTER -> "Numpad Enter"
                KeyEvent.KEYCODE_SPACE -> "Space"
                KeyEvent.KEYCODE_ESCAPE -> "Esc"
                KeyEvent.KEYCODE_TAB -> "Tab"
                KeyEvent.KEYCODE_BACK -> "Back"
                KeyEvent.KEYCODE_MENU -> "Menu"
                KeyEvent.KEYCODE_DEL -> "Backspace"
                KeyEvent.KEYCODE_FORWARD_DEL -> "Delete"
                else -> {
                    val name = try {
                        KeyEvent.keyCodeToString(keyCode)
                    } catch (_: Exception) {
                        "Key_$keyCode"
                    }
                    name.removePrefix("KEYCODE_")
                        .replace("_", " ")
                        .lowercase()
                        .split(" ")
                        .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
                }
            }
        }

        fun loadFromPrefs(prefs: SharedPreferences): KeyShortcut {
            val keyCode = prefs.getInt("menu_bind_keycode", DEFAULT.keyCode)
            val alt = prefs.getBoolean("menu_bind_alt", DEFAULT.alt)
            val ctrl = prefs.getBoolean("menu_bind_ctrl", DEFAULT.ctrl)
            val shift = prefs.getBoolean("menu_bind_shift", DEFAULT.shift)
            val meta = prefs.getBoolean("menu_bind_meta", DEFAULT.meta)
            return KeyShortcut(keyCode, alt, ctrl, shift, meta)
        }

        fun saveToPrefs(prefs: SharedPreferences, shortcut: KeyShortcut) {
            prefs.edit().apply {
                putInt("menu_bind_keycode", shortcut.keyCode)
                putBoolean("menu_bind_alt", shortcut.alt)
                putBoolean("menu_bind_ctrl", shortcut.ctrl)
                putBoolean("menu_bind_shift", shortcut.shift)
                putBoolean("menu_bind_meta", shortcut.meta)
            }.apply()
        }
    }
}