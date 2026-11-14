package com.homekey.tv.ui.panel

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.homekey.tv.data.api.MjpegCameraStreamClient
import com.homekey.tv.data.models.HAEntityState
import com.homekey.tv.viewmodel.PanelViewModel

internal sealed interface EntityDialogRoute {
    val isMinimal: Boolean

    data class Camera(override val isMinimal: Boolean) : EntityDialogRoute
    data class Climate(override val isMinimal: Boolean) : EntityDialogRoute
    data class Brightness(override val isMinimal: Boolean) : EntityDialogRoute
    data class Switch(override val isMinimal: Boolean) : EntityDialogRoute
    data class MediaPlayer(override val isMinimal: Boolean) : EntityDialogRoute
    data class Cover(override val isMinimal: Boolean) : EntityDialogRoute
    data class Fan(override val isMinimal: Boolean) : EntityDialogRoute
    data class Lock(override val isMinimal: Boolean) : EntityDialogRoute
    data object Unsupported : EntityDialogRoute {
        override val isMinimal: Boolean = false
    }
}

internal fun resolveEntityDialogRoute(
    entity: HAEntityState,
    isMinimal: Boolean
): EntityDialogRoute = when (entity.domain) {
    "camera" -> EntityDialogRoute.Camera(isMinimal)
    "climate" -> EntityDialogRoute.Climate(isMinimal)
    "light" -> if (entity.supportsBrightness) {
        EntityDialogRoute.Brightness(isMinimal)
    } else {
        EntityDialogRoute.Switch(isMinimal)
    }
    "switch", "input_boolean" -> EntityDialogRoute.Switch(isMinimal)
    "media_player" -> EntityDialogRoute.MediaPlayer(isMinimal)
    "cover" -> EntityDialogRoute.Cover(isMinimal)
    "fan" -> EntityDialogRoute.Fan(isMinimal)
    "lock" -> EntityDialogRoute.Lock(isMinimal)
    else -> EntityDialogRoute.Unsupported
}

