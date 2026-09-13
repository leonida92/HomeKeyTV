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
import com.homekey.tv.data.local.PreferencesManager
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
// Long-press (hold DPAD center/OK) opens the light/climate control dialog.
private const val DIALOG_LONG_PRESS_MS = 550L

@Stable
class FocusBadgeState {
    var name by mutableStateOf<String?>(null)
    var state by mutableStateOf<String?>(null)
    var humidity by mutableStateOf<Float?>(null)

    fun update(newName: String, newState: String, newHumidity: Float? = null) {
        name = newName
        state = newState
        humidity = newHumidity
    }

    fun clear(oldName: String) {
        if (name == oldName) {
            name = null
            state = null
            humidity = null
        }
    }
}

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
    val isMinimalActive = activeDialogEntity != null && popupStyle == PreferencesManager.POPUP_STYLE_MINIMAL
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
                    else -> {}
                }
            }
        }
    }
}
}

@Composable
private fun MinimalEntityPopupContent(
    entity: HAEntityState,
    isCompactCamera: Boolean,
    isVertical: Boolean = false,
    viewModel: PanelViewModel,
    onDismiss: () -> Unit
) {
    when {
        isCompactCamera -> {
            val serverUrl by viewModel.serverUrl.collectAsState()
            val accessToken by viewModel.accessToken.collectAsState()
            val cameraCompactSize by viewModel.cameraCompactSize.collectAsState()
            CameraDialog(
                entity = entity,
                serverUrl = serverUrl,
                accessToken = accessToken,
                isCompact = true,
                compactSize = cameraCompactSize,
                onDismiss = onDismiss
            )
        }
        entity.domain == "climate" -> {
            val allEntities by viewModel.allEntities.collectAsState()
            val fallbackHumidity = remember(entity.entityId, allEntities) {
                if (entity.currentHumidity != null) null
                else {
                    val baseId = entity.entityId.removePrefix("climate.")
                    allEntities["sensor.${baseId}_humidity"]?.state?.toFloatOrNull()
                        ?: allEntities.values.firstOrNull {
                            it.domain == "sensor" &&
                            it.attributes["device_class"]?.toString()?.contains("humidity", ignoreCase = true) == true &&
                            (it.entityId.contains(baseId, ignoreCase = true) || it.friendlyName.contains(entity.friendlyName, ignoreCase = true))
                        }?.state?.toFloatOrNull()
                }
            }
            MinimalClimatePopup(
                entity = entity,
                humidity = entity.currentHumidity ?: fallbackHumidity,
                isVertical = isVertical,
                onSetTemperature = { viewModel.setTargetTemperature(entity.entityId, it) },
                onSetHvacMode = { viewModel.setHvacMode(entity.entityId, it) },
                onSetFanMode = { viewModel.setFanMode(entity.entityId, it) },
                onDismiss = onDismiss
            )
        }
        entity.domain == "light" && entity.supportsBrightness -> {
            MinimalBrightnessPopup(
                entity = entity,
                isVertical = isVertical,
                onSetBrightness = { viewModel.setBrightness(entity.entityId, it) },
                onDismiss = onDismiss
            )
        }
        entity.domain == "switch" || entity.domain == "input_boolean" || (entity.domain == "light" && !entity.supportsBrightness) -> {
            MinimalSwitchPopup(
                entity = entity,
                isVertical = isVertical,
                onToggle = { viewModel.toggleEntity(entity.entityId) },
                onTurnOn = { viewModel.turnOnEntity(entity.entityId) },
                onTurnOff = { viewModel.turnOffEntity(entity.entityId) },
                onDismiss = onDismiss
            )
        }
        entity.domain == "media_player" -> {
            MinimalMediaPopup(
                entity = entity,
                isVertical = isVertical,
                onTogglePlayPause = { viewModel.toggleMediaPlayPause(entity.entityId) },
                onNextTrack = { viewModel.mediaNext(entity.entityId) },
                onPreviousTrack = { viewModel.mediaPrevious(entity.entityId) },
                onSetVolume = { viewModel.setMediaVolume(entity.entityId, it) },
                onToggleMute = { viewModel.toggleMediaMute(entity.entityId, entity.isVolumeMuted) },
                onDismiss = onDismiss
            )
        }
        else -> {}
    }
}

