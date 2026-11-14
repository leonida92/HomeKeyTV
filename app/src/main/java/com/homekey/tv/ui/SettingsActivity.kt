package com.homekey.tv.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.homekey.tv.service.RemoteInputCoordinator
import com.homekey.tv.ui.settings.SettingsScreen
import com.homekey.tv.ui.theme.HomeAssistantTVTheme
import com.homekey.tv.viewmodel.SettingsViewModel

class SettingsActivity : ComponentActivity() {

    private val viewModel: SettingsViewModel by viewModels()
    private val remoteInputCoordinator: RemoteInputCoordinator by lazy { RemoteInputCoordinator.getInstance() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            HomeAssistantTVTheme {
                Surface(
                    modifier = Modifier.fillMaxSize()
                ) {
                    SettingsScreen(
                        viewModel = viewModel,
                        onBack = { finish() },
                        onOpenAccessibilitySettings = {
                            try {
                                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                            } catch (_: Exception) {}
                        }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        remoteInputCoordinator.setSettingsForeground(true)
    }

    override fun onPause() {
        super.onPause()
        remoteInputCoordinator.setSettingsForeground(false)
    }
}
