package com.homekey.tv

import com.homekey.tv.data.api.HomeAssistantRepository
import com.homekey.tv.data.models.CameraPipAlert
import com.homekey.tv.data.models.ConnectionStatus
import com.homekey.tv.data.models.HAEntityState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonObject

class FakeHomeAssistantRepository : HomeAssistantRepository {
    private val _connectionStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    override val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private val _entities = MutableStateFlow<Map<String, HAEntityState>>(emptyMap())
    override val entities: StateFlow<Map<String, HAEntityState>> = _entities.asStateFlow()

    private val _pipAlert = MutableStateFlow<CameraPipAlert?>(null)
    override val pipAlert: StateFlow<CameraPipAlert?> = _pipAlert.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    override val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    val serviceCalls = mutableListOf<String>()

    fun setConnectionStatus(status: ConnectionStatus) {
        _connectionStatus.update { status }
    }

    fun setEntities(entityMap: Map<String, HAEntityState>) {
        _entities.update { entityMap }
    }

    override fun connect(serverUrl: String, token: String) {
        _connectionStatus.update { ConnectionStatus.AUTHENTICATED }
    }

    override fun disconnect() {
        _connectionStatus.update { ConnectionStatus.DISCONNECTED }
    }

    override fun dismissPipAlert() {
        _pipAlert.update { null }
    }

    override fun setActiveDialogEntity(entityId: String?) {}

    override fun toggleEntity(entityId: String) {
        serviceCalls.add("toggle:$entityId")
    }

    override fun turnOn(entityId: String, brightness: Int?) {
        serviceCalls.add("turnOn:$entityId:$brightness")
    }

    override fun turnOff(entityId: String) {
        serviceCalls.add("turnOff:$entityId")
    }

    override fun setBrightness(entityId: String, brightness: Int) {
        serviceCalls.add("setBrightness:$entityId:$brightness")
    }

    override fun setTargetTemperature(entityId: String, temperature: Float) {
        serviceCalls.add("setTargetTemperature:$entityId:$temperature")
    }

    override fun setHvacMode(entityId: String, mode: String) {
        serviceCalls.add("setHvacMode:$entityId:$mode")
    }

    override fun setFanMode(entityId: String, fanMode: String) {
        serviceCalls.add("setFanMode:$entityId:$fanMode")
    }

    override fun setPresetMode(entityId: String, presetMode: String) {
        serviceCalls.add("setPresetMode:$entityId:$presetMode")
    }

    override fun mediaPlayPause(entityId: String) {
        serviceCalls.add("mediaPlayPause:$entityId")
    }

    override fun mediaNext(entityId: String) {
        serviceCalls.add("mediaNext:$entityId")
    }

    override fun mediaPrevious(entityId: String) {
        serviceCalls.add("mediaPrevious:$entityId")
    }

    override fun mediaVolumeUp(entityId: String) {
        serviceCalls.add("mediaVolumeUp:$entityId")
    }

    override fun mediaVolumeDown(entityId: String) {
        serviceCalls.add("mediaVolumeDown:$entityId")
    }

    override fun selectSource(entityId: String, source: String) {
        serviceCalls.add("selectSource:$entityId:$source")
    }

    override fun setVolume(entityId: String, volume: Float) {
        serviceCalls.add("setVolume:$entityId:$volume")
    }

    override fun mediaMute(entityId: String, mute: Boolean) {
        serviceCalls.add("mediaMute:$entityId:$mute")
    }

    override fun callService(
        domain: String,
        service: String,
        serviceData: JsonObject?,
        entityId: String?
    ) {
        serviceCalls.add("callService:$domain:$service:$entityId")
    }
}
