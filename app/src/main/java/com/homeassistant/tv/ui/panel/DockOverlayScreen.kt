package com.homeassistant.tv.ui.panel

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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.homeassistant.tv.data.models.HAEntityState
import com.homeassistant.tv.ui.theme.*
import com.homeassistant.tv.viewmodel.DockItem
import com.homeassistant.tv.viewmodel.PanelViewModel
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

    fun update(newName: String, newState: String) {
        name = newName
        state = newState
    }

    fun clear(oldName: String) {
        if (name == oldName) {
            name = null
            state = null
        }
    }
}

@Composable
fun DockOverlayScreen(
    viewModel: PanelViewModel,
    layoutPosition: String, // DOCK_BOTTOM, DOCK_LEFT, DOCK_RIGHT
    onOpenSettings: () -> Unit
) {
    val displayEntities by viewModel.displayEntities.collectAsState()
    val activeDialogEntity by viewModel.activeDialogEntity.collectAsState()
    val isConfigured by viewModel.isConfigured.collectAsState()
    val isReorderMode by viewModel.isReorderMode.collectAsState()
    val selectedReorderEntityId by viewModel.selectedReorderEntityId.collectAsState()

    val badgeState = remember { FocusBadgeState() }

    // Completely transparent root overlay (TV display shows behind dock)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Transparent)
    ) {
        when (layoutPosition) {
            "DOCK_LEFT" -> {
                Row(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 36.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    DockListVertical(
                        items = displayEntities,
                        isConfigured = isConfigured,
                        isReorderMode = isReorderMode,
                        selectedReorderId = selectedReorderEntityId,
                        onFocused = { name, state -> badgeState.update(name, state) },
                        onUnfocused = { name -> badgeState.clear(name) },
                        onItemClick = { item ->
                            if (item.isApp && item.packageName != null) {
                                viewModel.launchApp(item.packageName)
                            } else if (item.entity != null) {
                                viewModel.toggleEntity(item.id)
                            }
                        },
                        onItemLongPress = { item ->
                            item.entity?.let { viewModel.openEntityDialog(it) }
                        },
                        onSelectForReorder = { id -> viewModel.selectEntityForReorder(id) },
                        onMove = { id, dir -> viewModel.moveEntity(id, dir) },
                        onToggleReorderMode = { viewModel.toggleReorderMode() },
                        onOpenSettings = onOpenSettings
                    )

                    Spacer(modifier = Modifier.width(16.dp))

                    FloatingLabelBadge(
                        badgeState = badgeState,
                        isReorderMode = isReorderMode,
                        selectedEntityId = selectedReorderEntityId
                    )
                }
            }

            "DOCK_RIGHT" -> {
                Row(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 36.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FloatingLabelBadge(
                        badgeState = badgeState,
                        isReorderMode = isReorderMode,
                        selectedEntityId = selectedReorderEntityId
                    )

                    Spacer(modifier = Modifier.width(16.dp))

                    DockListVertical(
                        items = displayEntities,
                        isConfigured = isConfigured,
                        isReorderMode = isReorderMode,
                        selectedReorderId = selectedReorderEntityId,
                        onFocused = { name, state -> badgeState.update(name, state) },
                        onUnfocused = { name -> badgeState.clear(name) },
                        onItemClick = { item ->
                            if (item.isApp && item.packageName != null) {
                                viewModel.launchApp(item.packageName)
                            } else if (item.entity != null) {
                                viewModel.toggleEntity(item.id)
                            }
                        },
                        onItemLongPress = { item ->
                            item.entity?.let { viewModel.openEntityDialog(it) }
                        },
                        onSelectForReorder = { id -> viewModel.selectEntityForReorder(id) },
                        onMove = { id, dir -> viewModel.moveEntity(id, dir) },
                        onToggleReorderMode = { viewModel.toggleReorderMode() },
                        onOpenSettings = onOpenSettings
                    )
                }
            }

            else -> { // DOCK_BOTTOM (Default)
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 36.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    FloatingLabelBadge(
                        badgeState = badgeState,
                        isReorderMode = isReorderMode,
                        selectedEntityId = selectedReorderEntityId
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    DockListHorizontal(
                        items = displayEntities,
                        isConfigured = isConfigured,
                        isReorderMode = isReorderMode,
                        selectedReorderId = selectedReorderEntityId,
                        onFocused = { name, state -> badgeState.update(name, state) },
                        onUnfocused = { name -> badgeState.clear(name) },
                        onItemClick = { item ->
                            if (item.isApp && item.packageName != null) {
                                viewModel.launchApp(item.packageName)
                            } else if (item.entity != null) {
                                viewModel.toggleEntity(item.id)
                            }
                        },
                        onItemLongPress = { item ->
                            item.entity?.let { viewModel.openEntityDialog(it) }
                        },
                        onSelectForReorder = { id -> viewModel.selectEntityForReorder(id) },
                        onMove = { id, dir -> viewModel.moveEntity(id, dir) },
                        onToggleReorderMode = { viewModel.toggleReorderMode() },
                        onOpenSettings = onOpenSettings
                    )
                }
            }
        }

        // Active Dialogs (Brightness / Climate)
        activeDialogEntity?.let { entity ->
            when (entity.domain) {
                "light" -> {
                    BrightnessDialog(
                        entity = entity,
                        onSetBrightness = { viewModel.setBrightness(entity.entityId, it) },
                        onDismiss = { viewModel.closeEntityDialog() }
                    )
                }
                "climate" -> {
                    ClimateDialog(
                        entity = entity,
                        onSetTemperature = { viewModel.setTargetTemperature(entity.entityId, it) },
                        onSetHvacMode = { viewModel.setHvacMode(entity.entityId, it) },
                        onDismiss = { viewModel.closeEntityDialog() }
                    )
                }
                else -> {}
            }
        }
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

    AnimatedVisibility(
        visible = name != null,
        enter = fadeIn(tween(60)),
        exit = fadeOut(tween(40))
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF0F172A))
                .border(
                    width = 1.2.dp,
                    color = if (isReorderMode) HA_Yellow_On else Color(0xFF475569),
                    shape = RoundedCornerShape(14.dp)
                )
                .padding(horizontal = 18.dp, vertical = 8.dp)
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
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = name ?: "",
                        fontSize = 15.sp,
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
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    if (!state.isNullOrBlank()) {
                        Text(
                            text = "•  ${state.uppercase()}",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = HA_Blue
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun DockListHorizontal(
    items: List<DockItem>,
    isConfigured: Boolean,
    isReorderMode: Boolean,
    selectedReorderId: String?,
    onFocused: (String, String) -> Unit,
    onUnfocused: (String) -> Unit,
    onItemClick: (DockItem) -> Unit,
    onItemLongPress: (DockItem) -> Unit,
    onSelectForReorder: (String) -> Unit,
    onMove: (String, Int) -> Unit,
    onToggleReorderMode: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val firstFocusRequester = remember { FocusRequester() }
    val settingsFocusRequester = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    if (!isConfigured && items.isEmpty()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xEE161E2E))
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Text(
                text = "Setup required",
                color = TV_Text_Secondary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
            FocusableButton(
                text = "Settings",
                icon = Icons.Default.Settings,
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
                    index = index,
                    isReorderMode = isReorderMode,
                    isSelectedForReorder = selectedReorderId == item.id,
                    isVertical = false,
                    customFocusRequester = if (index == 0) firstFocusRequester else null,
                    onWrapToLast = {
                        scope.launch {
                            try {
                                listState.animateScrollToItem((items.size + 1).coerceAtLeast(0))
                            } catch (_: Exception) {}
                            try {
                                settingsFocusRequester.requestFocus()
                            } catch (_: Exception) {}
                        }
                    },
                    onFocused = onFocused,
                    onUnfocused = onUnfocused,
                    onClick = { onItemClick(item) },
                    onLongPress = if (!item.isApp && item.entity != null &&
                        (item.entity.domain == "light" || item.entity.domain == "climate")
                    ) {
                        { onItemLongPress(item) }
                    } else null,
                    onSelectForReorder = { onSelectForReorder(item.id) },
                    onMove = { dir -> onMove(item.id, dir) }
                )
            }

            // Reorder Mode Toggle Tile
            item(key = "dock_reorder_tile") {
                DockReorderTile(
                    isReorderMode = isReorderMode,
                    onFocused = { onFocused("Reorder Mode", if (isReorderMode) "Active (Click to Exit)" else "Click to Enter") },
                    onUnfocused = { onUnfocused("Reorder Mode") },
                    onClick = onToggleReorderMode
                )
            }

            // Settings Tile at the end
            item(key = "dock_settings_tile") {
                DockSettingsTile(
                    focusRequester = settingsFocusRequester,
                    isVertical = false,
                    onWrapToFirst = {
                        scope.launch {
                            try {
                                listState.animateScrollToItem(0)
                            } catch (_: Exception) {}
                            try {
                                firstFocusRequester.requestFocus()
                            } catch (_: Exception) {}
                        }
                    },
                    onFocused = { onFocused("Settings", "Configure") },
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
    isConfigured: Boolean,
    isReorderMode: Boolean,
    selectedReorderId: String?,
    onFocused: (String, String) -> Unit,
    onUnfocused: (String) -> Unit,
    onItemClick: (DockItem) -> Unit,
    onItemLongPress: (DockItem) -> Unit,
    onSelectForReorder: (String) -> Unit,
    onMove: (String, Int) -> Unit,
    onToggleReorderMode: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val firstFocusRequester = remember { FocusRequester() }
    val settingsFocusRequester = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    if (!isConfigured && items.isEmpty()) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xEE161E2E))
                .padding(16.dp)
        ) {
            Text(
                text = "Setup required",
                color = TV_Text_Secondary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
            FocusableButton(
                text = "Settings",
                icon = Icons.Default.Settings,
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
                    index = index,
                    isReorderMode = isReorderMode,
                    isSelectedForReorder = selectedReorderId == item.id,
                    isVertical = true,
                    customFocusRequester = if (index == 0) firstFocusRequester else null,
                    onWrapToLast = {
                        scope.launch {
                            try {
                                listState.animateScrollToItem((items.size + 1).coerceAtLeast(0))
                            } catch (_: Exception) {}
                            try {
                                settingsFocusRequester.requestFocus()
                            } catch (_: Exception) {}
                        }
                    },
                    onFocused = onFocused,
                    onUnfocused = onUnfocused,
                    onClick = { onItemClick(item) },
                    onLongPress = if (!item.isApp && item.entity != null &&
                        (item.entity.domain == "light" || item.entity.domain == "climate")
                    ) {
                        { onItemLongPress(item) }
                    } else null,
                    onSelectForReorder = { onSelectForReorder(item.id) },
                    onMove = { dir -> onMove(item.id, dir) }
                )
            }

            item(key = "dock_vertical_reorder") {
                DockReorderTile(
                    isReorderMode = isReorderMode,
                    onFocused = { onFocused("Reorder Mode", if (isReorderMode) "Active (Click to Exit)" else "Click to Enter") },
                    onUnfocused = { onUnfocused("Reorder Mode") },
                    onClick = onToggleReorderMode
                )
            }

            item(key = "dock_vertical_settings") {
                DockSettingsTile(
                    focusRequester = settingsFocusRequester,
                    isVertical = true,
                    onWrapToFirst = {
                        scope.launch {
                            try {
                                listState.animateScrollToItem(0)
                            } catch (_: Exception) {}
                            try {
                                firstFocusRequester.requestFocus()
                            } catch (_: Exception) {}
                        }
                    },
                    onFocused = { onFocused("Settings", "Configure") },
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
    index: Int,
    isReorderMode: Boolean,
    isSelectedForReorder: Boolean,
    isVertical: Boolean,
    customFocusRequester: FocusRequester?,
    onWrapToLast: () -> Unit,
    onFocused: (String, String) -> Unit,
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

    // Long-press state (hold OK to open the control dialog).
    val scope = rememberCoroutineScope()
    var longPressJob by remember(item.id) { mutableStateOf<Job?>(null) }
    var suppressClickAfterLongPress by remember(item.id) { mutableStateOf(false) }

    val isOn = if (isApp) true else (entity?.isOn == true)
    val isUnavailable = if (isApp) false else (entity?.isUnavailable == true)
    val appIconBitmap = if (isApp) rememberAppIconBitmap(item.packageName) else null

    LaunchedEffect(isFocused) {
        if (isFocused) {
            val stateText = if (isApp) {
                "Open App"
            } else {
                when {
                    isUnavailable -> "Offline"
                    entity?.domain == "light" && isOn && entity.brightness != null -> "${((entity.brightness!! / 255f) * 100).toInt()}%"
                    entity?.domain == "climate" -> "${entity.currentTemperature ?: entity.targetTemperature ?: 0f}°"
                    entity?.domain == "sensor" -> "${entity.state} ${entity.unitOfMeasurement ?: ""}".trim()
                    else -> if (isOn) "ON" else "OFF"
                }
            }
            onFocused(name, stateText)
        } else {
            onUnfocused(name)
        }
    }

    val scale by animateFloatAsState(
        targetValue = if (isSelectedForReorder) 1.20f else if (isFocused) 1.12f else 1.0f,
        animationSpec = tween(durationMillis = 80, easing = FastOutSlowInEasing),
        label = "dock_scale"
    )

    val backgroundColor by animateColorAsState(
        targetValue = when {
            isUnavailable -> Color(0xFF1E293B)
            isSelectedForReorder -> Color(0xFFD97706)
            isApp && isFocused -> Color(0xFF2C3852)
            isApp -> Color(0xFF1E293B)
            isFocused && isOn -> getActiveColor(entity?.domain ?: "")
            isFocused && !isOn -> Color(0xFF2C3852)
            isOn -> getActiveColor(entity?.domain ?: "")
            else -> Color(0xFF1E293B)
        },
        animationSpec = tween(durationMillis = 80),
        label = "tile_bg"
    )

    val borderColor by animateColorAsState(
        targetValue = when {
            isSelectedForReorder -> HA_Yellow_On
            isFocused -> Color.White
            isOn -> Color(0xFF94A3B8)
            else -> Color(0xFF475569)
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
            }
            .then(modifierWithFocus)
            .clip(RoundedCornerShape(16.dp))
            .background(backgroundColor)
            .border(
                width = if (isSelectedForReorder) 3.5.dp else if (isFocused) 2.6.dp else 1.2.dp,
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
            .focusable(interactionSource = interactionSource)
            .clickable(
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
            Icon(
                imageVector = icon,
                contentDescription = name,
                tint = if (isOn || isFocused || isSelectedForReorder) Color.White else Color(0xFF94A3B8),
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
    isReorderMode: Boolean,
    onFocused: () -> Unit,
    onUnfocused: () -> Unit,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    LaunchedEffect(isFocused) {
        if (isFocused) onFocused() else onUnfocused()
    }

    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.12f else 1.0f,
        animationSpec = tween(durationMillis = 80, easing = FastOutSlowInEasing),
        label = "reorder_scale"
    )

    Box(
        modifier = Modifier
            .size(62.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (isReorderMode) Color(0xFFD97706)
                else if (isFocused) Color(0xFF2C3852)
                else Color(0xFF1E293B)
            )
            .border(
                width = if (isReorderMode) 3.dp else if (isFocused) 2.6.dp else 1.2.dp,
                color = if (isReorderMode) HA_Yellow_On else if (isFocused) Color.White else Color(0xFF475569),
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
                }
                false
            }
            .focusable(interactionSource = interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.SwapHoriz,
            contentDescription = "Reorder",
            tint = if (isReorderMode || isFocused) Color.White else Color(0xFF94A3B8),
            modifier = Modifier.size(28.dp)
        )
    }
}

@Composable
fun DockSettingsTile(
    focusRequester: FocusRequester,
    isVertical: Boolean,
    onWrapToFirst: () -> Unit,
    onFocused: () -> Unit,
    onUnfocused: () -> Unit,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    LaunchedEffect(isFocused) {
        if (isFocused) onFocused() else onUnfocused()
    }

    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.12f else 1.0f,
        animationSpec = tween(durationMillis = 80, easing = FastOutSlowInEasing),
        label = "settings_scale"
    )

    Box(
        modifier = Modifier
            .size(62.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .focusRequester(focusRequester)
            .clip(RoundedCornerShape(16.dp))
            .background(if (isFocused) Color(0xFF2C3852) else Color(0xFF1E293B))
            .border(
                width = if (isFocused) 2.6.dp else 1.2.dp,
                color = if (isFocused) Color.White else Color(0xFF475569),
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
            .focusable(interactionSource = interactionSource)
            .clickable(
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
            modifier = Modifier.size(28.dp)
        )
    }
}

private fun getActiveColor(domain: String): Color {
    return when (domain) {
        "light" -> Color(0xFFF59E0B) // Warm Amber
        "switch", "input_boolean" -> Color(0xFF0284C7) // Sky Blue
        "climate" -> Color(0xFFEA580C) // Orange
        "scene", "script" -> Color(0xFF9333EA) // Purple
        "media_player" -> Color(0xFF0D9488) // Teal
        "cover" -> Color(0xFF059669) // Emerald
        "fan" -> Color(0xFF0891B2) // Cyan
        "vacuum" -> Color(0xFF78716C) // Stone
        else -> Color(0xFF0284C7)
    }
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
        // Lights (specific icon names must be checked before the broad "light" match,
        // otherwise "highlight"/"nightlight" always fell through to the Lightbulb icon)
        key.contains("nightlight") || key.contains("moon") -> Icons.Default.Nightlight
        key.contains("led") || key.contains("strip") || key.contains("highlight") || key.contains("spotlight") -> Icons.Default.Highlight
        key.contains("sunny") || key.contains("sun") || key.contains("bright") -> Icons.Default.WbSunny
        key.contains("lightbulb") || key.contains("lamp") || key.contains("bulb") || key.contains("light") -> Icons.Default.Lightbulb
        key.contains("candle") || key.contains("fire") || key.contains("flame") -> Icons.Default.Whatshot

        // Media / Screens
        key.contains("tv") || key.contains("television") || key.contains("screen") || key.contains("display") -> Icons.Default.Tv
        key.contains("monitor") || key.contains("desktop") || key.contains("computer") -> Icons.Default.DesktopWindows
        key.contains("laptop") -> Icons.Default.Laptop
        key.contains("phone") || key.contains("mobile") -> Icons.Default.Smartphone

        // Audio
        key.contains("speaker") || key.contains("sound") || key.contains("volume") -> Icons.Default.Speaker
        key.contains("music") || key.contains("audio") -> Icons.Default.MusicNote
        key.contains("headphone") -> Icons.Default.Headphones

        // Climate / HVAC (no bare "ac" substring - it matched unrelated words like "back"/"package")
        key.contains("fan") -> Icons.Default.Air
        key.contains("air_condition") || key.contains("aircondition") || key.contains("conditioning") ||
        key.contains("ac_unit") || key.contains("snowflake") || key.contains("cold") || key.contains("frost") -> Icons.Default.AcUnit
        key.contains("heater") || key.contains("radiator") || key.contains("warm") -> Icons.Default.LocalFireDepartment
        key.contains("thermostat") || key.contains("temp") -> Icons.Default.Thermostat

        // Furniture / Rooms
        key.contains("sofa") || key.contains("couch") || key.contains("living") -> Icons.Default.Weekend
        key.contains("bed") || key.contains("sleep") || key.contains("bedroom") -> Icons.Default.Bed
        key.contains("chair") -> Icons.Default.Chair
        key.contains("table") || key.contains("desk") -> Icons.Default.TableRestaurant
        key.contains("kitchen") || key.contains("restaurant") -> Icons.Default.Restaurant
        key.contains("bath") || key.contains("tub") -> Icons.Default.Bathtub

        // Doors, Windows & Curtains
        key.contains("curtain") || key.contains("blind") || key.contains("shade") -> Icons.Default.Curtains
        key.contains("window") -> Icons.Default.Window
        key.contains("door") -> Icons.Default.DoorFront
        key.contains("garage") -> Icons.Default.Garage

        // Security & Locks
        key.contains("lock_open") || key.contains("unlock") -> Icons.Default.LockOpen
        key.contains("lock") -> Icons.Default.Lock
        key.contains("shield") || key.contains("protect") -> Icons.Default.Shield
        key.contains("security") -> Icons.Default.Security
        key.contains("camera") || key.contains("cctv") || key.contains("video") -> Icons.Default.Videocam

        // Power & Switches
        key.contains("power") || key.contains("switch") || key.contains("button") -> Icons.Default.PowerSettingsNew
        key.contains("plug") || key.contains("socket") || key.contains("outlet") -> Icons.Default.Power

        // Cleaning
        key.contains("vacuum") || key.contains("clean") || key.contains("roomba") -> Icons.Default.CleaningServices

        // Appliances & Fun
        key.contains("coffee") || key.contains("cafe") || key.contains("kettle") -> Icons.Default.LocalCafe
        key.contains("game") || key.contains("gamepad") || key.contains("controller") || key.contains("playstation") || key.contains("xbox") -> Icons.Default.SportsEsports
        key.contains("car") || key.contains("auto") || key.contains("vehicle") -> Icons.Default.DirectionsCar
        key.contains("ev") || key.contains("electric_car") -> Icons.Default.ElectricCar

        // Automation / Sensors
        key.contains("palette") || key.contains("scene") -> Icons.Default.Palette
        key.contains("play") || key.contains("script") -> Icons.Default.PlayArrow
        key.contains("wifi") || key.contains("router") -> Icons.Default.Wifi
        key.contains("sensor") -> Icons.Default.Sensors

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
            "vacuum" -> Icons.Default.CleaningServices
            "sensor", "binary_sensor" -> Icons.Default.Sensors
            else -> Icons.Default.DeviceHub
        }
    }
}
