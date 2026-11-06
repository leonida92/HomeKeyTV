package com.homekey.tv

import com.homekey.tv.data.local.*
import com.homekey.tv.data.models.ButtonRemapConfig
import com.homekey.tv.data.models.DomainColorPalette
import com.homekey.tv.data.models.PinnedAppConfig
import com.homekey.tv.data.models.PinnedEntityConfig
import com.homekey.tv.viewmodel.PanelViewModel
import com.homekey.tv.viewmodel.SettingsViewModel
import android.app.Application
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class PreferencesRepositoryTest {

    private lateinit var repo: PreferencesRepository

    @Before
    fun setup() {
        repo = FakePreferencesRepository()
    }

    @Test
    fun `serverConfig isConfigured requires haEnabled, serverUrl, and accessToken`() = runTest {
        assertFalse(repo.serverConfig.value.isConfigured)

        repo.updateServerConfig {
            it.copy(serverUrl = "http://192.168.1.100:8123", accessToken = "token123", haEnabled = true)
        }
        assertTrue(repo.serverConfig.value.isConfigured)

        repo.updateServerConfig { it.copy(haEnabled = false) }
        assertFalse(repo.serverConfig.value.isConfigured)

        repo.updateServerConfig { it.copy(haEnabled = true, serverUrl = "") }
        assertFalse(repo.serverConfig.value.isConfigured)
    }

    @Test
    fun `atomic facet mutation updates dockConfig correctly`() = runTest {
        assertEquals(DockConfig.LAYOUT_BOTTOM, repo.dockConfig.value.layout)

        repo.updateDockConfig {
            it.copy(
                layout = DockConfig.LAYOUT_LEFT,
                popupStyle = DockConfig.POPUP_STYLE_MINIMAL,
                cameraPopupStyle = DockConfig.CAMERA_POPUP_COMPACT,
                cameraCompactSize = DockConfig.CAMERA_COMPACT_SIZE_LARGE
            )
        }

        val config = repo.dockConfig.value
        assertEquals(DockConfig.LAYOUT_LEFT, config.layout)
        assertEquals(DockConfig.POPUP_STYLE_MINIMAL, config.popupStyle)
        assertEquals(DockConfig.CAMERA_POPUP_COMPACT, config.cameraPopupStyle)
        assertEquals(DockConfig.CAMERA_COMPACT_SIZE_LARGE, config.cameraCompactSize)
    }

    @Test
    fun `pinEntity and unpinEntity mutate pinned entities atomically`() = runTest {
        val light1 = PinnedEntityConfig(entityId = "light.living_room", customName = "Living Room")
        val switch1 = PinnedEntityConfig(entityId = "switch.kitchen", customName = "Kitchen Switch")

        repo.pinEntity(light1)
        repo.pinEntity(switch1)

        assertEquals(2, repo.pinnedEntities.value.size)
        assertEquals("light.living_room", repo.pinnedEntities.value[0].entityId)
        assertEquals("switch.kitchen", repo.pinnedEntities.value[1].entityId)

        repo.unpinEntity("light.living_room")
        assertEquals(1, repo.pinnedEntities.value.size)
        assertEquals("switch.kitchen", repo.pinnedEntities.value[0].entityId)
    }

    @Test
    fun `movePinnedEntity reorders entities with wrap-around`() = runTest {
        val e1 = PinnedEntityConfig(entityId = "light.1", order = 0)
        val e2 = PinnedEntityConfig(entityId = "light.2", order = 1)
        val e3 = PinnedEntityConfig(entityId = "light.3", order = 2)
        repo.savePinnedEntities(listOf(e1, e2, e3))

        // Move light.1 forward by 1 (0 -> 1)
        repo.movePinnedEntity("light.1", 1)
        assertEquals(listOf("light.2", "light.1", "light.3"), repo.pinnedEntities.value.map { it.entityId })

        // Move light.2 backward by 1 with wrap-around (0 -> 2)
        repo.movePinnedEntity("light.2", -1)
        assertEquals(listOf("light.1", "light.3", "light.2"), repo.pinnedEntities.value.map { it.entityId })
    }

    @Test
    fun `movePinnedEntity reorders apps with app prefix`() = runTest {
        val app1 = PinnedAppConfig(packageName = "com.netflix.ninja", appName = "Netflix", order = 0)
        val app2 = PinnedAppConfig(packageName = "com.google.android.youtube.tv", appName = "YouTube", order = 1)
        repo.savePinnedApps(listOf(app1, app2))

        repo.movePinnedEntity("app:com.netflix.ninja", 1)
        assertEquals("com.google.android.youtube.tv", repo.pinnedApps.value[0].packageName)
        assertEquals("com.netflix.ninja", repo.pinnedApps.value[1].packageName)
    }

    @Test
    fun `recordRecentApp does nothing when recentAppsConfig is disabled`() = runTest {
        repo.updateRecentAppsConfig { it.copy(enabled = false) }
        repo.recordRecentApp("app.a")
        assertTrue(repo.recentAppsConfig.value.recentPackages.isEmpty())
    }

    @Test
    fun `recordRecentApp manages LRU list up to maxCount when enabled`() = runTest {
        repo.updateRecentAppsConfig { it.copy(enabled = true, maxCount = 2) }

        repo.recordRecentApp("app.a")
        repo.recordRecentApp("app.b")
        assertEquals(listOf("app.b", "app.a"), repo.recentAppsConfig.value.recentPackages)

        repo.recordRecentApp("app.c")
        // maxCount is 2, app.a should be trimmed
        assertEquals(listOf("app.c", "app.b"), repo.recentAppsConfig.value.recentPackages)

        // Promoting existing app brings it to front
        repo.recordRecentApp("app.b")
        assertEquals(listOf("app.b", "app.c"), repo.recentAppsConfig.value.recentPackages)
    }

    @Test
    fun `export and restore backup preserves all configuration facets`() = runTest {
        repo.updateServerConfig {
            it.copy(serverUrl = "http://ha.local:8123", accessToken = "secret_tok", haEnabled = true)
        }
        repo.updateDockConfig {
            it.copy(layout = DockConfig.LAYOUT_RIGHT, popupStyle = DockConfig.POPUP_STYLE_MINIMAL)
        }
        repo.updateThemeConfig {
            it.copy(preset = "cyberpunk", customColors = mapOf("light" to "#00ffcc"))
        }
        repo.savePinnedEntities(listOf(PinnedEntityConfig(entityId = "light.neon")))

        val jsonWithoutToken = repo.exportBackupJson(includeToken = false)
        assertTrue(jsonWithoutToken.contains("http://ha.local:8123"))
        assertTrue(jsonWithoutToken.contains("cyberpunk"))
        assertFalse(jsonWithoutToken.contains("secret_tok"))

        val jsonWithToken = repo.exportBackupJson(includeToken = true)
        assertTrue(jsonWithToken.contains("secret_tok"))

        val freshRepo = FakePreferencesRepository()
        val result = freshRepo.restoreFromBackupJson(jsonWithToken)
        assertTrue(result.isSuccess)

        assertEquals("http://ha.local:8123", freshRepo.serverConfig.value.serverUrl)
        assertEquals("secret_tok", freshRepo.serverConfig.value.accessToken)
        assertEquals(DockConfig.LAYOUT_RIGHT, freshRepo.dockConfig.value.layout)
        assertEquals(DockConfig.POPUP_STYLE_MINIMAL, freshRepo.dockConfig.value.popupStyle)
        assertEquals("cyberpunk", freshRepo.themeConfig.value.preset)
        assertEquals("#00ffcc", freshRepo.themeConfig.value.customColors["light"])
        assertEquals(1, freshRepo.pinnedEntities.value.size)
        assertEquals("light.neon", freshRepo.pinnedEntities.value[0].entityId)
    }

    @Test
    fun `restoreFromBackupJson handles legacy backup with panelLayout`() = runTest {
        val legacyJson = """
            {
                "serverUrl": "http://legacy:8123",
                "panelLayout": "DOCK_LEFT",
                "popupStyle": "MINIMAL",
                "cameraPopupStyle": "COMPACT",
                "cameraCompactSize": "SMALL",
                "recentAppsEnabled": true,
                "recentAppsCount": 4,
                "haEnabled": true,
                "themePreset": "classic",
                "customThemeColors": {},
                "volumeFixEnabled": false,
                "pinnedEntities": [],
                "pinnedApps": [],
                "buttonRemaps": []
            }
        """.trimIndent()

        val freshRepo = FakePreferencesRepository()
        val result = freshRepo.restoreFromBackupJson(legacyJson)
        assertTrue(result.isSuccess)
        assertEquals(DockConfig.LAYOUT_LEFT, freshRepo.dockConfig.value.layout)
        assertEquals(DockConfig.POPUP_STYLE_MINIMAL, freshRepo.dockConfig.value.popupStyle)
        assertEquals(DockConfig.CAMERA_POPUP_COMPACT, freshRepo.dockConfig.value.cameraPopupStyle)
        assertEquals(DockConfig.CAMERA_COMPACT_SIZE_SMALL, freshRepo.dockConfig.value.cameraCompactSize)
        assertEquals(4, freshRepo.recentAppsConfig.value.maxCount)
    }

    @Test
    fun `PanelViewModel and SettingsViewModel have public single-arg Application constructor for AndroidViewModelFactory`() {
        val panelConstructor = PanelViewModel::class.java.getConstructor(Application::class.java)
        assertNotNull(panelConstructor)

        val settingsConstructor = SettingsViewModel::class.java.getConstructor(Application::class.java)
        assertNotNull(settingsConstructor)
    }
}
