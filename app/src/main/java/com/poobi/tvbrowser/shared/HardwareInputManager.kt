package com.poobi.tvbrowser.shared

import android.content.Context
import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object HardwareInputManager {
    private const val TAG = "HardwareInputManager"
    private const val SETTINGS_FILE = "BrowserSettings"

    const val MODE_AUTO = 0
    const val MODE_ALWAYS = 1
    const val MODE_DISABLED = 2

    private val _lastInputWasMouse = MutableStateFlow(false)
    val lastInputWasMouse: StateFlow<Boolean> = _lastInputWasMouse.asStateFlow()

    private val _isKeyboardConnected = MutableStateFlow(false)
    val isKeyboardConnected: StateFlow<Boolean> = _isKeyboardConnected.asStateFlow()

    private val _isMouseConnected = MutableStateFlow(false)
    val isMouseConnected: StateFlow<Boolean> = _isMouseConnected.asStateFlow()

    private val _isKeyboardActive = MutableStateFlow(false)
    val isKeyboardActive: StateFlow<Boolean> = _isKeyboardActive.asStateFlow()

    private val _isMouseActive = MutableStateFlow(false)
    val isMouseActive: StateFlow<Boolean> = _isMouseActive.asStateFlow()

    private var isInitialized = false

    fun init(context: Context) {
        if (isInitialized) {
            updateConnectedDevices(context)
            return
        }
        isInitialized = true

        val appContext = context.applicationContext
        updateConnectedDevices(appContext)

        try {
            val inputManager = appContext.getSystemService(Context.INPUT_SERVICE) as? InputManager
            inputManager?.registerInputDeviceListener(object : InputManager.InputDeviceListener {
                override fun onInputDeviceAdded(deviceId: Int) {
                    updateConnectedDevices(appContext)
                }

                override fun onInputDeviceRemoved(deviceId: Int) {
                    updateConnectedDevices(appContext)
                }

                override fun onInputDeviceChanged(deviceId: Int) {
                    updateConnectedDevices(appContext)
                }
            }, Handler(Looper.getMainLooper()))
        } catch (e: Exception) {
            Log.e(TAG, "Error registering InputDeviceListener", e)
        }
    }

    fun isAlphaKeyboardDevice(device: InputDevice?): Boolean {
        if (device == null || device.isVirtual) return false
        val sources = device.sources
        if ((sources and InputDevice.SOURCE_KEYBOARD) != InputDevice.SOURCE_KEYBOARD) return false
        val hasKeys = device.hasKeys(KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_ENTER)
        return hasKeys[0] && hasKeys[1] && hasKeys[2]
    }

    fun isMouseDevice(device: InputDevice?): Boolean {
        if (device == null || device.isVirtual) return false
        val sources = device.sources
        return (sources and InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE ||
               (sources and InputDevice.SOURCE_TRACKBALL) == InputDevice.SOURCE_TRACKBALL
    }

    fun updateConnectedDevices(context: Context) {
        var keyboardFound = false
        var mouseFound = false

        try {
            val deviceIds = InputDevice.getDeviceIds()
            for (id in deviceIds) {
                val dev = InputDevice.getDevice(id) ?: continue
                if (isAlphaKeyboardDevice(dev)) {
                    keyboardFound = true
                }
                if (isMouseDevice(dev)) {
                    mouseFound = true
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking devices", e)
        }

        _isKeyboardConnected.value = keyboardFound
        _isMouseConnected.value = mouseFound
        updateActiveStates(context)
    }

    fun updateActiveStates(context: Context) {
        val prefs = context.getSharedPreferences(SETTINGS_FILE, Context.MODE_PRIVATE)
        val kbMode = prefs.getInt("hw_keyboard_mode", MODE_AUTO)
        val mouseMode = prefs.getInt("hw_mouse_mode", MODE_AUTO)

        _isKeyboardActive.value = when (kbMode) {
            MODE_ALWAYS -> true
            MODE_DISABLED -> false
            else -> _isKeyboardConnected.value
        }

        _isMouseActive.value = when (mouseMode) {
            MODE_ALWAYS -> true
            MODE_DISABLED -> false
            else -> _isMouseConnected.value
        }
    }

    fun isHardwareKeyboardActive(context: Context): Boolean {
        updateActiveStates(context)
        return _isKeyboardActive.value
    }

    fun isMouseActive(context: Context): Boolean {
        updateActiveStates(context)
        return _isMouseActive.value
    }

    fun shouldShowCustomCursorForMouse(context: Context): Boolean {
        val prefs = context.getSharedPreferences(SETTINGS_FILE, Context.MODE_PRIVATE)
        return prefs.getBoolean("hw_mouse_show_cursor", false)
    }

    fun notifyMouseUsed() {
        _lastInputWasMouse.value = true
        if (!_isMouseConnected.value) {
            _isMouseConnected.value = true
        }
    }

    fun notifyKeyboardUsed() {
        _lastInputWasMouse.value = false
        if (!_isKeyboardConnected.value) {
            _isKeyboardConnected.value = true
        }
    }
}