@Composable
fun FloatingLabelBadge(
    badgeState: FocusBadgeState,
    isReorderMode: Boolean,
    selectedEntityId: String?
) {
    val name = badgeState.name
    val state = badgeState.state

    val palette = LocalThemePalette.current
    val offColor = palette.getOffBackgroundColor()
    val isOffDark = DomainColorPalette.isColorDark(offColor)
    val badgeTextColor = if (isOffDark) Color.White else Color(0xFF0F172A)
    val badgeStateColor = palette.getColorForDomain("switch")

    AnimatedVisibility(
        visible = name != null,
        enter = fadeIn(tween(60)),
        exit = fadeOut(tween(40))
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(offColor)
                .border(
                    width = if (isReorderMode) 1.2.dp else 0.dp,
                    color = if (isReorderMode) HA_Yellow_On else Color.Transparent,
                    shape = RoundedCornerShape(12.dp)
                )
                .padding(horizontal = 14.dp, vertical = 5.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (isReorderMode) {
                    Icon(
                        Icons.Default.SwapHoriz,
                        contentDescription = null,
                        tint = HA_Yellow_On,
                        modifier = Modifier.size(15.dp)
                    )
                    Text(
                        text = name ?: "",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = if (selectedEntityId != null) "•  MOVING (D-Pad, OK to drop)"
                        else if (name == "Reorder Mode" || name == "Settings") "•  ${state?.uppercase() ?: "ACTIVE"}"
                        else "•  REORDER (OK to grab)",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = HA_Yellow_On
                    )
                } else {
                    Text(
                        text = name ?: "",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = badgeTextColor
                    )
                    if (!state.isNullOrBlank()) {
                        Text(
                            text = "•  ${state.uppercase()}",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = badgeStateColor
                        )
                    }
                    if (badgeState.humidity != null) {
                        Text(
                            text = "•",
                            fontSize = 12.sp,
                            color = if (isOffDark) Color(0x66FFFFFF) else Color(0x66000000)
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.WaterDrop,
                                contentDescription = "Humidity",
                                tint = badgeStateColor,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(
                                text = "${badgeState.humidity!!.toInt()}%",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = badgeStateColor
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DockListHorizontal(
    items: List<DockItem>,
    allEntities: Map<String, HAEntityState> = emptyMap(),
    isReorderMode: Boolean,
    isDialogActive: Boolean,
    activeDialogEntityId: String? = null,
    selectedReorderId: String?,
    overlayOpenEpoch: Long = 0L,
    firstFocusRequester: FocusRequester,
    settingsFocusRequester: FocusRequester,
    reorderFocusRequester: FocusRequester,
    itemFocusRequesters: Map<String, FocusRequester>,
    onDirectionToRecent: ((Int) -> Unit)? = null,
    directionToRecentKeyCode: Int = KeyEvent.KEYCODE_DPAD_UP,
    onItemFocusedId: (String) -> Unit = {},
    onFocused: (String, String, Float?) -> Unit = { _, _, _ -> },
    onUnfocused: (String) -> Unit,
    onItemClick: (DockItem) -> Unit,
    onItemLongPress: (DockItem) -> Unit,
    onSelectForReorder: (String) -> Unit,
    onMove: (String, Int) -> Unit,
    onToggleReorderMode: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var lastFocusedItemId by remember { mutableStateOf<String?>(null) }
    var wasDialogActive by remember { mutableStateOf(false) }

    LaunchedEffect(overlayOpenEpoch) {
        if (overlayOpenEpoch > 0) {
            lastFocusedItemId = null
            if (listState.firstVisibleItemIndex != 0 || listState.firstVisibleItemScrollOffset != 0) {
                try {
                    listState.scrollToItem(0)
                } catch (_: Exception) {}
            }
            try {
                firstFocusRequester.requestFocus()
            } catch (_: Exception) {}
        }
    }

    LaunchedEffect(Unit) {
        delay(50)
        try {
            val target = when (lastFocusedItemId) {
                "dock_settings_tile" -> settingsFocusRequester
                "dock_reorder_tile" -> if (items.size > 1) reorderFocusRequester else firstFocusRequester
                else -> lastFocusedItemId?.let { itemFocusRequesters[it] } ?: firstFocusRequester
            }
            target.requestFocus()
        } catch (_: Exception) {}
    }

    LaunchedEffect(isDialogActive) {
        if (isDialogActive) {
            wasDialogActive = true
        } else if (wasDialogActive) {
            wasDialogActive = false
            delay(50)
            try {
                val target = when (lastFocusedItemId) {
                    "dock_settings_tile" -> settingsFocusRequester
                    "dock_reorder_tile" -> if (items.size > 1) reorderFocusRequester else firstFocusRequester
                    else -> lastFocusedItemId?.let { itemFocusRequesters[it] } ?: firstFocusRequester
                }
                target.requestFocus()
            } catch (_: Exception) {}
        }
    }

    if (items.isEmpty()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xEE161E2E))
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Text(
                text = "Configure from settings",
                color = TV_Text_Secondary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
            FocusableButton(
                text = "Settings",
                icon = Icons.Default.Settings,
                modifier = Modifier.focusRequester(firstFocusRequester),
                onClick = onOpenSettings
            )
        }
    } else {
        LazyRow(
            state = listState,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            modifier = Modifier.wrapContentWidth()
        ) {
            itemsIndexed(
                items = items,
                key = { _, item -> item.id }
            ) { index, item ->
                DockTile(
                    item = item,
                    allEntities = allEntities,
                    index = index,
                    isReorderMode = isReorderMode,
                    isDialogActive = isDialogActive,
                    activeDialogEntityId = activeDialogEntityId,
                    isSelectedForReorder = selectedReorderId == item.id,
                    isVertical = false,
                    customFocusRequester = if (index == 0) firstFocusRequester else itemFocusRequesters[item.id],
                    onDirectionToRecent = onDirectionToRecent?.let { fn -> { fn(index) } },
                    directionToRecentKeyCode = directionToRecentKeyCode,
                    onWrapToLast = {
                        scope.launch {
                            val lastIndex = if (items.size > 1) items.size + 1 else items.size
                            try {
                                listState.scrollToItem(lastIndex)
                            } catch (_: Exception) {}
                            delay(16)
                            try {
                                settingsFocusRequester.requestFocus()
                            } catch (_: Exception) {}
                        }
                    },
                    onFocused = { name, state, hum ->
                        lastFocusedItemId = item.id
                        onItemFocusedId(item.id)
                        onFocused(name, state, hum)
                    },
                    onUnfocused = onUnfocused,
                    onClick = { onItemClick(item) },
                    onLongPress = if (!item.isApp && item.entity != null &&
                        (item.entity.domain == "light" || item.entity.domain == "climate" || item.entity.domain == "switch" || item.entity.domain == "input_boolean" || item.entity.domain == "media_player")
                    ) {
                        { onItemLongPress(item) }
                    } else null,
                    onSelectForReorder = { onSelectForReorder(item.id) },
                    onMove = { dir -> onMove(item.id, dir) }
                )
            }

            // Reorder Mode Toggle Tile (only shown when more than 1 item pinned)
            if (items.size > 1) {
                item(key = "dock_reorder_tile") {
                    DockReorderTile(
                        focusRequester = reorderFocusRequester,
                        isReorderMode = isReorderMode,
                        isDialogActive = isDialogActive,
                        activeDialogEntityId = activeDialogEntityId,
                        onDirectionToRecent = onDirectionToRecent?.let { fn -> { fn(items.size) } },
                        directionToRecentKeyCode = directionToRecentKeyCode,
                        onFocused = {
                            lastFocusedItemId = "dock_reorder_tile"
                            onItemFocusedId("dock_reorder_tile")
                            onFocused("Reorder Mode", if (isReorderMode) "Active (Click to Exit)" else "Click to Enter", null)
                        },
                        onUnfocused = { onUnfocused("Reorder Mode") },
                        onClick = onToggleReorderMode
                    )
                }
            }

            // Settings Tile at the end
            item(key = "dock_settings_tile") {
                DockSettingsTile(
                    focusRequester = settingsFocusRequester,
                    isVertical = false,
                    isDialogActive = isDialogActive,
                    activeDialogEntityId = activeDialogEntityId,
                    onDirectionToRecent = onDirectionToRecent?.let { fn -> { fn(if (items.size > 1) items.size + 1 else items.size) } },
                    directionToRecentKeyCode = directionToRecentKeyCode,
                    onWrapToFirst = {
                        scope.launch {
                            try {
                                listState.scrollToItem(0)
                            } catch (_: Exception) {}
                            delay(16)
                            try {
                                firstFocusRequester.requestFocus()
                            } catch (_: Exception) {}
                        }
                    },
                    onFocused = {
                        lastFocusedItemId = "dock_settings_tile"
                        onItemFocusedId("dock_settings_tile")
                        onFocused("Settings", "Configure", null)
                    },
                    onUnfocused = { onUnfocused("Settings") },
                    onClick = onOpenSettings
                )
            }
        }
    }
}

@Composable
fun DockListVertical(
    items: List<DockItem>,
    allEntities: Map<String, HAEntityState> = emptyMap(),
    isReorderMode: Boolean,
    isDialogActive: Boolean,
    activeDialogEntityId: String? = null,
    selectedReorderId: String?,
    overlayOpenEpoch: Long = 0L,
    firstFocusRequester: FocusRequester,
    settingsFocusRequester: FocusRequester,
    reorderFocusRequester: FocusRequester,
    itemFocusRequesters: Map<String, FocusRequester>,
    onDirectionToRecent: ((Int) -> Unit)? = null,
    directionToRecentKeyCode: Int = KeyEvent.KEYCODE_DPAD_RIGHT,
    onItemFocusedId: (String) -> Unit = {},
    onFocused: (String, String, Float?) -> Unit = { _, _, _ -> },
    onUnfocused: (String) -> Unit,
    onItemClick: (DockItem) -> Unit,
    onItemLongPress: (DockItem) -> Unit,
    onSelectForReorder: (String) -> Unit,
    onMove: (String, Int) -> Unit,
    onToggleReorderMode: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var lastFocusedItemId by remember { mutableStateOf<String?>(null) }
    var wasDialogActive by remember { mutableStateOf(false) }

    LaunchedEffect(overlayOpenEpoch) {
        if (overlayOpenEpoch > 0) {
            lastFocusedItemId = null
            if (listState.firstVisibleItemIndex != 0 || listState.firstVisibleItemScrollOffset != 0) {
                try {
                    listState.scrollToItem(0)
                } catch (_: Exception) {}
            }
            try {
                firstFocusRequester.requestFocus()
            } catch (_: Exception) {}
        }
    }

    LaunchedEffect(Unit) {
        delay(50)
        try {
            val target = when (lastFocusedItemId) {
                "dock_settings_tile" -> settingsFocusRequester
                "dock_reorder_tile" -> if (items.size > 1) reorderFocusRequester else firstFocusRequester
                else -> lastFocusedItemId?.let { itemFocusRequesters[it] } ?: firstFocusRequester
            }
            target.requestFocus()
        } catch (_: Exception) {}
    }

    LaunchedEffect(isDialogActive) {
        if (isDialogActive) {
            wasDialogActive = true
        } else if (wasDialogActive) {
            wasDialogActive = false
            delay(50)
            try {
                val target = when (lastFocusedItemId) {
                    "dock_settings_tile" -> settingsFocusRequester
                    "dock_reorder_tile" -> if (items.size > 1) reorderFocusRequester else firstFocusRequester
                    else -> lastFocusedItemId?.let { itemFocusRequesters[it] } ?: firstFocusRequester
                }
                target.requestFocus()
            } catch (_: Exception) {}
        }
    }

    if (items.isEmpty()) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xEE161E2E))
                .padding(16.dp)
        ) {
            Text(
                text = "Configure from settings",
                color = TV_Text_Secondary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
            FocusableButton(
                text = "Settings",
                icon = Icons.Default.Settings,
                modifier = Modifier.focusRequester(firstFocusRequester),
                onClick = onOpenSettings
            )
        }
    } else {
        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp),
            modifier = Modifier.wrapContentHeight()
        ) {
            itemsIndexed(
                items = items,
                key = { _, item -> item.id }
            ) { index, item ->
                DockTile(
                    item = item,
                    allEntities = allEntities,
                    index = index,
                    isReorderMode = isReorderMode,
                    isDialogActive = isDialogActive,
                    activeDialogEntityId = activeDialogEntityId,
                    isSelectedForReorder = selectedReorderId == item.id,
                    isVertical = true,
                    customFocusRequester = if (index == 0) firstFocusRequester else itemFocusRequesters[item.id],
                    onDirectionToRecent = onDirectionToRecent?.let { fn -> { fn(index) } },
                    directionToRecentKeyCode = directionToRecentKeyCode,
                    onWrapToLast = {
                        scope.launch {
                            val lastIndex = if (items.size > 1) items.size + 1 else items.size
                            try {
                                listState.scrollToItem(lastIndex)
                            } catch (_: Exception) {}
                            delay(16)
                            try {
                                settingsFocusRequester.requestFocus()
                            } catch (_: Exception) {}
                        }
                    },
                    onFocused = { name, state, hum ->
                        lastFocusedItemId = item.id
                        onItemFocusedId(item.id)
                        onFocused(name, state, hum)
                    },
                    onUnfocused = onUnfocused,
                    onClick = { onItemClick(item) },
                    onLongPress = if (!item.isApp && item.entity != null &&
                        (item.entity.domain == "light" || item.entity.domain == "climate" || item.entity.domain == "switch" || item.entity.domain == "input_boolean" || item.entity.domain == "media_player")
                    ) {
                        { onItemLongPress(item) }
                    } else null,
                    onSelectForReorder = { onSelectForReorder(item.id) },
                    onMove = { dir -> onMove(item.id, dir) }
                )
            }

            // Reorder Mode Toggle Tile (only shown when more than 1 item pinned)
            if (items.size > 1) {
                item(key = "dock_vertical_reorder") {
                    DockReorderTile(
                        focusRequester = reorderFocusRequester,
                        isReorderMode = isReorderMode,
                        isDialogActive = isDialogActive,
                        activeDialogEntityId = activeDialogEntityId,
                        onDirectionToRecent = onDirectionToRecent?.let { fn -> { fn(items.size) } },
                        directionToRecentKeyCode = directionToRecentKeyCode,
                        onFocused = {
                            lastFocusedItemId = "dock_reorder_tile"
                            onItemFocusedId("dock_reorder_tile")
                            onFocused("Reorder Mode", if (isReorderMode) "Active (Click to Exit)" else "Click to Enter", null)
                        },
                        onUnfocused = { onUnfocused("Reorder Mode") },
                        onClick = onToggleReorderMode
                    )
                }
            }

            // Settings Tile at the end
            item(key = "dock_vertical_settings") {
                DockSettingsTile(
                    focusRequester = settingsFocusRequester,
                    isVertical = true,
                    isDialogActive = isDialogActive,
                    activeDialogEntityId = activeDialogEntityId,
                    onDirectionToRecent = onDirectionToRecent?.let { fn -> { fn(if (items.size > 1) items.size + 1 else items.size) } },
                    directionToRecentKeyCode = directionToRecentKeyCode,
                    onWrapToFirst = {
                        scope.launch {
                            try {
                                listState.scrollToItem(0)
                            } catch (_: Exception) {}
                            delay(16)
                            try {
                                firstFocusRequester.requestFocus()
                            } catch (_: Exception) {}
                        }
                    },
                    onFocused = {
                        lastFocusedItemId = "dock_settings_tile"
                        onItemFocusedId("dock_settings_tile")
                        onFocused("Settings", "Configure", null)
                    },
                    onUnfocused = { onUnfocused("Settings") },
                    onClick = onOpenSettings
                )
            }
        }
    }
}

