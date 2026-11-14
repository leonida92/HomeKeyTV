package com.homekey.tv.ui.panel

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.homekey.tv.data.models.DomainColorPalette
import com.homekey.tv.data.models.LocalThemePalette
import com.homekey.tv.ui.theme.TV_Border_Focused
import com.homekey.tv.ui.theme.TV_Surface_Focused
import com.homekey.tv.ui.theme.TV_Text_Secondary

@Composable
internal fun MinimalBarContainer(
    content: @Composable RowScope.() -> Unit
) {
    val palette = LocalThemePalette.current
    val offColor = palette.getOffBackgroundColor()
    Row(
        modifier = Modifier
            .wrapContentWidth()
            .height(58.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(offColor)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        content = content
    )
}

@Composable
internal fun MinimalCardDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Color(0x22FFFFFF))
    )
}

@Composable
internal fun MinimalVerticalCardContainer(
    content: @Composable ColumnScope.() -> Unit
) {
    val palette = LocalThemePalette.current
    val offColor = palette.getOffBackgroundColor()
    Column(
        modifier = Modifier
            .width(240.dp)
            .wrapContentHeight()
            .clip(RoundedCornerShape(18.dp))
            .background(offColor)
            .padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content
    )
}

@Composable
internal fun MinimalChip(
    text: String,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    selectedColor: Color? = null,
    unselectedColor: Color = Color(0x332C3852),
    unselectedBorderColor: Color = Color.Transparent,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.10f else 1.0f,
        animationSpec = spring(dampingRatio = 0.75f, stiffness = 400f),
        label = "chip_scale"
    )

    val palette = LocalThemePalette.current
    val activeAccent = selectedColor ?: palette.getColorForDomain("climate")

    val bgColor by animateColorAsState(
        targetValue = when {
            isFocused -> TV_Surface_Focused
            isSelected -> activeAccent
            else -> unselectedColor
        },
        label = "chip_bg"
    )

    val borderColor by animateColorAsState(
        targetValue = when {
            isFocused -> TV_Border_Focused
            else -> unselectedBorderColor
        },
        label = "chip_border"
    )
    val borderWidth = if (isFocused) 2.dp else if (unselectedBorderColor != Color.Transparent) 1.dp else 0.dp

    Box(
        modifier = modifier
            .scale(scale)
            .height(34.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(bgColor)
            .border(borderWidth, borderColor, RoundedCornerShape(10.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .focusable(interactionSource = interactionSource)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        val chipTextColor = when {
            isSelected && !DomainColorPalette.isColorDark(activeAccent) -> Color(0xFF0F172A)
            isSelected || isFocused || unselectedBorderColor != Color.Transparent -> Color.White
            else -> TV_Text_Secondary
        }
        Text(
            text = text,
            fontSize = 11.sp,
            fontWeight = if (isSelected || isFocused) FontWeight.Bold else FontWeight.Medium,
            color = chipTextColor
        )
    }
}

@Composable
internal fun MinimalIconButton(
    icon: ImageVector,
    description: String,
    modifier: Modifier = Modifier,
    tint: Color = TV_Text_Secondary,
    containerColor: Color = Color(0x221E293B),
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.12f else 1.0f,
        animationSpec = spring(dampingRatio = 0.75f, stiffness = 400f),
        label = "min_icon_scale"
    )

    Box(
        modifier = modifier
            .scale(scale)
            .size(34.dp)
            .clip(CircleShape)
            .background(if (isFocused) TV_Surface_Focused else containerColor)
            .border(
                width = if (isFocused) 2.dp else 0.dp,
                color = if (isFocused) TV_Border_Focused else Color.Transparent,
                shape = CircleShape
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .focusable(interactionSource = interactionSource),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = if (isFocused) Color.White else tint,
            modifier = Modifier.size(18.dp)
        )
    }
}
