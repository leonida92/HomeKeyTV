package com.homeassistant.tv.ui.panel

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.homeassistant.tv.data.models.HAEntityState
import com.homeassistant.tv.ui.theme.*

@Composable
fun EntityCard(
    entity: HAEntityState,
    displayName: String? = null,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.05f else 1.0f,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 400f),
        label = "card_scale"
    )

    val isOn = entity.isOn
    val isUnavailable = entity.isUnavailable

    val backgroundColor by animateColorAsState(
        targetValue = when {
            isUnavailable -> Color(0x33333333)
            isFocused && isOn -> Color(0xFF0288D1)
            isFocused && !isOn -> Color(0xFF2C3852)
            isOn -> Color(0xCC0277BD)
            else -> Color(0x661E293B)
        },
        label = "bg_color"
    )

    val borderColor by animateColorAsState(
        targetValue = when {
            isFocused -> TV_Border_Focused
            isOn -> Color(0x6638BDF8)
            else -> Color(0x33475569)
        },
        label = "border_color"
    )

    val (icon, iconColor) = getEntityIconAndColor(entity, isOn)

    Box(
        modifier = modifier
            .scale(scale)
            .clip(RoundedCornerShape(14.dp))
            .background(backgroundColor)
            .border(
                width = if (isFocused) 3.dp else 1.5.dp,
                color = borderColor,
                shape = RoundedCornerShape(14.dp)
            )
            .focusable(interactionSource = interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onToggle
            )
            .padding(14.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isOn) Color(0x33FFFFFF) else Color(0x22FFFFFF)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (isOn) iconColor else Color(0xFF94A3B8),
                        modifier = Modifier.size(24.dp)
                    )
                }

                // State badge or extra info (e.g. brightness or temperature)
                val extraInfo = when {
                    isUnavailable -> "Offline"
                    entity.domain == "light" && isOn && entity.brightness != null -> {
                        val pct = ((entity.brightness!! / 255f) * 100).toInt()
                        "$pct%"
                    }
                    entity.domain == "climate" -> {
                        val temp = entity.currentTemperature ?: entity.targetTemperature
                        if (temp != null) "$temp°" else entity.state.uppercase()
                    }
                    entity.domain == "sensor" -> "${entity.state} ${entity.unitOfMeasurement ?: ""}".trim()
                    else -> if (isOn) "ON" else "OFF"
                }

                Text(
                    text = extraInfo,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isOn) Color.White else TV_Text_Secondary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (isOn) Color(0x33000000) else Color(0x22000000))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Column {
                Text(
                    text = displayName ?: entity.friendlyName,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TV_Text_Primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = entity.domain.replace("_", " ").uppercase(),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (isFocused) TV_Text_Primary.copy(alpha = 0.8f) else TV_Text_Secondary,
                    maxLines = 1
                )
            }
        }
    }
}

private fun getEntityIconAndColor(entity: HAEntityState, isOn: Boolean): Pair<ImageVector, Color> {
    return when (entity.domain) {
        "light" -> Icons.Default.Lightbulb to (if (isOn) Color(0xFFFFD54F) else Color.White)
        "switch", "input_boolean" -> Icons.Default.PowerSettingsNew to (if (isOn) Color(0xFF4CAF50) else Color.White)
        "scene" -> Icons.Default.Palette to Color(0xFFAB47BC)
        "script" -> Icons.Default.PlayArrow to Color(0xFF29B6F6)
        "climate" -> Icons.Default.Thermostat to (if (isOn) Color(0xFFFF7043) else Color.White)
        "media_player" -> Icons.Default.Tv to (if (isOn) Color(0xFF42A5F5) else Color.White)
        "cover" -> Icons.Default.Curtains to Color(0xFF26A69A)
        "fan" -> Icons.Default.Air to (if (isOn) Color(0xFF26C6DA) else Color.White)
        "lock" -> (if (entity.state == "locked") Icons.Default.Lock else Icons.Default.LockOpen) to (if (entity.state == "locked") Color(0xFF4CAF50) else Color(0xFFEF5350))
        "sensor" -> Icons.Default.Sensors to Color(0xFF66BB6A)
        "binary_sensor" -> Icons.Default.Sensors to (if (isOn) Color(0xFFFFA726) else Color.White)
        "vacuum" -> Icons.Default.CleaningServices to Color(0xFF8D6E63)
        else -> Icons.Default.DeviceHub to Color.White
    }
}