@Composable
fun DockTile(
    item: DockItem,
    allEntities: Map<String, HAEntityState> = emptyMap(),
    index: Int,
    isReorderMode: Boolean,
    isSelectedForReorder: Boolean,
    isVertical: Boolean,
    customFocusRequester: FocusRequester?,
    isDialogActive: Boolean = false,
    activeDialogEntityId: String? = null,
    onDirectionToRecent: (() -> Unit)? = null,
    directionToRecentKeyCode: Int = KeyEvent.KEYCODE_DPAD_UP,
    onWrapToLast: () -> Unit,
    onFocused: (String, String, Float?) -> Unit,
    onUnfocused: (String) -> Unit,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)? = null,
    onSelectForReorder: () -> Unit,
    onMove: (Int) -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val name = item.displayName
    val isApp = item.isApp
    val entity = item.entity

    val isSelfActive = item.entity != null && item.entity.entityId == activeDialogEntityId
    val isOtherItemActive = activeDialogEntityId != null && !isSelfActive

    val tileAlpha by animateFloatAsState(
        targetValue = if (isOtherItemActive) 0.22f else 1.0f,
        animationSpec = tween(durationMillis = 150),
        label = "tile_alpha"
    )

    // Long-press state (hold OK to open the control dialog).
    val scope = rememberCoroutineScope()
    var longPressJob by remember(item.id) { mutableStateOf<Job?>(null) }
    var suppressClickAfterLongPress by remember(item.id) { mutableStateOf(false) }

    val isOn = if (isApp) true else (entity?.isOn == true)
    val isUnavailable = if (isApp) false else (entity?.isUnavailable == true)
    val appIconBitmap = if (isApp) rememberAppIconBitmap(item.packageName) else null

    LaunchedEffect(isFocused, entity?.state, entity?.currentTemperature, entity?.currentHumidity) {
        if (isFocused) {
            var humidity: Float? = null
            val stateText = if (isApp) {
                "Open App"
            } else {
                when {
                    isUnavailable -> "Offline"
                    entity?.domain == "light" && isOn && entity.brightness != null -> "${((entity.brightness!! / 255f) * 100).toInt()}%"
                    entity?.domain == "climate" -> {
                        val temp = "${entity.currentTemperature ?: entity.targetTemperature ?: 0f}°"
                        humidity = entity.currentHumidity ?: run {
                            val baseId = entity.entityId.removePrefix("climate.")
                            allEntities["sensor.${baseId}_humidity"]?.state?.toFloatOrNull()
                                ?: allEntities.values.firstOrNull {
                                    it.domain == "sensor" &&
                                    it.attributes["device_class"]?.toString()?.contains("humidity", ignoreCase = true) == true &&
                                    (it.entityId.contains(baseId, ignoreCase = true) || it.friendlyName.contains(entity.friendlyName, ignoreCase = true))
                                }?.state?.toFloatOrNull()
                        }
                        temp
                    }
                    entity?.domain == "sensor" -> "${entity.state} ${entity.unitOfMeasurement ?: ""}".trim()
                    else -> if (isOn) "ON" else "OFF"
                }
            }
            onFocused(name, stateText, humidity)
        } else {
            onUnfocused(name)
        }
    }

    val scale by animateFloatAsState(
        targetValue = if (isSelectedForReorder) 1.20f else if (isFocused) 1.12f else 1.0f,
        animationSpec = tween(durationMillis = 80, easing = FastOutSlowInEasing),
        label = "dock_scale"
    )

    val palette = LocalThemePalette.current
    val offColor = palette.getOffBackgroundColor()
    val isOffDark = DomainColorPalette.isColorDark(offColor)
    val focusedOffColor = remember(offColor, isOffDark) {
        if (isOffDark) {
            Color(
                red = (offColor.red + 0.08f).coerceAtMost(1f),
                green = (offColor.green + 0.08f).coerceAtMost(1f),
                blue = (offColor.blue + 0.12f).coerceAtMost(1f),
                alpha = 1f
            )
        } else {
            Color(
                red = (offColor.red - 0.10f).coerceAtLeast(0f),
                green = (offColor.green - 0.10f).coerceAtLeast(0f),
                blue = (offColor.blue - 0.10f).coerceAtLeast(0f),
                alpha = 1f
            )
        }
    }

    val backgroundColor by animateColorAsState(
        targetValue = when {
            isUnavailable -> offColor
            isSelectedForReorder -> Color(0xFFD97706)
            isApp && isFocused -> Color(0xFF2C3852)
            isApp -> offColor
            isFocused && isOn -> getActiveColor(entity?.domain ?: "", palette)
            isFocused && !isOn -> focusedOffColor
            isOn -> getActiveColor(entity?.domain ?: "", palette)
            else -> offColor
        },
        animationSpec = tween(durationMillis = 80),
        label = "tile_bg"
    )

    val borderColor by animateColorAsState(
        targetValue = when {
            isSelectedForReorder -> HA_Yellow_On
            isFocused -> Color.White
            else -> Color.Transparent
        },
        animationSpec = tween(durationMillis = 80),
        label = "tile_border"
    )

    val modifierWithFocus = if (customFocusRequester != null) {
        Modifier.focusRequester(customFocusRequester)
    } else Modifier

    Box(
        modifier = Modifier
            .size(62.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = tileAlpha
            }
            .blur(if (isOtherItemActive) 6.dp else 0.dp)
            .then(modifierWithFocus)
            .clip(RoundedCornerShape(16.dp))
            .background(backgroundColor)
            .border(
                width = if (isSelectedForReorder) 3.5.dp else if (isFocused) 2.6.dp else 0.dp,
                color = borderColor,
                shape = RoundedCornerShape(16.dp)
            )
            .onPreviewKeyEvent { keyEvent ->
                val native = keyEvent.nativeKeyEvent
                val type = keyEvent.type
                val isCenter = native.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                        native.keyCode == KeyEvent.KEYCODE_ENTER ||
                        native.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER

                // Hold OK on a light/climate tile to open its control dialog. If the dialog opens
                // while the key is still held, swallow the following KeyUp so the clickable's normal
                // toggle does not fire as well.
                if (isCenter && onLongPress != null && !isReorderMode) {
                    if (type == KeyEventType.KeyDown && native.repeatCount == 0) {
                        suppressClickAfterLongPress = false
                        longPressJob?.cancel()
                        longPressJob = scope.launch {
                            delay(DIALOG_LONG_PRESS_MS)
                            suppressClickAfterLongPress = true
                            onLongPress()
                        }
                    } else if (type == KeyEventType.KeyUp) {
                        val wasLongPressed = suppressClickAfterLongPress
                        longPressJob?.cancel()
                        longPressJob = null
                        if (wasLongPressed) {
                            suppressClickAfterLongPress = false
                            return@onPreviewKeyEvent true
                        }
                    }
                }

                if (type == KeyEventType.KeyDown) {
                    if (isReorderMode) {
                        if (isCenter) {
                            onSelectForReorder()
                            return@onPreviewKeyEvent true
                        }
                        if (isSelectedForReorder) {
                            when (native.keyCode) {
                                KeyEvent.KEYCODE_DPAD_LEFT -> {
                                    if (!isVertical) {
                                        onMove(-1)
                                        return@onPreviewKeyEvent true
                                    }
                                }
                                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                    if (!isVertical) {
                                        onMove(+1)
                                        return@onPreviewKeyEvent true
                                    }
                                }
                                KeyEvent.KEYCODE_DPAD_UP -> {
                                    if (isVertical) {
                                        onMove(-1)
                                        return@onPreviewKeyEvent true
                                    }
                                }
                                KeyEvent.KEYCODE_DPAD_DOWN -> {
                                    if (isVertical) {
                                        onMove(+1)
                                        return@onPreviewKeyEvent true
                                    }
                                }
                            }
                        }
                    } else {
                        // Direction to recent apps
                        if (onDirectionToRecent != null && native.keyCode == directionToRecentKeyCode) {
                            onDirectionToRecent()
                            return@onPreviewKeyEvent true
                        }

                        // Normal infinite focus wrap
                        if (!isVertical && index == 0 && native.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                            onWrapToLast()
                            return@onPreviewKeyEvent true
                        } else if (isVertical && index == 0 && native.keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                            onWrapToLast()
                            return@onPreviewKeyEvent true
                        }
                    }
                }
                false
            }
            .focusable(
                enabled = !isDialogActive,
                interactionSource = interactionSource
            )
            .clickable(
                enabled = !isDialogActive,
                interactionSource = interactionSource,
                indication = null,
                onClick = {
                    if (!isReorderMode) {
                        onClick()
                    }
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        if (isApp && appIconBitmap != null) {
            Image(
                bitmap = appIconBitmap,
                contentDescription = name,
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(8.dp))
            )
        } else {
            val icon = if (isApp) {
                Icons.Default.Apps
            } else {
                resolveDockIcon(item.customIcon, entity?.icon, entity?.domain ?: "")
            }
            val activeIconTint = palette.getIconColor()
            val offIconTint = remember(isOffDark) {
                if (isOffDark) Color(0xFF94A3B8) else Color(0xFF475569)
            }
            val focusedOffIconTint = remember(isOffDark) {
                if (isOffDark) Color.White else Color(0xFF0F172A)
            }

            Icon(
                imageVector = icon,
                contentDescription = name,
                tint = when {
                    isSelectedForReorder -> Color.White
                    isOn -> activeIconTint
                    isFocused -> focusedOffIconTint
                    else -> offIconTint
                },
                modifier = Modifier.size(30.dp)
            )
        }
    }
}

