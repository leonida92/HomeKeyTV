package com.homeassistant.tv.ui.settings

import android.content.Intent
import android.graphics.Bitmap
import android.provider.Settings
import android.view.KeyEvent
import java.io.File
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.homeassistant.tv.data.models.*
import com.homeassistant.tv.ui.panel.FocusableButton
import com.homeassistant.tv.ui.panel.FocusableIconButton
import com.homeassistant.tv.ui.theme.*
import com.homeassistant.tv.viewmodel.SettingsViewModel

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
    val installedApps by viewModel.installedApps.collectAsState()
    val appIcons by viewModel.appIcons.collectAsState()
    val pinnedApps by viewModel.pinnedApps.collectAsState()
    val buttonRemaps by viewModel.buttonRemaps.collectAsState()
    val isAccessibilityEnabled by viewModel.isAccessibilityEnabled.collectAsState()
    val learnedKey by viewModel.learnedKey.collectAsState()
    val updateState by viewModel.updateState.collectAsState()
    val appVersion = viewModel.appVersion

    var selectedTab by remember { mutableStateOf(0) } // 0: Phone Setup, 1: Layout Position, 2: Button Remap, 3: Installed Apps, 4: Updates

    val tab0FocusRequester = remember { FocusRequester() }
    val tab1FocusRequester = remember { FocusRequester() }
    val tab2FocusRequester = remember { FocusRequester() }
    val tab3FocusRequester = remember { FocusRequester() }
    val tab4FocusRequester = remember { FocusRequester() }

    BackHandler(onBack = onBack)

    LaunchedEffect(Unit) {
        try {
            tab0FocusRequester.requestFocus()
        } catch (_: Exception) {}
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

            // Navigation Tabs (Require clicking/pressing OK to switch)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SettingsTabItem(
                    title = "Phone Setup",
                    isSelected = selectedTab == 0,
                    focusRequester = tab0FocusRequester,
                    onRight = { tab1FocusRequester.requestFocus() },
                    onSelect = { selectedTab = 0 }
                )
                SettingsTabItem(
                    title = "Layout Position",
                    isSelected = selectedTab == 1,
                    focusRequester = tab1FocusRequester,
                    onLeft = { tab0FocusRequester.requestFocus() },
                    onRight = { tab2FocusRequester.requestFocus() },
                    onSelect = { selectedTab = 1 }
                )
                SettingsTabItem(
                    title = "Button Remap",
                    isSelected = selectedTab == 2,
                    focusRequester = tab2FocusRequester,
                    onLeft = { tab1FocusRequester.requestFocus() },
                    onRight = { tab3FocusRequester.requestFocus() },
                    onSelect = { selectedTab = 2 }
                )
                SettingsTabItem(
                    title = "Installed Apps (${pinnedApps.size} in dock)",
                    isSelected = selectedTab == 3,
                    focusRequester = tab3FocusRequester,
                    onLeft = { tab2FocusRequester.requestFocus() },
                    onRight = { tab4FocusRequester.requestFocus() },
                    onSelect = { selectedTab = 3 }
                )
                SettingsTabItem(
                    title = "Updates (v$appVersion)",
                    isSelected = selectedTab == 4,
                    focusRequester = tab4FocusRequester,
                    onLeft = { tab3FocusRequester.requestFocus() },
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
                        onSelectLayout = { viewModel.setPanelLayout(it) },
                        onUp = { tab1FocusRequester.requestFocus() }
                    )
                    2 -> {
                        // Collect the (alphabetised, off-main) entity list only while this tab is
                        // visible, so the rest of Settings is not recomposed by HA state events.
                        val remapEntities by viewModel.sortedEntities.collectAsState()
                        ButtonRemapView(
                            isAccessibilityEnabled = isAccessibilityEnabled,
                            buttonRemaps = buttonRemaps,
                            learnedKey = learnedKey,
                            installedApps = installedApps,
                            haEntities = remapEntities,
                            onOpenAccessibilitySettings = {
                                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                context.startActivity(intent)
                            },
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
                        onTogglePinnedApp = { pkg, name -> viewModel.togglePinnedApp(pkg, name) }
                    )
                    4 -> UpdatesView(
                        appVersion = appVersion,
                        updateState = updateState,
                        onCheckForUpdates = { viewModel.checkForUpdates() },
                        onDownloadAndInstall = { viewModel.downloadAndInstallUpdate(it) },
                        onInstallApk = { viewModel.installApk(it) },
                        onOpenReleaseUrl = { viewModel.openReleaseUrl(it) },
                        onDismiss = { viewModel.resetUpdateState() }
                    )
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
            .focusable(interactionSource = interactionSource)
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
    onSelectLayout: (String) -> Unit,
    onUp: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        LayoutChoiceCard(
            title = "Bottom Dock",
            subtitle = "Floating horizontal capsule at bottom center",
            icon = Icons.Default.ViewAgenda,
            isSelected = currentLayout == "DOCK_BOTTOM",
            onUp = onUp,
            onClick = { onSelectLayout("DOCK_BOTTOM") },
            modifier = Modifier.weight(1f)
        )

        LayoutChoiceCard(
            title = "Left Dock",
            subtitle = "Floating vertical capsule on the left screen edge",
            icon = Icons.Default.VerticalSplit,
            isSelected = currentLayout == "DOCK_LEFT",
            onUp = onUp,
            onClick = { onSelectLayout("DOCK_LEFT") },
            modifier = Modifier.weight(1f)
        )

        LayoutChoiceCard(
            title = "Right Dock",
            subtitle = "Floating vertical capsule on the right screen edge",
            icon = Icons.AutoMirrored.Filled.ViewSidebar,
            isSelected = currentLayout == "DOCK_RIGHT",
            onUp = onUp,
            onClick = { onSelectLayout("DOCK_RIGHT") },
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
fun LayoutChoiceCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    isSelected: Boolean,
    onUp: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.04f else 1.0f,
        animationSpec = tween(durationMillis = 80, easing = FastOutSlowInEasing),
        label = "choice_scale"
    )

    Box(
        modifier = modifier
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
                    if (code == KeyEvent.KEYCODE_DPAD_UP) {
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
            .padding(20.dp)
    ) {
        Column(
            verticalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxSize()
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isSelected) HA_Blue else Color(0x33FFFFFF)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(26.dp))
                }

                Text(
                    text = title,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )

                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    color = TV_Text_Secondary,
                    lineHeight = 17.sp
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(if (isSelected) HA_Blue else Color(0x44FFFFFF))
                )
                Text(
                    text = if (isSelected) "Active" else "Select",
                    fontSize = 12.sp,
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
    buttonRemaps: List<ButtonRemapConfig>,
    learnedKey: Pair<Int, String>?,
    installedApps: List<InstalledAppInfo>,
    haEntities: List<HAEntityState>,
    onOpenAccessibilitySettings: () -> Unit,
    onStartLearnMode: () -> Unit,
    onStopLearnMode: () -> Unit,
    onSaveRemap: (ButtonRemapConfig) -> Unit,
    onRemoveRemap: (Int) -> Unit
) {
    var isLearning by remember { mutableStateOf(false) }
    var selectedKeyForConfig by remember { mutableStateOf<Pair<Int, String>?>(null) }

    BackHandler(enabled = selectedKeyForConfig != null) {
        selectedKeyForConfig = null
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
                            "Enable HomeAssistantTV under TV Settings > Accessibility to allow button remapping.",
                        fontSize = 11.sp,
                        color = TV_Text_Secondary
                    )

                    FocusableButton(
                        text = "Open TV Accessibility Settings",
                        icon = Icons.Default.SettingsAccessibility,
                        onClick = onOpenAccessibilitySettings
                    )
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
                            PresetButtonRow("★ Custom Star Button") {
                                selectedKeyForConfig = Pair(KeyEvent.KEYCODE_PAIRING, "Custom Star Button")
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

                var doubleActionType by remember(code, existing) { mutableStateOf(existing?.doublePressAction?.type ?: "NONE") }
                var doubleTarget by remember(code, existing) { mutableStateOf(existing?.doublePressAction?.target ?: "") }

                var longActionType by remember(code, existing) { mutableStateOf(existing?.longPressAction?.type ?: "NONE") }
                var longTarget by remember(code, existing) { mutableStateOf(existing?.longPressAction?.target ?: "") }

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
                            installedApps = installedApps,
                            haEntities = haEntities,
                            onSelectAction = { type, target ->
                                singleActionType = type
                                singleTarget = target ?: ""
                            }
                        )
                    }

                    item {
                        ActionChoiceRow(
                            pressType = "Double Press",
                            currentAction = doubleActionType,
                            currentTarget = doubleTarget,
                            installedApps = installedApps,
                            haEntities = haEntities,
                            onSelectAction = { type, target ->
                                doubleActionType = type
                                doubleTarget = target ?: ""
                            }
                        )
                    }

                    item {
                        ActionChoiceRow(
                            pressType = "Long Press",
                            currentAction = longActionType,
                            currentTarget = longTarget,
                            installedApps = installedApps,
                            haEntities = haEntities,
                            onSelectAction = { type, target ->
                                longActionType = type
                                longTarget = target ?: ""
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
                                        singlePressAction = if (singleActionType != "NONE") RemapAction(singleActionType, singleTarget.ifBlank { null }) else null,
                                        doublePressAction = if (doubleActionType != "NONE") RemapAction(doubleActionType, doubleTarget.ifBlank { null }) else null,
                                        longPressAction = if (longActionType != "NONE") RemapAction(longActionType, longTarget.ifBlank { null }) else null
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
                            items(buttonRemaps, key = { it.keyCode }) { remap ->
                                RemapCardItem(
                                    remap = remap,
                                    onEdit = { selectedKeyForConfig = Pair(remap.keyCode, remap.keyName) },
                                    onDelete = { onRemoveRemap(remap.keyCode) }
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
    installedApps: List<InstalledAppInfo>,
    haEntities: List<HAEntityState>,
    onSelectAction: (String, String?) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = "$pressType Action:", fontSize = 12.sp, color = TV_Text_Secondary, fontWeight = FontWeight.Bold)

        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val actions = listOf(
                "OPEN_DOCK" to "Open Dock",
                "LAUNCH_APP" to "Open App",
                "TOGGLE_ENTITY" to "Toggle HA",
                "SYSTEM_SLEEP" to "Sleep TV",
                "SYSTEM_SETTINGS" to "TV Settings",
                "NONE" to "None"
            )
            items(actions) { (type, label) ->
                val isSelected = currentAction == type
                val interactionSource = remember { MutableInteractionSource() }
                val isFocused by interactionSource.collectIsFocusedAsState()

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isFocused) TV_Surface_Focused else if (isSelected) HA_Blue else Color(0x22FFFFFF))
                        .border(1.dp, if (isFocused) TV_Border_Focused else Color.Transparent, RoundedCornerShape(8.dp))
                        .onPreviewKeyEvent { keyEvent ->
                            if (keyEvent.type == KeyEventType.KeyUp) {
                                val code = keyEvent.nativeKeyEvent.keyCode
                                if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                                    val defaultTarget = when (type) {
                                        "LAUNCH_APP" -> if (currentTarget.isNullOrBlank()) installedApps.firstOrNull()?.packageName else currentTarget
                                        "TOGGLE_ENTITY" -> if (currentTarget.isNullOrBlank()) haEntities.firstOrNull()?.entityId else currentTarget
                                        else -> null
                                    }
                                    onSelectAction(type, defaultTarget)
                                    return@onPreviewKeyEvent true
                                }
                            }
                            false
                        }
                        .focusable(interactionSource = interactionSource)
                        .clickable(interactionSource = interactionSource, indication = null) {
                            val defaultTarget = when (type) {
                                "LAUNCH_APP" -> if (currentTarget.isNullOrBlank()) installedApps.firstOrNull()?.packageName else currentTarget
                                "TOGGLE_ENTITY" -> if (currentTarget.isNullOrBlank()) haEntities.firstOrNull()?.entityId else currentTarget
                                else -> null
                            }
                            onSelectAction(type, defaultTarget)
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
                                    onSelectAction("LAUNCH_APP", app.packageName)
                                    return@onPreviewKeyEvent true
                                }
                            }
                            false
                        }
                            .focusable(interactionSource = interactionSource)
                            .clickable(interactionSource = interactionSource, indication = null) {
                                onSelectAction("LAUNCH_APP", app.packageName)
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

        // If TOGGLE_ENTITY: show HA entity selector
        if (currentAction == "TOGGLE_ENTITY") {
            Text(text = "Select HA Entity to Toggle:", fontSize = 11.sp, color = HA_Yellow_On, fontWeight = FontWeight.SemiBold)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(haEntities, key = { it.entityId }) { entity ->
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
                                    onSelectAction("TOGGLE_ENTITY", entity.entityId)
                                    return@onPreviewKeyEvent true
                                }
                            }
                            false
                        }
                            .focusable(interactionSource = interactionSource)
                            .clickable(interactionSource = interactionSource, indication = null) {
                                onSelectAction("TOGGLE_ENTITY", entity.entityId)
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
}

