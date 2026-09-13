package com.homekey.tv.ui.panel

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeMute
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.homekey.tv.data.models.HAEntityState
import com.homekey.tv.ui.theme.*
import kotlin.math.roundToInt

@Composable
fun MinimalBrightnessPopup(
    entity: HAEntityState,
    isVertical: Boolean = false,
    onSetBrightness: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val initialBrightness = entity.brightness ?: 128
    var brightness by remember { mutableIntStateOf(initialBrightness) }
    val brightnessPercent = ((brightness / 255f) * 100).toInt()

    val initialFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        try {
            initialFocus.requestFocus()
        } catch (_: Exception) {}
    }

    BackHandler(onBack = onDismiss)

    if (isVertical) {
        MinimalVerticalCardContainer {
            // Header Row: Icon + Title + Status + Close
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(Color(0x33FFD54F)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Lightbulb,
                        contentDescription = null,
                        tint = Color(0xFFFFD54F),
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entity.friendlyName,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = TV_Text_Primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "$brightnessPercent%",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFFFFD54F)
                    )
                }
                MinimalIconButton(
                    icon = Icons.Default.Close,
                    description = "Close",
                    onClick = onDismiss
                )
            }

            MinimalCardDivider()

            // Stepper Controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                MinimalIconButton(
                    icon = Icons.Default.Remove,
                    description = "Decrease brightness",
                    onClick = {
                        val next = (brightness - 25).coerceIn(0, 255)
                        brightness = next
                        onSetBrightness(next)
                    }
                )
                Text(
                    text = "$brightnessPercent%",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                MinimalIconButton(
                    icon = Icons.Default.Add,
                    description = "Increase brightness",
                    onClick = {
                        val next = (brightness + 25).coerceIn(0, 255)
                        brightness = next
                        onSetBrightness(next)
                    }
                )
            }

            MinimalCardDivider()

            // Presets grid: 2 rows of 2 chips
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(25, 50).forEach { pct ->
                    val targetVal = (pct * 2.55f).roundToInt()
                    val isCurrent = (brightnessPercent in (pct - 10)..(pct + 10))
                    MinimalChip(
                        text = "$pct%",
                        isSelected = isCurrent,
                        modifier = Modifier
                            .weight(1f)
                            .then(if (pct == 50) Modifier.focusRequester(initialFocus) else Modifier),
                        onClick = {
                            brightness = targetVal
                            onSetBrightness(targetVal)
                        }
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(75, 100).forEach { pct ->
                    val targetVal = (pct * 2.55f).roundToInt()
                    val isCurrent = (brightnessPercent in (pct - 10)..(pct + 10))
                    MinimalChip(
                        text = "$pct%",
                        isSelected = isCurrent,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            brightness = targetVal
                            onSetBrightness(targetVal)
                        }
                    )
                }
            }
        }
    } else {
        MinimalBarContainer {
            // Entity Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(end = 12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(Color(0x33FFD54F)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Lightbulb,
                        contentDescription = null,
                        tint = Color(0xFFFFD54F),
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = entity.friendlyName,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = TV_Text_Primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "$brightnessPercent%",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFFFFD54F)
                    )
                }
            }

            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(28.dp)
                    .background(Color(0x33FFFFFF))
            )
            Spacer(modifier = Modifier.width(12.dp))

            // Stepper Controls
            MinimalIconButton(
                icon = Icons.Default.Remove,
                description = "Decrease brightness",
                onClick = {
                    val next = (brightness - 25).coerceIn(0, 255)
                    brightness = next
                    onSetBrightness(next)
                }
            )

            Spacer(modifier = Modifier.width(8.dp))

            MinimalIconButton(
                icon = Icons.Default.Add,
                description = "Increase brightness",
                onClick = {
                    val next = (brightness + 25).coerceIn(0, 255)
                    brightness = next
                    onSetBrightness(next)
                }
            )

            Spacer(modifier = Modifier.width(12.dp))

            // Quick Presets
            listOf(25, 50, 75, 100).forEach { pct ->
                val targetVal = (pct * 2.55f).roundToInt()
                val isCurrent = (brightnessPercent in (pct - 10)..(pct + 10))
                MinimalChip(
                    text = "$pct%",
                    isSelected = isCurrent,
                    modifier = if (pct == 50) Modifier.focusRequester(initialFocus) else Modifier,
                    onClick = {
                        brightness = targetVal
                        onSetBrightness(targetVal)
                    }
                )
                Spacer(modifier = Modifier.width(6.dp))
            }

            Spacer(modifier = Modifier.width(6.dp))

            // Close
            MinimalIconButton(
                icon = Icons.Default.Close,
                description = "Close",
                onClick = onDismiss
            )
        }
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
    val initialTemp = entity.targetTemperature ?: 21.0f
    var targetTemp by remember { mutableFloatStateOf(initialTemp) }
    var isModeMenuOpen by remember { mutableStateOf(false) }

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
            Column(
                modifier = Modifier
                    .padding(bottom = 8.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xF0182234))
                    .border(1.5.dp, TV_Border_Focused, RoundedCornerShape(14.dp))
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
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(Color(0x33FF7043)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Thermostat,
                            contentDescription = null,
                            tint = Color(0xFFFF7043),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = entity.friendlyName,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = TV_Text_Primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "${entity.currentTemperature ?: targetTemp}° -> ${"%.1f".format(targetTemp)}°",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFFFF7043)
                            )
                            val hum = humidity ?: entity.currentHumidity
                            if (hum != null) {
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
                                    text = "${hum.toInt()}%",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFF38BDF8)
                                )
                            }
                        }
                    }
                    MinimalIconButton(
                        icon = Icons.Default.Close,
                        description = "Close",
                        onClick = onDismiss
                    )
                }

                MinimalCardDivider()

                // Stepper Controls
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MinimalIconButton(
                        icon = Icons.Default.Remove,
                        description = "Decrease temperature",
                        onClick = {
                            val next = (targetTemp - 0.5f).coerceIn(10f, 35f)
                            targetTemp = next
                            onSetTemperature(next)
                        }
                    )
                    Text(
                        text = "${"%.1f".format(targetTemp)}°",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    MinimalIconButton(
                        icon = Icons.Default.Add,
                        description = "Increase temperature",
                        onClick = {
                            val next = (targetTemp + 0.5f).coerceIn(10f, 35f)
                            targetTemp = next
                            onSetTemperature(next)
                        }
                    )
                }

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
                val currentFan = entity.fanMode
                if (entity.fanModes.isNotEmpty() && currentFan != null) {
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
                        MinimalChip(
                            text = "FAN: ${currentFan.uppercase()}",
                            isSelected = false,
                            modifier = Modifier.weight(1.3f),
                            onClick = {
                                val currentIndex = entity.fanModes.indexOf(currentFan)
                                val nextIndex = (currentIndex + 1) % entity.fanModes.size
                                onSetFanMode(entity.fanModes[nextIndex])
                            }
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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(end = 12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(Color(0x33FF7043)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Thermostat,
                            contentDescription = null,
                            tint = Color(0xFFFF7043),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = entity.friendlyName,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = TV_Text_Primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "${entity.currentTemperature ?: targetTemp}° -> ${"%.1f".format(targetTemp)}°",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFFFF7043)
                            )
                            val hum = humidity ?: entity.currentHumidity
                            if (hum != null) {
                                Text(
                                    text = "  •  ",
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
                                    text = "${hum.toInt()}%",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFF38BDF8)
                                )
                            }
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(28.dp)
                        .background(Color(0x33FFFFFF))
                )
                Spacer(modifier = Modifier.width(12.dp))

                // Temp Steppers
                MinimalIconButton(
                    icon = Icons.Default.Remove,
                    description = "Decrease temperature",
                    onClick = {
                        val next = (targetTemp - 0.5f).coerceIn(10f, 35f)
                        targetTemp = next
                        onSetTemperature(next)
                    }
                )

                Spacer(modifier = Modifier.width(8.dp))

                MinimalIconButton(
                    icon = Icons.Default.Add,
                    description = "Increase temperature",
                    onClick = {
                        val next = (targetTemp + 0.5f).coerceIn(10f, 35f)
                        targetTemp = next
                        onSetTemperature(next)
                    }
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
                val currentFan = entity.fanMode
                if (entity.fanModes.isNotEmpty() && currentFan != null) {
                    MinimalChip(
                        text = "FAN: ${currentFan.uppercase()}",
                        isSelected = false,
                        onClick = {
                            val currentIndex = entity.fanModes.indexOf(currentFan)
                            val nextIndex = (currentIndex + 1) % entity.fanModes.size
                            onSetFanMode(entity.fanModes[nextIndex])
                        }
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
fun MinimalSwitchPopup(
    entity: HAEntityState,
    isVertical: Boolean = false,
    onToggle: () -> Unit,
    onTurnOn: () -> Unit,
    onTurnOff: () -> Unit,
    onDismiss: () -> Unit
) {
    val isOn = entity.state.equals("on", ignoreCase = true)

    val initialFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        try {
            initialFocus.requestFocus()
        } catch (_: Exception) {}
    }

    BackHandler(onBack = onDismiss)

    if (isVertical) {
        MinimalVerticalCardContainer {
            // Header: Icon + Title + State + Close
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(if (isOn) Color(0x334CAF50) else Color(0x22475569)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.PowerSettingsNew,
                        contentDescription = null,
                        tint = if (isOn) Color(0xFF4CAF50) else Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entity.friendlyName,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = TV_Text_Primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = if (isOn) "ON" else "OFF",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isOn) Color(0xFF4CAF50) else TV_Text_Secondary
                    )
                }
                MinimalIconButton(
                    icon = Icons.Default.Close,
                    description = "Close",
                    onClick = onDismiss
                )
            }

            MinimalCardDivider()

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                MinimalChip(
                    text = "OFF",
                    isSelected = !isOn,
                    modifier = Modifier.weight(1f),
                    onClick = onTurnOff
                )
                MinimalChip(
                    text = "TOGGLE",
                    isSelected = false,
                    modifier = Modifier
                        .weight(1.2f)
                        .focusRequester(initialFocus),
                    onClick = onToggle
                )
                MinimalChip(
                    text = "ON",
                    isSelected = isOn,
                    modifier = Modifier.weight(1f),
                    onClick = onTurnOn
                )
            }
        }
    } else {
        MinimalBarContainer {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(end = 12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(if (isOn) Color(0x334CAF50) else Color(0x22475569)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.PowerSettingsNew,
                        contentDescription = null,
                        tint = if (isOn) Color(0xFF4CAF50) else Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = entity.friendlyName,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = TV_Text_Primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = if (isOn) "ON" else "OFF",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isOn) Color(0xFF4CAF50) else TV_Text_Secondary
                    )
                }
            }

            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(28.dp)
                    .background(Color(0x33FFFFFF))
            )
            Spacer(modifier = Modifier.width(12.dp))

            MinimalChip(
                text = "TURN OFF",
                isSelected = !isOn,
                onClick = onTurnOff
            )

            Spacer(modifier = Modifier.width(8.dp))

            MinimalChip(
                text = "TOGGLE",
                isSelected = false,
                modifier = Modifier.focusRequester(initialFocus),
                onClick = onToggle
            )

            Spacer(modifier = Modifier.width(8.dp))

            MinimalChip(
                text = "TURN ON",
                isSelected = isOn,
                onClick = onTurnOn
            )

            Spacer(modifier = Modifier.width(12.dp))

            MinimalIconButton(
                icon = Icons.Default.Close,
                description = "Close",
                onClick = onDismiss
            )
        }
    }
}

