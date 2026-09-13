package com.homekey.tv

import com.homekey.tv.data.models.HAEntityState
import com.homekey.tv.data.models.OptimisticToggle
import androidx.compose.material.icons.filled.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HAModelTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test
    fun testLightEntityStateParsing() {
        val jsonString = """
            {
                "entity_id": "light.living_room_light",
                "state": "on",
                "attributes": {
                    "friendly_name": "Living Room Ceiling",
                    "brightness": 204,
                    "supported_features": 44
                },
                "last_changed": "2026-09-01T20:00:00Z"
            }
        """.trimIndent()

        val entity = json.decodeFromString<HAEntityState>(jsonString)
        assertEquals("light.living_room_light", entity.entityId)
        assertEquals("light", entity.domain)
        assertEquals("Living Room Ceiling", entity.friendlyName)
        assertEquals(204, entity.brightness)
        assertTrue(entity.isOn)
    }

    @Test
    fun testClimateEntityStateParsing() {
        val jsonString = """
            {
                "entity_id": "climate.living_room_thermostat",
                "state": "heat",
                "attributes": {
                    "friendly_name": "Living Room AC",
                    "current_temperature": 22.5,
                    "temperature": 24.0,
                    "hvac_modes": ["heat", "cool", "auto", "off"],
                    "hvac_action": "heating",
                    "current_humidity": 45,
                    "fan_modes": ["auto", "low", "high"],
                    "fan_mode": "auto",
                    "preset_modes": ["none", "eco", "boost"],
                    "preset_mode": "eco",
                    "min_temp": 16.0,
                    "max_temp": 30.0,
                    "target_temp_step": 0.5
                }
            }
        """.trimIndent()

        val entity = json.decodeFromString<HAEntityState>(jsonString)
        assertEquals("climate", entity.domain)
        assertEquals(22.5f, entity.currentTemperature ?: 0f, 0.01f)
        assertEquals(24.0f, entity.targetTemperature ?: 0f, 0.01f)
        assertEquals("heating", entity.hvacAction)
        assertEquals(45f, entity.currentHumidity ?: 0f, 0.01f)
        assertEquals(listOf("auto", "low", "high"), entity.fanModes)
        assertEquals("auto", entity.fanMode)
        assertEquals(listOf("none", "eco", "boost"), entity.presetModes)
        assertEquals("eco", entity.presetMode)
        assertEquals(16.0f, entity.minTemp, 0.01f)
        assertEquals(30.0f, entity.maxTemp, 0.01f)
        assertEquals(0.5f, entity.targetTempStep, 0.01f)
        assertTrue(entity.isOn)
    }

    @Test
    fun testButtonRemapConfigSerialization() {
        val config = com.homekey.tv.data.models.ButtonRemapConfig(
            keyCode = 278,
            keyName = "Custom Star Button",
            singlePressAction = com.homekey.tv.data.models.RemapAction("OPEN_DOCK"),
            doublePressAction = com.homekey.tv.data.models.RemapAction("TOGGLE_ENTITY", "light.ambient"),
            longPressAction = com.homekey.tv.data.models.RemapAction("LAUNCH_APP", "com.limelight")
        )
        val serialized = json.encodeToString(com.homekey.tv.data.models.ButtonRemapConfig.serializer(), config)
        val decoded = json.decodeFromString<com.homekey.tv.data.models.ButtonRemapConfig>(serialized)

        assertEquals(278, decoded.keyCode)
        assertEquals("Custom Star Button", decoded.keyName)
        assertEquals("OPEN_DOCK", decoded.singlePressAction?.type)
        assertEquals("light.ambient", decoded.doublePressAction?.target)
        assertEquals("com.limelight", decoded.longPressAction?.target)
    }

    @Test
    fun testPinnedAppConfigSerialization() {
        val app = com.homekey.tv.data.models.PinnedAppConfig(
            packageName = "com.limelight",
            appName = "Moonlight",
            order = 1
        )
        val serialized = json.encodeToString(com.homekey.tv.data.models.PinnedAppConfig.serializer(), app)
        val decoded = json.decodeFromString<com.homekey.tv.data.models.PinnedAppConfig>(serialized)

        assertEquals("com.limelight", decoded.packageName)
        assertEquals("Moonlight", decoded.appName)
        assertEquals(1, decoded.order)
    }

    @Test
    fun testLoadingAndUnavailableStatesAreNotOn() {
        // Pre-state-change placeholders ("loading") and dead entities must never render as ON.
        val loading = json.decodeFromString<HAEntityState>("""{"entity_id":"switch.test","state":"loading","attributes":{}}""")
        val unavailable = json.decodeFromString<HAEntityState>("""{"entity_id":"switch.test","state":"unavailable","attributes":{}}""")
        val unknown = json.decodeFromString<HAEntityState>("""{"entity_id":"switch.test","state":"unknown","attributes":{}}""")
        assertFalse(loading.isOn)
        assertFalse(unavailable.isOn)
        assertFalse(unknown.isOn)
    }

    @Test
    fun testOptimisticToggleSkipsTriggerAndMediaDomains() {
        // One-shot triggers and media play/pause must NOT optimistically flip on/off: HA will never
        // hold a scene/script/button in an "off" state, and media toggles play, not power.
        assertNull(OptimisticToggle.nextStateAfterToggle("scene", "on"))
        assertNull(OptimisticToggle.nextStateAfterToggle("scene", "off"))
        assertNull(OptimisticToggle.nextStateAfterToggle("script", "on"))
        assertNull(OptimisticToggle.nextStateAfterToggle("button", "unknown"))
        assertNull(OptimisticToggle.nextStateAfterToggle("input_button", "off"))
        assertNull(OptimisticToggle.nextStateAfterToggle("media_player", "playing"))
        assertNull(OptimisticToggle.nextStateAfterToggle("media_player", "off"))
    }

    @Test
    fun testOptimisticToggleFlipsRealOnOffDomains() {
        assertEquals("off", OptimisticToggle.nextStateAfterToggle("light", "on"))
        assertEquals("on", OptimisticToggle.nextStateAfterToggle("light", "off"))
        assertEquals("on", OptimisticToggle.nextStateAfterToggle("switch", null))
        assertEquals("closed", OptimisticToggle.nextStateAfterToggle("cover", "open"))
        assertEquals("open", OptimisticToggle.nextStateAfterToggle("cover", "closed"))
        assertEquals("unlocked", OptimisticToggle.nextStateAfterToggle("lock", "locked"))
        assertEquals("locked", OptimisticToggle.nextStateAfterToggle("lock", "unlocked"))
        assertEquals("docked", OptimisticToggle.nextStateAfterToggle("vacuum", "cleaning"))
        assertEquals("cleaning", OptimisticToggle.nextStateAfterToggle("vacuum", "docked"))
        // Climate only flips to "off"; it never guesses which mode to resume on.
        assertEquals("off", OptimisticToggle.nextStateAfterToggle("climate", "heat"))
        assertNull(OptimisticToggle.nextStateAfterToggle("climate", "off"))
        assertNull(OptimisticToggle.nextStateAfterToggle("climate", null))
    }

    @Test
    fun testUpdateManagerVersionComparison() {
        // Newer major version
        assertTrue(com.homekey.tv.data.api.UpdateManager.isNewerVersion("v2.0.0", "1.1.0"))
        // Newer minor version
        assertTrue(com.homekey.tv.data.api.UpdateManager.isNewerVersion("v1.2.0", "1.1.0"))
        // Newer patch version
        assertTrue(com.homekey.tv.data.api.UpdateManager.isNewerVersion("v1.1.1", "1.1.0"))
        // Same version with prefix
        assertFalse(com.homekey.tv.data.api.UpdateManager.isNewerVersion("v1.1.0", "1.1.0"))
        // Same version without prefix
        assertFalse(com.homekey.tv.data.api.UpdateManager.isNewerVersion("1.1.0", "1.1.0"))
        // Older versions
        assertFalse(com.homekey.tv.data.api.UpdateManager.isNewerVersion("v1.0.9", "1.1.0"))
        assertFalse(com.homekey.tv.data.api.UpdateManager.isNewerVersion("v0.9.0", "1.1.0"))
        // Pre-release tag stripping
        assertTrue(com.homekey.tv.data.api.UpdateManager.isNewerVersion("v1.2.0-beta1", "1.1.0"))
        assertFalse(com.homekey.tv.data.api.UpdateManager.isNewerVersion("v1.1.0-rc1", "1.1.0"))

        // Comparisons against 1.1.1
        assertTrue(com.homekey.tv.data.api.UpdateManager.isNewerVersion("v1.1.2", "1.1.1"))
        assertTrue(com.homekey.tv.data.api.UpdateManager.isNewerVersion("v1.2.0", "1.1.1"))
        assertFalse(com.homekey.tv.data.api.UpdateManager.isNewerVersion("v1.1.1", "1.1.1"))
        assertFalse(com.homekey.tv.data.api.UpdateManager.isNewerVersion("v1.1.0", "1.1.1"))

        // Comparisons against 1.1.2
        assertTrue(com.homekey.tv.data.api.UpdateManager.isNewerVersion("v1.1.3", "1.1.2"))
        assertTrue(com.homekey.tv.data.api.UpdateManager.isNewerVersion("v1.2.0", "1.1.2"))
        assertFalse(com.homekey.tv.data.api.UpdateManager.isNewerVersion("v1.1.2", "1.1.2"))
        assertFalse(com.homekey.tv.data.api.UpdateManager.isNewerVersion("v1.1.1", "1.1.2"))

        // Comparisons against 1.1.3
        assertTrue(com.homekey.tv.data.api.UpdateManager.isNewerVersion("v1.1.4", "1.1.3"))
        assertTrue(com.homekey.tv.data.api.UpdateManager.isNewerVersion("v1.2.0", "1.1.3"))
        assertFalse(com.homekey.tv.data.api.UpdateManager.isNewerVersion("v1.1.3", "1.1.3"))
        assertFalse(com.homekey.tv.data.api.UpdateManager.isNewerVersion("v1.1.2", "1.1.3"))

        // Comparisons against 1.2.0
        assertTrue(com.homekey.tv.data.api.UpdateManager.isNewerVersion("v1.2.1", "1.2.0"))
        assertTrue(com.homekey.tv.data.api.UpdateManager.isNewerVersion("v1.3.0", "1.2.0"))
        assertTrue(com.homekey.tv.data.api.UpdateManager.isNewerVersion("v2.0.0", "1.2.0"))
        assertFalse(com.homekey.tv.data.api.UpdateManager.isNewerVersion("v1.2.0", "1.2.0"))
        assertFalse(com.homekey.tv.data.api.UpdateManager.isNewerVersion("v1.1.3", "1.2.0"))
        assertFalse(com.homekey.tv.data.api.UpdateManager.isNewerVersion("v1.1.0", "1.2.0"))

        // Comparisons against 1.2.1
        assertTrue(com.homekey.tv.data.api.UpdateManager.isNewerVersion("v1.2.2", "1.2.1"))
        assertTrue(com.homekey.tv.data.api.UpdateManager.isNewerVersion("v1.3.0", "1.2.1"))
        assertFalse(com.homekey.tv.data.api.UpdateManager.isNewerVersion("v1.2.1", "1.2.1"))
        assertFalse(com.homekey.tv.data.api.UpdateManager.isNewerVersion("v1.2.0", "1.2.1"))

        // Comparisons against 1.2.2
        assertTrue(com.homekey.tv.data.api.UpdateManager.isNewerVersion("v1.2.3", "1.2.2"))
        assertTrue(com.homekey.tv.data.api.UpdateManager.isNewerVersion("v1.3.0", "1.2.2"))
        assertFalse(com.homekey.tv.data.api.UpdateManager.isNewerVersion("v1.2.2", "1.2.2"))
        assertFalse(com.homekey.tv.data.api.UpdateManager.isNewerVersion("v1.2.1", "1.2.2"))

        // Comparisons against 1.3.0
        assertTrue(com.homekey.tv.data.api.UpdateManager.isNewerVersion("v1.3.1", "1.3.0"))
        assertTrue(com.homekey.tv.data.api.UpdateManager.isNewerVersion("v2.0.0", "1.3.0"))
        assertFalse(com.homekey.tv.data.api.UpdateManager.isNewerVersion("v1.3.0", "1.3.0"))
        assertFalse(com.homekey.tv.data.api.UpdateManager.isNewerVersion("v1.2.2", "1.3.0"))

        // Comparisons against 1.4.0
        assertTrue(com.homekey.tv.data.api.UpdateManager.isNewerVersion("v1.4.1", "1.4.0"))
        assertTrue(com.homekey.tv.data.api.UpdateManager.isNewerVersion("v2.0.0", "1.4.0"))
        assertFalse(com.homekey.tv.data.api.UpdateManager.isNewerVersion("v1.4.0", "1.4.0"))
        assertFalse(com.homekey.tv.data.api.UpdateManager.isNewerVersion("v1.3.0", "1.4.0"))

        // Comparisons against 1.4.1
        assertTrue(com.homekey.tv.data.api.UpdateManager.isNewerVersion("v1.4.2", "1.4.1"))
        assertFalse(com.homekey.tv.data.api.UpdateManager.isNewerVersion("v1.4.1", "1.4.1"))
        assertFalse(com.homekey.tv.data.api.UpdateManager.isNewerVersion("v1.4.0", "1.4.1"))

        // Comparisons against 1.4.2
        assertTrue(com.homekey.tv.data.api.UpdateManager.isNewerVersion("v1.4.3", "1.4.2"))
        assertTrue(com.homekey.tv.data.api.UpdateManager.isNewerVersion("v1.5.0", "1.4.2"))
        assertFalse(com.homekey.tv.data.api.UpdateManager.isNewerVersion("v1.4.2", "1.4.2"))
        assertFalse(com.homekey.tv.data.api.UpdateManager.isNewerVersion("v1.4.1", "1.4.2"))
    }

    @Test
    fun testSupportsBrightness() {
        val dimmableJson = """
            {
                "entity_id": "light.dimmable_lamp",
                "state": "on",
                "attributes": {
                    "friendly_name": "Dimmable Lamp",
                    "supported_color_modes": ["brightness"]
                }
            }
        """.trimIndent()
        val dimmable = json.decodeFromString<HAEntityState>(dimmableJson)
        assertTrue(dimmable.supportsBrightness)

        val onOffJson = """
            {
                "entity_id": "light.on_off_bulb",
                "state": "on",
                "attributes": {
                    "friendly_name": "On Off Bulb",
                    "supported_color_modes": ["onoff"]
                }
            }
        """.trimIndent()
        val onOff = json.decodeFromString<HAEntityState>(onOffJson)
        assertFalse(onOff.supportsBrightness)

        val switchJson = """
            {
                "entity_id": "switch.coffee_maker",
                "state": "on",
                "attributes": {
                    "friendly_name": "Coffee Maker"
                }
            }
        """.trimIndent()
        val switchEntity = json.decodeFromString<HAEntityState>(switchJson)
        assertFalse(switchEntity.supportsBrightness)

        val lightWithBrightnessAttr = """
            {
                "entity_id": "light.mystery_light",
                "state": "on",
                "attributes": {
                    "friendly_name": "Mystery",
                    "brightness": 128
                }
            }
        """.trimIndent()
        val withBrightness = json.decodeFromString<HAEntityState>(lightWithBrightnessAttr)
        assertTrue(withBrightness.supportsBrightness)
    }

    @Test
    fun testMediaPlayerEntityStateParsing() {
        val jsonString = """
            {
                "entity_id": "media_player.living_room_tv",
                "state": "playing",
                "attributes": {
                    "friendly_name": "Living Room TV",
                    "media_title": "Interstellar",
                    "media_artist": "Hans Zimmer",
                    "source": "HDMI 1",
                    "source_list": ["TV", "HDMI 1", "HDMI 2", "Apple TV"],
                    "volume_level": 0.45,
                    "is_volume_muted": false
                }
            }
        """.trimIndent()

        val entity = json.decodeFromString<HAEntityState>(jsonString)
        assertEquals("media_player.living_room_tv", entity.entityId)
        assertEquals("media_player", entity.domain)
        assertEquals("Living Room TV", entity.friendlyName)
        assertEquals("Interstellar", entity.mediaTitle)
        assertEquals("Hans Zimmer", entity.mediaArtist)
        assertEquals("HDMI 1", entity.source)
        assertEquals(listOf("TV", "HDMI 1", "HDMI 2", "Apple TV"), entity.sourceList)
        assertEquals(0.45f, entity.volumeLevel ?: 0f, 0.01f)
        assertFalse(entity.isVolumeMuted)
        assertTrue(entity.isPlaying)
    }

    @Test
    fun testButtonRemapConfigWithExtraSerialization() {
        val config = com.homekey.tv.data.models.ButtonRemapConfig(
            keyCode = 225,
            keyName = "Live TV",
            singlePressAction = com.homekey.tv.data.models.RemapAction("SELECT_SOURCE", "media_player.living_room_tv", extra = "TV"),
            doublePressAction = com.homekey.tv.data.models.RemapAction("SELECT_SOURCE", "media_player.living_room_tv", extra = "HDMI 1"),
            longPressAction = com.homekey.tv.data.models.RemapAction("OPEN_DOCK")
        )
        val serialized = json.encodeToString(com.homekey.tv.data.models.ButtonRemapConfig.serializer(), config)
        val decoded = json.decodeFromString<com.homekey.tv.data.models.ButtonRemapConfig>(serialized)

        assertEquals(225, decoded.keyCode)
        assertEquals("Live TV", decoded.keyName)
        assertEquals("SELECT_SOURCE", decoded.singlePressAction?.type)
        assertEquals("media_player.living_room_tv", decoded.singlePressAction?.target)
        assertEquals("TV", decoded.singlePressAction?.extra)
        assertEquals("HDMI 1", decoded.doublePressAction?.extra)
        assertNull(decoded.longPressAction?.extra)
    }

    @Test
    fun testButtonRemapConfigBackwardCompatibility() {
        // Test JSON without "extra" field parses properly with extra = null
        val legacyJson = """
            {
                "keyCode": 313,
                "keyName": "Star Button",
                "singlePressAction": {
                    "type": "OPEN_DOCK"
                },
                "doublePressAction": {
                    "type": "TOGGLE_ENTITY",
                    "target": "light.living_room"
                }
            }
        """.trimIndent()

        val decoded = json.decodeFromString<com.homekey.tv.data.models.ButtonRemapConfig>(legacyJson)
        assertEquals(313, decoded.keyCode)
        assertEquals("Star Button", decoded.keyName)
        assertEquals("OPEN_DOCK", decoded.singlePressAction?.type)
        assertNull(decoded.singlePressAction?.extra)
        assertEquals("light.living_room", decoded.doublePressAction?.target)
        assertNull(decoded.doublePressAction?.extra)
        assertNull(decoded.longPressAction)
    }

    @Test
    fun testSingleActionVsMultiActionDetection() {
        fun isSingleActionOnly(config: com.homekey.tv.data.models.ButtonRemapConfig): Boolean {
            return config.singlePressAction != null &&
                config.doublePressAction == null &&
                config.longPressAction == null
        }

        val singleOnly = com.homekey.tv.data.models.ButtonRemapConfig(
            keyCode = 313,
            keyName = "Star",
            singlePressAction = com.homekey.tv.data.models.RemapAction("OPEN_DOCK")
        )
        assertTrue(isSingleActionOnly(singleOnly))

        val withDouble = singleOnly.copy(
            doublePressAction = com.homekey.tv.data.models.RemapAction("TOGGLE_ENTITY")
        )
        assertFalse(isSingleActionOnly(withDouble))

        val withLong = singleOnly.copy(
            longPressAction = com.homekey.tv.data.models.RemapAction("CALL_SERVICE")
        )
        assertFalse(isSingleActionOnly(withLong))

        val noneConfigured = singleOnly.copy(
            singlePressAction = null
        )
        assertFalse(isSingleActionOnly(noneConfigured))
    }

    @Test
    fun testCameraEntityParsing() {
        val cameraJson = """
            {
                "entity_id": "camera.front_door",
                "state": "idle",
                "attributes": {
                    "friendly_name": "Front Door Camera",
                    "access_token": "secret_token_123",
                    "frontend_stream_type": "hls"
                }
            }
        """.trimIndent()
        val camera = json.decodeFromString<HAEntityState>(cameraJson)
        assertEquals("camera", camera.domain)
        assertEquals("Front Door Camera", camera.friendlyName)
        assertEquals("idle", camera.state)

        val serverUrl = "http://192.168.1.100:8123/"
        val cleanUrl = serverUrl.trim().removeSuffix("/")
        val streamUrl = "$cleanUrl/api/camera_proxy_stream/${camera.entityId}"
        val snapshotUrl = "$cleanUrl/api/camera_proxy/${camera.entityId}"
        assertEquals("http://192.168.1.100:8123/api/camera_proxy_stream/camera.front_door", streamUrl)
        assertEquals("http://192.168.1.100:8123/api/camera_proxy/camera.front_door", snapshotUrl)
    }

    @Test
    fun testPopupStylePreferenceNormalization() {
        // Control popup styles
        assertEquals(
            com.homekey.tv.data.local.PreferencesManager.POPUP_STYLE_MINIMAL,
            com.homekey.tv.data.local.PreferencesManager.normalizePopupStyle("MINIMAL")
        )
        assertEquals(
            com.homekey.tv.data.local.PreferencesManager.POPUP_STYLE_MINIMAL,
            com.homekey.tv.data.local.PreferencesManager.normalizePopupStyle("horizontal")
        )
        assertEquals(
            com.homekey.tv.data.local.PreferencesManager.POPUP_STYLE_MINIMAL,
            com.homekey.tv.data.local.PreferencesManager.normalizePopupStyle("MINIMAL_DOCK")
        )
        assertEquals(
            com.homekey.tv.data.local.PreferencesManager.POPUP_STYLE_MINIMAL,
            com.homekey.tv.data.local.PreferencesManager.normalizePopupStyle("COMPACT")
        )
        assertEquals(
            com.homekey.tv.data.local.PreferencesManager.POPUP_STYLE_MINIMAL,
            com.homekey.tv.data.local.PreferencesManager.normalizePopupStyle("dock")
        )
        assertEquals(
            com.homekey.tv.data.local.PreferencesManager.POPUP_STYLE_CENTERED,
            com.homekey.tv.data.local.PreferencesManager.normalizePopupStyle("CENTERED")
        )
        assertEquals(
            com.homekey.tv.data.local.PreferencesManager.POPUP_STYLE_CENTERED,
            com.homekey.tv.data.local.PreferencesManager.normalizePopupStyle(null)
        )
        assertEquals(
            com.homekey.tv.data.local.PreferencesManager.POPUP_STYLE_CENTERED,
            com.homekey.tv.data.local.PreferencesManager.normalizePopupStyle("unknown_style")
        )

        // Camera popup styles
        assertEquals(
            com.homekey.tv.data.local.PreferencesManager.CAMERA_POPUP_COMPACT,
            com.homekey.tv.data.local.PreferencesManager.normalizeCameraPopupStyle("COMPACT")
        )
        assertEquals(
            com.homekey.tv.data.local.PreferencesManager.CAMERA_POPUP_COMPACT,
            com.homekey.tv.data.local.PreferencesManager.normalizeCameraPopupStyle("minimal")
        )
        assertEquals(
            com.homekey.tv.data.local.PreferencesManager.CAMERA_POPUP_COMPACT,
            com.homekey.tv.data.local.PreferencesManager.normalizeCameraPopupStyle("DOCK")
        )
        assertEquals(
            com.homekey.tv.data.local.PreferencesManager.CAMERA_POPUP_CENTERED,
            com.homekey.tv.data.local.PreferencesManager.normalizeCameraPopupStyle("CENTERED")
        )
        assertEquals(
            com.homekey.tv.data.local.PreferencesManager.CAMERA_POPUP_CENTERED,
            com.homekey.tv.data.local.PreferencesManager.normalizeCameraPopupStyle(null)
        )
        assertEquals(
            com.homekey.tv.data.local.PreferencesManager.CAMERA_POPUP_CENTERED,
            com.homekey.tv.data.local.PreferencesManager.normalizeCameraPopupStyle("random_value")
        )

        // Camera compact sizes
        assertEquals(
            com.homekey.tv.data.local.PreferencesManager.CAMERA_COMPACT_SIZE_SMALL,
            com.homekey.tv.data.local.PreferencesManager.normalizeCameraCompactSize("SMALL")
        )
        assertEquals(
            com.homekey.tv.data.local.PreferencesManager.CAMERA_COMPACT_SIZE_SMALL,
            com.homekey.tv.data.local.PreferencesManager.normalizeCameraCompactSize("s")
        )
        assertEquals(
            com.homekey.tv.data.local.PreferencesManager.CAMERA_COMPACT_SIZE_SMALL,
            com.homekey.tv.data.local.PreferencesManager.normalizeCameraCompactSize("small_size")
        )
        assertEquals(
            com.homekey.tv.data.local.PreferencesManager.CAMERA_COMPACT_SIZE_LARGE,
            com.homekey.tv.data.local.PreferencesManager.normalizeCameraCompactSize("LARGE")
        )
        assertEquals(
            com.homekey.tv.data.local.PreferencesManager.CAMERA_COMPACT_SIZE_LARGE,
            com.homekey.tv.data.local.PreferencesManager.normalizeCameraCompactSize("l")
        )
        assertEquals(
            com.homekey.tv.data.local.PreferencesManager.CAMERA_COMPACT_SIZE_LARGE,
            com.homekey.tv.data.local.PreferencesManager.normalizeCameraCompactSize("large_size")
        )
        assertEquals(
            com.homekey.tv.data.local.PreferencesManager.CAMERA_COMPACT_SIZE_MEDIUM,
            com.homekey.tv.data.local.PreferencesManager.normalizeCameraCompactSize("MEDIUM")
        )
        assertEquals(
            com.homekey.tv.data.local.PreferencesManager.CAMERA_COMPACT_SIZE_MEDIUM,
            com.homekey.tv.data.local.PreferencesManager.normalizeCameraCompactSize(null)
        )
        assertEquals(
            com.homekey.tv.data.local.PreferencesManager.CAMERA_COMPACT_SIZE_MEDIUM,
            com.homekey.tv.data.local.PreferencesManager.normalizeCameraCompactSize("arbitrary")
        )
    }

    @Test
    fun testRecentAppsConfigurationAndSerialization() {
        val testApps = listOf(
            "com.google.android.youtube.tv",
            "com.netflix.ninja",
            "com.amazon.amazonvideo.livingroom"
        )
        val serialized = json.encodeToString<List<String>>(testApps)
        val decoded = json.decodeFromString<List<String>>(serialized)

        assertEquals(3, decoded.size)
        assertEquals("com.google.android.youtube.tv", decoded[0])
        assertEquals("com.netflix.ninja", decoded[1])
        assertEquals("com.amazon.amazonvideo.livingroom", decoded[2])

        // Verify count clamping bounds (1..5)
        fun clampCount(input: Int): Int = input.coerceIn(1, 5)
        assertEquals(1, clampCount(0))
        assertEquals(1, clampCount(1))
        assertEquals(3, clampCount(3))
        assertEquals(5, clampCount(5))
        assertEquals(5, clampCount(10))
    }

    @Test
    fun testEmptyPinnedYieldsEmptyDockItems() {
        fun buildDockItemsMock(
            entities: Map<String, HAEntityState>,
            pinned: List<com.homekey.tv.data.models.PinnedEntityConfig>,
            apps: List<com.homekey.tv.data.models.PinnedAppConfig>
        ): List<String> {
            val result = mutableListOf<String>()
            if (pinned.isNotEmpty() || apps.isNotEmpty()) {
                for (p in pinned.sortedBy { it.order }) {
                    val entity = entities[p.entityId] ?: HAEntityState(entityId = p.entityId, state = "loading")
                    result.add(entity.entityId)
                }
                for (app in apps.sortedBy { it.order }) {
                    result.add("app:${app.packageName}")
                }
            }
            return result
        }

        val entities = (1..20).associate {
            "light.lamp_$it" to json.decodeFromString<HAEntityState>(
                """{"entity_id":"light.lamp_$it","state":"on","attributes":{"friendly_name":"Lamp $it"}}"""
            )
        }

        // When nothing is pinned, dock items list MUST be empty (no automatic 12-item fallback)
        val emptyDock = buildDockItemsMock(entities, emptyList(), emptyList())
        assertTrue(emptyDock.isEmpty())

        // When items are pinned, only pinned items are returned
        val pinned = listOf(
            com.homekey.tv.data.models.PinnedEntityConfig(entityId = "light.lamp_5", order = 0)
        )
        val singlePinnedDock = buildDockItemsMock(entities, pinned, emptyList())
        assertEquals(1, singlePinnedDock.size)
        assertEquals("light.lamp_5", singlePinnedDock[0])
    }

    @Test
    fun testDockItemsWithHaDisabled() {
        fun buildDockItemsMock(
            entities: Map<String, HAEntityState>,
            pinned: List<com.homekey.tv.data.models.PinnedEntityConfig>,
            apps: List<com.homekey.tv.data.models.PinnedAppConfig>,
            haEnabled: Boolean
        ): List<String> {
            val result = mutableListOf<String>()
            val effectivePinned = if (haEnabled) pinned else emptyList()
            if (effectivePinned.isNotEmpty() || apps.isNotEmpty()) {
                for (p in effectivePinned.sortedBy { it.order }) {
                    val entity = entities[p.entityId] ?: HAEntityState(entityId = p.entityId, state = "loading")
                    result.add(entity.entityId)
                }
                for (app in apps.sortedBy { it.order }) {
                    result.add("app:${app.packageName}")
                }
            }
            return result
        }

        val entities = mapOf(
            "light.living_room" to HAEntityState(entityId = "light.living_room", state = "on")
        )
        val pinned = listOf(
            com.homekey.tv.data.models.PinnedEntityConfig(entityId = "light.living_room", order = 0)
        )
        val apps = listOf(
            com.homekey.tv.data.models.PinnedAppConfig(packageName = "com.google.android.youtube.tv", appName = "YouTube", order = 0)
        )

        // When HA is enabled, both HA entity and app are present
        val dockHaEnabled = buildDockItemsMock(entities, pinned, apps, haEnabled = true)
        assertEquals(2, dockHaEnabled.size)
        assertEquals("light.living_room", dockHaEnabled[0])
        assertEquals("app:com.google.android.youtube.tv", dockHaEnabled[1])

        // When HA is disabled, HA entity is omitted and only app remains
        val dockHaDisabled = buildDockItemsMock(entities, pinned, apps, haEnabled = false)
        assertEquals(1, dockHaDisabled.size)
        assertEquals("app:com.google.android.youtube.tv", dockHaDisabled[0])
    }

    @Test
    fun testIconGalleryDockMapping() {
        val mappings = mapOf(
            // Lighting
            "lightbulb" to androidx.compose.material.icons.Icons.Default.Lightbulb,
            "lamp" to androidx.compose.material.icons.Icons.Default.WbIncandescent,
            "ceiling_light" to androidx.compose.material.icons.Icons.Default.Light,
            "strip" to androidx.compose.material.icons.Icons.Default.Fluorescent,
            "spotlight" to androidx.compose.material.icons.Icons.Default.FlashOn,
            "sunny" to androidx.compose.material.icons.Icons.Default.WbSunny,
            "nightlight" to androidx.compose.material.icons.Icons.Default.Nightlight,

            // Rooms & Furniture
            "sofa" to androidx.compose.material.icons.Icons.Default.Weekend,
            "bed" to androidx.compose.material.icons.Icons.Default.Bed,
            "chair" to androidx.compose.material.icons.Icons.Default.Chair,
            "table" to androidx.compose.material.icons.Icons.Default.TableRestaurant,
            "desk" to androidx.compose.material.icons.Icons.Default.Desk,
            "kitchen" to androidx.compose.material.icons.Icons.Default.Kitchen,
            "bath" to androidx.compose.material.icons.Icons.Default.Bathtub,
            "shower" to androidx.compose.material.icons.Icons.Default.Shower,

            // Climate & Air
            "ac" to androidx.compose.material.icons.Icons.Default.AcUnit,
            "fan" to androidx.compose.material.icons.Icons.Default.Air,
            "heater" to androidx.compose.material.icons.Icons.Default.Whatshot,
            "thermostat" to androidx.compose.material.icons.Icons.Default.Thermostat,
            "fireplace" to androidx.compose.material.icons.Icons.Default.Fireplace,

            // Doors, Windows & Covers
            "door" to androidx.compose.material.icons.Icons.Default.DoorFront,
            "door_sliding" to androidx.compose.material.icons.Icons.Default.DoorSliding,
            "garage" to androidx.compose.material.icons.Icons.Default.Garage,
            "window" to androidx.compose.material.icons.Icons.Default.Window,
            "curtains" to androidx.compose.material.icons.Icons.Default.Curtains,
            "blinds" to androidx.compose.material.icons.Icons.Default.Blinds,
            "roller_shade" to androidx.compose.material.icons.Icons.Default.RollerShades,

            // Security & Access
            "lock" to androidx.compose.material.icons.Icons.Default.Lock,
            "lock_open" to androidx.compose.material.icons.Icons.Default.LockOpen,
            "key" to androidx.compose.material.icons.Icons.Default.Key,
            "shield" to androidx.compose.material.icons.Icons.Default.Shield,
            "camera" to androidx.compose.material.icons.Icons.Default.Videocam,
            "doorbell" to androidx.compose.material.icons.Icons.Default.Doorbell,
            "alarm" to androidx.compose.material.icons.Icons.Default.Alarm,

            // Power & Energy
            "power" to androidx.compose.material.icons.Icons.Default.PowerSettingsNew,
            "socket" to androidx.compose.material.icons.Icons.Default.Outlet,
            "plug" to androidx.compose.material.icons.Icons.Default.Power,
            "bolt" to androidx.compose.material.icons.Icons.Default.Bolt,
            "battery" to androidx.compose.material.icons.Icons.Default.BatteryChargingFull,
            "solar" to androidx.compose.material.icons.Icons.Default.SolarPower,

            // Appliances
            "vacuum" to androidx.compose.material.icons.Icons.Default.SmartToy,
            "coffee" to androidx.compose.material.icons.Icons.Default.CoffeeMaker,
            "laundry" to androidx.compose.material.icons.Icons.Default.LocalLaundryService,
            "microwave" to androidx.compose.material.icons.Icons.Default.Microwave,
            "blender" to androidx.compose.material.icons.Icons.Default.Blender,
            "iron" to androidx.compose.material.icons.Icons.Default.Iron,
            "grill" to androidx.compose.material.icons.Icons.Default.OutdoorGrill,

            // Media & Audio
            "tv" to androidx.compose.material.icons.Icons.Default.Tv,
            "monitor" to androidx.compose.material.icons.Icons.Default.DesktopWindows,
            "laptop" to androidx.compose.material.icons.Icons.Default.Laptop,
            "smartphone" to androidx.compose.material.icons.Icons.Default.Smartphone,
            "tablet" to androidx.compose.material.icons.Icons.Default.Tablet,
            "speaker" to androidx.compose.material.icons.Icons.Default.Speaker,
            "speaker_group" to androidx.compose.material.icons.Icons.Default.SpeakerGroup,
            "headphones" to androidx.compose.material.icons.Icons.Default.Headphones,
            "music" to androidx.compose.material.icons.Icons.Default.MusicNote,
            "radio" to androidx.compose.material.icons.Icons.Default.Radio,
            "mic" to androidx.compose.material.icons.Icons.Default.Mic,
            "gamepad" to androidx.compose.material.icons.Icons.Default.SportsEsports,

            // Vehicles
            "car" to androidx.compose.material.icons.Icons.Default.DirectionsCar,
            "electric_car" to androidx.compose.material.icons.Icons.Default.ElectricCar,
            "ev_station" to androidx.compose.material.icons.Icons.Default.EvStation,
            "bike" to androidx.compose.material.icons.Icons.Default.PedalBike,
            "motorcycle" to androidx.compose.material.icons.Icons.Default.TwoWheeler,

            // Outdoor & Garden
            "yard" to androidx.compose.material.icons.Icons.Default.Yard,
            "balcony" to androidx.compose.material.icons.Icons.Default.Balcony,
            "pool" to androidx.compose.material.icons.Icons.Default.Pool,
            "deck" to androidx.compose.material.icons.Icons.Default.Deck,
            "fence" to androidx.compose.material.icons.Icons.Default.Fence,
            "hot_tub" to androidx.compose.material.icons.Icons.Default.HotTub,

            // Sensors & Automations
            "sensor" to androidx.compose.material.icons.Icons.Default.Sensors,
            "water" to androidx.compose.material.icons.Icons.Default.WaterDrop,
            "co2" to androidx.compose.material.icons.Icons.Default.Co2,
            "timer" to androidx.compose.material.icons.Icons.Default.Timer,
            "wifi" to androidx.compose.material.icons.Icons.Default.Wifi,
            "palette" to androidx.compose.material.icons.Icons.Default.Palette,
            "play" to androidx.compose.material.icons.Icons.Default.PlayArrow,
            "pets" to androidx.compose.material.icons.Icons.Default.Pets
        )

        assertEquals(78, mappings.size)

        for ((id, expectedIcon) in mappings) {
            val resolved = com.homekey.tv.ui.panel.resolveDockIcon(id, null, "custom_unknown_domain")
            assertEquals("Icon id '$id' must resolve to expected ${expectedIcon.name} but got ${resolved.name}", expectedIcon, resolved)
        }
    }
}
