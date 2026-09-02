package com.homeassistant.tv.data.local

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.homeassistant.tv.data.models.ButtonRemapConfig
import com.homeassistant.tv.data.models.PinnedAppConfig
import com.homeassistant.tv.data.models.PinnedEntityConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ThreadLocalRandom

class PreferencesManager(context: Context) {
    private val appContext = context.applicationContext
    private val prefs: SharedPreferences = appContext.getSharedPreferences(FILE_LEGACY_PREFS, Context.MODE_PRIVATE)

    // The HA long-lived token is kept in an Android-Keystore-backed encrypted prefs file, not the
    // readable/backup-eligible plaintext file. Falls back to plaintext only when the Keystore can't
    // be initialised (broken or emulated devices), so first-run setup never hard-crashes.
    private val securePrefs: SharedPreferences? = createSecurePrefs(appContext)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

    init {
        // One-time migration for installs that stored the token in the old plaintext file.
        migrateLegacyToken()
    }

    private val _serverUrl = MutableStateFlow(prefs.getString(KEY_SERVER_URL, "") ?: "")
    val serverUrl: StateFlow<String> = _serverUrl.asStateFlow()

    private val _accessToken = MutableStateFlow(readAccessToken())
    val accessToken: StateFlow<String> = _accessToken.asStateFlow()

    // 6-digit pairing code the user reads off the TV screen and types into the phone setup page.
    private val _pairingPin = MutableStateFlow(loadOrCreatePairingPin())
    val pairingPin: StateFlow<String> = _pairingPin.asStateFlow()

    private val _pinnedEntities = MutableStateFlow(loadPinnedEntities())
    val pinnedEntities: StateFlow<List<PinnedEntityConfig>> = _pinnedEntities.asStateFlow()

    private val _pinnedApps = MutableStateFlow(loadPinnedApps())
    val pinnedApps: StateFlow<List<PinnedAppConfig>> = _pinnedApps.asStateFlow()

    private val _buttonRemaps = MutableStateFlow(loadButtonRemaps())
    val buttonRemaps: StateFlow<List<ButtonRemapConfig>> = _buttonRemaps.asStateFlow()

    private val _panelLayout = MutableStateFlow(normalizeLayout(prefs.getString(KEY_PANEL_LAYOUT, "DOCK_BOTTOM")))
    val panelLayout: StateFlow<String> = _panelLayout.asStateFlow()

    val isConfigured: Boolean
        get() = serverUrl.value.isNotBlank() && accessToken.value.isNotBlank()

    private fun normalizeLayout(layout: String?): String {
        return when (layout?.uppercase()) {
            "DOCK_LEFT", "LEFT" -> "DOCK_LEFT"
            "DOCK_RIGHT", "RIGHT", "SIDE_PANEL" -> "DOCK_RIGHT"
            else -> "DOCK_BOTTOM"
        }
    }

    fun saveServerConfig(url: String, token: String) {
        val cleanUrl = url.trim().removeSuffix("/")
        val cleanToken = token.trim()
        prefs.edit().putString(KEY_SERVER_URL, cleanUrl).apply()
        writeAccessToken(cleanToken)
        _serverUrl.value = cleanUrl
        _accessToken.value = cleanToken
    }

    private fun readAccessToken(): String {
        val secure = securePrefs
        if (secure != null && secure.contains(KEY_ACCESS_TOKEN)) {
            return secure.getString(KEY_ACCESS_TOKEN, "") ?: ""
        }
        // Encrypted store not ready (or first read before migration) — fall back to legacy file.
        return prefs.getString(KEY_ACCESS_TOKEN, "") ?: ""
    }

    private fun writeAccessToken(token: String) {
        val secure = securePrefs
        if (secure != null) {
            secure.edit().putString(KEY_ACCESS_TOKEN, token).apply()
            // Scrub any plaintext copy so it can never leak via cloud/ADB backup.
            prefs.edit().remove(KEY_ACCESS_TOKEN).apply()
        } else {
            prefs.edit().putString(KEY_ACCESS_TOKEN, token).apply()
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
            Log.w("PreferencesManager", "EncryptedSharedPreferences unavailable: $e")
            null
        }
    }