@Composable
fun MinimalMediaPopup(
    entity: HAEntityState,
    isVertical: Boolean = false,
    onTogglePlayPause: () -> Unit,
    onNextTrack: () -> Unit,
    onPreviousTrack: () -> Unit,
    onSetVolume: (Float) -> Unit,
    onToggleMute: () -> Unit,
    onDismiss: () -> Unit
) {
    val isPlaying = entity.state.equals("playing", ignoreCase = true)
    val volume = entity.volumeLevel ?: 0.5f

    val initialFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        try {
            initialFocus.requestFocus()
        } catch (_: Exception) {}
    }

    BackHandler(onBack = onDismiss)

    if (isVertical) {
        MinimalVerticalCardContainer {
            // Header: Icon + Title + Subtitle/Artist + Close
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(Color(0x3342A5F5)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Tv,
                        contentDescription = null,
                        tint = Color(0xFF42A5F5),
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entity.mediaTitle ?: entity.friendlyName,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = TV_Text_Primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = entity.mediaArtist ?: entity.state.uppercase(),
                        fontSize = 11.sp,
                        color = TV_Text_Secondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                MinimalIconButton(
                    icon = Icons.Default.Close,
                    description = "Close",
                    onClick = onDismiss
                )
            }

            MinimalCardDivider()

            // Playback controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                MinimalIconButton(
                    icon = Icons.Default.SkipPrevious,
                    description = "Previous Track",
                    onClick = onPreviousTrack
                )
                MinimalIconButton(
                    icon = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    description = if (isPlaying) "Pause" else "Play",
                    modifier = Modifier.focusRequester(initialFocus),
                    onClick = onTogglePlayPause
                )
                MinimalIconButton(
                    icon = Icons.Default.SkipNext,
                    description = "Next Track",
                    onClick = onNextTrack
                )
            }

            MinimalCardDivider()

            // Volume controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                MinimalIconButton(
                    icon = Icons.AutoMirrored.Filled.VolumeMute,
                    description = "Mute",
                    onClick = onToggleMute
                )
                MinimalIconButton(
                    icon = Icons.AutoMirrored.Filled.VolumeDown,
                    description = "Volume Down",
                    onClick = { onSetVolume((volume - 0.05f).coerceIn(0f, 1f)) }
                )
                MinimalIconButton(
                    icon = Icons.AutoMirrored.Filled.VolumeUp,
                    description = "Volume Up",
                    onClick = { onSetVolume((volume + 0.05f).coerceIn(0f, 1f)) }
                )
            }
        }
    } else {
        MinimalBarContainer {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(end = 12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(Color(0x3342A5F5)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Tv,
                        contentDescription = null,
                        tint = Color(0xFF42A5F5),
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = entity.mediaTitle ?: entity.friendlyName,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = TV_Text_Primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = entity.mediaArtist ?: entity.state.uppercase(),
                        fontSize = 11.sp,
                        color = TV_Text_Secondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(28.dp)
                    .background(Color(0x33FFFFFF))
            )
            Spacer(modifier = Modifier.width(12.dp))

            // Playback controls
            MinimalIconButton(
                icon = Icons.Default.SkipPrevious,
                description = "Previous Track",
                onClick = onPreviousTrack
            )

            Spacer(modifier = Modifier.width(8.dp))

            MinimalIconButton(
                icon = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                description = if (isPlaying) "Pause" else "Play",
                modifier = Modifier.focusRequester(initialFocus),
                onClick = onTogglePlayPause
            )

            Spacer(modifier = Modifier.width(8.dp))

            MinimalIconButton(
                icon = Icons.Default.SkipNext,
                description = "Next Track",
                onClick = onNextTrack
            )

            Spacer(modifier = Modifier.width(12.dp))

            // Volume Controls
            MinimalIconButton(
                icon = Icons.AutoMirrored.Filled.VolumeMute,
                description = "Mute",
                onClick = onToggleMute
            )

            Spacer(modifier = Modifier.width(8.dp))

            MinimalIconButton(
                icon = Icons.AutoMirrored.Filled.VolumeDown,
                description = "Volume Down",
                onClick = { onSetVolume((volume - 0.05f).coerceIn(0f, 1f)) }
            )

            Spacer(modifier = Modifier.width(8.dp))

            MinimalIconButton(
                icon = Icons.AutoMirrored.Filled.VolumeUp,
                description = "Volume Up",
                onClick = { onSetVolume((volume + 0.05f).coerceIn(0f, 1f)) }
            )

            Spacer(modifier = Modifier.width(12.dp))

            MinimalIconButton(
                icon = Icons.Default.Close,
                description = "Close",
                onClick = onDismiss
            )
        }
    }
}

