package com.homekey.tv.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import com.homekey.tv.data.api.HomeAssistantRepository
import com.homekey.tv.data.api.MjpegCameraStreamClient
import com.homekey.tv.data.local.PreferencesRepository
import com.homekey.tv.data.models.CameraPipAlert
import com.homekey.tv.ui.panel.CameraPipCard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

interface CameraPipOverlayController {
    fun showPipAlert(context: Context, alert: CameraPipAlert)
    fun dismissPip()

    companion object {
        @Volatile
        private var instance: CameraPipOverlayController? = null

        fun getInstance(): CameraPipOverlayController {
            return instance ?: synchronized(this) {
                instance ?: error("CameraPipOverlayController not initialized")
            }
        }

        fun initialize(controller: CameraPipOverlayController) {
            instance = controller
        }
    }
}

class DefaultCameraPipOverlayController(
    private val prefs: PreferencesRepository,
    private val haRepository: HomeAssistantRepository = HomeAssistantRepository.getInstance()
) : CameraPipOverlayController {

    private val tag = "CameraPipOverlayController"
    private val windowHost = AndroidOverlayWindowHost(tag)
    private var pipDismissJob: Job? = null
    private val pipScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private fun createPipLayoutParams(context: Context): WindowManager.LayoutParams {
        val density = context.resources.displayMetrics.density
        val widthPx = (360 * density).toInt()
        val heightPx = (220 * density).toInt()
        val marginPx = (24 * density).toInt()

        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
            WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS

        val windowType = if (context is AccessibilityService) {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        }

        return WindowManager.LayoutParams(
            widthPx,
            heightPx,
            windowType,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = marginPx
            y = marginPx
            format = PixelFormat.TRANSLUCENT
        }
    }

    override fun showPipAlert(context: Context, alert: CameraPipAlert) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post { showPipAlert(context, alert) }
            return
        }

        dismissPip()

        val fallbackServerUrl = prefs.serverConfig.value.serverUrl
        val initialServerUrl = haRepository.activeServerUrl.value.ifBlank { fallbackServerUrl }
        val accessToken = prefs.serverConfig.value.accessToken
        if (initialServerUrl.isBlank()) return

        val safeDuration = alert.durationSeconds.coerceIn(3, 120)
        val feedFlow = MjpegCameraStreamClient.getInstance().observeStream(
            entityId = alert.entityId,
            serverUrlFlow = haRepository.activeServerUrl.map { it.ifBlank { fallbackServerUrl } },
            accessToken = accessToken,
            maxTargetWidthPx = 960
        )

        val attached = windowHost.attach(
            context = context,
            layoutParams = ::createPipLayoutParams,
            initiallyVisible = true,
            interceptBack = false,
            onBackPressed = {
                dismissPip()
                true
            }
        ) {
            CameraPipCard(
                alert = alert.copy(durationSeconds = safeDuration),
                feedFlow = feedFlow,
                onDismiss = { dismissPip() }
            )
        }

        if (attached) {
            Log.d(tag, "Camera PiP alert shown for ${alert.entityId} (${safeDuration}s)")
            pipDismissJob = pipScope.launch {
                delay(safeDuration * 1000L)
                dismissPip()
            }
        } else {
            dismissPip()
        }
    }

    override fun dismissPip() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post { dismissPip() }
            return
        }
        pipDismissJob?.cancel()
        pipDismissJob = null

        windowHost.detach()
        haRepository.dismissPipAlert()
    }
}
