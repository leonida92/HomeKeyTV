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
                    "hvac_modes": ["heat", "cool", "auto", "off"]
                }
            }
        """.trimIndent()

        val entity = json.decodeFromString<HAEntityState>(jsonString)
        assertEquals("climate", entity.domain)
        assertEquals(22.5f, entity.currentTemperature ?: 0f, 0.01f)
        assertEquals(24.0f, entity.targetTemperature ?: 0f, 0.01f)
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
}