private val appIconCache = java.util.concurrent.ConcurrentHashMap<String, ImageBitmap>()

@Composable
fun rememberAppIconBitmap(packageName: String?): ImageBitmap? {
    if (packageName == null) return null

    // Return the cached bitmap immediately if we have one.
    appIconCache[packageName]?.let { return it }

    // Decode the app icon off the main thread; a placeholder is rendered until it arrives.
    val context = LocalContext.current
    var icon by remember(packageName) { mutableStateOf(appIconCache[packageName]) }
    LaunchedEffect(packageName) {
        if (icon != null) return@LaunchedEffect
        val decoded = withContext(Dispatchers.IO) {
            try {
                val pm = context.packageManager
                val drawable = pm.getApplicationIcon(packageName)
                drawableToImageBitmap(drawable)
            } catch (e: Exception) {
                null
            }
        }
        if (decoded != null) {
            appIconCache[packageName] = decoded
            icon = decoded
        }
    }
    return icon
}

fun drawableToImageBitmap(drawable: Drawable): ImageBitmap? {
    return try {
        if (drawable is BitmapDrawable && drawable.bitmap != null) {
            drawable.bitmap.asImageBitmap()
        } else {
            val width = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 96
            val height = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 96
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            bitmap.asImageBitmap()
        }
    } catch (e: Exception) {
        null
    }
}

