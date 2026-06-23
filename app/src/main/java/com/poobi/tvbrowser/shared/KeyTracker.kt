package com.poobi.tvbrowser.shared

object KeyTracker {
    var lastKeyCode: Int = -1
    var lastKeyPressTime: Long = 0L

    fun isKeyFresh(): Boolean {
        return (System.currentTimeMillis() - lastKeyPressTime) < 500L
    }
}