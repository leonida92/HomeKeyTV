package com.homeassistant.tv.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.homeassistant.tv.data.api.HAWebSocketClient
import com.homeassistant.tv.data.local.PreferencesManager
import com.homeassistant.tv.data.models.RemapAction
import com.homeassistant.tv.ui.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class RemoteButtonRemapService : AccessibilityService() {

    private val tag = "RemoteButtonRemapService"
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var prefs: PreferencesManager

    // Timing state is tracked per keyCode. The old implementation used single shared fields for all
    // keys, so pressing two different remapped keys in quick succession was misread as a
    // double-press and the first key's single action was dropped.
    private class KeyState {
        var isLongPressTriggered = false
        var lastUpTime = 0L
        var singleClickRunnable: Runnable? = null
        var longPressRunnable: Runnable? = null
    }

    private val keyStates = HashMap<Int, KeyState>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(tag, "RemoteButtonRemapService connected and active")
        prefs = PreferencesManager.getInstance(this)
        _isServiceRunning.value = true
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(tag, "RemoteButtonRemapService destroyed")
        keyStates.values.forEach { state ->
            state.singleClickRunnable?.let { handler.removeCallbacks(it) }
            state.longPressRunnable?.let { handler.removeCallbacks(it) }
        }
        keyStates.clear()
        _isServiceRunning.value = false
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not needed for key filtering
    }

    override fun onInterrupt() {
        Log.d(tag, "RemoteButtonRemapService interrupted")
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode

        // If Settings is open or Learn Mode is active, do not execute actions - pass through to UI
        if (_isSettingsForeground.value || _isLearnModeActive.value) {
            if (_isLearnModeActive.value && event.action == KeyEvent.ACTION_DOWN) {
                val name = KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_")
                _lastLearnedKeyCode.value = Pair(keyCode, name)
                Log.d(tag, "Learned Key: $keyCode ($name)")
                return true
            }
            val isNavKey = keyCode in listOf(
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE
            )
            if (isNavKey) {
                return false // Allow D-pad and Back to control SettingsActivity
            } else {
                return true // Consume app launch buttons (Netflix, YouTube, Star) so OS doesn't close Settings!
            }
        }

        // Check if this key is mapped
        val config = prefs.buttonRemaps.value.find { it.keyCode == keyCode } ?: return false

        val state = keyStates.getOrPut(keyCode) { KeyState() }

        if (event.action == KeyEvent.ACTION_DOWN) {
            if (event.repeatCount == 0) {
                state.isLongPressTriggered = false

                // A second press of the same key cancels the pending single-click (armed because the
                // previous press was within the double-tap window) so it cannot fire mid-hold.
                state.singleClickRunnable?.let { handler.removeCallbacks(it) }
                state.singleClickRunnable = null

                // Schedule long press check if configured
                if (config.longPressAction != null) {
                    state.longPressRunnable?.let { handler.removeCallbacks(it) }
                    val longRunnable = Runnable {
                        if (!state.isLongPressTriggered) {
                            state.isLongPressTriggered = true
                            state.singleClickRunnable?.let { handler.removeCallbacks(it) }
                            state.singleClickRunnable = null
                            state.longPressRunnable = null
                            executeAction(config.longPressAction)
                        }
                    }
                    state.longPressRunnable = longRunnable
                    handler.postDelayed(longRunnable, LONG_PRESS_TIMEOUT_MS)
                }
            }
            return true
        } else if (event.action == KeyEvent.ACTION_UP) {
            state.longPressRunnable?.let { handler.removeCallbacks(it) }
            state.longPressRunnable = null

            if (state.isLongPressTriggered) {
                // Long press already handled
                return true
            }

            // Short release before the long-press threshold: single vs double.
            val now = System.currentTimeMillis()
            val timeSinceLastClick = now - state.lastUpTime

            if (config.doublePressAction != null && timeSinceLastClick < DOUBLE_PRESS_TIMEOUT_MS) {
                // Double click detected (same key pressed twice within the window).
                state.singleClickRunnable?.let { handler.removeCallbacks(it) }
                state.singleClickRunnable = null
                state.lastUpTime = 0L
                executeAction(config.doublePressAction)
            } else {
                state.lastUpTime = now
                if (config.doublePressAction != null) {
                    // Wait for a possible second tap before firing the single action.
                    state.singleClickRunnable?.let { handler.removeCallbacks(it) }
                    val singleRunnable = Runnable {
                        if (config.singlePressAction != null) {
                            executeAction(config.singlePressAction)
                        }
                        state.singleClickRunnable = null
                    }
                    state.singleClickRunnable = singleRunnable
                    handler.postDelayed(singleRunnable, DOUBLE_PRESS_TIMEOUT_MS)
                } else {
                    // No double click configured, trigger single click immediately
                    if (config.singlePressAction != null) {
                        executeAction(config.singlePressAction)
                    }
                }
            }
            return true
        }

        return false
    }

    private fun executeAction(action: RemapAction?) {
        if (action == null) return
        Log.d(tag, "Executing Remap Action: ${action.type} -> ${action.target}")

        when (action.type) {
            "OPEN_DOCK" -> {
                val intent = Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                startActivity(intent)
            }

            "TOGGLE_ENTITY" -> {
                action.target?.let { entityId ->
                    HAWebSocketClient.getInstance().toggleEntity(entityId)
                }
            }

            "CALL_SERVICE" -> {
                action.target?.let { serviceKey ->
                    val domain = serviceKey.substringBefore(".", "homeassistant")
                    val service = serviceKey.substringAfter(".", serviceKey)
                    HAWebSocketClient.getInstance().callService(domain, service)
                }
            }

            "LAUNCH_APP" -> {
                action.target?.let { pkgName ->
                    val launchIntent = packageManager.getLaunchIntentForPackage(pkgName)
                    if (launchIntent != null) {
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(launchIntent)
                    }
                }
            }

            "SYSTEM_SLEEP" -> {
                // The SDK exposes no true "sleep" action to accessibility services. Locking the
                // screen is the closest supported proxy — on Android TV this follows the same
                // path as the power button's sleep/standby. If the device can't lock (e.g. no
                // lock screen configured), fall back to the power menu so the user can pick Sleep.
                val locked = performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
                if (!locked) {
                    performGlobalAction(GLOBAL_ACTION_POWER_DIALOG)
                }
            }

            "SYSTEM_SETTINGS" -> {
                val intent = Intent(android.provider.Settings.ACTION_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(intent)
            }
        }
    }

    companion object {
        private const val LONG_PRESS_TIMEOUT_MS = 450L
        private const val DOUBLE_PRESS_TIMEOUT_MS = 280L

        private val _isServiceRunning = MutableStateFlow(false)
        val isServiceRunning: StateFlow<Boolean> = _isServiceRunning.asStateFlow()

        private val _isSettingsForeground = MutableStateFlow(false)
        val isSettingsForeground: StateFlow<Boolean> = _isSettingsForeground.asStateFlow()

        private val _isLearnModeActive = MutableStateFlow(false)
        val isLearnModeActive: StateFlow<Boolean> = _isLearnModeActive.asStateFlow()

        private val _lastLearnedKeyCode = MutableStateFlow<Pair<Int, String>?>(null)
        val lastLearnedKeyCode: StateFlow<Pair<Int, String>?> = _lastLearnedKeyCode.asStateFlow()

        fun setSettingsForeground(inForeground: Boolean) {
            _isSettingsForeground.value = inForeground
        }

        fun setLearnMode(active: Boolean) {
            _isLearnModeActive.value = active
            if (active) {
                _lastLearnedKeyCode.value = null
            }
        }

        fun clearLearnedKey() {
            _lastLearnedKeyCode.value = null
        }
    }
}
