package com.homeassistant.tv.ui.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Remove
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
import java.util.Locale

// Temperature +/- presses update the label instantly but the set_temperature call is trailing-
// debounced, so mashing the button sends one call instead of a flood.
private const val TEMP_DEBOUNCE_MS = 300L

@Composable
fun ClimateDialog(
    entity: HAEntityState,
    onSetTemperature: (Float) -> Unit,
    onSetHvacMode: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val initialTemp = entity.targetTemperature ?: 21.0f
    var targetTemp by remember { mutableStateOf(initialTemp) }

    val scope = rememberCoroutineScope()
    var debounceJob by remember { mutableStateOf<Job?>(null) }
    var lastSent by remember { mutableStateOf(Float.NaN) }

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

    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .width(440.dp)
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

                Spacer(modifier = Modifier.height(16.dp))

                if (entity.currentTemperature != null) {
                    Text(
                        text = "Current: ${entity.currentTemperature}°",
                        fontSize = 14.sp,
                        color = TV_Text_Secondary
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    IconButton(
                        onClick = {
                            val newTemp = targetTemp - 0.5f
                            targetTemp = newTemp
                            scheduleTemperatureSend(newTemp)
                        },
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0x33FFFFFF))
                    ) {
                        Icon(Icons.Default.Remove, contentDescription = "Decrease", tint = Color.White)
                    }

                    Spacer(modifier = Modifier.width(20.dp))

                    Text(
                        text = String.format(Locale.US, "%.1f°", targetTemp),
                        fontSize = 40.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color(0xFFFF7043)
                    )

                    Spacer(modifier = Modifier.width(20.dp))

                    IconButton(
                        onClick = {
                            val newTemp = targetTemp + 0.5f
                            targetTemp = newTemp
                            scheduleTemperatureSend(newTemp)
                        },
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0x33FFFFFF))
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Increase", tint = Color.White)
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // HVAC Modes (heat, cool, auto, off) — explicit one-shot mode switches, sent directly.
                val modes = entity.hvacModes.ifEmpty { listOf("heat", "cool", "auto", "off") }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    modes.forEach { mode ->
                        val isSelected = entity.state.equals(mode, ignoreCase = true)
                        Button(
                            onClick = {
                                debounceJob?.cancel()
                                onSetHvacMode(mode)
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isSelected) Color(0xFFFF7043) else Color(0x4D334155)
                            ),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.padding(2.dp)
                        ) {
                            Text(
                                text = mode.uppercase(),
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }
    }
}
