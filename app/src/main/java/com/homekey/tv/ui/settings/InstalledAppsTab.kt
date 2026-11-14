package com.homekey.tv.ui.settings

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.History
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.homekey.tv.data.models.InstalledAppInfo
import com.homekey.tv.data.models.PinnedAppConfig
import com.homekey.tv.ui.theme.HA_Blue
import com.homekey.tv.ui.theme.TV_Border_Focused
import com.homekey.tv.ui.theme.TV_Surface_Focused
import com.homekey.tv.ui.theme.TV_Text_Secondary

@Composable
fun InstalledAppsView(
    installedApps: List<InstalledAppInfo>,
    appIcons: Map<String, Bitmap>,
    pinnedApps: List<PinnedAppConfig>,
    onTogglePinnedApp: (String, String) -> Unit,
    modifier: Modifier = Modifier,
    recentAppsEnabled: Boolean = false,
    recentAppsCount: Int = 3,
    onSetRecentAppsCount: (Int) -> Unit = {},
    toggleFocusRequester: FocusRequester? = null,
    firstAppFocusRequester: FocusRequester? = null,
    onToggleRecentApps: () -> Unit = {},
    onUpToTab: (() -> Unit)? = null
) {
    val countFocusRequesters = remember { List(5) { FocusRequester() } }

    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Installed Apps (Click to Pin/Unpin from Dock)",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Text(
                text = "${pinnedApps.size} pinned",
                fontSize = 13.sp,
                color = HA_Blue,
                fontWeight = FontWeight.SemiBold
            )
        }

        RecentAppsToggleCard(
            enabled = recentAppsEnabled,
            recentAppsCount = recentAppsCount,
            onToggle = onToggleRecentApps,
            onSetRecentAppsCount = onSetRecentAppsCount,
            countFocusRequesters = countFocusRequesters,
            toggleFocusRequester = toggleFocusRequester,
            onUp = onUpToTab,
            onDown = {
                try { firstAppFocusRequester?.requestFocus() } catch (_: Exception) {}
            }
        )

        if (installedApps.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = HA_Blue)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(
                    count = installedApps.size,
                    key = { index -> installedApps[index].packageName }
                ) { index ->
                    val app = installedApps[index]
                    val isPinned = pinnedApps.any { it.packageName == app.packageName }
                    AppGridCard(
                        app = app,
                        appIcon = appIcons[app.packageName],
                        isPinned = isPinned,
                        onClick = { onTogglePinnedApp(app.packageName, app.appName) },
                        index = index,
                        firstItemFocusRequester = if (index == 0) firstAppFocusRequester else null,
                        onUpToTab = {
                            try {
                                toggleFocusRequester?.requestFocus()
                            } catch (_: Exception) {}
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun RecentAppsToggleCard(
    enabled: Boolean,
    recentAppsCount: Int,
    onToggle: () -> Unit,
    onSetRecentAppsCount: (Int) -> Unit,
    countFocusRequesters: List<FocusRequester>,
    modifier: Modifier = Modifier,
    toggleFocusRequester: FocusRequester? = null,
    onUp: (() -> Unit)? = null,
    onDown: (() -> Unit)? = null
) {
    val toggleInteractionSource = remember { MutableInteractionSource() }
    val isToggleFocused by toggleInteractionSource.collectIsFocusedAsState()

    val toggleScale by animateFloatAsState(
        targetValue = if (isToggleFocused) 1.02f else 1.0f,
        animationSpec = spring(dampingRatio = 0.75f, stiffness = 400f),
        label = "toggle_scale"
    )

    val toggleFocusMod = if (toggleFocusRequester != null) Modifier.focusRequester(toggleFocusRequester) else Modifier

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF1E293B))
            .border(
                width = 1.dp,
                color = Color(0x33475569),
                shape = RoundedCornerShape(10.dp)
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .then(toggleFocusMod)
                .scale(toggleScale)
                .clip(RoundedCornerShape(8.dp))
                .background(if (isToggleFocused) TV_Surface_Focused else Color.Transparent)
                .border(
                    width = if (isToggleFocused) 2.dp else 0.dp,
                    color = if (isToggleFocused) TV_Border_Focused else Color.Transparent,
                    shape = RoundedCornerShape(8.dp)
                )
                .onPreviewKeyEvent { keyEvent ->
                    val code = keyEvent.nativeKeyEvent.keyCode
                    if (keyEvent.type == KeyEventType.KeyUp) {
                        if (code == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
                            code == android.view.KeyEvent.KEYCODE_ENTER ||
                            code == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER
                        ) {
                            onToggle()
                            return@onPreviewKeyEvent true
                        }
                        if (code == android.view.KeyEvent.KEYCODE_DPAD_UP ||
                            code == android.view.KeyEvent.KEYCODE_DPAD_DOWN ||
                            code == android.view.KeyEvent.KEYCODE_DPAD_RIGHT
                        ) {
                            return@onPreviewKeyEvent true
                        }
                    } else if (keyEvent.type == KeyEventType.KeyDown) {
                        if (code == android.view.KeyEvent.KEYCODE_DPAD_UP && onUp != null) {
                            onUp()
                            return@onPreviewKeyEvent true
                        }
                        if (code == android.view.KeyEvent.KEYCODE_DPAD_DOWN && onDown != null) {
                            onDown()
                            return@onPreviewKeyEvent true
                        }
                        if (code == android.view.KeyEvent.KEYCODE_DPAD_RIGHT && enabled) {
                            try {
                                val chipIdx = (recentAppsCount - 1).coerceIn(0, 4)
                                countFocusRequesters.getOrNull(chipIdx)?.requestFocus()
                                return@onPreviewKeyEvent true
                            } catch (_: Exception) {}
                        }
                    }
                    false
                }
                .clickable(
                    interactionSource = toggleInteractionSource,
                    indication = null,
                    onClick = onToggle
                )
                .focusable(interactionSource = toggleInteractionSource)
                .padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (enabled) HA_Blue else Color(0x33FFFFFF)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.History,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
            }
            Column {
                Text(
                    text = "Show Recent Apps in Dock",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    text = if (enabled)
                        "Displays up to $recentAppsCount recent apps in dock"
                    else
                        "Disabled (only pinned apps appear in dock)",
                    fontSize = 11.sp,
                    color = TV_Text_Secondary
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            Switch(
                checked = enabled,
                onCheckedChange = null,
                modifier = Modifier.scale(0.8f),
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = HA_Blue,
                    uncheckedThumbColor = TV_Text_Secondary,
                    uncheckedTrackColor = Color(0xFF334155)
                )
            )
        }

        AnimatedVisibility(
            visible = enabled,
            enter = fadeIn() + expandHorizontally(),
            exit = fadeOut() + shrinkHorizontally()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(end = 6.dp)
            ) {
                Text(
                    text = "Limit:",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = TV_Text_Secondary
                )
                (1..5).forEach { count ->
                    RecentAppsCountChip(
                        count = count,
                        isSelected = count == recentAppsCount,
                        focusRequester = countFocusRequesters[count - 1],
                        onClick = { onSetRecentAppsCount(count) },
                        onUp = { onUp?.invoke() },
                        onDown = { onDown?.invoke() },
                        onLeft = {
                            if (count > 1) {
                                try { countFocusRequesters[count - 2].requestFocus() } catch (_: Exception) {}
                            } else {
                                try { toggleFocusRequester?.requestFocus() } catch (_: Exception) {}
                            }
                        },
                        onRight = if (count < 5) {
                            {
                                try { countFocusRequesters[count].requestFocus() } catch (_: Exception) {}
                            }
                        } else null
                    )
                }
            }
        }
    }
}

