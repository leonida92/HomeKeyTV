package com.homeassistant.tv.ui.panel

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.homeassistant.tv.data.models.HAEntityState
import com.homeassistant.tv.ui.theme.*
import kotlin.math.roundToInt

@Composable
fun MediaPlayerDialog(
    entity: HAEntityState,
    onSelectSource: (String) -> Unit,
    onSetVolume: (Float) -> Unit,
    onTogglePlayPause: () -> Unit,
    onNextTrack: () -> Unit,
    onPreviousTrack: () -> Unit,
    onToggleMute: () -> Unit,
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

    val isPlaying = entity.isPlaying
    val isOn = entity.isOn
    val activeSource = entity.source
    val sources = entity.sourceList
    val volumeLevel = entity.volumeLevel ?: 0.5f
    val isMuted = entity.isVolumeMuted
    val mediaTitle = entity.mediaTitle
    val mediaArtist = entity.mediaArtist

    val activeColor = Color(0xFF0D9488) // Teal

    Box(
        modifier = Modifier
            .width(500.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xFF1E293B))
            .border(2.dp, TV_Border_Focused, RoundedCornerShape(20.dp))
            .padding(24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entity.friendlyName,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "MEDIA PLAYER",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = activeColor
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                }
            }

            // Media Status / Title Display
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0x330F172A))
                    .border(1.dp, Color(0x33475569), RoundedCornerShape(12.dp))
                    .padding(14.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .clip(CircleShape)
                            .background(if (isPlaying) activeColor.copy(alpha = 0.2f) else Color(0x22334155))
                            .border(2.dp, if (isPlaying) activeColor else Color(0x33475569), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.PlayArrow else Icons.Default.Tv,
                            contentDescription = null,
                            tint = if (isPlaying) activeColor else Color(0xFF94A3B8),
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = mediaTitle ?: if (isOn) "Media Active" else "Off / Standby",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (!mediaArtist.isNullOrBlank()) {
                            Text(
                                text = mediaArtist,
                                fontSize = 12.sp,
                                color = TV_Text_Secondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        } else {
                            Text(
                                text = entity.state.uppercase(),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isPlaying) activeColor else TV_Text_Secondary
                            )
                        }
                    }
                }
            }

            // Input Source Selector (if sources available)
            if (sources.isNotEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Input Source:",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = TV_Text_Secondary
                    )

                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(sources, key = { it }) { src ->
                            val isSelected = src.equals(activeSource, ignoreCase = true)
                            val interactionSource = remember { MutableInteractionSource() }
                            val isFocused by interactionSource.collectIsFocusedAsState()

                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        if (isFocused) TV_Surface_Focused
                                        else if (isSelected) activeColor
                                        else Color(0x22FFFFFF)
                                    )
                                    .border(
                                        1.dp,
                                        if (isFocused) TV_Border_Focused else Color.Transparent,
                                        RoundedCornerShape(8.dp)
                                    )
                                    .onPreviewKeyEvent { keyEvent ->
                                        if (keyEvent.type == KeyEventType.KeyUp) {
                                            val code = keyEvent.nativeKeyEvent.keyCode
                                            if (code == KeyEvent.KEYCODE_DPAD_CENTER ||
                                                code == KeyEvent.KEYCODE_ENTER ||
                                                code == KeyEvent.KEYCODE_NUMPAD_ENTER
                                            ) {
                                                onSelectSource(src)
                                                return@onPreviewKeyEvent true
                                            }
                                        }
                                        false
                                    }
                                    .focusable(interactionSource = interactionSource)
                                    .clickable(interactionSource = interactionSource, indication = null) {
                                        onSelectSource(src)
                                    }
                                    .padding(horizontal = 14.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    text = src,
                                    fontSize = 12.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = Color.White
                                )
                            }
                        }
                    }
                }
            }

            // Volume Control (if volumeLevel is supported)
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isMuted) "Volume: MUTED" else "Volume: ${(volumeLevel * 100).roundToInt()}%",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isMuted) Color(0xFFEF4444) else TV_Text_Secondary
                    )
                    Text(
                        text = "Left / Right to adjust",
                        fontSize = 10.sp,
                        color = TV_Text_Secondary
                    )
                }

                val volInteractionSource = remember { MutableInteractionSource() }
                val isVolFocused by volInteractionSource.collectIsFocusedAsState()

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(38.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0x330F172A))
                        .border(
                            2.dp,
                            if (isVolFocused) TV_Border_Focused else Color(0x33475569),
                            RoundedCornerShape(10.dp)
                        )
                        .onPreviewKeyEvent { keyEvent ->
                            if (keyEvent.type == KeyEventType.KeyDown) {
                                when (keyEvent.nativeKeyEvent.keyCode) {
                                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                                        onSetVolume((volumeLevel - 0.05f).coerceAtLeast(0f))
                                        return@onPreviewKeyEvent true
                                    }
                                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                        onSetVolume((volumeLevel + 0.05f).coerceAtMost(1f))
                                        return@onPreviewKeyEvent true
                                    }
                                }
                            }
                            false
                        }
                        .focusable(interactionSource = volInteractionSource)
                        .padding(horizontal = 4.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    // Volume fill bar
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction = volumeLevel.coerceIn(0.02f, 1f))
                            .fillMaxHeight(0.65f)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (isMuted) Color(0xFFEF4444) else activeColor)
                    )
                }
            }

            // Transport Controls (Previous, Play/Pause, Next, Mute)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Previous Track
                MediaActionButton(
                    icon = Icons.Default.SkipPrevious,
                    label = "Prev",
                    modifier = Modifier.weight(1f),
                    onClick = onPreviousTrack
                )

                // Play / Pause (Focused by default)
                MediaActionButton(
                    icon = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    label = if (isPlaying) "Pause" else "Play",
                    modifier = Modifier
                        .weight(1.2f)
                        .focusRequester(initialFocusRequester),
                    isPrimary = true,
                    onClick = onTogglePlayPause
                )

                // Next Track
                MediaActionButton(
                    icon = Icons.Default.SkipNext,
                    label = "Next",
                    modifier = Modifier.weight(1f),
                    onClick = onNextTrack
                )

                // Mute Toggle
                MediaActionButton(
                    icon = if (isMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                    label = if (isMuted) "Unmute" else "Mute",
                    modifier = Modifier.weight(1f),
                    onClick = onToggleMute
                )
            }

            // Power Control Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                MediaActionButton(
                    icon = Icons.Default.PowerSettingsNew,
                    label = if (isOn) "Turn Off" else "Turn On",
                    modifier = Modifier.fillMaxWidth(),
                    isDestructive = isOn,
                    onClick = { if (isOn) onTurnOff() else onTurnOn() }
                )
            }
        }
    }
}

@Composable
private fun MediaActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    isPrimary: Boolean = false,
    isDestructive: Boolean = false,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val defaultBg = when {
        isPrimary -> Color(0xFF0D9488) // Teal
        isDestructive -> Color(0x33EF4444)
        else -> Color(0x22FFFFFF)
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (isFocused) TV_Surface_Focused else defaultBg)
            .border(
                1.dp,
                if (isFocused) TV_Border_Focused else Color.Transparent,
                RoundedCornerShape(10.dp)
            )
            .onPreviewKeyEvent { keyEvent ->
                if (keyEvent.type == KeyEventType.KeyUp) {
                    val code = keyEvent.nativeKeyEvent.keyCode
                    if (code == KeyEvent.KEYCODE_DPAD_CENTER ||
                        code == KeyEvent.KEYCODE_ENTER ||
                        code == KeyEvent.KEYCODE_NUMPAD_ENTER
                    ) {
                        onClick()
                        return@onPreviewKeyEvent true
                    }
                }
                false
            }
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .focusable(interactionSource = interactionSource)
            .padding(vertical = 10.dp, horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = label,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )
        }
    }
}
