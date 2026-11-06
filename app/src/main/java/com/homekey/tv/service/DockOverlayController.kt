package com.homekey.tv.service

import kotlinx.coroutines.flow.StateFlow

interface DockOverlayController {
    val isShowing: StateFlow<Boolean>
    val isServiceAttached: Boolean
    fun show(): Boolean
    fun hide()
    fun toggle()
    fun handleBack(): Boolean
    fun prewarm()
    fun teardown()
    fun attachService(service: RemoteButtonRemapService)
    fun detachService()

    companion object {
        @Volatile
        private var instance: DockOverlayController? = null

        fun getInstance(): DockOverlayController {
            return instance ?: synchronized(this) {
                instance ?: error("DockOverlayController not initialized")
            }
        }

        fun initialize(controller: DockOverlayController) {
            instance = controller
        }
    }
}
