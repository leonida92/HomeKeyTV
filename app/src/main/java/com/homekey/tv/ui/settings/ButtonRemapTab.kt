package com.homekey.tv.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.HourglassBottom
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.SettingsAccessibility
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.homekey.tv.data.models.ButtonRemapConfig
import com.homekey.tv.data.models.HAEntityState
import com.homekey.tv.data.models.InstalledAppInfo
import com.homekey.tv.data.models.RemapAction
import com.homekey.tv.ui.panel.FocusableButton
import com.homekey.tv.ui.panel.FocusableIconButton
import com.homekey.tv.ui.theme.HA_Blue
import com.homekey.tv.ui.theme.HA_Green_On
import com.homekey.tv.ui.theme.HA_Yellow_On
import com.homekey.tv.ui.theme.TV_Border_Focused
import com.homekey.tv.ui.theme.TV_Surface_Focused
import com.homekey.tv.ui.theme.TV_Text_Secondary
import com.homekey.tv.viewmodel.AdbSetupState
import kotlinx.coroutines.delay

@Composable
fun ButtonRemapView(
    isAccessibilityEnabled: Boolean,
    volumeFixEnabled: Boolean,
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
    onEnableViaAdbWithPort: (Int) -> Unit = {},
    onToggleVolumeFix: () -> Unit,
    onStartLearnMode: () -> Unit,
    onStopLearnMode: () -> Unit,
    onSaveRemap: (ButtonRemapConfig) -> Unit,
    onRemoveRemap: (ButtonRemapConfig) -> Unit
) {
    var isLearning by remember { mutableStateOf(false) }
    var selectedKeyForConfig by remember { mutableStateOf<Pair<Int, String>?>(null) }
    var editingRemapConfig by remember { mutableStateOf<ButtonRemapConfig?>(null) }
    var wasEditingConfig by remember { mutableStateOf(false) }
    var showCustomPortDialog by remember { mutableStateOf(false) }

    val editMenuFocusRequester = remember { FocusRequester() }
    val remapListFocusRequester = remember { FocusRequester() }
    val learnButtonFocusRequester = remember { FocusRequester() }
    val volumeFixFocusRequester = remember { FocusRequester() }
    val openAccessibilityFocusRequester = remember { FocusRequester() }
    val adbSetupFocusRequester = remember { FocusRequester() }

    BackHandler(enabled = selectedKeyForConfig != null) {
        selectedKeyForConfig = null
        editingRemapConfig = null
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
                } else if (!isAccessibilityEnabled) {
                    openAccessibilityFocusRequester.requestFocus()
                } else {
                    learnButtonFocusRequester.requestFocus()
                }
            } catch (_: Exception) {}
            onResetContentFocus()
        }
    }

    LaunchedEffect(learnedKey) {
        if (learnedKey != null && isLearning) {
            editingRemapConfig = null
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
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
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
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }

                        VolumeFixCompactToggle(
                            enabled = volumeFixEnabled,
                            onToggle = onToggleVolumeFix,
                            modifier = Modifier.focusRequester(volumeFixFocusRequester),
                            onUp = onUp,
                            onDown = { try { learnButtonFocusRequester.requestFocus() } catch (_: Exception) {} },
                            onRight = { if (buttonRemaps.isNotEmpty()) { try { remapListFocusRequester.requestFocus() } catch (_: Exception) {} } }
                        )
                    }

                    Text(
                        text = if (isAccessibilityEnabled)
                            if (volumeFixEnabled) "Ready to remap keys. Volume repeat fix active for Xiaomi / Android 9."
                            else "Ready to remap keys. Volume keys pass through standard OS filter."
                        else
                            "Enable HomeKey TV under TV Settings > Accessibility or use Local ADB auto-setup.",
                        fontSize = 11.sp,
                        color = TV_Text_Secondary,
                        lineHeight = 14.sp
                    )

                    if (!isAccessibilityEnabled) {
                        FocusableButton(
                            text = "Open TV Accessibility Settings",
                            icon = Icons.Default.SettingsAccessibility,
                            modifier = Modifier
                                .focusRequester(openAccessibilityFocusRequester)
                                .dpadKeyHandler(
                                    onUp = onUp,
                                    onDown = { try { adbSetupFocusRequester.requestFocus() } catch (_: Exception) {} },
                                    onRight = if (buttonRemaps.isNotEmpty()) {
                                        { try { remapListFocusRequester.requestFocus() } catch (_: Exception) {} }
                                    } else null
                                ),
                            onClick = onOpenAccessibilitySettings
                        )

                        FocusableButton(
                            text = when (adbSetupState) {
                                is AdbSetupState.Connecting -> "Connecting to ADB..."
                                else -> "Auto-Enable via Local ADB"
                            },
                            icon = Icons.Default.Terminal,
                            modifier = Modifier
                                .focusRequester(adbSetupFocusRequester)
                                .dpadKeyHandler(
                                    onUp = { try { openAccessibilityFocusRequester.requestFocus() } catch (_: Exception) {} },
                                    onDown = { try { learnButtonFocusRequester.requestFocus() } catch (_: Exception) {} },
                                    onRight = if (buttonRemaps.isNotEmpty()) {
                                        { try { remapListFocusRequester.requestFocus() } catch (_: Exception) {} }
                                    } else null
                                ),
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
                                Spacer(modifier = Modifier.height(4.dp))
                                FocusableButton(
                                    text = "Enter Wireless Debugging Port",
                                    icon = Icons.Default.Edit,
                                    onClick = { showCustomPortDialog = true }
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
                    .padding(14.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Remote Buttons",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )

                    FocusableButton(
                        text = if (isLearning) "Press any button on remote..." else "Learn Remote Button (Press Key)",
                        icon = if (isLearning) Icons.Default.HourglassBottom else Icons.Default.Sensors,
                        modifier = Modifier
                            .focusRequester(learnButtonFocusRequester)
                            .dpadKeyHandler(
                                onUp = {
                                    if (isAccessibilityEnabled) {
                                        try { volumeFixFocusRequester.requestFocus() } catch (_: Exception) {}
                                    } else {
                                        try { adbSetupFocusRequester.requestFocus() } catch (_: Exception) {}
                                    }
                                },
                                onRight = if (buttonRemaps.isNotEmpty()) {
                                    { try { remapListFocusRequester.requestFocus() } catch (_: Exception) {} }
                                } else null
                            ),
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

                    val onPresetRight: (() -> Unit) = {
                        if (buttonRemaps.isNotEmpty()) {
                            try { remapListFocusRequester.requestFocus() } catch (_: Exception) {}
                        }
                    }

                    val presetButtons = remember {
                        listOf(
                            313 to "Custom Star Button",
                            191 to "Netflix Button",
                            190 to "YouTube Button",
                            229 to "Prime Video Button",
                            android.view.KeyEvent.KEYCODE_VOLUME_MUTE to "Mute Button"
                        )
                    }

                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        itemsIndexed(presetButtons) { index, (keyCode, keyName) ->
                            PresetButtonRow(
                                name = keyName,
                                onClick = {
                                    editingRemapConfig = null
                                    selectedKeyForConfig = Pair(keyCode, keyName)
                                },
                                onUp = if (index == 0) {
                                    { try { learnButtonFocusRequester.requestFocus() } catch (_: Exception) {} }
                                } else null,
                                onRight = onPresetRight
                            )
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
            selectedKeyForConfig?.let { (code, name) ->
                RemapConfigEditor(
                    keyCode = code,
                    keyName = name,
                    editingRemapConfig = editingRemapConfig,
                    buttonRemaps = buttonRemaps,
                    installedApps = installedApps,
                    haEntities = haEntities,
                    editMenuFocusRequester = editMenuFocusRequester,
                    onSaveRemap = { config ->
                        onSaveRemap(config)
                        selectedKeyForConfig = null
                        editingRemapConfig = null
                    },
                    onCancel = {
                        selectedKeyForConfig = null
                        editingRemapConfig = null
                    },
                    onDelete = { config ->
                        onRemoveRemap(config)
                        selectedKeyForConfig = null
                        editingRemapConfig = null
                    }
                )
            } ?: run {
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
                            itemsIndexed(buttonRemaps, key = { _, remap -> "${remap.keyCode}_${remap.appScope ?: "global"}" }) { index, remap ->
                                RemapCardItem(
                                    remap = remap,
                                    onEdit = {
                                        editingRemapConfig = remap
                                        selectedKeyForConfig = Pair(remap.keyCode, remap.keyName)
                                    },
                                    onDelete = { onRemoveRemap(remap) },
                                    modifier = if (index == 0) Modifier.focusRequester(remapListFocusRequester) else Modifier,
                                    onUp = if (index == 0) onUp else null,
                                    onLeft = { try { learnButtonFocusRequester.requestFocus() } catch (_: Exception) {} },
                                    installedApps = installedApps
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCustomPortDialog) {
        AdbCustomPortDialog(
            onConnect = { port ->
                showCustomPortDialog = false
                onEnableViaAdbWithPort(port)
            },
            onDismiss = { showCustomPortDialog = false }
        )
    }
}

@Composable
private fun RemapConfigEditor(
    keyCode: Int,
    keyName: String,
    editingRemapConfig: ButtonRemapConfig?,
    buttonRemaps: List<ButtonRemapConfig>,
    installedApps: List<InstalledAppInfo>,
    haEntities: List<HAEntityState>,
    editMenuFocusRequester: FocusRequester,
    onSaveRemap: (ButtonRemapConfig) -> Unit,
    onCancel: () -> Unit,
    onDelete: (ButtonRemapConfig) -> Unit
) {
    val existing = editingRemapConfig ?: remember(keyCode, buttonRemaps) {
        buttonRemaps.find { it.keyCode == keyCode && it.appScope == null }
    }

    var appScope by remember(keyCode, existing) { mutableStateOf(existing?.appScope) }
    var singleActionType by remember(keyCode, existing) { mutableStateOf(existing?.singlePressAction?.type ?: "OPEN_DOCK") }
    var singleTarget by remember(keyCode, existing) { mutableStateOf(existing?.singlePressAction?.target ?: "") }
    var singleExtra by remember(keyCode, existing) { mutableStateOf(existing?.singlePressAction?.extra ?: "") }

    var doubleActionType by remember(keyCode, existing) { mutableStateOf(existing?.doublePressAction?.type ?: "NONE") }
    var doubleTarget by remember(keyCode, existing) { mutableStateOf(existing?.doublePressAction?.target ?: "") }
    var doubleExtra by remember(keyCode, existing) { mutableStateOf(existing?.doublePressAction?.extra ?: "") }

    var longActionType by remember(keyCode, existing) { mutableStateOf(existing?.longPressAction?.type ?: "NONE") }
    var longTarget by remember(keyCode, existing) { mutableStateOf(existing?.longPressAction?.target ?: "") }
    var longExtra by remember(keyCode, existing) { mutableStateOf(existing?.longPressAction?.extra ?: "") }

    fun selectScope(newScope: String?) {
        appScope = newScope
        val matchingRule = buttonRemaps.find { it.keyCode == keyCode && it.appScope == newScope }
        if (matchingRule != null) {
            singleActionType = matchingRule.singlePressAction?.type ?: "OPEN_DOCK"
            singleTarget = matchingRule.singlePressAction?.target ?: ""
            singleExtra = matchingRule.singlePressAction?.extra ?: ""
            doubleActionType = matchingRule.doublePressAction?.type ?: "NONE"
            doubleTarget = matchingRule.doublePressAction?.target ?: ""
            doubleExtra = matchingRule.doublePressAction?.extra ?: ""
            longActionType = matchingRule.longPressAction?.type ?: "NONE"
            longTarget = matchingRule.longPressAction?.target ?: ""
            longExtra = matchingRule.longPressAction?.extra ?: ""
        } else {
            singleActionType = if (newScope == null) "OPEN_DOCK" else "PASS_THROUGH"
            singleTarget = ""
            singleExtra = ""
            doubleActionType = "NONE"
            doubleTarget = ""
            doubleExtra = ""
            longActionType = "NONE"
            longTarget = ""
            longExtra = ""
        }
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Text(
                text = "Configure: $keyName",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = HA_Yellow_On
            )
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Application Scope:",
                    fontSize = 12.sp,
                    color = TV_Text_Secondary,
                    fontWeight = FontWeight.Bold
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SettingsFilterChip(
                        label = "Global (All Apps)",
                        isSelected = appScope.isNullOrBlank(),
                        selectedBackground = HA_Blue,
                        unselectedBackground = Color(0x22FFFFFF),
                        selectedBorder = Color.Transparent,
                        unselectedBorder = Color.Transparent,
                        cornerRadius = 8.dp,
                        horizontalPadding = 12.dp,
                        verticalPadding = 6.dp,
                        boldWhenSelected = false,
                        onClick = { selectScope(null) }
                    )

                    installedApps.forEach { app ->
                        SettingsFilterChip(
                            label = app.appName,
                            isSelected = appScope == app.packageName,
                            selectedBackground = Color(0xFF6366F1),
                            unselectedBackground = Color(0x22FFFFFF),
                            selectedBorder = Color.Transparent,
                            unselectedBorder = Color.Transparent,
                            cornerRadius = 8.dp,
                            horizontalPadding = 12.dp,
                            verticalPadding = 6.dp,
                            boldWhenSelected = false,
                            onClick = { selectScope(app.packageName) }
                        )
                    }
                }
            }
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
                            keyCode = keyCode,
                            keyName = keyName,
                            appScope = appScope?.trim()?.ifEmpty { null },
                            singlePressAction = if (singleActionType != "NONE") RemapAction(singleActionType, singleTarget.ifBlank { null }, extra = singleExtra.ifBlank { null }) else null,
                            doublePressAction = if (doubleActionType != "NONE") RemapAction(doubleActionType, doubleTarget.ifBlank { null }, extra = doubleExtra.ifBlank { null }) else null,
                            longPressAction = if (longActionType != "NONE") RemapAction(longActionType, longTarget.ifBlank { null }, extra = longExtra.ifBlank { null }) else null
                        )
                        onSaveRemap(config)
                    }
                )

                FocusableButton(
                    text = "Cancel",
                    icon = Icons.Default.Close,
                    onClick = onCancel
                )

                val currentConfigToDelete = buttonRemaps.find {
                    it.keyCode == keyCode && it.appScope == appScope?.trim()?.ifEmpty { null }
                }
                if (currentConfigToDelete != null) {
                    FocusableButton(
                        text = "Delete",
                        icon = Icons.Default.Delete,
                        onClick = { onDelete(currentConfigToDelete) }
                    )
                }
            }
        }
    }
}

