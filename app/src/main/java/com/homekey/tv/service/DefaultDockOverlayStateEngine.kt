package com.homekey.tv.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class DefaultDockOverlayStateEngine(
    private val host: WindowOverlayHost,
    private val scheduler: TimeScheduler
) : DockOverlayStateEngine {

    private val lock = Any()

    private val _state = MutableStateFlow<DockOverlayState>(DockOverlayState.Unattached)
    override val state: StateFlow<DockOverlayState> = _state.asStateFlow()

    private val _isShowing = MutableStateFlow(false)
    override val isShowing: StateFlow<Boolean> = _isShowing.asStateFlow()

    private var _isServiceAttached = false
    override val isServiceAttached: Boolean
        get() = synchronized(lock) { _isServiceAttached }

    private var lastToggleTime = -DockOverlayStateEngine.TOGGLE_DEBOUNCE_MS
    private val prewarmToken = Any()

    private fun updateState(newState: DockOverlayState) {
        _state.update { newState }
        _isShowing.update { newState is DockOverlayState.Showing }
    }

    override fun attachService() {
        synchronized(lock) {
            _isServiceAttached = true
            updateState(DockOverlayState.Hidden)
        }
    }

    override fun detachService() {
        synchronized(lock) {
            teardown()
            _isServiceAttached = false
            updateState(DockOverlayState.Unattached)
        }
    }

    override fun show(): Boolean {
        synchronized(lock) {
            if (!_isServiceAttached) return false
            if (_state.value is DockOverlayState.Showing) return true

            scheduler.cancel(prewarmToken)
            lastToggleTime = scheduler.currentTimeMs()
            return try {
                val success = host.showWindow()
                if (success) {
                    updateState(DockOverlayState.Showing)
                    true
                } else {
                    teardown()
                    false
                }
            } catch (_: Exception) {
                teardown()
                false
            }
        }
    }

    override fun hide() {
        synchronized(lock) {
            if (_state.value !is DockOverlayState.Showing) return
            lastToggleTime = scheduler.currentTimeMs()
            try {
                val success = host.hideWindow()
                if (success) {
                    updateState(DockOverlayState.Prewarmed)
                } else {
                    teardown()
                }
            } catch (_: Exception) {
                teardown()
            }
        }
    }

    override fun toggle() {
        synchronized(lock) {
            if (!_isServiceAttached) return
            val now = scheduler.currentTimeMs()
            if (now - lastToggleTime < DockOverlayStateEngine.TOGGLE_DEBOUNCE_MS) {
                return
            }
            if (_state.value is DockOverlayState.Showing) {
                hide()
            } else {
                show()
            }
        }
    }

    override fun prewarm() {
        synchronized(lock) {
            if (!_isServiceAttached) return
            if (_state.value is DockOverlayState.Showing || _state.value is DockOverlayState.Prewarmed) return

            scheduler.schedule(DockOverlayStateEngine.PREWARM_DELAY_MS, prewarmToken) {
                synchronized(lock) {
                    if (!_isServiceAttached) return@schedule
                    if (_state.value !is DockOverlayState.Hidden) return@schedule
                    try {
                        val success = host.prewarmWindow()
                        if (success) {
                            updateState(DockOverlayState.Prewarmed)
                        }
                    } catch (_: Exception) {
                        teardown()
                    }
                }
            }
        }
    }

    override fun teardown() {
        synchronized(lock) {
            scheduler.cancel(prewarmToken)
            try {
                host.teardownWindow()
            } catch (_: Exception) {}
            if (_isServiceAttached) {
                updateState(DockOverlayState.Hidden)
            } else {
                updateState(DockOverlayState.Unattached)
            }
        }
    }

    override fun handleBack(): Boolean {
        synchronized(lock) {
            if (_state.value !is DockOverlayState.Showing) {
                return false
            }
            if (host.dismissActiveModal()) {
                return true
            }
            hide()
            return true
        }
    }
}
