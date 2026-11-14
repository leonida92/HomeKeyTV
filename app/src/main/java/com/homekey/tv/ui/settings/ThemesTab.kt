package com.homekey.tv.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Curtains
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.ToggleOn
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.homekey.tv.data.models.DomainColorPalette
import com.homekey.tv.data.models.ThemeCatalog
import com.homekey.tv.data.models.ThemePreset
import com.homekey.tv.ui.theme.HA_Blue
import com.homekey.tv.ui.theme.TV_Border_Focused
import com.homekey.tv.ui.theme.TV_Surface_Focused
import com.homekey.tv.ui.theme.TV_Text_Secondary
import kotlinx.coroutines.delay

@Composable
fun ThemesView(
    currentPreset: String,
    customColors: Map<String, String>,
    activePalette: DomainColorPalette,
    firstChoiceFocusRequester: FocusRequester,
    onSelectPreset: (String) -> Unit,
    onSelectCustomColor: (String, String) -> Unit,
    onUp: () -> Unit
) {
    val scrollState = rememberScrollState()
    var colorPickerDomainTarget by remember { mutableStateOf<String?>(null) }
    var colorPickerDomainName by remember { mutableStateOf("") }
    var colorPickerInitialHex by remember { mutableStateOf("#0284C7") }
    var lastTargetedDomain by remember { mutableStateOf<String?>(null) }

    val presets = listOf(
        ThemePreset.CLASSIC,
        ThemePreset.APPLE,
        ThemePreset.CYBERPUNK,
        ThemePreset.NORDIC,
        ThemePreset.MONOCHROME,
        ThemePreset.CUSTOM
    )

    val domainList = remember {
        ThemeCatalog.domainMetadata.map { metadata ->
            Triple(metadata.key, metadata.label, domainIconForKey(metadata.key))
        }
    }

    val domainFocusRequesters = remember {
        domainList.associate { it.first to FocusRequester() }
    }

    LaunchedEffect(colorPickerDomainTarget) {
        if (colorPickerDomainTarget == null && lastTargetedDomain != null) {
            delay(50)
            try {
                domainFocusRequesters[lastTargetedDomain]?.requestFocus()
            } catch (_: Exception) {}
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(end = 10.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Section 1: Presets
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "THEME PRESETS",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = HA_Blue
                )
                Text(
                    text = "Select an active color palette for your dock tiles and status indicators.",
                    fontSize = 12.sp,
                    color = TV_Text_Secondary
                )

                // 2 rows of 3 presets
                for (rowIdx in 0..1) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(130.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        for (colIdx in 0..2) {
                            val index = rowIdx * 3 + colIdx
                            val preset = presets[index]
                            val isSelected = currentPreset.equals(preset.id, ignoreCase = true)
                            val palette = if (preset == ThemePreset.CUSTOM) {
                                DomainColorPalette.CLASSIC.withOverrides(customColors)
                            } else {
                                DomainColorPalette.forPreset(preset)
                            }

                            ThemePresetCard(
                                preset = preset,
                                palette = palette,
                                isSelected = isSelected,
                                modifier = Modifier.weight(1f),
                                focusRequester = if (index == 0) firstChoiceFocusRequester else null,
                                onUp = if (rowIdx == 0) onUp else null,
                                onClick = { onSelectPreset(preset.id) }
                            )
                        }
                    }
                }
            }

            // Section 2: Domain Colors
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "DOMAIN COLOR PALETTE",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = HA_Blue
                        )
                        Text(
                            text = if (currentPreset.equals("custom", ignoreCase = true))
                                "Custom palette is active. Click any domain below to change its color."
                            else
                                "Viewing colors for ${ThemePreset.fromId(currentPreset).displayName}. Click any domain to customize.",
                            fontSize = 12.sp,
                            color = TV_Text_Secondary
                        )
                    }

                    if (!currentPreset.equals("custom", ignoreCase = true)) {
                        Text(
                            text = "Preset Active",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TV_Text_Secondary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0x22FFFFFF))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                for (i in domainList.indices step 2) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        val first = domainList[i]
                        val firstColor = activePalette.getColorForDomain(first.first)
                        val firstHex = activePalette.getColorHexForDomain(first.first)
                        DomainColorCard(
                            title = first.second,
                            icon = first.third,
                            color = firstColor,
                            hex = firstHex,
                            modifier = Modifier.weight(1f),
                            focusRequester = domainFocusRequesters[first.first],
                            onClick = {
                                if (!currentPreset.equals("custom", ignoreCase = true)) {
                                    onSelectPreset("custom")
                                }
                                lastTargetedDomain = first.first
                                colorPickerDomainTarget = first.first
                                colorPickerDomainName = first.second
                                colorPickerInitialHex = firstHex
                            }
                        )

                        if (i + 1 < domainList.size) {
                            val second = domainList[i + 1]
                            val secondColor = activePalette.getColorForDomain(second.first)
                            val secondHex = activePalette.getColorHexForDomain(second.first)
                            DomainColorCard(
                                title = second.second,
                                icon = second.third,
                                color = secondColor,
                                hex = secondHex,
                                modifier = Modifier.weight(1f),
                                focusRequester = domainFocusRequesters[second.first],
                                onClick = {
                                    if (!currentPreset.equals("custom", ignoreCase = true)) {
                                        onSelectPreset("custom")
                                    }
                                    lastTargetedDomain = second.first
                                    colorPickerDomainTarget = second.first
                                    colorPickerDomainName = second.second
                                    colorPickerInitialHex = secondHex
                                }
                            )
                        } else {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        if (colorPickerDomainTarget != null) {
            TVColorPickerDialog(
                domainName = colorPickerDomainName,
                currentHex = colorPickerInitialHex,
                onSelectColor = { hex ->
                    colorPickerDomainTarget?.let { dom ->
                        onSelectCustomColor(dom, hex)
                    }
                    colorPickerDomainTarget = null
                },
                onDismiss = { colorPickerDomainTarget = null }
            )
        }
    }
}

