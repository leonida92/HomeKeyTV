package com.homekey.tv.data.local

import com.homekey.tv.data.models.ButtonRemapConfig
import com.homekey.tv.data.models.DomainColorPalette
import com.homekey.tv.data.models.PinnedAppConfig
import com.homekey.tv.data.models.PinnedEntityConfig
import com.homekey.tv.data.models.ThemePreset
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Pure Kotlin domain operations for normalizing preference facets, reordering pinned lists,
 * trimming recent apps, and coding HomeKey backups. Shared by [DataStorePreferencesRepository]
 * and test doubles so repository behavior never diverges.
 */
object PreferencesDomainOps {
    const val MIN_RECENT_APPS = 1
    const val MAX_RECENT_APPS = 5

    private val EXCLUDED_EXACT_PACKAGES = setOf(
        "com.homekey.tv",
        "com.android.systemui",
        "android",
        "com.google.android.katniss"
    )

    private val EXCLUDED_PACKAGE_PREFIXES = listOf(
        "com.google.android.apps.tv.launcher",
        "com.google.android.tvlauncher",
        "com.google.android.tungsten.setupwraith",
        "com.android.vending"
    )

    fun normalizeServerConfig(config: ServerConfig): ServerConfig {
        return config.copy(
            serverUrl = config.serverUrl.trim().removeSuffix("/"),
            fallbackServerUrl = config.fallbackServerUrl.trim().removeSuffix("/"),
            accessToken = config.accessToken.trim()
        )
    }

    fun normalizeDockConfig(config: DockConfig): DockConfig {
        return DockConfig(
            layout = DockConfig.normalizeLayout(config.layout),
            popupStyle = DockConfig.normalizePopupStyle(config.popupStyle),
            cameraPopupStyle = DockConfig.normalizeCameraPopupStyle(config.cameraPopupStyle),
            cameraCompactSize = DockConfig.normalizeCameraCompactSize(config.cameraCompactSize)
        )
    }

    fun calculateActivePalette(presetId: String, customColors: Map<String, String>): DomainColorPalette {
        val preset = ThemePreset.fromId(presetId)
        return if (preset == ThemePreset.CUSTOM) {
            DomainColorPalette.CLASSIC.withOverrides(customColors)
        } else {
            DomainColorPalette.forPreset(preset)
        }
    }

    fun normalizeThemeConfig(config: ThemeConfig): ThemeConfig {
        val normalizedPreset = ThemePreset.fromId(config.preset).id
        return ThemeConfig(
            preset = normalizedPreset,
            customColors = config.customColors,
            activePalette = calculateActivePalette(normalizedPreset, config.customColors)
        )
    }

    fun normalizeRecentAppsConfig(config: RecentAppsConfig): RecentAppsConfig {
        val clampedCount = config.maxCount.coerceIn(MIN_RECENT_APPS, MAX_RECENT_APPS)
        val trimmed = config.recentPackages.take(clampedCount)
        return RecentAppsConfig(
            enabled = config.enabled,
            maxCount = clampedCount,
            recentPackages = trimmed
        )
    }

    fun isExcludedRecentPackage(packageName: String): Boolean {
        if (packageName.isBlank()) return true
        if (packageName in EXCLUDED_EXACT_PACKAGES) return true
        return EXCLUDED_PACKAGE_PREFIXES.any { packageName.startsWith(it) }
    }

    /**
     * Returns an updated [RecentAppsConfig] with [packageName] promoted to the front and
     * trimmed to [RecentAppsConfig.maxCount], or `null` when disabled or excluded.
     */
    fun recordRecentApp(config: RecentAppsConfig, packageName: String): RecentAppsConfig? {
        if (!config.enabled) return null
        if (isExcludedRecentPackage(packageName)) return null

        val clampedCount = config.maxCount.coerceIn(MIN_RECENT_APPS, MAX_RECENT_APPS)
        val updated = config.recentPackages.toMutableList().apply {
            remove(packageName)
            add(0, packageName)
        }.take(clampedCount)

        return config.copy(maxCount = clampedCount, recentPackages = updated)
    }

    fun reindexPinnedEntities(entities: List<PinnedEntityConfig>): List<PinnedEntityConfig> {
        return entities.mapIndexed { idx, item -> item.copy(order = idx) }
    }

    fun reindexPinnedApps(apps: List<PinnedAppConfig>): List<PinnedAppConfig> {
        return apps.mapIndexed { idx, item -> item.copy(order = idx) }
    }

    fun movePinnedEntity(
        entities: List<PinnedEntityConfig>,
        entityId: String,
        delta: Int
    ): List<PinnedEntityConfig>? {
        val moved = moveWithWrapAround(entities, delta) { it.entityId == entityId } ?: return null
        return reindexPinnedEntities(moved)
    }

    fun movePinnedApp(
        apps: List<PinnedAppConfig>,
        packageName: String,
        delta: Int
    ): List<PinnedAppConfig>? {
        val moved = moveWithWrapAround(apps, delta) { it.packageName == packageName } ?: return null
        return reindexPinnedApps(moved)
    }

    fun pinEntity(
        entities: List<PinnedEntityConfig>,
        entity: PinnedEntityConfig
    ): List<PinnedEntityConfig> {
        val updated = entities.filterNot { it.entityId == entity.entityId } + entity
        return reindexPinnedEntities(updated)
    }

    fun unpinEntity(
        entities: List<PinnedEntityConfig>,
        entityId: String
    ): List<PinnedEntityConfig> {
        return reindexPinnedEntities(entities.filterNot { it.entityId == entityId })
    }

