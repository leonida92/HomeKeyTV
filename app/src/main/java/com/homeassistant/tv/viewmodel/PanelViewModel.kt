package com.homeassistant.tv.viewmodel

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.homeassistant.tv.data.api.HAWebSocketClient
import com.homeassistant.tv.data.local.PreferencesManager
import com.homeassistant.tv.data.models.ConnectionStatus
import com.homeassistant.tv.data.models.HAEntityState
import com.homeassistant.tv.data.models.PinnedAppConfig
import com.homeassistant.tv.data.models.PinnedEntityConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*

data class DockItem(
    val id: String, // entityId or "app:pkg"
    val displayName: String,
    val isApp: Boolean = false,
    val packageName: String? = null,
    val entity: HAEntityState? = null,
    val customIcon: String? = null
)

class PanelViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = PreferencesManager.getInstance(application)
    private val wsClient = HAWebSocketClient.getInstance()

    val connectionStatus: StateFlow<ConnectionStatus> = wsClient.connectionStatus
    val allEntities: StateFlow<Map<String, HAEntityState>> = wsClient.entities
    val errorMessage: StateFlow<String?> = wsClient.errorMessage
    val pinnedConfigs: StateFlow<List<PinnedEntityConfig>> = prefs.pinnedEntities
    val pinnedApps: StateFlow<List<PinnedAppConfig>> = prefs.pinnedApps
    val panelLayout: StateFlow<String> = prefs.panelLayout

    private val _activeDialogEntity = MutableStateFlow<HAEntityState?>(null)
    val activeDialogEntity: StateFlow<HAEntityState?> = _activeDialogEntity.asStateFlow()

    private val _isReorderMode = MutableStateFlow(false)
    val isReorderMode: StateFlow<Boolean> = _isReorderMode.asStateFlow()

    private val _selectedReorderEntityId = MutableStateFlow<String?>(null)
    val selectedReorderEntityId: StateFlow<String?> = _selectedReorderEntityId.asStateFlow()

    // Reactive config flag: changes whenever the server URL/token prefs change (e.g. after the
    // phone-setup server saves a config), so the dock can react without a manual re-check.
    val isConfigured: StateFlow<Boolean> = combine(prefs.serverUrl, prefs.accessToken) { url, token ->
        url.isNotBlank() && token.isNotBlank()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, prefs.isConfigured)

    // Display items: unified list of pinned HA entities and pinned apps.
    // Upstream work (mapping/filtering) runs on Dispatchers.Default and only the *resulting* list
    // crosses to the Main-thread collector. distinctUntilChanged() compares the produced DockItem
    // list, so a state_changed event for an entity that is NOT on the dock no longer recomposes the
    // dock at all — and events for docked entities emit a new list only when that tile actually
    // changed (HAEntityState instances are retained for untouched entries).
    val displayEntities: StateFlow<List<DockItem>> = combine(
        allEntities,
        pinnedConfigs,
        pinnedApps
    ) { entities, pinned, apps ->
        buildDockItems(entities, pinned, apps)
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
        } else {
            // Fallback preview: no pinned entities yet. Sort by name so the same devices always
            // appear in the same order (the previous map iteration order was non-deterministic).
            val supportedDomains = setOf("light", "switch", "input_boolean", "scene", "script", "climate", "media_player")
            val available = entities.values
                .filter { supportedDomains.contains(it.domain) }
                .sortedBy { it.friendlyName.lowercase() }
                .take(12)

            for (entity in available) {
                result.add(
                    DockItem(
                        id = entity.entityId,
                        displayName = entity.friendlyName,
                        isApp = false,
                        entity = entity,
                        customIcon = null
                    )
                )
            }
        }
        return result
    }

    init {
        connectToHomeAssistant()
    }

    fun connectToHomeAssistant() {
        val url = prefs.serverUrl.value
        val token = prefs.accessToken.value
        if (url.isNotBlank() && token.isNotBlank()) {
            wsClient.connect(url, token)
        }
    }

    fun toggleEntity(entityId: String) {
        wsClient.toggleEntity(entityId)
    }

    fun launchApp(packageName: String) {
        val launchIntent = getApplication<Application>().packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            getApplication<Application>().startActivity(launchIntent)
        }
    }

    fun openEntityDialog(entity: HAEntityState) {
        _activeDialogEntity.value = entity
    }

    fun closeEntityDialog() {
        _activeDialogEntity.value = null
    }

    fun setBrightness(entityId: String, brightness: Int) {
        wsClient.setBrightness(entityId, brightness)
    }

    fun setTargetTemperature(entityId: String, temp: Float) {
        wsClient.setTargetTemperature(entityId, temp)
    }

    fun setHvacMode(entityId: String, mode: String) {
        wsClient.setHvacMode(entityId, mode)
    }

    fun setPanelLayout(layout: String) {
        prefs.setPanelLayout(layout)
    }

    fun toggleReorderMode() {
        if (_isReorderMode.value) {
            _isReorderMode.value = false
            _selectedReorderEntityId.value = null
        } else {
            ensurePinnedListInitialized()
            _isReorderMode.value = true
            _selectedReorderEntityId.value = null
        }
    }

    fun exitReorderMode() {
        _isReorderMode.value = false
        _selectedReorderEntityId.value = null
    }

    fun selectEntityForReorder(id: String) {
        if (_selectedReorderEntityId.value == id) {
            _selectedReorderEntityId.value = null
        } else {
            _selectedReorderEntityId.value = id
        }
    }

    fun moveEntity(id: String, delta: Int) {
        if (id.startsWith("app:")) {
            val pkg = id.removePrefix("app:")
            val current = prefs.pinnedApps.value.toMutableList()
            val currentIndex = current.indexOfFirst { it.packageName == pkg }
            if (currentIndex == -1 || current.size <= 1) return

            val targetIndex = (currentIndex + delta).let {
                if (it < 0) current.size - 1
                else if (it >= current.size) 0
                else it
            }
            if (currentIndex != targetIndex) {
                val item = current.removeAt(currentIndex)
                current.add(targetIndex, item)
                prefs.savePinnedApps(current)
            }
        } else {
            ensurePinnedListInitialized()
            val current = prefs.pinnedEntities.value.toMutableList()
            val currentIndex = current.indexOfFirst { it.entityId == id }
            if (currentIndex == -1 || current.size <= 1) return

            val targetIndex = (currentIndex + delta).let {
                if (it < 0) current.size - 1
                else if (it >= current.size) 0
                else it
            }

            if (currentIndex != targetIndex) {
                val item = current.removeAt(currentIndex)
                current.add(targetIndex, item)
                prefs.savePinnedEntities(current)
            }
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
                prefs.savePinnedEntities(initial)
            }
        }
    }
}
