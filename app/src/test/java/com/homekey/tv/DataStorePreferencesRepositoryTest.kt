package com.homekey.tv

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.homekey.tv.data.local.DataStorePreferencesRepository
import com.homekey.tv.data.local.DockConfig
import com.homekey.tv.data.local.TokenCipher
import com.homekey.tv.data.models.DomainColorPalette
import com.homekey.tv.data.models.PinnedAppConfig
import com.homekey.tv.data.models.PinnedEntityConfig
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DataStorePreferencesRepositoryTest {

    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    private val trackedJobs = mutableListOf<CompletableJob>()

    private class FakeTokenCipher : TokenCipher {
        val encryptedInputs = mutableListOf<String>()
        val decryptedInputs = mutableListOf<String>()

        override fun encrypt(plaintext: String): String {
            encryptedInputs.add(plaintext)
            return if (plaintext.isEmpty()) "" else "enc:$plaintext"
        }

        override fun decrypt(ciphertext: String): String {
            decryptedInputs.add(ciphertext)
            return ciphertext.removePrefix("enc:")
        }
    }

    private fun newDataStore(file: File): Pair<DataStore<Preferences>, CompletableJob> {
        val job = SupervisorJob().also { trackedJobs.add(it) }
        val store = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + job),
            produceFile = { file }
        )
        return store to job
    }

    private fun newRepo(
        dataStore: DataStore<Preferences>,
        cipher: TokenCipher
    ): Pair<DataStorePreferencesRepository, CompletableJob> {
        val job = SupervisorJob().also { trackedJobs.add(it) }
        val repo = DataStorePreferencesRepository(
            dataStore = dataStore,
            tokenCipher = cipher,
            scope = CoroutineScope(Dispatchers.IO + job),
            defaultVolumeFixEnabled = false
        )
        return repo to job
    }

    @After
    fun tearDown() = runBlocking {
        for (job in trackedJobs.asReversed()) {
            job.cancelAndJoin()
        }
        trackedJobs.clear()
    }

    @Test
    fun `persists serverConfig with encrypted token and prewarms synchronously on restart`() = runBlocking {
        val prefsFile = File(tempFolder.newFolder(), "ha_tv_settings.preferences_pb")
        val cipher = FakeTokenCipher()
        val (store1, dsJob1) = newDataStore(prefsFile)
        val (repo1, repoJob1) = newRepo(store1, cipher)

        repo1.updateServerConfig {
            it.copy(
                serverUrl = "http://192.168.1.50:8123/",
                fallbackServerUrl = "https://remote.example.com/",
                accessToken = "  secret_token_123  ",
                haEnabled = true
            )
        }
        repo1.updateDockConfig {
            it.copy(layout = DockConfig.LAYOUT_LEFT, popupStyle = DockConfig.POPUP_STYLE_MINIMAL)
        }
        repo1.setVolumeFixEnabled(true)

        assertEquals("http://192.168.1.50:8123", repo1.serverConfig.value.serverUrl)
        assertEquals("https://remote.example.com", repo1.serverConfig.value.fallbackServerUrl)
        assertEquals("secret_token_123", repo1.serverConfig.value.accessToken)
        assertTrue(cipher.encryptedInputs.contains("secret_token_123"))

        val rawPrefs = store1.data.first()
        assertEquals("enc:secret_token_123", rawPrefs[stringPreferencesKey("ha_encrypted_access_token")])
        assertNull(rawPrefs[stringPreferencesKey("ha_access_token")])

        val generatedPin = repo1.serverConfig.value.pairingPin
        assertEquals(6, generatedPin.length)

        // Close first DataStore/repository and reopen the same backing file
        repoJob1.cancelAndJoin()
        dsJob1.cancelAndJoin()

        val cipher2 = FakeTokenCipher()
        val (store2, _) = newDataStore(prefsFile)
        val (repo2, _) = newRepo(store2, cipher2)

        // Synchronous IO pre-warming populates facets immediately upon construction
        assertEquals("http://192.168.1.50:8123", repo2.serverConfig.value.serverUrl)
        assertEquals("https://remote.example.com", repo2.serverConfig.value.fallbackServerUrl)
        assertEquals("secret_token_123", repo2.serverConfig.value.accessToken)
        assertEquals(generatedPin, repo2.serverConfig.value.pairingPin)
        assertTrue(cipher2.decryptedInputs.contains("enc:secret_token_123"))
        assertEquals(DockConfig.LAYOUT_LEFT, repo2.dockConfig.value.layout)
        assertEquals(DockConfig.POPUP_STYLE_MINIMAL, repo2.dockConfig.value.popupStyle)
        assertTrue(repo2.volumeFixEnabled.value)
    }

    @Test
    fun `migrates legacy plaintext access token in DataStore to encrypted token`() = runBlocking {
        val prefsFile = File(tempFolder.newFolder(), "legacy_token.preferences_pb")
        val (store, _) = newDataStore(prefsFile)
        val plainKey = stringPreferencesKey("ha_access_token")
        val encKey = stringPreferencesKey("ha_encrypted_access_token")

        store.edit { p ->
            p[plainKey] = "legacy_plain_token"
        }

        val cipher = FakeTokenCipher()
        val (repo, _) = newRepo(store, cipher)

        assertEquals("legacy_plain_token", repo.serverConfig.value.accessToken)

        val migratedPrefs = withTimeout(10_000L) {
            store.data.first { prefs ->
                prefs[encKey] == "enc:legacy_plain_token" && !prefs.contains(plainKey)
            }
        }
        assertEquals("enc:legacy_plain_token", migratedPrefs[encKey])
    }

    @Test
    fun `restoreFromBackupJson normalizes legacy panelLayout, dock styles, recentAppsCount, theme palette, and encrypts token`() = runBlocking {
        val prefsFile = File(tempFolder.newFolder(), "backup_restore.preferences_pb")
        val (store, _) = newDataStore(prefsFile)
        val cipher = FakeTokenCipher()
        val (repo, _) = newRepo(store, cipher)

        val backupJson = """
            {
                "serverUrl": "http://ha.local:8123/",
                "fallbackServerUrl": "https://nabu.test/",
                "accessToken": "restored_token_xyz",
                "panelLayout": "SIDE_PANEL",
                "popupStyle": "COMPACT",
                "cameraPopupStyle": "MINIMAL",
                "cameraCompactSize": "L",
                "recentAppsEnabled": true,
                "recentAppsCount": 99,
                "haEnabled": true,
                "themePreset": "cyberpunk",
                "customThemeColors": {"light": "#00FFCC"},
                "volumeFixEnabled": true,
                "pinnedEntities": [{"entityId": "light.desk", "order": 0}],
                "pinnedApps": [{"packageName": "com.kodi", "appName": "Kodi", "order": 0}],
                "buttonRemaps": []
            }
        """.trimIndent()

        val result = repo.restoreFromBackupJson(backupJson)
        assertTrue(result.isSuccess)

        assertEquals("http://ha.local:8123", repo.serverConfig.value.serverUrl)
        assertEquals("https://nabu.test", repo.serverConfig.value.fallbackServerUrl)
        assertEquals("restored_token_xyz", repo.serverConfig.value.accessToken)
        assertEquals(DockConfig.LAYOUT_RIGHT, repo.dockConfig.value.layout)
        assertEquals(DockConfig.POPUP_STYLE_MINIMAL, repo.dockConfig.value.popupStyle)
        assertEquals(DockConfig.CAMERA_POPUP_COMPACT, repo.dockConfig.value.cameraPopupStyle)
        assertEquals(DockConfig.CAMERA_COMPACT_SIZE_LARGE, repo.dockConfig.value.cameraCompactSize)
        assertTrue(repo.recentAppsConfig.value.enabled)
        assertEquals(5, repo.recentAppsConfig.value.maxCount)
        assertEquals("cyberpunk", repo.themeConfig.value.preset)
        assertEquals(DomainColorPalette.CYBERPUNK, repo.themeConfig.value.activePalette)
        assertTrue(repo.volumeFixEnabled.value)
        assertEquals(1, repo.pinnedEntities.value.size)
        assertEquals("light.desk", repo.pinnedEntities.value[0].entityId)
        assertEquals(1, repo.pinnedApps.value.size)
        assertEquals("com.kodi", repo.pinnedApps.value[0].packageName)

        val rawPrefs = store.data.first()
        assertEquals("enc:restored_token_xyz", rawPrefs[stringPreferencesKey("ha_encrypted_access_token")])
    }

    @Test
    fun `recordRecentApp enforces enabled guard rail, excludes system packages, and persists trimmed LRU list`() = runBlocking {
        val prefsFile = File(tempFolder.newFolder(), "recent_apps.preferences_pb")
        val (store, _) = newDataStore(prefsFile)
        val cipher = FakeTokenCipher()
        val (repo, _) = newRepo(store, cipher)

        val recentKey = stringPreferencesKey("ha_recent_apps_list")

        // Disabled by default -> no mutation and no DataStore write
        repo.recordRecentApp("com.netflix.ninja")
        assertTrue(repo.recentAppsConfig.value.recentPackages.isEmpty())
        assertNull(store.data.first()[recentKey])

        // Enable with maxCount = 2 (also check clamping when > 5)
        repo.updateRecentAppsConfig { it.copy(enabled = true, maxCount = 10) }
        assertEquals(5, repo.recentAppsConfig.value.maxCount)

        repo.updateRecentAppsConfig { it.copy(enabled = true, maxCount = 2) }
        repo.recordRecentApp("com.homekey.tv")
        repo.recordRecentApp("com.android.systemui")
        repo.recordRecentApp("com.google.android.tvlauncher.home")
        repo.recordRecentApp("")
        assertTrue(repo.recentAppsConfig.value.recentPackages.isEmpty())

        repo.recordRecentApp("com.app.one")
        repo.recordRecentApp("com.app.two")
        repo.recordRecentApp("com.app.three")
        assertEquals(listOf("com.app.three", "com.app.two"), repo.recentAppsConfig.value.recentPackages)

        val storedList = store.data.first()[recentKey]
        assertEquals("""["com.app.three","com.app.two"]""", storedList)
    }

    @Test
    fun `movePinnedEntity and exportBackupJson persist reordered items and respect includeToken`() = runBlocking {
        val prefsFile = File(tempFolder.newFolder(), "pinned_and_export.preferences_pb")
        val (store, _) = newDataStore(prefsFile)
        val cipher = FakeTokenCipher()
        val (repo, _) = newRepo(store, cipher)

        repo.updateServerConfig {
            it.copy(serverUrl = "http://ha.local:8123", accessToken = "top_secret")
        }
        repo.savePinnedEntities(
            listOf(
                PinnedEntityConfig(entityId = "light.one"),
                PinnedEntityConfig(entityId = "light.two"),
                PinnedEntityConfig(entityId = "light.three")
            )
        )
        repo.savePinnedApps(
            listOf(
                PinnedAppConfig(packageName = "com.one", appName = "One"),
                PinnedAppConfig(packageName = "com.two", appName = "Two")
            )
        )

        repo.movePinnedEntity("light.one", -1)
        assertEquals(listOf("light.two", "light.three", "light.one"), repo.pinnedEntities.value.map { it.entityId })
        assertEquals(listOf(0, 1, 2), repo.pinnedEntities.value.map { it.order })

        repo.movePinnedEntity("app:com.one", 1)
        assertEquals(listOf("com.two", "com.one"), repo.pinnedApps.value.map { it.packageName })
        assertEquals(listOf(0, 1), repo.pinnedApps.value.map { it.order })

        val withoutToken = repo.exportBackupJson(includeToken = false)
        assertFalse(withoutToken.contains("top_secret"))

        val withToken = repo.exportBackupJson(includeToken = true)
        assertTrue(withToken.contains("top_secret"))
    }

    @Test
    fun `initial pairing pin is persisted synchronously during construction`() = runBlocking {
        val prefsFile = File(tempFolder.newFolder(), "sync_pin.preferences_pb")
        val (store, _) = newDataStore(prefsFile)
        val cipher = FakeTokenCipher()
        val (repo, _) = newRepo(store, cipher)

        val generatedPin = repo.serverConfig.value.pairingPin
        assertEquals(6, generatedPin.length)
        assertEquals(generatedPin, store.data.first()[stringPreferencesKey("ha_pairing_pin")])
    }

    @Test
    fun `concurrent mutations are serialized by writeMutex without clobbering`() = runBlocking {
        val prefsFile = File(tempFolder.newFolder(), "concurrent_writes.preferences_pb")
        val (store, _) = newDataStore(prefsFile)
        val cipher = FakeTokenCipher()
        val (repo, _) = newRepo(store, cipher)

        repo.updateRecentAppsConfig { it.copy(enabled = true, maxCount = 5) }

        val entityJobs = (1..10).map { idx ->
            async(Dispatchers.IO) {
                repo.pinEntity(PinnedEntityConfig(entityId = "light.entity_$idx"))
            }
        }
        val recentJobs = (1..5).map { idx ->
            async(Dispatchers.IO) {
                repo.recordRecentApp("com.example.app$idx")
            }
        }
        (entityJobs + recentJobs).awaitAll()

        assertEquals(10, repo.pinnedEntities.value.size)
        assertEquals(5, repo.recentAppsConfig.value.recentPackages.size)

        val storedEntities = store.data.first()[stringPreferencesKey("ha_pinned_entities")] ?: ""
        for (idx in 1..10) {
            assertTrue("Missing light.entity_$idx in persisted DataStore", storedEntities.contains("light.entity_$idx"))
        }
    }
}