    fun pinApp(
        apps: List<PinnedAppConfig>,
        app: PinnedAppConfig
    ): List<PinnedAppConfig> {
        val updated = apps.filterNot { it.packageName == app.packageName } + app
        return reindexPinnedApps(updated)
    }

    fun unpinApp(
        apps: List<PinnedAppConfig>,
        packageName: String
    ): List<PinnedAppConfig> {
        return reindexPinnedApps(apps.filterNot { it.packageName == packageName })
    }

    private inline fun <T> moveWithWrapAround(
        items: List<T>,
        delta: Int,
        matches: (T) -> Boolean
    ): List<T>? {
        val currentIndex = items.indexOfFirst(matches)
        if (currentIndex == -1 || items.size <= 1) return null

        val targetIndex = (currentIndex + delta).let {
            if (it < 0) items.size - 1
            else if (it >= items.size) 0
            else it
        }
        if (currentIndex == targetIndex) return null

        val mutable = items.toMutableList()
        val item = mutable.removeAt(currentIndex)
        mutable.add(targetIndex, item)
        return mutable
    }
}

data class RestoredBackupState(
    val serverConfig: ServerConfig,
    val dockConfig: DockConfig,
    val themeConfig: ThemeConfig,
    val recentAppsConfig: RecentAppsConfig,
    val volumeFixEnabled: Boolean,
    val pinnedEntities: List<PinnedEntityConfig>,
    val pinnedApps: List<PinnedAppConfig>,
    val buttonRemaps: List<ButtonRemapConfig>,
    val cleanUrl: String,
    val cleanFallbackUrl: String,
    val cleanToken: String
)

/**
 * Pure codec for serializing and restoring [HomeKeyBackup] payloads with consistent
 * field normalization and legacy alias handling (`panelLayout` -> `dockLayout`).
 */
object HomeKeyBackupCodec {
    val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

    fun exportBackupJson(
        serverConfig: ServerConfig,
        dockConfig: DockConfig,
        themeConfig: ThemeConfig,
        recentAppsConfig: RecentAppsConfig,
        volumeFixEnabled: Boolean,
        pinnedEntities: List<PinnedEntityConfig>,
        pinnedApps: List<PinnedAppConfig>,
        buttonRemaps: List<ButtonRemapConfig>,
        includeToken: Boolean
    ): String {
        val backup = HomeKeyBackup(
            serverUrl = serverConfig.serverUrl,
            fallbackServerUrl = serverConfig.fallbackServerUrl,
            accessToken = if (includeToken) serverConfig.accessToken else null,
            dockLayout = dockConfig.layout,
            panelLayout = dockConfig.layout,
            popupStyle = dockConfig.popupStyle,
            cameraPopupStyle = dockConfig.cameraPopupStyle,
            cameraCompactSize = dockConfig.cameraCompactSize,
            recentAppsEnabled = recentAppsConfig.enabled,
            recentAppsCount = recentAppsConfig.maxCount,
            haEnabled = serverConfig.haEnabled,
            themePreset = themeConfig.preset,
            customThemeColors = themeConfig.customColors,
            volumeFixEnabled = volumeFixEnabled,
            pinnedEntities = pinnedEntities,
            pinnedApps = pinnedApps,
            buttonRemaps = buttonRemaps
        )
        return json.encodeToString(backup)
    }

    fun decodeBackup(
        jsonString: String,
        currentServerConfig: ServerConfig,
        currentRecentAppsConfig: RecentAppsConfig
    ): Result<RestoredBackupState> {
        return try {
            val backup = json.decodeFromString<HomeKeyBackup>(jsonString)
            val cleanUrl = backup.serverUrl.trim().removeSuffix("/")
            val cleanFallbackUrl = backup.fallbackServerUrl.trim().removeSuffix("/")
            val cleanToken = backup.accessToken?.trim()?.ifBlank { null } ?: currentServerConfig.accessToken

            val restoredServer = currentServerConfig.copy(
                serverUrl = if (cleanUrl.isNotBlank()) cleanUrl else currentServerConfig.serverUrl,
                fallbackServerUrl = if (cleanFallbackUrl.isNotBlank()) cleanFallbackUrl else currentServerConfig.fallbackServerUrl,
                accessToken = cleanToken,
                haEnabled = backup.haEnabled
            )
            val restoredDock = PreferencesDomainOps.normalizeDockConfig(
                DockConfig(
                    layout = backup.resolvedDockLayout,
                    popupStyle = backup.popupStyle,
                    cameraPopupStyle = backup.cameraPopupStyle,
                    cameraCompactSize = backup.cameraCompactSize
                )
            )
            val restoredRecent = PreferencesDomainOps.normalizeRecentAppsConfig(
                RecentAppsConfig(
                    enabled = backup.recentAppsEnabled,
                    maxCount = backup.recentAppsCount,
                    recentPackages = currentRecentAppsConfig.recentPackages
                )
            )
            val restoredTheme = PreferencesDomainOps.normalizeThemeConfig(
                ThemeConfig(
                    preset = backup.themePreset,
                    customColors = backup.customThemeColors
                )
            )

            Result.success(
                RestoredBackupState(
                    serverConfig = restoredServer,
                    dockConfig = restoredDock,
                    themeConfig = restoredTheme,
                    recentAppsConfig = restoredRecent,
                    volumeFixEnabled = backup.volumeFixEnabled,
                    pinnedEntities = backup.pinnedEntities,
                    pinnedApps = backup.pinnedApps,
                    buttonRemaps = backup.buttonRemaps,
                    cleanUrl = cleanUrl,
                    cleanFallbackUrl = cleanFallbackUrl,
                    cleanToken = cleanToken
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
