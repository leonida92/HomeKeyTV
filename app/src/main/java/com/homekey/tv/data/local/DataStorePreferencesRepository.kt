package com.homekey.tv.data.local

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.homekey.tv.data.models.ButtonRemapConfig
import com.homekey.tv.data.models.DomainColorPalette
import com.homekey.tv.data.models.PinnedAppConfig
import com.homekey.tv.data.models.PinnedEntityConfig
import com.homekey.tv.data.models.ThemePreset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ThreadLocalRandom

private val Context.haDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "ha_tv_settings",
    produceMigrations = { context ->
        listOf(SharedPreferencesMigration(context, DataStorePreferencesRepository.FILE_LEGACY_PREFS))
    }
)

class DataStorePreferencesRepository(context: Context) : PreferencesRepository {
    private val appContext = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _serverConfig = MutableStateFlow(ServerConfig())
    override val serverConfig: StateFlow<ServerConfig> = _serverConfig.asStateFlow()

    private val _dockConfig = MutableStateFlow(DockConfig())
    override val dockConfig: StateFlow<DockConfig> = _dockConfig.asStateFlow()

    private val _themeConfig = MutableStateFlow(ThemeConfig())
    override val themeConfig: StateFlow<ThemeConfig> = _themeConfig.asStateFlow()

    private val _recentAppsConfig = MutableStateFlow(RecentAppsConfig())
    override val recentAppsConfig: StateFlow<RecentAppsConfig> = _recentAppsConfig.asStateFlow()

    private val _pinnedEntities = MutableStateFlow<List<PinnedEntityConfig>>(emptyList())
    override val pinnedEntities: StateFlow<List<PinnedEntityConfig>> = _pinnedEntities.asStateFlow()

    private val _pinnedApps = MutableStateFlow<List<PinnedAppConfig>>(emptyList())
    override val pinnedApps: StateFlow<List<PinnedAppConfig>> = _pinnedApps.asStateFlow()

    private val _buttonRemaps = MutableStateFlow<List<ButtonRemapConfig>>(emptyList())
    override val buttonRemaps: StateFlow<List<ButtonRemapConfig>> = _buttonRemaps.asStateFlow()

    private val _volumeFixEnabled = MutableStateFlow(Build.VERSION.SDK_INT <= Build.VERSION_CODES.P)
    override val volumeFixEnabled: StateFlow<Boolean> = _volumeFixEnabled.asStateFlow()

