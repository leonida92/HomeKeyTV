package com.homekey.tv

import com.homekey.tv.service.DefaultDockOverlayController
import com.homekey.tv.service.DockOverlayStateEngine
import com.homekey.tv.service.FakeWindowOverlayHost
import com.homekey.tv.service.RemoteButtonRemapService
import com.homekey.tv.service.VirtualTimeScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DockOverlayControllerTest {

    @Test
    fun defaultState_isNotShowing_andNotAttached() {
        val controller = DefaultDockOverlayController()
        assertFalse(controller.isShowing.value)
        assertFalse(controller.isServiceAttached)
    }

    @Test
    fun show_withoutAttachedService_returnsFalse() {
        val controller = DefaultDockOverlayController()
        val result = controller.show()
        assertFalse(result)
        assertFalse(controller.isShowing.value)
    }

    @Test
    fun handleBack_whenNotShowing_returnsFalse() {
        val controller = DefaultDockOverlayController()
        val handled = controller.handleBack()
        assertFalse(handled)
    }

    @Test
    fun teardown_resetsShowingState() {
        val controller = DefaultDockOverlayController()
        controller.teardown()
        assertFalse(controller.isShowing.value)
        assertFalse(controller.isServiceAttached)
    }

    @Test
    fun delegatesLifecycleAndWindowOperationsThroughStateEngineToHost() {
        val scheduler = VirtualTimeScheduler(currentTime = 1000L)
        val fakeHost = FakeWindowOverlayHost()
        val controller = DefaultDockOverlayController(
            scheduler = scheduler,
            host = fakeHost
        )
        val service = RemoteButtonRemapService()

        controller.attachService(service)
        assertTrue(controller.isServiceAttached)
        assertFalse(controller.isShowing.value)

        // Prewarm schedules via injected TimeScheduler instead of silently swallowing
        controller.prewarm()
        assertEquals(0, fakeHost.prewarmWindowCalls)
        scheduler.advanceTimeBy(DockOverlayStateEngine.PREWARM_DELAY_MS)
        assertEquals(1, fakeHost.prewarmWindowCalls)
        assertFalse(controller.isShowing.value)

        // Show transitions to showing
        assertTrue(controller.show())
        assertTrue(controller.isShowing.value)
        assertEquals(1, fakeHost.showWindowCalls)

        // Handle back dismisses active modal first
        fakeHost.dismissActiveModalResult = true
        assertTrue(controller.handleBack())
        assertEquals(1, fakeHost.dismissActiveModalCalls)
        assertTrue(controller.isShowing.value)

        // Handle back hides dock when no modal is open
        fakeHost.dismissActiveModalResult = false
        assertTrue(controller.handleBack())
        assertEquals(2, fakeHost.dismissActiveModalCalls)
        assertFalse(controller.isShowing.value)
        assertEquals(1, fakeHost.hideWindowCalls)

        // Toggle respects debounce window and then opens
        scheduler.advanceTimeBy(DockOverlayStateEngine.TOGGLE_DEBOUNCE_MS)
        controller.toggle()
        assertTrue(controller.isShowing.value)
        assertEquals(2, fakeHost.showWindowCalls)

        // Teardown and detach clean up via host
        controller.teardown()
        assertFalse(controller.isShowing.value)
        assertEquals(1, fakeHost.teardownWindowCalls)

        controller.detachService()
        assertFalse(controller.isServiceAttached)
        assertEquals(2, fakeHost.teardownWindowCalls)
    }
}
