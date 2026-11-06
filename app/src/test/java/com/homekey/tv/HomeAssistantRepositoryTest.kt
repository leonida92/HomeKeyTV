package com.homekey.tv

import com.homekey.tv.data.api.DefaultHomeAssistantRepository
import com.homekey.tv.data.models.ConnectionStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeAssistantRepositoryTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var testJob: Job
    private lateinit var repoScope: CoroutineScope
    private lateinit var fakeTransport: FakeHATransport
    private lateinit var fakePrefs: FakePreferencesRepository
    private lateinit var repository: DefaultHomeAssistantRepository

    @Before
    fun setUp() {
        testJob = Job()
        repoScope = CoroutineScope(testDispatcher + testJob)
        fakeTransport = FakeHATransport()
        fakePrefs = FakePreferencesRepository()
        repository = DefaultHomeAssistantRepository(
            transport = fakeTransport,
            prefs = fakePrefs,
            scope = repoScope
        )
    }

    @After
    fun tearDown() {
        testJob.cancel()
    }

    @Test
    fun connect_and_authenticate_lifecycle() = runTest(testDispatcher) {
        repository.connect("http://192.168.1.100:8123", "my_token")

        assertEquals("ws://192.168.1.100:8123/api/websocket", fakeTransport.lastConnectedUrl)
        assertEquals(ConnectionStatus.CONNECTING, repository.connectionStatus.value)

        // Simulate server asking for auth
        fakeTransport.emitMessage("""{"type":"auth_required"}""")
        assertTrue(fakeTransport.sentMessages.any { it.contains("\"type\":\"auth\"") && it.contains("\"access_token\":\"my_token\"") })

        // Simulate server accepting auth
        fakeTransport.emitMessage("""{"type":"auth_ok"}""")
        assertEquals(ConnectionStatus.AUTHENTICATED, repository.connectionStatus.value)

        // Repository should automatically request get_states & subscribe to events
        assertTrue(fakeTransport.sentMessages.any { it.contains("\"type\":\"get_states\"") })
        assertTrue(fakeTransport.sentMessages.any { it.contains("homekey_tv_notify") })

        // Simulate initial states snapshot response
        fakeTransport.emitMessage("""{
            "id":1,
            "type":"result",
            "success":true,
            "result":[
                {"entity_id":"light.living_room","state":"off"},
                {"entity_id":"switch.kitchen","state":"on"}
            ]
        }""")

        assertEquals(2, repository.entities.value.size)
        assertEquals("off", repository.entities.value["light.living_room"]?.state)
        assertEquals("on", repository.entities.value["switch.kitchen"]?.state)
    }

    @Test
    fun optimistic_toggle_updates_memory_immediately_and_sends_service_call() = runTest(testDispatcher) {
        // Authenticate first
        repository.connect("http://192.168.1.100:8123", "token")
        fakeTransport.emitMessage("""{"type":"auth_required"}""")
        fakeTransport.emitMessage("""{"type":"auth_ok"}""")

        // Load an initial off entity
        fakeTransport.emitMessage("""{
            "id":1,
            "type":"result",
            "success":true,
            "result":[{"entity_id":"light.living_room","state":"off"}]
        }""")

        assertEquals("off", repository.entities.value["light.living_room"]?.state)

        // Toggle light
        repository.toggleEntity("light.living_room")

        // Optimistic state flips to on immediately (0ms)
        assertEquals("on", repository.entities.value["light.living_room"]?.state)

        // Sent call_service command
        assertTrue(fakeTransport.sentMessages.any {
            it.contains("\"type\":\"call_service\"") &&
            it.contains("\"domain\":\"light\"") &&
            it.contains("\"service\":\"toggle\"") &&
            it.contains("\"entity_id\":\"light.living_room\"")
        })
    }

    @Test
    fun auth_invalid_sets_connection_failed() = runTest(testDispatcher) {
        repository.connect("http://192.168.1.100:8123", "bad_token")
        fakeTransport.emitMessage("""{"type":"auth_required"}""")
        fakeTransport.emitMessage("""{"type":"auth_invalid","message":"Invalid password"}""")

        assertEquals(ConnectionStatus.FAILED, repository.connectionStatus.value)
        assertEquals("Authentication failed: Invalid password", repository.errorMessage.value)
    }

    @Test
    fun pip_alert_received_and_dismissed() = runTest(testDispatcher) {
        repository.connect("http://192.168.1.100:8123", "token")
        fakeTransport.emitMessage("""{"type":"auth_required"}""")
        fakeTransport.emitMessage("""{"type":"auth_ok"}""")

        fakeTransport.emitMessage("""{
            "id":2,
            "type":"event",
            "event":{
                "event_type":"homekey_tv_notify",
                "data":{
                    "entity_id":"camera.backyard",
                    "title":"Backyard Cam",
                    "message":"Person detected",
                    "duration":10
                }
            }
        }""")

        val alert = repository.pipAlert.value
        assertNotNull(alert)
        assertEquals("camera.backyard", alert?.entityId)
        assertEquals("Backyard Cam", alert?.title)

        repository.dismissPipAlert()
        assertNull(repository.pipAlert.value)
    }

    @Test
    fun cover_service_calls_generate_expected_ha_payloads() = runTest(testDispatcher) {
        repository.openCover("cover.living_room_blinds")
        assertTrue(fakeTransport.sentMessages.any { it.contains("call_service") && it.contains("open_cover") && it.contains("cover.living_room_blinds") })

        repository.closeCover("cover.living_room_blinds")
        assertTrue(fakeTransport.sentMessages.any { it.contains("call_service") && it.contains("close_cover") && it.contains("cover.living_room_blinds") })

        repository.stopCover("cover.living_room_blinds")
        assertTrue(fakeTransport.sentMessages.any { it.contains("call_service") && it.contains("stop_cover") && it.contains("cover.living_room_blinds") })

        repository.setCoverPosition("cover.living_room_blinds", 75)
        assertTrue(fakeTransport.sentMessages.any { it.contains("set_cover_position") && it.contains("\"position\":75") })

        repository.setCoverTiltPosition("cover.living_room_blinds", 45)
        assertTrue(fakeTransport.sentMessages.any { it.contains("set_cover_tilt_position") && it.contains("\"tilt_position\":45") })
    }

    @Test
    fun fan_service_calls_generate_expected_ha_payloads() = runTest(testDispatcher) {
        repository.setFanPercentage("fan.living_room", 60)
        assertTrue(fakeTransport.sentMessages.any { it.contains("call_service") && it.contains("set_percentage") && it.contains("\"percentage\":60") })

        repository.setFanOscillating("fan.living_room", true)
        assertTrue(fakeTransport.sentMessages.any { it.contains("call_service") && it.contains("oscillate") && it.contains("\"oscillating\":true") })
    }

    @Test
    fun lock_service_calls_generate_expected_ha_payloads() = runTest(testDispatcher) {
        repository.lock("lock.front_door")
        assertTrue(fakeTransport.sentMessages.any { it.contains("call_service") && it.contains("\"service\":\"lock\"") && it.contains("lock.front_door") })

        repository.unlock("lock.front_door")
        assertTrue(fakeTransport.sentMessages.any { it.contains("call_service") && it.contains("\"service\":\"unlock\"") && it.contains("lock.front_door") })
    }
}
