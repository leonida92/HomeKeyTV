package com.homekey.tv.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.homekey.tv.data.models.ConnectionStatus
import com.homekey.tv.ui.theme.HA_Blue
import com.homekey.tv.ui.theme.HA_Green_On
import com.homekey.tv.ui.theme.HA_Red_Off
import com.homekey.tv.ui.theme.HA_Yellow_On
import com.homekey.tv.ui.theme.TV_Border_Focused
import com.homekey.tv.ui.theme.TV_Surface_Focused
import com.homekey.tv.ui.theme.TV_Text_Secondary

/**
 * Shared D-pad key handler for TV settings cards, tabs, and chips.
 * Uses qualified [android.view.KeyEvent] keycodes to avoid Compose KeyEvent collisions.
 */
internal fun Modifier.dpadKeyHandler(
    onSelect: (() -> Unit)? = null,
    onUp: (() -> Unit)? = null,
    onDown: (() -> Unit)? = null,
    onLeft: (() -> Unit)? = null,
    onRight: (() -> Unit)? = null,
    consumeUp: Boolean = false,
    consumeDown: Boolean = false,
    consumeDirectionalKeyUp: Boolean = false
): Modifier = this.onPreviewKeyEvent { keyEvent ->
    val code = keyEvent.nativeKeyEvent.keyCode
    if (keyEvent.type == KeyEventType.KeyUp) {
        when (code) {
            android.view.KeyEvent.KEYCODE_DPAD_CENTER,
            android.view.KeyEvent.KEYCODE_ENTER,
            android.view.KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (onSelect != null) {
                    onSelect()
                    return@onPreviewKeyEvent true
                }
            }
            android.view.KeyEvent.KEYCODE_DPAD_UP,
            android.view.KeyEvent.KEYCODE_DPAD_DOWN,
            android.view.KeyEvent.KEYCODE_DPAD_LEFT,
            android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (consumeDirectionalKeyUp) return@onPreviewKeyEvent true
                if ((code == android.view.KeyEvent.KEYCODE_DPAD_UP && consumeUp) ||
                    (code == android.view.KeyEvent.KEYCODE_DPAD_DOWN && consumeDown)
                ) {
                    return@onPreviewKeyEvent true
                }
            }
        }
    } else if (keyEvent.type == KeyEventType.KeyDown) {
        when (code) {
            android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                if (onUp != null) {
                    onUp()
                    return@onPreviewKeyEvent true
                }
                if (consumeUp) return@onPreviewKeyEvent true
            }
            android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (onDown != null) {
                    onDown()
                    return@onPreviewKeyEvent true
                }
                if (consumeDown) return@onPreviewKeyEvent true
            }
            android.view.KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (onLeft != null) {
                    onLeft()
                    return@onPreviewKeyEvent true
                }
            }
            android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (onRight != null) {
                    onRight()
                    return@onPreviewKeyEvent true
                }
            }
        }
    }
    false
}

@Composable
internal fun SettingsHeader(
    haEnabled: Boolean,
    connectionStatus: ConnectionStatus,
    entityCount: Int
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = null,
                tint = TV_Text_Secondary,
                modifier = Modifier.size(24.dp)
            )
            Text(
                text = "HomeKey TV Settings",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0x331E293B))
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(
                        if (!haEnabled) Color(0xFF64748B)
                        else when (connectionStatus) {
                            ConnectionStatus.AUTHENTICATED -> HA_Green_On
                            ConnectionStatus.CONNECTING -> HA_Yellow_On
                            else -> HA_Red_Off
                        }
                    )
            )
            Text(
                text = if (!haEnabled) "Disabled"
                else when (connectionStatus) {
                    ConnectionStatus.AUTHENTICATED -> "Connected ($entityCount entities)"
                    ConnectionStatus.CONNECTING -> "Connecting..."
                    else -> "Disconnected"
                },
                fontSize = 12.sp,
                color = Color.White
            )
        }
    }
}

@Composable
fun SettingsTabItem(
    title: String,
    isSelected: Boolean,
    focusRequester: FocusRequester,
    onUp: (() -> Unit)? = null,
    onDown: (() -> Unit)? = null,
    onLeft: (() -> Unit)? = null,
    onRight: (() -> Unit)? = null,
    onSelect: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    LaunchedEffect(isFocused) {
        if (isFocused && !isSelected) {
            onSelect()
        }
    }

    Box(
        modifier = Modifier
            .focusRequester(focusRequester)
            .clip(RoundedCornerShape(10.dp))
            .background(
                when {
                    isFocused -> TV_Surface_Focused
                    isSelected -> HA_Blue
                    else -> Color(0x331E293B)
                }
            )
            .border(
                width = if (isFocused) 2.5.dp else if (isSelected) 1.5.dp else 0.dp,
                color = if (isFocused) Color.White else if (isSelected) HA_Blue else Color.Transparent,
                shape = RoundedCornerShape(10.dp)
            )
            .dpadKeyHandler(
                onSelect = onSelect,
                onUp = onUp,
                onDown = onDown,
                onLeft = onLeft,
                onRight = onRight,
                consumeDirectionalKeyUp = true
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onSelect
            )
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Text(
            text = title,
            fontSize = 13.sp,
            fontWeight = if (isSelected || isFocused) FontWeight.Bold else FontWeight.Medium,
            color = if (isSelected || isFocused) Color.White else TV_Text_Secondary
        )
    }
}

@Composable
internal fun SettingsFilterChip(
    label: String,
    isSelected: Boolean,
    selectedBackground: Color,
    modifier: Modifier = Modifier,
    unselectedBackground: Color = Color(0x331E293B),
    selectedBorder: Color = Color.White,
    unselectedBorder: Color = Color(0x22475569),
    selectedTextColor: Color = Color.White,
    unselectedTextColor: Color = Color.White,
    cornerRadius: Dp = 6.dp,
    horizontalPadding: Dp = 10.dp,
    verticalPadding: Dp = 5.dp,
    fontSize: TextUnit = 11.sp,
    boldWhenSelected: Boolean = true,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(if (isFocused) TV_Surface_Focused else if (isSelected) selectedBackground else unselectedBackground)
            .border(
                width = 1.dp,
                color = if (isFocused) TV_Border_Focused else if (isSelected) selectedBorder else unselectedBorder,
                shape = RoundedCornerShape(cornerRadius)
            )
            .dpadKeyHandler(onSelect = onClick)
            .focusable(interactionSource = interactionSource)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(horizontal = horizontalPadding, vertical = verticalPadding)
    ) {
        Text(
            text = label,
            fontSize = fontSize,
            color = if (isSelected && !isFocused) selectedTextColor else unselectedTextColor,
            fontWeight = if (boldWhenSelected && isSelected) FontWeight.Bold else if (!boldWhenSelected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}
