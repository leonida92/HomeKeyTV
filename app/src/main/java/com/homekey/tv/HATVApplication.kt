package com.homekey.tv

import android.app.Application
import com.homekey.tv.data.api.DefaultHomeAssistantRepository
import com.homekey.tv.data.api.HomeAssistantRepository
import com.homekey.tv.data.api.OkHttpHATransport
import com.homekey.tv.data.local.DataStorePreferencesRepository
import com.homekey.tv.data.local.PreferencesRepository
import com.homekey.tv.data.server.WebSetupServerManager
import com.homekey.tv.service.CameraPipOverlayController
import com.homekey.tv.service.DefaultCameraPipOverlayController
import com.homekey.tv.service.DefaultDockOverlayController
import com.homekey.tv.service.DockOverlayController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class HATVApplication : Application() {

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

        val server = preferencesRepository.serverConfig.value

        if (server.haEnabled) {
            if (server.isConfigured) {
                haRepository.connect(server.serverUrl, server.accessToken)
            }

            // Keep WebSetupServer running in background on port 8124 for instant phone access
            WebSetupServerManager.start(preferencesRepository) {
                val current = preferencesRepository.serverConfig.value
                if (current.isConfigured && current.haEnabled) {
                    haRepository.connect(current.serverUrl, current.accessToken)
                }
            }
        }
    }

    companion object {
        lateinit var preferencesRepository: PreferencesRepository
            private set
    }
}
