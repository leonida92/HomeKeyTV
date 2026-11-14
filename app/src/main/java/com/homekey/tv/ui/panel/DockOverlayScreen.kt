package com.homekey.tv.ui.panel

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.homekey.tv.data.local.DockConfig
import com.homekey.tv.data.models.LocalThemePalette
import com.homekey.tv.viewmodel.DockItem
import com.homekey.tv.viewmodel.PanelViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun DockOverlayScreen(
    viewModel: PanelViewModel,
    layoutPosition: String, // DOCK_BOTTOM, DOCK_LEFT, DOCK_RIGHT
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit = {}
) {
    val context = LocalContext.current
    val displayEntities by viewModel.displayEntities.collectAsState()
    val activeDialogEntity by viewModel.activeDialogEntity.collectAsState()
    val dockConfig by viewModel.dockConfig.collectAsState()
    val themeConfig by viewModel.themeConfig.collectAsState()
    val recentAppsConfig by viewModel.recentAppsConfig.collectAsState()
    val isReorderMode by viewModel.isReorderMode.collectAsState()
    val selectedReorderEntityId by viewModel.selectedReorderEntityId.collectAsState()
    val overlayOpenEpoch by viewModel.overlayOpenEpoch.collectAsState()

    val displayRecentApps = remember(recentAppsConfig) {
        recentAppsConfig.recentPackages.take(recentAppsConfig.maxCount)
    }
    val showRecentApps = recentAppsConfig.enabled && displayRecentApps.isNotEmpty() && !isReorderMode

    val recentAppsFocusRequesters = remember { List(5) { FocusRequester() } }
    val firstFocusRequester = remember { FocusRequester() }
    val settingsFocusRequester = remember { FocusRequester() }
    val reorderFocusRequester = remember { FocusRequester() }
    val itemFocusRequesters = remember(displayEntities) {
        displayEntities.associate {
            it.id to (if (displayEntities.firstOrNull()?.id == it.id) firstFocusRequester else FocusRequester())
        }
    }
    var lastFocusedItemId by remember { mutableStateOf<String?>(null) }

    val isMinimalActive = activeDialogEntity != null && dockConfig.popupStyle == DockConfig.POPUP_STYLE_MINIMAL
    val isCenteredActive = activeDialogEntity != null && !isMinimalActive
    val activeDialogEntityId = if (isMinimalActive) activeDialogEntity?.entityId else null
    val isDialogActive = activeDialogEntity != null

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

    val renderDockList: @Composable (
        items: List<DockItem>,
        isVertical: Boolean,
        directionToRecentKeyCode: Int,
        onDirectionToRecent: ((Int) -> Unit)?
    ) -> Unit = { items, isVertical, directionToRecentKeyCode, onDirectionToRecent ->
        DockList(
            isVertical = isVertical,
            items = items,
            isReorderMode = if (items.isEmpty()) false else isReorderMode,
            isDialogActive = if (items.isEmpty()) false else isDialogActive,
            activeDialogEntityId = if (items.isEmpty()) null else activeDialogEntityId,
            selectedReorderId = if (items.isEmpty()) null else selectedReorderEntityId,
            overlayOpenEpoch = overlayOpenEpoch,
            firstFocusRequester = firstFocusRequester,
            settingsFocusRequester = settingsFocusRequester,
            reorderFocusRequester = reorderFocusRequester,
            itemFocusRequesters = if (items.isEmpty()) emptyMap() else itemFocusRequesters,
            onDirectionToRecent = onDirectionToRecent,
            directionToRecentKeyCode = directionToRecentKeyCode,
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

    val renderRecentApps: @Composable (
        isVertical: Boolean,
        exitKeyCode: Int,
        showSettingsButton: Boolean,
        onExitToDock: () -> Unit,
        modifier: Modifier
    ) -> Unit = { isVertical, exitKeyCode, showSettingsButton, onExitToDock, modifier ->
        RecentAppsSatellite(
            recentApps = displayRecentApps,
            focusRequesters = recentAppsFocusRequesters,
            isVertical = isVertical,
            isDialogActive = if (isVertical) false else isDialogActive,
            onLaunchApp = { pkg ->
                viewModel.launchApp(pkg)
                onDismiss()
            },
            onExitToDock = onExitToDock,
            exitKeyCode = exitKeyCode,
            showSettingsButton = showSettingsButton,
            onOpenSettings = onOpenSettings,
            settingsFocusRequester = settingsFocusRequester,
            onFocused = { label ->
                badgeState.update(label, if (label == "Settings") "Configure" else "Open App", null)
            },
            onUnfocused = { label -> badgeState.clear(label) },
            modifier = modifier
        )
    }

    // Completely transparent root overlay (TV display shows behind dock)
    CompositionLocalProvider(LocalThemePalette provides themeConfig.activePalette) {
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
            if (layoutPosition == DockConfig.LAYOUT_LEFT || layoutPosition == DockConfig.LAYOUT_RIGHT) {
                val isLeft = layoutPosition == DockConfig.LAYOUT_LEFT
                val centerAlignment = if (isLeft) Alignment.CenterStart else Alignment.CenterEnd
                val directionToRecentKey = if (isLeft) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
                val exitRecentKey = if (isLeft) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(
                            start = if (isLeft) 36.dp else 0.dp,
                            end = if (isLeft) 0.dp else 36.dp
                        )
                ) {
                    // Floating label badge at top of the side dock area, starting from the recent apps column
                    if (activeDialogEntity == null) {
                        val edgeInset = if (displayEntities.isNotEmpty()) 100.dp else 0.dp
                        Box(
                            modifier = Modifier
                                .align(if (isLeft) Alignment.TopStart else Alignment.TopEnd)
                                .padding(
                                    start = if (isLeft) edgeInset else 0.dp,
                                    end = if (isLeft) 0.dp else edgeInset,
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
                            modifier = Modifier.align(centerAlignment),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (!isLeft && isMinimalActive) {
                                activeDialogEntity?.let { dialogEntity ->
                                    EntityControlDialog(
                                        entity = dialogEntity,
                                        isMinimal = true,
                                        isVertical = true,
                                        viewModel = viewModel,
                                        onDismiss = { viewModel.closeEntityDialog() }
                                    )
                                    Spacer(modifier = Modifier.width(16.dp))
                                }
                            }

                            renderDockList(
                                displayEntities,
                                true,
                                directionToRecentKey,
                                if (showRecentApps) handleDirectionToRecent else null
                            )

                            if (isLeft && isMinimalActive) {
                                activeDialogEntity?.let { dialogEntity ->
                                    Spacer(modifier = Modifier.width(16.dp))
                                    EntityControlDialog(
                                        entity = dialogEntity,
                                        isMinimal = true,
                                        isVertical = true,
                                        viewModel = viewModel,
                                        onDismiss = { viewModel.closeEntityDialog() }
                                    )
                                }
                            }
                        }

                        if (showRecentApps && activeDialogEntity == null) {
                            renderRecentApps(
                                true,
                                exitRecentKey,
                                false,
                                handleExitToDock,
                                Modifier
                                    .align(centerAlignment)
                                    .padding(
                                        start = if (isLeft) 92.dp else 0.dp,
                                        end = if (isLeft) 0.dp else 92.dp
                                    )
                            )
                        }
                    } else if (showRecentApps) {
                        // displayEntities.isEmpty() && showRecentApps: Show ONLY recent apps + circular settings button centered vertically
                        renderRecentApps(
                            true,
                            exitRecentKey,
                            true,
                            { /* No dock to exit to */ },
                            Modifier.align(centerAlignment)
                        )
                    } else {
                        // displayEntities.isEmpty() && !showRecentApps: Show "Configure from settings" centered vertically
                        Box(modifier = Modifier.align(centerAlignment)) {
                            renderDockList(emptyList(), true, directionToRecentKey, null)
                        }
                    }
                }
            } else { // DOCK_BOTTOM (Default)
                Box(modifier = Modifier.fillMaxSize()) {
                    if (displayEntities.isNotEmpty()) {
                        Column(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            val minimalDialogEntity = activeDialogEntity?.takeIf { isMinimalActive }
                            if (minimalDialogEntity != null) {
                                EntityControlDialog(
                                    entity = minimalDialogEntity,
                                    isMinimal = true,
                                    isVertical = false,
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

                            renderDockList(
                                displayEntities,
                                false,
                                KeyEvent.KEYCODE_DPAD_DOWN,
                                if (showRecentApps && !isDialogActive) handleDirectionToRecent else null
                            )

                            if (showRecentApps) {
                                Spacer(modifier = Modifier.height(4.dp))
                                renderRecentApps(
                                    false,
                                    KeyEvent.KEYCODE_DPAD_UP,
                                    false,
                                    handleExitToDock,
                                    Modifier.align(Alignment.CenterHorizontally)
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
                            renderRecentApps(
                                false,
                                KeyEvent.KEYCODE_DPAD_UP,
                                true,
                                { /* No dock to exit to */ },
                                Modifier.align(Alignment.CenterHorizontally)
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
                            renderDockList(emptyList(), false, KeyEvent.KEYCODE_DPAD_DOWN, null)
                        }
                    }
                }
            }

            // Active Centered Dialogs rendered directly as in-window modal overlay
            if (isCenteredActive) {
                activeDialogEntity?.let { dialogEntity ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.65f)),
                        contentAlignment = Alignment.Center
                    ) {
                        EntityControlDialog(
                            entity = dialogEntity,
                            isMinimal = false,
                            isVertical = false,
                            viewModel = viewModel,
                            onDismiss = { viewModel.closeEntityDialog() }
                        )
                    }
                }
            }
        }
    }
}
