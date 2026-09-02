package com.homeassistant.tv.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = HA_Blue,
    secondary = HA_Yellow_On,
    tertiary = HA_Green_On,
    background = TV_Background,
    surface = TV_Surface,
    onPrimary = Color.White,
    onSecondary = Color.Black,
    onTertiary = Color.White,
    onBackground = TV_Text_Primary,
    onSurface = TV_Text_Primary,
)

@Composable
fun HomeAssistantTVTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}
