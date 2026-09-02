package com.homeassistant.tv

import android.app.Application
import android.util.Log
import com.homeassistant.tv.data.api.HAWebSocketClient
import com.homeassistant.tv.data.local.PreferencesManager
import com.homeassistant.tv.data.server.WebSetupServer

class HATVApplication : Application() {

    private var webServer: WebSetupServer? = null

    override fun onCreate() {
        super.onCreate()
        val prefs = PreferencesManager.getInstance(this)

        if (prefs.isConfigured) {
            HAWebSocketClient.getInstance().connect(prefs.serverUrl.value, prefs.accessToken.value)
        }

        // Keep WebSetupServer running in background on port 8124 for instant phone access
        try {
            webServer = WebSetupServer(8124, prefs) {
                if (prefs.isConfigured) {
                    HAWebSocketClient.getInstance().connect(prefs.serverUrl.value, prefs.accessToken.value)
                }
            }
            webServer?.start()
            Log.d("HATVApplication", "Web setup server started on port 8124")
        } catch (e: Exception) {
            Log.e("HATVApplication", "Failed to start web server on 8124", e)
        }
    }
}