    init {
        // Load initial preferences synchronously on IO to eliminate cold-start latency
        val initialPrefs = try {
            runBlocking(Dispatchers.IO) { appContext.haDataStore.data.first() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed reading initial DataStore snapshot: ${e.message}")
            null
        }

        if (initialPrefs != null) {
            val serverUrl = initialPrefs[PreferencesKeys.SERVER_URL] ?: ""
            val fallbackServerUrl = initialPrefs[PreferencesKeys.FALLBACK_SERVER_URL] ?: ""
            val haEnabled = initialPrefs[PreferencesKeys.HA_ENABLED] ?: true
            val layout = DockConfig.normalizeLayout(initialPrefs[PreferencesKeys.PANEL_LAYOUT])
            val popupStyle = DockConfig.normalizePopupStyle(initialPrefs[PreferencesKeys.POPUP_STYLE])
            val cameraPopupStyle = DockConfig.normalizeCameraPopupStyle(initialPrefs[PreferencesKeys.CAMERA_POPUP_STYLE])
            val cameraCompactSize = DockConfig.normalizeCameraCompactSize(initialPrefs[PreferencesKeys.CAMERA_COMPACT_SIZE])
            val recentEnabled = initialPrefs[PreferencesKeys.RECENT_APPS_ENABLED] ?: false
            val recentCount = (initialPrefs[PreferencesKeys.RECENT_APPS_COUNT] ?: 3).coerceIn(1, 5)
            val themePreset = initialPrefs[PreferencesKeys.THEME_PRESET] ?: "classic"
            val volumeFix = initialPrefs[PreferencesKeys.VOLUME_FIX_ENABLED] ?: (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P)

            val customColors = loadCustomThemeColors(initialPrefs[PreferencesKeys.CUSTOM_THEME_COLORS])
            val palette = calculateActivePalette(themePreset, customColors)

            _dockConfig.value = DockConfig(
                layout = layout,
                popupStyle = popupStyle,
                cameraPopupStyle = cameraPopupStyle,
                cameraCompactSize = cameraCompactSize
            )
            _themeConfig.value = ThemeConfig(
                preset = themePreset,
                customColors = customColors,
                activePalette = palette
            )
            _recentAppsConfig.value = RecentAppsConfig(
                enabled = recentEnabled,
                maxCount = recentCount,
                recentPackages = loadRecentApps(initialPrefs[PreferencesKeys.RECENT_APPS_LIST])
            )
            _volumeFixEnabled.value = volumeFix

            _pinnedEntities.value = loadPinnedEntities(initialPrefs[PreferencesKeys.PINNED_ENTITIES])
            _pinnedApps.value = loadPinnedApps(initialPrefs[PreferencesKeys.PINNED_APPS])
            _buttonRemaps.value = loadButtonRemaps(initialPrefs[PreferencesKeys.BUTTON_REMAPS])

            // Resolve and migrate access token
            var token = ""
            val encryptedToken = initialPrefs[PreferencesKeys.ENCRYPTED_ACCESS_TOKEN]
            if (!encryptedToken.isNullOrEmpty()) {
                token = TinkKeystoreCrypto.decrypt(appContext, encryptedToken)
            } else {
                val migratedPlain = initialPrefs[PreferencesKeys.ACCESS_TOKEN]
                if (!migratedPlain.isNullOrEmpty()) {
                    token = migratedPlain
                    scope.launch(Dispatchers.IO) {
                        val enc = TinkKeystoreCrypto.encrypt(appContext, token)
                        appContext.haDataStore.edit { p ->
                            p[PreferencesKeys.ENCRYPTED_ACCESS_TOKEN] = enc
                            p.remove(PreferencesKeys.ACCESS_TOKEN)
                        }
                    }
                } else {
                    val secure = createSecurePrefs(appContext)
                    val legacyToken = secure?.getString(KEY_ACCESS_TOKEN, null)
                    if (!legacyToken.isNullOrEmpty()) {
                        token = legacyToken
                        scope.launch(Dispatchers.IO) {
                            val enc = TinkKeystoreCrypto.encrypt(appContext, token)
                            appContext.haDataStore.edit { p ->
                                p[PreferencesKeys.ENCRYPTED_ACCESS_TOKEN] = enc
                            }
                            try {
                                secure.edit().clear().apply()
                            } catch (_: Exception) {}
                        }
                    }
                }
            }

            // Resolve or generate pairing PIN
            var pin = initialPrefs[PreferencesKeys.PAIRING_PIN] ?: ""
            if (pin.isBlank() || pin.length < 6) {
                pin = ThreadLocalRandom.current().nextInt(100000, 1000000).toString()
                val newPin = pin
                scope.launch(Dispatchers.IO) {
                    appContext.haDataStore.edit { p ->
                        p[PreferencesKeys.PAIRING_PIN] = newPin
                    }
                }
            }

            _serverConfig.value = ServerConfig(
                serverUrl = serverUrl,
                fallbackServerUrl = fallbackServerUrl,
                accessToken = token,
                pairingPin = pin,
                haEnabled = haEnabled
            )
        } else {
            val randomPin = ThreadLocalRandom.current().nextInt(100000, 1000000).toString()
            _serverConfig.value = ServerConfig(pairingPin = randomPin)
        }

        // Continuously collect DataStore updates in background
        scope.launch {
            appContext.haDataStore.data.collect { prefs ->
                val serverUrl = prefs[PreferencesKeys.SERVER_URL] ?: _serverConfig.value.serverUrl
                val fallbackServerUrl = prefs[PreferencesKeys.FALLBACK_SERVER_URL] ?: _serverConfig.value.fallbackServerUrl
                val haEnabled = prefs[PreferencesKeys.HA_ENABLED] ?: _serverConfig.value.haEnabled
                val pin = prefs[PreferencesKeys.PAIRING_PIN] ?: _serverConfig.value.pairingPin
                val encToken = prefs[PreferencesKeys.ENCRYPTED_ACCESS_TOKEN]
                val token = if (!encToken.isNullOrEmpty()) {
                    TinkKeystoreCrypto.decrypt(appContext, encToken)
                } else _serverConfig.value.accessToken

                val newServerConfig = ServerConfig(serverUrl, fallbackServerUrl, token, pin, haEnabled)
                if (newServerConfig != _serverConfig.value) {
                    _serverConfig.value = newServerConfig
                }

                val layout = DockConfig.normalizeLayout(prefs[PreferencesKeys.PANEL_LAYOUT] ?: _dockConfig.value.layout)
                val popupStyle = DockConfig.normalizePopupStyle(prefs[PreferencesKeys.POPUP_STYLE] ?: _dockConfig.value.popupStyle)
                val cameraPopupStyle = DockConfig.normalizeCameraPopupStyle(prefs[PreferencesKeys.CAMERA_POPUP_STYLE] ?: _dockConfig.value.cameraPopupStyle)
                val cameraCompactSize = DockConfig.normalizeCameraCompactSize(prefs[PreferencesKeys.CAMERA_COMPACT_SIZE] ?: _dockConfig.value.cameraCompactSize)
                val newDockConfig = DockConfig(layout, popupStyle, cameraPopupStyle, cameraCompactSize)
                if (newDockConfig != _dockConfig.value) {
                    _dockConfig.value = newDockConfig
                }

                val recentEnabled = prefs[PreferencesKeys.RECENT_APPS_ENABLED] ?: _recentAppsConfig.value.enabled
                val recentCount = (prefs[PreferencesKeys.RECENT_APPS_COUNT] ?: _recentAppsConfig.value.maxCount).coerceIn(1, 5)
                val recentList = prefs[PreferencesKeys.RECENT_APPS_LIST]?.let { loadRecentApps(it) } ?: _recentAppsConfig.value.recentPackages
                val newRecent = RecentAppsConfig(recentEnabled, recentCount, recentList)
                if (newRecent != _recentAppsConfig.value) {
                    _recentAppsConfig.value = newRecent
                }

                val themePreset = prefs[PreferencesKeys.THEME_PRESET] ?: _themeConfig.value.preset
                val customColors = prefs[PreferencesKeys.CUSTOM_THEME_COLORS]?.let { loadCustomThemeColors(it) } ?: _themeConfig.value.customColors
                val palette = calculateActivePalette(themePreset, customColors)
                val newTheme = ThemeConfig(themePreset, customColors, palette)
                if (newTheme != _themeConfig.value) {
                    _themeConfig.value = newTheme
                }

                prefs[PreferencesKeys.VOLUME_FIX_ENABLED]?.let { if (it != _volumeFixEnabled.value) _volumeFixEnabled.value = it }
                prefs[PreferencesKeys.PINNED_ENTITIES]?.let { raw ->
                    val parsed = loadPinnedEntities(raw)
                    if (parsed != _pinnedEntities.value) _pinnedEntities.value = parsed
                }
                prefs[PreferencesKeys.PINNED_APPS]?.let { raw ->
                    val parsed = loadPinnedApps(raw)
                    if (parsed != _pinnedApps.value) _pinnedApps.value = parsed
                }
                prefs[PreferencesKeys.BUTTON_REMAPS]?.let { raw ->
                    val parsed = loadButtonRemaps(raw)
                    if (parsed != _buttonRemaps.value) _buttonRemaps.value = parsed
                }
            }
        }
    }

    override suspend fun updateServerConfig(transform: (ServerConfig) -> ServerConfig) {
        val updated = transform(_serverConfig.value)
        val cleanUrl = updated.serverUrl.trim().removeSuffix("/")
        val cleanFallbackUrl = updated.fallbackServerUrl.trim().removeSuffix("/")
        val cleanToken = updated.accessToken.trim()
        val normalized = updated.copy(
            serverUrl = cleanUrl,
            fallbackServerUrl = cleanFallbackUrl,
            accessToken = cleanToken
        )

        withContext(Dispatchers.IO) {
            val enc = if (cleanToken.isNotBlank()) TinkKeystoreCrypto.encrypt(appContext, cleanToken) else ""
            appContext.haDataStore.edit { p ->
                p[PreferencesKeys.SERVER_URL] = cleanUrl
                p[PreferencesKeys.FALLBACK_SERVER_URL] = cleanFallbackUrl
                p[PreferencesKeys.ENCRYPTED_ACCESS_TOKEN] = enc
                p.remove(PreferencesKeys.ACCESS_TOKEN)
                p[PreferencesKeys.HA_ENABLED] = normalized.haEnabled
                if (normalized.pairingPin.isNotBlank()) {
                    p[PreferencesKeys.PAIRING_PIN] = normalized.pairingPin
                }
            }
        }
        _serverConfig.update { normalized }
    }

    override suspend fun updateDockConfig(transform: (DockConfig) -> DockConfig) {
        val updated = transform(_dockConfig.value)
        val normalized = DockConfig(
            layout = DockConfig.normalizeLayout(updated.layout),
            popupStyle = DockConfig.normalizePopupStyle(updated.popupStyle),
            cameraPopupStyle = DockConfig.normalizeCameraPopupStyle(updated.cameraPopupStyle),
            cameraCompactSize = DockConfig.normalizeCameraCompactSize(updated.cameraCompactSize)
        )
        withContext(Dispatchers.IO) {
            appContext.haDataStore.edit { p ->
                p[PreferencesKeys.PANEL_LAYOUT] = normalized.layout
                p[PreferencesKeys.POPUP_STYLE] = normalized.popupStyle
                p[PreferencesKeys.CAMERA_POPUP_STYLE] = normalized.cameraPopupStyle
                p[PreferencesKeys.CAMERA_COMPACT_SIZE] = normalized.cameraCompactSize
            }
        }
        _dockConfig.update { normalized }
    }

    override suspend fun updateThemeConfig(transform: (ThemeConfig) -> ThemeConfig) {
        val updated = transform(_themeConfig.value)
        val normalizedPreset = ThemePreset.fromId(updated.preset).id
        val palette = calculateActivePalette(normalizedPreset, updated.customColors)
        val normalized = ThemeConfig(
            preset = normalizedPreset,
            customColors = updated.customColors,
            activePalette = palette
        )
        val colorsJson = json.encodeToString(normalized.customColors)
        withContext(Dispatchers.IO) {
            appContext.haDataStore.edit { p ->
                p[PreferencesKeys.THEME_PRESET] = normalized.preset
                p[PreferencesKeys.CUSTOM_THEME_COLORS] = colorsJson
            }
        }
        _themeConfig.update { normalized }
    }

    override suspend fun updateRecentAppsConfig(transform: (RecentAppsConfig) -> RecentAppsConfig) {
        val updated = transform(_recentAppsConfig.value)
        val clampedCount = updated.maxCount.coerceIn(1, 5)
        val trimmed = updated.recentPackages.take(clampedCount)
        val normalized = RecentAppsConfig(updated.enabled, clampedCount, trimmed)
        val encoded = json.encodeToString(trimmed)
        withContext(Dispatchers.IO) {
            appContext.haDataStore.edit { p ->
                p[PreferencesKeys.RECENT_APPS_ENABLED] = normalized.enabled
                p[PreferencesKeys.RECENT_APPS_COUNT] = normalized.maxCount
                p[PreferencesKeys.RECENT_APPS_LIST] = encoded
            }
        }
        _recentAppsConfig.update { normalized }
    }

    override suspend fun setVolumeFixEnabled(enabled: Boolean) {
        withContext(Dispatchers.IO) {
            appContext.haDataStore.edit { p ->
                p[PreferencesKeys.VOLUME_FIX_ENABLED] = enabled
            }
        }
        _volumeFixEnabled.update { enabled }
    }

    override suspend fun recordRecentApp(packageName: String) {
        if (!_recentAppsConfig.value.enabled) return
        if (packageName.isBlank()) return
        if (packageName == "com.homekey.tv" ||
            packageName == "com.android.systemui" ||
            packageName == "android" ||
            packageName.startsWith("com.google.android.apps.tv.launcher") ||
            packageName.startsWith("com.google.android.tvlauncher") ||
            packageName.startsWith("com.google.android.tungsten.setupwraith") ||
            packageName.startsWith("com.android.vending") ||
            packageName == "com.google.android.katniss"
        ) {
            return
        }

        val current = _recentAppsConfig.value.recentPackages.toMutableList()
        current.remove(packageName)
        current.add(0, packageName)
        val trimmed = current.take(_recentAppsConfig.value.maxCount)
        val encoded = json.encodeToString(trimmed)
        try {
            withContext(Dispatchers.IO) {
                appContext.haDataStore.edit { p ->
                    p[PreferencesKeys.RECENT_APPS_LIST] = encoded
                }
            }
            _recentAppsConfig.update { it.copy(recentPackages = trimmed) }
        } catch (_: Exception) {}
    }

    override suspend fun savePinnedEntities(entities: List<PinnedEntityConfig>) {
        val indexed = entities.mapIndexed { idx, item -> item.copy(order = idx) }
        val serialized = json.encodeToString(indexed)
        withContext(Dispatchers.IO) {
            appContext.haDataStore.edit { p ->
                p[PreferencesKeys.PINNED_ENTITIES] = serialized
            }
        }
        _pinnedEntities.update { indexed }
    }

    override suspend fun savePinnedApps(apps: List<PinnedAppConfig>) {
        val indexed = apps.mapIndexed { idx, item -> item.copy(order = idx) }
        val serialized = json.encodeToString(indexed)
        withContext(Dispatchers.IO) {
            appContext.haDataStore.edit { p ->
                p[PreferencesKeys.PINNED_APPS] = serialized
            }
        }
        _pinnedApps.update { indexed }
    }

    override suspend fun saveButtonRemaps(remaps: List<ButtonRemapConfig>) {
        val serialized = json.encodeToString(remaps)
        withContext(Dispatchers.IO) {
            appContext.haDataStore.edit { p ->
                p[PreferencesKeys.BUTTON_REMAPS] = serialized
            }
        }
        _buttonRemaps.update { remaps }
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
            fallbackServerUrl = _serverConfig.value.fallbackServerUrl,
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
            val cleanUrl = backup.serverUrl.trim().removeSuffix("/")
            val cleanFallbackUrl = backup.fallbackServerUrl.trim().removeSuffix("/")
            val cleanToken = backup.accessToken?.ifBlank { null } ?: _serverConfig.value.accessToken
            val dockLayout = DockConfig.normalizeLayout(backup.resolvedDockLayout)
            val popupStyle = DockConfig.normalizePopupStyle(backup.popupStyle)
            val cameraPopupStyle = DockConfig.normalizeCameraPopupStyle(backup.cameraPopupStyle)
            val cameraCompactSize = DockConfig.normalizeCameraCompactSize(backup.cameraCompactSize)
            val recentAppsEnabled = backup.recentAppsEnabled
            val recentAppsCount = backup.recentAppsCount.coerceIn(1, 5)
            val haEnabled = backup.haEnabled
            val volumeFixEnabled = backup.volumeFixEnabled
            val themePreset = backup.themePreset
            val customColors = backup.customThemeColors
            val pinnedEntities = backup.pinnedEntities
            val pinnedApps = backup.pinnedApps
            val buttonRemaps = backup.buttonRemaps

            val encToken = if (cleanUrl.isNotBlank() && cleanToken.isNotBlank()) {
                withContext(Dispatchers.IO) {
                    TinkKeystoreCrypto.encrypt(appContext, cleanToken)
                }
            } else null

            val customColorsJson = json.encodeToString(customColors)
            val pinnedEntitiesJson = json.encodeToString(pinnedEntities)
            val pinnedAppsJson = json.encodeToString(pinnedApps)
            val buttonRemapsJson = json.encodeToString(buttonRemaps)

            withContext(Dispatchers.IO) {
                appContext.haDataStore.edit { p ->
                    if (cleanUrl.isNotBlank()) {
                        p[PreferencesKeys.SERVER_URL] = cleanUrl
                    }
                    if (cleanFallbackUrl.isNotBlank()) {
                        p[PreferencesKeys.FALLBACK_SERVER_URL] = cleanFallbackUrl
                    }
                    if (encToken != null) {
                        p[PreferencesKeys.ENCRYPTED_ACCESS_TOKEN] = encToken
                        p.remove(PreferencesKeys.ACCESS_TOKEN)
                    }
                    p[PreferencesKeys.PANEL_LAYOUT] = dockLayout
                    p[PreferencesKeys.POPUP_STYLE] = popupStyle
                    p[PreferencesKeys.CAMERA_POPUP_STYLE] = cameraPopupStyle
                    p[PreferencesKeys.CAMERA_COMPACT_SIZE] = cameraCompactSize
                    p[PreferencesKeys.RECENT_APPS_ENABLED] = recentAppsEnabled
                    p[PreferencesKeys.RECENT_APPS_COUNT] = recentAppsCount
                    p[PreferencesKeys.HA_ENABLED] = haEnabled
                    p[PreferencesKeys.VOLUME_FIX_ENABLED] = volumeFixEnabled
                    p[PreferencesKeys.THEME_PRESET] = themePreset
                    p[PreferencesKeys.CUSTOM_THEME_COLORS] = customColorsJson
                    p[PreferencesKeys.PINNED_ENTITIES] = pinnedEntitiesJson
                    p[PreferencesKeys.PINNED_APPS] = pinnedAppsJson
                    p[PreferencesKeys.BUTTON_REMAPS] = buttonRemapsJson
                }
            }

            _serverConfig.update {
                it.copy(
                    serverUrl = if (cleanUrl.isNotBlank()) cleanUrl else it.serverUrl,
                    fallbackServerUrl = if (cleanFallbackUrl.isNotBlank()) cleanFallbackUrl else it.fallbackServerUrl,
                    accessToken = cleanToken,
                    haEnabled = haEnabled
                )
            }
            _dockConfig.update { DockConfig(dockLayout, popupStyle, cameraPopupStyle, cameraCompactSize) }
            _recentAppsConfig.update { RecentAppsConfig(recentAppsEnabled, recentAppsCount, it.recentPackages) }
            _themeConfig.update { ThemeConfig(themePreset, customColors, calculateActivePalette(themePreset, customColors)) }
            _volumeFixEnabled.update { volumeFixEnabled }
            _pinnedEntities.update { pinnedEntities }
            _pinnedApps.update { pinnedApps }
            _buttonRemaps.update { buttonRemaps }

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun loadPinnedEntities(raw: String?): List<PinnedEntityConfig> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            json.decodeFromString<List<PinnedEntityConfig>>(raw)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun loadPinnedApps(raw: String?): List<PinnedAppConfig> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            json.decodeFromString<List<PinnedAppConfig>>(raw)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun loadButtonRemaps(raw: String?): List<ButtonRemapConfig> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            json.decodeFromString<List<ButtonRemapConfig>>(raw)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun loadCustomThemeColors(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return try {
            json.decodeFromString<Map<String, String>>(raw)
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun calculateActivePalette(presetId: String, customColors: Map<String, String>): DomainColorPalette {
        val preset = ThemePreset.fromId(presetId)
        return if (preset == ThemePreset.CUSTOM) {
            DomainColorPalette.CLASSIC.withOverrides(customColors)
        } else {
            DomainColorPalette.forPreset(preset)
        }
    }

    private fun loadRecentApps(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            json.decodeFromString<List<String>>(raw)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun createSecurePrefs(context: Context): SharedPreferences? {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                FILE_SECURE_PREFS,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed creating EncryptedSharedPreferences fallback", e)
            null
        }
    }

    private object PreferencesKeys {
        val SERVER_URL = stringPreferencesKey("ha_server_url")
        val FALLBACK_SERVER_URL = stringPreferencesKey("ha_fallback_server_url")
        val ACCESS_TOKEN = stringPreferencesKey("ha_access_token")
        val ENCRYPTED_ACCESS_TOKEN = stringPreferencesKey("ha_encrypted_access_token")
        val PANEL_LAYOUT = stringPreferencesKey("ha_panel_layout")
        val POPUP_STYLE = stringPreferencesKey("ha_popup_style")
        val CAMERA_POPUP_STYLE = stringPreferencesKey("ha_camera_popup_style")
        val CAMERA_COMPACT_SIZE = stringPreferencesKey("ha_camera_compact_size")
        val RECENT_APPS_ENABLED = booleanPreferencesKey("ha_recent_apps_enabled")
        val RECENT_APPS_COUNT = intPreferencesKey("ha_recent_apps_count")
        val HA_ENABLED = booleanPreferencesKey("ha_integration_enabled")
        val PAIRING_PIN = stringPreferencesKey("ha_pairing_pin")
        val PINNED_ENTITIES = stringPreferencesKey("ha_pinned_entities")
        val PINNED_APPS = stringPreferencesKey("ha_pinned_apps")
        val BUTTON_REMAPS = stringPreferencesKey("ha_button_remaps")
        val THEME_PRESET = stringPreferencesKey("ha_theme_preset")
        val CUSTOM_THEME_COLORS = stringPreferencesKey("ha_custom_theme_colors")
        val VOLUME_FIX_ENABLED = booleanPreferencesKey("ha_volume_fix_enabled")
        val RECENT_APPS_LIST = stringPreferencesKey("ha_recent_apps_list")
    }

    companion object {
        private const val TAG = "DataStorePreferencesRepo"
        const val FILE_LEGACY_PREFS = "ha_tv_prefs"
        private const val FILE_SECURE_PREFS = "ha_tv_secure_prefs"
        private const val KEY_ACCESS_TOKEN = "ha_access_token"

        @Volatile
        private var instance: PreferencesRepository? = null

        fun getInstance(context: Context): PreferencesRepository {
            return instance ?: synchronized(this) {
                instance ?: DataStorePreferencesRepository(context.applicationContext).also { instance = it }
            }
        }
    }
}
