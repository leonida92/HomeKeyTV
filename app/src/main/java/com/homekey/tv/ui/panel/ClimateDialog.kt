package com.homekey.tv.ui.panel

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.activity.compose.BackHandler
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.homekey.tv.data.models.DomainColorPalette
import com.homekey.tv.data.models.HAEntityState
import com.homekey.tv.data.models.LocalThemePalette
import com.homekey.tv.ui.theme.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

// Temperature +/- presses update the label instantly but the set_temperature call is trailing-
// debounced, so pressing repeatedly emits one service call instead of flooding Home Assistant.
private const val TEMP_DEBOUNCE_MS = 300L

@Composable
fun ClimateDialog(
    entity: HAEntityState,
    humidity: Float? = entity.currentHumidity,
    onSetTemperature: (Float) -> Unit,
    onSetHvacMode: (String) -> Unit,
    onSetFanMode: (String) -> Unit = {},
    onSetPresetMode: (String) -> Unit = {},
    onDismiss: () -> Unit
) {
    val palette = LocalThemePalette.current
    val offColor = palette.getOffBackgroundColor()
    val isOffDark = DomainColorPalette.isColorDark(offColor)
    val textColor = if (isOffDark) Color.White else Color(0xFF0F172A)

    val initialTemp = entity.targetTemperature ?: 21.0f
    var targetTemp by remember { mutableFloatStateOf(initialTemp) }

    LaunchedEffect(entity.targetTemperature) {
        entity.targetTemperature?.let {
            targetTemp = it
        }
    }

    val step = entity.targetTempStep
    val minTemp = entity.minTemp
    val maxTemp = entity.maxTemp

    val scope = rememberCoroutineScope()
    var debounceJob by remember { mutableStateOf<Job?>(null) }
    var lastSent by remember { mutableFloatStateOf(Float.NaN) }

    fun sendTemperature(value: Float) {
        if (value == lastSent) return
        lastSent = value
        onSetTemperature(value)
    }

    fun scheduleTemperatureSend(value: Float) {
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(TEMP_DEBOUNCE_MS)
            sendTemperature(value)
        }
    }

    val currentMode = entity.state.lowercase()
    val activeColor = when (currentMode) {
        "cool" -> Color(0xFF38BDF8)
        "heat" -> palette.getColorForDomain("climate")
        "heat_cool", "auto" -> Color(0xFF34D399)
        "dry" -> Color(0xFFFBBF24)
        "fan_only" -> Color(0xFF2DD4BF)
        "off" -> if (isOffDark) Color(0xFF94A3B8) else Color(0xFF64748B)
        else -> palette.getColorForDomain("climate")
    }

    val initialFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        try {
            initialFocusRequester.requestFocus()
        } catch (_: Exception) {}
    }

    BackHandler(onBack = onDismiss)

    Box(
        modifier = Modifier
            .width(550.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(offColor)
            .padding(horizontal = 24.dp, vertical = 20.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            // Header: Entity Name + Live Action Badge + Close Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(activeColor.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = resolveModeIcon(currentMode),
                            contentDescription = null,
                            tint = activeColor,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Column {
                        Text(
                            text = entity.friendlyName,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = textColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        // Live action / status badge
                        val actionText = formatHvacStatus(entity.hvacAction, entity.state)
                        Text(
                            text = actionText,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (entity.state.equals("off", ignoreCase = true)) Color(0xFF94A3B8) else activeColor
                        )
                    }
                }

                // Close Button
                TVIconButton(
                    onClick = onDismiss,
                    icon = Icons.Default.Close,
                    contentDescription = "Close",
                    modifier = Modifier.size(42.dp)
                )
            }

                Spacer(modifier = Modifier.height(16.dp))

                // Hero Temperature Card
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(if (isOffDark) Color(0xFF0F172A) else Color(0x1A000000))
                        .padding(horizontal = 20.dp, vertical = 14.dp)
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        // Current Environment Info (Temp & Humidity)
                        val currentTemp = entity.currentTemperature
                        val displayHumidity = humidity ?: entity.currentHumidity
                        if (currentTemp != null || displayHumidity != null) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (currentTemp != null) {
                                    Text(
                                        text = "Current: ",
                                        fontSize = 13.sp,
                                        color = TV_Text_Secondary
                                    )
                                    Text(
                                        text = String.format(Locale.US, "%.1f°", currentTemp),
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                }

                                if (displayHumidity != null) {
                                    if (currentTemp != null) {
                                        Text(
                                            text = "   •   ",
                                            fontSize = 13.sp,
                                            color = TV_Text_Secondary
                                        )
                                    }
                                    Icon(
                                        imageVector = Icons.Default.WaterDrop,
                                        contentDescription = null,
                                        tint = Color(0xFF38BDF8),
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "${displayHumidity.toInt()}% Humidity",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color.White
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Target Temperature Controls Row
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            // Decrease Temp (-) Button
                            TVIconButton(
                                onClick = {
                                    val newTemp = (targetTemp - step).coerceIn(minTemp, maxTemp)
                                    targetTemp = newTemp
                                    scheduleTemperatureSend(newTemp)
                                },
                                icon = Icons.Default.Remove,
                                contentDescription = "Decrease Temperature",
                                modifier = Modifier.size(52.dp).focusRequester(initialFocusRequester)
                            )

                            // Target Temperature Display
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = String.format(Locale.US, "%.1f°", targetTemp),
                                    fontSize = 44.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = if (entity.state.equals("off", ignoreCase = true)) Color(0xFF64748B) else activeColor
                                )
                                Text(
                                    text = "TARGET TEMP",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp,
                                    color = TV_Text_Secondary
                                )
                            }

                            // Increase Temp (+) Button
                            TVIconButton(
                                onClick = {
                                    val newTemp = (targetTemp + step).coerceIn(minTemp, maxTemp)
                                    targetTemp = newTemp
                                    scheduleTemperatureSend(newTemp)
                                },
                                icon = Icons.Default.Add,
                                contentDescription = "Increase Temperature",
                                modifier = Modifier.size(52.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Mode Selection Section
                val modes = entity.hvacModes.ifEmpty { listOf("heat", "cool", "auto", "off") }
                Text(
                    text = "MODE",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    color = TV_Text_Secondary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 2.dp, bottom = 8.dp)
                )

                // Layout modes: if <= 4 modes, 1 row; if > 4 modes, split into 2 balanced rows
                if (modes.size <= 4) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        modes.forEach { mode ->
                            ClimateModeButton(
                                mode = mode,
                                isSelected = entity.state.equals(mode, ignoreCase = true),
                                onClick = {
                                    debounceJob?.cancel()
                                    onSetHvacMode(mode)
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                } else {
                    val half = (modes.size + 1) / 2
                    val row1 = modes.take(half)
                    val row2 = modes.drop(half)

                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            row1.forEach { mode ->
                                ClimateModeButton(
                                    mode = mode,
                                    isSelected = entity.state.equals(mode, ignoreCase = true),
                                    onClick = {
                                        debounceJob?.cancel()
                                        onSetHvacMode(mode)
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            row2.forEach { mode ->
                                ClimateModeButton(
                                    mode = mode,
                                    isSelected = entity.state.equals(mode, ignoreCase = true),
                                    onClick = {
                                        debounceJob?.cancel()
                                        onSetHvacMode(mode)
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }

                // Optional Fan Modes Section
                val fanModes = entity.fanModes
                if (fanModes.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(14.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 2.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Air,
                            contentDescription = null,
                            tint = TV_Text_Secondary,
                            modifier = Modifier.size(13.dp)
                        )
                        Text(
                            text = "FAN SPEED",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp,
                            color = TV_Text_Secondary
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        fanModes.forEach { fanMode ->
                            val isSelected = entity.fanMode.equals(fanMode, ignoreCase = true)
                            ClimateChip(
                                label = formatOptionName(fanMode),
                                isSelected = isSelected,
                                selectedColor = Color(0xFF0284C7),
                                onClick = { onSetFanMode(fanMode) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                // Optional Preset Modes Section
                val presetModes = entity.presetModes.filter { !it.equals("none", ignoreCase = true) }
                if (presetModes.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = "PRESET",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        color = TV_Text_Secondary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 2.dp, bottom = 6.dp)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        presetModes.forEach { preset ->
                            val isSelected = entity.presetMode.equals(preset, ignoreCase = true)
                            ClimateChip(
                                label = formatOptionName(preset),
                                isSelected = isSelected,
                                selectedColor = Color(0xFF8B5CF6),
                                onClick = { onSetPresetMode(preset) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }
    }

@Composable
private fun ClimateModeButton(
    mode: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val label = formatModeName(mode)
    val icon = resolveModeIcon(mode)
    val modeColor = resolveModeColor(mode)

    val bgColor by animateColorAsState(
        targetValue = when {
            isSelected -> modeColor
            isFocused -> Color(0xFF334155)
            else -> Color(0x33334155)
        },
        label = "modeBgColor"
    )

    Box(
        modifier = modifier
            .height(44.dp)
            .scale(if (isFocused) 1.04f else 1f)
            .clip(RoundedCornerShape(12.dp))
            .background(bgColor)
            .border(
                width = if (isFocused) 2.dp else 0.dp,
                color = if (isFocused) Color.White else Color.Transparent,
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .focusable(interactionSource = interactionSource),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 8.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isSelected) Color.White else if (isFocused) Color.White else Color(0xFF94A3B8),
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                color = if (isSelected) Color.White else if (isFocused) Color.White else Color(0xFFCBD5E1),
                fontSize = 12.sp,
                fontWeight = if (isSelected || isFocused) FontWeight.Bold else FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ClimateChip(
    label: String,
    isSelected: Boolean,
    selectedColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Box(
        modifier = modifier
            .height(34.dp)
            .scale(if (isFocused) 1.04f else 1f)
            .clip(RoundedCornerShape(8.dp))
            .background(if (isSelected) selectedColor else Color(0x33334155))
            .border(
                width = if (isFocused) 2.dp else 0.dp,
                color = if (isFocused) Color.White else Color.Transparent,
                shape = RoundedCornerShape(8.dp)
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .focusable(interactionSource = interactionSource),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (isSelected || isFocused) Color.White else Color(0xFF94A3B8),
            fontSize = 11.sp,
            fontWeight = if (isSelected || isFocused) FontWeight.Bold else FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun TVIconButton(
    onClick: () -> Unit,
    icon: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Box(
        modifier = modifier
            .scale(if (isFocused) 1.08f else 1f)
            .clip(RoundedCornerShape(12.dp))
            .background(if (isFocused) Color(0xFF334155) else Color(0x33FFFFFF))
            .border(
                width = if (isFocused) 2.dp else 0.dp,
                color = if (isFocused) Color.White else Color.Transparent,
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .focusable(interactionSource = interactionSource),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = Color.White,
            modifier = Modifier.size(22.dp)
        )
    }
}

private fun formatModeName(mode: String): String {
    return when (mode.lowercase()) {
        "cool" -> "Cool"
        "heat" -> "Heat"
        "heat_cool" -> "Auto"
        "auto" -> "Auto"
        "dry" -> "Dry"
        "fan_only" -> "Fan"
        "off" -> "Off"
        else -> mode.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }
}

private fun formatOptionName(value: String): String {
    return value.replace('_', ' ').split(' ')
        .joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }
}

private fun resolveModeIcon(mode: String): ImageVector {
    return when (mode.lowercase()) {
        "cool" -> Icons.Default.AcUnit
        "heat" -> Icons.Default.Whatshot
        "heat_cool", "auto" -> Icons.Default.Sync
        "dry" -> Icons.Default.WaterDrop
        "fan_only" -> Icons.Default.Air
        "off" -> Icons.Default.PowerSettingsNew
        else -> Icons.Default.Thermostat
    }
}

private fun resolveModeColor(mode: String): Color {
    return when (mode.lowercase()) {
        "cool" -> Color(0xFF0284C7)
        "heat" -> Color(0xFFEA580C)
        "heat_cool", "auto" -> Color(0xFF059669)
        "dry" -> Color(0xFFD97706)
        "fan_only" -> Color(0xFF0D9488)
        "off" -> Color(0xFFDC2626)
        else -> Color(0xFF475569)
    }
}

private fun formatHvacStatus(action: String?, state: String): String {
    if (state.equals("off", ignoreCase = true)) {
        return "OFF"
    }
    return when (action?.lowercase()) {
        "cooling" -> "COOLING"
        "heating" -> "HEATING"
        "drying" -> "DRYING"
        "fan" -> "FAN RUNNING"
        "idle" -> "IDLE"
        "off" -> "OFF"
        else -> state.uppercase()
    }
}

@Composable
fun MinimalClimatePopup(
    entity: HAEntityState,
    humidity: Float? = entity.currentHumidity,
    isVertical: Boolean = false,
    onSetTemperature: (Float) -> Unit,
    onSetHvacMode: (String) -> Unit,
    onSetFanMode: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val palette = LocalThemePalette.current
    val climateColor = palette.getColorForDomain("climate")
    val initialTemp = entity.targetTemperature ?: 21.0f
    var targetTemp by remember(entity.entityId) { mutableFloatStateOf(initialTemp) }
    var isModeMenuOpen by remember { mutableStateOf(false) }

    LaunchedEffect(entity.targetTemperature) {
        entity.targetTemperature?.let {
            targetTemp = it
        }
    }

    val modeButtonFocusRequester = remember { FocusRequester() }
    val activeModeFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        try {
            modeButtonFocusRequester.requestFocus()
        } catch (_: Exception) {}
    }

    LaunchedEffect(isModeMenuOpen) {
        if (isModeMenuOpen) {
            delay(20)
            try {
                activeModeFocusRequester.requestFocus()
            } catch (_: Exception) {}
        }
    }

    BackHandler {
        if (isModeMenuOpen) {
            isModeMenuOpen = false
            try {
                modeButtonFocusRequester.requestFocus()
            } catch (_: Exception) {}
        } else {
            onDismiss()
        }
    }

    val stepTemperature: (Int) -> Unit = { direction ->
        val next = (targetTemp + direction * entity.targetTempStep).coerceIn(entity.minTemp, entity.maxTemp)
        targetTemp = next
        onSetTemperature(next)
    }

    val currentFan = entity.fanMode
    val canCycleFan = entity.fanModes.isNotEmpty() && currentFan != null
    val cycleFanMode: () -> Unit = {
        if (canCycleFan && currentFan != null) {
            val currentIndex = entity.fanModes.indexOf(currentFan)
            val nextIndex = (currentIndex + 1) % entity.fanModes.size
            onSetFanMode(entity.fanModes[nextIndex])
        }
    }

    val availableModes = if (entity.hvacModes.isNotEmpty()) entity.hvacModes else listOf("off", "heat", "cool", "heat_cool", "auto", "dry", "fan_only")
    val currentHvacMode = entity.state
    val isOff = currentHvacMode.equals("off", ignoreCase = true)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Bottom
    ) {
        AnimatedVisibility(
            visible = isModeMenuOpen,
            enter = fadeIn() + expandVertically(expandFrom = Alignment.Bottom),
            exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Bottom)
        ) {
            val offColor = palette.getOffBackgroundColor()
            Column(
                modifier = Modifier
                    .padding(bottom = 8.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(offColor)
                    .padding(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                val hasMatch = availableModes.any { it.equals(currentHvacMode, ignoreCase = true) }
                availableModes.forEachIndexed { index, mode ->
                    val isCurrent = currentHvacMode.equals(mode, ignoreCase = true)
                    val needsFocus = isCurrent || (!hasMatch && index == 0)
                    MinimalModeMenuItem(
                        mode = mode,
                        isSelected = isCurrent,
                        focusRequester = if (needsFocus) activeModeFocusRequester else null,
                        onClick = {
                            onSetHvacMode(mode)
                            isModeMenuOpen = false
                            try {
                                modeButtonFocusRequester.requestFocus()
                            } catch (_: Exception) {}
                        }
                    )
                }
            }
        }

        if (isVertical) {
            MinimalVerticalCardContainer {
                // Header Row: Icon + Title + Status/Humidity + Close
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MinimalClimateIdentity(
                        friendlyName = entity.friendlyName,
                        currentTemperature = entity.currentTemperature,
                        targetTemp = targetTemp,
                        humidity = humidity ?: entity.currentHumidity,
                        climateColor = climateColor,
                        modifier = Modifier.weight(1f)
                    )
                    MinimalIconButton(
                        icon = Icons.Default.Close,
                        description = "Close",
                        onClick = onDismiss
                    )
                }

                MinimalCardDivider()

                // Stepper Controls
                MinimalClimateTempSteppers(
                    targetTemp = targetTemp,
                    showCenterTemp = true,
                    onStepTemperature = stepTemperature,
                    modifier = Modifier.fillMaxWidth()
                )

                MinimalCardDivider()

                // HVAC Mode Dropdown Button
                MinimalModeDropdownButton(
                    currentMode = currentHvacMode,
                    isOpen = isModeMenuOpen,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(modeButtonFocusRequester),
                    onClick = { isModeMenuOpen = !isModeMenuOpen }
                )

                // Dedicated OFF Button and Fan Mode cycling
                if (canCycleFan && currentFan != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        MinimalOffButton(
                            isOff = isOff,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                onSetHvacMode("off")
                                isModeMenuOpen = false
                            }
                        )
                        MinimalClimateFanCycleChip(
                            currentFan = currentFan,
                            climateColor = climateColor,
                            modifier = Modifier.weight(1.3f),
                            onClick = cycleFanMode
                        )
                    }
                } else {
                    MinimalOffButton(
                        isOff = isOff,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            onSetHvacMode("off")
                            isModeMenuOpen = false
                        }
                    )
                }
            }
        } else {
            MinimalBarContainer {
                MinimalClimateIdentity(
                    friendlyName = entity.friendlyName,
                    currentTemperature = entity.currentTemperature,
                    targetTemp = targetTemp,
                    humidity = humidity ?: entity.currentHumidity,
                    climateColor = climateColor,
                    modifier = Modifier.padding(end = 12.dp)
                )

                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(28.dp)
                        .background(Color(0x33FFFFFF))
                )
                Spacer(modifier = Modifier.width(12.dp))

                // Temp Steppers
                MinimalClimateTempSteppers(
                    targetTemp = targetTemp,
                    showCenterTemp = false,
                    onStepTemperature = stepTemperature
                )

                Spacer(modifier = Modifier.width(12.dp))

                // HVAC Mode Dropdown Button
                MinimalModeDropdownButton(
                    currentMode = currentHvacMode,
                    isOpen = isModeMenuOpen,
                    modifier = Modifier.focusRequester(modeButtonFocusRequester),
                    onClick = { isModeMenuOpen = !isModeMenuOpen }
                )

                Spacer(modifier = Modifier.width(6.dp))

                // Dedicated OFF Button
                MinimalOffButton(
                    isOff = isOff,
                    onClick = {
                        onSetHvacMode("off")
                        isModeMenuOpen = false
                    }
                )

                Spacer(modifier = Modifier.width(6.dp))

                // Fan Mode cycling if supported
                if (canCycleFan && currentFan != null) {
                    MinimalClimateFanCycleChip(
                        currentFan = currentFan,
                        climateColor = climateColor,
                        onClick = cycleFanMode
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                }

                MinimalIconButton(
                    icon = Icons.Default.Close,
                    description = "Close",
                    onClick = onDismiss
                )
            }
        }
    }
}

@Composable
private fun MinimalClimateIdentity(
    friendlyName: String,
    currentTemperature: Float?,
    targetTemp: Float,
    humidity: Float?,
    climateColor: Color,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(climateColor.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Thermostat,
                contentDescription = null,
                tint = climateColor,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column {
            Text(
                text = friendlyName,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = TV_Text_Primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${currentTemperature ?: targetTemp}° -> ${String.format(Locale.US, "%.1f", targetTemp)}°",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = climateColor
                )
                if (humidity != null) {
                    Text(
                        text = " • ",
                        fontSize = 11.sp,
                        color = Color(0x66FFFFFF)
                    )
                    Icon(
                        imageVector = Icons.Default.WaterDrop,
                        contentDescription = "Humidity",
                        tint = Color(0xFF38BDF8),
                        modifier = Modifier.size(11.dp)
                    )
                    Spacer(modifier = Modifier.width(2.dp))
                    Text(
                        text = "${humidity.toInt()}%",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF38BDF8)
                    )
                }
            }
        }
    }
}

@Composable
private fun MinimalClimateTempSteppers(
    targetTemp: Float,
    showCenterTemp: Boolean,
    onStepTemperature: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = if (showCenterTemp) Arrangement.SpaceEvenly else Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MinimalIconButton(
            icon = Icons.Default.Remove,
            description = "Decrease temperature",
            onClick = { onStepTemperature(-1) }
        )
        if (showCenterTemp) {
            Text(
                text = "${String.format(Locale.US, "%.1f", targetTemp)}°",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }
        MinimalIconButton(
            icon = Icons.Default.Add,
            description = "Increase temperature",
            onClick = { onStepTemperature(1) }
        )
    }
}

@Composable
private fun MinimalClimateFanCycleChip(
    currentFan: String,
    climateColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    MinimalChip(
        text = "FAN: ${currentFan.uppercase()}",
        isSelected = false,
        selectedColor = climateColor,
        modifier = modifier,
        onClick = onClick
    )
}

@Composable
private fun MinimalModeDropdownButton(
    currentMode: String,
    isOpen: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.10f else 1.0f,
        animationSpec = spring(dampingRatio = 0.75f, stiffness = 400f),
        label = "mode_btn_scale"
    )

    val bgColor by animateColorAsState(
        targetValue = when {
            isFocused -> TV_Surface_Focused
            isOpen -> Color(0x4438BDF8)
            else -> Color(0x332C3852)
        },
        label = "mode_btn_bg"
    )

    val borderColor by animateColorAsState(
        targetValue = when {
            isFocused -> TV_Border_Focused
            isOpen -> Color(0xFF38BDF8)
            else -> Color.Transparent
        },
        label = "mode_btn_border"
    )

    Row(
        modifier = modifier
            .scale(scale)
            .height(34.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(bgColor)
            .border(if (isFocused) 2.dp else 0.dp, borderColor, RoundedCornerShape(10.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .focusable(interactionSource = interactionSource)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = resolveModeIcon(currentMode),
            contentDescription = null,
            tint = if (isFocused) Color.White else getHvacModeColor(currentMode),
            modifier = Modifier.size(14.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = "MODE: ${formatHvacMode(currentMode).uppercase()}",
            fontSize = 11.sp,
            fontWeight = if (isOpen || isFocused) FontWeight.Bold else FontWeight.Medium,
            color = if (isOpen || isFocused) Color.White else TV_Text_Secondary
        )
        Spacer(modifier = Modifier.width(4.dp))
        Icon(
            imageVector = if (isOpen) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
            contentDescription = null,
            tint = if (isOpen || isFocused) Color.White else TV_Text_Secondary,
            modifier = Modifier.size(16.dp)
        )
    }
}

@Composable
private fun MinimalOffButton(
    isOff: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.10f else 1.0f,
        animationSpec = spring(dampingRatio = 0.75f, stiffness = 400f),
        label = "off_btn_scale"
    )

    val bgColor by animateColorAsState(
        targetValue = when {
            isFocused -> TV_Surface_Focused
            isOff -> Color(0x33EF4444)
            else -> Color(0x332C3852)
        },
        label = "off_btn_bg"
    )

    val borderColor by animateColorAsState(
        targetValue = when {
            isFocused -> TV_Border_Focused
            isOff -> Color(0xFFEF4444)
            else -> Color.Transparent
        },
        label = "off_btn_border"
    )

    val contentColor by animateColorAsState(
        targetValue = when {
            isFocused -> Color.White
            isOff -> Color(0xFFEF4444)
            else -> TV_Text_Secondary
        },
        label = "off_btn_content_color"
    )

    Row(
        modifier = modifier
            .scale(scale)
            .height(34.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(bgColor)
            .border(if (isFocused) 2.dp else 0.dp, borderColor, RoundedCornerShape(10.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .focusable(interactionSource = interactionSource)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.PowerSettingsNew,
            contentDescription = "Turn off",
            tint = contentColor,
            modifier = Modifier.size(14.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = "OFF",
            fontSize = 11.sp,
            fontWeight = if (isOff || isFocused) FontWeight.Bold else FontWeight.Medium,
            color = contentColor,
            maxLines = 1
        )
    }
}

@Composable
private fun MinimalModeMenuItem(
    mode: String,
    isSelected: Boolean,
    focusRequester: FocusRequester?,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.06f else 1.0f,
        animationSpec = spring(dampingRatio = 0.75f, stiffness = 400f),
        label = "mode_item_scale"
    )

    val bgColor by animateColorAsState(
        targetValue = when {
            isFocused -> TV_Surface_Focused
            isSelected -> Color(0x3338BDF8)
            else -> Color(0x222C3852)
        },
        label = "mode_item_bg"
    )

    val borderColor by animateColorAsState(
        targetValue = when {
            isFocused -> TV_Border_Focused
            isSelected -> Color(0xFF38BDF8)
            else -> Color.Transparent
        },
        label = "mode_item_border"
    )

    val mod = if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier

    Row(
        modifier = mod
            .scale(scale)
            .widthIn(min = 160.dp)
            .height(36.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .border(if (isFocused) 2.dp else 0.dp, borderColor, RoundedCornerShape(8.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .focusable(interactionSource = interactionSource)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = resolveModeIcon(mode),
                contentDescription = null,
                tint = if (isFocused) Color.White else getHvacModeColor(mode),
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = formatHvacMode(mode),
                fontSize = 12.sp,
                fontWeight = if (isSelected || isFocused) FontWeight.Bold else FontWeight.Medium,
                color = if (isSelected || isFocused) Color.White else TV_Text_Secondary
            )
        }

        if (isSelected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Selected",
                tint = Color(0xFF38BDF8),
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

private fun formatHvacMode(mode: String): String {
    return when (mode.lowercase()) {
        "off" -> "Off"
        "heat" -> "Heat"
        "cool" -> "Cool"
        "heat_cool" -> "Heat/Cool"
        "auto" -> "Auto"
        "dry" -> "Dry"
        "fan_only" -> "Fan Only"
        else -> mode.replace('_', ' ').split(' ')
            .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
    }
}

private fun getHvacModeColor(mode: String): Color {
    return when (mode.lowercase()) {
        "cool" -> Color(0xFF0284C7)
        "heat" -> Color(0xFFEA580C)
        "heat_cool", "auto" -> Color(0xFF059669)
        "dry" -> Color(0xFFD97706)
        "fan_only" -> Color(0xFF0D9488)
        "off" -> Color(0xFFDC2626)
        else -> Color(0xFF38BDF8)
    }
}