@Composable
fun DockReorderTile(
    focusRequester: FocusRequester? = null,
    isReorderMode: Boolean,
    isDialogActive: Boolean = false,
    activeDialogEntityId: String? = null,
    onDirectionToRecent: (() -> Unit)? = null,
    directionToRecentKeyCode: Int = KeyEvent.KEYCODE_DPAD_UP,
    onFocused: () -> Unit,
    onUnfocused: () -> Unit,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val isOtherItemActive = activeDialogEntityId != null

    val tileAlpha by animateFloatAsState(
        targetValue = if (isOtherItemActive) 0.22f else 1.0f,
        animationSpec = tween(durationMillis = 150),
        label = "reorder_tile_alpha"
    )

    LaunchedEffect(isFocused) {
        if (isFocused) onFocused() else onUnfocused()
    }

    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.12f else 1.0f,
        animationSpec = tween(durationMillis = 80, easing = FastOutSlowInEasing),
        label = "reorder_scale"
    )

    val palette = LocalThemePalette.current
    val offColor = palette.getOffBackgroundColor()
    val isOffDark = DomainColorPalette.isColorDark(offColor)
    val focusedOffColor = remember(offColor, isOffDark) {
        if (isOffDark) {
            Color(
                red = (offColor.red + 0.08f).coerceAtMost(1f),
                green = (offColor.green + 0.08f).coerceAtMost(1f),
                blue = (offColor.blue + 0.12f).coerceAtMost(1f),
                alpha = 1f
            )
        } else {
            Color(
                red = (offColor.red - 0.10f).coerceAtLeast(0f),
                green = (offColor.green - 0.10f).coerceAtLeast(0f),
                blue = (offColor.blue - 0.10f).coerceAtLeast(0f),
                alpha = 1f
            )
        }
    }

    Box(
        modifier = Modifier
            .size(62.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = tileAlpha
            }
            .blur(if (isOtherItemActive) 6.dp else 0.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (isReorderMode) Color(0xFFD97706)
                else if (isFocused) focusedOffColor
                else offColor
            )
            .border(
                width = if (isReorderMode) 3.dp else if (isFocused) 2.6.dp else 0.dp,
                color = if (isReorderMode) HA_Yellow_On else if (isFocused) Color.White else Color.Transparent,
                shape = RoundedCornerShape(16.dp)
            )
            .onPreviewKeyEvent { keyEvent ->
                if (keyEvent.type == KeyEventType.KeyDown) {
                    val native = keyEvent.nativeKeyEvent
                    val isCenter = native.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                            native.keyCode == KeyEvent.KEYCODE_ENTER ||
                            native.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
                    if (isCenter) {
                        onClick()
                        return@onPreviewKeyEvent true
                    }
                    if (!isReorderMode && onDirectionToRecent != null && native.keyCode == directionToRecentKeyCode) {
                        onDirectionToRecent()
                        return@onPreviewKeyEvent true
                    }
                }
                false
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
            imageVector = Icons.Default.SwapHoriz,
            contentDescription = "Reorder",
            tint = if (isReorderMode || isFocused) (if (isOffDark) Color.White else Color(0xFF0F172A)) else (if (isOffDark) Color(0xFF94A3B8) else Color(0xFF475569)),
            modifier = Modifier.size(28.dp)
        )
    }
}