@Composable
fun RecentAppsCountChip(
    count: Int,
    isSelected: Boolean,
    focusRequester: FocusRequester,
    onClick: () -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onLeft: (() -> Unit)?,
    onRight: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.1f else 1.0f,
        animationSpec = tween(durationMillis = 80),
        label = "count_chip_scale"
    )

    Box(
        modifier = modifier
            .focusRequester(focusRequester)
            .scale(scale)
            .size(width = 34.dp, height = 28.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(
                when {
                    isFocused -> TV_Surface_Focused
                    isSelected -> HA_Blue
                    else -> Color(0xFF334155)
                }
            )
            .border(
                width = if (isFocused) 2.dp else if (isSelected) 1.dp else 0.dp,
                color = when {
                    isFocused -> TV_Border_Focused
                    isSelected -> Color(0x66FFFFFF)
                    else -> Color.Transparent
                },
                shape = RoundedCornerShape(6.dp)
            )
            .dpadKeyHandler(
                onSelect = onClick,
                onUp = onUp,
                onDown = onDown,
                onLeft = onLeft,
                onRight = onRight,
                consumeDirectionalKeyUp = true
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = count.toString(),
            fontSize = 12.sp,
            fontWeight = if (isSelected || isFocused) FontWeight.Bold else FontWeight.Medium,
            color = Color.White
        )
    }
}

@Composable
fun AppGridCard(
    app: InstalledAppInfo,
    appIcon: Bitmap?,
    isPinned: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    index: Int = 0,
    firstItemFocusRequester: FocusRequester? = null,
    onUpToTab: (() -> Unit)? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.05f else 1.0f,
        animationSpec = tween(durationMillis = 80, easing = FastOutSlowInEasing),
        label = "app_scale"
    )

    // Icon is decoded off the main thread by the ViewModel; just wrap it for display.
    val appIconBitmap = remember(appIcon) { appIcon?.asImageBitmap() }

    val focusModifier = if (firstItemFocusRequester != null) Modifier.focusRequester(firstItemFocusRequester) else Modifier

    Box(
        modifier = modifier
            .then(focusModifier)
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(12.dp))
            .background(if (isFocused) TV_Surface_Focused else if (isPinned) Color(0xFF1E293B) else Color(0xFF161E2E))
            .border(
                width = if (isFocused) 2.dp else if (isPinned) 1.5.dp else 1.dp,
                color = if (isFocused) TV_Border_Focused else if (isPinned) HA_Blue else Color(0x22475569),
                shape = RoundedCornerShape(12.dp)
            )
            .dpadKeyHandler(
                onSelect = onClick,
                onUp = if (index in 0..2) onUpToTab else null
            )
            .focusable(interactionSource = interactionSource)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(14.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isPinned) Color(0x3338BDF8) else Color(0x22FFFFFF)),
                contentAlignment = Alignment.Center
            ) {
                if (appIconBitmap != null) {
                    Image(
                        bitmap = appIconBitmap,
                        contentDescription = app.appName,
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(6.dp))
                    )
                } else {
                    Icon(Icons.Default.Apps, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.appName,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1
                )
                Text(
                    text = if (isPinned) "Pinned in Dock" else "Click to pin",
                    fontSize = 11.sp,
                    color = if (isPinned) HA_Blue else TV_Text_Secondary
                )
            }

            if (isPinned) {
                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = HA_Blue, modifier = Modifier.size(20.dp))
            }
        }
    }
}
