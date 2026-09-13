package com.homekey.tv.ui.settings

import android.content.Intent
import android.graphics.Bitmap
import android.provider.Settings
import android.view.KeyEvent
import java.io.File
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import kotlinx.coroutines.delay
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ViewSidebar
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.homekey.tv.data.local.PreferencesManager
import com.homekey.tv.data.models.*
import com.homekey.tv.ui.panel.FocusableButton
import com.homekey.tv.ui.panel.FocusableIconButton
import com.homekey.tv.ui.theme.*
import com.homekey.tv.viewmodel.AdbSetupState
import com.homekey.tv.viewmodel.SettingsViewModel

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val connectionStatus by viewModel.connectionStatus.collectAsState()
    val setupUrl by viewModel.setupUrl.collectAsState()
    val qrBitmap by viewModel.qrCodeBitmap.collectAsState()
    val pairingPin by viewModel.pairingPin.collectAsState()
    val entityCount by viewModel.entityCount.collectAsState()
    val panelLayout by viewModel.panelLayout.collectAsState()
    val popupStyle by viewModel.popupStyle.collectAsState()
    val cameraCompactSize by viewModel.cameraCompactSize.collectAsState()
    val installedApps by viewModel.installedApps.collectAsState()
    val appIcons by viewModel.appIcons.collectAsState()
    val pinnedApps by viewModel.pinnedApps.collectAsState()
    val buttonRemaps by viewModel.buttonRemaps.collectAsState()
    val isAccessibilityEnabled by viewModel.isAccessibilityEnabled.collectAsState()
    val adbSetupState by viewModel.adbSetupState.collectAsState()
    val learnedKey by viewModel.learnedKey.collectAsState()
    val updateState by viewModel.updateState.collectAsState()
    val recentAppsEnabled by viewModel.recentAppsEnabled.collectAsState()
    val recentAppsCount by viewModel.recentAppsCount.collectAsState()
    val appVersion = viewModel.appVersion

    var selectedTab by remember { mutableIntStateOf(0) } // 0: Phone Setup, 1: Layout Position, 2: Button Remap, 3: Installed Apps, 4: Updates

    val tab0FocusRequester = remember { FocusRequester() }
    val tab1FocusRequester = remember { FocusRequester() }
    val tab2FocusRequester = remember { FocusRequester() }
    val tab3FocusRequester = remember { FocusRequester() }
    val tab4FocusRequester = remember { FocusRequester() }
    val updateActionButtonFocusRequester = remember { FocusRequester() }
    val firstDockChoiceFocusRequester = remember { FocusRequester() }
    val firstAppFocusRequester = remember { FocusRequester() }
    val recentAppsToggleFocusRequester = remember { FocusRequester() }
    var requestButtonRemapContentFocus by remember { mutableStateOf(false) }
    var requestTabContentFocus by remember { mutableStateOf<Int?>(null) }

    BackHandler(onBack = onBack)

    LaunchedEffect(Unit) {
        delay(50)
        try {
            tab0FocusRequester.requestFocus()
        } catch (_: Exception) {}
    }

    LaunchedEffect(requestTabContentFocus) {
        val tab = requestTabContentFocus ?: return@LaunchedEffect
        delay(60)
        try {
            when (tab) {
                1 -> firstDockChoiceFocusRequester.requestFocus()
                2 -> requestButtonRemapContentFocus = true
                3 -> recentAppsToggleFocusRequester.requestFocus()
                4 -> updateActionButtonFocusRequester.requestFocus()
            }
        } catch (_: Exception) {}
        requestTabContentFocus = null
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F172A))
            .padding(28.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = null,
                        tint = TV_Text_Secondary,
                        modifier = Modifier.size(24.dp)
                    )
                    Text(
                        text = "HomeKey TV Settings",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }

                // Connection indicator
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0x331E293B))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(
                                when (connectionStatus) {
                                    ConnectionStatus.AUTHENTICATED -> HA_Green_On
                                    ConnectionStatus.CONNECTING -> HA_Yellow_On
                                    else -> HA_Red_Off
                                }
                            )
                    )
                    Text(
                        text = when (connectionStatus) {
                            ConnectionStatus.AUTHENTICATED -> "Connected ($entityCount entities)"
                            ConnectionStatus.CONNECTING -> "Connecting..."
                            else -> "Disconnected"
                        },
                        fontSize = 12.sp,
                        color = Color.White
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Navigation Tabs (Switches automatically on focus, or with OK/Center)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SettingsTabItem(
                    title = "Phone Setup",
                    isSelected = selectedTab == 0,
                    focusRequester = tab0FocusRequester,
                    onLeft = { /* Stop at first tab: consume left */ },
                    onRight = { try { tab1FocusRequester.requestFocus() } catch (_: Exception) {} },
                    onUp = { /* Stay on tab bar */ },
                    onDown = { /* Consume down on tab 0 so focus stays on tab bar */ },
                    onSelect = { selectedTab = 0 }
                )
                SettingsTabItem(
                    title = "Layout & Popups",
                    isSelected = selectedTab == 1,
                    focusRequester = tab1FocusRequester,
                    onLeft = { try { tab0FocusRequester.requestFocus() } catch (_: Exception) {} },
                    onRight = { try { tab2FocusRequester.requestFocus() } catch (_: Exception) {} },
                    onUp = { /* Stay on tab bar */ },
                    onDown = {
                        selectedTab = 1
                        requestTabContentFocus = 1
                        try { firstDockChoiceFocusRequester.requestFocus() } catch (_: Exception) {}
                    },
                    onSelect = { selectedTab = 1 }
                )
                SettingsTabItem(
                    title = "Button Remap",
                    isSelected = selectedTab == 2,
                    focusRequester = tab2FocusRequester,
                    onLeft = { try { tab1FocusRequester.requestFocus() } catch (_: Exception) {} },
                    onRight = { try { tab3FocusRequester.requestFocus() } catch (_: Exception) {} },
                    onUp = { /* Stay on tab bar */ },
                    onDown = {
                        selectedTab = 2
                        requestButtonRemapContentFocus = true
                    },
                    onSelect = { selectedTab = 2 }
                )
                SettingsTabItem(
                    title = "Installed Apps (${pinnedApps.size} in dock)",
                    isSelected = selectedTab == 3,
                    focusRequester = tab3FocusRequester,
                    onLeft = { try { tab2FocusRequester.requestFocus() } catch (_: Exception) {} },
                    onRight = { try { tab4FocusRequester.requestFocus() } catch (_: Exception) {} },
                    onUp = { /* Stay on tab bar */ },
                    onDown = {
                        selectedTab = 3
                        requestTabContentFocus = 3
                        try { recentAppsToggleFocusRequester.requestFocus() } catch (_: Exception) {}
                    },
                    onSelect = { selectedTab = 3 }
                )
                SettingsTabItem(
                    title = "Updates",
                    isSelected = selectedTab == 4,
                    focusRequester = tab4FocusRequester,
                    onLeft = { try { tab3FocusRequester.requestFocus() } catch (_: Exception) {} },
                    onRight = { /* Stop at last tab: consume right */ },
                    onUp = { /* Stay on tab bar */ },
                    onDown = {
                        selectedTab = 4
                        requestTabContentFocus = 4
                        try { updateActionButtonFocusRequester.requestFocus() } catch (_: Exception) {}
                    },
                    onSelect = { selectedTab = 4 }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Tab Content
            Box(modifier = Modifier.weight(1f)) {
                when (selectedTab) {
                    0 -> QRSetupView(
                        setupUrl = setupUrl,
                        qrBitmap = qrBitmap,
                        pairingPin = pairingPin
                    )
                    1 -> LayoutStyleView(
                        currentLayout = panelLayout,
                        popupStyle = popupStyle,
                        cameraCompactSize = cameraCompactSize,
                        firstChoiceFocusRequester = firstDockChoiceFocusRequester,
                        onSelectLayout = { viewModel.setPanelLayout(it) },
                        onSelectPopupStyle = { viewModel.setPopupStyle(it) },
                        onSelectCameraCompactSize = { viewModel.setCameraCompactSize(it) },
                        onUp = { tab1FocusRequester.requestFocus() }
                    )
                    2 -> {
                        // Collect the (alphabetised, off-main) entity list only while this tab is
                        // visible, so the rest of Settings is not recomposed by HA state events.
                        val remapEntities by viewModel.sortedEntities.collectAsState()
                        ButtonRemapView(
                            isAccessibilityEnabled = isAccessibilityEnabled,
                            adbSetupState = adbSetupState,
                            buttonRemaps = buttonRemaps,
                            learnedKey = learnedKey,
                            installedApps = installedApps,
                            haEntities = remapEntities,
                            requestContentFocus = requestButtonRemapContentFocus,
                            onResetContentFocus = { requestButtonRemapContentFocus = false },
                            onUp = { tab2FocusRequester.requestFocus() },
                            onOpenAccessibilitySettings = {
                                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                context.startActivity(intent)
                            },
                            onEnableViaAdb = { viewModel.enableAccessibilityViaAdb() },
                            onStartLearnMode = { viewModel.setLearnMode(true) },
                            onStopLearnMode = { viewModel.setLearnMode(false) },
                            onSaveRemap = { viewModel.saveOrUpdateButtonRemap(it) },
                            onRemoveRemap = { viewModel.removeButtonRemap(it) }
                        )
                    }
                    3 -> InstalledAppsView(
                        installedApps = installedApps,
                        appIcons = appIcons,
                        pinnedApps = pinnedApps,
                        recentAppsEnabled = recentAppsEnabled,
                        recentAppsCount = recentAppsCount,
                        onSetRecentAppsCount = { viewModel.setRecentAppsCount(it) },
                        toggleFocusRequester = recentAppsToggleFocusRequester,
                        firstAppFocusRequester = firstAppFocusRequester,
                        onToggleRecentApps = { viewModel.setRecentAppsEnabled(!recentAppsEnabled) },
                        onUpToTab = { tab3FocusRequester.requestFocus() },
                        onTogglePinnedApp = { pkg, name -> viewModel.togglePinnedApp(pkg, name) }
                    )
                    4 -> {
                        LaunchedEffect(Unit) {
                            if (updateState is AppUpdateState.Idle) {
                                viewModel.checkForUpdates()
                            }
                        }
                        UpdatesView(
                            appVersion = appVersion,
                            updateState = updateState,
                            actionButtonFocusRequester = updateActionButtonFocusRequester,
                            onCheckForUpdates = { viewModel.checkForUpdates() },
                            onDownloadAndInstall = { viewModel.downloadAndInstallUpdate(it) },
                            onInstallApk = { viewModel.installApk(it) },
                            onOpenReleaseUrl = { viewModel.openReleaseUrl(it) },
                            onDismiss = { viewModel.resetUpdateState() },
                            onUp = { tab4FocusRequester.requestFocus() }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsTabItem(
    title: String,
    isSelected: Boolean,
    focusRequester: FocusRequester,
    onUp: (() -> Unit)? = null,
    onDown: (() -> Unit)? = null,
    onLeft: (() -> Unit)? = null,
    onRight: (() -> Unit)? = null,
    onSelect: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    LaunchedEffect(isFocused) {
        if (isFocused && !isSelected) {
            onSelect()
        }
    }

    Box(
        modifier = Modifier
            .focusRequester(focusRequester)
            .clip(RoundedCornerShape(10.dp))
            .background(
                when {
                    isFocused -> TV_Surface_Focused
                    isSelected -> HA_Blue
                    else -> Color(0x331E293B)
                }
            )
            .border(
                width = if (isFocused) 2.5.dp else if (isSelected) 1.5.dp else 0.dp,
                color = if (isFocused) Color.White else if (isSelected) HA_Blue else Color.Transparent,
                shape = RoundedCornerShape(10.dp)
            )
            .onPreviewKeyEvent { keyEvent ->
                val code = keyEvent.nativeKeyEvent.keyCode
                if (keyEvent.type == KeyEventType.KeyUp) {
                    when (code) {
                        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                            onSelect()
                            return@onPreviewKeyEvent true
                        }
                        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            return@onPreviewKeyEvent true
                        }
                    }
                } else if (keyEvent.type == KeyEventType.KeyDown) {
                    when (code) {
                        KeyEvent.KEYCODE_DPAD_UP -> {
                            onUp?.let { it(); return@onPreviewKeyEvent true }
                        }
                        KeyEvent.KEYCODE_DPAD_DOWN -> {
                            onDown?.let { it(); return@onPreviewKeyEvent true }
                        }
                        KeyEvent.KEYCODE_DPAD_LEFT -> {
                            onLeft?.let { it(); return@onPreviewKeyEvent true }
                        }
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            onRight?.let { it(); return@onPreviewKeyEvent true }
                        }
                    }
                }
                false
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onSelect
            )
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Text(
            text = title,
            fontSize = 13.sp,
            fontWeight = if (isSelected || isFocused) FontWeight.Bold else FontWeight.Medium,
            color = if (isSelected || isFocused) Color.White else TV_Text_Secondary
        )
    }
}

@Composable
fun LayoutStyleView(
    currentLayout: String,
    popupStyle: String,
    cameraCompactSize: String = PreferencesManager.CAMERA_COMPACT_SIZE_MEDIUM,
    firstChoiceFocusRequester: FocusRequester? = null,
    onSelectLayout: (String) -> Unit,
    onSelectPopupStyle: (String) -> Unit,
    onSelectCameraCompactSize: (String) -> Unit = {},
    onUp: () -> Unit
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(end = 10.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Section 1: Dock Position
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "DOCK POSITION",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = HA_Blue
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(135.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                LayoutChoiceCard(
                    title = "Bottom Dock",
                    subtitle = "Horizontal capsule at bottom center",
                    icon = Icons.Default.ViewAgenda,
                    isSelected = currentLayout == "DOCK_BOTTOM",
                    onClick = { onSelectLayout("DOCK_BOTTOM") },
                    modifier = Modifier.weight(1f),
                    onUp = onUp,
                    focusRequester = firstChoiceFocusRequester
                )

                LayoutChoiceCard(
                    title = "Left Dock",
                    subtitle = "Vertical capsule on left screen edge",
                    icon = Icons.Default.VerticalSplit,
                    isSelected = currentLayout == "DOCK_LEFT",
                    onClick = { onSelectLayout("DOCK_LEFT") },
                    modifier = Modifier.weight(1f),
                    onUp = onUp
                )

                LayoutChoiceCard(
                    title = "Right Dock",
                    subtitle = "Vertical capsule on right screen edge",
                    icon = Icons.AutoMirrored.Filled.ViewSidebar,
                    isSelected = currentLayout == "DOCK_RIGHT",
                    onClick = { onSelectLayout("DOCK_RIGHT") },
                    modifier = Modifier.weight(1f),
                    onUp = onUp
                )
            }
        }

        // Section 2: Controls & Camera Popup Style
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "POPUP & DIALOG STYLE",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = HA_Blue
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(135.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                LayoutChoiceCard(
                    title = "Compact Dock Preview",
                    subtitle = "Linear bar and compact camera preview above dock, blurring other icons",
                    icon = Icons.Default.Tune,
                    isSelected = popupStyle == PreferencesManager.POPUP_STYLE_MINIMAL,
                    onClick = { onSelectPopupStyle(PreferencesManager.POPUP_STYLE_MINIMAL) },
                    modifier = Modifier.weight(1f)
                )

                LayoutChoiceCard(
                    title = "Centered Modal",
                    subtitle = "Full modal dialog centered on screen with dimmed backdrop",
                    icon = Icons.Default.Fullscreen,
                    isSelected = popupStyle == PreferencesManager.POPUP_STYLE_CENTERED,
                    onClick = { onSelectPopupStyle(PreferencesManager.POPUP_STYLE_CENTERED) },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Section 3: Compact Camera Preview Size (shown only when Compact Dock Preview is selected)
        if (popupStyle == PreferencesManager.POPUP_STYLE_MINIMAL) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "COMPACT CAMERA PREVIEW SIZE",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = HA_Blue
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(135.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    LayoutChoiceCard(
                        title = "Small",
                        subtitle = "Compact adaptive preview matching camera feed",
                        icon = Icons.Default.Tv,
                        isSelected = cameraCompactSize == PreferencesManager.CAMERA_COMPACT_SIZE_SMALL,
                        onClick = { onSelectCameraCompactSize(PreferencesManager.CAMERA_COMPACT_SIZE_SMALL) },
                        modifier = Modifier.weight(1f)
                    )

                    LayoutChoiceCard(
                        title = "Medium",
                        subtitle = "Balanced adaptive preview matching camera feed (Default)",
                        icon = Icons.Default.Tv,
                        isSelected = cameraCompactSize == PreferencesManager.CAMERA_COMPACT_SIZE_MEDIUM,
                        onClick = { onSelectCameraCompactSize(PreferencesManager.CAMERA_COMPACT_SIZE_MEDIUM) },
                        modifier = Modifier.weight(1f)
                    )

                    LayoutChoiceCard(
                        title = "Large",
                        subtitle = "Spacious adaptive preview matching camera feed",
                        icon = Icons.Default.Tv,
                        isSelected = cameraCompactSize == PreferencesManager.CAMERA_COMPACT_SIZE_LARGE,
                        onClick = { onSelectCameraCompactSize(PreferencesManager.CAMERA_COMPACT_SIZE_LARGE) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
fun LayoutChoiceCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onUp: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.04f else 1.0f,
        animationSpec = tween(durationMillis = 80, easing = FastOutSlowInEasing),
        label = "choice_scale"
    )

    val focusModifier = if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier

    Box(
        modifier = modifier
            .then(focusModifier)
            .fillMaxHeight()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(16.dp))
            .background(if (isFocused) TV_Surface_Focused else Color(0xFF1E293B))
            .border(
                width = if (isFocused) 2.5.dp else if (isSelected) 2.dp else 1.dp,
                color = if (isFocused) TV_Border_Focused else if (isSelected) HA_Blue else Color(0x33475569),
                shape = RoundedCornerShape(16.dp)
            )
            .onPreviewKeyEvent { keyEvent ->
                val code = keyEvent.nativeKeyEvent.keyCode
                if (keyEvent.type == KeyEventType.KeyUp) {
                    if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                        onClick()
                        return@onPreviewKeyEvent true
                    }
                } else if (keyEvent.type == KeyEventType.KeyDown) {
                    if (code == KeyEvent.KEYCODE_DPAD_UP && onUp != null) {
                        onUp()
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
            )
            .padding(14.dp)
    ) {
        Column(
            verticalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxSize()
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isSelected) HA_Blue else Color(0x33FFFFFF)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                }

                Text(
                    text = title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )

                Text(
                    text = subtitle,
                    fontSize = 11.sp,
                    color = TV_Text_Secondary,
                    lineHeight = 15.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (isSelected) HA_Blue else Color(0x44FFFFFF))
                )
                Text(
                    text = if (isSelected) "Active" else "Select",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isSelected) HA_Blue else TV_Text_Secondary
                )
            }
        }
    }
}

@Composable
fun QRSetupView(
    setupUrl: String?,
    qrBitmap: Bitmap?,
    pairingPin: String
) {
    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF1E293B))
                .border(1.dp, Color(0x33475569), RoundedCornerShape(16.dp))
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            if (qrBitmap != null) {
                Image(
                    bitmap = qrBitmap.asImageBitmap(),
                    contentDescription = "QR Code Setup",
                    modifier = Modifier
                        .size(260.dp)
                        .clip(RoundedCornerShape(12.dp))
                )
            } else {
                CircularProgressIndicator(color = HA_Blue)
            }
        }

        Column(
            modifier = Modifier
                .weight(1.2f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    text = "Instant Phone Setup",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )

                Text(
                    text = "1. Connect your phone to the same Wi-Fi network.\n" +
                            "2. Scan the QR code or open the link below in your mobile browser.\n" +
                            "3. Enter the Pairing PIN shown here, plus your Home Assistant URL and Token (once).",
                    fontSize = 13.sp,
                    color = TV_Text_Secondary,
                    lineHeight = 20.sp
                )

                if (setupUrl != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF1E293B))
                            .border(1.dp, Color(0x33475569), RoundedCornerShape(10.dp))
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Text(
                            text = setupUrl,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = HA_Blue
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Pairing PIN",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TV_Text_Secondary
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF0B1120))
                            .border(1.dp, HA_Yellow_On, RoundedCornerShape(10.dp))
                            .padding(horizontal = 18.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = pairingPin,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = HA_Yellow_On,
                            letterSpacing = 8.sp
                        )
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.Info, contentDescription = null, tint = TV_Text_Secondary, modifier = Modifier.size(16.dp))
                Text(
                    text = "Web setup server running locally on port 8124",
                    fontSize = 11.sp,
                    color = TV_Text_Secondary
                )
            }
        }
    }
}

