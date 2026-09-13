package com.homekey.tv.ui.panel

import androidx.compose.animation.animateColorAsState
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
import com.homekey.tv.data.models.HAEntityState
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
    onSetTemperature: (Float) -> Unit,
    onSetHvacMode: (String) -> Unit,
    onSetFanMode: (String) -> Unit = {},
    onSetPresetMode: (String) -> Unit = {},
    onDismiss: () -> Unit
) {
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
        "heat" -> Color(0xFFFF7043)
        "heat_cool", "auto" -> Color(0xFF34D399)
        "dry" -> Color(0xFFFBBF24)
        "fan_only" -> Color(0xFF2DD4BF)
        "off" -> Color(0xFF94A3B8)
        else -> Color(0xFFFF7043)
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
            .background(Color(0xFF1E293B))
            .border(2.dp, TV_Border_Focused, RoundedCornerShape(20.dp))
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
                            color = Color.White,
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
                        .background(Color(0xFF0F172A))
                        .padding(horizontal = 20.dp, vertical = 14.dp)
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        // Current Environment Info (Temp & Humidity)
                        val currentTemp = entity.currentTemperature
                        val humidity = entity.currentHumidity
                        if (currentTemp != null || humidity != null) {
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

                                if (humidity != null) {
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
                                        text = "${humidity.toInt()}% Humidity",
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
                width = if (isFocused) 2.dp else if (isSelected) 1.dp else 0.dp,
                color = if (isFocused) Color.White else if (isSelected) modeColor.copy(alpha = 0.5f) else Color.Transparent,
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
