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

private const val COVER_DEBOUNCE_MS = 300L

@Composable
fun CoverDialog(
    entity: HAEntityState,
    onOpen: () -> Unit,
    onClose: () -> Unit,
    onStop: () -> Unit,
    onSetPosition: (Int) -> Unit,
    onSetTilt: (Int) -> Unit = {},
    onDismiss: () -> Unit
) {
    val palette = LocalThemePalette.current
    val coverColor = palette.getColorForDomain("cover")
    val offColor = palette.getOffBackgroundColor()
    val isOffDark = DomainColorPalette.isColorDark(offColor)
    val textColor = if (isOffDark) Color.White else Color(0xFF0F172A)

    val initialPosition = entity.currentPosition ?: if (entity.state.equals("open", ignoreCase = true)) 100 else 0
    var position by remember { mutableIntStateOf(initialPosition) }

    val initialTilt = entity.currentTiltPosition ?: 0
    var tilt by remember { mutableIntStateOf(initialTilt) }

    val scope = rememberCoroutineScope()
    var debouncePositionJob by remember { mutableStateOf<Job?>(null) }
    var debounceTiltJob by remember { mutableStateOf<Job?>(null) }

    val initialFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        try {
            initialFocusRequester.requestFocus()
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

            // State Badge
            val stateText = entity.state.uppercase()
            val positionText = if (entity.currentPosition != null) " • $position%" else ""
            Text(
                text = "$stateText$positionText",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = coverColor
            )

            Spacer(modifier = Modifier.height(20.dp))

            // 3-Button Command Row: Open, Stop, Close
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)
            ) {
                CoverActionButton(
                    icon = Icons.Default.ArrowUpward,
                    label = "Open",
                    color = coverColor,
                    focusRequester = initialFocusRequester,
                    onClick = onOpen
                )
                CoverActionButton(
                    icon = Icons.Default.Stop,
                    label = "Stop",
                    color = Color(0xFFEF4444),
                    onClick = onStop
                )
                CoverActionButton(
                    icon = Icons.Default.ArrowDownward,
                    label = "Close",
                    color = coverColor,
                    onClick = onClose
                )
            }

            // Position Slider & Presets
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "Position: $position%",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = textColor,
                modifier = Modifier.align(Alignment.Start)
            )

            Spacer(modifier = Modifier.height(8.dp))
            CoverSliderTrack(
                value = position,
                color = coverColor,
                onValueChange = { newPos ->
                    position = newPos
                    debouncePositionJob?.cancel()
                    debouncePositionJob = scope.launch {
                        delay(COVER_DEBOUNCE_MS)
                        onSetPosition(newPos)
                    }
                }
            )

            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                listOf(0, 25, 50, 75, 100).forEach { pct ->
                    MinimalChip(
                        text = "$pct%",
                        isSelected = position == pct,
                        selectedColor = coverColor,
                        onClick = {
                            position = pct
                            debouncePositionJob?.cancel()
                            onSetPosition(pct)
                        }
                    )
                }
            }

            // Optional Tilt Control (if supported)
            if (entity.supportsTilt) {
                Spacer(modifier = Modifier.height(20.dp))
                Text(
                    text = "Tilt: $tilt%",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = textColor,
                    modifier = Modifier.align(Alignment.Start)
                )

                Spacer(modifier = Modifier.height(8.dp))
                CoverSliderTrack(
                    value = tilt,
                    color = coverColor,
                    onValueChange = { newTilt ->
                        tilt = newTilt
                        debounceTiltJob?.cancel()
                        debounceTiltJob = scope.launch {
                            delay(COVER_DEBOUNCE_MS)
                            onSetTilt(newTilt)
                        }
                    }
                )

                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    listOf(0, 50, 100).forEach { pct ->
                        MinimalChip(
                            text = "$pct%",
                            isSelected = tilt == pct,
                            selectedColor = coverColor,
                            onClick = {
                                tilt = pct
                                debounceTiltJob?.cancel()
                                onSetTilt(pct)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun MinimalCoverPopup(
    entity: HAEntityState,
    isVertical: Boolean = false,
    onOpen: () -> Unit,
    onClose: () -> Unit,
    onStop: () -> Unit,
    onSetPosition: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val palette = LocalThemePalette.current
    val coverColor = palette.getColorForDomain("cover")

    val initialFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        try { initialFocus.requestFocus() } catch (_: Exception) {}
    }

    BackHandler(onBack = onDismiss)

    val currentPos = entity.currentPosition
    val posText = if (currentPos != null) "$currentPos%" else entity.state.uppercase()

    if (isVertical) {
        MinimalVerticalCardContainer {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CoverMinimalIdentity(
                    friendlyName = entity.friendlyName,
                    statusText = posText,
                    coverColor = coverColor,
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
                CoverMinimalCommandButtons(
                    initialFocus = initialFocus,
                    onOpen = onOpen,
                    onStop = onStop,
                    onClose = onClose
                )
            }

            MinimalCardDivider()

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                CoverMinimalPresets(
                    currentPos = currentPos,
                    coverColor = coverColor,
                    onSetPosition = onSetPosition
                )
            }
        }
    } else {
        MinimalBarContainer {
            CoverMinimalIdentity(
                friendlyName = entity.friendlyName,
                statusText = posText,
                coverColor = coverColor
            )

            Spacer(modifier = Modifier.width(14.dp))
            Box(modifier = Modifier.width(1.dp).height(28.dp).background(Color(0x33FFFFFF)))
            Spacer(modifier = Modifier.width(14.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CoverMinimalCommandButtons(
                    initialFocus = initialFocus,
                    onOpen = onOpen,
                    onStop = onStop,
                    onClose = onClose
                )
            }

            Spacer(modifier = Modifier.width(14.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                CoverMinimalPresets(
                    currentPos = currentPos,
                    coverColor = coverColor,
                    onSetPosition = onSetPosition
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
private fun CoverMinimalIdentity(
    friendlyName: String,
    statusText: String,
    coverColor: Color,
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
                .background(coverColor.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Window,
                contentDescription = null,
                tint = coverColor,
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
                color = coverColor
            )
        }
    }
}

@Composable
private fun CoverMinimalCommandButtons(
    initialFocus: FocusRequester,
    onOpen: () -> Unit,
    onStop: () -> Unit,
    onClose: () -> Unit
) {
    val buttonTint = Color.White
    val buttonBg = Color(0x332C3852)
    MinimalIconButton(
        icon = Icons.Default.ArrowUpward,
        description = "Open",
        modifier = Modifier.focusRequester(initialFocus),
        tint = buttonTint,
        containerColor = buttonBg,
        onClick = onOpen
    )
    MinimalIconButton(
        icon = Icons.Default.Stop,
        description = "Stop",
        tint = buttonTint,
        containerColor = buttonBg,
        onClick = onStop
    )
    MinimalIconButton(
        icon = Icons.Default.ArrowDownward,
        description = "Close",
        tint = buttonTint,
        containerColor = buttonBg,
        onClick = onClose
    )
}

@Composable
private fun CoverMinimalPresets(
    currentPos: Int?,
    coverColor: Color,
    onSetPosition: (Int) -> Unit
) {
    listOf(0, 50, 100).forEach { pct ->
        MinimalChip(
            text = "$pct%",
            isSelected = currentPos == pct,
            selectedColor = coverColor,
            onClick = { onSetPosition(pct) }
        )
    }
}

@Composable
private fun CoverActionButton(
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
            .width(100.dp)
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
private fun CoverSliderTrack(
    value: Int,
    color: Color,
    onValueChange: (Int) -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Box(
        modifier = Modifier
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
                            onValueChange((value - 5).coerceIn(0, 100))
                            true
                        }
                        android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            onValueChange((value + 5).coerceIn(0, 100))
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
            text = "Use D-pad ◄ ► to adjust",
            fontSize = 11.sp,
            color = Color(0x88FFFFFF),
            modifier = Modifier.align(Alignment.Center)
        )
    }
}
