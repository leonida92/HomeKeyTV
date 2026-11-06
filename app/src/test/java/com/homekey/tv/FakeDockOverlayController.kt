package com.homekey.tv

import com.homekey.tv.service.DockOverlayController
import com.homekey.tv.service.RemoteButtonRemapService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class FakeDockOverlayController : DockOverlayController {
    private val _isShowing = MutableStateFlow(false)
    override val isShowing: StateFlow<Boolean> = _isShowing.asStateFlow()

    private var _isServiceAttached = false
    override val isServiceAttached: Boolean get() = _isServiceAttached

    var showCount = 0
    var hideCount = 0
    var toggleCount = 0
    var handleBackResult = false

    override fun show(): Boolean {
        showCount++
        _isShowing.update { true }
        return true
    }

    override fun hide() {
        hideCount++
        _isShowing.update { false }
    }

    override fun toggle() {
        toggleCount++
        _isShowing.update { !it }
    }

    override fun handleBack(): Boolean {
        if (_isShowing.value) {
            _isShowing.update { false }
            return true
        }
        return handleBackResult
    }

    override fun prewarm() {}

    override fun teardown() {
        _isShowing.update { false }
        _isServiceAttached = false
    }

    override fun attachService(service: RemoteButtonRemapService) {
        _isServiceAttached = true
    }

    override fun detachService() {
        _isServiceAttached = false
    }
}
