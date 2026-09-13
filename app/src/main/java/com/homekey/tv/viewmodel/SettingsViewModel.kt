package com.homekey.tv.viewmodel

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.homekey.tv.data.api.HAWebSocketClient
import com.homekey.tv.data.api.UpdateManager
import com.homekey.tv.data.local.PreferencesManager
import com.homekey.tv.data.models.AppUpdateState
import com.homekey.tv.data.models.ButtonRemapConfig
import com.homekey.tv.data.models.ConnectionStatus
import com.homekey.tv.data.models.DomainColorPalette
import com.homekey.tv.data.models.HAEntityState
import com.homekey.tv.data.models.InstalledAppInfo
import com.homekey.tv.data.models.PinnedAppConfig
import com.homekey.tv.data.models.ThemePreset
import com.homekey.tv.data.server.WebSetupServerManager
import com.homekey.tv.service.LocalAdbManager
import com.homekey.tv.service.RemoteButtonRemapService
import com.homekey.tv.util.NetworkUtils
import com.homekey.tv.util.QRCodeGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = PreferencesManager.getInstance(application)
    private val wsClient = HAWebSocketClient.getInstance()
    private val updateManager = UpdateManager(application)

    val appVersion: String = try {
        application.packageManager.getPackageInfo(application.packageName, 0).versionName ?: "1.6.6"
    } catch (_: Exception) {
        "1.6.6"
    }

    private val _updateState = MutableStateFlow<AppUpdateState>(AppUpdateState.Idle)
    val updateState: StateFlow<AppUpdateState> = _updateState.asStateFlow()

    val haEnabled: StateFlow<Boolean> = prefs.haEnabled
    val serverUrl: StateFlow<String> = prefs.serverUrl
    val accessToken: StateFlow<String> = prefs.accessToken
    val panelLayout: StateFlow<String> = prefs.panelLayout
    val popupStyle: StateFlow<String> = prefs.popupStyle
    val cameraPopupStyle: StateFlow<String> = prefs.cameraPopupStyle
    val cameraCompactSize: StateFlow<String> = prefs.cameraCompactSize
    val recentAppsEnabled: StateFlow<Boolean> = prefs.recentAppsEnabled
    val recentAppsCount: StateFlow<Int> = prefs.recentAppsCount
    val connectionStatus: StateFlow<ConnectionStatus> = wsClient.connectionStatus
    val themePreset: StateFlow<String> = prefs.themePreset
    val customThemeColors: StateFlow<Map<String, String>> = prefs.customThemeColors
    val activePalette: StateFlow<DomainColorPalette> = prefs.activePalette

    // Pairing code shown next to the QR so the phone can authorise /api/save & /api/fetch-entities.
    val pairingPin: StateFlow<String> = prefs.pairingPin

    // Total entity count, re-emitted only when the number actually changes (used for the header
    // badge). Keeping the raw full-state map out of the Settings screen avoids recomposing the
    // whole activity on every HA state_changed event.
    val entityCount: StateFlow<Int> = wsClient.entities
        .map { it.size }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    // Stable, alphabetised entity list used only by the "Toggle HA" target picker. Built off the
    // main thread.
    val sortedEntities: StateFlow<List<HAEntityState>> = wsClient.entities
        .map { map -> map.values.sortedBy { it.friendlyName.lowercase() } }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val pinnedApps: StateFlow<List<PinnedAppConfig>> = prefs.pinnedApps
    val buttonRemaps: StateFlow<List<ButtonRemapConfig>> = prefs.buttonRemaps
    val isAccessibilityEnabled: StateFlow<Boolean> = RemoteButtonRemapService.isServiceRunning
    val learnedKey: StateFlow<Pair<Int, String>?> = RemoteButtonRemapService.lastLearnedKeyCode

    private val _adbSetupState = MutableStateFlow<AdbSetupState>(AdbSetupState.Idle)
    val adbSetupState: StateFlow<AdbSetupState> = _adbSetupState.asStateFlow()

    private val _installedApps = MutableStateFlow<List<InstalledAppInfo>>(emptyList())
    val installedApps: StateFlow<List<InstalledAppInfo>> = _installedApps.asStateFlow()

    // Decoded app icons, keyed by package name. Decoding happens once on the IO thread in
    // loadInstalledApps() so the grid never converts Drawables during composition.
    private val _appIcons = MutableStateFlow<Map<String, Bitmap>>(emptyMap())
    val appIcons: StateFlow<Map<String, Bitmap>> = _appIcons.asStateFlow()

    private val _setupUrl = MutableStateFlow<String?>(null)
    val setupUrl: StateFlow<String?> = _setupUrl.asStateFlow()

    private val _qrCodeBitmap = MutableStateFlow<Bitmap?>(null)
    val qrCodeBitmap: StateFlow<Bitmap?> = _qrCodeBitmap.asStateFlow()

    init {
        if (prefs.haEnabled.value) {
            loadSetupQr()
        }
        loadInstalledApps()
    }

    fun setHaEnabled(enabled: Boolean) {
        prefs.setHaEnabled(enabled)
        if (enabled) {
            WebSetupServerManager.start(prefs) {
                if (prefs.isConfigured && prefs.haEnabled.value) {
                    wsClient.connect(prefs.serverUrl.value, prefs.accessToken.value)
                }
            }
            if (prefs.isConfigured) {
                wsClient.connect(prefs.serverUrl.value, prefs.accessToken.value)
            }
            loadSetupQr()
        } else {
            WebSetupServerManager.stop()
            wsClient.disconnect()
        }
    }

    fun loadSetupQr() {
        viewModelScope.launch(Dispatchers.Default) {
            val ip = withContext(Dispatchers.IO) { NetworkUtils.getLocalIpAddress() }
            if (ip != null) {
                val url = "http://$ip:8124"
                _setupUrl.value = url
                // QR generation is a per-pixel loop; never run it on the main thread.
                _qrCodeBitmap.value = QRCodeGenerator.generateQRCode(url, 400)
            }
        }
    }

    fun loadInstalledApps() {
        viewModelScope.launch(Dispatchers.IO) {
            val pm = getApplication<Application>().packageManager
            val myPkg = getApplication<Application>().packageName
            val apps = mutableListOf<InstalledAppInfo>()
            val seenPackages = mutableSetOf<String>()

            // 1. Query Leanback Launcher (TV apps)
            val leanbackIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
            }
            val leanbackList = pm.queryIntentActivities(leanbackIntent, 0)
            for (info in leanbackList) {
                val pkg = info.activityInfo.packageName
                if (pkg != myPkg && seenPackages.add(pkg)) {
                    apps.add(
                        InstalledAppInfo(
                            packageName = pkg,
                            appName = info.loadLabel(pm).toString(),
                            icon = info.loadIcon(pm)
                        )
                    )
                }
            }

            // 2. Query Standard Launcher (sideloaded/mobile apps)
            val launcherIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val standardList = pm.queryIntentActivities(launcherIntent, 0)
            for (info in standardList) {
                val pkg = info.activityInfo.packageName
                if (pkg != myPkg && seenPackages.add(pkg)) {
                    apps.add(
                        InstalledAppInfo(
                            packageName = pkg,
                            appName = info.loadLabel(pm).toString(),
                            icon = info.loadIcon(pm)
                        )
                    )
                }
            }

            // 3. Fallback: Query all installed applications with a launch intent
            val installedList = pm.getInstalledApplications(0)
            for (appInfo in installedList) {
                val pkg = appInfo.packageName
                if (pkg != myPkg && !seenPackages.contains(pkg)) {
                    val launchIntent = pm.getLaunchIntentForPackage(pkg)
                    if (launchIntent != null) {
                        seenPackages.add(pkg)
                        apps.add(
                            InstalledAppInfo(
                                packageName = pkg,
                                appName = appInfo.loadLabel(pm).toString(),
                                icon = appInfo.loadIcon(pm)
                            )
                        )
                    }
                }
            }

            val sorted = apps.sortedBy { it.appName.lowercase() }

            // Decode all icons off the main thread once, so the settings grid has no first-frame jank.
            val icons = HashMap<String, Bitmap>()
            for (app in sorted) {
                app.icon?.let { drawable ->
                    try {
                        drawable.toBitmapSafe()?.let { icons[app.packageName] = it }
                    } catch (e: Exception) {
                        // Ignore a single bad icon
                    }
                }
            }
            _appIcons.value = icons
            _installedApps.value = sorted
        }
    }

    private fun Drawable.toBitmapSafe(): Bitmap? {
        return if (this is BitmapDrawable && bitmap != null) {
            bitmap
        } else {
            val width = if (intrinsicWidth > 0) intrinsicWidth else 96
            val height = if (intrinsicHeight > 0) intrinsicHeight else 96
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            setBounds(0, 0, canvas.width, canvas.height)
            draw(canvas)
            bitmap
        }
    }

    fun setPanelLayout(layout: String) {
        prefs.setPanelLayout(layout)
    }

    fun setPopupStyle(style: String) {
        prefs.setPopupStyle(style)
    }

    fun setCameraPopupStyle(style: String) {
        prefs.setCameraPopupStyle(style)
    }

    fun setCameraCompactSize(size: String) {
        prefs.setCameraCompactSize(size)
    }

    fun setRecentAppsEnabled(enabled: Boolean) {
        prefs.setRecentAppsEnabled(enabled)
    }

    fun setRecentAppsCount(count: Int) {
        prefs.setRecentAppsCount(count)
    }

    fun setThemePreset(preset: String) {
        prefs.setThemePreset(preset)
    }

    fun setCustomDomainColor(domain: String, hex: String) {
        prefs.setCustomDomainColor(domain, hex)
    }

    fun setCustomDomainColors(colors: Map<String, String>) {
        prefs.setCustomDomainColors(colors)
    }

    fun togglePinnedApp(packageName: String, appName: String) {
        prefs.togglePinnedApp(packageName, appName)
    }

    fun saveOrUpdateButtonRemap(config: ButtonRemapConfig) {
        prefs.saveOrUpdateButtonRemap(config)
    }

    fun removeButtonRemap(keyCode: Int) {
        prefs.removeButtonRemap(keyCode)
    }

    fun setLearnMode(active: Boolean) {
        RemoteButtonRemapService.setLearnMode(active)
    }

    fun clearLearnedKey() {
        RemoteButtonRemapService.clearLearnedKey()
    }

    fun checkForUpdates() {
        viewModelScope.launch {
            _updateState.value = AppUpdateState.Checking
            val result = updateManager.checkForUpdates(appVersion)
            _updateState.value = result
        }
    }

    fun downloadAndInstallUpdate(downloadUrl: String) {
        viewModelScope.launch {
            _updateState.value = AppUpdateState.Downloading(0)
            val result = updateManager.downloadApk(downloadUrl) { progress ->
                _updateState.value = AppUpdateState.Downloading(progress)
            }
            result.onSuccess { apkFile ->
                _updateState.value = AppUpdateState.ReadyToInstall(apkFile)
                updateManager.promptInstallApk(apkFile)
            }.onFailure { error ->
                _updateState.value = AppUpdateState.Error(error.message ?: "Download failed")
            }
        }
    }

    fun installApk(file: java.io.File) {
        updateManager.promptInstallApk(file)
    }

    fun openReleaseUrl(url: String) {
        updateManager.openBrowserUrl(url)
    }

    fun resetUpdateState() {
        _updateState.value = AppUpdateState.Idle
    }

    fun enableAccessibilityViaAdb(port: Int = LocalAdbManager.DEFAULT_PORT) {
        if (_adbSetupState.value is AdbSetupState.Connecting) return
        viewModelScope.launch(Dispatchers.IO) {
            _adbSetupState.value = AdbSetupState.Connecting(
                "Connecting to local ADB (127.0.0.1:$port)...\nIf prompted on screen, check 'Always allow' and press OK."
            )
            val context = getApplication<Application>()

            // 1. Try direct Secure Settings write first if WRITE_SECURE_SETTINGS is already granted
            if (LocalAdbManager.tryEnableViaSecureSettings(context)) {
                for (i in 1..10) {
                    if (RemoteButtonRemapService.isServiceRunning.value) {
                        _adbSetupState.value = AdbSetupState.Success(
                            "Permissions active! Accessibility service is connected."
                        )
                        return@launch
                    }
                    kotlinx.coroutines.delay(100)
                }
            }

            // 2. Perform ADB setup to grant permissions, bypass restricted settings, and bind service
            val result = LocalAdbManager.enableAccessibilityService(
                context = context,
                port = port,
                onConnecting = {
                    _adbSetupState.value = AdbSetupState.Connecting(
                        "Connecting to local ADB (127.0.0.1:$port)...\nIf prompted on screen, check 'Always allow' and press OK."
                    )
                }
            )

            when (result) {
                is LocalAdbManager.AdbResult.Success -> {
                    // Poll for service to bind up to 3 seconds
                    var isBound = RemoteButtonRemapService.isServiceRunning.value
                    for (i in 1..30) {
                        if (isBound) break
                        kotlinx.coroutines.delay(100)
                        isBound = RemoteButtonRemapService.isServiceRunning.value
                    }
                    if (isBound) {
                        _adbSetupState.value = AdbSetupState.Success(
                            "Permissions granted! Accessibility service is now active."
                        )
                    } else {
                        _adbSetupState.value = AdbSetupState.Success(
                            "Permissions configured. Service should connect shortly."
                        )
                    }
                }
                is LocalAdbManager.AdbResult.AdbDisabled -> {
                    _adbSetupState.value = AdbSetupState.Error(result.message)
                }
                is LocalAdbManager.AdbResult.AuthTimeout -> {
                    _adbSetupState.value = AdbSetupState.Error(result.message)
                }
                is LocalAdbManager.AdbResult.Error -> {
                    _adbSetupState.value = AdbSetupState.Error(result.message)
                }
            }
        }
    }

    fun resetAdbSetupState() {
        _adbSetupState.value = AdbSetupState.Idle
    }
}

sealed class AdbSetupState {
    object Idle : AdbSetupState()
    data class Connecting(val message: String) : AdbSetupState()
    data class Success(val message: String) : AdbSetupState()
    data class Error(val message: String) : AdbSetupState()
}
