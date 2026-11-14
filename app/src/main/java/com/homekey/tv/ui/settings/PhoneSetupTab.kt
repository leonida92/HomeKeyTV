package com.homekey.tv.ui.settings

import android.graphics.Bitmap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.homekey.tv.ui.theme.HA_Blue
import com.homekey.tv.ui.theme.HA_Green_On
import com.homekey.tv.ui.theme.HA_Yellow_On
import com.homekey.tv.ui.theme.TV_Border_Focused
import com.homekey.tv.ui.theme.TV_Surface_Focused
import com.homekey.tv.ui.theme.TV_Text_Secondary

@Composable
fun PhoneSetupView(
    haEnabled: Boolean,
    onToggleHaEnabled: () -> Unit,
    toggleFocusRequester: FocusRequester,
    setupUrl: String?,
    qrBitmap: Bitmap?,
    pairingPin: String,
    serverUrl: String = "",
    fallbackServerUrl: String = "",
    activeServerUrl: String = "",
    onUpToTab: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        HaIntegrationToggleCard(
            enabled = haEnabled,
            onToggle = onToggleHaEnabled,
            toggleFocusRequester = toggleFocusRequester,
            onUp = onUpToTab
        )

        if (haEnabled) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF1E293B))
                        .border(1.dp, Color(0x33475569), RoundedCornerShape(16.dp))
                        .padding(20.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (qrBitmap != null) {
                        Image(
                            bitmap = qrBitmap.asImageBitmap(),
                            contentDescription = "QR Code Setup",
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(12.dp))
                        )
                    } else {
                        CircularProgressIndicator(color = HA_Blue)
                    }
                }

                Column(
                    modifier = Modifier
                        .weight(1.2f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = "Instant Phone Setup",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )

                        Text(
                            text = "1. Connect your phone to the same Wi-Fi network.\n" +
                                "2. Scan the QR code or open the link below in your mobile browser.\n" +
                                "3. Enter the Pairing PIN shown here, plus your Home Assistant URL and Token (once).",
                            fontSize = 13.sp,
                            color = TV_Text_Secondary,
                            lineHeight = 19.sp
                        )

                        if (setupUrl != null) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color(0xFF1E293B))
                                    .border(1.dp, Color(0x33475569), RoundedCornerShape(10.dp))
                                    .padding(horizontal = 14.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    text = setupUrl,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = HA_Blue
                                )
                            }
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "Pairing PIN",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TV_Text_Secondary
                            )
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color(0xFF0B1120))
                                    .border(1.dp, HA_Yellow_On, RoundedCornerShape(10.dp))
                                    .padding(horizontal = 18.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = pairingPin,
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = HA_Yellow_On,
                                    letterSpacing = 8.sp
                                )
                            }
                        }
                        if (serverUrl.isNotBlank()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFF0F172A))
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .clip(CircleShape)
                                            .background(if (activeServerUrl.isNotBlank()) HA_Green_On else HA_Yellow_On)
                                    )
                                    Text(
                                        text = "Active: " + (if (activeServerUrl.isNotBlank()) activeServerUrl else serverUrl),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color.White
                                    )
                                }
                                if (fallbackServerUrl.isNotBlank()) {
                                    Text(
                                        text = "Fallback: " + fallbackServerUrl,
                                        fontSize = 10.sp,
                                        color = TV_Text_Secondary
                                    )
                                }
                            }
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                            tint = TV_Text_Secondary,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "Web setup server running locally on port 8124",
                            fontSize = 11.sp,
                            color = TV_Text_Secondary
                        )
                    }
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF1E293B))
                    .border(1.dp, Color(0x33475569), RoundedCornerShape(16.dp))
                    .padding(24.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(Color(0x2264748B)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.PowerSettingsNew,
                            contentDescription = null,
                            tint = Color(0xFF94A3B8),
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "Home Assistant Integration Disabled",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = "Home Assistant entities are hidden from the dock, and the web setup server is stopped to conserve TV performance.\nEnable this toggle anytime you want to configure entities or connect to Home Assistant.",
                            fontSize = 13.sp,
                            color = TV_Text_Secondary,
                            lineHeight = 18.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun HaIntegrationToggleCard(
    enabled: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    toggleFocusRequester: FocusRequester? = null,
    onUp: (() -> Unit)? = null
) {
    val toggleInteractionSource = remember { MutableInteractionSource() }
    val isToggleFocused by toggleInteractionSource.collectIsFocusedAsState()

    val toggleScale by animateFloatAsState(
        targetValue = if (isToggleFocused) 1.015f else 1.0f,
        animationSpec = spring(dampingRatio = 0.75f, stiffness = 400f),
        label = "ha_toggle_scale"
    )

    val toggleFocusMod = if (toggleFocusRequester != null) Modifier.focusRequester(toggleFocusRequester) else Modifier

    Row(
        modifier = modifier
            .then(toggleFocusMod)
            .fillMaxWidth()
            .scale(toggleScale)
            .clip(RoundedCornerShape(12.dp))
            .background(if (isToggleFocused) TV_Surface_Focused else Color(0xFF1E293B))
            .border(
                width = if (isToggleFocused) 2.dp else 1.dp,
                color = if (isToggleFocused) TV_Border_Focused else Color(0x33475569),
                shape = RoundedCornerShape(12.dp)
            )
            .dpadKeyHandler(
                onSelect = onToggle,
                onUp = onUp,
                consumeUp = true,
                consumeDown = true
            )
            .clickable(
                interactionSource = toggleInteractionSource,
                indication = null,
                onClick = onToggle
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.weight(1f)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (enabled) HA_Blue else Color(0x33FFFFFF)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Home,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
            Column {
                Text(
                    text = "Enable Home Assistant Integration",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    text = if (enabled) "Home Assistant entities and controls are active on your dock"
                    else "Integration is disabled; web server is stopped and HA entities are hidden",
                    fontSize = 12.sp,
                    color = TV_Text_Secondary
                )
            }
        }

        Switch(
            checked = enabled,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = HA_Blue,
                uncheckedThumbColor = Color(0xFF94A3B8),
                uncheckedTrackColor = Color(0xFF334155)
            )
        )
    }
}
