package com.homeassistant.tv.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.homeassistant.tv.service.RemoteButtonRemapService
import com.homeassistant.tv.ui.settings.SettingsScreen
import com.homeassistant.tv.ui.theme.HomeAssistantTVTheme
import com.homeassistant.tv.viewmodel.SettingsViewModel

class SettingsActivity : ComponentActivity() {

    private val viewModel: SettingsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            HomeAssistantTVTheme {
                Surface(
                    modifier = Modifier.fillMaxSize()
                ) {
                    SettingsScreen(
                        viewModel = viewModel,
                        onBack = { finish() }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        RemoteButtonRemapService.setSettingsForeground(true)
    }

    override fun onPause() {
        super.onPause()
        RemoteButtonRemapService.setSettingsForeground(false)
    }
}
