package com.homekey.tv.service

/**
 * Seam interface abstracting Android WindowManager and ComposeView presentation mechanics
 * away from the pure DockOverlayStateEngine.
 */
interface WindowOverlayHost {
    fun showWindow(): Boolean
    fun hideWindow(): Boolean
    fun prewarmWindow(): Boolean
    fun teardownWindow()
    fun dismissActiveModal(): Boolean
}
