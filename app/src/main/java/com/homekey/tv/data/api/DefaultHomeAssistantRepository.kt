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
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class DefaultHomeAssistantRepository(
    private val transport: HATransport,
    private val prefs: PreferencesRepository,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    private val endpointProbe: suspend (String) -> Boolean = { url -> defaultHttpProbe(url) }
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

    private val _activeServerUrl = MutableStateFlow("")
    override val activeServerUrl: StateFlow<String> = _activeServerUrl.asStateFlow()

    private val _entities = MutableStateFlow<Map<String, HAEntityState>>(emptyMap())
    override val entities: StateFlow<Map<String, HAEntityState>> = _entities.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    override val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    @Volatile private var primaryUrl: String = ""
    @Volatile private var fallbackUrl: String = ""
    @Volatile private var currentToken: String = ""
    @Volatile private var isUsingFallback: Boolean = false
    @Volatile private var primaryConsecutiveFailures: Int = 0
    @Volatile private var reconnectAttempt = 0
    @Volatile private var reconnectJob: Job? = null
    @Volatile private var timeoutJob: Job? = null
    @Volatile private var failbackJob: Job? = null

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
                        Log.e(tag, "Transport failure on ${_activeServerUrl.value}: ${event.throwable.message}", event.throwable)
                        timeoutJob?.cancel()
                        _connectionStatus.update { ConnectionStatus.FAILED }
                        _errorMessage.update { event.throwable.localizedMessage ?: "Connection failed" }
                        if (!isUsingFallback && fallbackUrl.isNotBlank()) {
                            primaryConsecutiveFailures++
                            if (primaryConsecutiveFailures >= 2) {
                                Log.w(tag, "Primary failed ($primaryConsecutiveFailures times). Triggering immediate failover to $fallbackUrl")
                                triggerFailover()
                                return@collect
                            }
                        }
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

    override fun connect(serverUrl: String, fallbackServerUrl: String, token: String) {
        val cleanPrimary = serverUrl.trim().removeSuffix("/")
        val cleanFallback = fallbackServerUrl.trim().removeSuffix("/")
        val cleanToken = token.trim()

        if ((cleanPrimary.isBlank() && cleanFallback.isBlank()) || cleanToken.isBlank()) {
            _connectionStatus.update { ConnectionStatus.FAILED }
            _errorMessage.update { "Server URL and Access Token are required" }
            return
        }

        val activeTarget = if (cleanPrimary.isNotBlank()) cleanPrimary else cleanFallback
        if (_connectionStatus.value == ConnectionStatus.AUTHENTICATED &&
            primaryUrl == cleanPrimary && fallbackUrl == cleanFallback && currentToken == cleanToken &&
            _activeServerUrl.value == activeTarget) {
            Log.d(tag, "Already authenticated and connected to $activeTarget")
            return
        }

        primaryUrl = cleanPrimary
        fallbackUrl = cleanFallback
        currentToken = cleanToken
        reconnectAttempt = 0
        primaryConsecutiveFailures = 0

        if (primaryUrl.isBlank() && fallbackUrl.isNotBlank()) {
            isUsingFallback = true
            startConnection(fallbackUrl)
            return
        }

        isUsingFallback = false
        startConnection(primaryUrl)
    }

    private fun startConnection(targetUrl: String) {
        disconnect(updateStatus = false)

        _activeServerUrl.update { targetUrl }
        val wsUrl = buildWsUrl(targetUrl)
        Log.d(tag, "Connecting to WebSocket: $wsUrl (fallback=$isUsingFallback)")
        _connectionStatus.update { ConnectionStatus.CONNECTING }
        _errorMessage.update { null }

        timeoutJob?.cancel()
        if (!isUsingFallback && fallbackUrl.isNotBlank()) {
            timeoutJob = scope.launch {
                delay(FAILOVER_TIMEOUT_MS)
                if (_connectionStatus.value != ConnectionStatus.AUTHENTICATED && !isUsingFallback) {
                    Log.w(tag, "Primary LAN connection timed out after ${FAILOVER_TIMEOUT_MS}ms. Triggering failover to $fallbackUrl")
                    triggerFailover()
                }
            }
        }

        transport.connect(wsUrl)
    }

    private fun triggerFailover() {
        if (fallbackUrl.isBlank() || isUsingFallback) return
        timeoutJob?.cancel()
        isUsingFallback = true
        reconnectAttempt = 0
        startConnection(fallbackUrl)
    }

    override fun disconnect() = disconnect(updateStatus = true)

    private fun disconnect(updateStatus: Boolean) {
        timeoutJob?.cancel()
        timeoutJob = null
        reconnectJob?.cancel()
        reconnectJob = null
        stopOpportunisticFailbackLoop()
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

    override fun notifyOverlayShown() {
        if (isUsingFallback && primaryUrl.isNotBlank()) {
            probeAndFailback()
        }
    }

    override fun probeLocalFailback() {
        probeAndFailback()
    }

    private fun startOpportunisticFailbackLoop() {
        failbackJob?.cancel()
        failbackJob = scope.launch {
            while (isActive && isUsingFallback && primaryUrl.isNotBlank()) {
                delay(FAILBACK_CADENCE_MS)
                probeAndFailback()
            }
        }
    }

    private fun stopOpportunisticFailbackLoop() {
        failbackJob?.cancel()
        failbackJob = null
    }

    private fun probeAndFailback() {
        if (!isUsingFallback || primaryUrl.isBlank()) return
        scope.launch {
            val reachable = try {
                endpointProbe(primaryUrl)
            } catch (_: Exception) {
                false
            }
            if (reachable && isUsingFallback) {
                Log.d(tag, "Primary LAN endpoint $primaryUrl is reachable. Returning from fallback.")
                stopOpportunisticFailbackLoop()
                isUsingFallback = false
                primaryConsecutiveFailures = 0
                reconnectAttempt = 0
                startConnection(primaryUrl)
            }
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

    private fun scheduleReconnect() {
        val targetUrl = if (isUsingFallback) fallbackUrl.ifBlank { primaryUrl } else primaryUrl
        if (targetUrl.isBlank() || currentToken.isBlank()) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            val backoffSeconds = (2L shl reconnectAttempt.coerceAtMost(4)).coerceAtMost(30L)
            reconnectAttempt++
            Log.d(tag, "Attempting reconnect in ${backoffSeconds}s (attempt $reconnectAttempt) to $targetUrl...")
            delay(backoffSeconds * 1000)
            startConnection(targetUrl)
        }
    }

    private fun handleInboundMessage(message: HAInboundMessage) {
        when (message) {
            is HAInboundMessage.AuthRequired -> {
                Log.d(tag, "Auth required, sending access token")
                transport.send(HAMessageCodec.encodeAuth(currentToken))
            }

            is HAInboundMessage.AuthOk -> {
                Log.d(tag, "Auth OK on ${_activeServerUrl.value}! Requesting initial states & subscribing to updates")
                timeoutJob?.cancel()
                reconnectAttempt = 0
                primaryConsecutiveFailures = 0
                _connectionStatus.update { ConnectionStatus.AUTHENTICATED }
                _errorMessage.update { null }
                fetchInitialStates()
                resubscribeEntities()
                subscribeToNotifyEvents()

                if (isUsingFallback) {
                    startOpportunisticFailbackLoop()
                } else {
                    stopOpportunisticFailbackLoop()
                }
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

        sendServiceCall(serviceDomain, service, entityId = entityId)
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

        sendServiceCall(domain, "turn_on", serviceData, entityId)
    }

    override fun turnOff(entityId: String) {
        val domain = HAMessageCodec.extractDomain(entityId)
        applyOptimistic(entityId) { it.copy(state = "off") }
        sendServiceCall(domain, "turn_off", entityId = entityId)
    }

    override fun setBrightness(entityId: String, brightness: Int) {
        applyOptimistic(entityId) { curr ->
            val newAttrs = curr.attributes.toMutableMap()
            newAttrs["brightness"] = JsonPrimitive(brightness)
            curr.copy(state = "on", attributes = JsonObject(newAttrs))
        }
        val serviceData = HAMessageCodec.buildBrightnessData(brightness)
        sendServiceCall("light", "turn_on", serviceData, entityId)
    }

    override fun setTargetTemperature(entityId: String, temperature: Float) {
        applyOptimistic(entityId) { curr ->
            val newAttrs = curr.attributes.toMutableMap()
            newAttrs["temperature"] = JsonPrimitive(temperature)
            curr.copy(attributes = JsonObject(newAttrs))
        }
        val serviceData = HAMessageCodec.buildTemperatureData(temperature)
        sendServiceCall("climate", "set_temperature", serviceData, entityId)
    }

    override fun setHvacMode(entityId: String, mode: String) {
        val serviceData = HAMessageCodec.buildHvacModeData(mode)
        sendServiceCall("climate", "set_hvac_mode", serviceData, entityId)
    }

    override fun setFanMode(entityId: String, fanMode: String) {
        val serviceData = HAMessageCodec.buildFanModeData(fanMode)
        sendServiceCall("climate", "set_fan_mode", serviceData, entityId)
    }

    override fun setPresetMode(entityId: String, presetMode: String) {
        val serviceData = HAMessageCodec.buildPresetModeData(presetMode)
        sendServiceCall("climate", "set_preset_mode", serviceData, entityId)
    }

    override fun mediaPlayPause(entityId: String) {
        sendServiceCall("media_player", "media_play_pause", entityId = entityId)
    }

    override fun mediaNext(entityId: String) {
        sendServiceCall("media_player", "media_next_track", entityId = entityId)
    }

    override fun mediaPrevious(entityId: String) {
        sendServiceCall("media_player", "media_previous_track", entityId = entityId)
    }

    override fun mediaVolumeUp(entityId: String) {
        sendServiceCall("media_player", "volume_up", entityId = entityId)
    }

    override fun mediaVolumeDown(entityId: String) {
        sendServiceCall("media_player", "volume_down", entityId = entityId)
    }

    override fun selectSource(entityId: String, source: String) {
        val serviceData = HAMessageCodec.buildSourceData(source)
        sendServiceCall("media_player", "select_source", serviceData, entityId)
    }

    override fun setVolume(entityId: String, volume: Float) {
        val serviceData = HAMessageCodec.buildVolumeData(volume)
        sendServiceCall("media_player", "volume_set", serviceData, entityId)
    }

    override fun mediaMute(entityId: String, mute: Boolean) {
        val serviceData = HAMessageCodec.buildMuteData(mute)
        sendServiceCall("media_player", "volume_mute", serviceData, entityId)
    }

    override fun openCover(entityId: String) {
        sendServiceCall("cover", "open_cover", entityId = entityId)
    }

    override fun closeCover(entityId: String) {
        sendServiceCall("cover", "close_cover", entityId = entityId)
    }

    override fun stopCover(entityId: String) {
        sendServiceCall("cover", "stop_cover", entityId = entityId)
    }

    override fun setCoverPosition(entityId: String, position: Int) {
        val serviceData = HAMessageCodec.buildPositionData(position)
        sendServiceCall("cover", "set_cover_position", serviceData, entityId)
    }

    override fun setCoverTiltPosition(entityId: String, tilt: Int) {
        val serviceData = HAMessageCodec.buildTiltData(tilt)
        sendServiceCall("cover", "set_cover_tilt_position", serviceData, entityId)
    }

    override fun setFanPercentage(entityId: String, percentage: Int) {
        val serviceData = HAMessageCodec.buildPercentageData(percentage)
        sendServiceCall("fan", "set_percentage", serviceData, entityId)
    }

    override fun setFanOscillating(entityId: String, oscillating: Boolean) {
        val serviceData = HAMessageCodec.buildOscillateData(oscillating)
        sendServiceCall("fan", "oscillate", serviceData, entityId)
    }

    override fun lock(entityId: String) {
        sendServiceCall("lock", "lock", entityId = entityId)
    }

    override fun unlock(entityId: String) {
        sendServiceCall("lock", "unlock", entityId = entityId)
    }

    override fun callService(
        domain: String,
        service: String,
        entityId: String?
    ) {
        sendServiceCall(domain, service, serviceData = null, entityId = entityId)
    }

    private fun sendServiceCall(
        domain: String,
        service: String,
        serviceData: JsonObject? = null,
        entityId: String? = null
    ) {
        val id = messageId.getAndIncrement()
        val cmd = HAMessageCodec.encodeCallService(id, domain, service, serviceData, entityId)
        Log.d(tag, "Sending service call: $cmd")
        transport.send(cmd)
    }

    companion object {
        const val FAILOVER_TIMEOUT_MS = 3000L
        const val FAILBACK_CADENCE_MS = 60000L

        private val probeClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(2000, TimeUnit.MILLISECONDS)
                .readTimeout(2000, TimeUnit.MILLISECONDS)
                .build()
        }

        private fun defaultHttpProbe(baseUrl: String): Boolean {
            if (baseUrl.isBlank()) return false
            return try {
                val probeUrl = baseUrl.trim().removeSuffix("/") + "/manifest.json"
                val request = okhttp3.Request.Builder().url(probeUrl).head().build()
                probeClient.newCall(request).execute().use { response ->
                    response.isSuccessful || response.code in 400..499
                }
            } catch (_: Exception) {
                false
            }
        }
    }
}
