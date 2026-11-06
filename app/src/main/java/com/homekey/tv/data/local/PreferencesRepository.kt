package com.homekey.tv.data.local

import com.homekey.tv.data.models.ButtonRemapConfig
import com.homekey.tv.data.models.DomainColorPalette
import com.homekey.tv.data.models.PinnedAppConfig
import com.homekey.tv.data.models.PinnedEntityConfig
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

data class ServerConfig(
    val serverUrl: String = "",
    val accessToken: String = "",
    val pairingPin: String = "",
    val haEnabled: Boolean = true
) {
    val isConfigured: Boolean
        get() = haEnabled && serverUrl.isNotBlank() && accessToken.isNotBlank()
}

data class DockConfig(
    val layout: String = LAYOUT_BOTTOM,
    val popupStyle: String = POPUP_STYLE_CENTERED,
    val cameraPopupStyle: String = CAMERA_POPUP_CENTERED,
    val cameraCompactSize: String = CAMERA_COMPACT_SIZE_MEDIUM
) {
    companion object {
        const val LAYOUT_BOTTOM = "DOCK_BOTTOM"
        const val LAYOUT_LEFT = "DOCK_LEFT"
        const val LAYOUT_RIGHT = "DOCK_RIGHT"

        const val POPUP_STYLE_CENTERED = "CENTERED"
        const val POPUP_STYLE_MINIMAL = "MINIMAL"

        const val CAMERA_POPUP_CENTERED = "CENTERED"
        const val CAMERA_POPUP_COMPACT = "COMPACT"

        const val CAMERA_COMPACT_SIZE_SMALL = "SMALL"
        const val CAMERA_COMPACT_SIZE_MEDIUM = "MEDIUM"
        const val CAMERA_COMPACT_SIZE_LARGE = "LARGE"

        fun normalizeLayout(layout: String?): String {
            return when (layout?.uppercase()) {
                "DOCK_LEFT", "LEFT" -> "DOCK_LEFT"
                "DOCK_RIGHT", "RIGHT", "SIDE_PANEL" -> "DOCK_RIGHT"
                else -> "DOCK_BOTTOM"
            }
        }

        fun normalizePopupStyle(style: String?): String {
            return when (style?.uppercase()) {
                POPUP_STYLE_MINIMAL, "COMPACT", "HORIZONTAL", "MINIMAL_DOCK", "DOCK" -> POPUP_STYLE_MINIMAL
                else -> POPUP_STYLE_CENTERED
            }
        }

        fun normalizeCameraPopupStyle(style: String?): String {
            return when (style?.uppercase()) {
                CAMERA_POPUP_COMPACT, "MINIMAL", "DOCK" -> CAMERA_POPUP_COMPACT
                else -> CAMERA_POPUP_CENTERED
            }
        }

        fun normalizeCameraCompactSize(size: String?): String {
            return when (size?.uppercase()) {
                CAMERA_COMPACT_SIZE_SMALL, "S", "SMALL_SIZE" -> CAMERA_COMPACT_SIZE_SMALL
                CAMERA_COMPACT_SIZE_LARGE, "L", "LARGE_SIZE" -> CAMERA_COMPACT_SIZE_LARGE
                else -> CAMERA_COMPACT_SIZE_MEDIUM
            }
        }
    }
}

data class ThemeConfig(
    val preset: String = "classic",
    val customColors: Map<String, String> = emptyMap(),
    val activePalette: DomainColorPalette = DomainColorPalette.CLASSIC
)

data class RecentAppsConfig(
    val enabled: Boolean = false,
    val maxCount: Int = 3,
    val recentPackages: List<String> = emptyList()
)

@Serializable
data class HomeKeyBackup(
    val serverUrl: String = "",
    val accessToken: String? = null,
    val dockLayout: String = "DOCK_BOTTOM",
    val panelLayout: String? = null, // for backwards compatibility with earlier backup files
    val popupStyle: String = "CENTERED",
    val cameraPopupStyle: String = "CENTERED",
    val cameraCompactSize: String = "MEDIUM",
    val recentAppsEnabled: Boolean = true,
    val recentAppsCount: Int = 3,
    val haEnabled: Boolean = true,
    val themePreset: String = "DEFAULT",
    val customThemeColors: Map<String, String> = emptyMap(),
    val volumeFixEnabled: Boolean = false,
    val pinnedEntities: List<PinnedEntityConfig> = emptyList(),
    val pinnedApps: List<PinnedAppConfig> = emptyList(),
    val buttonRemaps: List<ButtonRemapConfig> = emptyList()
) {
    val resolvedDockLayout: String
        get() = panelLayout ?: dockLayout
}

interface PreferencesRepository {
    val serverConfig: StateFlow<ServerConfig>
    val dockConfig: StateFlow<DockConfig>
    val themeConfig: StateFlow<ThemeConfig>
    val recentAppsConfig: StateFlow<RecentAppsConfig>

    val pinnedEntities: StateFlow<List<PinnedEntityConfig>>
    val pinnedApps: StateFlow<List<PinnedAppConfig>>
    val buttonRemaps: StateFlow<List<ButtonRemapConfig>>
    val volumeFixEnabled: StateFlow<Boolean>

    suspend fun updateServerConfig(transform: (ServerConfig) -> ServerConfig)
    suspend fun updateDockConfig(transform: (DockConfig) -> DockConfig)
    suspend fun updateThemeConfig(transform: (ThemeConfig) -> ThemeConfig)
    suspend fun updateRecentAppsConfig(transform: (RecentAppsConfig) -> RecentAppsConfig)

    suspend fun setVolumeFixEnabled(enabled: Boolean)
    suspend fun recordRecentApp(packageName: String)

    suspend fun savePinnedEntities(entities: List<PinnedEntityConfig>)
    suspend fun savePinnedApps(apps: List<PinnedAppConfig>)
    suspend fun saveButtonRemaps(remaps: List<ButtonRemapConfig>)

    suspend fun movePinnedEntity(id: String, delta: Int)
    suspend fun pinEntity(entity: PinnedEntityConfig)
    suspend fun unpinEntity(entityId: String)
    suspend fun pinApp(app: PinnedAppConfig)
    suspend fun unpinApp(packageName: String)

    suspend fun exportBackupJson(includeToken: Boolean = false): String
    suspend fun restoreFromBackupJson(jsonString: String): Result<Unit>
}
