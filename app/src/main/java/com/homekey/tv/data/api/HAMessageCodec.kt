package com.homekey.tv.data.api

import com.homekey.tv.data.models.CameraPipAlert
import com.homekey.tv.data.models.HAEntityState
import kotlinx.serialization.json.*

sealed interface HAInboundMessage {
    data object AuthRequired : HAInboundMessage
    data object AuthOk : HAInboundMessage
    data class AuthInvalid(val message: String) : HAInboundMessage
    data class Result(
        val id: Long,
        val success: Boolean,
        val resultJson: JsonElement? = null,
        val errorMessage: String? = null
    ) : HAInboundMessage
    data class PipAlertEvent(val alert: CameraPipAlert) : HAInboundMessage
    data class StateChangedEvent(val entityId: String, val newState: HAEntityState?) : HAInboundMessage
    data class EntityDiffEvent(val eventData: JsonObject) : HAInboundMessage
    data class Unknown(val rawType: String?, val rawJson: String) : HAInboundMessage
}

object HAMessageCodec {
    val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

    fun extractDomain(entityId: String): String = entityId.substringBefore(".", "")
    fun isValidEntityId(entityId: String): Boolean = entityId.isNotBlank() && entityId.contains(".")

    fun decode(text: String): HAInboundMessage {
        val root = try {
            json.parseToJsonElement(text).jsonObject
        } catch (_: Exception) {
            return HAInboundMessage.Unknown(null, text)
        }

        val type = root["type"]?.jsonPrimitive?.contentOrNull ?: return HAInboundMessage.Unknown(null, text)

        return when (type) {
            "auth_required" -> HAInboundMessage.AuthRequired
            "auth_ok" -> HAInboundMessage.AuthOk
            "auth_invalid" -> {
                val msg = root["message"]?.jsonPrimitive?.contentOrNull ?: "Invalid access token"
                HAInboundMessage.AuthInvalid(msg)
            }
            "result" -> {
                val id = root["id"]?.jsonPrimitive?.longOrNull ?: -1L
                val success = root["success"]?.jsonPrimitive?.booleanOrNull ?: false
                val errorMsg = root["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
                HAInboundMessage.Result(
                    id = id,
                    success = success,
                    resultJson = root["result"],
                    errorMessage = errorMsg
                )
            }
            "event" -> {
                val event = root["event"]?.jsonObject ?: return HAInboundMessage.Unknown(type, text)
                val eventType = event["event_type"]?.jsonPrimitive?.contentOrNull

                when (eventType) {
                    "homekey_tv_notify" -> {
                        val data = event["data"]?.jsonObject
                        val entityId = data?.get("entity_id")?.jsonPrimitive?.contentOrNull
                        if (entityId != null) {
                            val title = data["title"]?.jsonPrimitive?.contentOrNull
                            val message = data["message"]?.jsonPrimitive?.contentOrNull
                            val duration = (data["duration"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 15).coerceIn(3, 120)
                            HAInboundMessage.PipAlertEvent(
                                CameraPipAlert(
                                    entityId = entityId,
                                    title = title,
                                    message = message,
                                    durationSeconds = duration,
                                    timestamp = System.currentTimeMillis()
                                )
                            )
                        } else {
                            HAInboundMessage.Unknown(type, text)
                        }
                    }
                    "state_changed" -> {
                        val data = event["data"]?.jsonObject
                        val entityId = data?.get("entity_id")?.jsonPrimitive?.contentOrNull
                        val newStateElem = data?.get("new_state")
                        if (entityId != null) {
                            val state = if (newStateElem != null && newStateElem !is JsonNull) {
                                try {
                                    json.decodeFromJsonElement<HAEntityState>(newStateElem)
                                } catch (_: Exception) {
                                    null
                                }
                            } else null
                            HAInboundMessage.StateChangedEvent(entityId, state)
                        } else {
                            HAInboundMessage.Unknown(type, text)
                        }
                    }
                    else -> {
                        HAInboundMessage.EntityDiffEvent(event)
                    }
                }
            }
            else -> HAInboundMessage.Unknown(type, text)
        }
    }

    fun encodeAuth(token: String): String {
        return buildJsonObject {
            put("type", "auth")
            put("access_token", token)
        }.toString()
    }

    fun encodeGetStates(id: Long): String {
        return buildJsonObject {
            put("id", id)
            put("type", "get_states")
        }.toString()
    }

    fun encodeSubscribeEntities(id: Long, entityIds: Set<String>): String {
        return buildJsonObject {
            put("id", id)
            put("type", "subscribe_entities")
            put("entity_ids", buildJsonArray {
                entityIds.forEach { add(JsonPrimitive(it)) }
            })
        }.toString()
    }

    fun encodeUnsubscribe(id: Long, subscriptionId: Long): String {
        return buildJsonObject {
            put("id", id)
            put("type", "unsubscribe_events")
            put("subscription", subscriptionId)
        }.toString()
    }

    fun encodeSubscribeEvents(id: Long, eventType: String): String {
        return buildJsonObject {
            put("id", id)
            put("type", "subscribe_events")
            put("event_type", eventType)
        }.toString()
    }

    fun buildBrightnessData(brightness: Int): JsonObject = buildJsonObject {
        put("brightness", brightness.coerceIn(0, 255))
    }

    fun buildTemperatureData(temperature: Float): JsonObject = buildJsonObject {
        put("temperature", temperature)
    }

    fun buildHvacModeData(mode: String): JsonObject = buildJsonObject {
        put("hvac_mode", mode)
    }

    fun buildFanModeData(fanMode: String): JsonObject = buildJsonObject {
        put("fan_mode", fanMode)
    }

    fun buildPresetModeData(presetMode: String): JsonObject = buildJsonObject {
        put("preset_mode", presetMode)
    }

    fun buildSourceData(source: String): JsonObject = buildJsonObject {
        put("source", source)
    }

    fun buildVolumeData(volume: Float): JsonObject = buildJsonObject {
        put("volume_level", volume.coerceIn(0f, 1f).toDouble())
    }

    fun buildMuteData(mute: Boolean): JsonObject = buildJsonObject {
        put("is_volume_muted", mute)
    }

    fun buildPositionData(position: Int): JsonObject = buildJsonObject {
        put("position", position.coerceIn(0, 100))
    }

    fun buildTiltData(tilt: Int): JsonObject = buildJsonObject {
        put("tilt_position", tilt.coerceIn(0, 100))
    }

    fun buildPercentageData(percentage: Int): JsonObject = buildJsonObject {
        put("percentage", percentage.coerceIn(0, 100))
    }

    fun buildOscillateData(oscillating: Boolean): JsonObject = buildJsonObject {
        put("oscillating", oscillating)
    }

    fun encodeCallService(
        id: Long,
        domain: String,
        service: String,
        serviceData: JsonObject? = null,
        entityId: String? = null
    ): String {
        return buildJsonObject {
            put("id", id)
            put("type", "call_service")
            put("domain", domain)
            put("service", service)
            if (serviceData != null) {
                put("service_data", serviceData)
            }
            if (entityId != null) {
                put("target", buildJsonObject {
                    put("entity_id", entityId)
                })
            }
        }.toString()
    }
}
