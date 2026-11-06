package com.homekey.tv

import com.homekey.tv.service.DefaultDockOverlayController
import org.junit.Assert.*
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
}
