package com.homekey.tv.service

import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Pure policy engine governing quick-access dock visibility, debounce timing,
 * prewarm scheduling, lifecycle transitions, and authoritative back navigation.
 */
class DockOverlayStateEngine(
    private val host: WindowOverlayHost,
    private val scheduler: TimeScheduler
) {

    private val lock = Any()

    private val _state = MutableStateFlow<DockOverlayState>(DockOverlayState.Unattached)
    val state: StateFlow<DockOverlayState> = _state.asStateFlow()

    val isShowing: StateFlow<Boolean> = object : StateFlow<Boolean> {
        override val replayCache: List<Boolean>
            get() = listOf(value)

        override val value: Boolean
            get() = _state.value is DockOverlayState.Showing

        override suspend fun collect(collector: FlowCollector<Boolean>): Nothing {
            var lastEmitted: Boolean? = null
            _state.collect { current ->
                val showing = current is DockOverlayState.Showing
                if (lastEmitted != showing) {
                    lastEmitted = showing
                    collector.emit(showing)
                }
            }
        }
    }

    private var _isServiceAttached = false
    val isServiceAttached: Boolean
        get() = synchronized(lock) { _isServiceAttached }

    private var lastToggleTime = -TOGGLE_DEBOUNCE_MS
    private val prewarmToken = Any()

    private fun updateState(newState: DockOverlayState) {
        _state.update { newState }
    }

    fun attachService() {
        synchronized(lock) {
            _isServiceAttached = true
            updateState(DockOverlayState.Hidden)
        }
    }

    fun detachService() {
        synchronized(lock) {
            teardown()
            _isServiceAttached = false
            updateState(DockOverlayState.Unattached)
        }
    }

    fun show(): Boolean {
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

    fun hide() {
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

    fun toggle() {
        synchronized(lock) {
            if (!_isServiceAttached) return
            val now = scheduler.currentTimeMs()
            if (now - lastToggleTime < TOGGLE_DEBOUNCE_MS) {
                return
            }
            if (_state.value is DockOverlayState.Showing) {
                hide()
            } else {
                show()
            }
        }
    }

    fun prewarm() {
        synchronized(lock) {
            if (!_isServiceAttached) return
            if (_state.value is DockOverlayState.Showing || _state.value is DockOverlayState.Prewarmed) return

            scheduler.schedule(PREWARM_DELAY_MS, prewarmToken) {
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

    fun teardown() {
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

    fun handleBack(): Boolean {
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

    companion object {
        const val TOGGLE_DEBOUNCE_MS = 350L
        const val PREWARM_DELAY_MS = 500L
    }
}