@Composable
fun RemapCardItem(
    remap: ButtonRemapConfig,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.02f else 1.0f,
        animationSpec = tween(durationMillis = 80, easing = FastOutSlowInEasing),
        label = "card_scale"
    )

    Box(
        modifier = Modifier
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
                        val label = if (remap.singlePressAction.target != null) "${remap.singlePressAction.type} (${remap.singlePressAction.target.substringAfterLast(".")})" else remap.singlePressAction.type
                        Text(text = "1x: $label", fontSize = 11.sp, color = HA_Blue)
                    }
                    if (remap.doublePressAction != null) {
                        val label = if (remap.doublePressAction.target != null) "${remap.doublePressAction.type} (${remap.doublePressAction.target.substringAfterLast(".")})" else remap.doublePressAction.type
                        Text(text = "2x: $label", fontSize = 11.sp, color = HA_Yellow_On)
                    }
                    if (remap.longPressAction != null) {
                        val label = if (remap.longPressAction.target != null) "${remap.longPressAction.type} (${remap.longPressAction.target.substringAfterLast(".")})" else remap.longPressAction.type
                        Text(text = "Hold: $label", fontSize = 11.sp, color = HA_Green_On)
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

@Composable
fun InstalledAppsView(
    installedApps: List<InstalledAppInfo>,
    appIcons: Map<String, Bitmap>,
    pinnedApps: List<PinnedAppConfig>,
    onTogglePinnedApp: (String, String) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
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
                items(installedApps, key = { it.packageName }) { app ->
                    val isPinned = pinnedApps.any { it.packageName == app.packageName }
                    AppGridCard(
                        app = app,
                        appIcon = appIcons[app.packageName],
                        isPinned = isPinned,
                        onClick = { onTogglePinnedApp(app.packageName, app.appName) }
                    )
                }
            }
        }
    }
}

