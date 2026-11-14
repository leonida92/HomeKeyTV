package com.homekey.tv.viewmodel

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.homekey.tv.data.api.AppInstaller
import com.homekey.tv.data.api.AppUpdateRepository
import com.homekey.tv.data.api.HomeAssistantRepository
import com.homekey.tv.data.api.UpdateManager
import com.homekey.tv.data.local.DataStorePreferencesRepository
import com.homekey.tv.data.local.DockConfig
import com.homekey.tv.data.local.PreferencesRepository
import com.homekey.tv.data.local.RecentAppsConfig
import com.homekey.tv.data.local.ServerConfig
import com.homekey.tv.data.local.ThemeConfig
import com.homekey.tv.data.models.AppUpdateState
import com.homekey.tv.data.models.ButtonRemapConfig
import com.homekey.tv.data.models.ConnectionStatus
import com.homekey.tv.data.models.HAEntityState
import com.homekey.tv.data.models.InstalledAppInfo
import com.homekey.tv.data.models.PinnedAppConfig
import com.homekey.tv.service.AdbAccessibilityProvisioner
import com.homekey.tv.service.LocalAdbManager
import com.homekey.tv.service.RemoteInputCoordinator
import com.homekey.tv.util.AndroidInstalledAppsProvider
import com.homekey.tv.util.DefaultSetupQrProvider
import com.homekey.tv.util.InstalledAppsProvider
import com.homekey.tv.util.SetupQrProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