@Composable
fun ThemePresetCard(
    preset: ThemePreset,
    palette: DomainColorPalette,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    onUp: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.04f else 1.0f,
        animationSpec = tween(durationMillis = 80, easing = FastOutSlowInEasing),
        label = "preset_scale"
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
            .dpadKeyHandler(onSelect = onClick, onUp = onUp)
            .focusable(interactionSource = interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(14.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = preset.displayName,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isFocused || isSelected) Color.White else Color(0xFFE2E8F0)
                )
                if (isSelected) {
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(HA_Blue),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Selected",
                            tint = Color.White,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                }
            }

            Text(
                text = preset.description,
                fontSize = 11.sp,
                color = TV_Text_Secondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val previewColors = listOf(
                    palette.getColorForDomain("light"),
                    palette.getColorForDomain("switch"),
                    palette.getColorForDomain("climate"),
                    palette.getColorForDomain("camera"),
                    palette.getOffBackgroundColor(),
                    palette.getIconColor()
                )
                previewColors.forEach { color ->
                    Box(
                        modifier = Modifier
                            .size(14.dp)
                            .clip(CircleShape)
                            .background(color)
                            .border(1.dp, Color(0x44FFFFFF), CircleShape)
                    )
                }
            }
        }
    }
}

@Composable
fun DomainColorCard(
    title: String,
    icon: ImageVector,
    color: Color,
    hex: String,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.03f else 1.0f,
        animationSpec = tween(durationMillis = 80, easing = FastOutSlowInEasing),
        label = "domain_card_scale"
    )

    val focusModifier = if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier

    Box(
        modifier = modifier
            .then(focusModifier)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(12.dp))
            .background(if (isFocused) TV_Surface_Focused else Color(0xFF1E293B))
            .border(
                width = if (isFocused) 2.2.dp else 1.dp,
                color = if (isFocused) TV_Border_Focused else Color(0x33475569),
                shape = RoundedCornerShape(12.dp)
            )
            .dpadKeyHandler(onSelect = onClick)
            .focusable(interactionSource = interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(color.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = color,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Text(
                    text = title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (isFocused) Color.White else Color(0xFFE2E8F0)
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = hex.uppercase(),
                    fontSize = 11.sp,
                    color = TV_Text_Secondary,
                    fontWeight = FontWeight.SemiBold
                )
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(color)
                        .border(1.5.dp, if (isFocused) Color.White else Color(0x66FFFFFF), CircleShape)
                )
            }
        }
    }
}

