package com.homeassistant.tv

import com.homeassistant.tv.data.models.HAEntityState
import com.homeassistant.tv.data.models.OptimisticToggle
import kotlinx.serialization.decodeFromString
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
        val config = com.homeassistant.tv.data.models.ButtonRemapConfig(
            keyCode = 278,
            keyName = "Custom Star Button",
            singlePressAction = com.homeassistant.tv.data.models.RemapAction("OPEN_DOCK"),
            doublePressAction = com.homeassistant.tv.data.models.RemapAction("TOGGLE_ENTITY", "light.ambient"),
            longPressAction = com.homeassistant.tv.data.models.RemapAction("LAUNCH_APP", "com.limelight")
        )
        val serialized = json.encodeToString(com.homeassistant.tv.data.models.ButtonRemapConfig.serializer(), config)
        val decoded = json.decodeFromString<com.homeassistant.tv.data.models.ButtonRemapConfig>(serialized)

        assertEquals(278, decoded.keyCode)
        assertEquals("Custom Star Button", decoded.keyName)
        assertEquals("OPEN_DOCK", decoded.singlePressAction?.type)
        assertEquals("light.ambient", decoded.doublePressAction?.target)
        assertEquals("com.limelight", decoded.longPressAction?.target)
    }

    @Test
    fun testPinnedAppConfigSerialization() {
        val app = com.homeassistant.tv.data.models.PinnedAppConfig(
            packageName = "com.limelight",
            appName = "Moonlight",
            order = 1
        )
        val serialized = json.encodeToString(com.homeassistant.tv.data.models.PinnedAppConfig.serializer(), app)
        val decoded = json.decodeFromString<com.homeassistant.tv.data.models.PinnedAppConfig>(serialized)

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
        assertTrue(com.homeassistant.tv.data.api.UpdateManager.isNewerVersion("v2.0.0", "1.1.0"))
        // Newer minor version
        assertTrue(com.homeassistant.tv.data.api.UpdateManager.isNewerVersion("v1.2.0", "1.1.0"))
        // Newer patch version
        assertTrue(com.homeassistant.tv.data.api.UpdateManager.isNewerVersion("v1.1.1", "1.1.0"))
        // Same version with prefix
        assertFalse(com.homeassistant.tv.data.api.UpdateManager.isNewerVersion("v1.1.0", "1.1.0"))
        // Same version without prefix
        assertFalse(com.homeassistant.tv.data.api.UpdateManager.isNewerVersion("1.1.0", "1.1.0"))
        // Older versions
        assertFalse(com.homeassistant.tv.data.api.UpdateManager.isNewerVersion("v1.0.9", "1.1.0"))
        assertFalse(com.homeassistant.tv.data.api.UpdateManager.isNewerVersion("v0.9.0", "1.1.0"))
        // Pre-release tag stripping
        assertTrue(com.homeassistant.tv.data.api.UpdateManager.isNewerVersion("v1.2.0-beta1", "1.1.0"))
        assertFalse(com.homeassistant.tv.data.api.UpdateManager.isNewerVersion("v1.1.0-rc1", "1.1.0"))

        // Comparisons against 1.1.1
        assertTrue(com.homeassistant.tv.data.api.UpdateManager.isNewerVersion("v1.1.2", "1.1.1"))
        assertTrue(com.homeassistant.tv.data.api.UpdateManager.isNewerVersion("v1.2.0", "1.1.1"))
        assertFalse(com.homeassistant.tv.data.api.UpdateManager.isNewerVersion("v1.1.1", "1.1.1"))
        assertFalse(com.homeassistant.tv.data.api.UpdateManager.isNewerVersion("v1.1.0", "1.1.1"))

        // Comparisons against 1.1.2
        assertTrue(com.homeassistant.tv.data.api.UpdateManager.isNewerVersion("v1.1.3", "1.1.2"))
        assertTrue(com.homeassistant.tv.data.api.UpdateManager.isNewerVersion("v1.2.0", "1.1.2"))
        assertFalse(com.homeassistant.tv.data.api.UpdateManager.isNewerVersion("v1.1.2", "1.1.2"))
        assertFalse(com.homeassistant.tv.data.api.UpdateManager.isNewerVersion("v1.1.1", "1.1.2"))

        // Comparisons against 1.1.3
        assertTrue(com.homeassistant.tv.data.api.UpdateManager.isNewerVersion("v1.1.4", "1.1.3"))
        assertTrue(com.homeassistant.tv.data.api.UpdateManager.isNewerVersion("v1.2.0", "1.1.3"))
        assertFalse(com.homeassistant.tv.data.api.UpdateManager.isNewerVersion("v1.1.3", "1.1.3"))
        assertFalse(com.homeassistant.tv.data.api.UpdateManager.isNewerVersion("v1.1.2", "1.1.3"))

        // Comparisons against 1.2.0
        assertTrue(com.homeassistant.tv.data.api.UpdateManager.isNewerVersion("v1.2.1", "1.2.0"))
        assertTrue(com.homeassistant.tv.data.api.UpdateManager.isNewerVersion("v1.3.0", "1.2.0"))
        assertTrue(com.homeassistant.tv.data.api.UpdateManager.isNewerVersion("v2.0.0", "1.2.0"))
        assertFalse(com.homeassistant.tv.data.api.UpdateManager.isNewerVersion("v1.2.0", "1.2.0"))
        assertFalse(com.homeassistant.tv.data.api.UpdateManager.isNewerVersion("v1.1.3", "1.2.0"))
        assertFalse(com.homeassistant.tv.data.api.UpdateManager.isNewerVersion("v1.1.0", "1.2.0"))

        // Comparisons against 1.2.1
        assertTrue(com.homeassistant.tv.data.api.UpdateManager.isNewerVersion("v1.2.2", "1.2.1"))
        assertTrue(com.homeassistant.tv.data.api.UpdateManager.isNewerVersion("v1.3.0", "1.2.1"))
        assertFalse(com.homeassistant.tv.data.api.UpdateManager.isNewerVersion("v1.2.1", "1.2.1"))
        assertFalse(com.homeassistant.tv.data.api.UpdateManager.isNewerVersion("v1.2.0", "1.2.1"))

        // Comparisons against 1.2.2
        assertTrue(com.homeassistant.tv.data.api.UpdateManager.isNewerVersion("v1.2.3", "1.2.2"))
        assertTrue(com.homeassistant.tv.data.api.UpdateManager.isNewerVersion("v1.3.0", "1.2.2"))
        assertFalse(com.homeassistant.tv.data.api.UpdateManager.isNewerVersion("v1.2.2", "1.2.2"))
        assertFalse(com.homeassistant.tv.data.api.UpdateManager.isNewerVersion("v1.2.1", "1.2.2"))

        // Comparisons against 1.3.0
        assertTrue(com.homeassistant.tv.data.api.UpdateManager.isNewerVersion("v1.3.1", "1.3.0"))
        assertTrue(com.homeassistant.tv.data.api.UpdateManager.isNewerVersion("v2.0.0", "1.3.0"))
        assertFalse(com.homeassistant.tv.data.api.UpdateManager.isNewerVersion("v1.3.0", "1.3.0"))
        assertFalse(com.homeassistant.tv.data.api.UpdateManager.isNewerVersion("v1.2.2", "1.3.0"))

        // Comparisons against 1.4.0
        assertTrue(com.homeassistant.tv.data.api.UpdateManager.isNewerVersion("v1.4.1", "1.4.0"))
        assertTrue(com.homeassistant.tv.data.api.UpdateManager.isNewerVersion("v2.0.0", "1.4.0"))
        assertFalse(com.homeassistant.tv.data.api.UpdateManager.isNewerVersion("v1.4.0", "1.4.0"))
        assertFalse(com.homeassistant.tv.data.api.UpdateManager.isNewerVersion("v1.3.0", "1.4.0"))

        // Comparisons against 1.4.1
        assertTrue(com.homeassistant.tv.data.api.UpdateManager.isNewerVersion("v1.4.2", "1.4.1"))
        assertFalse(com.homeassistant.tv.data.api.UpdateManager.isNewerVersion("v1.4.1", "1.4.1"))
        assertFalse(com.homeassistant.tv.data.api.UpdateManager.isNewerVersion("v1.4.0", "1.4.1"))

        // Comparisons against 1.4.2
        assertTrue(com.homeassistant.tv.data.api.UpdateManager.isNewerVersion("v1.4.3", "1.4.2"))
        assertTrue(com.homeassistant.tv.data.api.UpdateManager.isNewerVersion("v1.5.0", "1.4.2"))
        assertFalse(com.homeassistant.tv.data.api.UpdateManager.isNewerVersion("v1.4.2", "1.4.2"))
        assertFalse(com.homeassistant.tv.data.api.UpdateManager.isNewerVersion("v1.4.1", "1.4.2"))
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
        val config = com.homeassistant.tv.data.models.ButtonRemapConfig(
            keyCode = 225,
            keyName = "Live TV",
            singlePressAction = com.homeassistant.tv.data.models.RemapAction("SELECT_SOURCE", "media_player.living_room_tv", extra = "TV"),
            doublePressAction = com.homeassistant.tv.data.models.RemapAction("SELECT_SOURCE", "media_player.living_room_tv", extra = "HDMI 1"),
            longPressAction = com.homeassistant.tv.data.models.RemapAction("OPEN_DOCK")
        )
        val serialized = json.encodeToString(com.homeassistant.tv.data.models.ButtonRemapConfig.serializer(), config)
        val decoded = json.decodeFromString<com.homeassistant.tv.data.models.ButtonRemapConfig>(serialized)

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

        val decoded = json.decodeFromString<com.homeassistant.tv.data.models.ButtonRemapConfig>(legacyJson)
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
        fun isSingleActionOnly(config: com.homeassistant.tv.data.models.ButtonRemapConfig): Boolean {
            return config.singlePressAction != null &&
                config.doublePressAction == null &&
                config.longPressAction == null
        }

        val singleOnly = com.homeassistant.tv.data.models.ButtonRemapConfig(
            keyCode = 313,
            keyName = "Star",
            singlePressAction = com.homeassistant.tv.data.models.RemapAction("OPEN_DOCK")
        )
        assertTrue(isSingleActionOnly(singleOnly))

        val withDouble = singleOnly.copy(
            doublePressAction = com.homeassistant.tv.data.models.RemapAction("TOGGLE_ENTITY")
        )
        assertFalse(isSingleActionOnly(withDouble))

        val withLong = singleOnly.copy(
            longPressAction = com.homeassistant.tv.data.models.RemapAction("CALL_SERVICE")
        )
        assertFalse(isSingleActionOnly(withLong))

        val noneConfigured = singleOnly.copy(
            singlePressAction = null
        )
        assertFalse(isSingleActionOnly(noneConfigured))
    }
}

