package com.homekey.tv.data.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

@Serializable
data class HAEntityState(
    @SerialName("entity_id") val entityId: String,
    val state: String,
    val attributes: JsonObject = JsonObject(emptyMap()),
    @SerialName("last_changed") val lastChanged: String? = null,
    @SerialName("last_updated") val lastUpdated: String? = null
) {
    val domain: String
        get() = entityId.substringBefore(".", "")

    val friendlyName: String
        get() = attributes["friendly_name"]?.jsonPrimitive?.content ?: entityId

    val icon: String?
        get() = attributes["icon"]?.jsonPrimitive?.content

    val brightness: Int?
        get() = attributes["brightness"]?.jsonPrimitive?.content?.toIntOrNull()

    val currentTemperature: Float?
        get() = attributes["current_temperature"]?.jsonPrimitive?.content?.toFloatOrNull()

    val targetTemperature: Float?
        get() = (attributes["temperature"]?.jsonPrimitive?.content
            ?: attributes["target_temperature"]?.jsonPrimitive?.content)?.toFloatOrNull()

    val unitOfMeasurement: String?
        get() = attributes["unit_of_measurement"]?.jsonPrimitive?.content

    val hvacModes: List<String>
        get() = try {
            attributes["hvac_modes"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }

    val hvacAction: String?
        get() = attributes["hvac_action"]?.jsonPrimitive?.contentOrNull

    val currentHumidity: Float?
        get() = (attributes["current_humidity"] ?: attributes["humidity"])?.jsonPrimitive?.contentOrNull?.toFloatOrNull()

    val fanModes: List<String>
        get() = try {
            attributes["fan_modes"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }

    val fanMode: String?
        get() = attributes["fan_mode"]?.jsonPrimitive?.contentOrNull

    val presetModes: List<String>
        get() = try {
            attributes["preset_modes"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }

    val presetMode: String?
        get() = attributes["preset_mode"]?.jsonPrimitive?.contentOrNull

    val minTemp: Float
        get() = attributes["min_temp"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull() ?: 16.0f

    val maxTemp: Float
        get() = attributes["max_temp"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull() ?: 32.0f

    val targetTempStep: Float
        get() = attributes["target_temp_step"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull() ?: 0.5f

    val isOn: Boolean
        get() = !isUnavailable && !state.equals("off", ignoreCase = true) &&
                !state.equals("closed", ignoreCase = true) &&
                !state.equals("locked", ignoreCase = true) &&
                !state.equals("idle", ignoreCase = true) &&
                !state.equals("paused", ignoreCase = true) &&
                !state.equals("docked", ignoreCase = true) &&
                !state.equals("standby", ignoreCase = true) &&
                !state.equals("loading", ignoreCase = true) // placeholder before first state event

    val isUnavailable: Boolean
        get() = state.equals("unavailable", ignoreCase = true) || state.equals("unknown", ignoreCase = true)

    val supportedColorModes: List<String>
        get() = try {
            attributes["supported_color_modes"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }

    val supportedFeatures: Int
        get() = attributes["supported_features"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0

    val supportsBrightness: Boolean
        get() {
            if (domain != "light") return false
            if (brightness != null) return true
            val modes = supportedColorModes
            if (modes.isNotEmpty()) {
                // If it only has "onoff", it does NOT support brightness
                return modes.any { it != "onoff" && it != "unknown" }
            }
            // Fallback for older HA entities: bit 0 (value 1) is SUPPORT_BRIGHTNESS
            return (supportedFeatures and 1) != 0
        }

    val mediaTitle: String?
        get() = attributes["media_title"]?.jsonPrimitive?.contentOrNull

    val mediaArtist: String?
        get() = attributes["media_artist"]?.jsonPrimitive?.contentOrNull

    val source: String?
        get() = attributes["source"]?.jsonPrimitive?.contentOrNull

    val sourceList: List<String>
        get() = try {
            attributes["source_list"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }

    val volumeLevel: Float?
        get() = attributes["volume_level"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull()

    val isVolumeMuted: Boolean
        get() = attributes["is_volume_muted"]?.jsonPrimitive?.contentOrNull?.toBoolean() ?: false

    val isPlaying: Boolean
        get() = state.equals("playing", ignoreCase = true)
}

/**
 * Pure decision logic for the optimistic dock toggle. Kept separate from the WebSocket client so
 * it can be unit tested. Returns the state string the tile should *display* immediately after a
 * toggle press, or null when the domain shouldn't flip optimistically at all.
 *
 * Domains with a real on/off state flip. One-shot triggers (scene/script/button/input_button) and
 * media_player (play/pause, not on/off) return null so the UI never shows a state HA didn't enter.
 */
object OptimisticToggle {
    fun nextStateAfterToggle(domain: String, currentState: String?): String? {
        val s = currentState?.lowercase()
        return when (domain) {
            "light", "switch", "input_boolean", "fan", "siren" ->
                if (s == "on") "off" else "on"
            "cover" -> if (s == "open") "closed" else "open"
            "lock" -> if (s == "locked") "unlocked" else "locked"
            "vacuum" -> if (s == "cleaning") "docked" else "cleaning"
            // Turning off is unambiguous; turning on resumes the last mode (unknown to us here).
            "climate" -> if (s != null && s != "off") "off" else null
            else -> null
        }
    }
}

@Serializable
data class PinnedEntityConfig(
    val entityId: String,
    val customName: String? = null,
    val customCategory: String? = null,
    val customIcon: String? = null,
    val order: Int = 0
)

enum class ConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    AUTHENTICATED,
    FAILED
}
