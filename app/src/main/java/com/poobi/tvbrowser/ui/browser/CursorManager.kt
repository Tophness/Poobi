package com.poobi.tvbrowser.ui.browser

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowInsets
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalFocusManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class CursorManager(private val activity: ComponentActivity, private val viewModel: BrowserViewModel) {

    private val _cursorX = MutableStateFlow(500f)
    val cursorX: StateFlow<Float> = _cursorX.asStateFlow()

    private val _cursorY = MutableStateFlow(500f)
    val cursorY: StateFlow<Float> = _cursorY.asStateFlow()

    private val _cursorVisible = MutableStateFlow(false)
    val cursorVisible: StateFlow<Boolean> = _cursorVisible.asStateFlow()

    private val _cursorHandStyle = MutableStateFlow(false)
    val cursorHandStyle: StateFlow<Boolean> = _cursorHandStyle.asStateFlow()

    private var cursorVelocityX = 0f
    private var cursorVelocityY = 0f
    private var scrollVelocityY = 0f

    private val MIN_VELOCITY = 0.5f
    private val MAX_CURSOR_VELOCITY = 40f
    private val MAX_SCROLL_VELOCITY = 100f
    private val ACCELERATION = 1.15f
    private val SCROLL_ACCELERATION = 1.08f

    private val keyStates = mutableMapOf<Int, Boolean>()
    private val choreographer = Choreographer.getInstance()
    private var isMovementLoopRunning = false
    private var lastMovementTime = 0L

    var isSelectionMode = false
    var okDownTime = 0L
    private val LONG_PRESS_THRESHOLD = 600L
    var isLongPressing = false

    // Single Handler instance to prevent garbage collection and thread sync bugs!
    private val handler = Handler(Looper.getMainLooper())

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (updateMovement()) {
                choreographer.postFrameCallback(this)
            } else {
                isMovementLoopRunning = false
            }
        }
    }

    private val hideCursorRunnable = Runnable {
        if (viewModel.isBrowsing.value && _cursorVisible.value) {
            _cursorVisible.value = false
        }
    }

    fun wakeCursor() {
        if (viewModel.isBrowsing.value && !viewModel.topBarVisible.value) {
            if (viewModel.navigationModePref.value == 1 || isSelectionMode) {
                _cursorVisible.value = false
                viewModel.initDpadNav()
            } else {
                _cursorVisible.value = true
                handler.removeCallbacks(hideCursorRunnable)
                handler.postDelayed(hideCursorRunnable, 3500)
            }
            checkHover()
        }
    }

    fun handleMovementKey(event: KeyEvent): Boolean {
        if (!viewModel.isBrowsing.value || viewModel.topBarVisible.value || viewModel.currentDialog.value != null) {
            if (event.action == KeyEvent.ACTION_UP) {
                keyStates[event.keyCode] = false
            }
            return false
        }

        val keyCode = event.keyCode
        val isDown = event.action == KeyEvent.ACTION_DOWN

        if (viewModel.navigationModePref.value == 1 || isSelectionMode) {
            if (isDown) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> viewModel.handleDpadNav("up")
                    KeyEvent.KEYCODE_DPAD_DOWN -> viewModel.handleDpadNav("down")
                    KeyEvent.KEYCODE_DPAD_LEFT -> viewModel.handleDpadNav("left")
                    KeyEvent.KEYCODE_DPAD_RIGHT -> viewModel.handleDpadNav("right")
                    else -> return false
                }
            }
            return true
        }

        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN -> {
                keyStates[keyCode] = isDown
                if (isDown) {
                    wakeCursor()
                    if (!isMovementLoopRunning) {
                        isMovementLoopRunning = true
                        lastMovementTime = System.currentTimeMillis()
                        choreographer.postFrameCallback(frameCallback)
                    }
                }
                return true
            }
        }
        return false
    }

    private fun updateMovement(): Boolean {
        if (!viewModel.isBrowsing.value || viewModel.topBarVisible.value || viewModel.currentDialog.value != null || isLongPressing) {
            cursorVelocityX = 0f
            cursorVelocityY = 0f
            scrollVelocityY = 0f
            lastMovementTime = 0L
            return false
        }
        val currentTime = System.currentTimeMillis()
        if (lastMovementTime == 0L) {
            lastMovementTime = currentTime
            return true
        }
        // Frame delta-time calculation against a 60Hz/16.6ms standard
        val dt = (currentTime - lastMovementTime) / 16.6f
        lastMovementTime = currentTime

        var moved = false
        val accel = Math.pow(ACCELERATION.toDouble(), dt.toDouble()).toFloat()

        if (keyStates[KeyEvent.KEYCODE_DPAD_LEFT] == true) {
            cursorVelocityX = if (cursorVelocityX >= 0) -MIN_VELOCITY else (cursorVelocityX * accel).coerceIn(-MAX_CURSOR_VELOCITY, -MIN_VELOCITY)
            moved = true
        } else if (keyStates[KeyEvent.KEYCODE_DPAD_RIGHT] == true) {
            cursorVelocityX = if (cursorVelocityX <= 0) MIN_VELOCITY else (cursorVelocityX * accel).coerceIn(MIN_VELOCITY, MAX_CURSOR_VELOCITY)
            moved = true
        } else {
            cursorVelocityX = 0f
        }

        if (keyStates[KeyEvent.KEYCODE_DPAD_UP] == true) {
            cursorVelocityY = if (cursorVelocityY >= 0) -MIN_VELOCITY else (cursorVelocityY * accel).coerceIn(-MAX_CURSOR_VELOCITY, -MIN_VELOCITY)
            moved = true
        } else if (keyStates[KeyEvent.KEYCODE_DPAD_DOWN] == true) {
            cursorVelocityY = if (cursorVelocityY <= 0) MIN_VELOCITY else (cursorVelocityY * accel).coerceIn(MIN_VELOCITY, MAX_CURSOR_VELOCITY)
            moved = true
        } else {
            cursorVelocityY = 0f
        }

        if (keyStates[KeyEvent.KEYCODE_PAGE_UP] == true) {
            scrollVelocityY = if (scrollVelocityY >= 0) -MIN_VELOCITY * 5f else (scrollVelocityY * SCROLL_ACCELERATION).coerceIn(-MAX_SCROLL_VELOCITY, -MIN_VELOCITY)
            moved = true
        } else if (keyStates[KeyEvent.KEYCODE_PAGE_DOWN] == true) {
            scrollVelocityY = if (scrollVelocityY <= 0) MIN_VELOCITY * 5f else (scrollVelocityY * SCROLL_ACCELERATION).coerceIn(MIN_VELOCITY, MAX_SCROLL_VELOCITY)
            moved = true
        } else {
            scrollVelocityY = 0f
        }

        if (cursorVelocityX != 0f || cursorVelocityY != 0f) {
            moveCursor(cursorVelocityX * dt, cursorVelocityY * dt)
        }

        if (scrollVelocityY != 0f) {
            simulateScroll(scrollVelocityY * dt)
        }

        if (!moved) lastMovementTime = 0L
        return moved
    }

    private fun moveCursor(dx: Float, dy: Float) {
        val displayMetrics = activity.resources.displayMetrics
        val maxX = displayMetrics.widthPixels.toFloat()
        val maxY = displayMetrics.heightPixels.toFloat()

        _cursorX.value = (_cursorX.value + dx).coerceIn(0f, maxX)
        _cursorY.value = (_cursorY.value + dy).coerceIn(0f, maxY)

        if (viewModel.isBrowsing.value) {
            val wv = viewModel.currentWebView
            if (viewModel.scrollTopbarEnabled.value && _cursorY.value <= 0 && (wv?.scrollY ?: 0) == 0 && dy < 0 && !isLongPressing) {
                viewModel.showTopBar()
                _cursorVisible.value = false
            }

            if (_cursorY.value >= maxX - 1f && dy > 0) { 
                wv?.scrollBy(0, 15)
            } else if (_cursorY.value <= 0f && (wv?.scrollY ?: 0) > 0 && dy < 0) {
                wv?.scrollBy(0, -15)
            }
        }
    }

    private fun simulateScroll(dy: Float) {
        val wv = viewModel.currentWebView ?: return
        val downTime = SystemClock.uptimeMillis()
        val eventTime = SystemClock.uptimeMillis()

        val location = IntArray(2)
        wv.getLocationOnScreen(location)
        val relativeX = _cursorX.value - location[0]
        val relativeY = _cursorY.value - location[1]

        val properties = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE })
        val coords = arrayOf(MotionEvent.PointerCoords().apply {
            x = relativeX; y = relativeY; pressure = 1f; size = 1f
            setAxisValue(MotionEvent.AXIS_VSCROLL, -dy / 20f)
        })

        val event = MotionEvent.obtain(downTime, eventTime, MotionEvent.ACTION_SCROLL, 1, properties, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0)
        wv.dispatchGenericMotionEvent(event)
        event.recycle()
    }

    fun simulateClick() {
        val downTime = SystemClock.uptimeMillis()
        val eventTime = SystemClock.uptimeMillis()

        viewModel.currentWebView?.requestFocus()

        val properties = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }
        val coordinates = MotionEvent.PointerCoords().apply { x = _cursorX.value; y = _cursorY.value; pressure = 1.0f; size = 1.0f }

        val downEvent = MotionEvent.obtain(downTime, eventTime, MotionEvent.ACTION_DOWN, 1, arrayOf(properties), arrayOf(coordinates), 0, 0, 1.0f, 1.0f, 0, 0, 0, 0)
        val upEvent = MotionEvent.obtain(downTime, eventTime + 100, MotionEvent.ACTION_UP, 1, arrayOf(properties), arrayOf(coordinates), 0, 0, 1.0f, 1.0f, 0, 0, 0, 0)

        activity.window.superDispatchTouchEvent(downEvent)
        activity.window.superDispatchTouchEvent(upEvent)

        downEvent.recycle()
        upEvent.recycle()
    }

    private fun checkHover() {
        val wv = viewModel.currentWebView ?: return
        val density = activity.resources.displayMetrics.density
        val x = (_cursorX.value / density).toInt()
        val y = (_cursorY.value / density).toInt()

        wv.evaluateJavascript("""
            (function() {
                var el = document.elementFromPoint($x, $y);
                var isLink = false;
                var temp = el;
                while(temp) {
                    if (temp.tagName === 'A' || temp.tagName === 'BUTTON' || (temp.onclick) || temp.getAttribute('role') === 'button') {
                        isLink = true; break;
                    }
                    temp = temp.parentElement;
                }
                return isLink;
            })()
        """.trimIndent()) { result ->
            val isLink = result?.toBoolean() ?: false
            _cursorHandStyle.value = isLink
        }
    }

    fun cleanup() {
        choreographer.removeFrameCallback(frameCallback)
        handler.removeCallbacksAndMessages(null)
    }
}

// --- Smart Focus Modifier for TV-oriented InputBoxes ---
fun Modifier.smartDpadFocus(
    context: android.content.Context,
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

                    if (!isKeyboardOpen) {
                        val direction = when (keyCode) {
                            KeyEvent.KEYCODE_DPAD_UP -> androidx.compose.ui.focus.FocusDirection.Up
                            KeyEvent.KEYCODE_DPAD_DOWN -> androidx.compose.ui.focus.FocusDirection.Down
                            KeyEvent.KEYCODE_DPAD_LEFT -> androidx.compose.ui.focus.FocusDirection.Left
                            KeyEvent.KEYCODE_DPAD_RIGHT -> androidx.compose.ui.focus.FocusDirection.Right
                            else -> null
                        }
                        if (direction != null) {
                            focusManager.moveFocus(direction)
                            return@onPreviewKeyEvent true
                        }
                    }
                }
            }
            false
        }
}