@Composable
fun TVColorPickerDialog(
    domainName: String,
    currentHex: String,
    onSelectColor: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val swatches = listOf(
        // Row 1: Warm / Ambers / Reds
        "#FFC043", "#F59E0B", "#EA580C", "#FF453A", "#EF4444", "#DC2626",
        // Row 2: Pinks / Purples / Violets
        "#FF2D55", "#EC4899", "#D946EF", "#BF5AF2", "#9333EA", "#7C3AED",
        // Row 3: Indigos / Blues / Cyans
        "#6366F1", "#3B82F6", "#007AFF", "#0284C7", "#00F0FF", "#06B6D4",
        // Row 4: Teals / Greens / Earth
        "#0D9488", "#10B981", "#059669", "#34C759", "#D4A373", "#BC6C25",
        // Row 5: Grayscale & Neutrals
        "#FFFFFF", "#E2E8F0", "#94A3B8", "#475569", "#18181B", "#000000"
    )

    val initialSwatchIndex = swatches.indexOfFirst { it.equals(currentHex, ignoreCase = true) }.let {
        if (it >= 0) it else 0
    }
    val swatchFocusRequesters = remember { List(swatches.size) { FocusRequester() } }

    LaunchedEffect(Unit) {
        delay(60)
        try {
            swatchFocusRequesters[initialSwatchIndex].requestFocus()
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
                .width(480.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xFF1E293B))
                .border(1.dp, Color(0x44FFFFFF), RoundedCornerShape(20.dp))
                .padding(24.dp)
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Choose Color",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = domainName,
                            fontSize = 12.sp,
                            color = HA_Blue
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = currentHex.uppercase(),
                            fontSize = 12.sp,
                            color = TV_Text_Secondary
                        )
                        Box(
                            modifier = Modifier
                                .size(22.dp)
                                .clip(CircleShape)
                                .background(DomainColorPalette.parseHexColor(currentHex))
                                .border(1.5.dp, Color.White, CircleShape)
                        )
                    }
                }

                // 5 rows of 6 color swatches
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    for (rowIdx in 0..4) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            for (colIdx in 0..5) {
                                val swatchIndex = rowIdx * 6 + colIdx
                                val hex = swatches[swatchIndex]
                                val swatchColor = DomainColorPalette.parseHexColor(hex)
                                val isCurrent = hex.equals(currentHex, ignoreCase = true)

                                ColorSwatchButton(
                                    color = swatchColor,
                                    hex = hex,
                                    isCurrent = isCurrent,
                                    focusRequester = swatchFocusRequesters[swatchIndex],
                                    onUp = if (rowIdx == 0) { { /* Block escaping UP to tab row */ } } else null,
                                    onDown = if (rowIdx == 4) { { /* Block escaping DOWN */ } } else null,
                                    onLeft = if (colIdx == 0) { { /* Block escaping LEFT */ } } else null,
                                    onRight = if (colIdx == 5) { { /* Block escaping RIGHT */ } } else null,
                                    onClick = { onSelectColor(hex) }
                                )
                            }
                        }
                    }
                }

                Text(
                    text = "Press BACK to cancel",
                    fontSize = 11.sp,
                    color = TV_Text_Secondary,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

@Composable
fun ColorSwatchButton(
    color: Color,
    hex: String,
    isCurrent: Boolean,
    focusRequester: FocusRequester? = null,
    onUp: (() -> Unit)? = null,
    onDown: (() -> Unit)? = null,
    onLeft: (() -> Unit)? = null,
    onRight: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.25f else 1.0f,
        animationSpec = tween(durationMillis = 70, easing = FastOutSlowInEasing),
        label = "swatch_scale"
    )

    val focusMod = if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier

    Box(
        modifier = focusMod
            .size(46.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .background(color)
            .border(
                width = if (isFocused) 3.dp else if (isCurrent) 2.dp else 1.dp,
                color = if (isFocused) Color.White else if (isCurrent) Color.White.copy(alpha = 0.8f) else Color(0x44FFFFFF),
                shape = CircleShape
            )
            .dpadKeyHandler(
                onSelect = onClick,
                onUp = onUp,
                onDown = onDown,
                onLeft = onLeft,
                onRight = onRight
            )
            .focusable(interactionSource = interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        if (isCurrent) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = if (hex == "#FFFFFF" || hex == "#E2E8F0") Color.Black else Color.White,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

private fun domainIconForKey(key: String): ImageVector = when (key) {
    "light" -> Icons.Default.Lightbulb
    "switch" -> Icons.Default.ToggleOn
    "climate" -> Icons.Default.Thermostat
    "camera" -> Icons.Default.Videocam
    "media_player" -> Icons.Default.PlayCircle
    "cover" -> Icons.Default.Curtains
    "fan" -> Icons.Default.Air
    "lock" -> Icons.Default.Lock
    "vacuum" -> Icons.Default.CleaningServices
    "scene" -> Icons.Default.AutoAwesome
    "sensor" -> Icons.Default.Sensors
    "off_background" -> Icons.Default.DarkMode
    "icon_color" -> Icons.Default.Palette
    else -> Icons.Default.Palette
}
