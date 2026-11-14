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
import com.homekey.tv.data.models.PinnedAppConfig
import com.homekey.tv.data.models.PinnedEntityConfig
import kotlinx.coroutines.CancellationException
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.util.concurrent.ThreadLocalRandom

private val Context.haDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "ha_tv_settings",
    produceMigrations = { context ->
        listOf(SharedPreferencesMigration(context, DataStorePreferencesRepository.FILE_LEGACY_PREFS))
    }
)

internal data class LegacyTokenMigration(
    val token: String,
    val clear: () -> Unit
)

class DataStorePreferencesRepository internal constructor(
    private val dataStore: DataStore<Preferences>,
    private val tokenCipher: TokenCipher,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    private val legacyTokenMigrator: (() -> LegacyTokenMigration?)? = null,
    private val defaultVolumeFixEnabled: Boolean = Build.VERSION.SDK_INT <= Build.VERSION_CODES.P
) : PreferencesRepository {

    constructor(context: Context) : this(
        dataStore = context.applicationContext.haDataStore,
        tokenCipher = TinkKeystoreCrypto(context.applicationContext),
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
        legacyTokenMigrator = {
            val secure = createSecurePrefs(context.applicationContext)
            val legacyToken = secure?.getString(KEY_ACCESS_TOKEN, null)
            if (!legacyToken.isNullOrEmpty()) {
                LegacyTokenMigration(
                    token = legacyToken,
                    clear = {
                        try {
                            secure.edit().clear().apply()
                        } catch (_: Exception) {}
                    }
                )
            } else {
                null
            }
        }
    )

    private val json = HomeKeyBackupCodec.json
    private val writeMutex = Mutex()

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

    private val _volumeFixEnabled = MutableStateFlow(defaultVolumeFixEnabled)
    override val volumeFixEnabled: StateFlow<Boolean> = _volumeFixEnabled.asStateFlow()

    init {
        // Load initial preferences synchronously on IO to eliminate cold-start latency
        val initialSnapshot = try {
            runBlocking(Dispatchers.IO) {
                val prefs = dataStore.data.first()
                val existingPin = prefs[PreferencesKeys.PAIRING_PIN] ?: ""
                val resolvedPin = if (existingPin.isBlank() || existingPin.length < 6) {
                    val newPin = ThreadLocalRandom.current().nextInt(100000, 1000000).toString()
                    try {
                        dataStore.edit { p ->
                            p[PreferencesKeys.PAIRING_PIN] = newPin
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed persisting initial pairing PIN: ${e.message}")
                    }
                    newPin
                } else {
                    existingPin
                }
                prefs to resolvedPin
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed reading initial DataStore snapshot: ${e.message}")
            null
        }

        if (initialSnapshot != null) {
            val (initialPrefs, pin) = initialSnapshot
            val serverUrl = initialPrefs[PreferencesKeys.SERVER_URL] ?: ""
            val fallbackServerUrl = initialPrefs[PreferencesKeys.FALLBACK_SERVER_URL] ?: ""
            val haEnabled = initialPrefs[PreferencesKeys.HA_ENABLED] ?: true
            val themePreset = initialPrefs[PreferencesKeys.THEME_PRESET] ?: "classic"
            val volumeFix = initialPrefs[PreferencesKeys.VOLUME_FIX_ENABLED] ?: defaultVolumeFixEnabled
            val customColors = loadCustomThemeColors(initialPrefs[PreferencesKeys.CUSTOM_THEME_COLORS])

            _dockConfig.value = PreferencesDomainOps.normalizeDockConfig(
                DockConfig(
                    layout = initialPrefs[PreferencesKeys.PANEL_LAYOUT] ?: DockConfig.LAYOUT_BOTTOM,
                    popupStyle = initialPrefs[PreferencesKeys.POPUP_STYLE] ?: DockConfig.POPUP_STYLE_CENTERED,
                    cameraPopupStyle = initialPrefs[PreferencesKeys.CAMERA_POPUP_STYLE] ?: DockConfig.CAMERA_POPUP_CENTERED,
                    cameraCompactSize = initialPrefs[PreferencesKeys.CAMERA_COMPACT_SIZE] ?: DockConfig.CAMERA_COMPACT_SIZE_MEDIUM
                )
            )
            _themeConfig.value = PreferencesDomainOps.normalizeThemeConfig(
                ThemeConfig(
                    preset = themePreset,
                    customColors = customColors
                )
            )
            _recentAppsConfig.value = PreferencesDomainOps.normalizeRecentAppsConfig(
                RecentAppsConfig(
                    enabled = initialPrefs[PreferencesKeys.RECENT_APPS_ENABLED] ?: false,
                    maxCount = initialPrefs[PreferencesKeys.RECENT_APPS_COUNT] ?: 3,
                    recentPackages = loadRecentApps(initialPrefs[PreferencesKeys.RECENT_APPS_LIST])
                )
            )
            _volumeFixEnabled.value = volumeFix

            _pinnedEntities.value = loadPinnedEntities(initialPrefs[PreferencesKeys.PINNED_ENTITIES])
            _pinnedApps.value = loadPinnedApps(initialPrefs[PreferencesKeys.PINNED_APPS])
            _buttonRemaps.value = loadButtonRemaps(initialPrefs[PreferencesKeys.BUTTON_REMAPS])

            // Resolve and migrate access token
            var token = ""
            val encryptedToken = initialPrefs[PreferencesKeys.ENCRYPTED_ACCESS_TOKEN]
            if (!encryptedToken.isNullOrEmpty()) {
                token = tokenCipher.decrypt(encryptedToken)
            } else {
                val migratedPlain = initialPrefs[PreferencesKeys.ACCESS_TOKEN]
                if (!migratedPlain.isNullOrEmpty()) {
                    token = migratedPlain
                    scope.launch(Dispatchers.IO) {
                        val enc = tokenCipher.encrypt(token)
                        dataStore.edit { p ->
                            p[PreferencesKeys.ENCRYPTED_ACCESS_TOKEN] = enc
                            p.remove(PreferencesKeys.ACCESS_TOKEN)
                        }
                    }
                } else {
                    val legacy = legacyTokenMigrator?.invoke()
                    if (legacy != null) {
                        token = legacy.token
                        scope.launch(Dispatchers.IO) {
                            val enc = tokenCipher.encrypt(token)
                            dataStore.edit { p ->
                                p[PreferencesKeys.ENCRYPTED_ACCESS_TOKEN] = enc
                            }
                            legacy.clear()
                        }
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
            dataStore.data.collect { prefs ->
                val serverUrl = prefs[PreferencesKeys.SERVER_URL] ?: _serverConfig.value.serverUrl
                val fallbackServerUrl = prefs[PreferencesKeys.FALLBACK_SERVER_URL] ?: _serverConfig.value.fallbackServerUrl
                val haEnabled = prefs[PreferencesKeys.HA_ENABLED] ?: _serverConfig.value.haEnabled
                val pin = prefs[PreferencesKeys.PAIRING_PIN] ?: _serverConfig.value.pairingPin
                val encToken = prefs[PreferencesKeys.ENCRYPTED_ACCESS_TOKEN]
                val token = if (PreferencesKeys.ENCRYPTED_ACCESS_TOKEN in prefs) {
                    if (!encToken.isNullOrEmpty()) tokenCipher.decrypt(encToken) else ""
                } else {
                    _serverConfig.value.accessToken
                }

                val newServerConfig = ServerConfig(serverUrl, fallbackServerUrl, token, pin, haEnabled)
                if (newServerConfig != _serverConfig.value) {
                    _serverConfig.update { newServerConfig }
                }

                val newDockConfig = PreferencesDomainOps.normalizeDockConfig(
                    DockConfig(
                        layout = prefs[PreferencesKeys.PANEL_LAYOUT] ?: _dockConfig.value.layout,
                        popupStyle = prefs[PreferencesKeys.POPUP_STYLE] ?: _dockConfig.value.popupStyle,
                        cameraPopupStyle = prefs[PreferencesKeys.CAMERA_POPUP_STYLE] ?: _dockConfig.value.cameraPopupStyle,
                        cameraCompactSize = prefs[PreferencesKeys.CAMERA_COMPACT_SIZE] ?: _dockConfig.value.cameraCompactSize
                    )
                )
                if (newDockConfig != _dockConfig.value) {
                    _dockConfig.update { newDockConfig }
                }

                val newRecent = PreferencesDomainOps.normalizeRecentAppsConfig(
                    RecentAppsConfig(
                        enabled = prefs[PreferencesKeys.RECENT_APPS_ENABLED] ?: _recentAppsConfig.value.enabled,
                        maxCount = prefs[PreferencesKeys.RECENT_APPS_COUNT] ?: _recentAppsConfig.value.maxCount,
                        recentPackages = prefs[PreferencesKeys.RECENT_APPS_LIST]?.let { loadRecentApps(it) } ?: _recentAppsConfig.value.recentPackages
                    )
                )
                if (newRecent != _recentAppsConfig.value) {
                    _recentAppsConfig.update { newRecent }
                }

                val themePreset = prefs[PreferencesKeys.THEME_PRESET] ?: _themeConfig.value.preset
                val customColors = prefs[PreferencesKeys.CUSTOM_THEME_COLORS]?.let { loadCustomThemeColors(it) } ?: _themeConfig.value.customColors
                val newTheme = PreferencesDomainOps.normalizeThemeConfig(
                    ThemeConfig(preset = themePreset, customColors = customColors)
                )
                if (newTheme != _themeConfig.value) {
                    _themeConfig.update { newTheme }
                }

                prefs[PreferencesKeys.VOLUME_FIX_ENABLED]?.let { enabled ->
                    if (enabled != _volumeFixEnabled.value) _volumeFixEnabled.update { enabled }
                }
                prefs[PreferencesKeys.PINNED_ENTITIES]?.let { raw ->
                    val parsed = loadPinnedEntities(raw)
                    if (parsed != _pinnedEntities.value) _pinnedEntities.update { parsed }
                }
                prefs[PreferencesKeys.PINNED_APPS]?.let { raw ->
                    val parsed = loadPinnedApps(raw)
                    if (parsed != _pinnedApps.value) _pinnedApps.update { parsed }
                }
                prefs[PreferencesKeys.BUTTON_REMAPS]?.let { raw ->
                    val parsed = loadButtonRemaps(raw)
                    if (parsed != _buttonRemaps.value) _buttonRemaps.update { parsed }
                }
            }
        }
    }

    override suspend fun updateServerConfig(transform: (ServerConfig) -> ServerConfig) {
        writeMutex.withLock {
            val updated = transform(_serverConfig.value)
            val normalized = PreferencesDomainOps.normalizeServerConfig(updated)

            withContext(Dispatchers.IO) {
                val enc = if (normalized.accessToken.isNotBlank()) tokenCipher.encrypt(normalized.accessToken) else ""
                dataStore.edit { p ->
                    p[PreferencesKeys.SERVER_URL] = normalized.serverUrl
                    p[PreferencesKeys.FALLBACK_SERVER_URL] = normalized.fallbackServerUrl
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
    }

    override suspend fun updateDockConfig(transform: (DockConfig) -> DockConfig) {
        writeMutex.withLock {
            val updated = transform(_dockConfig.value)
            val normalized = PreferencesDomainOps.normalizeDockConfig(updated)
            withContext(Dispatchers.IO) {
                dataStore.edit { p ->
                    p[PreferencesKeys.PANEL_LAYOUT] = normalized.layout
                    p[PreferencesKeys.POPUP_STYLE] = normalized.popupStyle
                    p[PreferencesKeys.CAMERA_POPUP_STYLE] = normalized.cameraPopupStyle
                    p[PreferencesKeys.CAMERA_COMPACT_SIZE] = normalized.cameraCompactSize
                }
            }
            _dockConfig.update { normalized }
        }
    }

    override suspend fun updateThemeConfig(transform: (ThemeConfig) -> ThemeConfig) {
        writeMutex.withLock {
            val updated = transform(_themeConfig.value)
            val normalized = PreferencesDomainOps.normalizeThemeConfig(updated)
            val colorsJson = json.encodeToString(normalized.customColors)
            withContext(Dispatchers.IO) {
                dataStore.edit { p ->
                    p[PreferencesKeys.THEME_PRESET] = normalized.preset
                    p[PreferencesKeys.CUSTOM_THEME_COLORS] = colorsJson
                }
            }
            _themeConfig.update { normalized }
        }
    }

    override suspend fun updateRecentAppsConfig(transform: (RecentAppsConfig) -> RecentAppsConfig) {
        writeMutex.withLock {
            val updated = transform(_recentAppsConfig.value)
            val normalized = PreferencesDomainOps.normalizeRecentAppsConfig(updated)
            val encoded = json.encodeToString(normalized.recentPackages)
            withContext(Dispatchers.IO) {
                dataStore.edit { p ->
                    p[PreferencesKeys.RECENT_APPS_ENABLED] = normalized.enabled
                    p[PreferencesKeys.RECENT_APPS_COUNT] = normalized.maxCount
                    p[PreferencesKeys.RECENT_APPS_LIST] = encoded
                }
            }
            _recentAppsConfig.update { normalized }
        }
    }

    override suspend fun setVolumeFixEnabled(enabled: Boolean) {
        writeMutex.withLock {
            withContext(Dispatchers.IO) {
                dataStore.edit { p ->
                    p[PreferencesKeys.VOLUME_FIX_ENABLED] = enabled
                }
            }
            _volumeFixEnabled.update { enabled }
        }
    }

    override suspend fun recordRecentApp(packageName: String) {
        writeMutex.withLock {
            val updated = PreferencesDomainOps.recordRecentApp(_recentAppsConfig.value, packageName) ?: return
            val encoded = json.encodeToString(updated.recentPackages)
            try {
                withContext(Dispatchers.IO) {
                    dataStore.edit { p ->
                        p[PreferencesKeys.RECENT_APPS_LIST] = encoded
                    }
                }
                _recentAppsConfig.update { updated }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w(TAG, "Failed persisting recent app '$packageName': ${e.message}", e)
            }
        }
    }

    override suspend fun savePinnedEntities(entities: List<PinnedEntityConfig>) {
        writeMutex.withLock {
            savePinnedEntitiesLocked(entities)
        }
    }

    private suspend fun savePinnedEntitiesLocked(entities: List<PinnedEntityConfig>) {
        val indexed = PreferencesDomainOps.reindexPinnedEntities(entities)
        val serialized = json.encodeToString(indexed)
        withContext(Dispatchers.IO) {
            dataStore.edit { p ->
                p[PreferencesKeys.PINNED_ENTITIES] = serialized
            }
        }
        _pinnedEntities.update { indexed }
    }

    override suspend fun savePinnedApps(apps: List<PinnedAppConfig>) {
        writeMutex.withLock {
            savePinnedAppsLocked(apps)
        }
    }

    private suspend fun savePinnedAppsLocked(apps: List<PinnedAppConfig>) {
        val indexed = PreferencesDomainOps.reindexPinnedApps(apps)
        val serialized = json.encodeToString(indexed)
        withContext(Dispatchers.IO) {
            dataStore.edit { p ->
                p[PreferencesKeys.PINNED_APPS] = serialized
            }
        }
        _pinnedApps.update { indexed }
    }

    override suspend fun saveButtonRemaps(remaps: List<ButtonRemapConfig>) {
        writeMutex.withLock {
            val serialized = json.encodeToString(remaps)
            withContext(Dispatchers.IO) {
                dataStore.edit { p ->
                    p[PreferencesKeys.BUTTON_REMAPS] = serialized
                }
            }
            _buttonRemaps.update { remaps }
        }
    }

    override suspend fun movePinnedEntity(id: String, delta: Int) {
        writeMutex.withLock {
            if (id.startsWith("app:")) {
                val pkg = id.removePrefix("app:")
                val reordered = PreferencesDomainOps.movePinnedApp(_pinnedApps.value, pkg, delta) ?: return
                savePinnedAppsLocked(reordered)
            } else {
                val reordered = PreferencesDomainOps.movePinnedEntity(_pinnedEntities.value, id, delta) ?: return
                savePinnedEntitiesLocked(reordered)
            }
        }
    }

    override suspend fun pinEntity(entity: PinnedEntityConfig) {
        writeMutex.withLock {
            savePinnedEntitiesLocked(PreferencesDomainOps.pinEntity(_pinnedEntities.value, entity))
        }
    }

    override suspend fun unpinEntity(entityId: String) {
        writeMutex.withLock {
            savePinnedEntitiesLocked(PreferencesDomainOps.unpinEntity(_pinnedEntities.value, entityId))
        }
    }

    override suspend fun pinApp(app: PinnedAppConfig) {
        writeMutex.withLock {
            savePinnedAppsLocked(PreferencesDomainOps.pinApp(_pinnedApps.value, app))
        }
    }

    override suspend fun unpinApp(packageName: String) {
        writeMutex.withLock {
            savePinnedAppsLocked(PreferencesDomainOps.unpinApp(_pinnedApps.value, packageName))
        }
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

    override suspend fun restoreFromBackupJson(jsonString: String): Result<Unit> = writeMutex.withLock {
        val restored = HomeKeyBackupCodec.decodeBackup(
            jsonString = jsonString,
            currentServerConfig = _serverConfig.value,
            currentRecentAppsConfig = _recentAppsConfig.value
        ).getOrElse { return@withLock Result.failure(it) }

        try {
            val encToken = if ((restored.cleanUrl.isNotBlank() || restored.cleanFallbackUrl.isNotBlank()) &&
                restored.cleanToken.isNotBlank()
            ) {
                withContext(Dispatchers.IO) {
                    tokenCipher.encrypt(restored.cleanToken)
                }
            } else null

            val customColorsJson = json.encodeToString(restored.themeConfig.customColors)
            val pinnedEntitiesJson = json.encodeToString(restored.pinnedEntities)
            val pinnedAppsJson = json.encodeToString(restored.pinnedApps)
            val buttonRemapsJson = json.encodeToString(restored.buttonRemaps)

            withContext(Dispatchers.IO) {
                dataStore.edit { p ->
                    if (restored.cleanUrl.isNotBlank()) {
                        p[PreferencesKeys.SERVER_URL] = restored.cleanUrl
                    }
                    if (restored.cleanFallbackUrl.isNotBlank()) {
                        p[PreferencesKeys.FALLBACK_SERVER_URL] = restored.cleanFallbackUrl
                    }
                    if (encToken != null) {
                        p[PreferencesKeys.ENCRYPTED_ACCESS_TOKEN] = encToken
                        p.remove(PreferencesKeys.ACCESS_TOKEN)
                    }
                    p[PreferencesKeys.PANEL_LAYOUT] = restored.dockConfig.layout
                    p[PreferencesKeys.POPUP_STYLE] = restored.dockConfig.popupStyle
                    p[PreferencesKeys.CAMERA_POPUP_STYLE] = restored.dockConfig.cameraPopupStyle
                    p[PreferencesKeys.CAMERA_COMPACT_SIZE] = restored.dockConfig.cameraCompactSize
                    p[PreferencesKeys.RECENT_APPS_ENABLED] = restored.recentAppsConfig.enabled
                    p[PreferencesKeys.RECENT_APPS_COUNT] = restored.recentAppsConfig.maxCount
                    p[PreferencesKeys.HA_ENABLED] = restored.serverConfig.haEnabled
                    p[PreferencesKeys.VOLUME_FIX_ENABLED] = restored.volumeFixEnabled
                    p[PreferencesKeys.THEME_PRESET] = restored.themeConfig.preset
                    p[PreferencesKeys.CUSTOM_THEME_COLORS] = customColorsJson
                    p[PreferencesKeys.PINNED_ENTITIES] = pinnedEntitiesJson
                    p[PreferencesKeys.PINNED_APPS] = pinnedAppsJson
                    p[PreferencesKeys.BUTTON_REMAPS] = buttonRemapsJson
                }
            }

            _serverConfig.update { restored.serverConfig }
            _dockConfig.update { restored.dockConfig }
            _recentAppsConfig.update { restored.recentAppsConfig }
            _themeConfig.update { restored.themeConfig }
            _volumeFixEnabled.update { restored.volumeFixEnabled }
            _pinnedEntities.update { restored.pinnedEntities }
            _pinnedApps.update { restored.pinnedApps }
            _buttonRemaps.update { restored.buttonRemaps }

            Result.success(Unit)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
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

    private fun loadRecentApps(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            json.decodeFromString<List<String>>(raw)
        } catch (_: Exception) {
            emptyList()
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
    }
}
