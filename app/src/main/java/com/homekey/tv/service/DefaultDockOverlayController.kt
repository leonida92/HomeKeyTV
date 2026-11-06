package com.homekey.tv.service

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.lang.ref.WeakReference

class DefaultDockOverlayController(
    private val uptimeProvider: () -> Long = { SystemClock.uptimeMillis() }
) : DockOverlayController {

    private val tag = "DefaultDockOverlayController"
    private var lastToggleTime = -TOGGLE_DEBOUNCE_MS

    private val _isShowing = MutableStateFlow(false)
    override val isShowing: StateFlow<Boolean> = _isShowing.asStateFlow()

    private var serviceRef: WeakReference<RemoteButtonRemapService>? = null
    override val isServiceAttached: Boolean get() = serviceRef?.get() != null

    private var windowManager: WindowManager? = null
    private var rootLayout: OverlayFrameLayout? = null
    private var composeView: ComposeView? = null
    private var lifecycleOwner: OverlayLifecycleOwner? = null
    private var viewModel: PanelViewModel? = null

    override fun attachService(service: RemoteButtonRemapService) {
        serviceRef = WeakReference(service)
    }

    override fun detachService() {
        teardown()
        serviceRef = null
    }

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

    override fun toggle() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post { toggle() }
            return
        }
        val now = uptimeProvider()
        if (now - lastToggleTime < TOGGLE_DEBOUNCE_MS) {
            Log.d(tag, "toggle: ignoring rapid toggle event (${now - lastToggleTime}ms < ${TOGGLE_DEBOUNCE_MS}ms)")
            return
        }
        lastToggleTime = now
        if (_isShowing.value) hide() else show()
    }

    override fun prewarm() {
        val service = serviceRef?.get() ?: return
        Handler(Looper.getMainLooper()).postDelayed({
            if (rootLayout != null && rootLayout?.isAttachedToWindow == true) {
                return@postDelayed
            }
            initWindow(service, initiallyVisible = false)
        }, 500L)
    }

    override fun show(): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post { show() }
            return true
        }
        val service = serviceRef?.get() ?: run {
            Log.w(tag, "show: Cannot show overlay, RemoteButtonRemapService is not attached")
            return false
        }
        if (_isShowing.value) return true
        lastToggleTime = uptimeProvider()

        val layout = rootLayout
        val wm = windowManager
        val cv = composeView
        if (layout != null && layout.isAttachedToWindow && wm != null && cv != null) {
            try {
                wm.updateViewLayout(layout, createLayoutParams(focusable = true))
                layout.visibility = View.VISIBLE
                lifecycleOwner?.resume()
                viewModel?.notifyOverlayShown()
                cv.requestFocus()
                _isShowing.update { true }
                Log.d(tag, "Dock overlay shown (warm instant)")
                return true
            } catch (e: Exception) {
                Log.e(tag, "Error showing warm overlay view, re-initializing", e)
                teardown()
                initWindow(service, initiallyVisible = true)
                return true
            }
        } else {
            initWindow(service, initiallyVisible = true)
            return true
        }
    }

    private fun initWindow(service: RemoteButtonRemapService, initiallyVisible: Boolean) {
        try {
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
                _isShowing.update { true }
                Log.d(tag, "Dock overlay shown (fresh)")
            } else {
                layout.visibility = View.GONE
                val params = createLayoutParams(focusable = false)
                wm.addView(layout, params)
                owner.stop()
                _isShowing.update { false }
                Log.d(tag, "Dock overlay pre-warmed in background")
            }
        } catch (e: Exception) {
            Log.e(tag, "Error initializing overlay window", e)
            teardown()
        }
    }

    override fun handleBack(): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post { handleBack() }
            return _isShowing.value
        }
        val vm = viewModel
        if (vm != null && vm.handleBack()) {
            return true
        }
        if (_isShowing.value) {
            hide()
            return true
        }
        return false
    }

    override fun hide() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post { hide() }
            return
        }
        lastToggleTime = uptimeProvider()
        _isShowing.update { false }
        val layout = rootLayout ?: return
        val wm = windowManager ?: return

        try {
            if (layout.isAttachedToWindow) {
                layout.visibility = View.GONE
                wm.updateViewLayout(layout, createLayoutParams(focusable = false))
                lifecycleOwner?.stop()
                viewModel?.closeEntityDialog()
                viewModel?.exitReorderMode()
                Log.d(tag, "Dock overlay hidden (stopped & kept warm)")
            }
        } catch (e: Exception) {
            Log.e(tag, "Error hiding overlay", e)
            teardown()
        }
    }

    override fun teardown() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post { teardown() }
            return
        }
        _isShowing.update { false }
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

    companion object {
        private const val TOGGLE_DEBOUNCE_MS = 350L
    }
}
