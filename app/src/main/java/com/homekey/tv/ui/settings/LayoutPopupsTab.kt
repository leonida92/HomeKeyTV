package com.homekey.tv.ui.settings

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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewSidebar
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.VerticalSplit
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.homekey.tv.data.local.DockConfig
import com.homekey.tv.ui.theme.HA_Blue
import com.homekey.tv.ui.theme.TV_Border_Focused
import com.homekey.tv.ui.theme.TV_Surface_Focused
import com.homekey.tv.ui.theme.TV_Text_Secondary

@Composable
fun LayoutStyleView(
    currentLayout: String,
    popupStyle: String,
    cameraCompactSize: String = DockConfig.CAMERA_COMPACT_SIZE_MEDIUM,
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
                    isSelected = currentLayout == DockConfig.LAYOUT_BOTTOM,
                    onClick = { onSelectLayout(DockConfig.LAYOUT_BOTTOM) },
                    modifier = Modifier.weight(1f),
                    onUp = onUp,
                    focusRequester = firstChoiceFocusRequester
                )

                LayoutChoiceCard(
                    title = "Left Dock",
                    subtitle = "Vertical capsule on left screen edge",
                    icon = Icons.Default.VerticalSplit,
                    isSelected = currentLayout == DockConfig.LAYOUT_LEFT,
                    onClick = { onSelectLayout(DockConfig.LAYOUT_LEFT) },
                    modifier = Modifier.weight(1f),
                    onUp = onUp
                )

                LayoutChoiceCard(
                    title = "Right Dock",
                    subtitle = "Vertical capsule on right screen edge",
                    icon = Icons.AutoMirrored.Filled.ViewSidebar,
                    isSelected = currentLayout == DockConfig.LAYOUT_RIGHT,
                    onClick = { onSelectLayout(DockConfig.LAYOUT_RIGHT) },
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
                    isSelected = popupStyle == DockConfig.POPUP_STYLE_MINIMAL,
                    onClick = { onSelectPopupStyle(DockConfig.POPUP_STYLE_MINIMAL) },
                    modifier = Modifier.weight(1f)
                )

                LayoutChoiceCard(
                    title = "Centered Modal",
                    subtitle = "Full modal dialog centered on screen with dimmed backdrop",
                    icon = Icons.Default.Fullscreen,
                    isSelected = popupStyle == DockConfig.POPUP_STYLE_CENTERED,
                    onClick = { onSelectPopupStyle(DockConfig.POPUP_STYLE_CENTERED) },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Section 3: Compact Camera Preview Size (shown only when Compact Dock Preview is selected)
        if (popupStyle == DockConfig.POPUP_STYLE_MINIMAL) {
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
                        isSelected = cameraCompactSize == DockConfig.CAMERA_COMPACT_SIZE_SMALL,
                        onClick = { onSelectCameraCompactSize(DockConfig.CAMERA_COMPACT_SIZE_SMALL) },
                        modifier = Modifier.weight(1f)
                    )

                    LayoutChoiceCard(
                        title = "Medium",
                        subtitle = "Balanced adaptive preview matching camera feed (Default)",
                        icon = Icons.Default.Tv,
                        isSelected = cameraCompactSize == DockConfig.CAMERA_COMPACT_SIZE_MEDIUM,
                        onClick = { onSelectCameraCompactSize(DockConfig.CAMERA_COMPACT_SIZE_MEDIUM) },
                        modifier = Modifier.weight(1f)
                    )

                    LayoutChoiceCard(
                        title = "Large",
                        subtitle = "Spacious adaptive preview matching camera feed",
                        icon = Icons.Default.Tv,
                        isSelected = cameraCompactSize == DockConfig.CAMERA_COMPACT_SIZE_LARGE,
                        onClick = { onSelectCameraCompactSize(DockConfig.CAMERA_COMPACT_SIZE_LARGE) },
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
