package com.homekey.tv.data.models

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

val LocalThemePalette = staticCompositionLocalOf { DomainColorPalette.CLASSIC }

@Serializable
data class DomainThemeMetadata(
    val key: String,
    val label: String
)

@Serializable
data class PortalThemePreset(
    val id: String,
    val name: String,
    val desc: String,
    val colors: Map<String, String>
)

@Serializable
data class ThemePortalCatalog(
    val presets: Map<String, PortalThemePreset>,
    val presetOrder: List<String>,
    val domains: List<DomainThemeMetadata>
)

/**
 * Single-source theme catalog shared between Android UI and the mobile web setup portal.
 */
object ThemeCatalog {
    val domainMetadata: List<DomainThemeMetadata> = listOf(
        DomainThemeMetadata("light", "Lights"),
        DomainThemeMetadata("switch", "Switches & Outlets"),
        DomainThemeMetadata("climate", "Climate & AC"),
        DomainThemeMetadata("camera", "Camera Feeds"),
        DomainThemeMetadata("media_player", "Media Players"),
        DomainThemeMetadata("cover", "Covers & Blinds"),
        DomainThemeMetadata("fan", "Fans & Purifiers"),
        DomainThemeMetadata("lock", "Lock Control"),
        DomainThemeMetadata("vacuum", "Robot Vacuums"),
        DomainThemeMetadata("scene", "Scenes & Scripts"),
        DomainThemeMetadata("sensor", "Sensors"),
        DomainThemeMetadata("off_background", "Off-State Background"),
        DomainThemeMetadata("icon_color", "Active Icon Color")
    )

    private val catalogJson = Json { encodeDefaults = true }

    fun toPortalCatalog(): ThemePortalCatalog {
        val presets = linkedMapOf<String, PortalThemePreset>()
        for (preset in ThemePreset.entries) {
            val colors = if (preset == ThemePreset.CUSTOM) {
                emptyMap()
            } else {
                val palette = DomainColorPalette.forPreset(preset)
                domainMetadata.associate { meta ->
                    meta.key to palette.getColorHexForDomain(meta.key)
                }
            }
            presets[preset.id] = PortalThemePreset(
                id = preset.id,
                name = preset.displayName,
                desc = preset.description,
                colors = colors
            )
        }
        return ThemePortalCatalog(
            presets = presets,
            presetOrder = ThemePreset.entries.map { it.id },
            domains = domainMetadata
        )
    }

    fun toPortalCatalogJson(): String = catalogJson.encodeToString(toPortalCatalog())
}

fun toPortalCatalogJson(): String = ThemeCatalog.toPortalCatalogJson()

enum class ThemePreset(val id: String, val displayName: String, val description: String) {
    CLASSIC("classic", "HomeKey Classic", "Original vivid smart home palette"),
    APPLE("apple", "Cupertino Glow", "Apple HomeKit warm luminous design"),
    CYBERPUNK("cyberpunk", "Cyberpunk Neon", "High-contrast futuristic electric neon"),
    NORDIC("nordic", "Nordic Calm", "Subtle, Scandinavian minimal earth tones"),
    MONOCHROME("monochrome", "Monochrome Luxe", "High-contrast OLED white & platinum"),
    CUSTOM("custom", "Custom Palette", "Personalized custom domain colors");

    companion object {
        fun fromId(id: String?): ThemePreset {
            return entries.find { it.id.equals(id, ignoreCase = true) } ?: CLASSIC
        }
    }
}