class SettingsViewModel(
    application: Application,
    private val prefs: PreferencesRepository,
    private val haRepo: HomeAssistantRepository = HomeAssistantRepository.getInstance(),
    private val remoteInputCoordinator: RemoteInputCoordinator = RemoteInputCoordinator.getInstance(),
    private val adbProvisioner: AdbAccessibilityProvisioner = AdbAccessibilityProvisioner.getInstance(),
    appUpdateRepository: AppUpdateRepository? = null,
    appInstaller: AppInstaller? = null,
    private val installedAppsProvider: InstalledAppsProvider = AndroidInstalledAppsProvider(application),
    private val setupQrProvider: SetupQrProvider = DefaultSetupQrProvider,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : AndroidViewModel(application) {

    constructor(application: Application) : this(
        application,
        DataStorePreferencesRepository.getInstance(application)
    )

    constructor(application: Application, prefs: PreferencesRepository) : this(
        application,
        prefs,
        HomeAssistantRepository.getInstance()
    )

    private val defaultUpdateManager: UpdateManager? =
        if (appUpdateRepository == null || appInstaller == null) UpdateManager(application) else null
    private val appUpdateRepository: AppUpdateRepository = appUpdateRepository ?: defaultUpdateManager!!
    private val appInstaller: AppInstaller = appInstaller ?: defaultUpdateManager!!

    val appVersion: String = try {
        application.packageManager?.getPackageInfo(application.packageName, 0)?.versionName ?: "1.6.6"
    } catch (_: Exception) {
        "1.6.6"
    }

    private val _updateState = MutableStateFlow<AppUpdateState>(AppUpdateState.Idle)
    val updateState: StateFlow<AppUpdateState> = _updateState.asStateFlow()

    val serverConfig: StateFlow<ServerConfig> = prefs.serverConfig
    val dockConfig: StateFlow<DockConfig> = prefs.dockConfig
    val themeConfig: StateFlow<ThemeConfig> = prefs.themeConfig
    val recentAppsConfig: StateFlow<RecentAppsConfig> = prefs.recentAppsConfig

    val activeServerUrl: StateFlow<String> = haRepo.activeServerUrl
    val connectionStatus: StateFlow<ConnectionStatus> = haRepo.connectionStatus

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
    val isAccessibilityEnabled: StateFlow<Boolean> = remoteInputCoordinator.isServiceRunning
    val volumeFixEnabled: StateFlow<Boolean> = prefs.volumeFixEnabled
    val learnedKey: StateFlow<Pair<Int, String>?> = remoteInputCoordinator.learnedKey

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
            loadSetupQr()
        }
    }

    fun loadSetupQr() {
        viewModelScope.launch {
            val ip = setupQrProvider.getLocalIpAddress()
            if (ip != null) {
                val url = "http://$ip:8124"
                _setupUrl.update { url }
                _qrCodeBitmap.update { setupQrProvider.generateQrCode(url, 400) }
            }
        }
    }

    fun loadInstalledApps() {
        viewModelScope.launch {
            val snapshot = installedAppsProvider.loadInstalledApps()
            _appIcons.update { snapshot.icons }
            _installedApps.update { snapshot.apps }
        }
    }

    fun setDockLayout(layout: String) {
        viewModelScope.launch {
            prefs.updateDockConfig { it.copy(layout = layout) }
        }
    }

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
        remoteInputCoordinator.setLearnMode(active)
    }

    fun clearLearnedKey() {
        remoteInputCoordinator.clearLearnedKey()
    }

    fun checkForUpdates() {
        viewModelScope.launch {
            _updateState.update { AppUpdateState.Checking }
            val result = appUpdateRepository.checkForUpdates(appVersion)
            _updateState.update { result }
        }
    }

    fun downloadAndInstallUpdate(downloadUrl: String) {
        viewModelScope.launch {
            _updateState.update { AppUpdateState.Downloading(0) }
            val result = appUpdateRepository.downloadApk(downloadUrl) { progress ->
                _updateState.update { AppUpdateState.Downloading(progress) }
            }
            result.onSuccess { apkFile ->
                _updateState.update { AppUpdateState.ReadyToInstall(apkFile) }
                appInstaller.promptInstallApk(apkFile)
            }.onFailure { error ->
                _updateState.update { AppUpdateState.Error(error.message ?: "Download failed") }
            }
        }
    }

    fun installApk(file: File) {
        appInstaller.promptInstallApk(file)
    }

    fun openReleaseUrl(url: String) {
        appInstaller.openBrowserUrl(url)
    }

    fun resetUpdateState() {
        _updateState.update { AppUpdateState.Idle }
    }

    fun enableAccessibilityViaAdb(port: Int = LocalAdbManager.DEFAULT_PORT) {
        if (_adbSetupState.value is AdbSetupState.Connecting) return
        viewModelScope.launch(ioDispatcher) {
            _adbSetupState.update {
                AdbSetupState.Connecting(
                    "Connecting to local ADB (127.0.0.1:$port)...\nIf prompted on screen, check 'Always allow' and press OK."
                )
            }
            val context = getApplication<Application>()

            if (adbProvisioner.tryEnableViaSecureSettings(context)) {
                for (i in 1..10) {
                    if (remoteInputCoordinator.isServiceRunning.value) {
                        _adbSetupState.update {
                            AdbSetupState.Success(
                                "Permissions active! Accessibility service is connected."
                            )
                        }
                        return@launch
                    }
                    delay(100)
                }
            }

            val result = adbProvisioner.enableAccessibilityService(
                context = context,
                port = port,
                onConnecting = {
                    _adbSetupState.update {
                        AdbSetupState.Connecting(
                            "Connecting to local ADB (127.0.0.1:$port)...\nIf prompted on screen, check 'Always allow' and press OK."
                        )
                    }
                },
                onStatusUpdate = { status ->
                    _adbSetupState.update { AdbSetupState.Connecting(status) }
                }
            )

            when (result) {
                is LocalAdbManager.AdbResult.Success -> {
                    var isBound = remoteInputCoordinator.isServiceRunning.value
                    for (i in 1..30) {
                        if (isBound) break
                        delay(100)
                        isBound = remoteInputCoordinator.isServiceRunning.value
                    }
                    if (isBound) {
                        _adbSetupState.update {
                            AdbSetupState.Success(
                                "Permissions granted! Accessibility service is now active."
                            )
                        }
                    } else {
                        _adbSetupState.update {
                            AdbSetupState.Success(
                                "Permissions configured. Service should connect shortly."
                            )
                        }
                    }
                }
                is LocalAdbManager.AdbResult.AdbDisabled -> {
                    _adbSetupState.update { AdbSetupState.Error(result.message) }
                }
                is LocalAdbManager.AdbResult.AuthTimeout -> {
                    _adbSetupState.update { AdbSetupState.Error(result.message) }
                }
                is LocalAdbManager.AdbResult.Error -> {
                    _adbSetupState.update { AdbSetupState.Error(result.message) }
                }
            }
        }
    }

    fun resetAdbSetupState() {
        _adbSetupState.update { AdbSetupState.Idle }
    }
}

sealed class AdbSetupState {
    object Idle : AdbSetupState()
    data class Connecting(val message: String) : AdbSetupState()
    data class Success(val message: String) : AdbSetupState()
    data class Error(val message: String) : AdbSetupState()
}
