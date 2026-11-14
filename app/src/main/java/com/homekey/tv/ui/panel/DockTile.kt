package com.homekey.tv.ui.panel

import android.view.KeyEvent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import com.homekey.tv.data.models.DomainColorPalette
import com.homekey.tv.data.models.LocalThemePalette
import com.homekey.tv.ui.theme.HA_Yellow_On
import com.homekey.tv.viewmodel.DockItem
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val DIALOG_LONG_PRESS_MS = 550L

@Composable
fun DockTile(
    item: DockItem,
    index: Int,
    isReorderMode: Boolean,
    isSelectedForReorder: Boolean,
    isVertical: Boolean,
    customFocusRequester: FocusRequester?,
    isDialogActive: Boolean = false,
    activeDialogEntityId: String? = null,
    onDirectionToRecent: (() -> Unit)? = null,
    directionToRecentKeyCode: Int = KeyEvent.KEYCODE_DPAD_UP,
    onWrapToLast: () -> Unit,
    onFocused: (String, String, Float?) -> Unit,
    onUnfocused: (String) -> Unit,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)? = null,
    onSelectForReorder: () -> Unit,
    onMove: (Int) -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val name = item.displayName
    val isApp = item.isApp
    val entity = item.entity

    val isSelfActive = item.entity != null && item.entity.entityId == activeDialogEntityId
    val isOtherItemActive = activeDialogEntityId != null && !isSelfActive

    val tileAlpha by animateFloatAsState(
        targetValue = if (isOtherItemActive) 0.22f else 1.0f,
        animationSpec = tween(durationMillis = 150),
        label = "tile_alpha"
    )

    // Long-press state (hold OK to open the control dialog).
    val scope = rememberCoroutineScope()
    var longPressJob by remember(item.id) { mutableStateOf<Job?>(null) }
    var suppressClickAfterLongPress by remember(item.id) { mutableStateOf(false) }

    val isOn = if (isApp) true else (entity?.isOn == true)
    val isUnavailable = if (isApp) false else (entity?.isUnavailable == true)
    val appIconBitmap = if (isApp) rememberAppIconBitmap(item.packageName) else null

    LaunchedEffect(isFocused, entity?.state, entity?.currentTemperature, entity?.currentHumidity, item.resolvedHumidity) {
        if (isFocused) {
            var humidity: Float? = null
            val stateText = if (isApp) {
                "Open App"
            } else {
                when {
                    isUnavailable -> "Offline"
                    entity?.domain == "light" && isOn && entity.brightness != null -> "${((entity.brightness!! / 255f) * 100).toInt()}%"
                    entity?.domain == "climate" -> {
                        val temp = "${entity.currentTemperature ?: entity.targetTemperature ?: 0f}°"
                        humidity = item.resolvedHumidity ?: entity.currentHumidity
                        temp
                    }
                    entity?.domain == "sensor" -> "${entity.state} ${entity.unitOfMeasurement ?: ""}".trim()
                    else -> if (isOn) "ON" else "OFF"
                }
            }
            onFocused(name, stateText, humidity)
        } else {
            onUnfocused(name)
        }
    }

    val scale by animateFloatAsState(
        targetValue = if (isSelectedForReorder) 1.20f else if (isFocused) 1.12f else 1.0f,
        animationSpec = tween(durationMillis = 80, easing = FastOutSlowInEasing),
        label = "dock_scale"
    )

    val palette = LocalThemePalette.current
    val offColor = palette.getOffBackgroundColor()
    val isOffDark = DomainColorPalette.isColorDark(offColor)
    val focusedOffColor = remember(offColor, isOffDark) {
        if (isOffDark) {
            Color(
                red = (offColor.red + 0.08f).coerceAtMost(1f),
                green = (offColor.green + 0.08f).coerceAtMost(1f),
                blue = (offColor.blue + 0.12f).coerceAtMost(1f),
                alpha = 1f
            )
        } else {
            Color(
                red = (offColor.red - 0.10f).coerceAtLeast(0f),
                green = (offColor.green - 0.10f).coerceAtLeast(0f),
                blue = (offColor.blue - 0.10f).coerceAtLeast(0f),
                alpha = 1f
            )
        }
    }

    val backgroundColor by animateColorAsState(
        targetValue = when {
            isUnavailable -> offColor
            isSelectedForReorder -> Color(0xFFD97706)
            isApp && isFocused -> Color(0xFF2C3852)
            isApp -> offColor
            isFocused && isOn -> getActiveColor(entity?.domain ?: "", palette)
            isFocused && !isOn -> focusedOffColor
            isOn -> getActiveColor(entity?.domain ?: "", palette)
            else -> offColor
        },
        animationSpec = tween(durationMillis = 80),
        label = "tile_bg"
    )

    val borderColor by animateColorAsState(
        targetValue = when {
            isSelectedForReorder -> HA_Yellow_On
            isFocused -> Color.White
            else -> Color.Transparent
        },
        animationSpec = tween(durationMillis = 80),
        label = "tile_border"
    )

    val modifierWithFocus = if (customFocusRequester != null) {
        Modifier.focusRequester(customFocusRequester)
    } else Modifier

    Box(
        modifier = Modifier
            .size(62.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = tileAlpha
            }
            .blur(if (isOtherItemActive) 6.dp else 0.dp)
            .then(modifierWithFocus)
            .clip(RoundedCornerShape(16.dp))
            .background(backgroundColor)
            .border(
                width = if (isSelectedForReorder) 3.5.dp else if (isFocused) 2.6.dp else 0.dp,
                color = borderColor,
                shape = RoundedCornerShape(16.dp)
            )
            .onPreviewKeyEvent { keyEvent ->
                val native = keyEvent.nativeKeyEvent
                val type = keyEvent.type
                val isCenter = native.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                        native.keyCode == KeyEvent.KEYCODE_ENTER ||
                        native.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER

                // Hold OK on a light/climate tile to open its control dialog. If the dialog opens
                // while the key is still held, swallow the following KeyUp so the clickable's normal
                // toggle does not fire as well.
                if (isCenter && onLongPress != null && !isReorderMode) {
                    if (type == KeyEventType.KeyDown && native.repeatCount == 0) {
                        suppressClickAfterLongPress = false
                        longPressJob?.cancel()
                        longPressJob = scope.launch {
                            delay(DIALOG_LONG_PRESS_MS)
                            suppressClickAfterLongPress = true
                            onLongPress()
                        }
                    } else if (type == KeyEventType.KeyUp) {
                        val wasLongPressed = suppressClickAfterLongPress
                        longPressJob?.cancel()
                        longPressJob = null
                        if (wasLongPressed) {
                            suppressClickAfterLongPress = false
                            return@onPreviewKeyEvent true
                        }
                    }
                }

                if (type == KeyEventType.KeyDown) {
                    if (isReorderMode) {
                        if (isCenter) {
                            onSelectForReorder()
                            return@onPreviewKeyEvent true
                        }
                        if (isSelectedForReorder) {
                            when (native.keyCode) {
                                KeyEvent.KEYCODE_DPAD_LEFT -> {
                                    if (!isVertical) {
                                        onMove(-1)
                                        return@onPreviewKeyEvent true
                                    }
                                }
                                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                    if (!isVertical) {
                                        onMove(+1)
                                        return@onPreviewKeyEvent true
                                    }
                                }
                                KeyEvent.KEYCODE_DPAD_UP -> {
                                    if (isVertical) {
                                        onMove(-1)
                                        return@onPreviewKeyEvent true
                                    }
                                }
                                KeyEvent.KEYCODE_DPAD_DOWN -> {
                                    if (isVertical) {
                                        onMove(+1)
                                        return@onPreviewKeyEvent true
                                    }
                                }
                            }
                        }
                    } else {
                        // Direction to recent apps
                        if (onDirectionToRecent != null && native.keyCode == directionToRecentKeyCode) {
                            onDirectionToRecent()
                            return@onPreviewKeyEvent true
                        }

                        // Normal infinite focus wrap
                        if (!isVertical && index == 0 && native.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                            onWrapToLast()
                            return@onPreviewKeyEvent true
                        } else if (isVertical && index == 0 && native.keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                            onWrapToLast()
                            return@onPreviewKeyEvent true
                        }
                    }
                }
                false
            }
            .focusable(
                enabled = !isDialogActive,
                interactionSource = interactionSource
            )
            .clickable(
                enabled = !isDialogActive,
                interactionSource = interactionSource,
                indication = null,
                onClick = {
                    if (!isReorderMode) {
                        onClick()
                    }
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        if (isApp && appIconBitmap != null) {
            Image(
                bitmap = appIconBitmap,
                contentDescription = name,
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(8.dp))
            )
        } else {
            val icon = if (isApp) {
                Icons.Default.Apps
            } else {
                resolveDockIcon(item.customIcon, entity?.icon, entity?.domain ?: "")
            }
            val activeIconTint = palette.getIconColor()
            val offIconTint = remember(isOffDark) {
                if (isOffDark) Color(0xFF94A3B8) else Color(0xFF475569)
            }
            val focusedOffIconTint = remember(isOffDark) {
                if (isOffDark) Color.White else Color(0xFF0F172A)
            }

            Icon(
                imageVector = icon,
                contentDescription = name,
                tint = when {
                    isSelectedForReorder -> Color.White
                    isOn -> activeIconTint
                    isFocused -> focusedOffIconTint
                    else -> offIconTint
                },
                modifier = Modifier.size(30.dp)
            )
        }
    }
}

