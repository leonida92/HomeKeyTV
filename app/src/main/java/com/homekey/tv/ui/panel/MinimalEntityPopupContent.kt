package com.homekey.tv.ui.panel

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.homekey.tv.data.models.HAEntityState
import com.homekey.tv.viewmodel.PanelViewModel

@Composable
internal fun MinimalEntityPopupContent(
    entity: HAEntityState,
    isCompactCamera: Boolean,
    isVertical: Boolean = false,
    viewModel: PanelViewModel,
    onDismiss: () -> Unit
) {
    when {
        isCompactCamera -> {
            val serverUrl by viewModel.serverUrl.collectAsState()
            val accessToken by viewModel.accessToken.collectAsState()
            val cameraCompactSize by viewModel.cameraCompactSize.collectAsState()
            CameraDialog(
                entity = entity,
                serverUrl = serverUrl,
                accessToken = accessToken,
                isCompact = true,
                compactSize = cameraCompactSize,
                onDismiss = onDismiss
            )
        }
        entity.domain == "climate" -> {
            val allEntities by viewModel.allEntities.collectAsState()
            val fallbackHumidity = remember(entity.entityId, allEntities) {
                if (entity.currentHumidity != null) null
                else {
                    val baseId = entity.entityId.removePrefix("climate.")
                    allEntities["sensor.${baseId}_humidity"]?.state?.toFloatOrNull()
                        ?: allEntities.values.firstOrNull {
                            it.domain == "sensor" &&
                            it.attributes["device_class"]?.toString()?.contains("humidity", ignoreCase = true) == true &&
                            (it.entityId.contains(baseId, ignoreCase = true) || it.friendlyName.contains(entity.friendlyName, ignoreCase = true))
                        }?.state?.toFloatOrNull()
                }
            }
            MinimalClimatePopup(
                entity = entity,
                humidity = entity.currentHumidity ?: fallbackHumidity,
                isVertical = isVertical,
                onSetTemperature = { viewModel.setTargetTemperature(entity.entityId, it) },
                onSetHvacMode = { viewModel.setHvacMode(entity.entityId, it) },
                onSetFanMode = { viewModel.setFanMode(entity.entityId, it) },
                onDismiss = onDismiss
            )
        }
        entity.domain == "light" && entity.supportsBrightness -> {
            MinimalBrightnessPopup(
                entity = entity,
                isVertical = isVertical,
                onSetBrightness = { viewModel.setBrightness(entity.entityId, it) },
                onDismiss = onDismiss
            )
        }
        entity.domain == "switch" || entity.domain == "input_boolean" || (entity.domain == "light" && !entity.supportsBrightness) -> {
            MinimalSwitchPopup(
                entity = entity,
                isVertical = isVertical,
                onToggle = { viewModel.toggleEntity(entity.entityId) },
                onTurnOn = { viewModel.turnOnEntity(entity.entityId) },
                onTurnOff = { viewModel.turnOffEntity(entity.entityId) },
                onDismiss = onDismiss
            )
        }
        entity.domain == "media_player" -> {
            MinimalMediaPopup(
                entity = entity,
                isVertical = isVertical,
                onTogglePlayPause = { viewModel.toggleMediaPlayPause(entity.entityId) },
                onNextTrack = { viewModel.mediaNext(entity.entityId) },
                onPreviousTrack = { viewModel.mediaPrevious(entity.entityId) },
                onSetVolume = { viewModel.setMediaVolume(entity.entityId, it) },
                onToggleMute = { viewModel.toggleMediaMute(entity.entityId, entity.isVolumeMuted) },
                onDismiss = onDismiss
            )
        }
        entity.domain == "cover" -> {
            MinimalCoverPopup(
                entity = entity,
                isVertical = isVertical,
                onOpen = { viewModel.openCover(entity.entityId) },
                onClose = { viewModel.closeCover(entity.entityId) },
                onStop = { viewModel.stopCover(entity.entityId) },
                onSetPosition = { viewModel.setCoverPosition(entity.entityId, it) },
                onDismiss = onDismiss
            )
        }
        entity.domain == "fan" -> {
            MinimalFanPopup(
                entity = entity,
                isVertical = isVertical,
                onToggle = { viewModel.toggleEntity(entity.entityId) },
                onSetPercentage = { viewModel.setFanPercentage(entity.entityId, it) },
                onDismiss = onDismiss
            )
        }
        entity.domain == "lock" -> {
            MinimalLockPopup(
                entity = entity,
                isVertical = isVertical,
                onLock = { viewModel.lock(entity.entityId) },
                onUnlock = { viewModel.unlock(entity.entityId) },
                onDismiss = onDismiss
            )
        }
        else -> {}
    }
}
