package com.homekey.tv

import com.homekey.tv.data.api.HAInboundMessage
import com.homekey.tv.data.api.HAMessageCodec
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

class HAMessageCodecTest {

    @Test
    fun decode_auth_required() {
        val json = """{"type":"auth_required","ha_version":"2024.1.0"}"""
        val message = HAMessageCodec.decode(json)
        assertTrue(message is HAInboundMessage.AuthRequired)
    }

    @Test
    fun decode_auth_ok() {
        val json = """{"type":"auth_ok","ha_version":"2024.1.0"}"""
        val message = HAMessageCodec.decode(json)
        assertTrue(message is HAInboundMessage.AuthOk)
    }

    @Test
    fun decode_auth_invalid() {
        val json = """{"type":"auth_invalid","message":"Invalid token"}"""
        val message = HAMessageCodec.decode(json)
        assertTrue(message is HAInboundMessage.AuthInvalid)
        assertEquals("Invalid token", (message as HAInboundMessage.AuthInvalid).message)
    }

    @Test
    fun decode_result_success_and_failure() {
        val successJson = """{"id":1,"type":"result","success":true,"result":[{"entity_id":"light.desk","state":"on"}]}"""
        val successMsg = HAMessageCodec.decode(successJson)
        assertTrue(successMsg is HAInboundMessage.Result)
        val res = successMsg as HAInboundMessage.Result
        assertEquals(1L, res.id)
        assertTrue(res.success)
        assertNotNull(res.resultJson)

        val failJson = """{"id":2,"type":"result","success":false,"error":{"code":"not_found","message":"Unknown command"}}"""
        val failMsg = HAMessageCodec.decode(failJson)
        assertTrue(failMsg is HAInboundMessage.Result)
        val failRes = failMsg as HAInboundMessage.Result
        assertEquals(2L, failRes.id)
        assertFalse(failRes.success)
        assertEquals("Unknown command", failRes.errorMessage)
    }

    @Test
    fun decode_pip_alert_event() {
        val json = """{
            "id":3,
            "type":"event",
            "event":{
                "event_type":"homekey_tv_notify",
                "data":{
                    "entity_id":"camera.front_door",
                    "title":"Front Door",
                    "message":"Motion detected",
                    "duration":20
                }
            }
        }"""
        val msg = HAMessageCodec.decode(json)
        assertTrue(msg is HAInboundMessage.PipAlertEvent)
        val alert = (msg as HAInboundMessage.PipAlertEvent).alert
        assertEquals("camera.front_door", alert.entityId)
        assertEquals("Front Door", alert.title)
        assertEquals("Motion detected", alert.message)
        assertEquals(20, alert.durationSeconds)
    }

    @Test
    fun decode_state_changed_event() {
        val json = """{
            "id":4,
            "type":"event",
            "event":{
                "event_type":"state_changed",
                "data":{
                    "entity_id":"light.living_room",
                    "new_state":{
                        "entity_id":"light.living_room",
                        "state":"on",
                        "attributes":{"brightness":200}
                    }
                }
            }
        }"""
        val msg = HAMessageCodec.decode(json)
        assertTrue(msg is HAInboundMessage.StateChangedEvent)
        val stateEvent = msg as HAInboundMessage.StateChangedEvent
        assertEquals("light.living_room", stateEvent.entityId)
        assertNotNull(stateEvent.newState)
        assertEquals("on", stateEvent.newState?.state)
        assertEquals(200, stateEvent.newState?.brightness)
    }

    @Test
    fun decode_entity_diff_event() {
        val json = """{
            "id":5,
            "type":"event",
            "event":{
                "c":{
                    "light.living_room":{
                        "+":{"s":"off"}
                    }
                }
            }
        }"""
        val msg = HAMessageCodec.decode(json)
        assertTrue(msg is HAInboundMessage.EntityDiffEvent)
        val diffEvent = msg as HAInboundMessage.EntityDiffEvent
        assertNotNull(diffEvent.eventData)
    }

    @Test
    fun encode_outbound_messages() {
        val authJson = HAMessageCodec.encodeAuth("secret_token")
        assertTrue(authJson.contains("\"type\":\"auth\""))
        assertTrue(authJson.contains("\"access_token\":\"secret_token\""))

        val getStatesJson = HAMessageCodec.encodeGetStates(10)
        assertTrue(getStatesJson.contains("\"id\":10"))
        assertTrue(getStatesJson.contains("\"type\":\"get_states\""))

        val subscribeJson = HAMessageCodec.encodeSubscribeEntities(11, setOf("light.kitchen", "switch.fan"))
        assertTrue(subscribeJson.contains("\"id\":11"))
        assertTrue(subscribeJson.contains("\"type\":\"subscribe_entities\""))
        assertTrue(subscribeJson.contains("\"light.kitchen\""))

        val unsubJson = HAMessageCodec.encodeUnsubscribe(12, 11)
        assertTrue(unsubJson.contains("\"id\":12"))
        assertTrue(unsubJson.contains("\"type\":\"unsubscribe_events\""))
        assertTrue(unsubJson.contains("\"subscription\":11"))

        val serviceData = buildJsonObject { put("brightness", 150) }
        val callJson = HAMessageCodec.encodeCallService(13, "light", "turn_on", serviceData, "light.kitchen")
        assertTrue(callJson.contains("\"id\":13"))
        assertTrue(callJson.contains("\"type\":\"call_service\""))
        assertTrue(callJson.contains("\"domain\":\"light\""))
        assertTrue(callJson.contains("\"service\":\"turn_on\""))
        assertTrue(callJson.contains("\"brightness\":150"))
        assertTrue(callJson.contains("\"entity_id\":\"light.kitchen\""))
    }
}