    private fun migrateLegacyToken() {
        val secure = securePrefs ?: return
        if (!secure.contains(KEY_ACCESS_TOKEN)) {
            val legacy = prefs.getString(KEY_ACCESS_TOKEN, null)
            if (!legacy.isNullOrEmpty()) {
                secure.edit().putString(KEY_ACCESS_TOKEN, legacy).apply()
            }
        }
        if (secure.contains(KEY_ACCESS_TOKEN)) {
            prefs.edit().remove(KEY_ACCESS_TOKEN).apply()
        }
    }

    private fun loadOrCreatePairingPin(): String {
        prefs.getString(KEY_PAIRING_PIN, null)?.takeIf { it.length >= 6 }?.let { return it }
        val pin = (ThreadLocalRandom.current().nextInt(100000, 1000000)).toString()
        prefs.edit().putString(KEY_PAIRING_PIN, pin).apply()
        return pin
    }

    fun setPanelLayout(layout: String) {
        val normalized = normalizeLayout(layout)
        prefs.edit().putString(KEY_PANEL_LAYOUT, normalized).apply()
        _panelLayout.value = normalized
    }

    fun savePinnedEntities(entities: List<PinnedEntityConfig>) {
        val indexed = entities.mapIndexed { idx, item -> item.copy(order = idx) }
        val serialized = json.encodeToString(indexed)
        prefs.edit().putString(KEY_PINNED_ENTITIES, serialized).apply()
        _pinnedEntities.value = indexed
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
        prefs.edit().putString(KEY_PINNED_APPS, serialized).apply()
        _pinnedApps.value = indexed
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

    fun saveButtonRemaps(remaps: List<ButtonRemapConfig>) {
        val serialized = json.encodeToString(remaps)
        prefs.edit().putString(KEY_BUTTON_REMAPS, serialized).apply()
        _buttonRemaps.value = remaps
    }

    fun saveOrUpdateButtonRemap(config: ButtonRemapConfig) {
        val current = _buttonRemaps.value.toMutableList()
        val index = current.indexOfFirst { it.keyCode == config.keyCode }
        if (index != -1) {
            current[index] = config
        } else {
            current.add(config)
        }
        saveButtonRemaps(current)
    }

    fun removeButtonRemap(keyCode: Int) {
        val current = _buttonRemaps.value.filterNot { it.keyCode == keyCode }
        saveButtonRemaps(current)
    }

    private fun loadPinnedEntities(): List<PinnedEntityConfig> {
        val raw = prefs.getString(KEY_PINNED_ENTITIES, null) ?: return emptyList()
        return try {
            json.decodeFromString<List<PinnedEntityConfig>>(raw)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun loadPinnedApps(): List<PinnedAppConfig> {
        val raw = prefs.getString(KEY_PINNED_APPS, null) ?: return emptyList()
        return try {
            json.decodeFromString<List<PinnedAppConfig>>(raw)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun loadButtonRemaps(): List<ButtonRemapConfig> {
        val raw = prefs.getString(KEY_BUTTON_REMAPS, null) ?: return emptyList()
        return try {
            json.decodeFromString<List<ButtonRemapConfig>>(raw)
        } catch (e: Exception) {
            emptyList()
        }
    }

    companion object {
        private const val FILE_LEGACY_PREFS = "ha_tv_prefs"
        private const val FILE_SECURE_PREFS = "ha_tv_secure_prefs"
        private const val KEY_SERVER_URL = "ha_server_url"
        private const val KEY_ACCESS_TOKEN = "ha_access_token"
        private const val KEY_PINNED_ENTITIES = "ha_pinned_entities"
        private const val KEY_PINNED_APPS = "ha_pinned_apps"
        private const val KEY_BUTTON_REMAPS = "ha_button_remaps"
        private const val KEY_PANEL_LAYOUT = "ha_panel_layout"
        private const val KEY_PAIRING_PIN = "ha_pairing_pin"

        @Volatile
        private var instance: PreferencesManager? = null

        fun getInstance(context: Context): PreferencesManager {
            return instance ?: synchronized(this) {
                instance ?: PreferencesManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
