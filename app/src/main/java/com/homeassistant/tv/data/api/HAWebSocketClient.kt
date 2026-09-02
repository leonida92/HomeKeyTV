package com.homeassistant.tv.data.api

import android.util.Log
import com.homeassistant.tv.data.models.ConnectionStatus
import com.homeassistant.tv.data.models.HAEntityState
import com.homeassistant.tv.data.models.OptimisticToggle
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class HAWebSocketClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()
) : WebSocketListener() {

    private val tag = "HAWebSocketClient"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // OkHttp invokes onMessage on its reader thread. Every frame is funneled into one ordered
    // channel consumed by a single coroutine, so Home Assistant messages apply in arrival order
    // (no reordering, no lost updates from concurrent handlers).
    private val messageChannel = Channel<String>(Channel.UNLIMITED)

    @Volatile private var webSocket: WebSocket? = null
    private val messageId = AtomicLong(1)
    @Volatile private var isSubscribedToEvents = false
    @Volatile private var getStatesMsgId: Long = -1

    private val _connectionStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private val _entities = MutableStateFlow<Map<String, HAEntityState>>(emptyMap())
    val entities: StateFlow<Map<String, HAEntityState>> = _entities.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    @Volatile private var currentUrl: String = ""
    @Volatile private var currentToken: String = ""
    @Volatile private var reconnectAttempt = 0
    @Volatile private var reconnectJob: Job? = null

    init {
        // Single consumer: preserves WebSocket ordering and confines all inbound decoding and
        // entity-map mutation to one coroutine.
        scope.launch {
            for (text in messageChannel) {
                try {
                    handleMessage(text)
                } catch (e: Exception) {
                    Log.e(tag, "Error parsing message: $text", e)
                }
            }
        }
    }

    fun connect(serverUrl: String, token: String) {
        if (serverUrl.isBlank() || token.isBlank()) {
            _connectionStatus.value = ConnectionStatus.FAILED
            _errorMessage.value = "Server URL and Access Token are required"
            return
        }

        val cleanUrl = serverUrl.trim().removeSuffix("/")
        val cleanToken = token.trim()

        if (_connectionStatus.value == ConnectionStatus.AUTHENTICATED &&
            currentUrl == cleanUrl && currentToken == cleanToken && webSocket != null) {
            Log.d(tag, "Already authenticated and connected to $cleanUrl")
            return
        }

        currentUrl = cleanUrl
        currentToken = cleanToken

        // Don't flash DISCONNECTED while re-establishing a connection; we set CONNECTING below.
        disconnect(updateStatus = false)
        isSubscribedToEvents = false

        val wsUrl = buildWsUrl(currentUrl)
        Log.d(tag, "Connecting to WebSocket: $wsUrl")
        _connectionStatus.value = ConnectionStatus.CONNECTING
        _errorMessage.value = null

        val request = Request.Builder()
            .url(wsUrl)
            .build()

        webSocket = client.newWebSocket(request, this)
    }

    fun disconnect() = disconnect(updateStatus = true)

    private fun disconnect(updateStatus: Boolean) {
        reconnectJob?.cancel()
        reconnectJob = null
        try {
            webSocket?.close(1000, "User disconnected")
        } catch (e: Exception) {
            Log.e(tag, "Error closing websocket", e)
        }
        webSocket = null
        isSubscribedToEvents = false
        if (updateStatus) {
            _connectionStatus.value = ConnectionStatus.DISCONNECTED
        }
    }

    private fun buildWsUrl(baseUrl: String): String {
        return when {
            baseUrl.startsWith("https://") -> baseUrl.replaceFirst("https://", "wss://") + "/api/websocket"
            baseUrl.startsWith("http://") -> baseUrl.replaceFirst("http://", "ws://") + "/api/websocket"
            baseUrl.startsWith("ws://") || baseUrl.startsWith("wss://") -> {
                if (baseUrl.endsWith("/api/websocket")) baseUrl else "$baseUrl/api/websocket"
            }
            else -> "ws://$baseUrl/api/websocket"
        }
    }

    override fun onOpen(webSocket: WebSocket, response: Response) {
        Log.d(tag, "WebSocket Opened")
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        // Channel.UNLIMITED never suspends, so this never blocks OkHttp's reader thread.
        messageChannel.trySend(text)
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        Log.d(tag, "WebSocket Closing: $code $reason")
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        Log.d(tag, "WebSocket Closed: $code $reason")
        _connectionStatus.value = ConnectionStatus.DISCONNECTED
        isSubscribedToEvents = false
        if (code != 1000) {
            scheduleReconnect()
        }
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        Log.e(tag, "WebSocket Failure: ${t.message}", t)
        _connectionStatus.value = ConnectionStatus.FAILED
        _errorMessage.value = t.localizedMessage ?: "Connection failed"
        isSubscribedToEvents = false
        scheduleReconnect()
    }

    private fun scheduleReconnect() {
        if (currentUrl.isBlank() || currentToken.isBlank()) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            val backoffSeconds = (2L shl reconnectAttempt.coerceAtMost(4)).coerceAtMost(30L)
            reconnectAttempt++
            Log.d(tag, "Attempting reconnect in ${backoffSeconds}s (attempt $reconnectAttempt)...")
            delay(backoffSeconds * 1000)
            connect(currentUrl, currentToken)
        }
    }

    private fun handleMessage(text: String) {
        val root = json.parseToJsonElement(text).jsonObject
        val type = root["type"]?.jsonPrimitive?.content ?: return

        when (type) {
            "auth_required" -> {
                Log.d(tag, "Auth required, sending access token")
                val authPayload = buildJsonObject {
                    put("type", "auth")
                    put("access_token", currentToken)
                }
                webSocket?.send(authPayload.toString())
            }

            "auth_ok" -> {
                Log.d(tag, "Auth OK! Requesting initial states & subscribing to updates")
                reconnectAttempt = 0
                _connectionStatus.value = ConnectionStatus.AUTHENTICATED
                _errorMessage.value = null
                fetchInitialStates()
                subscribeToStateChanges()
            }

            "auth_invalid" -> {
                val errorMsg = root["message"]?.jsonPrimitive?.content ?: "Invalid access token"
                Log.e(tag, "Auth invalid: $errorMsg")
                _connectionStatus.value = ConnectionStatus.FAILED
                _errorMessage.value = "Authentication failed: $errorMsg"
            }

            "result" -> {
                val id = root["id"]?.jsonPrimitive?.longOrNull
                val success = root["success"]?.jsonPrimitive?.booleanOrNull ?: false

                if (id == getStatesMsgId && success) {
                    val resultList = root["result"]?.jsonArray
                    if (resultList != null) {
                        val map = ConcurrentHashMap<String, HAEntityState>()
                        for (item in resultList) {
                            try {
                                val state = json.decodeFromJsonElement<HAEntityState>(item)
                                map[state.entityId] = state
                            } catch (e: Exception) {
                                // Skip malformed item
                            }
                        }
                        _entities.value = map
                        Log.d(tag, "Loaded ${_entities.value.size} entities from Home Assistant")
                    }
                }
            }

            "event" -> {
                val event = root["event"]?.jsonObject
                val eventType = event?.get("event_type")?.jsonPrimitive?.content
                if (eventType == "state_changed") {
                    val data = event["data"]?.jsonObject
                    val entityId = data?.get("entity_id")?.jsonPrimitive?.content
                    val newStateElem = data?.get("new_state")
                    if (entityId != null) {
                        // Atomic read-modify-write: safe even when a UI-thread optimistic update
                        // interleaves with this single-consumer handler.
                        _entities.update { current ->
                            if (newStateElem != null && newStateElem !is JsonNull) {
                                val newState = try {
                                    json.decodeFromJsonElement<HAEntityState>(newStateElem)
                                } catch (e: Exception) {
                                    Log.e(tag, "Failed to decode updated state for $entityId", e)
                                    return@update current
                                }
                                if (current[entityId] == newState) current else current + (entityId to newState)
                            } else {
                                if (entityId !in current) current else current - entityId
                            }
                        }
                    }
                }
            }
        }
    }

    private fun fetchInitialStates() {
        val id = messageId.getAndIncrement()
        getStatesMsgId = id
        val cmd = buildJsonObject {
            put("id", id)
            put("type", "get_states")
        }
        webSocket?.send(cmd.toString())
    }

    private fun subscribeToStateChanges() {
        if (isSubscribedToEvents) return
        val id = messageId.getAndIncrement()
        val cmd = buildJsonObject {
            put("id", id)
            put("type", "subscribe_events")
            put("event_type", "state_changed")
        }
        webSocket?.send(cmd.toString())
        isSubscribedToEvents = true
    }

    /**
     * Applies a single-entity optimistic UI update. Guarded to AUTHENTICATED so the dock never
     * flips a tile while the socket is down (the service call would be silently dropped anyway).
     * update{} is atomic and short-circuits when the transformed value is unchanged, so unrelated
     * collectors are not woken by no-op writes.
     */
    private fun applyOptimistic(entityId: String, transform: (HAEntityState) -> HAEntityState?) {
        if (_connectionStatus.value != ConnectionStatus.AUTHENTICATED) return
        _entities.update { map ->
            val current = map[entityId] ?: return@update map
            val updated = transform(current) ?: return@update map
            if (updated == current) map else map + (entityId to updated)
        }
    }

    fun toggleEntity(entityId: String) {
        val domain = entityId.substringBefore(".")
        val currentEntity = _entities.value[entityId]
        val state = currentEntity?.state?.lowercase()

        // Optimistic flip only for domains with a real on/off-ish state (pure helper in the models
        // package, unit-tested in HAModelTest). scene/script/button/input_button are one-shot
        // triggers and media_player toggles play/pause — those return null and never flip here.
        val optimisticState = OptimisticToggle.nextStateAfterToggle(domain, state)
        if (optimisticState != null) {
            applyOptimistic(entityId) { it.copy(state = optimisticState) }
        }

        val (serviceDomain, service) = when (domain) {
            "light", "switch", "input_boolean", "fan", "siren" -> domain to "toggle"
            "scene", "script" -> domain to "turn_on"
            "media_player" -> "media_player" to "media_play_pause"
            "climate" -> {
                if (state == "off") "climate" to "turn_on" else "climate" to "turn_off"
            }
            "cover" -> {
                if (state == "open") "cover" to "close_cover" else "cover" to "open_cover"
            }
            "lock" -> {
                if (state == "locked") "lock" to "unlock" else "lock" to "lock"
            }
            "button", "input_button" -> domain to "press"
            "vacuum" -> {
                if (state == "cleaning") "vacuum" to "pause" else "vacuum" to "start"
            }
            else -> domain to "toggle"
        }

        callService(serviceDomain, service, entityId = entityId)
    }

    fun turnOn(entityId: String, brightness: Int? = null) {
        val domain = entityId.substringBefore(".")
        applyOptimistic(entityId) { curr ->
            val newAttrs = curr.attributes.toMutableMap()
            if (brightness != null) {
                newAttrs["brightness"] = JsonPrimitive(brightness)
            }
            curr.copy(state = "on", attributes = JsonObject(newAttrs))
        }

        val serviceData = if (brightness != null && domain == "light") {
            buildJsonObject {
                put("brightness", brightness)
            }
        } else null

        callService(domain, "turn_on", serviceData, entityId)
    }

    fun turnOff(entityId: String) {
        val domain = entityId.substringBefore(".")
        applyOptimistic(entityId) { it.copy(state = "off") }
        callService(domain, "turn_off", entityId = entityId)
    }

    fun setBrightness(entityId: String, brightness: Int) {
        applyOptimistic(entityId) { curr ->
            val newAttrs = curr.attributes.toMutableMap()
            newAttrs["brightness"] = JsonPrimitive(brightness)
            curr.copy(state = "on", attributes = JsonObject(newAttrs))
        }
        val serviceData = buildJsonObject {
            put("brightness", brightness.coerceIn(0, 255))
        }
        callService("light", "turn_on", serviceData, entityId)
    }

    fun setTargetTemperature(entityId: String, temperature: Float) {
        applyOptimistic(entityId) { curr ->
            val newAttrs = curr.attributes.toMutableMap()
            newAttrs["temperature"] = JsonPrimitive(temperature)
            curr.copy(attributes = JsonObject(newAttrs))
        }
        val serviceData = buildJsonObject {
            put("temperature", temperature)
        }
        callService("climate", "set_temperature", serviceData, entityId)
    }

    fun setHvacMode(entityId: String, mode: String) {
        val serviceData = buildJsonObject {
            put("hvac_mode", mode)
        }
        callService("climate", "set_hvac_mode", serviceData, entityId)
    }

    fun mediaPlayPause(entityId: String) {
        callService("media_player", "media_play_pause", entityId = entityId)
    }

    fun mediaNext(entityId: String) {
        callService("media_player", "media_next_track", entityId = entityId)
    }

    fun mediaPrevious(entityId: String) {
        callService("media_player", "media_previous_track", entityId = entityId)
    }

    fun mediaVolumeUp(entityId: String) {
        callService("media_player", "volume_up", entityId = entityId)
    }

    fun mediaVolumeDown(entityId: String) {
        callService("media_player", "volume_down", entityId = entityId)
    }

    fun callService(
        domain: String,
        service: String,
        serviceData: JsonObject? = null,
        entityId: String? = null
    ) {
        val id = messageId.getAndIncrement()
        val cmd = buildJsonObject {
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
        }

        Log.d(tag, "Sending service call: $cmd")
        webSocket?.send(cmd.toString())
    }

    companion object {
        @Volatile
        private var instance: HAWebSocketClient? = null

        fun getInstance(): HAWebSocketClient {
            return instance ?: synchronized(this) {
                instance ?: HAWebSocketClient().also { instance = it }
            }
        }
    }
}
