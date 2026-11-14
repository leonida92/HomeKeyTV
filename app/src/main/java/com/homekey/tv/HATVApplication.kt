package com.homekey.tv

import android.app.Application
import com.homekey.tv.data.api.DefaultHomeAssistantRepository
import com.homekey.tv.data.api.HomeAssistantRepository
import com.homekey.tv.data.api.OkHttpHATransport
import com.homekey.tv.data.local.DataStorePreferencesRepository
import com.homekey.tv.data.local.PreferencesRepository
import com.homekey.tv.data.server.WebSetupServerManager
import com.homekey.tv.service.AdbAccessibilityProvisioner
import com.homekey.tv.service.CameraPipOverlayController
import com.homekey.tv.service.DefaultAdbAccessibilityProvisioner
import com.homekey.tv.service.DefaultCameraPipOverlayController
import com.homekey.tv.service.DefaultDockOverlayController
import com.homekey.tv.service.DefaultRemoteInputCoordinator
import com.homekey.tv.service.DockOverlayController
import com.homekey.tv.service.RemoteInputCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class HATVApplication : Application() {

    private data class ServerConnectionKey(
        val serverUrl: String,
        val fallbackServerUrl: String,
        val accessToken: String,
        val haEnabled: Boolean
    )

    private val applicationScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        preferencesRepository = DataStorePreferencesRepository.getInstance(this)

        val haTransport = OkHttpHATransport()
        val haRepository = DefaultHomeAssistantRepository(
            transport = haTransport,
            prefs = preferencesRepository,
            scope = applicationScope
        )
        HomeAssistantRepository.initialize(haRepository)

        val dockOverlayController = DefaultDockOverlayController()
        DockOverlayController.initialize(dockOverlayController)

        val cameraPipOverlayController = DefaultCameraPipOverlayController(
            prefs = preferencesRepository,
            haRepository = haRepository
        )
        CameraPipOverlayController.initialize(cameraPipOverlayController)

        val remoteInputCoordinator = DefaultRemoteInputCoordinator()
        RemoteInputCoordinator.initialize(remoteInputCoordinator)

        val adbProvisioner = DefaultAdbAccessibilityProvisioner()
        AdbAccessibilityProvisioner.initialize(adbProvisioner)

        WebSetupServerManager.setApplicationContext(this)

        applicationScope.launch {
            preferencesRepository.serverConfig
                .map { config ->
                    ServerConnectionKey(
                        serverUrl = config.serverUrl,
                        fallbackServerUrl = config.fallbackServerUrl,
                        accessToken = config.accessToken,
                        haEnabled = config.haEnabled
                    )
                }
                .distinctUntilChanged()
                .collect { key ->
                    if (key.haEnabled) {
                        // Keep WebSetupServer running in background on port 8124 for instant phone access
                        WebSetupServerManager.start(preferencesRepository, this@HATVApplication)
                        val hasEndpoint = key.serverUrl.isNotBlank() || key.fallbackServerUrl.isNotBlank()
                        if (hasEndpoint && key.accessToken.isNotBlank()) {
                            haRepository.connect(key.serverUrl, key.fallbackServerUrl, key.accessToken)
                        } else {
                            haRepository.disconnect()
                        }
                    } else {
                        WebSetupServerManager.stop()
                        haRepository.disconnect()
                    }
                }
        }
    }

    companion object {
        lateinit var preferencesRepository: PreferencesRepository
            private set
    }
}