@Serializable
data class DomainColorPalette(
    val light: String = "#F59E0B",
    val switch: String = "#0284C7",
    val climate: String = "#EA580C",
    val camera: String = "#6366F1",
    val mediaPlayer: String = "#0D9488",
    val cover: String = "#059669",
    val fan: String = "#0891B2",
    val lock: String = "#10B981",
    val vacuum: String = "#78716C",
    val scene: String = "#9333EA",
    val sensor: String = "#8B5CF6",
    val fallback: String = "#0284C7",
    val offBackground: String = "#1E293B",
    val iconColor: String = "#FFFFFF"
) {
    fun getColorForDomain(domain: String): Color {
        val hex = getColorHexForDomain(domain)
        return parseHexColor(hex, fallback)
    }

    fun getColorHexForDomain(domain: String): String {
        return when (domain) {
            "light" -> light
            "switch", "input_boolean" -> switch
            "climate" -> climate
            "camera" -> camera
            "media_player" -> mediaPlayer
            "cover" -> cover
            "fan" -> fan
            "lock" -> lock
            "vacuum" -> vacuum
            "scene", "script" -> scene
            "sensor", "binary_sensor" -> sensor
            "off_background", "off_state" -> offBackground
            "icon_color", "icon" -> iconColor
            else -> fallback
        }
    }

    fun getOffBackgroundColor(): Color = parseHexColor(offBackground, "#1E293B")

    fun getIconColor(): Color = parseHexColor(iconColor, "#FFFFFF")

    fun withOverride(domain: String, hex: String): DomainColorPalette {
        return when (domain) {
            "light" -> copy(light = hex)
            "switch", "input_boolean" -> copy(switch = hex)
            "climate" -> copy(climate = hex)
            "camera" -> copy(camera = hex)
            "media_player" -> copy(mediaPlayer = hex)
            "cover" -> copy(cover = hex)
            "fan" -> copy(fan = hex)
            "lock" -> copy(lock = hex)
            "vacuum" -> copy(vacuum = hex)
            "scene", "script" -> copy(scene = hex)
            "sensor", "binary_sensor" -> copy(sensor = hex)
            "off_background", "off_state" -> copy(offBackground = hex)
            "icon_color", "icon" -> copy(iconColor = hex)
            else -> copy(fallback = hex)
        }
    }

    fun withOverrides(overrides: Map<String, String>): DomainColorPalette {
        var result = this
        for ((d, h) in overrides) {
            if (h.isNotBlank()) {
                result = result.withOverride(d, h)
            }
        }
        return result
    }

    companion object {
        fun isColorDark(color: Color): Boolean {
            val lum = 0.2126f * color.red + 0.7152f * color.green + 0.0722f * color.blue
            return lum <= 0.5f
        }

        fun parseHexColor(hex: String?, fallbackHex: String = "#0284C7"): Color {
            return try {
                val cleanHex = (hex ?: fallbackHex).trim().removePrefix("#")
                val colorInt = when (cleanHex.length) {
                    6 -> (0xFF000000 or cleanHex.toLong(16)).toInt()
                    8 -> cleanHex.toLong(16).toInt()
                    else -> (0xFF000000 or fallbackHex.removePrefix("#").toLong(16)).toInt()
                }
                Color(colorInt)
            } catch (_: Exception) {
                Color(0xFF0284C7)
            }
        }

        val CLASSIC = DomainColorPalette(
            light = "#F59E0B",
            switch = "#0284C7",
            climate = "#EA580C",
            camera = "#6366F1",
            mediaPlayer = "#0D9488",
            cover = "#059669",
            fan = "#0891B2",
            lock = "#10B981",
            vacuum = "#78716C",
            scene = "#9333EA",
            sensor = "#8B5CF6",
            fallback = "#0284C7",
            offBackground = "#1E293B",
            iconColor = "#FFFFFF"
        )

        val APPLE = DomainColorPalette(
            light = "#FFC043",
            switch = "#007AFF",
            climate = "#FF453A",
            camera = "#FF9500",
            mediaPlayer = "#FF2D55",
            cover = "#34C759",
            fan = "#64D2FF",
            lock = "#30D158",
            vacuum = "#8E8E93",
            scene = "#BF5AF2",
            sensor = "#5E5CE6",
            fallback = "#007AFF",
            offBackground = "#1C1C1E",
            iconColor = "#FFFFFF"
        )

        val CYBERPUNK = DomainColorPalette(
            light = "#FFE600",
            switch = "#00F0FF",
            climate = "#FF0055",
            camera = "#FF1744",
            mediaPlayer = "#00FF66",
            cover = "#39FF14",
            fan = "#00B8FF",
            lock = "#00FF66",
            vacuum = "#64748B",
            scene = "#B000FF",
            sensor = "#FF007F",
            fallback = "#00F0FF",
            offBackground = "#0D0D1A",
            iconColor = "#000000"
        )

        val NORDIC = DomainColorPalette(
            light = "#D4A373",
            switch = "#5B8296",
            climate = "#BC6C25",
            camera = "#9B5B6E",
            mediaPlayer = "#606C38",
            cover = "#588157",
            fan = "#7C98A6",
            lock = "#588157",
            vacuum = "#595959",
            scene = "#8E7C93",
            sensor = "#DDA15E",
            fallback = "#5B8296",
            offBackground = "#21272A",
            iconColor = "#FFFFFF"
        )

        val MONOCHROME = DomainColorPalette(
            light = "#FFFFFF",
            switch = "#E2E8F0",
            climate = "#F1F5F9",
            camera = "#E5E7EB",
            mediaPlayer = "#94A3B8",
            cover = "#A0AEC0",
            fan = "#F8FAFC",
            lock = "#E2E8F0",
            vacuum = "#64748B",
            scene = "#CBD5E1",
            sensor = "#D1D5DB",
            fallback = "#E2E8F0",
            offBackground = "#18181B",
            iconColor = "#000000"
        )

        fun forPreset(preset: ThemePreset): DomainColorPalette {
            return when (preset) {
                ThemePreset.CLASSIC -> CLASSIC
                ThemePreset.APPLE -> APPLE
                ThemePreset.CYBERPUNK -> CYBERPUNK
                ThemePreset.NORDIC -> NORDIC
                ThemePreset.MONOCHROME -> MONOCHROME
                ThemePreset.CUSTOM -> CLASSIC
            }
        }
    }
}