@Composable
fun DockSettingsTile(
    focusRequester: FocusRequester,
    isVertical: Boolean,
    isDialogActive: Boolean = false,
    activeDialogEntityId: String? = null,
    onDirectionToRecent: (() -> Unit)? = null,
    directionToRecentKeyCode: Int = KeyEvent.KEYCODE_DPAD_UP,
    onWrapToFirst: () -> Unit,
    onFocused: () -> Unit,
    onUnfocused: () -> Unit,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val isOtherItemActive = activeDialogEntityId != null

    val tileAlpha by animateFloatAsState(
        targetValue = if (isOtherItemActive) 0.22f else 1.0f,
        animationSpec = tween(durationMillis = 150),
        label = "settings_tile_alpha"
    )

    LaunchedEffect(isFocused) {
        if (isFocused) onFocused() else onUnfocused()
    }

    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.12f else 1.0f,
        animationSpec = tween(durationMillis = 80, easing = FastOutSlowInEasing),
        label = "settings_scale"
    )

    val palette = LocalThemePalette.current
    val offColor = palette.getOffBackgroundColor()
    val isOffDark = DomainColorPalette.isColorDark(offColor)
    val focusedOffColor = remember(offColor, isOffDark) {
        if (isOffDark) {
            Color(
                red = (offColor.red + 0.08f).coerceAtMost(1f),
                green = (offColor.green + 0.08f).coerceAtMost(1f),
                blue = (offColor.blue + 0.12f).coerceAtMost(1f),
                alpha = 1f
            )
        } else {
            Color(
                red = (offColor.red - 0.10f).coerceAtLeast(0f),
                green = (offColor.green - 0.10f).coerceAtLeast(0f),
                blue = (offColor.blue - 0.10f).coerceAtLeast(0f),
                alpha = 1f
            )
        }
    }

    Box(
        modifier = Modifier
            .size(62.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = tileAlpha
            }
            .blur(if (isOtherItemActive) 6.dp else 0.dp)
            .focusRequester(focusRequester)
            .clip(RoundedCornerShape(16.dp))
            .background(if (isFocused) focusedOffColor else offColor)
            .border(
                width = if (isFocused) 2.6.dp else 0.dp,
                color = if (isFocused) Color.White else Color.Transparent,
                shape = RoundedCornerShape(16.dp)
            )
            .onPreviewKeyEvent { keyEvent ->
                if (keyEvent.type == KeyEventType.KeyDown) {
                    val native = keyEvent.nativeKeyEvent
                    val isCenter = native.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                            native.keyCode == KeyEvent.KEYCODE_ENTER ||
                            native.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
                    if (isCenter) {
                        onClick()
                        return@onPreviewKeyEvent true
                    }
                    if (onDirectionToRecent != null && native.keyCode == directionToRecentKeyCode) {
                        onDirectionToRecent()
                        return@onPreviewKeyEvent true
                    }
                    if (!isVertical && native.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                        onWrapToFirst()
                        return@onPreviewKeyEvent true
                    } else if (isVertical && native.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                        onWrapToFirst()
                        return@onPreviewKeyEvent true
                    }
                }
                false
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
            tint = if (isFocused) (if (isOffDark) Color.White else Color(0xFF0F172A)) else (if (isOffDark) Color(0xFF94A3B8) else Color(0xFF475569)),
            modifier = Modifier.size(28.dp)
        )
    }
}

