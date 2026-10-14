package com.homekey.tv.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.SparseArray
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.homekey.tv.data.api.HAWebSocketClient
import com.homekey.tv.data.local.PreferencesManager
import com.homekey.tv.data.models.ButtonRemapConfig
import com.homekey.tv.data.models.RemapAction
import com.homekey.tv.ui.MainActivity
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

    private val audioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as? AudioManager }
    private var volumeRepeatRunnable: Runnable? = null
    private var lastOpenDockTime = 0L

    // Unboxed primitive key code cache for 0ms sub-millisecond lookup in onKeyEvent
    @Volatile private var globalRemapCache = SparseArray<ButtonRemapConfig>()
    @Volatile private var scopedRemapCache = HashMap<String, SparseArray<ButtonRemapConfig>>()

    // Timing state is tracked per keyCode. The old implementation used single shared fields for all
    // keys, so pressing two different remapped keys in quick succession was misread as a
    // double-press and the first key's single action was dropped.
    private class KeyState {
        var isLongPressTriggered = false
        var lastUpTime = 0L
        var lastDownTime = 0L
        var singleClickRunnable: Runnable? = null
        var longPressRunnable: Runnable? = null
    }

    private val keyStates = HashMap<Int, KeyState>()

    fun updateKeyFilterState() {
        val info = serviceInfo ?: return
        val hasRemaps = globalRemapCache.size() > 0 || scopedRemapCache.isNotEmpty() || (::prefs.isInitialized && prefs.buttonRemaps.value.isNotEmpty())
        val isLearning = _isLearnModeActive.value
        val shouldFilter = hasRemaps || isLearning

        val currentlyFiltering = (info.flags and AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS) != 0
        if (shouldFilter != currentlyFiltering) {
            info.flags = if (shouldFilter) {
                info.flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
            } else {
                info.flags and AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS.inv()
            }
            serviceInfo = info
            Log.d(tag, "updateKeyFilterState: shouldFilter=$shouldFilter (hasRemaps=$hasRemaps, isLearning=$isLearning)")
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(tag, "RemoteButtonRemapService connected and active")
        prefs = PreferencesManager.getInstance(this)
        _isServiceRunning.value = true

        rebuildRemapCache(prefs.buttonRemaps.value)
        updateKeyFilterState()

        serviceScope?.cancel()
        val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        serviceScope = scope

        // Reactively pre-warm or teardown overlay and sync key filtering when button remaps change
        scope.launch {
            prefs.buttonRemaps.collect { remaps ->
                rebuildRemapCache(remaps)
                updateKeyFilterState()
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

        // Reactively update key filtering when Learn Mode toggles
        scope.launch {
            _isLearnModeActive.collect {
                updateKeyFilterState()
            }
        }

        // Reactively cancel volume repeat timer if volume fix is disabled
        scope.launch {
            prefs.volumeFixEnabled.collect { enabled ->
                if (!enabled) {
                    volumeRepeatRunnable?.let { handler.removeCallbacks(it) }
                    volumeRepeatRunnable = null
                }
            }
        }

        // Reactively observe PiP Camera alerts from Home Assistant
        scope.launch {
            HAWebSocketClient.getInstance().pipAlert.collect { alert ->
                if (alert != null) {
                    DockOverlayManager.showPipAlert(this@RemoteButtonRemapService, alert)
                } else {
                    DockOverlayManager.dismissPip()
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
        currentForegroundPackage = null
        serviceScope?.cancel()
        serviceScope = null
        DockOverlayManager.teardown()
        volumeRepeatRunnable?.let { handler.removeCallbacks(it) }
        volumeRepeatRunnable = null
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
        if (event == null) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString() ?: return
            if (pkg != packageName && pkg != "com.android.systemui") {
                currentForegroundPackage = pkg
                if (::prefs.isInitialized && prefs.recentAppsEnabled.value) {
                    try {
                        val pm = packageManager
                        val isLaunchable = pm.getLeanbackLaunchIntentForPackage(pkg) != null ||
                                pm.getLaunchIntentForPackage(pkg) != null
                        if (isLaunchable) {
                            prefs.recordRecentApp(pkg)
                        }
                    } catch (_: Exception) {}
                }
            }
        }
    }

    override fun onInterrupt() {
        Log.d(tag, "RemoteButtonRemapService interrupted")
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode

        // Volume Buttons Fix: On Xiaomi Mi TV and Android 9 (Pie) devices, the OS input filter
        // breaks hardware key repeats for volume buttons and may hijack the volume stream to
        // STREAM_ACCESSIBILITY. When volume keys are pressed and unmapped, pass through with 0ms latency
        // and generate active software repeats so holding volume continuously ramps without filter flapping.
        val isVolumeKey = keyCode in listOf(
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_MUTE,
            KeyEvent.KEYCODE_MUTE
        )
        if (isVolumeKey && !_isLearnModeActive.value) {
            val mapped = findRemapConfig(keyCode)
            val isCustomMapped = mapped != null && !(mapped.singlePressAction?.type == "PASS_THROUGH" && mapped.doublePressAction == null && mapped.longPressAction == null)
            if (!isCustomMapped && ::prefs.isInitialized && prefs.volumeFixEnabled.value) {
                // Software repeat generator for Volume Up/Down on devices where the OS suppresses hold repeats
                if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        if (event.repeatCount > 0) {
                            // Hardware/OS repeat arrived: cancel synthetic repeat to prevent double ramping
                            volumeRepeatRunnable?.let { handler.removeCallbacks(it) }
                            volumeRepeatRunnable = null
                        } else {
                            volumeRepeatRunnable?.let { handler.removeCallbacks(it) }
                            val direction = if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                                AudioManager.ADJUST_RAISE
                            } else {
                                AudioManager.ADJUST_LOWER
                            }
                            val startTime = SystemClock.uptimeMillis()
                            val repeatRunnable = object : Runnable {
                                override fun run() {
                                    if (SystemClock.uptimeMillis() - startTime > 8000L) return
                                    try {
                                        audioManager?.adjustSuggestedStreamVolume(
                                             direction,
                                             AudioManager.USE_DEFAULT_STREAM_TYPE,
                                             AudioManager.FLAG_SHOW_UI
                                         )
                                    } catch (e: Exception) {
                                        Log.w(tag, "Volume adjustment failed", e)
                                    }
                                    handler.postDelayed(this, VOLUME_REPEAT_INTERVAL_MS)
                                }
                            }
                            volumeRepeatRunnable = repeatRunnable
                            handler.postDelayed(repeatRunnable, VOLUME_REPEAT_INITIAL_DELAY_MS)
                        }
                    } else if (event.action == KeyEvent.ACTION_UP) {
                        volumeRepeatRunnable?.let { handler.removeCallbacks(it) }
                        volumeRepeatRunnable = null
                    }
                }

                return false
            }
        }

        // Fast-path bypass: Navigation and system control keys must NEVER be intercepted or delayed
        // when the dock overlay is not open and learn mode is inactive. This guarantees 0ms latency
        // and prevents duplicate/multiplied D-pad inputs in other TV apps and system menus.
        if (!DockOverlayManager.isShowing && !_isLearnModeActive.value && !_isSettingsForeground.value) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER,
                KeyEvent.KEYCODE_BACK,
                KeyEvent.KEYCODE_ESCAPE,
                KeyEvent.KEYCODE_HOME,
                KeyEvent.KEYCODE_APP_SWITCH,
                KeyEvent.KEYCODE_POWER,
                KeyEvent.KEYCODE_SLEEP -> return false
                KeyEvent.KEYCODE_VOLUME_UP,
                KeyEvent.KEYCODE_VOLUME_DOWN,
                KeyEvent.KEYCODE_VOLUME_MUTE,
                KeyEvent.KEYCODE_MUTE -> {
                    // Only bypass unmapped volume keys; custom mapped keys proceed to action dispatch
                    val cfg = findRemapConfig(keyCode)
                    if (cfg == null || (cfg.singlePressAction?.type == "PASS_THROUGH" && cfg.doublePressAction == null && cfg.longPressAction == null)) return false
                }
            }
        }

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

        if (isSingleActionOnly && config.singlePressAction?.type == "PASS_THROUGH") {
            return false
        }

        val state = keyStates.getOrPut(config.keyCode) { KeyState() }

        if (event.action == KeyEvent.ACTION_DOWN) {
            if (event.repeatCount == 0) {
                val now = SystemClock.uptimeMillis()
                val debounceLimit = if (isSingleActionOnly) SINGLE_PRESS_DEBOUNCE_MS else MULTI_PRESS_DEBOUNCE_MS
                val timeSinceLastDown = now - state.lastDownTime
                if (timeSinceLastDown < debounceLimit) {
                    Log.d(tag, "Ignoring bounce down event for keyCode=$keyCode (${timeSinceLastDown}ms < ${debounceLimit}ms)")
                    return true
                }
                state.lastDownTime = now
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

            if (config.doublePressAction != null && timeSinceLastClick in DOUBLE_PRESS_MIN_INTERVAL_MS until DOUBLE_PRESS_TIMEOUT_MS) {
                // Double click detected (same key pressed twice within the valid human window).
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
            "PASS_THROUGH" -> {
                Log.d(tag, "Pass-through action: native event dispatch preserved")
            }

            "OPEN_DOCK" -> {
                val now = SystemClock.uptimeMillis()
                if (now - lastOpenDockTime < OPEN_DOCK_DEBOUNCE_MS) {
                    Log.d(tag, "OPEN_DOCK throttled (${now - lastOpenDockTime}ms < ${OPEN_DOCK_DEBOUNCE_MS}ms)")
                    return
                }
                lastOpenDockTime = now
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

            "SEND_BROADCAST" -> {
                action.target?.let { intentAction ->
                    try {
                        val intent = Intent(intentAction)
                        val extra = action.extra?.ifBlank { null }
                        if (extra != null) {
                            if (extra.contains("/")) {
                                val pkg = extra.substringBefore("/")
                                val cls = extra.substringAfter("/").let { if (it.startsWith(".")) "$pkg$it" else it }
                                intent.component = android.content.ComponentName(pkg, cls)
                            } else {
                                intent.setPackage(extra)
                            }
                        }
                        sendBroadcast(intent)
                        Log.d(tag, "Sent broadcast intent: $intentAction (target=$extra)")
                    } catch (e: Exception) {
                        Log.e(tag, "Failed to send broadcast intent: $intentAction", e)
                    }
                }
            }

            "START_SERVICE" -> {
                action.target?.let { intentAction ->
                    try {
                        val intent = Intent(intentAction)
                        val extra = action.extra?.ifBlank { null }
                        if (extra != null) {
                            if (extra.contains("/")) {
                                val pkg = extra.substringBefore("/")
                                val cls = extra.substringAfter("/").let { if (it.startsWith(".")) "$pkg$it" else it }
                                intent.component = android.content.ComponentName(pkg, cls)
                            } else {
                                intent.setPackage(extra)
                            }
                        }
                        try {
                            startService(intent)
                            Log.d(tag, "Started service intent: $intentAction (target=$extra)")
                        } catch (ise: IllegalStateException) {
                            // On Android 8+, background apps cannot call startService(); fall back to startForegroundService
                            startForegroundService(intent)
                            Log.d(tag, "Started foreground service intent: $intentAction (target=$extra)")
                        }
                    } catch (e: Exception) {
                        Log.e(tag, "Failed to start service intent: $intentAction", e)
                    }
                }
            }
        }
    }

    private fun rebuildRemapCache(remaps: List<ButtonRemapConfig>) {
        val newGlobalCache = SparseArray<ButtonRemapConfig>()
        val newScopedCache = HashMap<String, SparseArray<ButtonRemapConfig>>()

        fun putInArray(array: SparseArray<ButtonRemapConfig>, remap: ButtonRemapConfig) {
            array.put(remap.keyCode, remap)
            if (remap.keyCode == 313) {
                if (array.get(KeyEvent.KEYCODE_PAIRING) == null) {
                    array.put(KeyEvent.KEYCODE_PAIRING, remap)
                }
            } else if (remap.keyCode == KeyEvent.KEYCODE_PAIRING) {
                if (array.get(313) == null) {
                    array.put(313, remap)
                }
            } else if (remap.keyName.contains("Star", ignoreCase = true)) {
                if (array.get(313) == null) array.put(313, remap)
                if (array.get(KeyEvent.KEYCODE_PAIRING) == null) array.put(KeyEvent.KEYCODE_PAIRING, remap)
            }
        }

        for (remap in remaps) {
            val scope = remap.appScope?.trim()?.ifEmpty { null }
            if (scope == null) {
                putInArray(newGlobalCache, remap)
            } else {
                val array = newScopedCache.getOrPut(scope) { SparseArray() }
                putInArray(array, remap)
            }
        }
        globalRemapCache = newGlobalCache
        scopedRemapCache = newScopedCache
    }

    private fun findRemapConfig(keyCode: Int): ButtonRemapConfig? {
        val pkg = currentForegroundPackage
        if (pkg != null) {
            val scoped = scopedRemapCache[pkg]?.get(keyCode)
            if (scoped != null) return scoped
        }
        return globalRemapCache.get(keyCode)
    }

    companion object {
        private const val LONG_PRESS_TIMEOUT_MS = 450L
        private const val DOUBLE_PRESS_TIMEOUT_MS = 280L
        private const val DOUBLE_PRESS_MIN_INTERVAL_MS = 80L
        private const val SINGLE_PRESS_DEBOUNCE_MS = 250L
        private const val MULTI_PRESS_DEBOUNCE_MS = 80L
        private const val OPEN_DOCK_DEBOUNCE_MS = 350L
        private const val VOLUME_REPEAT_INITIAL_DELAY_MS = 350L
        private const val VOLUME_REPEAT_INTERVAL_MS = 90L

        @Volatile
        var currentForegroundPackage: String? = null
            private set

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
            instance?.updateKeyFilterState()
        }

        fun clearLearnedKey() {
            _lastLearnedKeyCode.value = null
        }
    }
}
