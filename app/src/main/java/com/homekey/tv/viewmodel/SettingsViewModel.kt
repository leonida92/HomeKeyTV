package com.homekey.tv.viewmodel

import android.annotation.SuppressLint
import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.homekey.tv.data.api.HomeAssistantRepository
import com.homekey.tv.data.api.UpdateManager
import com.homekey.tv.data.local.DataStorePreferencesRepository
import com.homekey.tv.data.local.PreferencesRepository
import com.homekey.tv.data.models.AppUpdateState
import com.homekey.tv.data.models.ButtonRemapConfig
import com.homekey.tv.data.models.ConnectionStatus
import com.homekey.tv.data.models.DomainColorPalette
import com.homekey.tv.data.models.HAEntityState
import com.homekey.tv.data.models.InstalledAppInfo
import com.homekey.tv.data.models.PinnedAppConfig
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsViewModel(
    application: Application,
    private val prefs: PreferencesRepository,
    private val haRepo: HomeAssistantRepository = HomeAssistantRepository.getInstance()
) : AndroidViewModel(application) {

    constructor(application: Application) : this(
        application,
        DataStorePreferencesRepository.getInstance(application),
        HomeAssistantRepository.getInstance()
    )

    constructor(application: Application, prefs: PreferencesRepository) : this(
        application,
        prefs,
        HomeAssistantRepository.getInstance()
    )

    private val updateManager = UpdateManager(application)

    val appVersion: String = try {
        application.packageManager.getPackageInfo(application.packageName, 0).versionName ?: "1.6.6"
    } catch (_: Exception) {
        "1.6.6"
    }

    private val _updateState = MutableStateFlow<AppUpdateState>(AppUpdateState.Idle)
    val updateState: StateFlow<AppUpdateState> = _updateState.asStateFlow()

    val haEnabled: StateFlow<Boolean> = prefs.serverConfig.map { it.haEnabled }
        .stateIn(viewModelScope, SharingStarted.Eagerly, prefs.serverConfig.value.haEnabled)
    val serverUrl: StateFlow<String> = prefs.serverConfig.map { it.serverUrl }
        .stateIn(viewModelScope, SharingStarted.Eagerly, prefs.serverConfig.value.serverUrl)
    val accessToken: StateFlow<String> = prefs.serverConfig.map { it.accessToken }
        .stateIn(viewModelScope, SharingStarted.Eagerly, prefs.serverConfig.value.accessToken)
    val pairingPin: StateFlow<String> = prefs.serverConfig.map { it.pairingPin }
        .stateIn(viewModelScope, SharingStarted.Eagerly, prefs.serverConfig.value.pairingPin)

    val dockLayout: StateFlow<String> = prefs.dockConfig.map { it.layout }
        .stateIn(viewModelScope, SharingStarted.Eagerly, prefs.dockConfig.value.layout)
    val panelLayout: StateFlow<String> get() = dockLayout
    val popupStyle: StateFlow<String> = prefs.dockConfig.map { it.popupStyle }
        .stateIn(viewModelScope, SharingStarted.Eagerly, prefs.dockConfig.value.popupStyle)
    val cameraPopupStyle: StateFlow<String> = prefs.dockConfig.map { it.cameraPopupStyle }
        .stateIn(viewModelScope, SharingStarted.Eagerly, prefs.dockConfig.value.cameraPopupStyle)
    val cameraCompactSize: StateFlow<String> = prefs.dockConfig.map { it.cameraCompactSize }
        .stateIn(viewModelScope, SharingStarted.Eagerly, prefs.dockConfig.value.cameraCompactSize)

    val recentAppsEnabled: StateFlow<Boolean> = prefs.recentAppsConfig.map { it.enabled }
        .stateIn(viewModelScope, SharingStarted.Eagerly, prefs.recentAppsConfig.value.enabled)
    val recentAppsCount: StateFlow<Int> = prefs.recentAppsConfig.map { it.maxCount }
        .stateIn(viewModelScope, SharingStarted.Eagerly, prefs.recentAppsConfig.value.maxCount)

    val connectionStatus: StateFlow<ConnectionStatus> = haRepo.connectionStatus
    val themePreset: StateFlow<String> = prefs.themeConfig.map { it.preset }
        .stateIn(viewModelScope, SharingStarted.Eagerly, prefs.themeConfig.value.preset)
    val customThemeColors: StateFlow<Map<String, String>> = prefs.themeConfig.map { it.customColors }
        .stateIn(viewModelScope, SharingStarted.Eagerly, prefs.themeConfig.value.customColors)
    val activePalette: StateFlow<DomainColorPalette> = prefs.themeConfig.map { it.activePalette }
        .stateIn(viewModelScope, SharingStarted.Eagerly, prefs.themeConfig.value.activePalette)

    // Total entity count, re-emitted only when the number actually changes
    val entityCount: StateFlow<Int> = haRepo.entities
        .map { it.size }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    // Stable, alphabetised entity list used only by the "Toggle HA" target picker
    val sortedEntities: StateFlow<List<HAEntityState>> = haRepo.entities
        .map { map -> map.values.sortedBy { it.friendlyName.lowercase() } }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val pinnedApps: StateFlow<List<PinnedAppConfig>> = prefs.pinnedApps
    val buttonRemaps: StateFlow<List<ButtonRemapConfig>> = prefs.buttonRemaps
    val isAccessibilityEnabled: StateFlow<Boolean> = RemoteButtonRemapService.isServiceRunning
    val volumeFixEnabled: StateFlow<Boolean> = prefs.volumeFixEnabled
    val learnedKey: StateFlow<Pair<Int, String>?> = RemoteButtonRemapService.lastLearnedKeyCode

    private val _adbSetupState = MutableStateFlow<AdbSetupState>(AdbSetupState.Idle)
    val adbSetupState: StateFlow<AdbSetupState> = _adbSetupState.asStateFlow()

    private val _installedApps = MutableStateFlow<List<InstalledAppInfo>>(emptyList())
    val installedApps: StateFlow<List<InstalledAppInfo>> = _installedApps.asStateFlow()

    private val _appIcons = MutableStateFlow<Map<String, Bitmap>>(emptyMap())
    val appIcons: StateFlow<Map<String, Bitmap>> = _appIcons.asStateFlow()

    private val _setupUrl = MutableStateFlow<String?>(null)
    val setupUrl: StateFlow<String?> = _setupUrl.asStateFlow()

    private val _qrCodeBitmap = MutableStateFlow<Bitmap?>(null)
    val qrCodeBitmap: StateFlow<Bitmap?> = _qrCodeBitmap.asStateFlow()

    init {
        if (prefs.serverConfig.value.haEnabled) {
            loadSetupQr()
        }
        loadInstalledApps()
    }

    fun setHaEnabled(enabled: Boolean) {
        viewModelScope.launch {
            prefs.updateServerConfig { it.copy(haEnabled = enabled) }
        }
        if (enabled) {
            WebSetupServerManager.start(prefs) {
                val s = prefs.serverConfig.value
                if (s.isConfigured && s.haEnabled) {
                    haRepo.connect(s.serverUrl, s.accessToken)
                }
            }
            val s = prefs.serverConfig.value
            if (s.isConfigured) {
                haRepo.connect(s.serverUrl, s.accessToken)
            }
            loadSetupQr()
        } else {
            WebSetupServerManager.stop()
            haRepo.disconnect()
        }
    }

    fun loadSetupQr() {
        viewModelScope.launch(Dispatchers.Default) {
            val ip = withContext(Dispatchers.IO) { NetworkUtils.getLocalIpAddress() }
            if (ip != null) {
                val url = "http://$ip:8124"
                _setupUrl.value = url
                _qrCodeBitmap.value = QRCodeGenerator.generateQRCode(url, 400)
            }
        }
    }

    @SuppressLint("QueryPermissionsNeeded")
    fun loadInstalledApps() {
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>() ?: return@launch
            val pm = app.packageManager ?: return@launch
            val myPkg = app.packageName ?: ""
            val apps = mutableListOf<InstalledAppInfo>()
            val seenPackages = mutableSetOf<String>()

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
            val icons = HashMap<String, Bitmap>()
            for (app in sorted) {
                app.icon?.let { drawable ->
                    try {
                        drawable.toBitmapSafe()?.let { icons[app.packageName] = it }
                    } catch (_: Exception) {}
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

    fun setDockLayout(layout: String) {
        viewModelScope.launch {
            prefs.updateDockConfig { it.copy(layout = layout) }
        }
    }

    fun setPanelLayout(layout: String) = setDockLayout(layout)

    fun setPopupStyle(style: String) {
        viewModelScope.launch {
            prefs.updateDockConfig { it.copy(popupStyle = style) }
        }
    }

    fun setCameraPopupStyle(style: String) {
        viewModelScope.launch {
            prefs.updateDockConfig { it.copy(cameraPopupStyle = style) }
        }
    }

    fun setCameraCompactSize(size: String) {
        viewModelScope.launch {
            prefs.updateDockConfig { it.copy(cameraCompactSize = size) }
        }
    }

    fun setRecentAppsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            prefs.updateRecentAppsConfig { it.copy(enabled = enabled) }
        }
    }

    fun setRecentAppsCount(count: Int) {
        viewModelScope.launch {
            prefs.updateRecentAppsConfig { it.copy(maxCount = count) }
        }
    }

    fun setThemePreset(preset: String) {
        viewModelScope.launch {
            prefs.updateThemeConfig { it.copy(preset = preset) }
        }
    }

    fun setCustomDomainColor(domain: String, hex: String) {
        viewModelScope.launch {
            prefs.updateThemeConfig { it.copy(customColors = it.customColors + (domain to hex)) }
        }
    }

    fun setCustomDomainColors(colors: Map<String, String>) {
        viewModelScope.launch {
            prefs.updateThemeConfig { it.copy(customColors = it.customColors + colors) }
        }
    }

    fun togglePinnedApp(packageName: String, appName: String) {
        viewModelScope.launch {
            val current = prefs.pinnedApps.value.toMutableList()
            val index = current.indexOfFirst { it.packageName == packageName }
            if (index != -1) {
                current.removeAt(index)
            } else {
                current.add(PinnedAppConfig(packageName = packageName, appName = appName, order = current.size))
            }
            prefs.savePinnedApps(current)
        }
    }

    fun saveOrUpdateButtonRemap(config: ButtonRemapConfig) {
        viewModelScope.launch {
            val current = prefs.buttonRemaps.value.toMutableList()
            val index = current.indexOfFirst { it.keyCode == config.keyCode && it.appScope == config.appScope }
            if (index != -1) {
                current[index] = config
            } else {
                current.add(config)
            }
            prefs.saveButtonRemaps(current)
        }
    }

    fun removeButtonRemap(keyCode: Int) {
        viewModelScope.launch {
            val current = prefs.buttonRemaps.value.filterNot { it.keyCode == keyCode }
            prefs.saveButtonRemaps(current)
        }
    }

    fun removeButtonRemap(config: ButtonRemapConfig) {
        viewModelScope.launch {
            val current = prefs.buttonRemaps.value.filterNot {
                it.keyCode == config.keyCode && it.appScope == config.appScope
            }
            prefs.saveButtonRemaps(current)
        }
    }

    fun setVolumeFixEnabled(enabled: Boolean) {
        viewModelScope.launch {
            prefs.setVolumeFixEnabled(enabled)
        }
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

            val result = LocalAdbManager.enableAccessibilityService(
                context = context,
                port = port,
                onConnecting = {
                    _adbSetupState.value = AdbSetupState.Connecting(
                        "Connecting to local ADB (127.0.0.1:$port)...\nIf prompted on screen, check 'Always allow' and press OK."
                    )
                },
                onStatusUpdate = { status ->
                    _adbSetupState.value = AdbSetupState.Connecting(status)
                }
            )

            when (result) {
                is LocalAdbManager.AdbResult.Success -> {
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
