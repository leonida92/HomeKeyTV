package com.homekey.tv

import android.app.Application
import com.homekey.tv.data.api.HAWebSocketClient
import com.homekey.tv.data.local.PreferencesManager
import com.homekey.tv.data.server.WebSetupServerManager

class HATVApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        val prefs = PreferencesManager.getInstance(this)

        if (prefs.haEnabled.value) {
            if (prefs.isConfigured) {
                HAWebSocketClient.getInstance().connect(prefs.serverUrl.value, prefs.accessToken.value)
            }

            // Keep WebSetupServer running in background on port 8124 for instant phone access
            WebSetupServerManager.start(prefs) {
                if (prefs.isConfigured && prefs.haEnabled.value) {
                    HAWebSocketClient.getInstance().connect(prefs.serverUrl.value, prefs.accessToken.value)
                }
            }
        }

    }
}
