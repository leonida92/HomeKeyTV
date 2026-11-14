package com.homekey.tv

import com.homekey.tv.data.local.*
import com.homekey.tv.data.models.ButtonRemapConfig
import com.homekey.tv.data.models.PinnedAppConfig
import com.homekey.tv.data.models.PinnedEntityConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class FakePreferencesRepository(
    initialServerConfig: ServerConfig = ServerConfig(),
    initialDockConfig: DockConfig = DockConfig(),
    initialThemeConfig: ThemeConfig = ThemeConfig(),
    initialRecentAppsConfig: RecentAppsConfig = RecentAppsConfig()
) : PreferencesRepository {

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
        _serverConfig.update { current ->
            PreferencesDomainOps.normalizeServerConfig(transform(current))
        }
    }

    override suspend fun updateDockConfig(transform: (DockConfig) -> DockConfig) {
        _dockConfig.update { current ->
            PreferencesDomainOps.normalizeDockConfig(transform(current))
        }
    }

    override suspend fun updateThemeConfig(transform: (ThemeConfig) -> ThemeConfig) {
        _themeConfig.update { current ->
            PreferencesDomainOps.normalizeThemeConfig(transform(current))
        }
    }

    override suspend fun updateRecentAppsConfig(transform: (RecentAppsConfig) -> RecentAppsConfig) {
        _recentAppsConfig.update { current ->
            PreferencesDomainOps.normalizeRecentAppsConfig(transform(current))
        }
    }

    override suspend fun setVolumeFixEnabled(enabled: Boolean) {
        _volumeFixEnabled.update { enabled }
    }

    override suspend fun recordRecentApp(packageName: String) {
        val updated = PreferencesDomainOps.recordRecentApp(_recentAppsConfig.value, packageName) ?: return
        _recentAppsConfig.update { updated }
    }

    override suspend fun savePinnedEntities(entities: List<PinnedEntityConfig>) {
        _pinnedEntities.update { PreferencesDomainOps.reindexPinnedEntities(entities) }
    }

    override suspend fun savePinnedApps(apps: List<PinnedAppConfig>) {
        _pinnedApps.update { PreferencesDomainOps.reindexPinnedApps(apps) }
    }

    override suspend fun saveButtonRemaps(remaps: List<ButtonRemapConfig>) {
        _buttonRemaps.update { remaps }
    }

    override suspend fun movePinnedEntity(id: String, delta: Int) {
        if (id.startsWith("app:")) {
            val pkg = id.removePrefix("app:")
            val reordered = PreferencesDomainOps.movePinnedApp(_pinnedApps.value, pkg, delta) ?: return
            savePinnedApps(reordered)
        } else {
            val reordered = PreferencesDomainOps.movePinnedEntity(_pinnedEntities.value, id, delta) ?: return
            savePinnedEntities(reordered)
        }
    }

    override suspend fun pinEntity(entity: PinnedEntityConfig) {
        savePinnedEntities(PreferencesDomainOps.pinEntity(_pinnedEntities.value, entity))
    }

    override suspend fun unpinEntity(entityId: String) {
        savePinnedEntities(PreferencesDomainOps.unpinEntity(_pinnedEntities.value, entityId))
    }

    override suspend fun pinApp(app: PinnedAppConfig) {
        savePinnedApps(PreferencesDomainOps.pinApp(_pinnedApps.value, app))
    }

    override suspend fun unpinApp(packageName: String) {
        savePinnedApps(PreferencesDomainOps.unpinApp(_pinnedApps.value, packageName))
    }

    override suspend fun exportBackupJson(includeToken: Boolean): String {
        return HomeKeyBackupCodec.exportBackupJson(
            serverConfig = _serverConfig.value,
            dockConfig = _dockConfig.value,
            themeConfig = _themeConfig.value,
            recentAppsConfig = _recentAppsConfig.value,
            volumeFixEnabled = _volumeFixEnabled.value,
            pinnedEntities = _pinnedEntities.value,
            pinnedApps = _pinnedApps.value,
            buttonRemaps = _buttonRemaps.value,
            includeToken = includeToken
        )
    }

    override suspend fun restoreFromBackupJson(jsonString: String): Result<Unit> {
        val restored = HomeKeyBackupCodec.decodeBackup(
            jsonString = jsonString,
            currentServerConfig = _serverConfig.value,
            currentRecentAppsConfig = _recentAppsConfig.value
        ).getOrElse { return Result.failure(it) }

        _serverConfig.update { restored.serverConfig }
        _dockConfig.update { restored.dockConfig }
        _recentAppsConfig.update { restored.recentAppsConfig }
        _themeConfig.update { restored.themeConfig }
        _volumeFixEnabled.update { restored.volumeFixEnabled }
        _pinnedEntities.update { restored.pinnedEntities }
        _pinnedApps.update { restored.pinnedApps }
        _buttonRemaps.update { restored.buttonRemaps }
        return Result.success(Unit)
    }
}
