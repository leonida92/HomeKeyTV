package com.homekey.tv.viewmodel

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.homekey.tv.data.api.HomeAssistantRepository
import com.homekey.tv.data.local.DataStorePreferencesRepository
import com.homekey.tv.data.local.PreferencesRepository
import com.homekey.tv.data.models.ConnectionStatus
import com.homekey.tv.data.models.DomainColorPalette
import com.homekey.tv.data.models.HAEntityState
import com.homekey.tv.data.models.PinnedAppConfig
import com.homekey.tv.data.models.PinnedEntityConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class DockItem(
    val id: String, // entityId or "app:pkg"
    val displayName: String,
    val isApp: Boolean = false,
    val packageName: String? = null,
    val entity: HAEntityState? = null,
    val customIcon: String? = null
)

class PanelViewModel(
    application: Application,
    private val prefs: PreferencesRepository,
    private val haRepo: HomeAssistantRepository = HomeAssistantRepository.getInstance()
) : AndroidViewModel(application) {

    constructor(application: Application) : this(
        application,
        DataStorePreferencesRepository.getInstance(application),
        HomeAssistantRepository.getInstance()
    )

    constructor(application: Application, prefs: PreferencesRepository) : this(
        application,
        prefs,
        HomeAssistantRepository.getInstance()
    )

    val connectionStatus: StateFlow<ConnectionStatus> = haRepo.connectionStatus
    val allEntities: StateFlow<Map<String, HAEntityState>> = haRepo.entities
    val errorMessage: StateFlow<String?> = haRepo.errorMessage
    val pinnedConfigs: StateFlow<List<PinnedEntityConfig>> = prefs.pinnedEntities
    val pinnedApps: StateFlow<List<PinnedAppConfig>> = prefs.pinnedApps

    val dockLayout: StateFlow<String> = prefs.dockConfig.map { it.layout }
        .stateIn(viewModelScope, SharingStarted.Eagerly, prefs.dockConfig.value.layout)
    val panelLayout: StateFlow<String> get() = dockLayout
    val popupStyle: StateFlow<String> = prefs.dockConfig.map { it.popupStyle }
        .stateIn(viewModelScope, SharingStarted.Eagerly, prefs.dockConfig.value.popupStyle)
    val cameraPopupStyle: StateFlow<String> = prefs.dockConfig.map { it.cameraPopupStyle }
        .stateIn(viewModelScope, SharingStarted.Eagerly, prefs.dockConfig.value.cameraPopupStyle)
    val cameraCompactSize: StateFlow<String> = prefs.dockConfig.map { it.cameraCompactSize }
        .stateIn(viewModelScope, SharingStarted.Eagerly, prefs.dockConfig.value.cameraCompactSize)

    val recentAppsEnabled: StateFlow<Boolean> = prefs.recentAppsConfig.map { it.enabled }
        .stateIn(viewModelScope, SharingStarted.Eagerly, prefs.recentAppsConfig.value.enabled)
    val recentAppsCount: StateFlow<Int> = prefs.recentAppsConfig.map { it.maxCount }
        .stateIn(viewModelScope, SharingStarted.Eagerly, prefs.recentAppsConfig.value.maxCount)
    val recentApps: StateFlow<List<String>> = prefs.recentAppsConfig.map { it.recentPackages }
        .stateIn(viewModelScope, SharingStarted.Eagerly, prefs.recentAppsConfig.value.recentPackages)

    val serverUrl: StateFlow<String> = haRepo.activeServerUrl
        .map { active -> active.ifBlank { prefs.serverConfig.value.serverUrl } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, haRepo.activeServerUrl.value.ifBlank { prefs.serverConfig.value.serverUrl })
    val accessToken: StateFlow<String> = prefs.serverConfig.map { it.accessToken }
        .stateIn(viewModelScope, SharingStarted.Eagerly, prefs.serverConfig.value.accessToken)
    val haEnabled: StateFlow<Boolean> = prefs.serverConfig.map { it.haEnabled }
        .stateIn(viewModelScope, SharingStarted.Eagerly, prefs.serverConfig.value.haEnabled)
    val themePreset: StateFlow<String> = prefs.themeConfig.map { it.preset }
        .stateIn(viewModelScope, SharingStarted.Eagerly, prefs.themeConfig.value.preset)
    val activePalette: StateFlow<DomainColorPalette> = prefs.themeConfig.map { it.activePalette }
        .stateIn(viewModelScope, SharingStarted.Eagerly, prefs.themeConfig.value.activePalette)

    val isConfigured: StateFlow<Boolean> = prefs.serverConfig.map { it.isConfigured }
        .stateIn(viewModelScope, SharingStarted.Eagerly, prefs.serverConfig.value.isConfigured)

    private val _overlayOpenEpoch = MutableStateFlow(0L)
    val overlayOpenEpoch: StateFlow<Long> = _overlayOpenEpoch.asStateFlow()

    fun notifyOverlayShown() {
        _overlayOpenEpoch.value = android.os.SystemClock.uptimeMillis()
        haRepo.notifyOverlayShown()
    }

    private var lastOpenedDialogEntity: HAEntityState? = null
    private val _activeDialogEntityId = MutableStateFlow<String?>(null)
    val activeDialogEntity: StateFlow<HAEntityState?> = combine(
        _activeDialogEntityId,
        allEntities
    ) { id, entities ->
        if (id == null) null
        else entities[id] ?: (if (lastOpenedDialogEntity?.entityId == id) lastOpenedDialogEntity else null)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _isReorderMode = MutableStateFlow(false)
    val isReorderMode: StateFlow<Boolean> = _isReorderMode.asStateFlow()

    private val _selectedReorderEntityId = MutableStateFlow<String?>(null)
    val selectedReorderEntityId: StateFlow<String?> = _selectedReorderEntityId.asStateFlow()

    val displayEntities: StateFlow<List<DockItem>> = combine(
        allEntities,
        pinnedConfigs,
        pinnedApps,
        haEnabled
    ) { entities, pinned, apps, enabled ->
        buildDockItems(entities, if (enabled) pinned else emptyList(), apps)
    }
        .flowOn(Dispatchers.Default)
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private fun buildDockItems(
        entities: Map<String, HAEntityState>,
        pinned: List<PinnedEntityConfig>,
        apps: List<PinnedAppConfig>
    ): List<DockItem> {
        val result = mutableListOf<DockItem>()

        if (pinned.isNotEmpty() || apps.isNotEmpty()) {
            for (p in pinned.sortedBy { it.order }) {
                val entity = entities[p.entityId] ?: HAEntityState(entityId = p.entityId, state = "loading")
                result.add(
                    DockItem(
                        id = p.entityId,
                        displayName = p.customName ?: entity.friendlyName,
                        isApp = false,
                        entity = entity,
                        customIcon = p.customIcon
                    )
                )
            }
            for (app in apps.sortedBy { it.order }) {
                result.add(
                    DockItem(
                        id = "app:${app.packageName}",
                        displayName = app.appName,
                        isApp = true,
                        packageName = app.packageName,
                        customIcon = "app"
                    )
                )
            }
        }
        return result
    }

    init {
        connectToHomeAssistant()
        viewModelScope.launch {
            haEnabled.collect { enabled ->
                if (enabled) {
                    connectToHomeAssistant()
                } else {
                    closeEntityDialog()
                    haRepo.disconnect()
                }
            }
        }
    }

    fun connectToHomeAssistant() {
        val server = prefs.serverConfig.value
        if (!server.haEnabled) return
        if (server.isConfigured) {
            haRepo.connect(server.serverUrl, server.fallbackServerUrl, server.accessToken)
        }
    }

    fun toggleEntity(entityId: String) {
        haRepo.toggleEntity(entityId)
    }

    fun launchApp(packageName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            prefs.recordRecentApp(packageName)
        }
        val pm = getApplication<Application>().packageManager
        val launchIntent = pm.getLeanbackLaunchIntentForPackage(packageName) ?: pm.getLaunchIntentForPackage(packageName)
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            getApplication<Application>().startActivity(launchIntent)
        }
    }

    /**
     * Handles back navigation hierarchy per ADR 0003:
     * 1. Active entity dialog dismissal
     * 2. Reorder mode exit
     * Returns true if a dialog or reorder mode was closed, false otherwise.
     */
    fun handleBack(): Boolean {
        if (activeDialogEntity.value != null) {
            closeEntityDialog()
            return true
        }
        if (isReorderMode.value) {
            exitReorderMode()
            return true
        }
        return false
    }

    fun openEntityDialog(entity: HAEntityState) {
        lastOpenedDialogEntity = entity
        _activeDialogEntityId.value = entity.entityId
        haRepo.setActiveDialogEntity(entity.entityId)
    }

    fun closeEntityDialog() {
        _activeDialogEntityId.value = null
        lastOpenedDialogEntity = null
        haRepo.setActiveDialogEntity(null)
    }

    fun turnOnEntity(entityId: String) {
        haRepo.turnOn(entityId)
    }

    fun turnOffEntity(entityId: String) {
        haRepo.turnOff(entityId)
    }

    fun setBrightness(entityId: String, brightness: Int) {
        haRepo.setBrightness(entityId, brightness)
    }

    fun setTargetTemperature(entityId: String, temp: Float) {
        haRepo.setTargetTemperature(entityId, temp)
    }

    fun setHvacMode(entityId: String, mode: String) {
        haRepo.setHvacMode(entityId, mode)
    }

    fun setFanMode(entityId: String, fanMode: String) {
        haRepo.setFanMode(entityId, fanMode)
    }

    fun setPresetMode(entityId: String, presetMode: String) {
        haRepo.setPresetMode(entityId, presetMode)
    }

    fun selectMediaSource(entityId: String, source: String) {
        haRepo.selectSource(entityId, source)
    }

    // Extended Domain Controls (ADR 0004)
    fun openCover(entityId: String) = haRepo.openCover(entityId)
    fun closeCover(entityId: String) = haRepo.closeCover(entityId)
    fun stopCover(entityId: String) = haRepo.stopCover(entityId)
    fun setCoverPosition(entityId: String, position: Int) = haRepo.setCoverPosition(entityId, position)
    fun setCoverTiltPosition(entityId: String, tilt: Int) = haRepo.setCoverTiltPosition(entityId, tilt)
    fun setFanPercentage(entityId: String, percentage: Int) = haRepo.setFanPercentage(entityId, percentage)
    fun setFanOscillating(entityId: String, oscillating: Boolean) = haRepo.setFanOscillating(entityId, oscillating)
    fun lock(entityId: String) = haRepo.lock(entityId)
    fun unlock(entityId: String) = haRepo.unlock(entityId)

    fun setMediaVolume(entityId: String, volume: Float) {
        haRepo.setVolume(entityId, volume)
    }

    fun toggleMediaPlayPause(entityId: String) {
        haRepo.mediaPlayPause(entityId)
    }

    fun mediaNext(entityId: String) {
        haRepo.mediaNext(entityId)
    }

    fun mediaPrevious(entityId: String) {
        haRepo.mediaPrevious(entityId)
    }

    fun toggleMediaMute(entityId: String, currentMute: Boolean) {
        haRepo.mediaMute(entityId, !currentMute)
    }

    fun setDockLayout(layout: String) {
        viewModelScope.launch {
            prefs.updateDockConfig { it.copy(layout = layout) }
        }
    }

    fun setPanelLayout(layout: String) = setDockLayout(layout)

    fun toggleReorderMode() {
        if (_isReorderMode.value) {
            _isReorderMode.update { false }
            _selectedReorderEntityId.update { null }
        } else {
            ensurePinnedListInitialized()
            _isReorderMode.update { true }
            _selectedReorderEntityId.update { null }
        }
    }

    fun exitReorderMode() {
        _isReorderMode.update { false }
        _selectedReorderEntityId.update { null }
    }

    fun selectEntityForReorder(id: String) {
        if (_selectedReorderEntityId.value == id) {
            _selectedReorderEntityId.value = null
        } else {
            _selectedReorderEntityId.value = id
        }
    }

    fun moveEntity(id: String, delta: Int) {
        viewModelScope.launch {
            if (!id.startsWith("app:")) {
                ensurePinnedListInitialized()
            }
            prefs.movePinnedEntity(id, delta)
        }
    }

    private fun ensurePinnedListInitialized() {
        if (prefs.pinnedEntities.value.isEmpty()) {
            val currentDisplay = displayEntities.value.filter { !it.isApp && it.entity != null }
            val initial = currentDisplay.mapIndexed { idx, item ->
                PinnedEntityConfig(
                    entityId = item.entity!!.entityId,
                    customName = item.displayName,
                    customCategory = item.entity.domain,
                    customIcon = item.customIcon,
                    order = idx
                )
            }
            if (initial.isNotEmpty()) {
                viewModelScope.launch {
                    prefs.savePinnedEntities(initial)
                }
            }
        }
    }
}