@Composable
private fun MinimalBarContainer(
    content: @Composable RowScope.() -> Unit
) {
    Row(
        modifier = Modifier
            .wrapContentWidth()
            .height(58.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xF0182234))
            .border(2.dp, TV_Border_Focused, RoundedCornerShape(18.dp))
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        content = content
    )
}

@Composable
private fun MinimalCardDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Color(0x22FFFFFF))
    )
}

@Composable
private fun MinimalVerticalCardContainer(
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .width(240.dp)
            .wrapContentHeight()
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xF0182234))
            .border(2.dp, TV_Border_Focused, RoundedCornerShape(18.dp))
            .padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content
    )
}

@Composable
private fun MinimalChip(
    text: String,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.10f else 1.0f,
        animationSpec = spring(dampingRatio = 0.75f, stiffness = 400f),
        label = "chip_scale"
    )

    val bgColor by animateColorAsState(
        targetValue = when {
            isFocused -> TV_Surface_Focused
            isSelected -> HA_Blue
            else -> Color(0x332C3852)
        },
        label = "chip_bg"
    )

    val borderColor by animateColorAsState(
        targetValue = when {
            isFocused -> TV_Border_Focused
            isSelected -> Color(0xFF38BDF8)
            else -> Color(0x22475569)
        },
        label = "chip_border"
    )

    Box(
        modifier = modifier
            .scale(scale)
            .height(34.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(bgColor)
            .border(if (isFocused) 2.dp else 1.dp, borderColor, RoundedCornerShape(10.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .focusable(interactionSource = interactionSource)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = 11.sp,
            fontWeight = if (isSelected || isFocused) FontWeight.Bold else FontWeight.Medium,
            color = if (isSelected || isFocused) Color.White else TV_Text_Secondary
        )
    }
}

@Composable
private fun MinimalIconButton(
    icon: ImageVector,
    description: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.12f else 1.0f,
        animationSpec = spring(dampingRatio = 0.75f, stiffness = 400f),
        label = "min_icon_scale"
    )

    Box(
        modifier = modifier
            .scale(scale)
            .size(34.dp)
            .clip(CircleShape)
            .background(if (isFocused) TV_Surface_Focused else Color(0x221E293B))
            .border(
                width = if (isFocused) 2.dp else 1.dp,
                color = if (isFocused) TV_Border_Focused else Color(0x22475569),
                shape = CircleShape
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
            contentDescription = description,
            tint = if (isFocused) Color.White else TV_Text_Secondary,
            modifier = Modifier.size(18.dp)
        )
    }
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
            else -> Color(0x22475569)
        },
        label = "mode_btn_border"
    )

    Row(
        modifier = modifier
            .scale(scale)
            .height(34.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(bgColor)
            .border(if (isFocused) 2.dp else 1.dp, borderColor, RoundedCornerShape(10.dp))
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
            imageVector = getHvacModeIcon(currentMode),
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
            else -> Color(0x22475569)
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
            .border(if (isFocused) 2.dp else 1.dp, borderColor, RoundedCornerShape(10.dp))
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
            .border(if (isFocused) 2.dp else if (isSelected) 1.dp else 0.dp, borderColor, RoundedCornerShape(8.dp))
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
                imageVector = getHvacModeIcon(mode),
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

private fun getHvacModeIcon(mode: String): ImageVector {
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

