package com.homeassistant.tv.ui.panel

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.homeassistant.tv.data.models.HAEntityState
import com.homeassistant.tv.ui.theme.*
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
    val initialBrightness = entity.brightness ?: 128
    var brightness by remember { mutableStateOf(initialBrightness) }
    val brightnessPercent = ((brightness / 255f) * 100).toInt()

    val scope = rememberCoroutineScope()
    var debounceJob by remember { mutableStateOf<Job?>(null) }
    var lastSent by remember { mutableStateOf(-1) }

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

    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .width(420.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xFF1E293B))
                .border(2.dp, TV_Border_Focused, RoundedCornerShape(20.dp))
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
                        color = Color.White
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    text = "$brightnessPercent%",
                    fontSize = 36.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = HA_Yellow_On
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
                        thumbColor = HA_Yellow_On,
                        activeTrackColor = HA_Yellow_On,
                        inactiveTrackColor = Color(0x33FFFFFF)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Quick Presets (25%, 50%, 75%, 100%)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    listOf(25, 50, 75, 100).forEach { pct ->
                        Button(
                            onClick = {
                                val value = ((pct / 100f) * 255).toInt()
                                brightness = value
                                debounceJob?.cancel()
                                debounceJob = null
                                sendBrightness(value)
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (brightnessPercent in (pct - 10)..(pct + 10)) HA_Blue else Color(0x4D334155)
                            ),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.padding(2.dp)
                        ) {
                            Text(text = "$pct%", color = Color.White, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}
