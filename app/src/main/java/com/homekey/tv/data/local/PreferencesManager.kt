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
import com.homekey.tv.data.api.HAWebSocketClient
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ThreadLocalRandom

private val Context.haDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "ha_tv_settings",
    produceMigrations = { context ->
        listOf(SharedPreferencesMigration(context, PreferencesManager.FILE_LEGACY_PREFS))
    }
)

class PreferencesManager(context: Context) {
    private val appContext = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _serverUrl = MutableStateFlow("")
    val serverUrl: StateFlow<String> = _serverUrl.asStateFlow()

    private val _accessToken = MutableStateFlow("")
    val accessToken: StateFlow<String> = _accessToken.asStateFlow()

    private val _pairingPin = MutableStateFlow("")
    val pairingPin: StateFlow<String> = _pairingPin.asStateFlow()

    private val _pinnedEntities = MutableStateFlow<List<PinnedEntityConfig>>(emptyList())
    val pinnedEntities: StateFlow<List<PinnedEntityConfig>> = _pinnedEntities.asStateFlow()

    private val _pinnedApps = MutableStateFlow<List<PinnedAppConfig>>(emptyList())
    val pinnedApps: StateFlow<List<PinnedAppConfig>> = _pinnedApps.asStateFlow()

    private val _buttonRemaps = MutableStateFlow<List<ButtonRemapConfig>>(emptyList())
    val buttonRemaps: StateFlow<List<ButtonRemapConfig>> = _buttonRemaps.asStateFlow()

    private val _panelLayout = MutableStateFlow("DOCK_BOTTOM")
    val panelLayout: StateFlow<String> = _panelLayout.asStateFlow()

    private val _popupStyle = MutableStateFlow(POPUP_STYLE_CENTERED)
    val popupStyle: StateFlow<String> = _popupStyle.asStateFlow()

    private val _cameraPopupStyle = MutableStateFlow(CAMERA_POPUP_CENTERED)
    val cameraPopupStyle: StateFlow<String> = _cameraPopupStyle.asStateFlow()

    private val _cameraCompactSize = MutableStateFlow(CAMERA_COMPACT_SIZE_MEDIUM)
    val cameraCompactSize: StateFlow<String> = _cameraCompactSize.asStateFlow()

    private val _recentAppsEnabled = MutableStateFlow(false)
    val recentAppsEnabled: StateFlow<Boolean> = _recentAppsEnabled.asStateFlow()

    private val _recentAppsCount = MutableStateFlow(3)
    val recentAppsCount: StateFlow<Int> = _recentAppsCount.asStateFlow()

    private val _recentApps = MutableStateFlow<List<String>>(emptyList())
    val recentApps: StateFlow<List<String>> = _recentApps.asStateFlow()

    private val _haEnabled = MutableStateFlow(true)
    val haEnabled: StateFlow<Boolean> = _haEnabled.asStateFlow()

    private val _themePreset = MutableStateFlow("classic")
    val themePreset: StateFlow<String> = _themePreset.asStateFlow()

    private val _customThemeColors = MutableStateFlow<Map<String, String>>(emptyMap())
    val customThemeColors: StateFlow<Map<String, String>> = _customThemeColors.asStateFlow()

    private val _activePalette = MutableStateFlow(DomainColorPalette.CLASSIC)
    val activePalette: StateFlow<DomainColorPalette> = _activePalette.asStateFlow()

    private val _volumeFixEnabled = MutableStateFlow(Build.VERSION.SDK_INT <= Build.VERSION_CODES.P)
    val volumeFixEnabled: StateFlow<Boolean> = _volumeFixEnabled.asStateFlow()

    val isConfigured: Boolean
        get() = haEnabled.value && serverUrl.value.isNotBlank() && accessToken.value.isNotBlank()