@Composable
private fun SetupStep(number: String, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(HA_Blue),
            contentAlignment = Alignment.Center
        ) {
            Text(text = number, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Text(text = text, color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
    }
}

@Composable
fun ButtonRemapView(
    isAccessibilityEnabled: Boolean,
    adbSetupState: AdbSetupState,
    buttonRemaps: List<ButtonRemapConfig>,
    learnedKey: Pair<Int, String>?,
    installedApps: List<InstalledAppInfo>,
    haEntities: List<HAEntityState>,
    requestContentFocus: Boolean = false,
    onResetContentFocus: () -> Unit = {},
    onUp: (() -> Unit)? = null,
    onOpenAccessibilitySettings: () -> Unit,
    onEnableViaAdb: () -> Unit,
    onStartLearnMode: () -> Unit,
    onStopLearnMode: () -> Unit,
    onSaveRemap: (ButtonRemapConfig) -> Unit,
    onRemoveRemap: (Int) -> Unit
) {
    var isLearning by remember { mutableStateOf(false) }
    var selectedKeyForConfig by remember { mutableStateOf<Pair<Int, String>?>(null) }
    var wasEditingConfig by remember { mutableStateOf(false) }

    val editMenuFocusRequester = remember { FocusRequester() }
    val remapListFocusRequester = remember { FocusRequester() }
    val learnButtonFocusRequester = remember { FocusRequester() }

    BackHandler(enabled = selectedKeyForConfig != null) {
        selectedKeyForConfig = null
    }

    LaunchedEffect(selectedKeyForConfig) {
        if (selectedKeyForConfig != null) {
            wasEditingConfig = true
            delay(80)
            try {
                editMenuFocusRequester.requestFocus()
            } catch (_: Exception) {}
        } else if (wasEditingConfig) {
            // Only return focus to remap list if user was actually editing and just closed the editor
            wasEditingConfig = false
            delay(80)
            try {
                if (buttonRemaps.isNotEmpty()) {
                    remapListFocusRequester.requestFocus()
                } else {
                    learnButtonFocusRequester.requestFocus()
                }
            } catch (_: Exception) {}
        }
    }

    LaunchedEffect(requestContentFocus) {
        if (requestContentFocus) {
            try {
                if (selectedKeyForConfig != null) {
                    editMenuFocusRequester.requestFocus()
                } else if (buttonRemaps.isNotEmpty()) {
                    remapListFocusRequester.requestFocus()
                } else {
                    learnButtonFocusRequester.requestFocus()
                }
            } catch (_: Exception) {}
            onResetContentFocus()
        }
    }

    LaunchedEffect(learnedKey) {
        if (learnedKey != null && isLearning) {
            selectedKeyForConfig = learnedKey
            isLearning = false
            onStopLearnMode()
        }
    }

    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // Left Column: Status & Remote Key Learning
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Accessibility Status Card
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF1E293B))
                    .border(1.dp, if (isAccessibilityEnabled) HA_Green_On else HA_Yellow_On, RoundedCornerShape(14.dp))
                    .padding(16.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(if (isAccessibilityEnabled) HA_Green_On else HA_Yellow_On)
                        )
                        Text(
                            text = if (isAccessibilityEnabled) "Remapper Service: ACTIVE" else "Accessibility: DISABLED",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }

                    Text(
                        text = if (isAccessibilityEnabled)
                            "Ready to intercept physical remote keys."
                        else
                            "Enable HomeKey TV under TV Settings > Accessibility or use Local ADB auto-setup.",
                        fontSize = 11.sp,
                        color = TV_Text_Secondary
                    )

                    if (!isAccessibilityEnabled) {
                        FocusableButton(
                            text = "Open TV Accessibility Settings",
                            icon = Icons.Default.SettingsAccessibility,
                            onClick = onOpenAccessibilitySettings
                        )

                        FocusableButton(
                            text = when (adbSetupState) {
                                is AdbSetupState.Connecting -> "Connecting to ADB..."
                                else -> "Auto-Enable via Local ADB"
                            },
                            icon = Icons.Default.Terminal,
                            onClick = onEnableViaAdb
                        )

                        when (adbSetupState) {
                            is AdbSetupState.Connecting -> {
                                Text(
                                    text = adbSetupState.message,
                                    fontSize = 11.sp,
                                    color = HA_Yellow_On,
                                    lineHeight = 15.sp
                                )
                            }
                            is AdbSetupState.Success -> {
                                Text(
                                    text = adbSetupState.message,
                                    fontSize = 11.sp,
                                    color = HA_Green_On,
                                    lineHeight = 15.sp
                                )
                            }
                            is AdbSetupState.Error -> {
                                Text(
                                    text = adbSetupState.message,
                                    fontSize = 11.sp,
                                    color = Color(0xFFEF4444),
                                    lineHeight = 15.sp
                                )
                            }
                            AdbSetupState.Idle -> {
                                Text(
                                    text = "Auto-Enable works on Fire TV or Android TV without opening TV settings. Requires ADB Debugging enabled in TV Settings > Developer Options.",
                                    fontSize = 10.sp,
                                    color = TV_Text_Secondary,
                                    lineHeight = 13.sp
                                )
                            }
                        }
                    } else {
                        if (adbSetupState is AdbSetupState.Success) {
                            Text(
                                text = adbSetupState.message,
                                fontSize = 11.sp,
                                color = HA_Green_On,
                                lineHeight = 15.sp
                            )
                        }
                    }
                }
            }

            // Quick Preset Buttons / Learning
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF1E293B))
                    .border(1.dp, Color(0x33475569), RoundedCornerShape(14.dp))
                    .padding(16.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "Remote Buttons",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )

                    FocusableButton(
                        text = if (isLearning) "Press any button on remote..." else "Learn Remote Button (Press Key)",
                        icon = if (isLearning) Icons.Default.HourglassBottom else Icons.Default.Sensors,
                        modifier = Modifier.focusRequester(learnButtonFocusRequester),
                        onClick = {
                            if (isLearning) {
                                isLearning = false
                                onStopLearnMode()
                            } else {
                                isLearning = true
                                onStartLearnMode()
                            }
                        }
                    )

                    Text(
                        text = "Or choose a preset button:",
                        fontSize = 12.sp,
                        color = TV_Text_Secondary
                    )

                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        item {
                            PresetButtonRow("Custom Star Button") {
                                selectedKeyForConfig = Pair(313, "Custom Star Button")
                            }
                        }
                        item {
                            PresetButtonRow("Netflix Button") {
                                selectedKeyForConfig = Pair(191, "Netflix Button")
                            }
                        }
                        item {
                            PresetButtonRow("YouTube Button") {
                                selectedKeyForConfig = Pair(190, "YouTube Button")
                            }
                        }
                        item {
                            PresetButtonRow("Prime Video Button") {
                                selectedKeyForConfig = Pair(229, "Prime Video Button")
                            }
                        }
                        item {
                            PresetButtonRow("Mute Button") {
                                selectedKeyForConfig = Pair(KeyEvent.KEYCODE_VOLUME_MUTE, "Mute Button")
                            }
                        }
                    }
                }
            }
        }

        // Right Column: Active Remap Configurations / Config Editor
        Box(
            modifier = Modifier
                .weight(1.4f)
                .fillMaxHeight()
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF1E293B))
                .border(1.dp, Color(0x33475569), RoundedCornerShape(14.dp))
                .padding(18.dp)
        ) {
            if (selectedKeyForConfig != null) {
                // Key Configuration Dialog
                val (code, name) = selectedKeyForConfig!!
                val existing = remember(code, buttonRemaps) { buttonRemaps.find { it.keyCode == code } }

                var singleActionType by remember(code, existing) { mutableStateOf(existing?.singlePressAction?.type ?: "OPEN_DOCK") }
                var singleTarget by remember(code, existing) { mutableStateOf(existing?.singlePressAction?.target ?: "") }
                var singleExtra by remember(code, existing) { mutableStateOf(existing?.singlePressAction?.extra ?: "") }

                var doubleActionType by remember(code, existing) { mutableStateOf(existing?.doublePressAction?.type ?: "NONE") }
                var doubleTarget by remember(code, existing) { mutableStateOf(existing?.doublePressAction?.target ?: "") }
                var doubleExtra by remember(code, existing) { mutableStateOf(existing?.doublePressAction?.extra ?: "") }

                var longActionType by remember(code, existing) { mutableStateOf(existing?.longPressAction?.type ?: "NONE") }
                var longTarget by remember(code, existing) { mutableStateOf(existing?.longPressAction?.target ?: "") }
                var longExtra by remember(code, existing) { mutableStateOf(existing?.longPressAction?.extra ?: "") }

                LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    item {
                        Text(
                            text = "Configure: $name",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = HA_Yellow_On
                        )
                    }

                    item {
                        ActionChoiceRow(
                            pressType = "Single Press",
                            currentAction = singleActionType,
                            currentTarget = singleTarget,
                            currentExtra = singleExtra,
                            installedApps = installedApps,
                            haEntities = haEntities,
                            initialFocusRequester = editMenuFocusRequester,
                            onSelectAction = { type, target, extra ->
                                singleActionType = type
                                singleTarget = target ?: ""
                                singleExtra = extra ?: ""
                            }
                        )
                    }

                    item {
                        ActionChoiceRow(
                            pressType = "Double Press",
                            currentAction = doubleActionType,
                            currentTarget = doubleTarget,
                            currentExtra = doubleExtra,
                            installedApps = installedApps,
                            haEntities = haEntities,
                            onSelectAction = { type, target, extra ->
                                doubleActionType = type
                                doubleTarget = target ?: ""
                                doubleExtra = extra ?: ""
                            }
                        )
                    }

                    item {
                        ActionChoiceRow(
                            pressType = "Long Press",
                            currentAction = longActionType,
                            currentTarget = longTarget,
                            currentExtra = longExtra,
                            installedApps = installedApps,
                            haEntities = haEntities,
                            onSelectAction = { type, target, extra ->
                                longActionType = type
                                longTarget = target ?: ""
                                longExtra = extra ?: ""
                            }
                        )
                    }

                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            FocusableButton(
                                text = "Save Remap",
                                icon = Icons.Default.Check,
                                onClick = {
                                    val config = ButtonRemapConfig(
                                        keyCode = code,
                                        keyName = name,
                                        singlePressAction = if (singleActionType != "NONE") RemapAction(singleActionType, singleTarget.ifBlank { null }, extra = singleExtra.ifBlank { null }) else null,
                                        doublePressAction = if (doubleActionType != "NONE") RemapAction(doubleActionType, doubleTarget.ifBlank { null }, extra = doubleExtra.ifBlank { null }) else null,
                                        longPressAction = if (longActionType != "NONE") RemapAction(longActionType, longTarget.ifBlank { null }, extra = longExtra.ifBlank { null }) else null
                                    )
                                    onSaveRemap(config)
                                    selectedKeyForConfig = null
                                }
                            )

                            FocusableButton(
                                text = "Cancel",
                                icon = Icons.Default.Close,
                                onClick = {
                                    selectedKeyForConfig = null
                                }
                            )

                            if (existing != null) {
                                FocusableButton(
                                    text = "Delete",
                                    icon = Icons.Default.Delete,
                                    onClick = {
                                        onRemoveRemap(code)
                                        selectedKeyForConfig = null
                                    }
                                )
                            }
                        }
                    }
                }
            } else {
                // List of Configured Remaps
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "Configured Button Remappings (${buttonRemaps.size})",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )

                    if (buttonRemaps.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No buttons remapped yet.\nSelect a preset or tap 'Learn Remote Button' on the left to map any key.",
                                color = TV_Text_Secondary,
                                fontSize = 13.sp,
                                lineHeight = 18.sp,
                                textAlign = TextAlign.Center
                            )
                        }
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            itemsIndexed(buttonRemaps, key = { _, remap -> remap.keyCode }) { index, remap ->
                                RemapCardItem(
                                    remap = remap,
                                    onEdit = { selectedKeyForConfig = Pair(remap.keyCode, remap.keyName) },
                                    onDelete = { onRemoveRemap(remap.keyCode) },
                                    modifier = if (index == 0) Modifier.focusRequester(remapListFocusRequester) else Modifier,
                                    onUp = if (index == 0) onUp else null
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}@Composable
fun PresetButtonRow(
    name: String,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (isFocused) TV_Surface_Focused else Color(0x22FFFFFF))
            .border(1.dp, if (isFocused) TV_Border_Focused else Color.Transparent, RoundedCornerShape(8.dp))
            .onPreviewKeyEvent { keyEvent ->
                if (keyEvent.type == KeyEventType.KeyUp) {
                    val code = keyEvent.nativeKeyEvent.keyCode
                    if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                        onClick()
                        return@onPreviewKeyEvent true
                    }
                }
                false
            }
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .focusable(interactionSource = interactionSource)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = name, fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.Medium)
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = TV_Text_Secondary, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
fun ActionChoiceRow(
    pressType: String,
    currentAction: String,
    currentTarget: String?,
    currentExtra: String? = null,
    installedApps: List<InstalledAppInfo>,
    haEntities: List<HAEntityState>,
    initialFocusRequester: FocusRequester? = null,
    onSelectAction: (String, String?, String?) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = "$pressType Action:", fontSize = 12.sp, color = TV_Text_Secondary, fontWeight = FontWeight.Bold)

        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val actions = listOf(
                "OPEN_DOCK" to "Open Dock",
                "LAUNCH_APP" to "Open App",
                "TOGGLE_ENTITY" to "Toggle HA",
                "SELECT_SOURCE" to "Input Source",
                "SYSTEM_SLEEP" to "Sleep TV",
                "SYSTEM_SETTINGS" to "TV Settings",
                "NONE" to "None"
            )
            actions.forEach { (type, label) ->
                val isSelected = currentAction == type
                val interactionSource = remember { MutableInteractionSource() }
                val isFocused by interactionSource.collectIsFocusedAsState()

                val focusModifier = if (initialFocusRequester != null &&
                    (isSelected || (actions.none { it.first == currentAction } && type == actions.first().first))
                ) {
                    Modifier.focusRequester(initialFocusRequester)
                } else {
                    Modifier
                }

                val chooseAction = {
                    val defaultTarget = when (type) {
                        "LAUNCH_APP" -> if (currentTarget.isNullOrBlank()) installedApps.firstOrNull()?.packageName else currentTarget
                        "TOGGLE_ENTITY" -> if (currentTarget.isNullOrBlank()) haEntities.firstOrNull()?.entityId else currentTarget
                        "SELECT_SOURCE" -> {
                            val mediaPlayers = haEntities.filter { it.domain == "media_player" }
                            if (currentTarget?.startsWith("media_player.") == true) currentTarget else mediaPlayers.firstOrNull()?.entityId
                        }
                        else -> null
                    }
                    val defaultExtra = when (type) {
                        "SELECT_SOURCE" -> {
                            if (!currentExtra.isNullOrBlank()) currentExtra
                            else {
                                val mp = haEntities.find { it.entityId == defaultTarget }
                                mp?.sourceList?.firstOrNull() ?: "TV"
                            }
                        }
                        else -> null
                    }
                    onSelectAction(type, defaultTarget, defaultExtra)
                }

                Box(
                    modifier = Modifier
                        .then(focusModifier)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isFocused) TV_Surface_Focused else if (isSelected) HA_Blue else Color(0x22FFFFFF))
                        .border(1.dp, if (isFocused) TV_Border_Focused else Color.Transparent, RoundedCornerShape(8.dp))
                        .onPreviewKeyEvent { keyEvent ->
                            if (keyEvent.type == KeyEventType.KeyUp) {
                                val code = keyEvent.nativeKeyEvent.keyCode
                                if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                                    chooseAction()
                                    return@onPreviewKeyEvent true
                                }
                            }
                            false
                        }
                        .focusable(interactionSource = interactionSource)
                        .clickable(interactionSource = interactionSource, indication = null) {
                            chooseAction()
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(text = label, fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        // If LAUNCH_APP: show installed apps selector
        if (currentAction == "LAUNCH_APP") {
            Text(text = "Select App to Launch:", fontSize = 11.sp, color = HA_Blue, fontWeight = FontWeight.SemiBold)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(installedApps, key = { it.packageName }) { app ->
                    val isAppSelected = currentTarget == app.packageName
                    val interactionSource = remember { MutableInteractionSource() }
                    val isFocused by interactionSource.collectIsFocusedAsState()

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (isFocused) TV_Surface_Focused else if (isAppSelected) Color(0xFF6366F1) else Color(0x331E293B))
                            .border(1.dp, if (isFocused) TV_Border_Focused else if (isAppSelected) Color.White else Color(0x22475569), RoundedCornerShape(6.dp))
                            .onPreviewKeyEvent { keyEvent ->
                                if (keyEvent.type == KeyEventType.KeyUp) {
                                    val code = keyEvent.nativeKeyEvent.keyCode
                                    if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                                        onSelectAction("LAUNCH_APP", app.packageName, null)
                                        return@onPreviewKeyEvent true
                                    }
                                }
                                false
                            }
                            .focusable(interactionSource = interactionSource)
                            .clickable(interactionSource = interactionSource, indication = null) {
                                onSelectAction("LAUNCH_APP", app.packageName, null)
                            }
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Text(
                            text = app.appName,
                            fontSize = 11.sp,
                            color = Color.White,
                            fontWeight = if (isAppSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }
        }

        // If TOGGLE_ENTITY: show HA entity selector with search function
        if (currentAction == "TOGGLE_ENTITY") {
            var entitySearchQuery by remember { mutableStateOf("") }
            val filteredEntities = remember(haEntities, entitySearchQuery) {
                if (entitySearchQuery.isBlank()) {
                    haEntities
                } else {
                    val q = entitySearchQuery.trim().lowercase()
                    haEntities.filter {
                        it.friendlyName.lowercase().contains(q) ||
                        it.entityId.lowercase().contains(q)
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "Select HA Entity to Toggle:", fontSize = 11.sp, color = HA_Yellow_On, fontWeight = FontWeight.SemiBold)
                if (entitySearchQuery.isNotBlank()) {
                    Text(text = "${filteredEntities.size} found", fontSize = 10.sp, color = TV_Text_Secondary)
                }
            }

            OutlinedTextField(
                value = entitySearchQuery,
                onValueChange = { entitySearchQuery = it },
                placeholder = { Text("Search entity by name or ID...", fontSize = 11.sp, color = TV_Text_Secondary) },
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = null, tint = TV_Text_Secondary, modifier = Modifier.size(16.dp))
                },
                trailingIcon = {
                    if (entitySearchQuery.isNotEmpty()) {
                        IconButton(onClick = { entitySearchQuery = "" }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear", tint = TV_Text_Secondary, modifier = Modifier.size(16.dp))
                        }
                    }
                },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = HA_Yellow_On,
                    unfocusedBorderColor = Color(0x44FFFFFF),
                    focusedContainerColor = Color(0xFF0F172A),
                    unfocusedContainerColor = Color(0x330F172A)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            )

            if (filteredEntities.isEmpty()) {
                Text(
                    text = "No entities found matching \"$entitySearchQuery\"",
                    fontSize = 11.sp,
                    color = TV_Text_Secondary,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            } else {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(filteredEntities, key = { it.entityId }) { entity ->
                        val isEntitySelected = currentTarget == entity.entityId
                        val interactionSource = remember { MutableInteractionSource() }
                        val isFocused by interactionSource.collectIsFocusedAsState()

                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (isFocused) TV_Surface_Focused else if (isEntitySelected) HA_Yellow_On else Color(0x331E293B))
                                .border(1.dp, if (isFocused) TV_Border_Focused else if (isEntitySelected) Color.White else Color(0x22475569), RoundedCornerShape(6.dp))
                                .onPreviewKeyEvent { keyEvent ->
                                    if (keyEvent.type == KeyEventType.KeyUp) {
                                        val code = keyEvent.nativeKeyEvent.keyCode
                                        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                                            onSelectAction("TOGGLE_ENTITY", entity.entityId, null)
                                            return@onPreviewKeyEvent true
                                        }
                                    }
                                    false
                                }
                                .focusable(interactionSource = interactionSource)
                                .clickable(interactionSource = interactionSource, indication = null) {
                                    onSelectAction("TOGGLE_ENTITY", entity.entityId, null)
                                }
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Text(
                                text = entity.friendlyName,
                                fontSize = 11.sp,
                                color = if (isEntitySelected) Color.Black else Color.White,
                                fontWeight = if (isEntitySelected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
            }
        }

        // If SELECT_SOURCE: show media player and input source selectors
        if (currentAction == "SELECT_SOURCE") {
            val mediaPlayers = remember(haEntities) { haEntities.filter { it.domain == "media_player" } }
            val selectedMp = remember(mediaPlayers, currentTarget) {
                mediaPlayers.find { it.entityId == currentTarget } ?: mediaPlayers.firstOrNull()
            }

            if (mediaPlayers.isEmpty()) {
                Text(
                    text = "No media player or TV entities found in Home Assistant.",
                    fontSize = 11.sp,
                    color = TV_Text_Secondary
                )
            } else {
                Text(
                    text = "Select Media Player / TV:",
                    fontSize = 11.sp,
                    color = Color(0xFF2DD4BF),
                    fontWeight = FontWeight.SemiBold
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(mediaPlayers, key = { it.entityId }) { mp ->
                        val isMpSelected = (selectedMp?.entityId == mp.entityId)
                        val interactionSource = remember { MutableInteractionSource() }
                        val isFocused by interactionSource.collectIsFocusedAsState()

                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (isFocused) TV_Surface_Focused else if (isMpSelected) Color(0xFF0D9488) else Color(0x331E293B))
                                .border(1.dp, if (isFocused) TV_Border_Focused else if (isMpSelected) Color.White else Color(0x22475569), RoundedCornerShape(6.dp))
                                .onPreviewKeyEvent { keyEvent ->
                                    if (keyEvent.type == KeyEventType.KeyUp) {
                                        val code = keyEvent.nativeKeyEvent.keyCode
                                        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                                            val defaultSrc = mp.sourceList.firstOrNull() ?: currentExtra ?: "TV"
                                            onSelectAction("SELECT_SOURCE", mp.entityId, defaultSrc)
                                            return@onPreviewKeyEvent true
                                        }
                                    }
                                    false
                                }
                                .focusable(interactionSource = interactionSource)
                                .clickable(interactionSource = interactionSource, indication = null) {
                                    val defaultSrc = mp.sourceList.firstOrNull() ?: currentExtra ?: "TV"
                                    onSelectAction("SELECT_SOURCE", mp.entityId, defaultSrc)
                                }
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Text(
                                text = mp.friendlyName,
                                fontSize = 11.sp,
                                color = Color.White,
                                fontWeight = if (isMpSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }

                val availableSources = remember(selectedMp) {
                    if (selectedMp != null && selectedMp.sourceList.isNotEmpty()) {
                        selectedMp.sourceList
                    } else {
                        listOf("TV", "HDMI 1", "HDMI 2", "HDMI 3", "HDMI 4")
                    }
                }

                Text(
                    text = "Select Input Source for ${selectedMp?.friendlyName ?: "TV"}:",
                    fontSize = 11.sp,
                    color = Color(0xFF2DD4BF),
                    fontWeight = FontWeight.SemiBold
                )

                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(availableSources, key = { it }) { src ->
                        val isSrcSelected = (currentExtra == src)
                        val interactionSource = remember { MutableInteractionSource() }
                        val isFocused by interactionSource.collectIsFocusedAsState()

                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (isFocused) TV_Surface_Focused else if (isSrcSelected) Color(0xFF0D9488) else Color(0x331E293B))
                                .border(1.dp, if (isFocused) TV_Border_Focused else if (isSrcSelected) Color.White else Color(0x22475569), RoundedCornerShape(6.dp))
                                .onPreviewKeyEvent { keyEvent ->
                                    if (keyEvent.type == KeyEventType.KeyUp) {
                                        val code = keyEvent.nativeKeyEvent.keyCode
                                        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                                            onSelectAction("SELECT_SOURCE", selectedMp?.entityId ?: currentTarget, src)
                                            return@onPreviewKeyEvent true
                                        }
                                    }
                                    false
                                }
                                .focusable(interactionSource = interactionSource)
                                .clickable(interactionSource = interactionSource, indication = null) {
                                    onSelectAction("SELECT_SOURCE", selectedMp?.entityId ?: currentTarget, src)
                                }
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Text(
                                text = src,
                                fontSize = 11.sp,
                                color = Color.White,
                                fontWeight = if (isSrcSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun RemapCardItem(
    remap: ButtonRemapConfig,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    onUp: (() -> Unit)? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.02f else 1.0f,
        animationSpec = tween(durationMillis = 80, easing = FastOutSlowInEasing),
        label = "card_scale"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(10.dp))
            .background(if (isFocused) TV_Surface_Focused else Color(0xFF0F172A))
            .border(
                width = if (isFocused) 2.dp else 1.dp,
                color = if (isFocused) TV_Border_Focused else Color(0x22FFFFFF),
                shape = RoundedCornerShape(10.dp)
            )
            .onPreviewKeyEvent { keyEvent ->
                if (keyEvent.type == KeyEventType.KeyUp) {
                    val code = keyEvent.nativeKeyEvent.keyCode
                    if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                        onEdit()
                        return@onPreviewKeyEvent true
                    }
                } else if (keyEvent.type == KeyEventType.KeyDown) {
                    if (keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_UP && onUp != null) {
                        onUp()
                        return@onPreviewKeyEvent true
                    }
                }
                false
            }
            .clickable(interactionSource = interactionSource, indication = null, onClick = onEdit)
            .focusable(interactionSource = interactionSource)
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(text = remap.keyName, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (remap.singlePressAction != null) {
                        Text(text = "1x: ${formatRemapActionLabel(remap.singlePressAction)}", fontSize = 11.sp, color = HA_Blue)
                    }
                    if (remap.doublePressAction != null) {
                        Text(text = "2x: ${formatRemapActionLabel(remap.doublePressAction)}", fontSize = 11.sp, color = HA_Yellow_On)
                    }
                    if (remap.longPressAction != null) {
                        Text(text = "Hold: ${formatRemapActionLabel(remap.longPressAction)}", fontSize = 11.sp, color = HA_Green_On)
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = "Edit",
                    tint = if (isFocused) Color.White else TV_Text_Secondary,
                    modifier = Modifier.size(18.dp)
                )
                FocusableIconButton(icon = Icons.Default.Delete, description = "Delete", onClick = onDelete)
            }
        }
    }
}

private fun formatRemapActionLabel(action: RemapAction): String {
    return when (action.type) {
        "SELECT_SOURCE" -> {
            val src = action.extra ?: "Source"
            val target = action.target?.substringAfterLast(".") ?: ""
            if (target.isNotBlank()) "$src ($target)" else src
        }
        "LAUNCH_APP" -> {
            val target = action.target?.substringAfterLast(".") ?: ""
            if (target.isNotBlank()) "App ($target)" else "Open App"
        }
        "TOGGLE_ENTITY" -> {
            val target = action.target?.substringAfterLast(".") ?: ""
            if (target.isNotBlank()) "Toggle ($target)" else "Toggle HA"
        }
        "OPEN_DOCK" -> "Open Dock"
        "SYSTEM_SLEEP" -> "Sleep TV"
        "SYSTEM_SETTINGS" -> "TV Settings"
        else -> action.type
    }
}

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
        verticalArrangement = Arrangement.spacedBy(14.dp)
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
                                if (recentAppsEnabled) {
                                    val chipIdx = (recentAppsCount - 1).coerceIn(0, 4)
                                    countFocusRequesters.getOrNull(chipIdx)?.requestFocus()
                                } else {
                                    toggleFocusRequester?.requestFocus()
                                }
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
        targetValue = if (isToggleFocused) 1.015f else 1.0f,
        animationSpec = spring(dampingRatio = 0.75f, stiffness = 400f),
        label = "toggle_scale"
    )

    val toggleFocusMod = if (toggleFocusRequester != null) Modifier.focusRequester(toggleFocusRequester) else Modifier

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF1E293B))
            .border(
                width = 1.dp,
                color = Color(0x33475569),
                shape = RoundedCornerShape(12.dp)
            )
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier
                .then(toggleFocusMod)
                .fillMaxWidth()
                .scale(toggleScale)
                .clip(RoundedCornerShape(10.dp))
                .background(if (isToggleFocused) TV_Surface_Focused else Color.Transparent)
                .border(
                    width = if (isToggleFocused) 2.dp else 0.dp,
                    color = if (isToggleFocused) TV_Border_Focused else Color.Transparent,
                    shape = RoundedCornerShape(10.dp)
                )
                .onPreviewKeyEvent { keyEvent ->
                    val code = keyEvent.nativeKeyEvent.keyCode
                    if (keyEvent.type == KeyEventType.KeyUp) {
                        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                            onToggle()
                            return@onPreviewKeyEvent true
                        }
                        if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) {
                            return@onPreviewKeyEvent true
                        }
                    } else if (keyEvent.type == KeyEventType.KeyDown) {
                        if (code == KeyEvent.KEYCODE_DPAD_UP && onUp != null) {
                            onUp()
                            return@onPreviewKeyEvent true
                        }
                        if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
                            if (enabled) {
                                try {
                                    val chipIdx = (recentAppsCount - 1).coerceIn(0, 4)
                                    countFocusRequesters.getOrNull(chipIdx)?.requestFocus()
                                } catch (_: Exception) {}
                            } else if (onDown != null) {
                                onDown()
                            }
                            return@onPreviewKeyEvent true
                        }
                    }
                    false
                }
                .clickable(
                    interactionSource = toggleInteractionSource,
                    indication = null,
                    onClick = onToggle
                )
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (enabled) HA_Blue else Color(0x33FFFFFF)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.History,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Column {
                    Text(
                        text = "Show Recent Apps in Dock",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = "Displays up to $recentAppsCount recently opened apps as floating circular icons in the dock",
                        fontSize = 12.sp,
                        color = TV_Text_Secondary
                    )
                }
            }

            Switch(
                checked = enabled,
                onCheckedChange = null,
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
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(Color(0x22FFFFFF))
                )
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Recent Apps to Display",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = TV_Text_Secondary
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        (1..5).forEach { count ->
                            RecentAppsCountChip(
                                count = count,
                                isSelected = count == recentAppsCount,
                                focusRequester = countFocusRequesters[count - 1],
                                onClick = { onSetRecentAppsCount(count) },
                                onUp = {
                                    try { toggleFocusRequester?.requestFocus() } catch (_: Exception) {}
                                },
                                onDown = {
                                    if (onDown != null) onDown()
                                },
                                onLeft = if (count > 1) {
                                    {
                                        try { countFocusRequesters[count - 2].requestFocus() } catch (_: Exception) {}
                                    }
                                } else null,
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
            .size(width = 44.dp, height = 34.dp)
            .clip(RoundedCornerShape(8.dp))
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
                shape = RoundedCornerShape(8.dp)
            )
            .onPreviewKeyEvent { keyEvent ->
                val code = keyEvent.nativeKeyEvent.keyCode
                if (keyEvent.type == KeyEventType.KeyUp) {
                    if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                        onClick()
                        return@onPreviewKeyEvent true
                    }
                    if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN ||
                        code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                        return@onPreviewKeyEvent true
                    }
                } else if (keyEvent.type == KeyEventType.KeyDown) {
                    when (code) {
                        KeyEvent.KEYCODE_DPAD_UP -> {
                            onUp()
                            return@onPreviewKeyEvent true
                        }
                        KeyEvent.KEYCODE_DPAD_DOWN -> {
                            onDown()
                            return@onPreviewKeyEvent true
                        }
                        KeyEvent.KEYCODE_DPAD_LEFT -> {
                            if (onLeft != null) {
                                onLeft()
                                return@onPreviewKeyEvent true
                            }
                        }
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            if (onRight != null) {
                                onRight()
                                return@onPreviewKeyEvent true
                            }
                        }
                    }
                }
                false
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = count.toString(),
            fontSize = 14.sp,
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
            .onPreviewKeyEvent { keyEvent ->
                val code = keyEvent.nativeKeyEvent.keyCode
                if (keyEvent.type == KeyEventType.KeyUp) {
                    if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                        onClick()
                        return@onPreviewKeyEvent true
                    }
                } else if (keyEvent.type == KeyEventType.KeyDown) {
                    if (code == KeyEvent.KEYCODE_DPAD_UP && index in 0..2 && onUpToTab != null) {
                        onUpToTab()
                        return@onPreviewKeyEvent true
                    }
                }
                false
            }
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

