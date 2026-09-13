package com.homekey.tv.ui.panel

import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.homekey.tv.data.models.HAEntityState
import com.homekey.tv.ui.theme.*

@Composable
fun SwitchDialog(
    entity: HAEntityState,
    onToggle: () -> Unit,
    onTurnOn: () -> Unit,
    onTurnOff: () -> Unit,
    onDismiss: () -> Unit
) {
    val initialFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        try {
            initialFocusRequester.requestFocus()
        } catch (_: Exception) {}
    }

    BackHandler(onBack = onDismiss)

    val isOn = entity.isOn
    val activeColor = if (entity.domain == "light") HA_Yellow_On else Color(0xFF4CAF50)
    val stateText = if (isOn) "ON" else "OFF"
    val icon = if (entity.domain == "light") Icons.Default.Lightbulb else Icons.Default.PowerSettingsNew

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
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = entity.friendlyName,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // State Display: Big Icon and Status Text
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(if (isOn) activeColor.copy(alpha = 0.2f) else Color(0x33334155))
                    .border(2.dp, if (isOn) activeColor else Color(0x33475569), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (isOn) activeColor else Color(0xFF64748B),
                    modifier = Modifier.size(44.dp)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = stateText,
                fontSize = 32.sp,
                fontWeight = FontWeight.ExtraBold,
                color = if (isOn) activeColor else TV_Text_Secondary
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Interactive Switch Card
            val interactionSource = remember { MutableInteractionSource() }
            val isCardFocused by interactionSource.collectIsFocusedAsState()

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (isCardFocused) Color(0xFF2C3852) else Color(0xFF0F172A))
                    .border(
                        width = if (isCardFocused) 2.dp else 1.dp,
                        color = if (isCardFocused) Color.White else Color(0x33475569),
                        shape = RoundedCornerShape(14.dp)
                    )
                    .focusRequester(initialFocusRequester)
                    .focusable(interactionSource = interactionSource)
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = onToggle
                    )
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = if (entity.domain == "light") "Light Power" else "Switch Power",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                    Text(
                        text = if (isCardFocused) "Press Center to toggle" else "Click to toggle",
                        fontSize = 12.sp,
                        color = TV_Text_Secondary
                    )
                }

                Switch(
                    checked = isOn,
                    onCheckedChange = { onToggle() },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = activeColor,
                        uncheckedThumbColor = Color(0xFF94A3B8),
                        uncheckedTrackColor = Color(0xFF334155)
                    )
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Turn Off / Turn On discrete action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = onTurnOff,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (!isOn) Color(0xFF334155) else Color(0x33334155)
                    ),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = "Turn Off",
                        color = if (!isOn) Color.White else TV_Text_Secondary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                Button(
                    onClick = onTurnOn,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isOn) activeColor.copy(alpha = 0.85f) else Color(0x33334155)
                    ),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = "Turn On",
                        color = if (isOn) Color.Black else TV_Text_Secondary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}