@Composable
fun DockReorderTile(
    focusRequester: FocusRequester? = null,
    isReorderMode: Boolean,
    isDialogActive: Boolean = false,
    activeDialogEntityId: String? = null,
    onDirectionToRecent: (() -> Unit)? = null,
    directionToRecentKeyCode: Int = KeyEvent.KEYCODE_DPAD_UP,
    onFocused: () -> Unit,
    onUnfocused: () -> Unit,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val isOtherItemActive = activeDialogEntityId != null

    val tileAlpha by animateFloatAsState(
        targetValue = if (isOtherItemActive) 0.22f else 1.0f,
        animationSpec = tween(durationMillis = 150),
        label = "reorder_tile_alpha"
    )

    LaunchedEffect(isFocused) {
        if (isFocused) onFocused() else onUnfocused()
    }

    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.12f else 1.0f,
        animationSpec = tween(durationMillis = 80, easing = FastOutSlowInEasing),
        label = "reorder_scale"
    )

    val palette = LocalThemePalette.current
    val offColor = palette.getOffBackgroundColor()
    val isOffDark = DomainColorPalette.isColorDark(offColor)
    val focusedOffColor = remember(offColor, isOffDark) {
        if (isOffDark) {
            Color(
                red = (offColor.red + 0.08f).coerceAtMost(1f),
                green = (offColor.green + 0.08f).coerceAtMost(1f),
                blue = (offColor.blue + 0.12f).coerceAtMost(1f),
                alpha = 1f
            )
        } else {
            Color(
                red = (offColor.red - 0.10f).coerceAtLeast(0f),
                green = (offColor.green - 0.10f).coerceAtLeast(0f),
                blue = (offColor.blue - 0.10f).coerceAtLeast(0f),
                alpha = 1f
            )
        }
    }

    Box(
        modifier = Modifier
            .size(62.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = tileAlpha
            }
            .blur(if (isOtherItemActive) 6.dp else 0.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (isReorderMode) Color(0xFFD97706)
                else if (isFocused) focusedOffColor
                else offColor
            )
            .border(
                width = if (isReorderMode) 3.dp else if (isFocused) 2.6.dp else 0.dp,
                color = if (isReorderMode) HA_Yellow_On else if (isFocused) Color.White else Color.Transparent,
                shape = RoundedCornerShape(16.dp)
            )
            .onPreviewKeyEvent { keyEvent ->
                if (keyEvent.type == KeyEventType.KeyDown) {
                    val native = keyEvent.nativeKeyEvent
                    val isCenter = native.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                            native.keyCode == KeyEvent.KEYCODE_ENTER ||
                            native.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
                    if (isCenter) {
                        onClick()
                        return@onPreviewKeyEvent true
                    }
                    if (!isReorderMode && onDirectionToRecent != null && native.keyCode == directionToRecentKeyCode) {
                        onDirectionToRecent()
                        return@onPreviewKeyEvent true
                    }
                }
                false
            }
            .focusable(
                enabled = !isDialogActive,
                interactionSource = interactionSource
            )
            .clickable(
                enabled = !isDialogActive,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.SwapHoriz,
            contentDescription = "Reorder",
            tint = if (isReorderMode || isFocused) (if (isOffDark) Color.White else Color(0xFF0F172A)) else (if (isOffDark) Color(0xFF94A3B8) else Color(0xFF475569)),
            modifier = Modifier.size(28.dp)
        )
    }
}

