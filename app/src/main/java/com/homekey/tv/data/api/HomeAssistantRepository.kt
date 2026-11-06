package com.homekey.tv.data.api

import com.homekey.tv.data.models.CameraPipAlert
import com.homekey.tv.data.models.ConnectionStatus
import com.homekey.tv.data.models.HAEntityState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject

interface HomeAssistantRepository {
    val connectionStatus: StateFlow<ConnectionStatus>
    val entities: StateFlow<Map<String, HAEntityState>>
    val pipAlert: StateFlow<CameraPipAlert?>
    val errorMessage: StateFlow<String?>

    fun connect(serverUrl: String, token: String)
    fun disconnect()
    fun dismissPipAlert()
    fun setActiveDialogEntity(entityId: String?)

    fun toggleEntity(entityId: String)
    fun turnOn(entityId: String, brightness: Int? = null)
    fun turnOff(entityId: String)
    fun setBrightness(entityId: String, brightness: Int)
    fun setTargetTemperature(entityId: String, temperature: Float)
    fun setHvacMode(entityId: String, mode: String)
    fun setFanMode(entityId: String, fanMode: String)
    fun setPresetMode(entityId: String, presetMode: String)
    fun mediaPlayPause(entityId: String)
    fun mediaNext(entityId: String)
    fun mediaPrevious(entityId: String)
    fun mediaVolumeUp(entityId: String)
    fun mediaVolumeDown(entityId: String)
    fun selectSource(entityId: String, source: String)
    fun setVolume(entityId: String, volume: Float)
    fun mediaMute(entityId: String, mute: Boolean)

    // Extended Domain Controls (ADR 0004)
    fun openCover(entityId: String)
    fun closeCover(entityId: String)
    fun stopCover(entityId: String)
    fun setCoverPosition(entityId: String, position: Int)
    fun setCoverTiltPosition(entityId: String, tilt: Int)
    fun setFanPercentage(entityId: String, percentage: Int)
    fun setFanOscillating(entityId: String, oscillating: Boolean)
    fun lock(entityId: String)
    fun unlock(entityId: String)

    fun callService(
        domain: String,
        service: String,
        serviceData: JsonObject? = null,
        entityId: String? = null
    )

    companion object {
        @Volatile
        private var instance: HomeAssistantRepository? = null

        fun getInstance(): HomeAssistantRepository {
            return instance ?: synchronized(this) {
                instance ?: error("HomeAssistantRepository not initialized")
            }
        }

        fun initialize(repository: HomeAssistantRepository) {
            instance = repository
        }
    }
}
