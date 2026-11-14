package com.homekey.tv.ui.panel

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
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.homekey.tv.data.models.DomainColorPalette
import com.homekey.tv.data.models.HAEntityState
import com.homekey.tv.data.models.LocalThemePalette
import com.homekey.tv.ui.theme.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val FAN_DEBOUNCE_MS = 300L

@Composable
fun FanDialog(
    entity: HAEntityState,
    onToggle: () -> Unit,
    onSetPercentage: (Int) -> Unit,
    onSetOscillating: (Boolean) -> Unit = {},
    onDismiss: () -> Unit
) {
    val palette = LocalThemePalette.current
    val fanColor = palette.getColorForDomain("fan")
    val offColor = palette.getOffBackgroundColor()
    val isOffDark = DomainColorPalette.isColorDark(offColor)
    val textColor = if (isOffDark) Color.White else Color(0xFF0F172A)

    val initialPercentage = entity.fanPercentage ?: if (entity.isOn) 100 else 0
    var percentage by remember { mutableIntStateOf(initialPercentage) }

    val scope = rememberCoroutineScope()
    var debounceJob by remember { mutableStateOf<Job?>(null) }

    val initialFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        try {
            initialFocusRequester.requestFocus()
        } catch (_: Exception) {}
    }

    BackHandler(onBack = onDismiss)

    val isOn = entity.isOn

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
            // Header: Title + Close
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
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = textColor)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Power Icon & Status
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(if (isOn) fanColor.copy(alpha = 0.2f) else (if (isOffDark) Color(0x33334155) else Color(0x22CBD5E1)))
                    .border(if (isOn) 2.dp else 0.dp, if (isOn) fanColor else Color.Transparent, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Air,
                    contentDescription = null,
                    tint = if (isOn) fanColor else (if (isOffDark) Color(0x88FFFFFF) else Color(0x88000000)),
                    modifier = Modifier.size(36.dp)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            val stateText = if (isOn) "$percentage%" else "OFF"
            Text(
                text = stateText,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = if (isOn) fanColor else Color.Gray
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Action Buttons Row: Power Toggle + Oscillate
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)
            ) {
                FanActionButton(
                    icon = Icons.Default.PowerSettingsNew,
                    label = if (isOn) "Turn Off" else "Turn On",
                    color = if (isOn) Color(0xFFEF4444) else fanColor,
                    onClick = onToggle
                )
                FanActionButton(
                    icon = Icons.Default.Sync,
                    label = if (entity.isOscillating) "Oscillate ON" else "Oscillate OFF",
                    color = if (entity.isOscillating) fanColor else Color.Gray,
                    onClick = { onSetOscillating(!entity.isOscillating) }
                )
            }

            // Speed Percentage Slider
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "Fan Speed: $percentage%",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = textColor,
                modifier = Modifier.align(Alignment.Start)
            )

            Spacer(modifier = Modifier.height(8.dp))
            FanSliderTrack(
                value = percentage,
                color = fanColor,
                focusRequester = initialFocusRequester,
                onValueChange = { newPct ->
                    percentage = newPct
                    debounceJob?.cancel()
                    debounceJob = scope.launch {
                        delay(FAN_DEBOUNCE_MS)
                        onSetPercentage(newPct)
                    }
                }
            )

            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                listOf(
                    0 to "Off",
                    33 to "Low",
                    66 to "Med",
                    100 to "High"
                ).forEach { (pct, label) ->
                    MinimalChip(
                        text = label,
                        isSelected = isOn && (percentage in (pct - 15)..(pct + 15)),
                        selectedColor = fanColor,
                        onClick = {
                            percentage = pct
                            debounceJob?.cancel()
                            onSetPercentage(pct)
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun MinimalFanPopup(
    entity: HAEntityState,
    isVertical: Boolean = false,
    onToggle: () -> Unit,
    onSetPercentage: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val palette = LocalThemePalette.current
    val fanColor = palette.getColorForDomain("fan")

    val initialFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        try { initialFocus.requestFocus() } catch (_: Exception) {}
    }

    BackHandler(onBack = onDismiss)

    val currentPct = entity.fanPercentage ?: if (entity.isOn) 100 else 0
    val statusText = if (entity.isOn) "$currentPct%" else "OFF"

    if (isVertical) {
        MinimalVerticalCardContainer {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FanMinimalIdentity(
                    friendlyName = entity.friendlyName,
                    statusText = statusText,
                    fanColor = fanColor,
                    modifier = Modifier.weight(1f)
                )
                MinimalIconButton(
                    icon = Icons.Default.Close,
                    description = "Close",
                    onClick = onDismiss
                )
            }

            MinimalCardDivider()

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
            ) {
                FanMinimalControlButtons(
                    currentPct = currentPct,
                    initialFocus = initialFocus,
                    onToggle = onToggle,
                    onSetPercentage = onSetPercentage
                )
            }

            MinimalCardDivider()

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                FanMinimalPresets(
                    isOn = entity.isOn,
                    currentPct = currentPct,
                    fanColor = fanColor,
                    onSetPercentage = onSetPercentage
                )
            }
        }
    } else {
        MinimalBarContainer {
            FanMinimalIdentity(
                friendlyName = entity.friendlyName,
                statusText = statusText,
                fanColor = fanColor
            )

            Spacer(modifier = Modifier.width(14.dp))
            Box(modifier = Modifier.width(1.dp).height(28.dp).background(Color(0x33FFFFFF)))
            Spacer(modifier = Modifier.width(14.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FanMinimalControlButtons(
                    currentPct = currentPct,
                    initialFocus = initialFocus,
                    onToggle = onToggle,
                    onSetPercentage = onSetPercentage
                )
            }

            Spacer(modifier = Modifier.width(14.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FanMinimalPresets(
                    isOn = entity.isOn,
                    currentPct = currentPct,
                    fanColor = fanColor,
                    onSetPercentage = onSetPercentage
                )
            }

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
private fun FanMinimalIdentity(
    friendlyName: String,
    statusText: String,
    fanColor: Color,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(fanColor.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Air,
                contentDescription = null,
                tint = fanColor,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column {
            Text(
                text = friendlyName,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = TV_Text_Primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = statusText,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = fanColor
            )
        }
    }
}

@Composable
private fun FanMinimalControlButtons(
    currentPct: Int,
    initialFocus: FocusRequester,
    onToggle: () -> Unit,
    onSetPercentage: (Int) -> Unit
) {
    val buttonTint = Color.White
    val buttonBg = Color(0x332C3852)
    MinimalIconButton(
        icon = Icons.Default.PowerSettingsNew,
        description = "Power",
        modifier = Modifier.focusRequester(initialFocus),
        tint = buttonTint,
        containerColor = buttonBg,
        onClick = onToggle
    )
    MinimalIconButton(
        icon = Icons.Default.Remove,
        description = "Decrease",
        tint = buttonTint,
        containerColor = buttonBg,
        onClick = { onSetPercentage((currentPct - 10).coerceIn(0, 100)) }
    )
    MinimalIconButton(
        icon = Icons.Default.Add,
        description = "Increase",
        tint = buttonTint,
        containerColor = buttonBg,
        onClick = { onSetPercentage((currentPct + 10).coerceIn(0, 100)) }
    )
}

@Composable
private fun FanMinimalPresets(
    isOn: Boolean,
    currentPct: Int,
    fanColor: Color,
    onSetPercentage: (Int) -> Unit
) {
    listOf(33 to "Low", 66 to "Med", 100 to "High").forEach { (pct, label) ->
        MinimalChip(
            text = label,
            isSelected = isOn && currentPct == pct,
            selectedColor = fanColor,
            onClick = { onSetPercentage(pct) }
        )
    }
}

@Composable
private fun FanActionButton(
    icon: ImageVector,
    label: String,
    color: Color,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.08f else 1.0f,
        animationSpec = spring(dampingRatio = 0.75f, stiffness = 400f),
        label = "btn_scale"
    )

    Box(
        modifier = Modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .scale(scale)
            .width(130.dp)
            .height(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (isFocused) TV_Surface_Focused else Color(0x22334155))
            .border(if (isFocused) 2.dp else 0.dp, if (isFocused) TV_Border_Focused else Color.Transparent, RoundedCornerShape(12.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(icon, contentDescription = label, tint = color, modifier = Modifier.size(18.dp))
            Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
        }
    }
}

@Composable
private fun FanSliderTrack(
    value: Int,
    color: Color,
    focusRequester: FocusRequester? = null,
    onValueChange: (Int) -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Box(
        modifier = Modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .fillMaxWidth()
            .height(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0x331E293B))
            .border(if (isFocused) 2.dp else 0.dp, if (isFocused) TV_Border_Focused else Color.Transparent, RoundedCornerShape(10.dp))
            .focusable(interactionSource = interactionSource)
            .onPreviewKeyEvent { keyEvent ->
                if (keyEvent.type == KeyEventType.KeyDown) {
                    when (keyEvent.nativeKeyEvent.keyCode) {
                        android.view.KeyEvent.KEYCODE_DPAD_LEFT -> {
                            onValueChange((value - 10).coerceIn(0, 100))
                            true
                        }
                        android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            onValueChange((value + 10).coerceIn(0, 100))
                            true
                        }
                        else -> false
                    }
                } else false
            },
        contentAlignment = Alignment.CenterStart
    ) {
        val fraction = value / 100f
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction)
                .background(color.copy(alpha = 0.6f))
        )
        Text(
            text = "Use D-pad ◄ ► to adjust speed",
            fontSize = 11.sp,
            color = Color(0x88FFFFFF),
            modifier = Modifier.align(Alignment.Center)
        )
    }
}
