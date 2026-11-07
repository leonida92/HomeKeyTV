package com.homekey.tv.ui.panel

import android.view.KeyEvent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
fun RecentAppsSatellite(
    recentApps: List<String>,
    focusRequesters: List<FocusRequester>,
    isVertical: Boolean,
    onLaunchApp: (String) -> Unit,
    onExitToDock: () -> Unit,
    modifier: Modifier = Modifier,
    isDialogActive: Boolean = false,
    exitKeyCode: Int = KeyEvent.KEYCODE_DPAD_UP,
    showSettingsButton: Boolean = false,
    onOpenSettings: (() -> Unit)? = null,
    settingsFocusRequester: FocusRequester? = null,
    onFocused: ((String) -> Unit)? = null,
    onUnfocused: ((String) -> Unit)? = null
) {
    val displayApps = recentApps.take(5)
    if (displayApps.isEmpty() && !showSettingsButton) return

    val actualSettingsRequester = settingsFocusRequester ?: remember { FocusRequester() }

    val satelliteAlpha by animateFloatAsState(
        targetValue = if (isDialogActive) 0.22f else 1.0f,
        animationSpec = tween(durationMillis = 150),
        label = "satellite_alpha"
    )

    Box(
        modifier = modifier
            .graphicsLayer { alpha = satelliteAlpha }
            .then(if (isDialogActive) Modifier.blur(6.dp) else Modifier)
    ) {
        if (isVertical) {
            Column(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                displayApps.forEachIndexed { index, packageName ->
                    val requester = focusRequesters.getOrElse(index) { remember { FocusRequester() } }
                    RecentAppCircleItem(
                        packageName = packageName,
                        focusRequester = requester,
                        isDialogActive = isDialogActive,
                        onClick = { onLaunchApp(packageName) },
                        onFocused = onFocused,
                        onUnfocused = onUnfocused,
                        onPreviewKey = { keyEvent ->
                            if (keyEvent.type == KeyEventType.KeyDown) {
                                val native = keyEvent.nativeKeyEvent
                                val isCenter = native.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                                        native.keyCode == KeyEvent.KEYCODE_ENTER ||
                                        native.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
                                if (isCenter) {
                                    onLaunchApp(packageName)
                                    return@RecentAppCircleItem true
                                }
                                if (native.keyCode == exitKeyCode) {
                                    onExitToDock()
                                    return@RecentAppCircleItem true
                                }
                                when (native.keyCode) {
                                    KeyEvent.KEYCODE_DPAD_UP -> {
                                        if (index > 0) {
                                            try { focusRequesters[index - 1].requestFocus() } catch (_: Exception) {}
                                        }
                                        return@RecentAppCircleItem true
                                    }
                                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                                        if (index < displayApps.size - 1) {
                                            try { focusRequesters[index + 1].requestFocus() } catch (_: Exception) {}
                                        } else if (showSettingsButton) {
                                            try { actualSettingsRequester.requestFocus() } catch (_: Exception) {}
                                        }
                                        return@RecentAppCircleItem true
                                    }
                                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                        // Block escaping away from dock
                                        return@RecentAppCircleItem true
                                    }
                                }
                            }
                            false
                        }
                    )
                }

                if (showSettingsButton) {
                    RecentAppCircleSettingsItem(
                        focusRequester = actualSettingsRequester,
                        isDialogActive = isDialogActive,
                        onClick = { onOpenSettings?.invoke() },
                        onFocused = { onFocused?.invoke("Settings") },
                        onUnfocused = { onUnfocused?.invoke("Settings") },
                        onPreviewKey = { keyEvent ->
                            if (keyEvent.type == KeyEventType.KeyDown) {
                                val native = keyEvent.nativeKeyEvent
                                val isCenter = native.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                                        native.keyCode == KeyEvent.KEYCODE_ENTER ||
                                        native.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
                                if (isCenter) {
                                    onOpenSettings?.invoke()
                                    return@RecentAppCircleSettingsItem true
                                }
                                if (native.keyCode == exitKeyCode) {
                                    onExitToDock()
                                    return@RecentAppCircleSettingsItem true
                                }
                                when (native.keyCode) {
                                    KeyEvent.KEYCODE_DPAD_UP -> {
                                        if (displayApps.isNotEmpty()) {
                                            try { focusRequesters[displayApps.size - 1].requestFocus() } catch (_: Exception) {}
                                        }
                                        return@RecentAppCircleSettingsItem true
                                    }
                                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                                        // Bottom of list, block escaping
                                        return@RecentAppCircleSettingsItem true
                                    }
                                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                        // Block escaping away from dock
                                        return@RecentAppCircleSettingsItem true
                                    }
                                }
                            }
                            false
                        }
                    )
                }
            }
        } else {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                displayApps.forEachIndexed { index, packageName ->
                    val requester = focusRequesters.getOrElse(index) { remember { FocusRequester() } }
                    RecentAppCircleItem(
                        packageName = packageName,
                        focusRequester = requester,
                        isDialogActive = isDialogActive,
                        onClick = { onLaunchApp(packageName) },
                        onFocused = onFocused,
                        onUnfocused = onUnfocused,
                        onPreviewKey = { keyEvent ->
                            if (keyEvent.type == KeyEventType.KeyDown) {
                                val native = keyEvent.nativeKeyEvent
                                val isCenter = native.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                                        native.keyCode == KeyEvent.KEYCODE_ENTER ||
                                        native.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
                                if (isCenter) {
                                    onLaunchApp(packageName)
                                    return@RecentAppCircleItem true
                                }
                                if (native.keyCode == exitKeyCode) {
                                    onExitToDock()
                                    return@RecentAppCircleItem true
                                }
                                when (native.keyCode) {
                                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                                        if (index > 0) {
                                            try { focusRequesters[index - 1].requestFocus() } catch (_: Exception) {}
                                        }
                                        return@RecentAppCircleItem true
                                    }
                                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                        if (index < displayApps.size - 1) {
                                            try { focusRequesters[index + 1].requestFocus() } catch (_: Exception) {}
                                        } else if (showSettingsButton) {
                                            try { actualSettingsRequester.requestFocus() } catch (_: Exception) {}
                                        }
                                        return@RecentAppCircleItem true
                                    }
                                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                                        // Block escaping vertically (unless exitKeyCode matched above)
                                        return@RecentAppCircleItem true
                                    }
                                }
                            }
                            false
                        }
                    )
                }

                if (showSettingsButton) {
                    RecentAppCircleSettingsItem(
                        focusRequester = actualSettingsRequester,
                        isDialogActive = isDialogActive,
                        onClick = { onOpenSettings?.invoke() },
                        onFocused = { onFocused?.invoke("Settings") },
                        onUnfocused = { onUnfocused?.invoke("Settings") },
                        onPreviewKey = { keyEvent ->
                            if (keyEvent.type == KeyEventType.KeyDown) {
                                val native = keyEvent.nativeKeyEvent
                                val isCenter = native.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                                        native.keyCode == KeyEvent.KEYCODE_ENTER ||
                                        native.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
                                if (isCenter) {
                                    onOpenSettings?.invoke()
                                    return@RecentAppCircleSettingsItem true
                                }
                                if (native.keyCode == exitKeyCode) {
                                    onExitToDock()
                                    return@RecentAppCircleSettingsItem true
                                }
                                when (native.keyCode) {
                                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                                        if (displayApps.isNotEmpty()) {
                                            try { focusRequesters[displayApps.size - 1].requestFocus() } catch (_: Exception) {}
                                        }
                                        return@RecentAppCircleSettingsItem true
                                    }
                                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                        // Rightmost item, block escaping
                                        return@RecentAppCircleSettingsItem true
                                    }
                                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                                        // Block escaping vertically (unless exitKeyCode matched above)
                                        return@RecentAppCircleSettingsItem true
                                    }
                                }
                            }
                            false
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun RecentAppCircleSettingsItem(
    focusRequester: FocusRequester,
    isDialogActive: Boolean = false,
    onClick: () -> Unit,
    onFocused: (() -> Unit)? = null,
    onUnfocused: (() -> Unit)? = null,
    onPreviewKey: (androidx.compose.ui.input.key.KeyEvent) -> Boolean
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.15f else 1.0f,
        animationSpec = tween(durationMillis = 80, easing = FastOutSlowInEasing),
        label = "recent_settings_scale"
    )

    LaunchedEffect(isFocused) {
        if (isFocused) {
            onFocused?.invoke()
        } else {
            onUnfocused?.invoke()
        }
    }

    Box(
        modifier = Modifier
            .size(44.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .focusRequester(focusRequester)
            .clip(CircleShape)
            .background(if (isFocused) Color(0xFF2C3852) else Color(0xEE161E2E))
            .border(
                width = if (isFocused) 2.5.dp else 0.dp,
                color = if (isFocused) Color.White else Color.Transparent,
                shape = CircleShape
            )
            .onPreviewKeyEvent { keyEvent ->
                onPreviewKey(keyEvent)
            }
            .focusable(
                enabled = !isDialogActive,
                interactionSource = interactionSource
            )
            .clickable(
                enabled = !isDialogActive,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.Settings,
            contentDescription = "Settings",
            tint = if (isFocused) Color.White else Color(0xFF94A3B8),
            modifier = Modifier.size(22.dp)
        )
    }
}