private fun getActiveColor(domain: String, palette: DomainColorPalette = DomainColorPalette.CLASSIC): Color {
    return palette.getColorForDomain(domain)
}

private val iconVectorCache = java.util.concurrent.ConcurrentHashMap<String, ImageVector>()

fun resolveDockIcon(customIcon: String?, haIcon: String?, domain: String): ImageVector {
    val cacheKey = "${customIcon ?: ""}|${haIcon ?: ""}|$domain"
    return iconVectorCache.getOrPut(cacheKey) {
        computeDockIcon(customIcon, haIcon, domain)
    }
}

private fun computeDockIcon(customIcon: String?, haIcon: String?, domain: String): ImageVector {
    val key = (customIcon ?: haIcon ?: "").trim().lowercase()
        .removePrefix("mdi:")
        .replace("-", "_")

    return when {
        // Lighting
        key.contains("nightlight") || key.contains("moon") -> Icons.Default.Nightlight
        key.contains("strip") || key.contains("fluorescent") -> Icons.Default.Fluorescent
        key.contains("spotlight") || key.contains("flash") -> Icons.Default.FlashOn
        key.contains("ceiling") || key.contains("chandelier") -> Icons.Default.Light
        key.contains("lamp") || key.contains("incandescent") -> Icons.Default.WbIncandescent
        key.contains("sunny") || key.contains("sun") || key.contains("bright") -> Icons.Default.WbSunny
        key.contains("lightbulb") || key.contains("bulb") || key.contains("light") || key.contains("led") -> Icons.Default.Lightbulb

        // Climate & Air
        key == "ac" || key == "ac_unit" || key.contains("air_condition") || key.contains("aircondition") ||
        key.contains("conditioning") || key.contains("snowflake") || key.contains("cold") || key.contains("frost") -> Icons.Default.AcUnit
        key.contains("fan") || key.contains("vent") || key.contains("blower") -> Icons.Default.Air
        key.contains("fireplace") -> Icons.Default.Fireplace
        key.contains("heater") || key.contains("radiator") || key.contains("warm") || key.contains("flame") || key.contains("fire") || key.contains("candle") -> Icons.Default.Whatshot
        key.contains("thermostat") || key.contains("temp") || key.contains("hvac") -> Icons.Default.Thermostat

        // Security & Access (check doorbell and locks before door/window)
        key.contains("doorbell") || key.contains("bell") -> Icons.Default.Doorbell
        key.contains("lock_open") || key.contains("unlock") -> Icons.Default.LockOpen
        key.contains("lock") -> Icons.Default.Lock
        key == "key" || key.contains("key_") || key.contains("_key") || key.contains("passkey") -> Icons.Default.Key
        key.contains("alarm") || key.contains("siren") -> Icons.Default.Alarm
        key.contains("shield") || key.contains("protect") -> Icons.Default.Shield
        key.contains("security") -> Icons.Default.Security
        key.contains("camera") || key.contains("cctv") || key.contains("video") || key.contains("cam") -> Icons.Default.Videocam

        // Doors, Windows & Covers
        key.contains("door_sliding") || key.contains("sliding") -> Icons.Default.DoorSliding
        key == "door" || key == "door_front" || key.contains("front_door") || (key.contains("door") && !key.contains("outdoor")) -> Icons.Default.DoorFront
        key.contains("garage") -> Icons.Default.Garage
        key.contains("curtain") -> Icons.Default.Curtains
        key.contains("blind") -> Icons.Default.Blinds
        key.contains("roller") || key.contains("shade") -> Icons.Default.RollerShades
        key.contains("window") -> Icons.Default.Window

        // Vehicles
        key.contains("electric_car") || key.contains("ev_car") -> Icons.Default.ElectricCar
        key.contains("ev_station") || key.contains("charger") || key.contains("charging") -> Icons.Default.EvStation
        key.contains("car") || key.contains("auto") || key.contains("vehicle") -> Icons.Default.DirectionsCar
        key.contains("bike") || key.contains("bicycle") -> Icons.Default.PedalBike
        key.contains("motorcycle") || key.contains("scooter") -> Icons.Default.TwoWheeler

        // Power & Energy
        key == "socket" || key.contains("socket") || key.contains("outlet") -> Icons.Default.Outlet
        key == "plug" || key.contains("plug") -> Icons.Default.Power
        key.contains("bolt") || key.contains("energy") || key.contains("voltage") || key.contains("current") || (key.contains("electric") && !key.contains("car")) -> Icons.Default.Bolt
        key.contains("battery") -> Icons.Default.BatteryChargingFull
        key.contains("solar") -> Icons.Default.SolarPower
        key.contains("power") || key.contains("switch") || key.contains("button") -> Icons.Default.PowerSettingsNew

        // Appliances
        key.contains("vacuum") || key.contains("roomba") || key.contains("robot") -> Icons.Default.SmartToy
        key.contains("laundry") || key.contains("washer") || key.contains("washing") -> Icons.Default.LocalLaundryService
        key.contains("coffee") || key.contains("cafe") || key.contains("espresso") -> Icons.Default.CoffeeMaker
        key.contains("microwave") -> Icons.Default.Microwave
        key.contains("blender") -> Icons.Default.Blender
        key == "iron" || key.contains("iron_") || key.contains("_iron") -> Icons.Default.Iron
        key.contains("grill") || key.contains("bbq") || key.contains("barbecue") -> Icons.Default.OutdoorGrill
        key.contains("clean") -> Icons.Default.CleaningServices

        // Media & Audio (check tablet before table, headphone before phone)
        key.contains("headphone") -> Icons.Default.Headphones
        key.contains("tablet") || key.contains("ipad") -> Icons.Default.Tablet
        key.contains("speaker_group") || key.contains("speakers") -> Icons.Default.SpeakerGroup
        key.contains("speaker") || key.contains("sound") || key.contains("volume") -> Icons.Default.Speaker
        key.contains("live_tv") || key.contains("livetv") -> Icons.Default.LiveTv
        key.contains("tv") || key.contains("television") || key.contains("screen") || key.contains("display") -> Icons.Default.Tv
        key.contains("monitor") || key.contains("desktop") || key.contains("computer") -> Icons.Default.DesktopWindows
        key.contains("laptop") -> Icons.Default.Laptop
        key.contains("smartphone") || key.contains("mobile") || (key.contains("phone") && !key.contains("headphone")) -> Icons.Default.Smartphone
        key.contains("music") || key.contains("audio") || key.contains("song") -> Icons.Default.MusicNote
        key.contains("radio") -> Icons.Default.Radio
        key == "mic" || key.contains("microphone") -> Icons.Default.Mic
        key.contains("game") || key.contains("gamepad") || key.contains("controller") || key.contains("playstation") || key.contains("xbox") -> Icons.Default.SportsEsports

        // Rooms & Furniture (check hot_tub before tub/bath)
        key.contains("hot_tub") || key.contains("jacuzzi") -> Icons.Default.HotTub
        key.contains("sofa") || key.contains("couch") || key.contains("living") -> Icons.Default.Weekend
        key.contains("bed") || key.contains("sleep") || key.contains("bedroom") -> Icons.Default.Bed
        key.contains("chair") -> Icons.Default.Chair
        key.contains("desk") || key.contains("office") -> Icons.Default.Desk
        key == "table" || key.contains("dining") || (key.contains("table") && !key.contains("tablet")) -> Icons.Default.TableRestaurant
        key.contains("kitchen") -> Icons.Default.Kitchen
        key.contains("restaurant") -> Icons.Default.Restaurant
        key.contains("bath") || key.contains("tub") -> Icons.Default.Bathtub
        key.contains("shower") -> Icons.Default.Shower

        // Outdoor & Garden
        key.contains("balcony") || key.contains("terrace") -> Icons.Default.Balcony
        key.contains("yard") || key.contains("garden") || key.contains("plant") -> Icons.Default.Yard
        key.contains("pool") || key.contains("swimming") -> Icons.Default.Pool
        key.contains("deck") || key.contains("patio") -> Icons.Default.Deck
        key.contains("fence") || key.contains("gate") -> Icons.Default.Fence

        // Sensors & Automation
        key.contains("water") || key.contains("leak") || key.contains("moisture") || key.contains("drop") -> Icons.Default.WaterDrop
        key.contains("co2") || key.contains("smoke") || key.contains("gas") -> Icons.Default.Co2
        key.contains("timer") || key.contains("clock") -> Icons.Default.Timer
        key.contains("palette") || key.contains("scene") || key.contains("color") -> Icons.Default.Palette
        key.contains("play") || key.contains("script") -> Icons.Default.PlayArrow
        key.contains("wifi") || key.contains("router") -> Icons.Default.Wifi
        key.contains("sensor") || key.contains("motion") || key.contains("presence") -> Icons.Default.Sensors
        key.contains("pet") || key.contains("dog") || key.contains("cat") -> Icons.Default.Pets

        // Domain Fallback
        else -> when (domain) {
            "light" -> Icons.Default.Lightbulb
            "switch", "input_boolean" -> Icons.Default.PowerSettingsNew
            "scene" -> Icons.Default.Palette
            "script" -> Icons.Default.PlayArrow
            "climate" -> Icons.Default.Thermostat
            "media_player" -> Icons.Default.Tv
            "cover" -> Icons.Default.Curtains
            "fan" -> Icons.Default.Air
            "lock" -> Icons.Default.Lock
            "vacuum" -> Icons.Default.SmartToy
            "sensor", "binary_sensor" -> Icons.Default.Sensors
            else -> Icons.Default.DeviceHub
        }
    }
}

@Composable
fun RecentAppsSatellite(
    recentApps: List<String>,
    focusRequesters: List<FocusRequester>,
    isVertical: Boolean,
    isDialogActive: Boolean = false,
    onLaunchApp: (String) -> Unit,
    onExitToDock: () -> Unit,
    modifier: Modifier = Modifier,
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
