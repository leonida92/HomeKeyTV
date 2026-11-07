package com.homekey.tv.ui.panel

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.view.KeyEvent
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.homekey.tv.data.local.DockConfig
import com.homekey.tv.data.models.DomainColorPalette
import com.homekey.tv.data.models.HAEntityState
import com.homekey.tv.data.models.LocalThemePalette
import com.homekey.tv.ui.theme.*
import com.homekey.tv.viewmodel.DockItem
import com.homekey.tv.viewmodel.PanelViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
@Composable
fun DockOverlayScreen(
    viewModel: PanelViewModel,
    layoutPosition: String, // DOCK_BOTTOM, DOCK_LEFT, DOCK_RIGHT
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit = {}
) {
    val context = LocalContext.current
    val displayEntities by viewModel.displayEntities.collectAsState()
    val allEntities by viewModel.allEntities.collectAsState()
    val activeDialogEntity by viewModel.activeDialogEntity.collectAsState()
    val popupStyle by viewModel.popupStyle.collectAsState()
    val activePalette by viewModel.activePalette.collectAsState()
    val isReorderMode by viewModel.isReorderMode.collectAsState()
    val selectedReorderEntityId by viewModel.selectedReorderEntityId.collectAsState()
    val overlayOpenEpoch by viewModel.overlayOpenEpoch.collectAsState()
    val recentAppsEnabled by viewModel.recentAppsEnabled.collectAsState()
    val recentAppsCount by viewModel.recentAppsCount.collectAsState()
    val recentApps by viewModel.recentApps.collectAsState()
    val displayRecentApps = remember(recentApps, recentAppsCount) { recentApps.take(recentAppsCount) }
    val showRecentApps = recentAppsEnabled && displayRecentApps.isNotEmpty() && !isReorderMode

    val recentAppsFocusRequesters = remember { List(5) { FocusRequester() } }
    val firstFocusRequester = remember { FocusRequester() }
    val settingsFocusRequester = remember { FocusRequester() }
    val reorderFocusRequester = remember { FocusRequester() }
    val itemFocusRequesters = remember(displayEntities) {
        displayEntities.associate { it.id to (if (displayEntities.firstOrNull()?.id == it.id) firstFocusRequester else FocusRequester()) }
    }
    var lastFocusedItemId by remember { mutableStateOf<String?>(null) }

    val isCamera = activeDialogEntity?.domain == "camera"
    val isMinimalActive = activeDialogEntity != null && popupStyle == DockConfig.POPUP_STYLE_MINIMAL
    val isCompactCamera = isCamera && isMinimalActive
    val isCenteredActive = activeDialogEntity != null && !isMinimalActive
    val activeDialogEntityId = if (isMinimalActive) activeDialogEntity?.entityId else null

    val badgeState = remember { FocusBadgeState() }
    LaunchedEffect(overlayOpenEpoch) {
        if (overlayOpenEpoch > 0) {
            lastFocusedItemId = null
            if (displayEntities.isNotEmpty()) {
                displayEntities.firstOrNull()?.let { first ->
                    val stateText = if (first.isApp) "Open App" else if (first.entity?.isOn == true) "ON" else "OFF"
                    badgeState.update(first.displayName, stateText, null)
                }
                try {
                    firstFocusRequester.requestFocus()
                } catch (_: Exception) {}
            } else if (showRecentApps) {
                displayRecentApps.firstOrNull()?.let { firstPkg ->
                    val appLabel = try {
                        val pm = context.packageManager
                        val info = pm.getApplicationInfo(firstPkg, 0)
                        pm.getApplicationLabel(info).toString()
                    } catch (_: Exception) {
                        firstPkg
                    }
                    badgeState.update(appLabel, "Open App", null)
                }
                try {
                    recentAppsFocusRequesters.firstOrNull()?.requestFocus()
                } catch (_: Exception) {}
            } else {
                try {
                    firstFocusRequester.requestFocus()
                } catch (_: Exception) {}
            }
        }
    }
    val isDialogActive = activeDialogEntity != null
    val scope = rememberCoroutineScope()
    var suppressCenterKeyAfterLongPress by remember { mutableStateOf(false) }

    val handleDirectionToRecent: (Int) -> Unit = { dockIndex ->
        scope.launch {
            try {
                val targetIndex = dockIndex.coerceIn(0, (displayRecentApps.size - 1).coerceAtLeast(0))
                recentAppsFocusRequesters.getOrNull(targetIndex)?.requestFocus()
            } catch (_: Exception) {}
        }
    }

    val handleExitToDock: () -> Unit = {
        if (displayEntities.isNotEmpty()) {
            scope.launch {
                val target = when (lastFocusedItemId) {
                    "dock_settings_tile" -> settingsFocusRequester
                    "dock_reorder_tile" -> if (displayEntities.size > 1) reorderFocusRequester else settingsFocusRequester
                    null -> firstFocusRequester
                    else -> itemFocusRequesters[lastFocusedItemId] ?: firstFocusRequester
                }
                try { target.requestFocus() } catch (_: Exception) {}
            }
        }
    }

    val handleItemClick: (DockItem) -> Unit = { item ->
        if (item.isApp && item.packageName != null) {
            viewModel.launchApp(item.packageName)
            onDismiss()
        } else if (item.entity != null) {
            if (item.entity.domain == "camera") {
                viewModel.openEntityDialog(item.entity)
            } else {
                viewModel.toggleEntity(item.id)
            }
        }
    }

    val handleItemLongPress: (DockItem) -> Unit = { item ->
        suppressCenterKeyAfterLongPress = true
        scope.launch {
            delay(1000L)
            suppressCenterKeyAfterLongPress = false
        }
        item.entity?.let { viewModel.openEntityDialog(it) }
    }

    // Completely transparent root overlay (TV display shows behind dock)
    CompositionLocalProvider(LocalThemePalette provides activePalette) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Transparent)
            .onPreviewKeyEvent { keyEvent ->
                val native = keyEvent.nativeKeyEvent
                val isCenter = native.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                        native.keyCode == KeyEvent.KEYCODE_ENTER ||
                        native.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
                if (isCenter && suppressCenterKeyAfterLongPress) {
                    if (keyEvent.type == KeyEventType.KeyUp) {
                        suppressCenterKeyAfterLongPress = false
                    }
                    return@onPreviewKeyEvent true
                }
                false
            }
    ) {
        when (layoutPosition) {
            "DOCK_LEFT" -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(start = 36.dp)
                ) {
                    // Floating label badge at top of the side dock area, starting from the recent apps column
                    if (activeDialogEntity == null) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(
                                    start = if (displayEntities.isNotEmpty()) 100.dp else 0.dp,
                                    top = 36.dp
                                )
                        ) {
                            FloatingLabelBadge(
                                badgeState = badgeState,
                                isReorderMode = isReorderMode,
                                selectedEntityId = selectedReorderEntityId
                            )
                        }
                    }

                    if (displayEntities.isNotEmpty()) {
                        Row(
                            modifier = Modifier.align(Alignment.CenterStart),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            DockListVertical(
                                items = displayEntities,
                                allEntities = allEntities,
                                isReorderMode = isReorderMode,
                                isDialogActive = isDialogActive,
                                activeDialogEntityId = activeDialogEntityId,
                                selectedReorderId = selectedReorderEntityId,
                                overlayOpenEpoch = overlayOpenEpoch,
                                firstFocusRequester = firstFocusRequester,
                                settingsFocusRequester = settingsFocusRequester,
                                reorderFocusRequester = reorderFocusRequester,
                                itemFocusRequesters = itemFocusRequesters,
                                onDirectionToRecent = if (showRecentApps) handleDirectionToRecent else null,
                                directionToRecentKeyCode = KeyEvent.KEYCODE_DPAD_RIGHT,
                                onItemFocusedId = { id -> lastFocusedItemId = id },
                                onFocused = { name, state, humidity -> badgeState.update(name, state, humidity) },
                                onUnfocused = { name -> badgeState.clear(name) },
                                onItemClick = handleItemClick,
                                onItemLongPress = handleItemLongPress,
                                onSelectForReorder = { id -> viewModel.selectEntityForReorder(id) },
                                onMove = { id, dir -> viewModel.moveEntity(id, dir) },
                                onToggleReorderMode = { viewModel.toggleReorderMode() },
                                onOpenSettings = onOpenSettings
                            )

                            if (isMinimalActive && activeDialogEntity != null) {
                                Spacer(modifier = Modifier.width(16.dp))
                                MinimalEntityPopupContent(
                                    entity = activeDialogEntity!!,
                                    isCompactCamera = isCompactCamera,
                                    isVertical = true,
                                    viewModel = viewModel,
                                    onDismiss = { viewModel.closeEntityDialog() }
                                )
                            }
                        }

                        if (showRecentApps && activeDialogEntity == null) {
                            RecentAppsSatellite(
                                recentApps = displayRecentApps,
                                focusRequesters = recentAppsFocusRequesters,
                                isVertical = true,
                                onLaunchApp = { pkg ->
                                    viewModel.launchApp(pkg)
                                    onDismiss()
                                },
                                onExitToDock = handleExitToDock,
                                exitKeyCode = KeyEvent.KEYCODE_DPAD_LEFT,
                                showSettingsButton = false,
                                onOpenSettings = onOpenSettings,
                                settingsFocusRequester = settingsFocusRequester,
                                onFocused = { label -> badgeState.update(label, if (label == "Settings") "Configure" else "Open App", null) },
                                onUnfocused = { label -> badgeState.clear(label) },
                                modifier = Modifier
                                    .align(Alignment.CenterStart)
                                    .padding(start = 92.dp)
                            )
                        }
                    } else if (showRecentApps) {
                        // displayEntities.isEmpty() && showRecentApps: Show ONLY recent apps + circular settings button centered vertically
                        RecentAppsSatellite(
                            recentApps = displayRecentApps,
                            focusRequesters = recentAppsFocusRequesters,
                            isVertical = true,
                            onLaunchApp = { pkg ->
                                viewModel.launchApp(pkg)
                                onDismiss()
                            },
                            onExitToDock = { /* No dock to exit to */ },
                            exitKeyCode = KeyEvent.KEYCODE_DPAD_LEFT,
                            showSettingsButton = true,
                            onOpenSettings = onOpenSettings,
                            settingsFocusRequester = settingsFocusRequester,
                            onFocused = { label -> badgeState.update(label, if (label == "Settings") "Configure" else "Open App", null) },
                            onUnfocused = { label -> badgeState.clear(label) },
                            modifier = Modifier.align(Alignment.CenterStart)
                        )
                    } else {
                        // displayEntities.isEmpty() && !showRecentApps: Show "Configure from settings" centered vertically
                        Box(
                            modifier = Modifier.align(Alignment.CenterStart)
                        ) {
                            DockListVertical(
                                items = emptyList(),
                                allEntities = allEntities,
                                isReorderMode = false,
                                isDialogActive = false,
                                activeDialogEntityId = null,
                                selectedReorderId = null,
                                overlayOpenEpoch = overlayOpenEpoch,
                                firstFocusRequester = firstFocusRequester,
                                settingsFocusRequester = settingsFocusRequester,
                                reorderFocusRequester = reorderFocusRequester,
                                itemFocusRequesters = emptyMap(),
                                onDirectionToRecent = null,
                                directionToRecentKeyCode = KeyEvent.KEYCODE_DPAD_RIGHT,
                                onItemFocusedId = { lastFocusedItemId = it },
                                onFocused = { name, state, hum -> badgeState.update(name, state, hum) },
                                onUnfocused = { name -> badgeState.clear(name) },
                                onItemClick = handleItemClick,
                                onItemLongPress = handleItemLongPress,
                                onSelectForReorder = {},
                                onMove = { _, _ -> },
                                onToggleReorderMode = {},
                                onOpenSettings = onOpenSettings
                            )
                        }
                    }
                }
            }

            "DOCK_RIGHT" -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(end = 36.dp)
                ) {
                    // Floating label badge at top of the side dock area, starting from the recent apps column
                    if (activeDialogEntity == null) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(
                                    end = if (displayEntities.isNotEmpty()) 100.dp else 0.dp,
                                    top = 36.dp
                                )
                        ) {
                            FloatingLabelBadge(
                                badgeState = badgeState,
                                isReorderMode = isReorderMode,
                                selectedEntityId = selectedReorderEntityId
                            )
                        }
                    }

                    if (displayEntities.isNotEmpty()) {
                        Row(
                            modifier = Modifier.align(Alignment.CenterEnd),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (isMinimalActive && activeDialogEntity != null) {
                                MinimalEntityPopupContent(
                                    entity = activeDialogEntity!!,
                                    isCompactCamera = isCompactCamera,
                                    isVertical = true,
                                    viewModel = viewModel,
                                    onDismiss = { viewModel.closeEntityDialog() }
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                            }

                            DockListVertical(
                                items = displayEntities,
                                allEntities = allEntities,
                                isReorderMode = isReorderMode,
                                isDialogActive = isDialogActive,
                                activeDialogEntityId = activeDialogEntityId,
                                selectedReorderId = selectedReorderEntityId,
                                overlayOpenEpoch = overlayOpenEpoch,
                                firstFocusRequester = firstFocusRequester,
                                settingsFocusRequester = settingsFocusRequester,
                                reorderFocusRequester = reorderFocusRequester,
                                itemFocusRequesters = itemFocusRequesters,
                                onDirectionToRecent = if (showRecentApps) handleDirectionToRecent else null,
                                directionToRecentKeyCode = KeyEvent.KEYCODE_DPAD_LEFT,
                                onItemFocusedId = { id -> lastFocusedItemId = id },
                                onFocused = { name, state, humidity -> badgeState.update(name, state, humidity) },
                                onUnfocused = { name -> badgeState.clear(name) },
                                onItemClick = handleItemClick,
                                onItemLongPress = handleItemLongPress,
                                onSelectForReorder = { id -> viewModel.selectEntityForReorder(id) },
                                onMove = { id, dir -> viewModel.moveEntity(id, dir) },
                                onToggleReorderMode = { viewModel.toggleReorderMode() },
                                onOpenSettings = onOpenSettings
                            )
                        }

                        if (showRecentApps && activeDialogEntity == null) {
                            RecentAppsSatellite(
                                recentApps = displayRecentApps,
                                focusRequesters = recentAppsFocusRequesters,
                                isVertical = true,
                                onLaunchApp = { pkg ->
                                    viewModel.launchApp(pkg)
                                    onDismiss()
                                },
                                onExitToDock = handleExitToDock,
                                exitKeyCode = KeyEvent.KEYCODE_DPAD_RIGHT,
                                showSettingsButton = false,
                                onOpenSettings = onOpenSettings,
                                settingsFocusRequester = settingsFocusRequester,
                                onFocused = { label -> badgeState.update(label, if (label == "Settings") "Configure" else "Open App", null) },
                                onUnfocused = { label -> badgeState.clear(label) },
                                modifier = Modifier
                                    .align(Alignment.CenterEnd)
                                    .padding(end = 92.dp)
                            )
                        }
                    } else if (showRecentApps) {
                        // displayEntities.isEmpty() && showRecentApps: Show ONLY recent apps + circular settings button centered vertically
                        RecentAppsSatellite(
                            recentApps = displayRecentApps,
                            focusRequesters = recentAppsFocusRequesters,
                            isVertical = true,
                            onLaunchApp = { pkg ->
                                viewModel.launchApp(pkg)
                                onDismiss()
                            },
                            onExitToDock = { /* No dock to exit to */ },
                            exitKeyCode = KeyEvent.KEYCODE_DPAD_RIGHT,
                            showSettingsButton = true,
                            onOpenSettings = onOpenSettings,
                            settingsFocusRequester = settingsFocusRequester,
                            onFocused = { label -> badgeState.update(label, if (label == "Settings") "Configure" else "Open App", null) },
                            onUnfocused = { label -> badgeState.clear(label) },
                            modifier = Modifier.align(Alignment.CenterEnd)
                        )
                    } else {
                        // displayEntities.isEmpty() && !showRecentApps: Show "Configure from settings" centered vertically
                        Box(
                            modifier = Modifier.align(Alignment.CenterEnd)
                        ) {
                            DockListVertical(
                                items = emptyList(),
                                allEntities = allEntities,
                                isReorderMode = false,
                                isDialogActive = false,
                                activeDialogEntityId = null,
                                selectedReorderId = null,
                                overlayOpenEpoch = overlayOpenEpoch,
                                firstFocusRequester = firstFocusRequester,
                                settingsFocusRequester = settingsFocusRequester,
                                reorderFocusRequester = reorderFocusRequester,
                                itemFocusRequesters = emptyMap(),
                                onDirectionToRecent = null,
                                directionToRecentKeyCode = KeyEvent.KEYCODE_DPAD_LEFT,
                                onItemFocusedId = { lastFocusedItemId = it },
                                onFocused = { name, state, hum -> badgeState.update(name, state, hum) },
                                onUnfocused = { name -> badgeState.clear(name) },
                                onItemClick = handleItemClick,
                                onItemLongPress = handleItemLongPress,
                                onSelectForReorder = {},
                                onMove = { _, _ -> },
                                onToggleReorderMode = {},
                                onOpenSettings = onOpenSettings
                            )
                        }
                    }
                }
            }

            else -> { // DOCK_BOTTOM (Default)
                Box(
                    modifier = Modifier.fillMaxSize()
                ) {
                    if (displayEntities.isNotEmpty()) {
                        Column(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            if (isMinimalActive && activeDialogEntity != null) {
                                MinimalEntityPopupContent(
                                    entity = activeDialogEntity!!,
                                    isCompactCamera = isCompactCamera,
                                    viewModel = viewModel,
                                    onDismiss = { viewModel.closeEntityDialog() }
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                            } else {
                                FloatingLabelBadge(
                                    badgeState = badgeState,
                                    isReorderMode = isReorderMode,
                                    selectedEntityId = selectedReorderEntityId
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                            }

                            DockListHorizontal(
                                items = displayEntities,
                                allEntities = allEntities,
                                isReorderMode = isReorderMode,
                                isDialogActive = isDialogActive,
                                activeDialogEntityId = activeDialogEntityId,
                                selectedReorderId = selectedReorderEntityId,
                                overlayOpenEpoch = overlayOpenEpoch,
                                firstFocusRequester = firstFocusRequester,
                                settingsFocusRequester = settingsFocusRequester,
                                reorderFocusRequester = reorderFocusRequester,
                                itemFocusRequesters = itemFocusRequesters,
                                onDirectionToRecent = if (showRecentApps && !isDialogActive) handleDirectionToRecent else null,
                                directionToRecentKeyCode = KeyEvent.KEYCODE_DPAD_DOWN,
                                onItemFocusedId = { id -> lastFocusedItemId = id },
                                onFocused = { name, state, humidity -> badgeState.update(name, state, humidity) },
                                onUnfocused = { name -> badgeState.clear(name) },
                                onItemClick = handleItemClick,
                                onItemLongPress = handleItemLongPress,
                                onSelectForReorder = { id -> viewModel.selectEntityForReorder(id) },
                                onMove = { id, dir -> viewModel.moveEntity(id, dir) },
                                onToggleReorderMode = { viewModel.toggleReorderMode() },
                                onOpenSettings = onOpenSettings
                            )

                            if (showRecentApps) {
                                Spacer(modifier = Modifier.height(4.dp))
                                RecentAppsSatellite(
                                    recentApps = displayRecentApps,
                                    focusRequesters = recentAppsFocusRequesters,
                                    isVertical = false,
                                    isDialogActive = isDialogActive,
                                    onLaunchApp = { pkg ->
                                        viewModel.launchApp(pkg)
                                        onDismiss()
                                    },
                                    onExitToDock = handleExitToDock,
                                    exitKeyCode = KeyEvent.KEYCODE_DPAD_UP,
                                    showSettingsButton = false,
                                    onOpenSettings = onOpenSettings,
                                    settingsFocusRequester = settingsFocusRequester,
                                    onFocused = { label -> badgeState.update(label, if (label == "Settings") "Configure" else "Open App", null) },
                                    onUnfocused = { label -> badgeState.clear(label) },
                                    modifier = Modifier.align(Alignment.CenterHorizontally)
                                )
                            }
                        }
                    } else if (showRecentApps) {
                        // displayEntities.isEmpty() && showRecentApps: Show ONLY recent apps + circular settings button
                        Column(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            FloatingLabelBadge(
                                badgeState = badgeState,
                                isReorderMode = isReorderMode,
                                selectedEntityId = selectedReorderEntityId
                            )
                            Spacer(modifier = Modifier.height(14.dp))
                            RecentAppsSatellite(
                                recentApps = displayRecentApps,
                                focusRequesters = recentAppsFocusRequesters,
                                isVertical = false,
                                onLaunchApp = { pkg ->
                                    viewModel.launchApp(pkg)
                                    onDismiss()
                                },
                                onExitToDock = { /* No dock to exit to */ },
                                exitKeyCode = KeyEvent.KEYCODE_DPAD_UP,
                                showSettingsButton = true,
                                onOpenSettings = onOpenSettings,
                                settingsFocusRequester = settingsFocusRequester,
                                onFocused = { label -> badgeState.update(label, if (label == "Settings") "Configure" else "Open App", null) },
                                onUnfocused = { label -> badgeState.clear(label) },
                                modifier = Modifier.align(Alignment.CenterHorizontally)
                            )
                        }
                    } else {
                        // displayEntities.isEmpty() && !showRecentApps: Show "Configure from settings"
                        Column(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 44.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            DockListHorizontal(
                                items = emptyList(),
                                allEntities = allEntities,
                                isReorderMode = false,
                                isDialogActive = false,
                                activeDialogEntityId = null,
                                selectedReorderId = null,
                                overlayOpenEpoch = overlayOpenEpoch,
                                firstFocusRequester = firstFocusRequester,
                                settingsFocusRequester = settingsFocusRequester,
                                reorderFocusRequester = reorderFocusRequester,
                                itemFocusRequesters = emptyMap(),
                                onDirectionToRecent = null,
                                directionToRecentKeyCode = KeyEvent.KEYCODE_DPAD_DOWN,
                                onItemFocusedId = { lastFocusedItemId = it },
                                onFocused = { name, state, hum -> badgeState.update(name, state, hum) },
                                onUnfocused = { name -> badgeState.clear(name) },
                                onItemClick = handleItemClick,
                                onItemLongPress = handleItemLongPress,
                                onSelectForReorder = {},
                                onMove = { _, _ -> },
                                onToggleReorderMode = {},
                                onOpenSettings = onOpenSettings
                            )
                        }
                    }
                }
            }
        }

        // Active Centered Dialogs (Brightness / Climate / Switch / Media / Camera) rendered directly as in-window modal overlay
        if (isCenteredActive && activeDialogEntity != null) {
            val entity = activeDialogEntity!!
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.65f)),
                contentAlignment = Alignment.Center
            ) {
                when {
                    entity.domain == "climate" -> {
                        ClimateDialog(
                            entity = entity,
                            onSetTemperature = { viewModel.setTargetTemperature(entity.entityId, it) },
                            onSetHvacMode = { viewModel.setHvacMode(entity.entityId, it) },
                            onSetFanMode = { viewModel.setFanMode(entity.entityId, it) },
                            onSetPresetMode = { viewModel.setPresetMode(entity.entityId, it) },
                            onDismiss = { viewModel.closeEntityDialog() }
                        )
                    }
                    entity.domain == "light" && entity.supportsBrightness -> {
                        BrightnessDialog(
                            entity = entity,
                            onSetBrightness = { viewModel.setBrightness(entity.entityId, it) },
                            onDismiss = { viewModel.closeEntityDialog() }
                        )
                    }
                    entity.domain == "switch" || entity.domain == "input_boolean" || (entity.domain == "light" && !entity.supportsBrightness) -> {
                        SwitchDialog(
                            entity = entity,
                            onToggle = { viewModel.toggleEntity(entity.entityId) },
                            onTurnOn = { viewModel.turnOnEntity(entity.entityId) },
                            onTurnOff = { viewModel.turnOffEntity(entity.entityId) },
                            onDismiss = { viewModel.closeEntityDialog() }
                        )
                    }
                    entity.domain == "media_player" -> {
                        MediaPlayerDialog(
                            entity = entity,
                            onSelectSource = { source -> viewModel.selectMediaSource(entity.entityId, source) },
                            onSetVolume = { volume -> viewModel.setMediaVolume(entity.entityId, volume) },
                            onTogglePlayPause = { viewModel.toggleMediaPlayPause(entity.entityId) },
                            onNextTrack = { viewModel.mediaNext(entity.entityId) },
                            onPreviousTrack = { viewModel.mediaPrevious(entity.entityId) },
                            onToggleMute = { viewModel.toggleMediaMute(entity.entityId, entity.isVolumeMuted) },
                            onTurnOn = { viewModel.turnOnEntity(entity.entityId) },
                            onTurnOff = { viewModel.turnOffEntity(entity.entityId) },
                            onDismiss = { viewModel.closeEntityDialog() }
                        )
                    }
                    entity.domain == "camera" -> {
                        val serverUrl by viewModel.serverUrl.collectAsState()
                        val accessToken by viewModel.accessToken.collectAsState()
                        CameraDialog(
                            entity = entity,
                            serverUrl = serverUrl,
                            accessToken = accessToken,
                            isCompact = false,
                            onDismiss = { viewModel.closeEntityDialog() }
                        )
                    }
                    entity.domain == "cover" -> {
                        CoverDialog(
                            entity = entity,
                            onOpen = { viewModel.openCover(entity.entityId) },
                            onClose = { viewModel.closeCover(entity.entityId) },
                            onStop = { viewModel.stopCover(entity.entityId) },
                            onSetPosition = { viewModel.setCoverPosition(entity.entityId, it) },
                            onSetTilt = { viewModel.setCoverTiltPosition(entity.entityId, it) },
                            onDismiss = { viewModel.closeEntityDialog() }
                        )
                    }
                    entity.domain == "fan" -> {
                        FanDialog(
                            entity = entity,
                            onToggle = { viewModel.toggleEntity(entity.entityId) },
                            onSetPercentage = { viewModel.setFanPercentage(entity.entityId, it) },
                            onSetOscillating = { viewModel.setFanOscillating(entity.entityId, it) },
                            onDismiss = { viewModel.closeEntityDialog() }
                        )
                    }
                    entity.domain == "lock" -> {
                        LockDialog(
                            entity = entity,
                            onLock = { viewModel.lock(entity.entityId) },
                            onUnlock = { viewModel.unlock(entity.entityId) },
                            onDismiss = { viewModel.closeEntityDialog() }
                        )
                    }
                    else -> {}
                }
            }
        }
    }
}
}
