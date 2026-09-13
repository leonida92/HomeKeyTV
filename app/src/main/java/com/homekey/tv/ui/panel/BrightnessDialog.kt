package com.homekey.tv.ui.panel

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brightness5
import androidx.compose.material.icons.filled.Brightness7
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.homekey.tv.data.models.DomainColorPalette
import com.homekey.tv.data.models.HAEntityState
import com.homekey.tv.data.models.LocalThemePalette
import com.homekey.tv.ui.theme.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Send the service call only after the slider has been still for this long, so dragging across the
// whole range emits one WebSocket call instead of one per frame. The on-screen % stays instant.
private const val BRIGHTNESS_DEBOUNCE_MS = 300L

@Composable
fun BrightnessDialog(
    entity: HAEntityState,
    onSetBrightness: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val palette = LocalThemePalette.current
    val lightColor = palette.getColorForDomain("light")
    val offColor = palette.getOffBackgroundColor()
    val isOffDark = DomainColorPalette.isColorDark(offColor)
    val textColor = if (isOffDark) Color.White else Color(0xFF0F172A)

    val initialBrightness = entity.brightness ?: 128
    var brightness by remember { mutableIntStateOf(initialBrightness) }
    val brightnessPercent = ((brightness / 255f) * 100).toInt()

    val scope = rememberCoroutineScope()
    var debounceJob by remember { mutableStateOf<Job?>(null) }
    var lastSent by remember { mutableIntStateOf(-1) }

    fun sendBrightness(value: Int) {
        if (value == lastSent) return
        lastSent = value
        onSetBrightness(value)
    }

    fun scheduleBrightnessSend(value: Int) {
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(BRIGHTNESS_DEBOUNCE_MS)
            sendBrightness(value)
        }
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
            .width(420.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(offColor)
            .padding(24.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = entity.friendlyName,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = textColor
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = textColor)
                }
            }

                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    text = "$brightnessPercent%",
                    fontSize = 36.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = lightColor
                )

                Spacer(modifier = Modifier.height(16.dp))

                Slider(
                    value = brightness.toFloat(),
                    onValueChange = {
                        brightness = it.toInt()
                        scheduleBrightnessSend(brightness)
                    },
                    onValueChangeFinished = {
                        // Ensure the final position is sent even if the last change was recent.
                        debounceJob?.cancel()
                        debounceJob = null
                        sendBrightness(brightness)
                    },
                    valueRange = 1f..255f,
                    colors = SliderDefaults.colors(
                        thumbColor = lightColor,
                        activeTrackColor = lightColor,
                        inactiveTrackColor = if (isOffDark) Color(0x33FFFFFF) else Color(0x22000000)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Quick Presets (25%, 50%, 75%, 100%)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    val defaultFocusPct = listOf(100, 75, 50, 25).firstOrNull { pct ->
                        brightnessPercent in (pct - 10)..(pct + 10)
                    } ?: 100
                    listOf(25, 50, 75, 100).forEach { pct ->
                        val isPresetActive = brightnessPercent in (pct - 10)..(pct + 10)
                        Button(
                            onClick = {
                                val value = ((pct / 100f) * 255).toInt()
                                brightness = value
                                debounceJob?.cancel()
                                debounceJob = null
                                sendBrightness(value)
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isPresetActive) lightColor else (if (isOffDark) Color(0x4D334155) else Color(0x33CBD5E1))
                            ),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .padding(2.dp)
                                .then(if (pct == defaultFocusPct) Modifier.focusRequester(initialFocusRequester) else Modifier)
                        ) {
                            val presetTextColor = if (isPresetActive && !DomainColorPalette.isColorDark(lightColor)) Color(0xFF0F172A) else Color.White
                            Text(text = "$pct%", color = presetTextColor, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
