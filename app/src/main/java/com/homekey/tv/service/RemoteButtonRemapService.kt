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
import com.homekey.tv.HATVApplication
import com.homekey.tv.data.api.HomeAssistantRepository
import com.homekey.tv.data.local.DataStorePreferencesRepository
import com.homekey.tv.data.local.PreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class RemoteButtonRemapService : AccessibilityService() {

    private val tag = "RemoteButtonRemapService"
    private val handler = Handler(Looper.getMainLooper())
    private var serviceScope: CoroutineScope? = null

    private lateinit var prefs: PreferencesRepository

    private val audioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as? AudioManager }
    private val dockController: DockOverlayController by lazy { DockOverlayController.getInstance() }
    private val pipController: CameraPipOverlayController by lazy { CameraPipOverlayController.getInstance() }
    private val coordinator: RemoteInputCoordinator by lazy { RemoteInputCoordinator.getInstance() }
    private val actionDispatcher: RemapActionDispatcher by lazy {
        RemapActionDispatcher(
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
        val isLearning = coordinator.isLearnModeActive.value
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
        prefs = (application as? HATVApplication)?.let { HATVApplication.preferencesRepository }
            ?: DataStorePreferencesRepository.getInstance(this)
        coordinator.attachInputEngine(inputEngine)
        coordinator.setServiceRunning(true)

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

        // Reactively sync Settings foreground state (coordinator -> engine)
        scope.launch {
            coordinator.isSettingsForeground.collect { isForeground ->
                inputEngine.setSettingsForeground(isForeground)
            }
        }

        // Reactively sync Learn Mode (coordinator -> engine)
        scope.launch {
            coordinator.isLearnModeActive.collect { isLearning ->
                inputEngine.setLearnMode(isLearning)
                updateKeyFilterState()
            }
        }

        // Reactively sync foreground package (coordinator -> engine)
        scope.launch {
            coordinator.currentForegroundPackage.collect { pkg ->
                inputEngine.setForegroundPackage(pkg)
            }
        }

        // Reactively sync learned key (engine -> coordinator)
        scope.launch {
            inputEngine.learnedKey.collect { learned ->
                coordinator.updateLearnedKey(learned)
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
        coordinator.detachInputEngine()
        dockController.detachService()
        coordinator.setServiceRunning(false)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(tag, "RemoteButtonRemapService destroyed")
        coordinator.setForegroundPackage(null)
        coordinator.detachInputEngine()
        serviceScope?.cancel()
        serviceScope = null
        dockController.detachService()
        coordinator.setServiceRunning(false)
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
                coordinator.setForegroundPackage(pkg)
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
}
