package com.homeassistant.tv.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.homeassistant.tv.data.api.HAWebSocketClient
import com.homeassistant.tv.data.local.PreferencesManager
import com.homeassistant.tv.data.models.ButtonRemapConfig
import com.homeassistant.tv.data.models.RemapAction
import com.homeassistant.tv.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class RemoteButtonRemapService : AccessibilityService() {

    private val tag = "RemoteButtonRemapService"
    private val handler = Handler(Looper.getMainLooper())
    private var serviceScope: CoroutineScope? = null

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
        instance = this
        Log.d(tag, "RemoteButtonRemapService connected and active")
        prefs = PreferencesManager.getInstance(this)
        _isServiceRunning.value = true

        serviceScope?.cancel()
        val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        serviceScope = scope

        // Reactively pre-warm or teardown overlay when button remaps change
        scope.launch {
            prefs.buttonRemaps.collect { remaps ->
                val hasDockRemap = remaps.any { remap ->
                    remap.singlePressAction?.type == "OPEN_DOCK" ||
                        remap.doublePressAction?.type == "OPEN_DOCK" ||
                        remap.longPressAction?.type == "OPEN_DOCK"
                }
                if (hasDockRemap) {
                    DockOverlayManager.prewarm(this@RemoteButtonRemapService)
                } else if (!DockOverlayManager.isShowing) {
                    DockOverlayManager.teardown()
                }
            }
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        DockOverlayManager.teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(tag, "RemoteButtonRemapService destroyed")
        if (instance == this) {
            instance = null
        }
        serviceScope?.cancel()
        serviceScope = null
        DockOverlayManager.teardown()
        keyStates.values.forEach { state ->
            state.singleClickRunnable?.let { handler.removeCallbacks(it) }
            state.longPressRunnable?.let { handler.removeCallbacks(it) }
        }
        keyStates.clear()
        _isServiceRunning.value = false
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!DockOverlayManager.isShowing) {
            DockOverlayManager.teardown()
            val hasDockRemap = prefs.buttonRemaps.value.any { remap ->
                remap.singlePressAction?.type == "OPEN_DOCK" ||
                    remap.doublePressAction?.type == "OPEN_DOCK" ||
                    remap.longPressAction?.type == "OPEN_DOCK"
            }
            if (hasDockRemap) {
                DockOverlayManager.prewarm(this)
            }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_CRITICAL && !DockOverlayManager.isShowing) {
            Log.d(tag, "Low memory: tearing down idle pre-warmed overlay")
            DockOverlayManager.teardown()
        }
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
            val isSystemOrNavKey = keyCode in listOf(
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE,
                KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_APP_SWITCH,
                KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE,
                KeyEvent.KEYCODE_MUTE, KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_SLEEP
            )
            if (isSystemOrNavKey) {
                return false // Allow D-pad, Back, Home, Volume, and System keys to control TV naturally
            } else {
                return true // Consume app launch buttons (Netflix, YouTube, Star) so OS doesn't close Settings!
            }
        }

        // If Dock Overlay is currently showing, handle Back or pass D-pad through to overlay window
        if (DockOverlayManager.isShowing) {
            val mappedConfig = findRemapConfig(keyCode)
            val isDockToggleKey = mappedConfig?.singlePressAction?.type == "OPEN_DOCK" ||
                    mappedConfig?.doublePressAction?.type == "OPEN_DOCK" ||
                    mappedConfig?.longPressAction?.type == "OPEN_DOCK"

            if (!isDockToggleKey) {
                if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
                    if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) {
                        DockOverlayManager.handleBack()
                    }
                    return true
                }
                if (keyCode == KeyEvent.KEYCODE_HOME) {
                    DockOverlayManager.hide()
                    return false
                }
                val isNavKey = keyCode in listOf(
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_NUMPAD_ENTER
                )
                if (isNavKey) {
                    return false
                }
            }
        }

        // Check if this key is mapped
        val config = findRemapConfig(keyCode) ?: return false

        val isSingleActionOnly = config.singlePressAction != null &&
            config.doublePressAction == null &&
            config.longPressAction == null
        val state = keyStates.getOrPut(config.keyCode) { KeyState() }

        if (event.action == KeyEvent.ACTION_DOWN) {
            if (event.repeatCount == 0) {
                state.isLongPressTriggered = false

                if (isSingleActionOnly) {
                    // Adaptive single-action optimization:
                    // Only a single action is assigned (no double-press or long-press configured).
                    // Fire immediately on physical button down for 0ms input latency!
                    executeAction(config.singlePressAction)
                    return true
                }

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
            if (isSingleActionOnly) {
                // Action was already executed on ACTION_DOWN; consume UP to prevent OS leak
                return true
            }

            state.longPressRunnable?.let { handler.removeCallbacks(it) }
            state.longPressRunnable = null

            if (state.isLongPressTriggered) {
                // Long press already handled
                return true
            }

            // Short release before the long-press threshold: single vs double.
            val now = SystemClock.uptimeMillis()
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
                DockOverlayManager.toggle(this)
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
                // screen is the closest supported proxy on Android 9+ (API 28+). On older versions
                // or if locking is unavailable, fall back to the power dialog.
                val locked = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
                } else {
                    false
                }
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

            "SELECT_SOURCE" -> {
                val entityId = action.target
                val source = action.extra
                if (entityId != null && source != null) {
                    HAWebSocketClient.getInstance().selectSource(entityId, source)
                }
            }
        }
    }

    private fun findRemapConfig(keyCode: Int): ButtonRemapConfig? {
        val remaps = prefs.buttonRemaps.value
        return remaps.find { it.keyCode == keyCode }
            ?: if (keyCode == 313) {
                // KeyCode 313 is KEYCODE_MACRO_1 (Google TV Streamer Star button).
                // Fallback to KEYCODE_PAIRING (225) or "Star" named configuration.
                remaps.find { it.keyCode == KeyEvent.KEYCODE_PAIRING || it.keyName.contains("Star", ignoreCase = true) }
            } else if (keyCode == KeyEvent.KEYCODE_PAIRING) {
                remaps.find { it.keyCode == 313 || it.keyName.contains("Star", ignoreCase = true) }
            } else {
                null
            }
    }

    companion object {
        private const val LONG_PRESS_TIMEOUT_MS = 450L
        private const val DOUBLE_PRESS_TIMEOUT_MS = 280L

        var instance: RemoteButtonRemapService? = null
            private set

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
