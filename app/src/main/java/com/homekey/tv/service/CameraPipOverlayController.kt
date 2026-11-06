package com.homekey.tv.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.setViewTreeOnBackPressedDispatcherOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalSavedStateRegistryOwner
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.homekey.tv.data.api.HomeAssistantRepository
import com.homekey.tv.data.local.PreferencesRepository
import com.homekey.tv.data.models.CameraPipAlert
import com.homekey.tv.ui.panel.CameraPipCard
import com.homekey.tv.ui.theme.HomeAssistantTVTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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
    private var windowManager: WindowManager? = null
    private var pipRootLayout: FrameLayout? = null
    private var pipComposeView: ComposeView? = null
    private var pipLifecycleOwner: OverlayLifecycleOwner? = null
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

        val uiContext = if (context is AccessibilityService) {
            context
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                context.createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
            } catch (_: Exception) {
                context
            }
        } else {
            context
        }

        val wm = (uiContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager) ?: return
        windowManager = wm

        val serverUrl = prefs.serverConfig.value.serverUrl
        val accessToken = prefs.serverConfig.value.accessToken
        if (serverUrl.isBlank()) return

        val safeDuration = alert.durationSeconds.coerceIn(3, 120)

        try {
            val owner = OverlayLifecycleOwner {
                dismissPip()
            }
            pipLifecycleOwner = owner

            val layout = FrameLayout(uiContext)
            pipRootLayout = layout

            layout.setViewTreeLifecycleOwner(owner)
            layout.setViewTreeViewModelStoreOwner(owner)
            layout.setViewTreeSavedStateRegistryOwner(owner)
            layout.setViewTreeOnBackPressedDispatcherOwner(owner)

            val cv = ComposeView(uiContext).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool)
                setContent {
                    CompositionLocalProvider(
                        LocalSavedStateRegistryOwner provides owner,
                        LocalOnBackPressedDispatcherOwner provides owner
                    ) {
                        HomeAssistantTVTheme {
                            CameraPipCard(
                                alert = alert.copy(durationSeconds = safeDuration),
                                serverUrl = serverUrl,
                                accessToken = accessToken,
                                onDismiss = { dismissPip() }
                            )
                        }
                    }
                }
            }
            pipComposeView = cv
            layout.addView(cv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

            val params = createPipLayoutParams(uiContext)
            wm.addView(layout, params)
            owner.resume()
            Log.d(tag, "Camera PiP alert shown for ${alert.entityId} (${safeDuration}s)")

            pipDismissJob = pipScope.launch {
                delay(safeDuration * 1000L)
                dismissPip()
            }
        } catch (e: Exception) {
            Log.e(tag, "Error displaying PiP alert", e)
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

        val wm = windowManager
        val layout = pipRootLayout
        if (wm != null && layout != null && layout.isAttachedToWindow) {
            try {
                wm.removeViewImmediate(layout)
            } catch (e: Exception) {
                Log.e(tag, "Error removing PiP view", e)
            }
        }
        pipRootLayout = null
        pipComposeView = null
        pipLifecycleOwner?.destroy()
        pipLifecycleOwner = null
        haRepository.dismissPipAlert()
    }
}
