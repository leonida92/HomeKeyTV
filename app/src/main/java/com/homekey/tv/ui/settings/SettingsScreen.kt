package com.homekey.tv.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.homekey.tv.data.models.AppUpdateState
import com.homekey.tv.viewmodel.SettingsViewModel
import kotlinx.coroutines.delay

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit
) {
    val connectionStatus by viewModel.connectionStatus.collectAsState()
    val setupUrl by viewModel.setupUrl.collectAsState()
    val qrBitmap by viewModel.qrCodeBitmap.collectAsState()
    val serverConfig by viewModel.serverConfig.collectAsState()
    val dockConfig by viewModel.dockConfig.collectAsState()
    val themeConfig by viewModel.themeConfig.collectAsState()
    val recentAppsConfig by viewModel.recentAppsConfig.collectAsState()
    val entityCount by viewModel.entityCount.collectAsState()
    val installedApps by viewModel.installedApps.collectAsState()
    val appIcons by viewModel.appIcons.collectAsState()
    val pinnedApps by viewModel.pinnedApps.collectAsState()
    val buttonRemaps by viewModel.buttonRemaps.collectAsState()
    val isAccessibilityEnabled by viewModel.isAccessibilityEnabled.collectAsState()
    val volumeFixEnabled by viewModel.volumeFixEnabled.collectAsState()
    val adbSetupState by viewModel.adbSetupState.collectAsState()
    val learnedKey by viewModel.learnedKey.collectAsState()
    val updateState by viewModel.updateState.collectAsState()
    val activeServerUrl by viewModel.activeServerUrl.collectAsState()
    val appVersion = viewModel.appVersion

    var selectedTab by remember { mutableIntStateOf(0) } // 0: Phone Setup, 1: Layout & Popups, 2: Themes, 3: Button Remap, 4: Installed Apps, 5: Updates

    val tabFocusRequesters = remember { List(6) { FocusRequester() } }
    val haToggleFocusRequester = remember { FocusRequester() }
    val updateActionButtonFocusRequester = remember { FocusRequester() }
    val firstDockChoiceFocusRequester = remember { FocusRequester() }
    val firstThemeChoiceFocusRequester = remember { FocusRequester() }
    val firstAppFocusRequester = remember { FocusRequester() }
    val recentAppsToggleFocusRequester = remember { FocusRequester() }
    var requestButtonRemapContentFocus by remember { mutableStateOf(false) }
    var requestTabContentFocus by remember { mutableStateOf<Int?>(null) }

    val contentFocusRequesters = remember {
        listOf(
            haToggleFocusRequester,
            firstDockChoiceFocusRequester,
            firstThemeChoiceFocusRequester,
            null,
            recentAppsToggleFocusRequester,
            updateActionButtonFocusRequester
        )
    }

    val tabTitles = listOf(
        "Phone Setup",
        "Layout & Popups",
        "Themes",
        "Button Remap",
        "Installed Apps (${pinnedApps.size} in dock)",
        "Updates"
    )

    BackHandler(onBack = onBack)

    LaunchedEffect(Unit) {
        delay(50)
        try {
            tabFocusRequesters[0].requestFocus()
        } catch (_: Exception) {}
    }

    LaunchedEffect(requestTabContentFocus) {
        val tab = requestTabContentFocus ?: return@LaunchedEffect
        delay(60)
        try {
            if (tab == 3) {
                requestButtonRemapContentFocus = true
            } else {
                contentFocusRequesters.getOrNull(tab)?.requestFocus()
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
            SettingsHeader(
                haEnabled = serverConfig.haEnabled,
                connectionStatus = connectionStatus,
                entityCount = entityCount
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Navigation Tabs (Switches automatically on focus, or with OK/Center)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                tabTitles.forEachIndexed { index, title ->
                    SettingsTabItem(
                        title = title,
                        isSelected = selectedTab == index,
                        focusRequester = tabFocusRequesters[index],
                        onLeft = {
                            if (index > 0) {
                                try { tabFocusRequesters[index - 1].requestFocus() } catch (_: Exception) {}
                            }
                        },
                        onRight = {
                            if (index < tabTitles.lastIndex) {
                                try { tabFocusRequesters[index + 1].requestFocus() } catch (_: Exception) {}
                            }
                        },
                        onUp = { /* Stay on tab bar */ },
                        onDown = {
                            selectedTab = index
                            if (index == 3) {
                                requestButtonRemapContentFocus = true
                            } else {
                                requestTabContentFocus = index
                                try { contentFocusRequesters.getOrNull(index)?.requestFocus() } catch (_: Exception) {}
                            }
                        },
                        onSelect = { selectedTab = index }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Tab Content
            Box(modifier = Modifier.weight(1f)) {
                when (selectedTab) {
                    0 -> PhoneSetupView(
                        haEnabled = serverConfig.haEnabled,
                        onToggleHaEnabled = { viewModel.setHaEnabled(!serverConfig.haEnabled) },
                        toggleFocusRequester = haToggleFocusRequester,
                        setupUrl = setupUrl,
                        qrBitmap = qrBitmap,
                        pairingPin = serverConfig.pairingPin,
                        serverUrl = serverConfig.serverUrl,
                        fallbackServerUrl = serverConfig.fallbackServerUrl,
                        activeServerUrl = activeServerUrl,
                        onUpToTab = { tabFocusRequesters[0].requestFocus() }
                    )
                    1 -> LayoutStyleView(
                        currentLayout = dockConfig.layout,
                        popupStyle = dockConfig.popupStyle,
                        cameraCompactSize = dockConfig.cameraCompactSize,
                        firstChoiceFocusRequester = firstDockChoiceFocusRequester,
                        onSelectLayout = { viewModel.setDockLayout(it) },
                        onSelectPopupStyle = { viewModel.setPopupStyle(it) },
                        onSelectCameraCompactSize = { viewModel.setCameraCompactSize(it) },
                        onUp = { tabFocusRequesters[1].requestFocus() }
                    )
                    2 -> ThemesView(
                        currentPreset = themeConfig.preset,
                        customColors = themeConfig.customColors,
                        activePalette = themeConfig.activePalette,
                        firstChoiceFocusRequester = firstThemeChoiceFocusRequester,
                        onSelectPreset = { viewModel.setThemePreset(it) },
                        onSelectCustomColor = { domain, hex -> viewModel.setCustomDomainColor(domain, hex) },
                        onUp = { tabFocusRequesters[2].requestFocus() }
                    )
                    3 -> {
                        // Collect the (alphabetised, off-main) entity list only while this tab is
                        // visible, so the rest of Settings is not recomposed by HA state events.
                        val remapEntities by viewModel.sortedEntities.collectAsState()
                        ButtonRemapView(
                            isAccessibilityEnabled = isAccessibilityEnabled,
                            volumeFixEnabled = volumeFixEnabled,
                            adbSetupState = adbSetupState,
                            buttonRemaps = buttonRemaps,
                            learnedKey = learnedKey,
                            installedApps = installedApps,
                            haEntities = remapEntities,
                            requestContentFocus = requestButtonRemapContentFocus,
                            onResetContentFocus = { requestButtonRemapContentFocus = false },
                            onUp = { tabFocusRequesters[3].requestFocus() },
                            onOpenAccessibilitySettings = onOpenAccessibilitySettings,
                            onEnableViaAdb = { viewModel.enableAccessibilityViaAdb() },
                            onEnableViaAdbWithPort = { port -> viewModel.enableAccessibilityViaAdb(port) },
                            onToggleVolumeFix = { viewModel.setVolumeFixEnabled(!volumeFixEnabled) },
                            onStartLearnMode = { viewModel.setLearnMode(true) },
                            onStopLearnMode = { viewModel.setLearnMode(false) },
                            onSaveRemap = { viewModel.saveOrUpdateButtonRemap(it) },
                            onRemoveRemap = { viewModel.removeButtonRemap(it) }
                        )
                    }
                    4 -> InstalledAppsView(
                        installedApps = installedApps,
                        appIcons = appIcons,
                        pinnedApps = pinnedApps,
                        recentAppsEnabled = recentAppsConfig.enabled,
                        recentAppsCount = recentAppsConfig.maxCount,
                        onSetRecentAppsCount = { viewModel.setRecentAppsCount(it) },
                        toggleFocusRequester = recentAppsToggleFocusRequester,
                        firstAppFocusRequester = firstAppFocusRequester,
                        onToggleRecentApps = { viewModel.setRecentAppsEnabled(!recentAppsConfig.enabled) },
                        onUpToTab = { tabFocusRequesters[4].requestFocus() },
                        onTogglePinnedApp = { pkg, name -> viewModel.togglePinnedApp(pkg, name) }
                    )
                    5 -> {
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
                            onUp = { tabFocusRequesters[5].requestFocus() }
                        )
                    }
                }
            }
        }
    }
}
