package com.homekey.tv

import android.app.Application
import com.homekey.tv.data.api.HAWebSocketClient
import com.homekey.tv.data.local.DataStorePreferencesRepository
import com.homekey.tv.data.local.PreferencesRepository
import com.homekey.tv.data.server.WebSetupServerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class HATVApplication : Application() {

    private val applicationScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        preferencesRepository = DataStorePreferencesRepository.getInstance(this)
        val server = preferencesRepository.serverConfig.value

        // Reconcile monitored entities within HAWebSocketClient
        HAWebSocketClient.getInstance().observeMonitoredEntities(applicationScope, preferencesRepository)

        if (server.haEnabled) {
            if (server.isConfigured) {
                HAWebSocketClient.getInstance().connect(server.serverUrl, server.accessToken)
            }

            // Keep WebSetupServer running in background on port 8124 for instant phone access
            WebSetupServerManager.start(preferencesRepository) {
                val current = preferencesRepository.serverConfig.value
                if (current.isConfigured && current.haEnabled) {
                    HAWebSocketClient.getInstance().connect(current.serverUrl, current.accessToken)
                }
            }
        }
    }

    companion object {
        lateinit var preferencesRepository: PreferencesRepository
            private set
    }
}
