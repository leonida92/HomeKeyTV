package com.homeassistant.tv.data.models

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
