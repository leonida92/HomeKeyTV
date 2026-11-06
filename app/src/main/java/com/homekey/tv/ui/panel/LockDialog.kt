package com.homekey.tv.ui.panel

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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

private val LockAlertRed = Color(0xFFEF4444)
private val LockAmber = Color(0xFFF59E0B)

@Composable
fun LockDialog(
    entity: HAEntityState,
    onLock: () -> Unit,
    onUnlock: () -> Unit,
    onDismiss: () -> Unit
) {
    val palette = LocalThemePalette.current
    val lockGreen = palette.getColorForDomain("lock")
    val offColor = palette.getOffBackgroundColor()
    val isOffDark = DomainColorPalette.isColorDark(offColor)
    val textColor = if (isOffDark) Color.White else Color(0xFF0F172A)

    val isLocked = entity.isLocked
    val isJammed = entity.isJammed

    var isConfirmingUnlock by remember { mutableStateOf(false) }
    var resetJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()

    val actionFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        try { actionFocusRequester.requestFocus() } catch (_: Exception) {}
    }

    BackHandler(onBack = onDismiss)

    val (statusColor, statusText, statusIcon) = when {
        isJammed -> Triple(LockAmber, "JAMMED", Icons.Default.Warning)
        isLocked -> Triple(lockGreen, "LOCKED", Icons.Default.Lock)
        else -> Triple(LockAlertRed, "UNLOCKED", Icons.Default.LockOpen)
    }

    Box(
        modifier = Modifier
            .width(420.dp)
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

            Spacer(modifier = Modifier.height(20.dp))

            // State Display: Big Status Icon and Text
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(statusColor.copy(alpha = 0.2f))
                    .border(2.dp, statusColor, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = statusIcon,
                    contentDescription = null,
                    tint = statusColor,
                    modifier = Modifier.size(40.dp)
                )
            }

            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = statusText,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = statusColor
            )

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (isLocked) "Door is securely locked" else if (isJammed) "Lock mechanism jammed" else "Door is currently unlocked",
                fontSize = 13.sp,
                color = Color(0xAAFFFFFF)
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Action Button
            val interactionSource = remember { MutableInteractionSource() }
            val isFocused by interactionSource.collectIsFocusedAsState()

            val scale by animateFloatAsState(
                targetValue = if (isFocused) 1.05f else 1.0f,
                animationSpec = spring(dampingRatio = 0.75f, stiffness = 400f),
                label = "btn_scale"
            )

            val buttonColor = if (isConfirmingUnlock) LockAmber else statusColor
            val buttonText = when {
                !isLocked -> "Lock Door"
                isConfirmingUnlock -> "Confirm Unlock (Press again)"
                else -> "Unlock Door"
            }

            Box(
                modifier = Modifier
                    .focusRequester(actionFocusRequester)
                    .scale(scale)
                    .fillMaxWidth()
                    .height(52.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (isFocused) TV_Surface_Focused else buttonColor.copy(alpha = 0.25f))
                    .border(
                        width = if (isFocused) 2.dp else if (isConfirmingUnlock) 2.dp else 1.dp,
                        color = if (isFocused) TV_Border_Focused else buttonColor,
                        shape = RoundedCornerShape(14.dp)
                    )
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = {
                            if (!isLocked) {
                                onLock()
                            } else if (isConfirmingUnlock) {
                                resetJob?.cancel()
                                isConfirmingUnlock = false
                                onUnlock()
                            } else {
                                isConfirmingUnlock = true
                                resetJob?.cancel()
                                resetJob = scope.launch {
                                    delay(4000L)
                                    isConfirmingUnlock = false
                                }
                            }
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = if (!isLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                        contentDescription = null,
                        tint = if (isFocused) Color.White else buttonColor,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = buttonText,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }
        }
    }
}

@Composable
fun MinimalLockPopup(
    entity: HAEntityState,
    isVertical: Boolean = false,
    onLock: () -> Unit,
    onUnlock: () -> Unit,
    onDismiss: () -> Unit
) {
    val palette = LocalThemePalette.current
    val offColor = palette.getOffBackgroundColor()

    val lockGreen = palette.getColorForDomain("lock")
    val isLocked = entity.isLocked
    val statusColor = if (isLocked) lockGreen else LockAlertRed
    val statusIcon = if (isLocked) Icons.Default.Lock else Icons.Default.LockOpen
    val statusText = if (isLocked) "LOCKED" else "UNLOCKED"

    var isConfirmingUnlock by remember { mutableStateOf(false) }
    var resetJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()

    val initialFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        try { initialFocus.requestFocus() } catch (_: Exception) {}
    }

    BackHandler(onBack = onDismiss)

    val buttonText = when {
        !isLocked -> "Lock Door"
        isConfirmingUnlock -> "Confirm Unlock"
        else -> "Unlock Door"
    }

    if (isVertical) {
        Column(
            modifier = Modifier
                .width(240.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(offColor)
                .padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(statusColor.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(statusIcon, contentDescription = null, tint = statusColor, modifier = Modifier.size(20.dp))
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entity.friendlyName,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = statusText,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = statusColor
                    )
                }
            }

            // In-place button
            val interactionSource = remember { MutableInteractionSource() }
            val isFocused by interactionSource.collectIsFocusedAsState()

            Box(
                modifier = Modifier
                    .focusRequester(initialFocus)
                    .fillMaxWidth()
                    .height(42.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (isFocused) TV_Surface_Focused else statusColor.copy(alpha = 0.2f))
                    .border(if (isFocused) 2.dp else 1.dp, if (isFocused) TV_Border_Focused else statusColor, RoundedCornerShape(10.dp))
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = {
                            if (!isLocked) {
                                onLock()
                            } else if (isConfirmingUnlock) {
                                resetJob?.cancel()
                                isConfirmingUnlock = false
                                onUnlock()
                            } else {
                                isConfirmingUnlock = true
                                resetJob?.cancel()
                                resetJob = scope.launch {
                                    delay(4000L)
                                    isConfirmingUnlock = false
                                }
                            }
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = buttonText,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }
    } else {
        // Horizontal Minimal Card
        Row(
            modifier = Modifier
                .wrapContentWidth()
                .height(58.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(offColor)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(statusColor.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(statusIcon, contentDescription = null, tint = statusColor, modifier = Modifier.size(20.dp))
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(
                    text = entity.friendlyName,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = statusText,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = statusColor
                )
            }

            Spacer(modifier = Modifier.width(14.dp))
            Box(modifier = Modifier.width(1.dp).height(28.dp).background(Color(0x33FFFFFF)))
            Spacer(modifier = Modifier.width(14.dp))

            val interactionSource = remember { MutableInteractionSource() }
            val isFocused by interactionSource.collectIsFocusedAsState()

            Box(
                modifier = Modifier
                    .focusRequester(initialFocus)
                    .height(38.dp)
                    .width(120.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (isFocused) TV_Surface_Focused else statusColor.copy(alpha = 0.2f))
                    .border(if (isFocused) 2.dp else 1.dp, if (isFocused) TV_Border_Focused else statusColor, RoundedCornerShape(10.dp))
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = {
                            if (!isLocked) {
                                onLock()
                            } else if (isConfirmingUnlock) {
                                resetJob?.cancel()
                                isConfirmingUnlock = false
                                onUnlock()
                            } else {
                                isConfirmingUnlock = true
                                resetJob?.cancel()
                                resetJob = scope.launch {
                                    delay(4000L)
                                    isConfirmingUnlock = false
                                }
                            }
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = buttonText,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }
    }
}
