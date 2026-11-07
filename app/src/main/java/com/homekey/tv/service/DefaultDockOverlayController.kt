package com.homekey.tv.service

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.setViewTreeOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalSavedStateRegistryOwner
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.homekey.tv.ui.SettingsActivity
import com.homekey.tv.ui.panel.PanelOverlayScreen
import com.homekey.tv.ui.theme.HomeAssistantTVTheme
import com.homekey.tv.viewmodel.PanelViewModel
import kotlinx.coroutines.flow.StateFlow
import java.lang.ref.WeakReference

/**
 * Android adapter implementing [DockOverlayController] and [WindowOverlayHost].
 * Delegates all state, debounce timing, and back navigation decisions to [DockOverlayStateEngine],
 * and handles Android WindowManager, ComposeView, and Lifecycle mechanics on the UI thread.
 */
class DefaultDockOverlayController(
    engine: DockOverlayStateEngine? = null,
    scheduler: TimeScheduler? = null
) : DockOverlayController, WindowOverlayHost {

    private val tag = "DefaultDockOverlayController"

    private val timeScheduler: TimeScheduler by lazy {
        scheduler ?: try {
            AndroidTimeScheduler(Handler(Looper.getMainLooper()))
        } catch (_: Exception) {
            object : TimeScheduler {
                override fun currentTimeMs(): Long = System.currentTimeMillis()
                override fun schedule(delayMs: Long, token: Any, action: () -> Unit) {}
                override fun cancel(token: Any) {}
            }
        }
    }

    private val stateEngine: DockOverlayStateEngine by lazy {
        engine ?: DefaultDockOverlayStateEngine(this, timeScheduler)
    }

    override val isShowing: StateFlow<Boolean> get() = stateEngine.isShowing
    override val isServiceAttached: Boolean get() = stateEngine.isServiceAttached

    private var serviceRef: WeakReference<RemoteButtonRemapService>? = null

    private var windowManager: WindowManager? = null
    private var rootLayout: OverlayFrameLayout? = null
    private var composeView: ComposeView? = null
    private var lifecycleOwner: OverlayLifecycleOwner? = null
    private var viewModel: PanelViewModel? = null

    private fun isMainThread(): Boolean {
        return try {
            Looper.myLooper() == Looper.getMainLooper()
        } catch (_: Exception) {
            true
        }
    }

    private fun postToMain(action: () -> Unit) {
        try {
            if (isMainThread()) {
                action()
            } else {
                Handler(Looper.getMainLooper()).post(action)
            }
        } catch (_: Exception) {
            action()
        }
    }

    // --- DockOverlayController Facade ---

    override fun attachService(service: RemoteButtonRemapService) {
        serviceRef = WeakReference(service)
        stateEngine.attachService()
    }

    override fun detachService() {
        postToMain {
            stateEngine.detachService()
            serviceRef = null
        }
    }

    override fun show(): Boolean {
        return if (isMainThread()) {
            stateEngine.show()
        } else {
            postToMain { stateEngine.show() }
            true
        }
    }

    override fun hide() {
        postToMain { stateEngine.hide() }
    }

    override fun toggle() {
        postToMain { stateEngine.toggle() }
    }

    override fun prewarm() {
        postToMain { stateEngine.prewarm() }
    }

    override fun teardown() {
        postToMain { stateEngine.teardown() }
    }

    override fun handleBack(): Boolean {
        return if (isMainThread()) {
            stateEngine.handleBack()
        } else {
            postToMain { stateEngine.handleBack() }
            isShowing.value
        }
    }

    // --- WindowOverlayHost Seam Implementation ---

    override fun dismissActiveModal(): Boolean {
        return viewModel?.handleBack() ?: false
    }

    override fun showWindow(): Boolean {
        val service = serviceRef?.get() ?: run {
            Log.w(tag, "showWindow: Cannot show overlay, RemoteButtonRemapService is not attached")
            return false
        }
        val layout = rootLayout
        val wm = windowManager
        val cv = composeView
        if (layout != null && layout.isAttachedToWindow && wm != null && cv != null) {
            return try {
                wm.updateViewLayout(layout, createLayoutParams(focusable = true))
                layout.visibility = View.VISIBLE
                lifecycleOwner?.resume()
                viewModel?.notifyOverlayShown()
                cv.requestFocus()
                Log.d(tag, "Dock overlay shown (warm instant)")
                true
            } catch (e: Exception) {
                Log.e(tag, "Error showing warm overlay view, re-initializing", e)
                teardownWindow()
                initWindow(service, initiallyVisible = true)
            }
        } else {
            return initWindow(service, initiallyVisible = true)
        }
    }

    override fun hideWindow(): Boolean {
        val layout = rootLayout ?: return true
        val wm = windowManager ?: return true

        return try {
            if (layout.isAttachedToWindow) {
                layout.visibility = View.GONE
                wm.updateViewLayout(layout, createLayoutParams(focusable = false))
                lifecycleOwner?.stop()
                viewModel?.closeEntityDialog()
                viewModel?.exitReorderMode()
                Log.d(tag, "Dock overlay hidden (stopped & kept warm)")
            }
            true
        } catch (e: Exception) {
            Log.e(tag, "Error hiding overlay", e)
            teardownWindow()
            false
        }
    }

    override fun prewarmWindow(): Boolean {
        val service = serviceRef?.get() ?: return false
        if (rootLayout != null && rootLayout?.isAttachedToWindow == true) {
            return true
        }
        return initWindow(service, initiallyVisible = false)
    }

    override fun teardownWindow() {
        try {
            val wm = windowManager
            val layout = rootLayout
            if (wm != null && layout != null && layout.isAttachedToWindow) {
                layout.onBackAction = null
                wm.removeViewImmediate(layout)
            }
        } catch (e: Exception) {
            Log.e(tag, "Error tearing down overlay", e)
        } finally {
            rootLayout = null
            composeView = null
            lifecycleOwner?.destroy()
            lifecycleOwner = null
            viewModel = null
            windowManager = null
            Log.d(tag, "Dock overlay torn down")
        }
    }

    // --- Private Window Initialization ---

    private fun createLayoutParams(focusable: Boolean): WindowManager.LayoutParams {
        val flags = if (focusable) {
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS
        } else {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS
        }
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            format = PixelFormat.TRANSLUCENT
        }
    }

    private fun initWindow(service: RemoteButtonRemapService, initiallyVisible: Boolean): Boolean {
        return try {
            val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            windowManager = wm

            val owner = OverlayLifecycleOwner {
                handleBack()
            }
            lifecycleOwner = owner

            val vm = ViewModelProvider(
                owner,
                ViewModelProvider.AndroidViewModelFactory.getInstance(service.application)
            )[PanelViewModel::class.java]
            viewModel = vm
            vm.connectToHomeAssistant()

            val layout = OverlayFrameLayout(service).apply {
                isFocusable = false
                isFocusableInTouchMode = false
                descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    defaultFocusHighlightEnabled = false
                }
                onBackAction = {
                    handleBack()
                }
            }
            rootLayout = layout

            layout.setViewTreeLifecycleOwner(owner)
            layout.setViewTreeViewModelStoreOwner(owner)
            layout.setViewTreeSavedStateRegistryOwner(owner)
            layout.setViewTreeOnBackPressedDispatcherOwner(owner)

            val cv = ComposeView(service).apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    defaultFocusHighlightEnabled = false
                }
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool)
                setContent {
                    CompositionLocalProvider(
                        LocalSavedStateRegistryOwner provides owner,
                        LocalOnBackPressedDispatcherOwner provides owner
                    ) {
                        HomeAssistantTVTheme {
                            Surface(
                                modifier = Modifier.fillMaxSize(),
                                color = androidx.compose.ui.graphics.Color.Transparent
                            ) {
                                PanelOverlayScreen(
                                    viewModel = vm,
                                    onOpenSettings = {
                                        hide()
                                        val intent = Intent(service, SettingsActivity::class.java).apply {
                                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                                        }
                                        service.startActivity(intent)
                                    },
                                    onDismiss = {
                                        hide()
                                    }
                                )
                            }
                        }
                    }
                }
            }
            composeView = cv
            layout.addView(
                cv,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )

            if (initiallyVisible) {
                layout.visibility = View.VISIBLE
                val params = createLayoutParams(focusable = true)
                wm.addView(layout, params)
                owner.resume()
                vm.notifyOverlayShown()
                cv.requestFocus()
                Log.d(tag, "Dock overlay shown (fresh)")
            } else {
                layout.visibility = View.GONE
                val params = createLayoutParams(focusable = false)
                wm.addView(layout, params)
                owner.stop()
                Log.d(tag, "Dock overlay pre-warmed in background")
            }
            true
        } catch (e: Exception) {
            Log.e(tag, "Error initializing overlay window", e)
            teardownWindow()
            false
        }
    }
}
