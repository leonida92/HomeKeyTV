package com.homekey.tv.ui.panel

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.homekey.tv.data.models.DomainColorPalette
import com.homekey.tv.data.models.LocalThemePalette
import com.homekey.tv.ui.theme.HA_Yellow_On

@Stable
class FocusBadgeState {
    var name by mutableStateOf<String?>(null)
    var state by mutableStateOf<String?>(null)
    var humidity by mutableStateOf<Float?>(null)

    fun update(newName: String, newState: String, newHumidity: Float? = null) {
        name = newName
        state = newState
        humidity = newHumidity
    }

    fun clear(oldName: String) {
        if (name == oldName) {
            name = null
            state = null
            humidity = null
        }
    }
}

@Composable
fun FloatingLabelBadge(
    badgeState: FocusBadgeState,
    isReorderMode: Boolean,
    selectedEntityId: String?
) {
    val name = badgeState.name
    val state = badgeState.state

    val palette = LocalThemePalette.current
    val offColor = palette.getOffBackgroundColor()
    val isOffDark = DomainColorPalette.isColorDark(offColor)
    val badgeTextColor = if (isOffDark) Color.White else Color(0xFF0F172A)
    val badgeStateColor = palette.getColorForDomain("switch")

    AnimatedVisibility(
        visible = name != null,
        enter = fadeIn(tween(60)),
        exit = fadeOut(tween(40))
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(offColor)
                .border(
                    width = if (isReorderMode) 1.2.dp else 0.dp,
                    color = if (isReorderMode) HA_Yellow_On else Color.Transparent,
                    shape = RoundedCornerShape(12.dp)
                )
                .padding(horizontal = 14.dp, vertical = 5.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (isReorderMode) {
                    Icon(
                        Icons.Default.SwapHoriz,
                        contentDescription = null,
                        tint = HA_Yellow_On,
                        modifier = Modifier.size(15.dp)
                    )
                    Text(
                        text = name ?: "",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = if (selectedEntityId != null) "•  MOVING (D-Pad, OK to drop)"
                        else if (name == "Reorder Mode" || name == "Settings") "•  ${state?.uppercase() ?: "ACTIVE"}"
                        else "•  REORDER (OK to grab)",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = HA_Yellow_On
                    )
                } else {
                    Text(
                        text = name ?: "",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = badgeTextColor
                    )
                    if (!state.isNullOrBlank()) {
                        Text(
                            text = "•  ${state.uppercase()}",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = badgeStateColor
                        )
                    }
                    if (badgeState.humidity != null) {
                        Text(
                            text = "•",
                            fontSize = 12.sp,
                            color = if (isOffDark) Color(0x66FFFFFF) else Color(0x66000000)
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.WaterDrop,
                                contentDescription = "Humidity",
                                tint = badgeStateColor,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(
                                text = "${badgeState.humidity!!.toInt()}%",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = badgeStateColor
                            )
                        }
                    }
                }
            }
        }
    }
}
