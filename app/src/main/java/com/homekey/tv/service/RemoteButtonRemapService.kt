package com.homekey.tv.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.homekey.tv.data.api.HomeAssistantRepository
import com.homekey.tv.data.local.DataStorePreferencesRepository
import com.homekey.tv.data.local.PreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class RemoteButtonRemapService : AccessibilityService() {

    private val tag = "RemoteButtonRemapService"
    private val handler = Handler(Looper.getMainLooper())
    private var serviceScope: CoroutineScope? = null

    private lateinit var prefs: PreferencesRepository

    private val audioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as? AudioManager }
    private val dockController: DockOverlayController by lazy { DockOverlayController.getInstance() }
    private val pipController: CameraPipOverlayController by lazy { CameraPipOverlayController.getInstance() }
    private val actionDispatcher: RemapActionDispatcher by lazy {
        RemapActionDispatcher(
            prefs = prefs,
            haRepository = HomeAssistantRepository.getInstance(),
            dockController = dockController
        )
    }

    private val scheduler: TimeScheduler by lazy { AndroidTimeScheduler(handler) }
    private val inputEngine: RemoteInputEngine by lazy {
        DefaultRemoteInputEngine(
            scheduler = scheduler,
            keyNameResolver = { KeyEvent.keyCodeToString(it).removePrefix("KEYCODE_") },
            onVolumeAdjust = { adjustVolume(it) },
            onAction = { actionDispatcher.execute(it, this@RemoteButtonRemapService) }
        )
    }

    private fun adjustVolume(direction: VolumeDirection) {
        val audioDir = when (direction) {
            VolumeDirection.UP -> AudioManager.ADJUST_RAISE
            VolumeDirection.DOWN -> AudioManager.ADJUST_LOWER
        }
        try {
            audioManager?.adjustSuggestedStreamVolume(
                audioDir,
                AudioManager.USE_DEFAULT_STREAM_TYPE,
                AudioManager.FLAG_SHOW_UI
            )
        } catch (e: Exception) {
            Log.w(tag, "Volume adjustment failed", e)
        }
    }

    fun updateKeyFilterState() {
        val info = serviceInfo ?: return
        val hasRemaps = ::prefs.isInitialized && prefs.buttonRemaps.value.isNotEmpty()
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
        dockController.attachService(this)
        Log.d(tag, "RemoteButtonRemapService connected and attached to DockOverlayController")
        prefs = DataStorePreferencesRepository.getInstance(this)
        _isServiceRunning.update { true }

        inputEngine.updateRemaps(prefs.buttonRemaps.value)
        inputEngine.setVolumeFixEnabled(prefs.volumeFixEnabled.value)
        updateKeyFilterState()

        serviceScope?.cancel()
        val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        serviceScope = scope

        // Reactively sync remaps and pre-warm/teardown overlay
        scope.launch {
            prefs.buttonRemaps.collect { remaps ->
                inputEngine.updateRemaps(remaps)
                updateKeyFilterState()
                val hasDockRemap = remaps.any { remap ->
                    remap.singlePressAction?.type == "OPEN_DOCK" ||
                        remap.doublePressAction?.type == "OPEN_DOCK" ||
                        remap.longPressAction?.type == "OPEN_DOCK"
                }
                if (hasDockRemap) {
                    dockController.prewarm()
                } else if (!dockController.isShowing.value) {
                    dockController.teardown()
                }
            }
        }

        // Reactively sync dock showing state
        scope.launch {
            dockController.isShowing.collect { isShowing ->
                inputEngine.setDockShowing(isShowing)
            }
        }

        // Reactively sync Settings foreground state
        scope.launch {
            _isSettingsForeground.collect { isForeground ->
                inputEngine.setSettingsForeground(isForeground)
            }
        }

        // Reactively sync Learn Mode
        scope.launch {
            _isLearnModeActive.collect { isLearning ->
                inputEngine.setLearnMode(isLearning)
                updateKeyFilterState()
            }
        }

        // Reactively sync learned key to companion StateFlow
        scope.launch {
            inputEngine.learnedKey.collect { learned ->
                _lastLearnedKeyCode.update { learned }
            }
        }

        // Reactively sync clearing learned key from companion to inputEngine
        scope.launch {
            _lastLearnedKeyCode.collect { learned ->
                if (learned == null) {
                    inputEngine.clearLearnedKey()
                }
            }
        }

        // Reactively sync volume fix enabled state
        scope.launch {
            prefs.volumeFixEnabled.collect { enabled ->
                inputEngine.setVolumeFixEnabled(enabled)
            }
        }

        // Reactively observe PiP Camera alerts from Home Assistant
        scope.launch {
            HomeAssistantRepository.getInstance().pipAlert.collect { alert ->
                if (alert != null) {
                    pipController.showPipAlert(this@RemoteButtonRemapService, alert)
                } else {
                    pipController.dismissPip()
                }
            }
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        dockController.detachService()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(tag, "RemoteButtonRemapService destroyed")
        currentForegroundPackage = null
        serviceScope?.cancel()
        serviceScope = null
        dockController.detachService()
        _isServiceRunning.update { false }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!dockController.isShowing.value) {
            dockController.teardown()
            val hasDockRemap = prefs.buttonRemaps.value.any { remap ->
                remap.singlePressAction?.type == "OPEN_DOCK" ||
                    remap.doublePressAction?.type == "OPEN_DOCK" ||
                    remap.longPressAction?.type == "OPEN_DOCK"
            }
            if (hasDockRemap) {
                dockController.prewarm()
            }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_CRITICAL && !dockController.isShowing.value) {
            Log.d(tag, "Low memory: tearing down idle pre-warmed overlay")
            dockController.teardown()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString() ?: return
            if (pkg != packageName && pkg != "com.android.systemui") {
                currentForegroundPackage = pkg
                inputEngine.setForegroundPackage(pkg)
                if (::prefs.isInitialized && prefs.recentAppsConfig.value.enabled) {
                    try {
                        val pm = packageManager
                        val isLaunchable = pm.getLeanbackLaunchIntentForPackage(pkg) != null ||
                                pm.getLaunchIntentForPackage(pkg) != null
                        if (isLaunchable) {
                            serviceScope?.launch(Dispatchers.IO) { prefs.recordRecentApp(pkg) }
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
        val decision = inputEngine.onKeyEvent(
            keyCode = event.keyCode,
            action = event.action,
            repeatCount = event.repeatCount
        )

        return when (decision) {
            is InputDecision.PassThrough -> false
            is InputDecision.Consume -> true
            is InputDecision.Execute -> {
                actionDispatcher.execute(decision.action, this@RemoteButtonRemapService)
                true
            }
            is InputDecision.HandleBack -> {
                dockController.handleBack()
                true
            }
            is InputDecision.DismissDock -> {
                dockController.hide()
                false
            }
        }
    }

    companion object {
        @Volatile
        var currentForegroundPackage: String? = null
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
            _isSettingsForeground.update { inForeground }
        }

        fun setLearnMode(active: Boolean) {
            _isLearnModeActive.update { active }
            if (active) {
                _lastLearnedKeyCode.update { null }
            }
        }

        fun clearLearnedKey() {
            _lastLearnedKeyCode.update { null }
        }
    }
}
