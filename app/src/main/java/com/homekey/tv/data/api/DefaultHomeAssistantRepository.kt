package com.homekey.tv.data.api

import android.util.Log
import com.homekey.tv.data.local.PreferencesRepository
import com.homekey.tv.data.models.CameraPipAlert
import com.homekey.tv.data.models.ConnectionStatus
import com.homekey.tv.data.models.HAEntityDiffProcessor
import com.homekey.tv.data.models.HAEntityState
import com.homekey.tv.data.models.OptimisticToggle
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import java.util.concurrent.atomic.AtomicLong

class DefaultHomeAssistantRepository(
    private val transport: HATransport,
    private val prefs: PreferencesRepository,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) : HomeAssistantRepository {

    private val tag = "DefaultHomeAssistantRepo"

    private val messageId = AtomicLong(1)
    @Volatile private var getStatesMsgId: Long = -1L
    @Volatile private var subscribeEntitiesSubId: Long = -1L
    @Volatile private var isSubscribedToEventsFallback = false
    @Volatile private var notifyEventsSubId: Long = -1L

    private val baseMonitoredEntities = MutableStateFlow<Set<String>>(emptySet())
    private val activeDialogEntity = MutableStateFlow<String?>(null)
    @Volatile private var monitoredEntityIds: Set<String> = emptySet()

    private val _pipAlert = MutableStateFlow<CameraPipAlert?>(null)
    override val pipAlert: StateFlow<CameraPipAlert?> = _pipAlert.asStateFlow()

    private val _connectionStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    override val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private val _entities = MutableStateFlow<Map<String, HAEntityState>>(emptyMap())
    override val entities: StateFlow<Map<String, HAEntityState>> = _entities.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    override val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    @Volatile private var currentUrl: String = ""
    @Volatile private var currentToken: String = ""
    @Volatile private var reconnectAttempt = 0
    @Volatile private var reconnectJob: Job? = null

    init {
        // Collect messages from transport
        scope.launch {
            transport.incomingMessages.collect { text ->
                try {
                    handleInboundMessage(HAMessageCodec.decode(text))
                } catch (e: Exception) {
                    Log.e(tag, "Error handling incoming message: $text", e)
                }
            }
        }

        // Collect connection events from transport
        scope.launch {
            transport.connectionEvents.collect { event ->
                when (event) {
                    is TransportEvent.Connected -> {
                        Log.d(tag, "Transport connected")
                    }
                    is TransportEvent.Disconnected -> {
                        Log.d(tag, "Transport disconnected: ${event.code} ${event.reason}")
                        _connectionStatus.update { ConnectionStatus.DISCONNECTED }
                        if (event.code != 1000) {
                            scheduleReconnect()
                        }
                    }
                    is TransportEvent.Failure -> {
                        Log.e(tag, "Transport failure: ${event.throwable.message}", event.throwable)
                        _connectionStatus.update { ConnectionStatus.FAILED }
                        _errorMessage.update { event.throwable.localizedMessage ?: "Connection failed" }
                        scheduleReconnect()
                    }
                }
            }
        }

        // Reactive reconciliation of monitored entities from preferences
        scope.launch {
            combine(prefs.pinnedEntities, prefs.buttonRemaps) { pinned, remaps ->
                val entityIds = mutableSetOf<String>()
                pinned.forEach { entityIds.add(it.entityId) }
                remaps.forEach { remap ->
                    val actions = listOf(remap.singlePressAction, remap.doublePressAction, remap.longPressAction)
                    actions.forEach { action ->
                        if (action != null && (action.type == "TOGGLE_ENTITY" || action.type == "SELECT_SOURCE")) {
                            action.target?.let { entityIds.add(it) }
                        }
                    }
                }
                entityIds.toSet()
            }.collect { monitoredSet ->
                baseMonitoredEntities.update { monitoredSet }
            }
        }

        // Debounced subscription trigger
        @OptIn(FlowPreview::class)
        scope.launch {
            combine(baseMonitoredEntities, activeDialogEntity) { base, dialog ->
                if (dialog != null && HAMessageCodec.isValidEntityId(dialog)) base + dialog else base
            }
                .distinctUntilChanged()
                .debounce(300L)
                .collect { entityIds ->
                    val changed = monitoredEntityIds != entityIds
                    monitoredEntityIds = entityIds
                    if (changed && _connectionStatus.value == ConnectionStatus.AUTHENTICATED) {
                        resubscribeEntities()
                    }
                }
        }
    }

    override fun connect(serverUrl: String, token: String) {
        if (serverUrl.isBlank() || token.isBlank()) {
            _connectionStatus.update { ConnectionStatus.FAILED }
            _errorMessage.update { "Server URL and Access Token are required" }
            return
        }

        val cleanUrl = serverUrl.trim().removeSuffix("/")
        val cleanToken = token.trim()

        if (_connectionStatus.value == ConnectionStatus.AUTHENTICATED &&
            currentUrl == cleanUrl && currentToken == cleanToken) {
            Log.d(tag, "Already authenticated and connected to $cleanUrl")
            return
        }

        currentUrl = cleanUrl
        currentToken = cleanToken

        disconnect(updateStatus = false)

        val wsUrl = buildWsUrl(currentUrl)
        Log.d(tag, "Connecting to WebSocket: $wsUrl")
        _connectionStatus.update { ConnectionStatus.CONNECTING }
        _errorMessage.update { null }

        transport.connect(wsUrl)
    }

    override fun disconnect() = disconnect(updateStatus = true)

    private fun disconnect(updateStatus: Boolean) {
        reconnectJob?.cancel()
        reconnectJob = null
        try {
            transport.disconnect(1000, "User disconnected")
        } catch (e: Exception) {
            Log.e(tag, "Error closing transport", e)
        }
        subscribeEntitiesSubId = -1L
        isSubscribedToEventsFallback = false
        notifyEventsSubId = -1L
        if (updateStatus) {
            _connectionStatus.update { ConnectionStatus.DISCONNECTED }
        }
    }

    override fun dismissPipAlert() {
        _pipAlert.update { null }
    }

    override fun setActiveDialogEntity(entityId: String?) {
        activeDialogEntity.update { entityId }
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

    private fun handleInboundMessage(message: HAInboundMessage) {
        when (message) {
            is HAInboundMessage.AuthRequired -> {
                Log.d(tag, "Auth required, sending access token")
                transport.send(HAMessageCodec.encodeAuth(currentToken))
            }

            is HAInboundMessage.AuthOk -> {
                Log.d(tag, "Auth OK! Requesting initial states & subscribing to updates")
                reconnectAttempt = 0
                _connectionStatus.update { ConnectionStatus.AUTHENTICATED }
                _errorMessage.update { null }
                fetchInitialStates()
                resubscribeEntities()
                subscribeToNotifyEvents()
            }

            is HAInboundMessage.AuthInvalid -> {
                Log.e(tag, "Auth invalid: ${message.message}")
                _connectionStatus.update { ConnectionStatus.FAILED }
                _errorMessage.update { "Authentication failed: ${message.message}" }
            }

            is HAInboundMessage.Result -> {
                if (message.id == subscribeEntitiesSubId && !message.success) {
                    Log.w(tag, "subscribe_entities rejected by HA (${message.errorMessage}), falling back to state_changed")
                    subscribeEntitiesSubId = -1L
                    subscribeToStateChangesFallback()
                }

                if (message.id == getStatesMsgId && message.success) {
                    val resultList = message.resultJson?.let { if (it is JsonArray) it else null }
                    if (resultList != null) {
                        val map = mutableMapOf<String, HAEntityState>()
                        for (item in resultList) {
                            try {
                                val state = HAMessageCodec.json.decodeFromJsonElement<HAEntityState>(item)
                                map[state.entityId] = state
                            } catch (_: Exception) {}
                        }
                        _entities.update { map }
                        Log.d(tag, "Loaded ${_entities.value.size} entities from Home Assistant")
                    }
                }
            }

            is HAInboundMessage.PipAlertEvent -> {
                Log.d(tag, "Received CameraPipAlert: ${message.alert.entityId} (${message.alert.title})")
                _pipAlert.update { message.alert }
            }

            is HAInboundMessage.StateChangedEvent -> {
                val entityId = message.entityId
                val newState = message.newState
                _entities.update { current ->
                    if (newState != null) {
                        if (current[entityId] == newState) current else current + (entityId to newState)
                    } else {
                        if (entityId !in current) current else current - entityId
                    }
                }
            }

            is HAInboundMessage.EntityDiffEvent -> {
                _entities.update { current ->
                    HAEntityDiffProcessor.applyDiff(current, message.eventData)
                }
            }

            is HAInboundMessage.Unknown -> {
                Log.d(tag, "Unhandled inbound message type: ${message.rawType}")
            }
        }
    }

    private fun fetchInitialStates() {
        val id = messageId.getAndIncrement()
        getStatesMsgId = id
        transport.send(HAMessageCodec.encodeGetStates(id))
    }

    private fun resubscribeEntities() {
        if (_connectionStatus.value != ConnectionStatus.AUTHENTICATED) return

        val oldSubId = subscribeEntitiesSubId
        if (oldSubId != -1L) {
            val unsubId = messageId.getAndIncrement()
            transport.send(HAMessageCodec.encodeUnsubscribe(unsubId, oldSubId))
            subscribeEntitiesSubId = -1L
        }

        val targets = monitoredEntityIds.filter { HAMessageCodec.isValidEntityId(it) }.toSet()
        if (targets.isEmpty()) {
            Log.d(tag, "No monitored entities configured, subscription idle")
            return
        }

        val newId = messageId.getAndIncrement()
        subscribeEntitiesSubId = newId
        Log.d(tag, "Subscribing to ${targets.size} monitored entities (id=$newId)")
        transport.send(HAMessageCodec.encodeSubscribeEntities(newId, targets))
    }

    private fun subscribeToStateChangesFallback() {
        if (isSubscribedToEventsFallback) return
        val id = messageId.getAndIncrement()
        transport.send(HAMessageCodec.encodeSubscribeEvents(id, "state_changed"))
        isSubscribedToEventsFallback = true
        Log.d(tag, "Subscribed to full state_changed fallback (id=$id)")
    }

    private fun subscribeToNotifyEvents() {
        if (_connectionStatus.value != ConnectionStatus.AUTHENTICATED) return
        val id = messageId.getAndIncrement()
        notifyEventsSubId = id
        transport.send(HAMessageCodec.encodeSubscribeEvents(id, "homekey_tv_notify"))
        Log.d(tag, "Subscribed to homekey_tv_notify events (id=$id)")
    }

    private fun applyOptimistic(entityId: String, transform: (HAEntityState) -> HAEntityState?) {
        if (_connectionStatus.value != ConnectionStatus.AUTHENTICATED) return
        _entities.update { map ->
            val current = map[entityId] ?: return@update map
            val updated = transform(current) ?: return@update map
            if (updated == current) map else map + (entityId to updated)
        }
    }

    override fun toggleEntity(entityId: String) {
        val domain = HAMessageCodec.extractDomain(entityId)
        val currentEntity = _entities.value[entityId]
        val state = currentEntity?.state?.lowercase()

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

    override fun turnOn(entityId: String, brightness: Int?) {
        val domain = HAMessageCodec.extractDomain(entityId)
        applyOptimistic(entityId) { curr ->
            val newAttrs = curr.attributes.toMutableMap()
            if (brightness != null) {
                newAttrs["brightness"] = JsonPrimitive(brightness)
            }
            curr.copy(state = "on", attributes = JsonObject(newAttrs))
        }

        val serviceData = if (brightness != null && domain == "light") {
            HAMessageCodec.buildBrightnessData(brightness)
        } else null

        callService(domain, "turn_on", serviceData, entityId)
    }

    override fun turnOff(entityId: String) {
        val domain = HAMessageCodec.extractDomain(entityId)
        applyOptimistic(entityId) { it.copy(state = "off") }
        callService(domain, "turn_off", entityId = entityId)
    }

    override fun setBrightness(entityId: String, brightness: Int) {
        applyOptimistic(entityId) { curr ->
            val newAttrs = curr.attributes.toMutableMap()
            newAttrs["brightness"] = JsonPrimitive(brightness)
            curr.copy(state = "on", attributes = JsonObject(newAttrs))
        }
        val serviceData = HAMessageCodec.buildBrightnessData(brightness)
        callService("light", "turn_on", serviceData, entityId)
    }

    override fun setTargetTemperature(entityId: String, temperature: Float) {
        applyOptimistic(entityId) { curr ->
            val newAttrs = curr.attributes.toMutableMap()
            newAttrs["temperature"] = JsonPrimitive(temperature)
            curr.copy(attributes = JsonObject(newAttrs))
        }
        val serviceData = HAMessageCodec.buildTemperatureData(temperature)
        callService("climate", "set_temperature", serviceData, entityId)
    }

    override fun setHvacMode(entityId: String, mode: String) {
        val serviceData = HAMessageCodec.buildHvacModeData(mode)
        callService("climate", "set_hvac_mode", serviceData, entityId)
    }

    override fun setFanMode(entityId: String, fanMode: String) {
        val serviceData = HAMessageCodec.buildFanModeData(fanMode)
        callService("climate", "set_fan_mode", serviceData, entityId)
    }

    override fun setPresetMode(entityId: String, presetMode: String) {
        val serviceData = HAMessageCodec.buildPresetModeData(presetMode)
        callService("climate", "set_preset_mode", serviceData, entityId)
    }

    override fun mediaPlayPause(entityId: String) {
        callService("media_player", "media_play_pause", entityId = entityId)
    }

    override fun mediaNext(entityId: String) {
        callService("media_player", "media_next_track", entityId = entityId)
    }

    override fun mediaPrevious(entityId: String) {
        callService("media_player", "media_previous_track", entityId = entityId)
    }

    override fun mediaVolumeUp(entityId: String) {
        callService("media_player", "volume_up", entityId = entityId)
    }

    override fun mediaVolumeDown(entityId: String) {
        callService("media_player", "volume_down", entityId = entityId)
    }

    override fun selectSource(entityId: String, source: String) {
        val serviceData = HAMessageCodec.buildSourceData(source)
        callService("media_player", "select_source", serviceData, entityId)
    }

    override fun setVolume(entityId: String, volume: Float) {
        val serviceData = HAMessageCodec.buildVolumeData(volume)
        callService("media_player", "volume_set", serviceData, entityId)
    }

    override fun mediaMute(entityId: String, mute: Boolean) {
        val serviceData = HAMessageCodec.buildMuteData(mute)
        callService("media_player", "volume_mute", serviceData, entityId)
    }

    override fun callService(
        domain: String,
        service: String,
        serviceData: JsonObject?,
        entityId: String?
    ) {
        val id = messageId.getAndIncrement()
        val cmd = HAMessageCodec.encodeCallService(id, domain, service, serviceData, entityId)
        Log.d(tag, "Sending service call: $cmd")
        transport.send(cmd)
    }
}
