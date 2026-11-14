package com.homekey.tv.service

import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import com.homekey.tv.ui.SettingsActivity
import com.homekey.tv.ui.panel.DockOverlayScreen
import com.homekey.tv.viewmodel.PanelViewModel
import kotlinx.coroutines.flow.StateFlow
import java.lang.ref.WeakReference
import java.util.concurrent.FutureTask

/**
 * Android adapter implementing [DockOverlayController] and [WindowOverlayHost].
 * Delegates all state, debounce timing, and back navigation decisions to [DockOverlayStateEngine],
 * and handles Android WindowManager, ComposeView, and Lifecycle mechanics via [AndroidOverlayWindowHost].
 */
class DefaultDockOverlayController(
    scheduler: TimeScheduler? = null,
    host: WindowOverlayHost? = null
) : DockOverlayController, WindowOverlayHost {

    private val tag = "DefaultDockOverlayController"

    private val timeScheduler: TimeScheduler by lazy {
        scheduler ?: Looper.getMainLooper()?.let { AndroidTimeScheduler(Handler(it)) }
            ?: object : TimeScheduler {
                override fun currentTimeMs(): Long = System.currentTimeMillis()
                override fun schedule(delayMs: Long, token: Any, action: () -> Unit) {
                    error("Main Looper unavailable; inject a TimeScheduler to schedule delayed tasks in tests")
                }
                override fun cancel(token: Any) {}
            }
    }

    private val overlayHost: WindowOverlayHost = host ?: this

    private val stateEngine: DockOverlayStateEngine by lazy {
        DockOverlayStateEngine(overlayHost, timeScheduler)
    }

    private val windowHost = AndroidOverlayWindowHost(tag)

    override val isShowing: StateFlow<Boolean> get() = stateEngine.isShowing
    override val isServiceAttached: Boolean get() = stateEngine.isServiceAttached

    private var serviceRef: WeakReference<RemoteButtonRemapService>? = null
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

    private fun <T> runOnMainBlocking(action: () -> T): T {
        val mainLooper = try {
            Looper.getMainLooper()
        } catch (_: Exception) {
            null
        }
        val currentLooper = try {
            Looper.myLooper()
        } catch (_: Exception) {
            null
        }
        if (mainLooper == null || currentLooper == mainLooper) {
            return action()
        }
        val task = FutureTask(action)
        val posted = try {
            Handler(mainLooper).post(task)
        } catch (_: Exception) {
            false
        }
        return if (posted) {
            task.get()
        } else {
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

    override fun show(): Boolean = runOnMainBlocking { stateEngine.show() }

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

    override fun handleBack(): Boolean = runOnMainBlocking { stateEngine.handleBack() }

    // --- WindowOverlayHost Seam Implementation ---

    override fun dismissActiveModal(): Boolean {
        return viewModel?.handleBack() ?: false
    }

    override fun showWindow(): Boolean {
        val service = serviceRef?.get() ?: run {
            Log.w(tag, "showWindow: Cannot show overlay, RemoteButtonRemapService is not attached")
            return false
        }
        return if (windowHost.isAttached) {
            try {
                windowHost.show(createLayoutParams(focusable = true)) {
                    viewModel?.notifyOverlayShown()
                }
                Log.d(tag, "Dock overlay shown (warm instant)")
                true
            } catch (e: Exception) {
                Log.e(tag, "Error showing warm overlay view, re-initializing", e)
                teardownWindow()
                initWindow(service, initiallyVisible = true)
            }
        } else {
            initWindow(service, initiallyVisible = true)
        }
    }

    override fun hideWindow(): Boolean {
        if (!windowHost.isAttached) return true
        return try {
            windowHost.hide(createLayoutParams(focusable = false))
            viewModel?.closeEntityDialog()
            viewModel?.exitReorderMode()
            Log.d(tag, "Dock overlay hidden (stopped & kept warm)")
            true
        } catch (e: Exception) {
            Log.e(tag, "Error hiding overlay", e)
            teardownWindow()
            false
        }
    }

    override fun prewarmWindow(): Boolean {
        val service = serviceRef?.get() ?: return false
        if (windowHost.isAttached) {
            return true
        }
        return initWindow(service, initiallyVisible = false)
    }

    override fun teardownWindow() {
        windowHost.detach()
        viewModel = null
        Log.d(tag, "Dock overlay torn down")
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
        var createdViewModel: PanelViewModel? = null
        val attached = windowHost.attach(
            context = service,
            layoutParams = { createLayoutParams(focusable = initiallyVisible) },
            initiallyVisible = initiallyVisible,
            interceptBack = true,
            onBackPressed = { handleBack() },
            onOwnerCreated = { owner ->
                val vm = ViewModelProvider(
                    owner,
                    ViewModelProvider.AndroidViewModelFactory.getInstance(service.application)
                )[PanelViewModel::class.java]
                viewModel = vm
                createdViewModel = vm
                vm.connectToHomeAssistant()
            }
        ) {
            val vm = createdViewModel ?: return@attach
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = androidx.compose.ui.graphics.Color.Transparent
            ) {
                val dockConfig by vm.dockConfig.collectAsState()
                DockOverlayScreen(
                    viewModel = vm,
                    layoutPosition = dockConfig.layout,
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
        if (!attached) {
            viewModel = null
            return false
        }
        if (initiallyVisible) {
            createdViewModel?.notifyOverlayShown()
            windowHost.requestComposeFocus()
            Log.d(tag, "Dock overlay shown (fresh)")
        } else {
            Log.d(tag, "Dock overlay pre-warmed in background")
        }
        return true
    }
}
