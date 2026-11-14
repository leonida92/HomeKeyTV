package com.homekey.tv.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Build
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.setViewTreeOnBackPressedDispatcherOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalSavedStateRegistryOwner
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.homekey.tv.ui.theme.HomeAssistantTVTheme

/**
 * Shared Android WindowManager + ComposeView + Lifecycle presentation host for system overlays.
 * Consolidates window context resolution, view-tree owner binding, layout updates, and teardown.
 */
internal class AndroidOverlayWindowHost(
    private val tag: String
) {
    private var windowManager: WindowManager? = null
    private var rootLayout: FrameLayout? = null
    private var composeView: ComposeView? = null
    private var lifecycleOwner: OverlayLifecycleOwner? = null

    val isAttached: Boolean
        get() = rootLayout?.isAttachedToWindow == true &&
            windowManager != null &&
            composeView != null

    fun resolveWindowContext(context: Context): Context {
        return if (context is AccessibilityService) {
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
    }

    fun attach(
        context: Context,
        layoutParams: (Context) -> WindowManager.LayoutParams,
        initiallyVisible: Boolean = true,
        interceptBack: Boolean = false,
        onBackPressed: (() -> Boolean)? = null,
        onOwnerCreated: ((OverlayLifecycleOwner) -> Unit)? = null,
        content: @Composable () -> Unit
    ): Boolean {
        return try {
            val uiContext = resolveWindowContext(context)
            val wm = (uiContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager) ?: return false
            windowManager = wm

            val owner = OverlayLifecycleOwner {
                onBackPressed?.invoke()
            }
            lifecycleOwner = owner
            onOwnerCreated?.invoke(owner)

            val layout = if (interceptBack) {
                OverlayFrameLayout(uiContext).apply {
                    isFocusable = false
                    isFocusableInTouchMode = false
                    descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        defaultFocusHighlightEnabled = false
                    }
                    onBackAction = onBackPressed
                }
            } else {
                FrameLayout(uiContext)
            }
            rootLayout = layout

            layout.setViewTreeLifecycleOwner(owner)
            layout.setViewTreeViewModelStoreOwner(owner)
            layout.setViewTreeSavedStateRegistryOwner(owner)
            layout.setViewTreeOnBackPressedDispatcherOwner(owner)

            val cv = ComposeView(uiContext).apply {
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
                            content()
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

            val params = layoutParams(uiContext)
            if (initiallyVisible) {
                layout.visibility = View.VISIBLE
                wm.addView(layout, params)
                owner.resume()
            } else {
                layout.visibility = View.GONE
                wm.addView(layout, params)
                owner.stop()
            }
            true
        } catch (e: Exception) {
            Log.e(tag, "Error attaching overlay window", e)
            detach()
            false
        }
    }

    fun show(
        layoutParams: WindowManager.LayoutParams,
        onBeforeFocus: (() -> Unit)? = null
    ) {
        val layout = rootLayout ?: return
        val wm = windowManager ?: return
        val cv = composeView ?: return
        if (!layout.isAttachedToWindow) return

        wm.updateViewLayout(layout, layoutParams)
        layout.visibility = View.VISIBLE
        lifecycleOwner?.resume()
        onBeforeFocus?.invoke()
        cv.requestFocus()
    }

    fun hide(layoutParams: WindowManager.LayoutParams) {
        val layout = rootLayout ?: return
        val wm = windowManager ?: return
        if (layout.isAttachedToWindow) {
            layout.visibility = View.GONE
            wm.updateViewLayout(layout, layoutParams)
            lifecycleOwner?.stop()
        }
    }

    fun requestComposeFocus() {
        composeView?.requestFocus()
    }

    fun detach() {
        try {
            val wm = windowManager
            val layout = rootLayout
            if (wm != null && layout != null && layout.isAttachedToWindow) {
                (layout as? OverlayFrameLayout)?.onBackAction = null
                wm.removeViewImmediate(layout)
            }
        } catch (e: Exception) {
            Log.e(tag, "Error detaching overlay window", e)
        } finally {
            rootLayout = null
            composeView = null
            lifecycleOwner?.destroy()
            lifecycleOwner = null
            windowManager = null
        }
    }
}