@Composable
fun DockSettingsTile(
    focusRequester: FocusRequester,
    isVertical: Boolean,
    isDialogActive: Boolean = false,
    activeDialogEntityId: String? = null,
    onDirectionToRecent: (() -> Unit)? = null,
    directionToRecentKeyCode: Int = KeyEvent.KEYCODE_DPAD_UP,
    onWrapToFirst: () -> Unit,
    onFocused: () -> Unit,
    onUnfocused: () -> Unit,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val isOtherItemActive = activeDialogEntityId != null

    val tileAlpha by animateFloatAsState(
        targetValue = if (isOtherItemActive) 0.22f else 1.0f,
        animationSpec = tween(durationMillis = 150),
        label = "settings_tile_alpha"
    )

    LaunchedEffect(isFocused) {
        if (isFocused) onFocused() else onUnfocused()
    }

    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.12f else 1.0f,
        animationSpec = tween(durationMillis = 80, easing = FastOutSlowInEasing),
        label = "settings_scale"
    )

    val palette = LocalThemePalette.current
    val offColor = palette.getOffBackgroundColor()
    val isOffDark = DomainColorPalette.isColorDark(offColor)
    val focusedOffColor = remember(offColor, isOffDark) {
        if (isOffDark) {
            Color(
                red = (offColor.red + 0.08f).coerceAtMost(1f),
                green = (offColor.green + 0.08f).coerceAtMost(1f),
                blue = (offColor.blue + 0.12f).coerceAtMost(1f),
                alpha = 1f
            )
        } else {
            Color(
                red = (offColor.red - 0.10f).coerceAtLeast(0f),
                green = (offColor.green - 0.10f).coerceAtLeast(0f),
                blue = (offColor.blue - 0.10f).coerceAtLeast(0f),
                alpha = 1f
            )
        }
    }

    Box(
        modifier = Modifier
            .size(62.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = tileAlpha
            }
            .blur(if (isOtherItemActive) 6.dp else 0.dp)
            .focusRequester(focusRequester)
            .clip(RoundedCornerShape(16.dp))
            .background(if (isFocused) focusedOffColor else offColor)
            .border(
                width = if (isFocused) 2.6.dp else 0.dp,
                color = if (isFocused) Color.White else Color.Transparent,
                shape = RoundedCornerShape(16.dp)
            )
            .onPreviewKeyEvent { keyEvent ->
                if (keyEvent.type == KeyEventType.KeyDown) {
                    val native = keyEvent.nativeKeyEvent
                    val isCenter = native.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                            native.keyCode == KeyEvent.KEYCODE_ENTER ||
                            native.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
                    if (isCenter) {
                        onClick()
                        return@onPreviewKeyEvent true
                    }
                    if (onDirectionToRecent != null && native.keyCode == directionToRecentKeyCode) {
                        onDirectionToRecent()
                        return@onPreviewKeyEvent true
                    }
                    if (!isVertical && native.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                        onWrapToFirst()
                        return@onPreviewKeyEvent true
                    } else if (isVertical && native.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                        onWrapToFirst()
                        return@onPreviewKeyEvent true
                    }
                }
                false
            }
            .focusable(
                enabled = !isDialogActive,
                interactionSource = interactionSource
            )
            .clickable(
                enabled = !isDialogActive,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.Settings,
            contentDescription = "Settings",
            tint = if (isFocused) (if (isOffDark) Color.White else Color(0xFF0F172A)) else (if (isOffDark) Color(0xFF94A3B8) else Color(0xFF475569)),
            modifier = Modifier.size(28.dp)
        )
    }
}
