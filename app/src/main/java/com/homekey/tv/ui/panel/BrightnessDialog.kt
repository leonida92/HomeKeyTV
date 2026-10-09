package com.homekey.tv.ui.panel

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
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
import kotlin.math.roundToInt

// Send the service call only after the slider has been still for this long, so dragging or tapping
// emits one WebSocket call instead of one per frame. The on-screen % stays instant.
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
    val brightnessPercent = ((brightness / 255f) * 100).roundToInt().coerceIn(1, 100)

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

    val sliderFocusRequester = remember { FocusRequester() }
    val closeFocusRequester = remember { FocusRequester() }
    val presetRequesters = remember { List(4) { FocusRequester() } }

    LaunchedEffect(Unit) {
        try {
            sliderFocusRequester.requestFocus()
        } catch (_: Exception) {}
    }

    BackHandler(onBack = onDismiss)

    Box(
        modifier = Modifier
            .width(440.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(offColor)
            .padding(24.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            // Header Row: Title + Close Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = entity.friendlyName,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = textColor,
                    modifier = Modifier.weight(1f)
                )

                val closeInteraction = remember { MutableInteractionSource() }
                val isCloseFocused by closeInteraction.collectIsFocusedAsState()

                IconButton(
                    onClick = onDismiss,
                    interactionSource = closeInteraction,
                    modifier = Modifier
                        .focusRequester(closeFocusRequester)
                        .clip(CircleShape)
                        .background(if (isCloseFocused) (if (isOffDark) Color(0x33FFFFFF) else Color(0x22000000)) else Color.Transparent)
                        .border(
                            width = if (isCloseFocused) 2.dp else 0.dp,
                            color = if (isCloseFocused) TV_Border_Focused else Color.Transparent,
                            shape = CircleShape
                        )
                        .onPreviewKeyEvent { keyEvent ->
                            if (keyEvent.type == KeyEventType.KeyDown) {
                                if (keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                                    try { sliderFocusRequester.requestFocus() } catch (_: Exception) {}
                                    return@onPreviewKeyEvent true
                                }
                            }
                            false
                        }
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = textColor)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "$brightnessPercent%",
                fontSize = 38.sp,
                fontWeight = FontWeight.ExtraBold,
                color = lightColor
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Focusable TV-optimised Brightness Bar with D-pad Left/Right increments
            val sliderInteraction = remember { MutableInteractionSource() }
            val isSliderFocused by sliderInteraction.collectIsFocusedAsState()

            val sliderScale by animateFloatAsState(
                targetValue = if (isSliderFocused) 1.03f else 1.0f,
                animationSpec = spring(dampingRatio = 0.75f, stiffness = 400f),
                label = "brightness_slider_scale"
            )

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .scale(sliderScale)
                        .height(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isOffDark) Color(0x330F172A) else Color(0x1A000000))
                        .border(
                            width = if (isSliderFocused) 2.dp else 0.dp,
                            color = if (isSliderFocused) TV_Border_Focused else Color.Transparent,
                            shape = RoundedCornerShape(12.dp)
                        )
                        .focusRequester(sliderFocusRequester)
                        .focusable(interactionSource = sliderInteraction)
                        .onPreviewKeyEvent { keyEvent ->
                            if (keyEvent.type == KeyEventType.KeyDown) {
                                when (keyEvent.nativeKeyEvent.keyCode) {
                                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                                        val next = (brightness - 13).coerceIn(1, 255)
                                        brightness = next
                                        scheduleBrightnessSend(next)
                                        return@onPreviewKeyEvent true
                                    }
                                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                        val next = (brightness + 13).coerceIn(1, 255)
                                        brightness = next
                                        scheduleBrightnessSend(next)
                                        return@onPreviewKeyEvent true
                                    }
                                    KeyEvent.KEYCODE_DPAD_UP -> {
                                        try { closeFocusRequester.requestFocus() } catch (_: Exception) {}
                                        return@onPreviewKeyEvent true
                                    }
                                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                                        val activeIdx = listOf(25, 50, 75, 100).indexOfFirst { pct ->
                                            brightnessPercent in (pct - 12)..(pct + 12)
                                        }.coerceAtLeast(0)
                                        try { presetRequesters[activeIdx].requestFocus() } catch (_: Exception) {}
                                        return@onPreviewKeyEvent true
                                    }
                                }
                            }
                            false
                        }
                        .padding(horizontal = 6.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    // Fill level
                    val fraction = (brightness / 255f).coerceIn(0.02f, 1f)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction = fraction)
                            .fillMaxHeight(0.70f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(lightColor)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = if (isSliderFocused) "D-pad Left / Right to adjust" else "Brightness Level",
                        fontSize = 11.sp,
                        color = if (isSliderFocused) TV_Border_Focused else TV_Text_Secondary,
                        fontWeight = if (isSliderFocused) FontWeight.SemiBold else FontWeight.Normal
                    )
                    Text(
                        text = "Press Down for presets",
                        fontSize = 11.sp,
                        color = TV_Text_Secondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Quick Presets (25%, 50%, 75%, 100%)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                val presets = listOf(25, 50, 75, 100)
                presets.forEachIndexed { idx, pct ->
                    val isPresetActive = brightnessPercent in (pct - 12)..(pct + 12)
                    val presetInteraction = remember { MutableInteractionSource() }
                    val isPresetFocused by presetInteraction.collectIsFocusedAsState()

                    Button(
                        onClick = {
                            val value = ((pct / 100f) * 255).roundToInt().coerceIn(1, 255)
                            brightness = value
                            debounceJob?.cancel()
                            debounceJob = null
                            sendBrightness(value)
                        },
                        interactionSource = presetInteraction,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = when {
                                isPresetFocused -> TV_Surface_Focused
                                isPresetActive -> lightColor
                                isOffDark -> Color(0x4D334155)
                                else -> Color(0x33CBD5E1)
                            }
                        ),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .padding(2.dp)
                            .focusRequester(presetRequesters[idx])
                            .border(
                                width = if (isPresetFocused) 2.dp else 0.dp,
                                color = if (isPresetFocused) TV_Border_Focused else Color.Transparent,
                                shape = RoundedCornerShape(8.dp)
                            )
                            .onPreviewKeyEvent { keyEvent ->
                                if (keyEvent.type == KeyEventType.KeyDown) {
                                    when (keyEvent.nativeKeyEvent.keyCode) {
                                        KeyEvent.KEYCODE_DPAD_UP -> {
                                            try { sliderFocusRequester.requestFocus() } catch (_: Exception) {}
                                            return@onPreviewKeyEvent true
                                        }
                                        KeyEvent.KEYCODE_DPAD_LEFT -> {
                                            if (idx > 0) {
                                                try { presetRequesters[idx - 1].requestFocus() } catch (_: Exception) {}
                                                return@onPreviewKeyEvent true
                                            }
                                        }
                                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                            if (idx < presets.size - 1) {
                                                try { presetRequesters[idx + 1].requestFocus() } catch (_: Exception) {}
                                                return@onPreviewKeyEvent true
                                            }
                                        }
                                    }
                                }
                                false
                            }
                    ) {
                        val presetTextColor = when {
                            isPresetFocused -> Color.White
                            isPresetActive && !DomainColorPalette.isColorDark(lightColor) -> Color(0xFF0F172A)
                            else -> Color.White
                        }
                        Text(
                            text = "$pct%",
                            color = presetTextColor,
                            fontSize = 12.sp,
                            fontWeight = if (isPresetActive) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}
