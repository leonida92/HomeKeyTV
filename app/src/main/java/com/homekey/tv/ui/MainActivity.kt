package com.homekey.tv.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.homekey.tv.service.DockOverlayManager
import com.homekey.tv.service.RemoteButtonRemapService
import com.homekey.tv.ui.panel.PanelOverlayScreen
import com.homekey.tv.ui.theme.HomeAssistantTVTheme
import com.homekey.tv.viewmodel.PanelViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: PanelViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Zero-duration transition to prevent Android 14 window flicker
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }

        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.setFormat(android.graphics.PixelFormat.TRANSLUCENT)
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        onBackPressedDispatcher.addCallback(this) {
            handleBackAction()
        }

        val service = RemoteButtonRemapService.instance
        if (service != null) {
            DockOverlayManager.show(service)
            finish()
            if (Build.VERSION.SDK_INT >= 34) {
                overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
            } else {
                @Suppress("DEPRECATION")
                overridePendingTransition(0, 0)
            }
            return
        }

        setContent {
            HomeAssistantTVTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = androidx.compose.ui.graphics.Color.Transparent
                ) {
                    PanelOverlayScreen(
                        viewModel = viewModel,
                        onOpenSettings = {
                            val intent = Intent(this, SettingsActivity::class.java).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                            }
                            startActivity(intent)
                        },
                        onDismiss = {
                            dismissOverlay()
                        }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.connectToHomeAssistant()
    }

    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_ESCAPE) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) {
                handleBackAction()
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun handleBackAction() {
        if (viewModel.isReorderMode.value) {
            viewModel.exitReorderMode()
        } else if (viewModel.activeDialogEntity.value != null) {
            viewModel.closeEntityDialog()
        } else {
            dismissOverlay()
        }
    }

    private fun dismissOverlay() {
        finish()
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }
}
