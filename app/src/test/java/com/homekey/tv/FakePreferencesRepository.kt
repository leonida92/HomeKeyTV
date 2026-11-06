package com.homekey.tv

import com.homekey.tv.data.local.*
import com.homekey.tv.data.models.ButtonRemapConfig
import com.homekey.tv.data.models.PinnedAppConfig
import com.homekey.tv.data.models.PinnedEntityConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class FakePreferencesRepository(
    initialServerConfig: ServerConfig = ServerConfig(),
    initialDockConfig: DockConfig = DockConfig(),
    initialThemeConfig: ThemeConfig = ThemeConfig(),
    initialRecentAppsConfig: RecentAppsConfig = RecentAppsConfig()
) : PreferencesRepository {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

    private val _serverConfig = MutableStateFlow(initialServerConfig)
    override val serverConfig: StateFlow<ServerConfig> = _serverConfig.asStateFlow()

    private val _dockConfig = MutableStateFlow(initialDockConfig)
    override val dockConfig: StateFlow<DockConfig> = _dockConfig.asStateFlow()

    private val _themeConfig = MutableStateFlow(initialThemeConfig)
    override val themeConfig: StateFlow<ThemeConfig> = _themeConfig.asStateFlow()

    private val _recentAppsConfig = MutableStateFlow(initialRecentAppsConfig)
    override val recentAppsConfig: StateFlow<RecentAppsConfig> = _recentAppsConfig.asStateFlow()

    private val _pinnedEntities = MutableStateFlow<List<PinnedEntityConfig>>(emptyList())
    override val pinnedEntities: StateFlow<List<PinnedEntityConfig>> = _pinnedEntities.asStateFlow()

    private val _pinnedApps = MutableStateFlow<List<PinnedAppConfig>>(emptyList())
    override val pinnedApps: StateFlow<List<PinnedAppConfig>> = _pinnedApps.asStateFlow()

    private val _buttonRemaps = MutableStateFlow<List<ButtonRemapConfig>>(emptyList())
    override val buttonRemaps: StateFlow<List<ButtonRemapConfig>> = _buttonRemaps.asStateFlow()

    private val _volumeFixEnabled = MutableStateFlow(false)
    override val volumeFixEnabled: StateFlow<Boolean> = _volumeFixEnabled.asStateFlow()

    override suspend fun updateServerConfig(transform: (ServerConfig) -> ServerConfig) {
        _serverConfig.update(transform)
    }

    override suspend fun updateDockConfig(transform: (DockConfig) -> DockConfig) {
        _dockConfig.update(transform)
    }

    override suspend fun updateThemeConfig(transform: (ThemeConfig) -> ThemeConfig) {
        _themeConfig.update(transform)
    }

    override suspend fun updateRecentAppsConfig(transform: (RecentAppsConfig) -> RecentAppsConfig) {
        _recentAppsConfig.update(transform)
    }

    override suspend fun setVolumeFixEnabled(enabled: Boolean) {
        _volumeFixEnabled.value = enabled
    }

    override suspend fun recordRecentApp(packageName: String) {
        if (!_recentAppsConfig.value.enabled) return
        val current = _recentAppsConfig.value.recentPackages.toMutableList()
        current.remove(packageName)
        current.add(0, packageName)
        val trimmed = current.take(_recentAppsConfig.value.maxCount)
        _recentAppsConfig.update { it.copy(recentPackages = trimmed) }
    }

    override suspend fun savePinnedEntities(entities: List<PinnedEntityConfig>) {
        _pinnedEntities.value = entities.mapIndexed { idx, item -> item.copy(order = idx) }
    }

    override suspend fun savePinnedApps(apps: List<PinnedAppConfig>) {
        _pinnedApps.value = apps.mapIndexed { idx, item -> item.copy(order = idx) }
    }

    override suspend fun saveButtonRemaps(remaps: List<ButtonRemapConfig>) {
        _buttonRemaps.value = remaps
    }

    override suspend fun movePinnedEntity(id: String, delta: Int) {
        if (id.startsWith("app:")) {
            val pkg = id.removePrefix("app:")
            val current = _pinnedApps.value.toMutableList()
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
                savePinnedApps(current)
            }
        } else {
            val current = _pinnedEntities.value.toMutableList()
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
                savePinnedEntities(current)
            }
        }
    }

    override suspend fun pinEntity(entity: PinnedEntityConfig) {
        val current = _pinnedEntities.value.toMutableList()
        current.removeAll { it.entityId == entity.entityId }
        current.add(entity)
        savePinnedEntities(current)
    }

    override suspend fun unpinEntity(entityId: String) {
        val current = _pinnedEntities.value.toMutableList()
        current.removeAll { it.entityId == entityId }
        savePinnedEntities(current)
    }

    override suspend fun pinApp(app: PinnedAppConfig) {
        val current = _pinnedApps.value.toMutableList()
        current.removeAll { it.packageName == app.packageName }
        current.add(app)
        savePinnedApps(current)
    }

    override suspend fun unpinApp(packageName: String) {
        val current = _pinnedApps.value.toMutableList()
        current.removeAll { it.packageName == packageName }
        savePinnedApps(current)
    }

    override suspend fun exportBackupJson(includeToken: Boolean): String {
        val backup = HomeKeyBackup(
            serverUrl = _serverConfig.value.serverUrl,
            accessToken = if (includeToken) _serverConfig.value.accessToken else null,
            dockLayout = _dockConfig.value.layout,
            panelLayout = _dockConfig.value.layout,
            popupStyle = _dockConfig.value.popupStyle,
            cameraPopupStyle = _dockConfig.value.cameraPopupStyle,
            cameraCompactSize = _dockConfig.value.cameraCompactSize,
            recentAppsEnabled = _recentAppsConfig.value.enabled,
            recentAppsCount = _recentAppsConfig.value.maxCount,
            haEnabled = _serverConfig.value.haEnabled,
            themePreset = _themeConfig.value.preset,
            customThemeColors = _themeConfig.value.customColors,
            volumeFixEnabled = _volumeFixEnabled.value,
            pinnedEntities = _pinnedEntities.value,
            pinnedApps = _pinnedApps.value,
            buttonRemaps = _buttonRemaps.value
        )
        return json.encodeToString(backup)
    }

    override suspend fun restoreFromBackupJson(jsonString: String): Result<Unit> {
        return try {
            val backup = json.decodeFromString<HomeKeyBackup>(jsonString)
            _serverConfig.update {
                it.copy(
                    serverUrl = backup.serverUrl,
                    accessToken = backup.accessToken ?: it.accessToken,
                    haEnabled = backup.haEnabled
                )
            }
            _dockConfig.update {
                it.copy(
                    layout = backup.resolvedDockLayout,
                    popupStyle = backup.popupStyle,
                    cameraPopupStyle = backup.cameraPopupStyle,
                    cameraCompactSize = backup.cameraCompactSize
                )
            }
            _recentAppsConfig.update {
                it.copy(
                    enabled = backup.recentAppsEnabled,
                    maxCount = backup.recentAppsCount
                )
            }
            _themeConfig.update {
                it.copy(
                    preset = backup.themePreset,
                    customColors = backup.customThemeColors
                )
            }
            _volumeFixEnabled.value = backup.volumeFixEnabled
            _pinnedEntities.value = backup.pinnedEntities
            _pinnedApps.value = backup.pinnedApps
            _buttonRemaps.value = backup.buttonRemaps
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