@Composable
fun AppGridCard(
    app: InstalledAppInfo,
    appIcon: Bitmap?,
    isPinned: Boolean,
    onClick: () -> Unit
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

    Box(
        modifier = Modifier
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
                if (keyEvent.type == KeyEventType.KeyUp) {
                    val code = keyEvent.nativeKeyEvent.keyCode
                    if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                        onClick()
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
    onCheckForUpdates: () -> Unit,
    onDownloadAndInstall: (String) -> Unit,
    onInstallApk: (File) -> Unit,
    onOpenReleaseUrl: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val checkButtonFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        try {
            checkButtonFocusRequester.requestFocus()
        } catch (_: Exception) {}
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
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
                            modifier = Modifier.focusRequester(checkButtonFocusRequester),
                            onClick = onCheckForUpdates
                        )
                    }
                }

                is AppUpdateState.Checking -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
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
                            modifier = Modifier.focusRequester(checkButtonFocusRequester),
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

                        if (updateState.releaseNotes.isNotBlank()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFF0F172A))
                                    .padding(12.dp)
                            ) {
                                Text(
                                    text = updateState.releaseNotes,
                                    fontSize = 13.sp,
                                    color = Color(0xFFCBD5E1),
                                    maxLines = 6
                                )
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (!updateState.downloadUrl.isNullOrBlank()) {
                                FocusableButton(
                                    text = "Download & Install",
                                    modifier = Modifier.focusRequester(checkButtonFocusRequester),
                                    onClick = { onDownloadAndInstall(updateState.downloadUrl) }
                                )
                            }
                            FocusableButton(
                                text = "View Release Page",
                                modifier = if (updateState.downloadUrl.isNullOrBlank()) Modifier.focusRequester(checkButtonFocusRequester) else Modifier,
                                onClick = { onOpenReleaseUrl(updateState.releaseUrl) }
                            )
                            FocusableButton(
                                text = "Dismiss",
                                onClick = onDismiss
                            )
                        }
                    }
                }

                is AppUpdateState.Downloading -> {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
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
                            modifier = Modifier.focusRequester(checkButtonFocusRequester),
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
                            modifier = Modifier.focusRequester(checkButtonFocusRequester),
                            onClick = onCheckForUpdates
                        )
                    }
                }
            }
        }
    }
}