@Composable
fun UpdatesView(
    appVersion: String,
    updateState: AppUpdateState,
    actionButtonFocusRequester: FocusRequester = remember { FocusRequester() },
    onCheckForUpdates: () -> Unit,
    onDownloadAndInstall: (String) -> Unit,
    onInstallApk: (File) -> Unit,
    onOpenReleaseUrl: (String) -> Unit,
    onDismiss: () -> Unit,
    onUp: (() -> Unit)? = null
) {
    val upModifier = if (onUp != null) {
        Modifier.onPreviewKeyEvent { keyEvent ->
            if (keyEvent.type == KeyEventType.KeyDown && keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                onUp()
                true
            } else false
        }
    } else Modifier

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "App Updates",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    text = "Installed Version: v$appVersion",
                    fontSize = 14.sp,
                    color = TV_Text_Secondary
                )
            }

            Text(
                text = "Repository: leonida92/HomeKeyTV",
                fontSize = 12.sp,
                color = HA_Blue,
                fontWeight = FontWeight.SemiBold
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF1E293B))
                .border(1.dp, Color(0x33475569), RoundedCornerShape(16.dp))
                .padding(24.dp)
        ) {
            when (updateState) {
                is AppUpdateState.Idle -> {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(
                            text = "Check directly with GitHub for new versions and improvements.",
                            fontSize = 14.sp,
                            color = Color.White
                        )
                        FocusableButton(
                            text = "Check for Updates",
                            modifier = Modifier.focusRequester(actionButtonFocusRequester).then(upModifier),
                            onClick = onCheckForUpdates
                        )
                    }
                }

                is AppUpdateState.Checking -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier
                            .focusRequester(actionButtonFocusRequester)
                            .then(upModifier)
                            .focusable()
                    ) {
                        CircularProgressIndicator(
                            color = HA_Blue,
                            modifier = Modifier.size(28.dp),
                            strokeWidth = 3.dp
                        )
                        Column {
                            Text(
                                text = "Checking for updates...",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = "Connecting to GitHub API (leonida92/HomeKeyTV)...",
                                fontSize = 13.sp,
                                color = TV_Text_Secondary
                            )
                        }
                    }
                }

                is AppUpdateState.UpToDate -> {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = HA_Green_On,
                                modifier = Modifier.size(28.dp)
                            )
                            Column {
                                Text(
                                    text = "HomeKey TV is up to date",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Text(
                                    text = "You are running the latest version (v${updateState.version}).",
                                    fontSize = 13.sp,
                                    color = TV_Text_Secondary
                                )
                            }
                        }

                        FocusableButton(
                            text = "Check Again",
                            modifier = Modifier.focusRequester(actionButtonFocusRequester).then(upModifier),
                            onClick = onCheckForUpdates
                        )
                    }
                }

                is AppUpdateState.UpdateAvailable -> {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.SystemUpdate,
                                contentDescription = null,
                                tint = HA_Yellow_On,
                                modifier = Modifier.size(28.dp)
                            )
                            Column {
                                Text(
                                    text = "Update Available: ${updateState.latestVersion}",
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Text(
                                    text = updateState.releaseTitle,
                                    fontSize = 13.sp,
                                    color = HA_Yellow_On
                                )
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (!updateState.downloadUrl.isNullOrBlank()) {
                                FocusableButton(
                                    text = "Download & Install",
                                    modifier = Modifier.focusRequester(actionButtonFocusRequester).then(upModifier),
                                    onClick = { onDownloadAndInstall(updateState.downloadUrl) }
                                )
                            }
                            FocusableButton(
                                text = "View Release Page",
                                modifier = (if (updateState.downloadUrl.isNullOrBlank()) Modifier.focusRequester(actionButtonFocusRequester) else Modifier).then(upModifier),
                                onClick = { onOpenReleaseUrl(updateState.releaseUrl) }
                            )
                            FocusableButton(
                                text = "Dismiss",
                                modifier = upModifier,
                                onClick = onDismiss
                            )
                        }

                        if (updateState.releaseNotes.isNotBlank()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 140.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFF0F172A))
                                    .verticalScroll(rememberScrollState())
                                    .padding(12.dp)
                            ) {
                                Text(
                                    text = updateState.releaseNotes,
                                    fontSize = 13.sp,
                                    color = Color(0xFFCBD5E1)
                                )
                            }
                        }
                    }
                }

                is AppUpdateState.Downloading -> {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier
                            .focusRequester(actionButtonFocusRequester)
                            .then(upModifier)
                            .focusable()
                    ) {
                        Text(
                            text = "Downloading Update... ${updateState.progressPercent}%",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        LinearProgressIndicator(
                            progress = { updateState.progressPercent / 100f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = HA_Blue,
                            trackColor = Color(0xFF0F172A)
                        )
                    }
                }

                is AppUpdateState.ReadyToInstall -> {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = HA_Green_On,
                                modifier = Modifier.size(28.dp)
                            )
                            Column {
                                Text(
                                    text = "Download Complete",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Text(
                                    text = "Package ready. Click below to launch installer.",
                                    fontSize = 13.sp,
                                    color = TV_Text_Secondary
                                )
                            }
                        }

                        FocusableButton(
                            text = "Install Update",
                            modifier = Modifier.focusRequester(actionButtonFocusRequester).then(upModifier),
                            onClick = { onInstallApk(updateState.apkFile) }
                        )
                    }
                }

                is AppUpdateState.Error -> {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Error,
                                contentDescription = null,
                                tint = HA_Red_Off,
                                modifier = Modifier.size(28.dp)
                            )
                            Column {
                                Text(
                                    text = "Update Check Failed",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Text(
                                    text = updateState.message,
                                    fontSize = 13.sp,
                                    color = HA_Red_Off
                                )
                            }
                        }

                        FocusableButton(
                            text = "Retry",
                            modifier = Modifier.focusRequester(actionButtonFocusRequester).then(upModifier),
                            onClick = onCheckForUpdates
                        )
                    }
                }
            }
        }
    }
}