@Composable
fun RecentAppCircleItem(
    packageName: String,
    focusRequester: FocusRequester,
    isDialogActive: Boolean = false,
    onClick: () -> Unit,
    onFocused: ((String) -> Unit)? = null,
    onUnfocused: ((String) -> Unit)? = null,
    onPreviewKey: (androidx.compose.ui.input.key.KeyEvent) -> Boolean
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.15f else 1.0f,
        animationSpec = tween(durationMillis = 80, easing = FastOutSlowInEasing),
        label = "recent_app_scale"
    )

    val iconBitmap = rememberAppIconBitmap(packageName)
    val context = LocalContext.current
    val appLabel = remember(packageName) {
        try {
            val pm = context.packageManager
            val info = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(info).toString()
        } catch (_: Exception) {
            packageName
        }
    }

    LaunchedEffect(isFocused) {
        if (isFocused) {
            onFocused?.invoke(appLabel)
        } else {
            onUnfocused?.invoke(appLabel)
        }
    }

    Box(
        modifier = Modifier
            .size(44.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .focusRequester(focusRequester)
            .clip(CircleShape)
            .background(Color.Transparent)
            .border(
                width = if (isFocused) 2.5.dp else 0.dp,
                color = if (isFocused) Color.White else Color.Transparent,
                shape = CircleShape
            )
            .onPreviewKeyEvent { keyEvent ->
                onPreviewKey(keyEvent)
            }
            .focusable(
                enabled = !isDialogActive,
                interactionSource = interactionSource
            )
            .clickable(
                enabled = !isDialogActive,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        if (iconBitmap != null) {
            Image(
                bitmap = iconBitmap,
                contentDescription = packageName,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(Color(0xEE161E2E)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Apps,
                    contentDescription = packageName,
                    tint = if (isFocused) Color.White else Color(0xFF94A3B8),
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}
