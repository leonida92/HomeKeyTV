package com.homekey.tv.service

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.setViewTreeOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalSavedStateRegistryOwner
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.homekey.tv.ui.SettingsActivity
import com.homekey.tv.ui.panel.PanelOverlayScreen
import com.homekey.tv.ui.theme.HomeAssistantTVTheme
import com.homekey.tv.viewmodel.PanelViewModel

class OverlayLifecycleOwner :
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner,
    OnBackPressedDispatcherOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()
    private val backPressedDispatcher = OnBackPressedDispatcher {
        DockOverlayManager.hide()
    }
    private var isDestroyed = false

    init {
        savedStateRegistryController.performAttach()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry
    override val onBackPressedDispatcher: OnBackPressedDispatcher get() = backPressedDispatcher

    fun pause() {
        if (!isDestroyed && lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        }
    }

    fun resume() {
        if (isDestroyed) return
        if (lifecycleRegistry.currentState == Lifecycle.State.CREATED) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        }
        if (lifecycleRegistry.currentState == Lifecycle.State.STARTED) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }
    }

    fun destroy() {
        if (isDestroyed) return
        isDestroyed = true
        if (lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        }
        if (lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        }
        if (lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.CREATED)) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        }
        store.clear()
    }
}

class OverlayFrameLayout(context: Context) : FrameLayout(context) {
    var onBackAction: (() -> Unit)? = null

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) {
                onBackAction?.invoke()
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}

object DockOverlayManager {
    private const val TAG = "DockOverlayManager"

    private var windowManager: WindowManager? = null
    private var rootLayout: OverlayFrameLayout? = null
    private var composeView: ComposeView? = null
    private var lifecycleOwner: OverlayLifecycleOwner? = null
    private var viewModel: PanelViewModel? = null

    val isShowing: Boolean
        get() = rootLayout != null && rootLayout?.isAttachedToWindow == true && rootLayout?.visibility == View.VISIBLE

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

    fun toggle(service: RemoteButtonRemapService) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post { toggle(service) }
            return
        }
        if (isShowing) hide() else show(service)
    }

    fun prewarm(service: RemoteButtonRemapService) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post { prewarm(service) }
            return
        }
        if (rootLayout != null && rootLayout?.isAttachedToWindow == true) {
            return
        }
        initWindow(service, initiallyVisible = false)
    }

    fun show(service: RemoteButtonRemapService) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post { show(service) }
            return
        }
        if (isShowing) return

        val layout = rootLayout
        val wm = windowManager
        val cv = composeView
        if (layout != null && layout.isAttachedToWindow && wm != null && cv != null) {
            try {
                wm.updateViewLayout(layout, createLayoutParams(focusable = true))
                layout.visibility = View.VISIBLE
                lifecycleOwner?.resume()
                viewModel?.notifyOverlayShown()
                layout.requestFocus()
                Log.d(TAG, "DockOverlayManager shown (warm instant)")
            } catch (e: Exception) {
                Log.e(TAG, "Error showing warm overlay view, re-initializing", e)
                teardown()
                initWindow(service, initiallyVisible = true)
            }
        } else {
            initWindow(service, initiallyVisible = true)
        }
    }

    private fun initWindow(service: RemoteButtonRemapService, initiallyVisible: Boolean) {
        try {
            val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            windowManager = wm

            val owner = OverlayLifecycleOwner()
            lifecycleOwner = owner

            val vm = ViewModelProvider(
                owner,
                ViewModelProvider.AndroidViewModelFactory.getInstance(service.application)
            )[PanelViewModel::class.java]
            viewModel = vm
            vm.connectToHomeAssistant()

            val layout = OverlayFrameLayout(service).apply {
                isFocusable = true
                isFocusableInTouchMode = true
                descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
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
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
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
                layout.requestFocus()
                Log.d(TAG, "DockOverlayManager shown (fresh)")
            } else {
                layout.visibility = View.GONE
                val params = createLayoutParams(focusable = false)
                wm.addView(layout, params)
                owner.pause()
                Log.d(TAG, "DockOverlayManager pre-warmed in background")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing overlay window", e)
            teardown()
        }
    }

    fun handleBack() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post { handleBack() }
            return
        }
        val vm = viewModel
        if (vm != null) {
            if (vm.isReorderMode.value) {
                vm.exitReorderMode()
                return
            }
            if (vm.activeDialogEntity.value != null) {
                vm.closeEntityDialog()
                return
            }
        }
        hide()
    }

    fun hide() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post { hide() }
            return
        }
        val layout = rootLayout ?: return
        val wm = windowManager ?: return

        try {
            if (layout.isAttachedToWindow) {
                layout.visibility = View.GONE
                wm.updateViewLayout(layout, createLayoutParams(focusable = false))
                lifecycleOwner?.pause()
                viewModel?.closeEntityDialog()
                Log.d(TAG, "DockOverlayManager hidden (kept warm)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error hiding overlay", e)
            teardown()
        }
    }

    fun teardown() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post { teardown() }
            return
        }
        try {
            val wm = windowManager
            val layout = rootLayout
            if (wm != null && layout != null && layout.isAttachedToWindow) {
                layout.onBackAction = null
                wm.removeViewImmediate(layout)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error tearing down overlay", e)
        } finally {
            rootLayout = null
            composeView = null
            lifecycleOwner?.destroy()
            lifecycleOwner = null
            viewModel = null
            windowManager = null
            Log.d(TAG, "DockOverlayManager torn down")
        }
    }
}
