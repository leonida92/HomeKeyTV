package com.homekey.tv.service

import kotlinx.coroutines.flow.StateFlow

interface DockOverlayStateEngine {
    val state: StateFlow<DockOverlayState>
    val isShowing: StateFlow<Boolean>
    val isServiceAttached: Boolean

    fun attachService()
    fun detachService()
    fun show(): Boolean
    fun hide()
    fun toggle()
    fun prewarm()
    fun teardown()
    fun handleBack(): Boolean

    companion object {
        const val TOGGLE_DEBOUNCE_MS = 350L
        const val PREWARM_DELAY_MS = 500L
    }
}
