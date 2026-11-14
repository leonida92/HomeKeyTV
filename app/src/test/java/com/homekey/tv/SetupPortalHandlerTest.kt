package com.homekey.tv

import com.homekey.tv.data.local.DockConfig
import com.homekey.tv.data.local.ServerConfig
import com.homekey.tv.data.local.ThemeConfig
import com.homekey.tv.data.models.PinnedEntityConfig
import com.homekey.tv.data.models.ThemePortalCatalog
import com.homekey.tv.data.models.ThemePreset
import com.homekey.tv.data.models.toPortalCatalogJson
import com.homekey.tv.data.server.PortalFetchResult
import com.homekey.tv.data.server.SetupPortalHandler
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SetupPortalHandlerTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun createPrefs(
        serverUrl: String = "http://192.168.1.50:8123",
        fallbackServerUrl: String = "",
        accessToken: String = "saved-secret-token",
        pairingPin: String = "123456"
    ): FakePreferencesRepository {
        return FakePreferencesRepository(
            initialServerConfig = ServerConfig(
                serverUrl = serverUrl,
                fallbackServerUrl = fallbackServerUrl,
                accessToken = accessToken,
                pairingPin = pairingPin,
                haEnabled = true
            )
        )
    }

    @Test
    fun `PIN brute-force lockout triggers after 8 failures and expires after 60s`() = runTest {
        var nowMs = 1_700_000_000_000L
        val prefs = createPrefs(pairingPin = "654321")
        val handler = SetupPortalHandler(
            preferencesRepository = prefs,
            fetchEntities = { _, _ -> PortalFetchResult.Success("[]") },
            clockMs = { nowMs }
        )

        // 7 failed attempts should not lock out yet
        repeat(SetupPortalHandler.MAX_PIN_ATTEMPTS - 1) {
            val res = handler.handleBackup(pin = "000000")
            assertEquals(403, res.statusCode)
        }
        assertEquals(200, handler.handleBackup(pin = "654321").statusCode)

        // Now trigger full 8 consecutive failures to engage lockout
        repeat(SetupPortalHandler.MAX_PIN_ATTEMPTS) {
            val res = handler.handleBackup(pin = "111111")
            assertEquals(403, res.statusCode)
        }

        // Even the valid PIN is rejected during the 60-second lockout window
        nowMs += SetupPortalHandler.PIN_LOCKOUT_MS - 1L
        assertEquals(403, handler.handleBackup(pin = "654321").statusCode)

        // Once 60 seconds have elapsed, valid PIN succeeds again
        nowMs += 1L
        assertEquals(200, handler.handleBackup(pin = "654321").statusCode)
    }

    @Test
    fun `fetch-entities blocks SSRF when typed serverUrl differs from saved URL without new token`() = runTest {
        val prefs = createPrefs(
            serverUrl = "http://192.168.1.50:8123",
            accessToken = "saved-secret-token"
        )
        var fetchedCalls = 0
        var lastUrl: String? = null
        var lastToken: String? = null

        val handler = SetupPortalHandler(
            preferencesRepository = prefs,
            fetchEntities = { url, token ->
                fetchedCalls++
                lastUrl = url
                lastToken = token
                PortalFetchResult.Success("[{\"entity_id\":\"light.living_room\"}]")
            }
        )

        // Attacker supplies a different URL with blank token: must reject before calling fetcher
        val ssrfRes = handler.handleFetchEntities(
            """{"serverUrl":"http://10.0.0.99:9000","token":"","pin":"123456"}"""
        )
        assertEquals(400, ssrfRes.statusCode)
        assertEquals(0, fetchedCalls)

        // Matching saved URL with blank token uses saved token safely
        val sameHostRes = handler.handleFetchEntities(
            """{"serverUrl":"http://192.168.1.50:8123/","token":"","pin":"123456"}"""
        )
        assertEquals(200, sameHostRes.statusCode)
        assertEquals(1, fetchedCalls)
        assertEquals("http://192.168.1.50:8123", lastUrl)
        assertEquals("saved-secret-token", lastToken)

        // Different URL WITH an explicitly typed token uses the typed token, never the saved token
        val explicitTokenRes = handler.handleFetchEntities(
            """{"serverUrl":"http://10.0.0.99:8123","token":"explicit-new-token","pin":"123456"}"""
        )
        assertEquals(200, explicitTokenRes.statusCode)
        assertEquals(2, fetchedCalls)
        assertEquals("http://10.0.0.99:8123", lastUrl)
        assertEquals("explicit-new-token", lastToken)
    }

    @Test
    fun `save blocks switching serverUrl with blank token when saved token exists`() = runTest {
        val prefs = createPrefs(
            serverUrl = "http://192.168.1.50:8123",
            accessToken = "saved-secret-token"
        )
        val handler = SetupPortalHandler(
            preferencesRepository = prefs,
            fetchEntities = { _, _ -> PortalFetchResult.Success("[]") }
        )

        val res = handler.handleSave(
            """{"serverUrl":"http://evil.local:8123","token":"","pin":"123456"}"""
        )
        assertEquals(400, res.statusCode)
        assertEquals("http://192.168.1.50:8123", prefs.serverConfig.value.serverUrl)
        assertEquals("saved-secret-token", prefs.serverConfig.value.accessToken)
    }

    @Test
    fun `save blocks changing fallbackServerUrl with blank token when saved token exists`() = runTest {
        val prefs = createPrefs(
            serverUrl = "http://192.168.1.50:8123",
            fallbackServerUrl = "https://remote.nabu.casa",
            accessToken = "saved-secret-token"
        )
        val handler = SetupPortalHandler(
            preferencesRepository = prefs,
            fetchEntities = { _, _ -> PortalFetchResult.Success("[]") }
        )

        val blockedRes = handler.handleSave(
            """{"serverUrl":"http://192.168.1.50:8123","fallbackServerUrl":"https://evil.local:8123","token":"","pin":"123456"}"""
        )
        assertEquals(400, blockedRes.statusCode)
        assertEquals("https://remote.nabu.casa", prefs.serverConfig.value.fallbackServerUrl)
        assertEquals("saved-secret-token", prefs.serverConfig.value.accessToken)

        // Keeping the existing fallback or matching saved primary URL does not require re-entering token
        val sameFallbackRes = handler.handleSave(
            """{"serverUrl":"http://192.168.1.50:8123","fallbackServerUrl":"https://remote.nabu.casa/","token":"","pin":"123456"}"""
        )
        assertEquals(200, sameFallbackRes.statusCode)
        assertEquals("https://remote.nabu.casa", prefs.serverConfig.value.fallbackServerUrl)

        val matchPrimaryRes = handler.handleSave(
            """{"serverUrl":"http://192.168.1.50:8123","fallbackServerUrl":"http://192.168.1.50:8123/","token":"","pin":"123456"}"""
        )
        assertEquals(200, matchPrimaryRes.statusCode)
        assertEquals("http://192.168.1.50:8123", prefs.serverConfig.value.fallbackServerUrl)

        // Changing fallback WITH a new token is allowed
        val newTokenRes = handler.handleSave(
            """{"serverUrl":"http://192.168.1.50:8123","fallbackServerUrl":"https://new-fallback.example.com/","token":"new-secret-token","pin":"123456"}"""
        )
        assertEquals(200, newTokenRes.statusCode)
        assertEquals("https://new-fallback.example.com", prefs.serverConfig.value.fallbackServerUrl)
        assertEquals("new-secret-token", prefs.serverConfig.value.accessToken)
    }

    @Test
    fun `save supports partial updates without clobbering omitted fields or saved token`() = runTest {
        val prefs = createPrefs(
            serverUrl = "http://192.168.1.50:8123",
            fallbackServerUrl = "https://remote.nabu.casa",
            accessToken = "saved-secret-token"
        )
        prefs.savePinnedEntities(
            listOf(PinnedEntityConfig(entityId = "light.kitchen", customName = "Kitchen", order = 0))
        )
        val handler = SetupPortalHandler(
            preferencesRepository = prefs,
            fetchEntities = { _, _ -> PortalFetchResult.Success("[]") }
        )

        // Partial save: null pinnedEntities leaves dock untouched, blank token keeps saved token
        val partialRes = handler.handleSave(
            """
            {
              "pin": "123456",
              "serverUrl": "http://192.168.1.50:8123",
              "token": "",
              "layout": "DOCK_LEFT",
              "themePreset": "cyberpunk",
              "customThemeColors": {"lock": "#FF00AA"}
            }
            """.trimIndent()
        )
        assertEquals(200, partialRes.statusCode)
        assertEquals("saved-secret-token", prefs.serverConfig.value.accessToken)
        assertEquals("https://remote.nabu.casa", prefs.serverConfig.value.fallbackServerUrl)
        assertEquals(DockConfig.LAYOUT_LEFT, prefs.dockConfig.value.layout)
        assertEquals("cyberpunk", prefs.themeConfig.value.preset)
        assertEquals("#FF00AA", prefs.themeConfig.value.customColors["lock"])
        assertEquals(listOf("light.kitchen"), prefs.pinnedEntities.value.map { it.entityId })

        // Explicit empty list clears pinned entities while partial omit of serverUrl keeps savedUrl
        val clearRes = handler.handleSave(
            """{"pin":"123456","pinnedEntities":[]}"""
        )
        assertEquals(200, clearRes.statusCode)
        assertTrue(prefs.pinnedEntities.value.isEmpty())
        assertEquals("http://192.168.1.50:8123", prefs.serverConfig.value.serverUrl)
        assertEquals("saved-secret-token", prefs.serverConfig.value.accessToken)
    }

    @Test
    fun `backup and restore round-trip settings and enforce PIN`() = runTest {
        val prefs = createPrefs(
            serverUrl = "http://192.168.1.50:8123",
            fallbackServerUrl = "https://fallback.example.com",
            accessToken = "saved-secret-token",
            pairingPin = "123456"
        )
        prefs.updateDockConfig { it.copy(layout = DockConfig.LAYOUT_RIGHT) }
        prefs.savePinnedEntities(
            listOf(PinnedEntityConfig(entityId = "lock.front_door", customName = "Front Door", order = 0))
        )
        val handler = SetupPortalHandler(
            preferencesRepository = prefs,
            fetchEntities = { _, _ -> PortalFetchResult.Success("[]") }
        )

        // Unauthenticated backup is rejected
        assertEquals(403, handler.handleBackup(pin = "wrong").statusCode)

        // Authenticated backup excludes token by default
        val backupRes = handler.handleBackup(pin = "123456", includeToken = false)
        assertEquals(200, backupRes.statusCode)
        assertEquals(
            "attachment; filename=\"homekey_tv_backup.json\"",
            backupRes.headers["Content-Disposition"]
        )
        assertFalse(backupRes.body.contains("saved-secret-token"))

        // Mutate state, then restore from backup JSON via wrapper payload
        prefs.updateDockConfig { it.copy(layout = DockConfig.LAYOUT_BOTTOM) }
        prefs.savePinnedEntities(emptyList())

        val restoreRes = handler.handleRestore(
            postData = """{"pin":"123456","backup":${backupRes.body}}"""
        )
        assertEquals(200, restoreRes.statusCode)
        assertEquals(DockConfig.LAYOUT_RIGHT, prefs.dockConfig.value.layout)
        assertEquals(listOf("lock.front_door"), prefs.pinnedEntities.value.map { it.entityId })
        assertEquals("saved-secret-token", prefs.serverConfig.value.accessToken)

        // Malformed backup payload returns 400
        val badRestore = handler.handleRestore(
            postData = "{not-valid-backup",
            headerPin = "123456"
        )
        assertEquals(400, badRestore.statusCode)
    }

    @Test
    fun `status endpoint returns structured configuration summary`() = runTest {
        val prefs = createPrefs(
            serverUrl = "http://192.168.1.50:8123",
            fallbackServerUrl = "https://fallback.example.com",
            accessToken = "saved-secret-token"
        )
        prefs.updateDockConfig { it.copy(layout = DockConfig.LAYOUT_LEFT) }
        prefs.updateThemeConfig { it.copy(preset = "nordic") }
        prefs.savePinnedEntities(listOf(PinnedEntityConfig(entityId = "light.one", order = 0)))

        val handler = SetupPortalHandler(
            preferencesRepository = prefs,
            fetchEntities = { _, _ -> PortalFetchResult.Success("[]") }
        )

        val statusRes = handler.handleStatus()
        assertEquals(200, statusRes.statusCode)
        val obj = json.parseToJsonElement(statusRes.body).jsonObject
        assertEquals("true", obj["configured"]?.jsonPrimitive?.content)
        assertEquals("http://192.168.1.50:8123", obj["serverUrl"]?.jsonPrimitive?.content)
        assertEquals("https://fallback.example.com", obj["fallbackServerUrl"]?.jsonPrimitive?.content)
        assertEquals("1", obj["pinnedCount"]?.jsonPrimitive?.content)
        assertEquals(DockConfig.LAYOUT_LEFT, obj["layout"]?.jsonPrimitive?.content)
        assertEquals("nordic", obj["themePreset"]?.jsonPrimitive?.content)
        assertNull(obj["accessToken"])
    }

    @Test
    fun `ThemeModels catalog JSON includes lock domain across metadata and all presets`() {
        val catalogJson = toPortalCatalogJson()
        val catalog = json.decodeFromString<ThemePortalCatalog>(catalogJson)

        val domainKeys = catalog.domains.map { it.key }
        assertEquals(
            listOf(
                "light",
                "switch",
                "climate",
                "camera",
                "media_player",
                "cover",
                "fan",
                "lock",
                "vacuum",
                "scene",
                "sensor",
                "off_background",
                "icon_color"
            ),
            domainKeys
        )
        assertEquals("Lock Control", catalog.domains.first { it.key == "lock" }.label)

        for (preset in ThemePreset.entries) {
            val portalPreset = catalog.presets[preset.id]
            assertTrue("Missing preset ${preset.id}", portalPreset != null)
            if (preset != ThemePreset.CUSTOM) {
                assertTrue(
                    "Preset ${preset.id} missing lock hex color",
                    !portalPreset!!.colors["lock"].isNullOrBlank()
                )
            }
        }
    }

    @Test
    fun `renderPortalHtml hydrates catalog and state without leaking accessToken or pairingPin`() = runTest {
        val prefs = createPrefs(
            serverUrl = "http://192.168.1.50:8123",
            fallbackServerUrl = "https://cloud.example.com",
            accessToken = "top-secret-ha-token",
            pairingPin = "987654"
        )
        prefs.updateDockConfig { it.copy(layout = DockConfig.LAYOUT_RIGHT) }
        prefs.updateThemeConfig {
            ThemeConfig(preset = "custom", customColors = mapOf("lock" to "#112233"))
        }
        prefs.savePinnedEntities(
            listOf(PinnedEntityConfig(entityId = "lock.garage", customName = "Garage Lock", order = 0))
        )

        val handler = SetupPortalHandler(
            preferencesRepository = prefs,
            fetchEntities = { _, _ -> PortalFetchResult.Success("[]") }
        )

        val assetFile = listOf(
            File("app/src/main/assets/setup_portal.html"),
            File("src/main/assets/setup_portal.html")
        ).first { it.isFile }
        val rendered = handler.renderPortalHtml(assetFile.readText())

        assertFalse(rendered.contains(SetupPortalHandler.THEME_CATALOG_PLACEHOLDER))
        assertFalse(rendered.contains(SetupPortalHandler.PORTAL_STATE_PLACEHOLDER))
        assertFalse(rendered.contains("top-secret-ha-token"))
        assertFalse(rendered.contains("987654"))
        assertTrue(rendered.contains("Lock Control"))
        assertTrue(rendered.contains("http://192.168.1.50:8123"))
        assertTrue(rendered.contains("https://cloud.example.com"))
        assertTrue(rendered.contains("lock.garage"))
        assertTrue(rendered.contains("#112233"))
        assertTrue(rendered.contains("\"isTokenSaved\":true"))
    }
}
