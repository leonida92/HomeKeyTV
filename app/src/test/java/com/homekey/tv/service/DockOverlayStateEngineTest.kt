package com.homekey.tv.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DockOverlayStateEngineTest {

    private lateinit var scheduler: VirtualTimeScheduler
    private lateinit var host: FakeWindowOverlayHost
    private lateinit var engine: DefaultDockOverlayStateEngine

    @Before
    fun setup() {
        scheduler = VirtualTimeScheduler(currentTime = 1000L)
        host = FakeWindowOverlayHost()
        engine = DefaultDockOverlayStateEngine(host = host, scheduler = scheduler)
    }

    @Test
    fun initial_state_is_unattached_and_show_fails() {
        assertEquals(DockOverlayState.Unattached, engine.state.value)
        assertFalse(engine.isShowing.value)
        assertFalse(engine.isServiceAttached)

        val shown = engine.show()
        assertFalse(shown)
        assertEquals(DockOverlayState.Unattached, engine.state.value)
        assertEquals(0, host.showWindowCalls)
    }

    @Test
    fun attach_service_transitions_to_hidden() {
        engine.attachService()

        assertTrue(engine.isServiceAttached)
        assertEquals(DockOverlayState.Hidden, engine.state.value)
        assertFalse(engine.isShowing.value)
    }

    @Test
    fun detach_service_teardowns_host_and_transitions_to_unattached() {
        engine.attachService()
        engine.detachService()

        assertFalse(engine.isServiceAttached)
        assertEquals(DockOverlayState.Unattached, engine.state.value)
        assertEquals(1, host.teardownWindowCalls)
    }

    @Test
    fun show_when_attached_calls_host_and_transitions_to_showing() {
        engine.attachService()

        val result = engine.show()
        assertTrue(result)
        assertTrue(engine.isShowing.value)
        assertEquals(DockOverlayState.Showing, engine.state.value)
        assertEquals(1, host.showWindowCalls)
    }

    @Test
    fun show_when_already_showing_is_idempotent() {
        engine.attachService()
        assertTrue(engine.show())
        assertEquals(1, host.showWindowCalls)

        // Second show call
        val secondResult = engine.show()
        assertTrue(secondResult)
        assertEquals(1, host.showWindowCalls) // No duplicate host invocation
        assertEquals(DockOverlayState.Showing, engine.state.value)
    }

    @Test
    fun hide_when_showing_calls_host_and_transitions_to_prewarmed() {
        engine.attachService()
        engine.show()

        engine.hide()
        assertFalse(engine.isShowing.value)
        assertEquals(DockOverlayState.Prewarmed, engine.state.value)
        assertEquals(1, host.hideWindowCalls)
    }

    @Test
    fun hide_when_not_showing_is_noop() {
        engine.attachService()
        engine.hide()

        assertEquals(0, host.hideWindowCalls)
        assertEquals(DockOverlayState.Hidden, engine.state.value)
    }

    @Test
    fun hide_rolls_back_to_hidden_and_teardowns_when_host_hide_fails() {
        engine.attachService()
        engine.show()

        host.hideWindowResult = false
        engine.hide()

        assertFalse(engine.isShowing.value)
        assertEquals(DockOverlayState.Hidden, engine.state.value)
        assertEquals(1, host.teardownWindowCalls)
    }

    @Test
    fun show_rolls_back_and_teardowns_when_host_show_fails() {
        engine.attachService()
        host.showWindowResult = false

        val result = engine.show()
        assertFalse(result)
        assertFalse(engine.isShowing.value)
        assertEquals(DockOverlayState.Hidden, engine.state.value)
        assertEquals(1, host.teardownWindowCalls)
    }

    @Test
    fun toggle_when_hidden_shows_overlay() {
        engine.attachService()

        engine.toggle()
        assertTrue(engine.isShowing.value)
        assertEquals(DockOverlayState.Showing, engine.state.value)
        assertEquals(1, host.showWindowCalls)
    }

    @Test
    fun toggle_when_showing_hides_overlay() {
        engine.attachService()
        engine.show()

        // Advance time past debounce of the initial show
        scheduler.advanceTimeBy(400L)
        engine.toggle()

        assertFalse(engine.isShowing.value)
        assertEquals(DockOverlayState.Prewarmed, engine.state.value)
        assertEquals(1, host.hideWindowCalls)
    }

    @Test
    fun toggle_within_debounce_window_is_ignored() {
        engine.attachService()

        // First toggle at t = 1000L
        engine.toggle()
        assertTrue(engine.isShowing.value)
        assertEquals(1, host.showWindowCalls)

        // Rapid toggle at t = 1200L (200ms < 350ms debounce)
        scheduler.advanceTimeBy(200L)
        engine.toggle()

        // State remains showing, hide was not called
        assertTrue(engine.isShowing.value)
        assertEquals(0, host.hideWindowCalls)

        // Advance past debounce window (total 400ms > 350ms)
        scheduler.advanceTimeBy(200L)
        engine.toggle()

        // Now toggle executes
        assertFalse(engine.isShowing.value)
        assertEquals(1, host.hideWindowCalls)
    }

    @Test
    fun prewarm_schedules_delayed_prewarmWindow_and_transitions_to_prewarmed() {
        engine.attachService()

        engine.prewarm()
        // Immediately after prewarm call, window is not yet prewarmed
        assertEquals(0, host.prewarmWindowCalls)
        assertEquals(DockOverlayState.Hidden, engine.state.value)

        // Advance past 500ms delay
        scheduler.advanceTimeBy(500L)
        assertEquals(1, host.prewarmWindowCalls)
        assertEquals(DockOverlayState.Prewarmed, engine.state.value)
        assertFalse(engine.isShowing.value)
    }

    @Test
    fun show_cancels_pending_prewarm_task() {
        engine.attachService()

        engine.prewarm()
        // 200ms in, user invokes show()
        scheduler.advanceTimeBy(200L)
        val shown = engine.show()

        assertTrue(shown)
        assertEquals(DockOverlayState.Showing, engine.state.value)
        assertEquals(1, host.showWindowCalls)
        assertEquals(0, host.prewarmWindowCalls)

        // Advance past original 500ms mark
        scheduler.advanceTimeBy(500L)
        // prewarmWindow was never invoked
        assertEquals(0, host.prewarmWindowCalls)
    }

    @Test
    fun prewarm_when_already_prewarmed_or_showing_is_noop() {
        engine.attachService()

        // Case 1: already prewarmed
        engine.prewarm()
        scheduler.advanceTimeBy(500L)
        assertEquals(1, host.prewarmWindowCalls)

        engine.prewarm()
        scheduler.advanceTimeBy(500L)
        assertEquals(1, host.prewarmWindowCalls) // No extra call

        // Case 2: already showing
        scheduler.advanceTimeBy(500L)
        engine.show()
        engine.prewarm()
        scheduler.advanceTimeBy(500L)
        assertEquals(1, host.prewarmWindowCalls)
    }

    @Test
    fun handleBack_dismisses_active_modal_first_without_closing_dock() {
        engine.attachService()
        engine.show()
        host.dismissActiveModalResult = true

        val handled = engine.handleBack()
        assertTrue(handled)
        assertEquals(1, host.dismissActiveModalCalls)
        // Dock should still be showing
        assertTrue(engine.isShowing.value)
        assertEquals(0, host.hideWindowCalls)
    }

    @Test
    fun handleBack_hides_dock_when_no_active_modal() {
        engine.attachService()
        engine.show()
        host.dismissActiveModalResult = false

        val handled = engine.handleBack()
        assertTrue(handled)
        assertEquals(1, host.dismissActiveModalCalls)
        // Dock is dismissed
        assertFalse(engine.isShowing.value)
        assertEquals(1, host.hideWindowCalls)
    }

    @Test
    fun handleBack_returns_false_when_dock_not_showing() {
        engine.attachService()
        host.dismissActiveModalResult = true // even if modal was somehow open, dock not showing means do not intercept

        val handled = engine.handleBack()
        assertFalse(handled)
        assertEquals(0, host.dismissActiveModalCalls)
        assertEquals(0, host.hideWindowCalls)
    }
}