@Composable
internal fun EntityControlDialog(
    entity: HAEntityState,
    isMinimal: Boolean,
    isVertical: Boolean = false,
    viewModel: PanelViewModel,
    onDismiss: () -> Unit
) {
    when (val route = resolveEntityDialogRoute(entity, isMinimal)) {
        is EntityDialogRoute.Camera -> {
            val dockConfig by viewModel.dockConfig.collectAsState()
            var reconnectTrigger by remember(entity.entityId) { mutableIntStateOf(0) }
            val maxTargetWidthPx = if (route.isMinimal) {
                1280
            } else {
                MjpegCameraStreamClient.DEFAULT_MAX_TARGET_WIDTH_PX
            }
            val feedFlow = remember(entity.entityId, maxTargetWidthPx, viewModel) {
                viewModel.observeCameraFeed(
                    entityId = entity.entityId,
                    reconnectTrigger = snapshotFlow { reconnectTrigger },
                    maxTargetWidthPx = maxTargetWidthPx
                )
            }
            CameraDialog(
                entity = entity,
                feedFlow = feedFlow,
                isCompact = route.isMinimal,
                compactSize = dockConfig.cameraCompactSize,
                onReconnect = { reconnectTrigger++ },
                onDismiss = onDismiss
            )
        }
        is EntityDialogRoute.Climate -> {
            val activeDialogHumidity by viewModel.activeDialogHumidity.collectAsState()
            if (route.isMinimal) {
                MinimalClimatePopup(
                    entity = entity,
                    humidity = activeDialogHumidity,
                    isVertical = isVertical,
                    onSetTemperature = { viewModel.setTargetTemperature(entity.entityId, it) },
                    onSetHvacMode = { viewModel.setHvacMode(entity.entityId, it) },
                    onSetFanMode = { viewModel.setFanMode(entity.entityId, it) },
                    onDismiss = onDismiss
                )
            } else {
                ClimateDialog(
                    entity = entity,
                    humidity = activeDialogHumidity,
                    onSetTemperature = { viewModel.setTargetTemperature(entity.entityId, it) },
                    onSetHvacMode = { viewModel.setHvacMode(entity.entityId, it) },
                    onSetFanMode = { viewModel.setFanMode(entity.entityId, it) },
                    onSetPresetMode = { viewModel.setPresetMode(entity.entityId, it) },
                    onDismiss = onDismiss
                )
            }
        }
        is EntityDialogRoute.Brightness -> {
            if (route.isMinimal) {
                MinimalBrightnessPopup(
                    entity = entity,
                    isVertical = isVertical,
                    onSetBrightness = { viewModel.setBrightness(entity.entityId, it) },
                    onDismiss = onDismiss
                )
            } else {
                BrightnessDialog(
                    entity = entity,
                    onSetBrightness = { viewModel.setBrightness(entity.entityId, it) },
                    onDismiss = onDismiss
                )
            }
        }
        is EntityDialogRoute.Switch -> {
            if (route.isMinimal) {
                MinimalSwitchPopup(
                    entity = entity,
                    isVertical = isVertical,
                    onToggle = { viewModel.toggleEntity(entity.entityId) },
                    onTurnOn = { viewModel.turnOnEntity(entity.entityId) },
                    onTurnOff = { viewModel.turnOffEntity(entity.entityId) },
                    onDismiss = onDismiss
                )
            } else {
                SwitchDialog(
                    entity = entity,
                    onToggle = { viewModel.toggleEntity(entity.entityId) },
                    onTurnOn = { viewModel.turnOnEntity(entity.entityId) },
                    onTurnOff = { viewModel.turnOffEntity(entity.entityId) },
                    onDismiss = onDismiss
                )
            }
        }
        is EntityDialogRoute.MediaPlayer -> {
            if (route.isMinimal) {
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
            } else {
                MediaPlayerDialog(
                    entity = entity,
                    onSelectSource = { source -> viewModel.selectMediaSource(entity.entityId, source) },
                    onSetVolume = { volume -> viewModel.setMediaVolume(entity.entityId, volume) },
                    onTogglePlayPause = { viewModel.toggleMediaPlayPause(entity.entityId) },
                    onNextTrack = { viewModel.mediaNext(entity.entityId) },
                    onPreviousTrack = { viewModel.mediaPrevious(entity.entityId) },
                    onToggleMute = { viewModel.toggleMediaMute(entity.entityId, entity.isVolumeMuted) },
                    onTurnOn = { viewModel.turnOnEntity(entity.entityId) },
                    onTurnOff = { viewModel.turnOffEntity(entity.entityId) },
                    onDismiss = onDismiss
                )
            }
        }
        is EntityDialogRoute.Cover -> {
            if (route.isMinimal) {
                MinimalCoverPopup(
                    entity = entity,
                    isVertical = isVertical,
                    onOpen = { viewModel.openCover(entity.entityId) },
                    onClose = { viewModel.closeCover(entity.entityId) },
                    onStop = { viewModel.stopCover(entity.entityId) },
                    onSetPosition = { viewModel.setCoverPosition(entity.entityId, it) },
                    onDismiss = onDismiss
                )
            } else {
                CoverDialog(
                    entity = entity,
                    onOpen = { viewModel.openCover(entity.entityId) },
                    onClose = { viewModel.closeCover(entity.entityId) },
                    onStop = { viewModel.stopCover(entity.entityId) },
                    onSetPosition = { viewModel.setCoverPosition(entity.entityId, it) },
                    onSetTilt = { viewModel.setCoverTiltPosition(entity.entityId, it) },
                    onDismiss = onDismiss
                )
            }
        }
        is EntityDialogRoute.Fan -> {
            if (route.isMinimal) {
                MinimalFanPopup(
                    entity = entity,
                    isVertical = isVertical,
                    onToggle = { viewModel.toggleEntity(entity.entityId) },
                    onSetPercentage = { viewModel.setFanPercentage(entity.entityId, it) },
                    onDismiss = onDismiss
                )
            } else {
                FanDialog(
                    entity = entity,
                    onToggle = { viewModel.toggleEntity(entity.entityId) },
                    onSetPercentage = { viewModel.setFanPercentage(entity.entityId, it) },
                    onSetOscillating = { viewModel.setFanOscillating(entity.entityId, it) },
                    onDismiss = onDismiss
                )
            }
        }
        is EntityDialogRoute.Lock -> {
            if (route.isMinimal) {
                MinimalLockPopup(
                    entity = entity,
                    isVertical = isVertical,
                    onLock = { viewModel.lock(entity.entityId) },
                    onUnlock = { viewModel.unlock(entity.entityId) },
                    onDismiss = onDismiss
                )
            } else {
                LockDialog(
                    entity = entity,
                    onLock = { viewModel.lock(entity.entityId) },
                    onUnlock = { viewModel.unlock(entity.entityId) },
                    onDismiss = onDismiss
                )
            }
        }
        EntityDialogRoute.Unsupported -> Unit
    }
}