@Composable
fun AdbCustomPortDialog(
    initialPort: String = "",
    onConnect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var portText by remember { mutableStateOf(initialPort) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val inputFocusRequester = remember { FocusRequester() }
    val connectButtonFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        delay(60)
        try {
            inputFocusRequester.requestFocus()
        } catch (_: Exception) {}
    }

    BackHandler(onBack = onDismiss)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xCC000000))
            .onPreviewKeyEvent { keyEvent ->
                val code = keyEvent.nativeKeyEvent.keyCode
                if (code == android.view.KeyEvent.KEYCODE_BACK || code == android.view.KeyEvent.KEYCODE_ESCAPE) {
                    if (keyEvent.type == KeyEventType.KeyUp) {
                        onDismiss()
                    }
                    return@onPreviewKeyEvent true
                }
                false
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .width(460.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF1E293B))
                .border(1.dp, Color(0x44FFFFFF), RoundedCornerShape(16.dp))
                .padding(24.dp)
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalAlignment = Alignment.Start
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Default.Terminal,
                        contentDescription = null,
                        tint = HA_Yellow_On,
                        modifier = Modifier.size(22.dp)
                    )
                    Text(
                        text = "Wireless Debugging Port",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }

                Text(
                    text = "On Android 11+, check TV Settings > Developer Options > Wireless Debugging to find the dynamic port (e.g. 192.168.1.50:41235).",
                    fontSize = 11.sp,
                    color = TV_Text_Secondary,
                    lineHeight = 15.sp
                )

                OutlinedTextField(
                    value = portText,
                    onValueChange = { input ->
                        val filtered = input.filter { it.isDigit() }.take(5)
                        portText = filtered
                        errorMessage = null
                    },
                    placeholder = { Text("e.g. 41235", fontSize = 12.sp, color = TV_Text_Secondary) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    textStyle = TextStyle(fontSize = 14.sp, color = Color.White, fontWeight = FontWeight.Bold),
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
                        .focusRequester(inputFocusRequester)
                        .onPreviewKeyEvent { keyEvent ->
                            if (keyEvent.type == KeyEventType.KeyUp) {
                                val code = keyEvent.nativeKeyEvent.keyCode
                                if (code == android.view.KeyEvent.KEYCODE_ENTER || code == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER) {
                                    val p = portText.toIntOrNull()
                                    if (p != null && p in 1024..65535) {
                                        onConnect(p)
                                    } else {
                                        errorMessage = "Enter a valid port (1024 - 65535)"
                                    }
                                    return@onPreviewKeyEvent true
                                }
                            }
                            false
                        }
                )

                errorMessage?.let { err ->
                    Text(
                        text = err,
                        fontSize = 11.sp,
                        color = Color(0xFFEF4444)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FocusableButton(
                        text = "Cancel",
                        onClick = onDismiss
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    FocusableButton(
                        text = "Connect",
                        icon = Icons.Default.Check,
                        modifier = Modifier.focusRequester(connectButtonFocusRequester),
                        onClick = {
                            val p = portText.toIntOrNull()
                            if (p != null && p in 1024..65535) {
                                onConnect(p)
                            } else {
                                errorMessage = "Enter a valid port (1024 - 65535)"
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun VolumeFixCompactToggle(
    enabled: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    onUp: (() -> Unit)? = null,
    onDown: (() -> Unit)? = null,
    onRight: (() -> Unit)? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.05f else 1.0f,
        animationSpec = spring(dampingRatio = 0.75f, stiffness = 400f),
        label = "vol_fix_toggle_scale"
    )

    Row(
        modifier = modifier
            .scale(scale)
            .clip(RoundedCornerShape(8.dp))
            .background(if (isFocused) TV_Surface_Focused else Color(0x33334155))
            .border(
                width = if (isFocused) 2.dp else 1.dp,
                color = if (isFocused) TV_Border_Focused else Color(0x44475569),
                shape = RoundedCornerShape(8.dp)
            )
            .dpadKeyHandler(
                onSelect = onToggle,
                onUp = onUp,
                onDown = onDown,
                onRight = onRight
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onToggle
            )
            .focusable(interactionSource = interactionSource)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.VolumeUp,
            contentDescription = null,
            tint = if (enabled) Color(0xFF38BDF8) else TV_Text_Secondary,
            modifier = Modifier.size(14.dp)
        )
        Text(
            text = if (enabled) "Volume Fix: ON" else "Volume Fix: OFF",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (enabled) Color.White else TV_Text_Secondary
        )
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(if (enabled) HA_Green_On else Color(0xFF64748B))
        )
    }
}

@Composable
fun PresetButtonRow(
    name: String,
    onClick: () -> Unit,
    onUp: (() -> Unit)? = null,
    onRight: (() -> Unit)? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (isFocused) TV_Surface_Focused else Color(0x22FFFFFF))
            .border(1.dp, if (isFocused) TV_Border_Focused else Color.Transparent, RoundedCornerShape(8.dp))
            .dpadKeyHandler(onSelect = onClick, onUp = onUp, onRight = onRight)
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
                "SEND_BROADCAST" to "Broadcast Intent",
                "START_SERVICE" to "Service Intent",
                "TOGGLE_ENTITY" to "Toggle HA",
                "SELECT_SOURCE" to "Input Source",
                "SYSTEM_SLEEP" to "Sleep TV",
                "SYSTEM_SETTINGS" to "TV Settings",
                "PASS_THROUGH" to "Pass-Through",
                "NONE" to "None"
            )
            actions.forEach { (type, label) ->
                val isSelected = currentAction == type
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
                        "SEND_BROADCAST" -> {
                            if (!currentTarget.isNullOrBlank() && currentTarget.contains("ACTION")) currentTarget
                            else if (installedApps.any { it.packageName == "com.wled.tv" }) "com.wled.tv.ACTION_TOGGLE_OVERLAY"
                            else currentTarget
                        }
                        "START_SERVICE" -> currentTarget
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
                        "SEND_BROADCAST" -> {
                            if (!currentExtra.isNullOrBlank()) currentExtra
                            else if (defaultTarget == "com.wled.tv.ACTION_TOGGLE_OVERLAY") "com.wled.tv"
                            else null
                        }
                        "START_SERVICE" -> currentExtra
                        else -> null
                    }
                    onSelectAction(type, defaultTarget, defaultExtra)
                }

                SettingsFilterChip(
                    label = label,
                    isSelected = isSelected,
                    selectedBackground = HA_Blue,
                    unselectedBackground = Color(0x22FFFFFF),
                    selectedBorder = Color.Transparent,
                    unselectedBorder = Color.Transparent,
                    cornerRadius = 8.dp,
                    horizontalPadding = 12.dp,
                    verticalPadding = 6.dp,
                    boldWhenSelected = false,
                    modifier = focusModifier,
                    onClick = chooseAction
                )
            }
        }

        // If LAUNCH_APP: show installed apps selector
        if (currentAction == "LAUNCH_APP") {
            Text(text = "Select App to Launch:", fontSize = 11.sp, color = HA_Blue, fontWeight = FontWeight.SemiBold)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(installedApps, key = { it.packageName }) { app ->
                    SettingsFilterChip(
                        label = app.appName,
                        isSelected = currentTarget == app.packageName,
                        selectedBackground = Color(0xFF6366F1),
                        onClick = { onSelectAction("LAUNCH_APP", app.packageName, null) }
                    )
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
                textStyle = TextStyle(fontSize = 12.sp, color = Color.White),
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
                    .defaultMinSize(minHeight = 48.dp)
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
                        SettingsFilterChip(
                            label = entity.friendlyName,
                            isSelected = currentTarget == entity.entityId,
                            selectedBackground = HA_Yellow_On,
                            selectedTextColor = Color.Black,
                            onClick = { onSelectAction("TOGGLE_ENTITY", entity.entityId, null) }
                        )
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
                        SettingsFilterChip(
                            label = mp.friendlyName,
                            isSelected = selectedMp?.entityId == mp.entityId,
                            selectedBackground = Color(0xFF0D9488),
                            onClick = {
                                val defaultSrc = mp.sourceList.firstOrNull() ?: currentExtra ?: "TV"
                                onSelectAction("SELECT_SOURCE", mp.entityId, defaultSrc)
                            }
                        )
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
                        SettingsFilterChip(
                            label = src,
                            isSelected = currentExtra == src,
                            selectedBackground = Color(0xFF0D9488),
                            onClick = { onSelectAction("SELECT_SOURCE", selectedMp?.entityId ?: currentTarget, src) }
                        )
                    }
                }
            }
        }

        // If SEND_BROADCAST or START_SERVICE: show Intent Action and Package Name fields
        if (currentAction == "SEND_BROADCAST" || currentAction == "START_SERVICE") {
            val isBroadcast = currentAction == "SEND_BROADCAST"
            val titleColor = if (isBroadcast) Color(0xFF38BDF8) else Color(0xFFA78BFA)
            Text(
                text = if (isBroadcast) "Configure Broadcast Intent:" else "Configure Service Intent:",
                fontSize = 11.sp,
                color = titleColor,
                fontWeight = FontWeight.SemiBold
            )

            var actionText by remember(currentTarget) { mutableStateOf(currentTarget ?: "") }
            var packageText by remember(currentExtra) { mutableStateOf(currentExtra ?: "") }

            Text(
                text = "Intent Action:",
                fontSize = 11.sp,
                color = titleColor,
                fontWeight = FontWeight.Medium
            )

            OutlinedTextField(
                value = actionText,
                onValueChange = {
                    actionText = it
                    onSelectAction(currentAction, it.ifBlank { null }, packageText.ifBlank { null })
                },
                placeholder = {
                    Text(
                        text = if (isBroadcast) "e.g. com.wled.tv.ACTION_TOGGLE_OVERLAY" else "e.g. com.example.START_SERVICE",
                        fontSize = 12.sp,
                        color = TV_Text_Secondary
                    )
                },
                textStyle = TextStyle(fontSize = 13.sp, color = Color.White),
                trailingIcon = {
                    if (actionText.isNotEmpty()) {
                        IconButton(onClick = {
                            actionText = ""
                            onSelectAction(currentAction, null, packageText.ifBlank { null })
                        }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear", tint = TV_Text_Secondary, modifier = Modifier.size(16.dp))
                        }
                    }
                },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = titleColor,
                    unfocusedBorderColor = Color(0x44FFFFFF),
                    focusedContainerColor = Color(0xFF0F172A),
                    unfocusedContainerColor = Color(0x330F172A)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 52.dp)
            )

            Text(
                text = "Target Package (Optional):",
                fontSize = 11.sp,
                color = TV_Text_Secondary,
                fontWeight = FontWeight.Medium
            )

            OutlinedTextField(
                value = packageText,
                onValueChange = {
                    packageText = it
                    onSelectAction(currentAction, actionText.ifBlank { null }, it.ifBlank { null })
                },
                placeholder = {
                    Text("e.g. com.wled.tv", fontSize = 12.sp, color = TV_Text_Secondary)
                },
                textStyle = TextStyle(fontSize = 13.sp, color = Color.White),
                trailingIcon = {
                    if (packageText.isNotEmpty()) {
                        IconButton(onClick = {
                            packageText = ""
                            onSelectAction(currentAction, actionText.ifBlank { null }, null)
                        }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear", tint = TV_Text_Secondary, modifier = Modifier.size(16.dp))
                        }
                    }
                },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = titleColor,
                    unfocusedBorderColor = Color(0x44FFFFFF),
                    focusedContainerColor = Color(0xFF0F172A),
                    unfocusedContainerColor = Color(0x330F172A)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 52.dp)
            )

            // Preset quick-fill chip if WLED TV is installed
            val hasWled = remember(installedApps) { installedApps.any { it.packageName == "com.wled.tv" } }
            if (hasWled && isBroadcast) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Quick Preset:", fontSize = 10.sp, color = TV_Text_Secondary)
                    SettingsFilterChip(
                        label = "WLED TV Overlay",
                        isSelected = false,
                        selectedBackground = Color(0x2238BDF8),
                        unselectedBackground = Color(0x2238BDF8),
                        unselectedBorder = Color(0x4438BDF8),
                        unselectedTextColor = Color(0xFF38BDF8),
                        horizontalPadding = 8.dp,
                        verticalPadding = 4.dp,
                        fontSize = 10.sp,
                        boldWhenSelected = false,
                        onClick = {
                            actionText = "com.wled.tv.ACTION_TOGGLE_OVERLAY"
                            packageText = "com.wled.tv"
                            onSelectAction("SEND_BROADCAST", "com.wled.tv.ACTION_TOGGLE_OVERLAY", "com.wled.tv")
                        }
                    )
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
    onUp: (() -> Unit)? = null,
    onLeft: (() -> Unit)? = null,
    installedApps: List<InstalledAppInfo> = emptyList()
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
            .dpadKeyHandler(onSelect = onEdit, onUp = onUp, onLeft = onLeft)
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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(text = remap.keyName, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    if (!remap.appScope.isNullOrBlank()) {
                        val appName = installedApps.find { it.packageName == remap.appScope }?.appName ?: remap.appScope
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0xFF334155))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "App: $appName",
                                fontSize = 10.sp,
                                color = Color(0xFF38BDF8),
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
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
        "SEND_BROADCAST" -> {
            val target = action.target?.substringAfterLast(".") ?: "Broadcast"
            "Broadcast ($target)"
        }
        "START_SERVICE" -> {
            val target = action.target?.substringAfterLast(".") ?: "Service"
            "Service ($target)"
        }
        "OPEN_DOCK" -> "Open Dock"
        "PASS_THROUGH" -> "Pass-Through"
        "SYSTEM_SLEEP" -> "Sleep TV"
        "SYSTEM_SETTINGS" -> "TV Settings"
        else -> action.type
    }
}