    init {
        // Load initial preferences synchronously on IO to eliminate cold-start lag
        val initialPrefs = try {
            runBlocking(Dispatchers.IO) { appContext.haDataStore.data.first() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed reading initial DataStore snapshot: ${e.message}")
            null
        }

        if (initialPrefs != null) {
            _serverUrl.value = initialPrefs[PreferencesKeys.SERVER_URL] ?: ""
            _panelLayout.value = normalizeLayout(initialPrefs[PreferencesKeys.PANEL_LAYOUT])
            _popupStyle.value = normalizePopupStyle(initialPrefs[PreferencesKeys.POPUP_STYLE])
            _cameraPopupStyle.value = normalizeCameraPopupStyle(initialPrefs[PreferencesKeys.CAMERA_POPUP_STYLE])
            _cameraCompactSize.value = normalizeCameraCompactSize(initialPrefs[PreferencesKeys.CAMERA_COMPACT_SIZE])
            _recentAppsEnabled.value = initialPrefs[PreferencesKeys.RECENT_APPS_ENABLED] ?: false
            _recentAppsCount.value = (initialPrefs[PreferencesKeys.RECENT_APPS_COUNT] ?: 3).coerceIn(1, 5)
            _haEnabled.value = initialPrefs[PreferencesKeys.HA_ENABLED] ?: true
            _themePreset.value = initialPrefs[PreferencesKeys.THEME_PRESET] ?: "classic"
            _volumeFixEnabled.value = initialPrefs[PreferencesKeys.VOLUME_FIX_ENABLED] ?: (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P)

            val customColors = loadCustomThemeColors(initialPrefs[PreferencesKeys.CUSTOM_THEME_COLORS])
            _customThemeColors.value = customColors
            _activePalette.value = calculateActivePalette(_themePreset.value, customColors)

            _pinnedEntities.value = loadPinnedEntities(initialPrefs[PreferencesKeys.PINNED_ENTITIES])
            _pinnedApps.value = loadPinnedApps(initialPrefs[PreferencesKeys.PINNED_APPS])
            _buttonRemaps.value = loadButtonRemaps(initialPrefs[PreferencesKeys.BUTTON_REMAPS])
            _recentApps.value = loadRecentApps(initialPrefs[PreferencesKeys.RECENT_APPS_LIST])

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
                    // Check legacy EncryptedSharedPreferences file
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
            _accessToken.value = token

            // Resolve or generate pairing PIN
            val existingPin = initialPrefs[PreferencesKeys.PAIRING_PIN]
            if (!existingPin.isNullOrBlank() && existingPin.length >= 6) {
                _pairingPin.value = existingPin
            } else {
                val newPin = ThreadLocalRandom.current().nextInt(100000, 1000000).toString()
                _pairingPin.value = newPin
                scope.launch(Dispatchers.IO) {
                    appContext.haDataStore.edit { p ->
                        p[PreferencesKeys.PAIRING_PIN] = newPin
                    }
                }
            }
        } else {
            _pairingPin.value = ThreadLocalRandom.current().nextInt(100000, 1000000).toString()
        }

        // Continuously collect DataStore updates in background
        scope.launch {
            appContext.haDataStore.data.collect { prefs ->
                prefs[PreferencesKeys.SERVER_URL]?.let { if (it != _serverUrl.value) _serverUrl.value = it }
                val enc = prefs[PreferencesKeys.ENCRYPTED_ACCESS_TOKEN]
                if (!enc.isNullOrEmpty()) {
                    val decrypted = TinkKeystoreCrypto.decrypt(appContext, enc)
                    if (decrypted != _accessToken.value) _accessToken.value = decrypted
                }
                prefs[PreferencesKeys.PANEL_LAYOUT]?.let {
                    val n = normalizeLayout(it)
                    if (n != _panelLayout.value) _panelLayout.value = n
                }
                prefs[PreferencesKeys.POPUP_STYLE]?.let {
                    val n = normalizePopupStyle(it)
                    if (n != _popupStyle.value) _popupStyle.value = n
                }
                prefs[PreferencesKeys.CAMERA_POPUP_STYLE]?.let {
                    val n = normalizeCameraPopupStyle(it)
                    if (n != _cameraPopupStyle.value) _cameraPopupStyle.value = n
                }
                prefs[PreferencesKeys.CAMERA_COMPACT_SIZE]?.let {
                    val n = normalizeCameraCompactSize(it)
                    if (n != _cameraCompactSize.value) _cameraCompactSize.value = n
                }
                prefs[PreferencesKeys.RECENT_APPS_ENABLED]?.let { if (it != _recentAppsEnabled.value) _recentAppsEnabled.value = it }
                prefs[PreferencesKeys.RECENT_APPS_COUNT]?.let {
                    val c = it.coerceIn(1, 5)
                    if (c != _recentAppsCount.value) _recentAppsCount.value = c
                }
                prefs[PreferencesKeys.HA_ENABLED]?.let { if (it != _haEnabled.value) _haEnabled.value = it }
                prefs[PreferencesKeys.VOLUME_FIX_ENABLED]?.let { if (it != _volumeFixEnabled.value) _volumeFixEnabled.value = it }
                prefs[PreferencesKeys.THEME_PRESET]?.let {
                    if (it != _themePreset.value) {
                        _themePreset.value = it
                        _activePalette.value = calculateActivePalette(it, _customThemeColors.value)
                    }
                }
                prefs[PreferencesKeys.CUSTOM_THEME_COLORS]?.let { raw ->
                    val parsed = loadCustomThemeColors(raw)
                    if (parsed != _customThemeColors.value) {
                        _customThemeColors.value = parsed
                        _activePalette.value = calculateActivePalette(_themePreset.value, parsed)
                    }
                }
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
                prefs[PreferencesKeys.RECENT_APPS_LIST]?.let { raw ->
                    val parsed = loadRecentApps(raw)
                    if (parsed != _recentApps.value) _recentApps.value = parsed
                }
            }
        }

        // Continuously sync monitored entity set to HAWebSocketClient
        scope.launch {
            combine(_pinnedEntities, _buttonRemaps) { pins, remaps ->
                val set = mutableSetOf<String>()
                for (p in pins) {
                    if (p.entityId.contains(".")) {
                        set.add(p.entityId)
                        val base = p.entityId.substringAfter(".")
                        set.add("sensor.${base}_humidity")
                    }
                }
                for (remap in remaps) {
                    listOfNotNull(remap.singlePressAction, remap.doublePressAction, remap.longPressAction).forEach { action ->
                        if (action.type == "TOGGLE_ENTITY" || action.type == "SELECT_SOURCE") {
                            action.target?.let { target ->
                                if (target.contains(".")) {
                                    set.add(target)
                                }
                            }
                        }
                    }
                }
                set
            }.collect { monitoredSet ->
                HAWebSocketClient.getInstance().setBaseMonitoredEntities(monitoredSet)
            }
        }
    }

    fun setPopupStyle(style: String) {
        val normalized = normalizePopupStyle(style)
        _popupStyle.value = normalized
        val cameraNormalized = if (normalized == POPUP_STYLE_MINIMAL) CAMERA_POPUP_COMPACT else CAMERA_POPUP_CENTERED
        _cameraPopupStyle.value = cameraNormalized
        scope.launch(Dispatchers.IO) {
            appContext.haDataStore.edit { p ->
                p[PreferencesKeys.POPUP_STYLE] = normalized
                p[PreferencesKeys.CAMERA_POPUP_STYLE] = cameraNormalized
            }
        }
    }

    fun setCameraPopupStyle(style: String) {
        val normalized = normalizeCameraPopupStyle(style)
        _cameraPopupStyle.value = normalized
        scope.launch(Dispatchers.IO) {
            appContext.haDataStore.edit { p ->
                p[PreferencesKeys.CAMERA_POPUP_STYLE] = normalized
            }
        }
    }

    fun setCameraCompactSize(size: String) {
        val normalized = normalizeCameraCompactSize(size)
        _cameraCompactSize.value = normalized
        scope.launch(Dispatchers.IO) {
            appContext.haDataStore.edit { p ->
                p[PreferencesKeys.CAMERA_COMPACT_SIZE] = normalized
            }
        }
    }

    fun saveServerConfig(url: String, token: String) {
        val cleanUrl = url.trim().removeSuffix("/")
        val cleanToken = token.trim()
        _serverUrl.value = cleanUrl
        _accessToken.value = cleanToken
        scope.launch(Dispatchers.IO) {
            val enc = TinkKeystoreCrypto.encrypt(appContext, cleanToken)
            appContext.haDataStore.edit { p ->
                p[PreferencesKeys.SERVER_URL] = cleanUrl
                p[PreferencesKeys.ENCRYPTED_ACCESS_TOKEN] = enc
                p.remove(PreferencesKeys.ACCESS_TOKEN)
            }
        }
    }

    fun setPanelLayout(layout: String) {
        val normalized = normalizeLayout(layout)
        _panelLayout.value = normalized
        scope.launch(Dispatchers.IO) {
            appContext.haDataStore.edit { p ->
                p[PreferencesKeys.PANEL_LAYOUT] = normalized
            }
        }
    }

    fun savePinnedEntities(entities: List<PinnedEntityConfig>) {
        val indexed = entities.mapIndexed { idx, item -> item.copy(order = idx) }
        val serialized = json.encodeToString(indexed)
        _pinnedEntities.value = indexed
        scope.launch(Dispatchers.IO) {
            appContext.haDataStore.edit { p ->
                p[PreferencesKeys.PINNED_ENTITIES] = serialized
            }
        }
    }

    fun movePinnedEntity(fromIndex: Int, toIndex: Int) {
        val current = _pinnedEntities.value.toMutableList()
        if (fromIndex in current.indices && toIndex in current.indices && fromIndex != toIndex) {
            val item = current.removeAt(fromIndex)
            current.add(toIndex, item)
            savePinnedEntities(current)
        }
    }

    fun addPinnedEntity(entityId: String, customName: String? = null, category: String? = null, customIcon: String? = null) {
        val current = _pinnedEntities.value.toMutableList()
        if (current.none { it.entityId == entityId }) {
            current.add(PinnedEntityConfig(entityId = entityId, customName = customName, customCategory = category, customIcon = customIcon, order = current.size))
            savePinnedEntities(current)
        }
    }

    fun removePinnedEntity(entityId: String) {
        val current = _pinnedEntities.value.filterNot { it.entityId == entityId }
        savePinnedEntities(current)
    }

    fun savePinnedApps(apps: List<PinnedAppConfig>) {
        val indexed = apps.mapIndexed { idx, item -> item.copy(order = idx) }
        val serialized = json.encodeToString(indexed)
        _pinnedApps.value = indexed
        scope.launch(Dispatchers.IO) {
            appContext.haDataStore.edit { p ->
                p[PreferencesKeys.PINNED_APPS] = serialized
            }
        }
    }

    fun togglePinnedApp(packageName: String, appName: String) {
        val current = _pinnedApps.value.toMutableList()
        val index = current.indexOfFirst { it.packageName == packageName }
        if (index != -1) {
            current.removeAt(index)
        } else {
            current.add(PinnedAppConfig(packageName = packageName, appName = appName, order = current.size))
        }
        savePinnedApps(current)
    }

    fun setRecentAppsEnabled(enabled: Boolean) {
        _recentAppsEnabled.value = enabled
        scope.launch(Dispatchers.IO) {
            appContext.haDataStore.edit { p ->
                p[PreferencesKeys.RECENT_APPS_ENABLED] = enabled
            }
        }
    }

    fun setRecentAppsCount(count: Int) {
        val clamped = count.coerceIn(1, 5)
        _recentAppsCount.value = clamped
        scope.launch(Dispatchers.IO) {
            appContext.haDataStore.edit { p ->
                p[PreferencesKeys.RECENT_APPS_COUNT] = clamped
            }
        }
    }

    fun setHaEnabled(enabled: Boolean) {
        _haEnabled.value = enabled
        scope.launch(Dispatchers.IO) {
            appContext.haDataStore.edit { p ->
                p[PreferencesKeys.HA_ENABLED] = enabled
            }
        }
    }

    fun setVolumeFixEnabled(enabled: Boolean) {
        _volumeFixEnabled.value = enabled
        scope.launch(Dispatchers.IO) {
            appContext.haDataStore.edit { p ->
                p[PreferencesKeys.VOLUME_FIX_ENABLED] = enabled
            }
        }
    }

    fun setThemePreset(presetId: String) {
        val normalized = ThemePreset.fromId(presetId).id
        _themePreset.value = normalized
        _activePalette.value = calculateActivePalette(normalized, _customThemeColors.value)
        scope.launch(Dispatchers.IO) {
            appContext.haDataStore.edit { p ->
                p[PreferencesKeys.THEME_PRESET] = normalized
            }
        }
    }

    fun setCustomDomainColor(domain: String, hex: String) {
        val current = _customThemeColors.value.toMutableMap()
        current[domain] = hex
        val serialized = json.encodeToString(current)
        _customThemeColors.value = current
        _activePalette.value = calculateActivePalette(_themePreset.value, current)
        scope.launch(Dispatchers.IO) {
            appContext.haDataStore.edit { p ->
                p[PreferencesKeys.CUSTOM_THEME_COLORS] = serialized
            }
        }
    }

    fun setCustomDomainColors(colors: Map<String, String>) {
        val current = _customThemeColors.value.toMutableMap()
        current.putAll(colors)
        val serialized = json.encodeToString(current)
        _customThemeColors.value = current
        _activePalette.value = calculateActivePalette(_themePreset.value, current)
        scope.launch(Dispatchers.IO) {
            appContext.haDataStore.edit { p ->
                p[PreferencesKeys.CUSTOM_THEME_COLORS] = serialized
            }
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

    fun recordRecentApp(packageName: String) {
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

        val current = _recentApps.value.toMutableList()
        current.remove(packageName)
        current.add(0, packageName)
        val trimmed = current.take(5)
        try {
            val encoded = json.encodeToString(trimmed)
            _recentApps.value = trimmed
            scope.launch(Dispatchers.IO) {
                appContext.haDataStore.edit { p ->
                    p[PreferencesKeys.RECENT_APPS_LIST] = encoded
                }
            }
        } catch (_: Exception) {}
    }

    fun clearRecentApps() {
        _recentApps.value = emptyList()
        scope.launch(Dispatchers.IO) {
            appContext.haDataStore.edit { p ->
                p.remove(PreferencesKeys.RECENT_APPS_LIST)
            }
        }
    }

    fun saveButtonRemaps(remaps: List<ButtonRemapConfig>) {
        val serialized = json.encodeToString(remaps)
        _buttonRemaps.value = remaps
        scope.launch(Dispatchers.IO) {
            appContext.haDataStore.edit { p ->
                p[PreferencesKeys.BUTTON_REMAPS] = serialized
            }
        }
    }

    fun saveOrUpdateButtonRemap(config: ButtonRemapConfig) {
        val current = _buttonRemaps.value.toMutableList()
        val index = current.indexOfFirst { it.keyCode == config.keyCode && it.appScope == config.appScope }
        if (index != -1) {
            current[index] = config
        } else {
            current.add(config)
        }
        saveButtonRemaps(current)
    }

    fun removeButtonRemap(keyCode: Int, appScope: String? = null) {
        val current = _buttonRemaps.value.filterNot {
            it.keyCode == keyCode && (appScope == null || it.appScope == appScope)
        }
        saveButtonRemaps(current)
    }

    fun removeButtonRemap(config: ButtonRemapConfig) {
        val current = _buttonRemaps.value.filterNot {
            it.keyCode == config.keyCode && it.appScope == config.appScope
        }
        saveButtonRemaps(current)
    }

    fun exportBackupJson(includeToken: Boolean = false): String {
        val backup = HomeKeyBackup(
            serverUrl = _serverUrl.value,
            accessToken = if (includeToken) _accessToken.value else null,
            panelLayout = _panelLayout.value,
            popupStyle = _popupStyle.value,
            cameraPopupStyle = _cameraPopupStyle.value,
            cameraCompactSize = _cameraCompactSize.value,
            recentAppsEnabled = _recentAppsEnabled.value,
            recentAppsCount = _recentAppsCount.value,
            haEnabled = _haEnabled.value,
            themePreset = _themePreset.value,
            customThemeColors = _customThemeColors.value,
            volumeFixEnabled = _volumeFixEnabled.value,
            pinnedEntities = _pinnedEntities.value,
            pinnedApps = _pinnedApps.value,
            buttonRemaps = _buttonRemaps.value
        )
        return json.encodeToString(backup)
    }

    fun restoreFromBackupJson(jsonString: String): Boolean {
        return try {
            val backup = json.decodeFromString<HomeKeyBackup>(jsonString)
            if (backup.serverUrl.isNotBlank()) {
                val token = backup.accessToken?.ifBlank { null } ?: _accessToken.value
                saveServerConfig(backup.serverUrl, token)
            }
            setPanelLayout(backup.panelLayout)
            setPopupStyle(backup.popupStyle)
            setCameraPopupStyle(backup.cameraPopupStyle)
            setCameraCompactSize(backup.cameraCompactSize)
            setRecentAppsEnabled(backup.recentAppsEnabled)
            setRecentAppsCount(backup.recentAppsCount)
            setHaEnabled(backup.haEnabled)
            setVolumeFixEnabled(backup.volumeFixEnabled)
            setThemePreset(backup.themePreset)
            setCustomDomainColors(backup.customThemeColors)
            savePinnedEntities(backup.pinnedEntities)
            savePinnedApps(backup.pinnedApps)
            saveButtonRemaps(backup.buttonRemaps)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore backup: ${e.message}", e)
            false
        }
    }

    private fun loadPinnedEntities(raw: String?): List<PinnedEntityConfig> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            json.decodeFromString<List<PinnedEntityConfig>>(raw)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun loadPinnedApps(raw: String?): List<PinnedAppConfig> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            json.decodeFromString<List<PinnedAppConfig>>(raw)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun loadButtonRemaps(raw: String?): List<ButtonRemapConfig> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            json.decodeFromString<List<ButtonRemapConfig>>(raw)
        } catch (e: Exception) {
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
            Log.w(TAG, "EncryptedSharedPreferences unavailable: $e")
            null
        }
    }

    private object PreferencesKeys {
        val SERVER_URL = stringPreferencesKey("ha_server_url")
        val ACCESS_TOKEN = stringPreferencesKey("ha_access_token")
        val ENCRYPTED_ACCESS_TOKEN = stringPreferencesKey("ha_encrypted_access_token")
        val PINNED_ENTITIES = stringPreferencesKey("ha_pinned_entities")
        val PINNED_APPS = stringPreferencesKey("ha_pinned_apps")
        val BUTTON_REMAPS = stringPreferencesKey("ha_button_remaps")
        val PANEL_LAYOUT = stringPreferencesKey("ha_panel_layout")
        val PAIRING_PIN = stringPreferencesKey("ha_pairing_pin")
        val HA_ENABLED = booleanPreferencesKey("ha_integration_enabled")
        val THEME_PRESET = stringPreferencesKey("ha_theme_preset")
        val CUSTOM_THEME_COLORS = stringPreferencesKey("ha_custom_theme_colors")
        val POPUP_STYLE = stringPreferencesKey("ha_popup_style")
        val RECENT_APPS_ENABLED = booleanPreferencesKey("ha_recent_apps_enabled")
        val RECENT_APPS_COUNT = intPreferencesKey("ha_recent_apps_count")
        val RECENT_APPS_LIST = stringPreferencesKey("ha_recent_apps_list")
        val CAMERA_POPUP_STYLE = stringPreferencesKey("ha_camera_popup_style")
        val CAMERA_COMPACT_SIZE = stringPreferencesKey("ha_camera_compact_size")
        val VOLUME_FIX_ENABLED = booleanPreferencesKey("ha_volume_fix_enabled")
    }

    companion object {
        private const val TAG = "PreferencesManager"
        const val FILE_LEGACY_PREFS = "ha_tv_prefs"
        private const val FILE_SECURE_PREFS = "ha_tv_secure_prefs"
        private const val KEY_ACCESS_TOKEN = "ha_access_token"
        const val KEY_HA_ENABLED = "ha_integration_enabled"
        const val KEY_THEME_PRESET = "ha_theme_preset"
        const val KEY_CUSTOM_THEME_COLORS = "ha_custom_theme_colors"
        const val KEY_POPUP_STYLE = "ha_popup_style"
        const val KEY_RECENT_APPS_ENABLED = "ha_recent_apps_enabled"
        const val KEY_RECENT_APPS_COUNT = "ha_recent_apps_count"
        const val KEY_CAMERA_POPUP_STYLE = "ha_camera_popup_style"
        const val KEY_CAMERA_COMPACT_SIZE = "ha_camera_compact_size"
        const val KEY_VOLUME_FIX_ENABLED = "ha_volume_fix_enabled"
        const val POPUP_STYLE_CENTERED = "CENTERED"
        const val POPUP_STYLE_MINIMAL = "MINIMAL"
        const val CAMERA_POPUP_CENTERED = "CENTERED"
        const val CAMERA_POPUP_COMPACT = "COMPACT"
        const val CAMERA_COMPACT_SIZE_SMALL = "SMALL"
        const val CAMERA_COMPACT_SIZE_MEDIUM = "MEDIUM"
        const val CAMERA_COMPACT_SIZE_LARGE = "LARGE"

        fun normalizeLayout(layout: String?): String {
            return when (layout?.uppercase()) {
                "DOCK_LEFT", "LEFT" -> "DOCK_LEFT"
                "DOCK_RIGHT", "RIGHT", "SIDE_PANEL" -> "DOCK_RIGHT"
                else -> "DOCK_BOTTOM"
            }
        }

        fun normalizePopupStyle(style: String?): String {
            return when (style?.uppercase()) {
                POPUP_STYLE_MINIMAL, "COMPACT", "HORIZONTAL", "MINIMAL_DOCK", "DOCK" -> POPUP_STYLE_MINIMAL
                else -> POPUP_STYLE_CENTERED
            }
        }

        fun normalizeCameraPopupStyle(style: String?): String {
            return when (style?.uppercase()) {
                CAMERA_POPUP_COMPACT, "MINIMAL", "DOCK" -> CAMERA_POPUP_COMPACT
                else -> CAMERA_POPUP_CENTERED
            }
        }

        fun normalizeCameraCompactSize(size: String?): String {
            return when (size?.uppercase()) {
                CAMERA_COMPACT_SIZE_SMALL, "S", "SMALL_SIZE" -> CAMERA_COMPACT_SIZE_SMALL
                CAMERA_COMPACT_SIZE_LARGE, "L", "LARGE_SIZE" -> CAMERA_COMPACT_SIZE_LARGE
                else -> CAMERA_COMPACT_SIZE_MEDIUM
            }
        }

        @Volatile
        private var instance: PreferencesManager? = null

        fun getInstance(context: Context): PreferencesManager {
            return instance ?: synchronized(this) {
                instance ?: PreferencesManager(context.applicationContext).also { instance = it }
            }
        }
    }
}

@Serializable
data class HomeKeyBackup(
    val serverUrl: String = "",
    val accessToken: String? = null,
    val panelLayout: String = "DOCK_BOTTOM",
    val popupStyle: String = "CENTERED",
    val cameraPopupStyle: String = "CENTERED",
    val cameraCompactSize: String = "MEDIUM",
    val recentAppsEnabled: Boolean = true,
    val recentAppsCount: Int = 3,
    val haEnabled: Boolean = true,
    val themePreset: String = "DEFAULT",
    val customThemeColors: Map<String, String> = emptyMap(),
    val volumeFixEnabled: Boolean = false,
    val pinnedEntities: List<PinnedEntityConfig> = emptyList(),
    val pinnedApps: List<PinnedAppConfig> = emptyList(),
    val buttonRemaps: List<ButtonRemapConfig> = emptyList()
)

